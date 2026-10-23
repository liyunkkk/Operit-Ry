package com.ai.assistance.operit.api.chat.enhance

import com.ai.assistance.operit.core.agent.AgentRunObservers
import android.content.Context
import android.os.SystemClock
import com.ai.assistance.operit.core.config.SystemToolPrompts
import com.ai.assistance.operit.R
import com.ai.assistance.operit.api.chat.protocol.ExecutableToolProtocolParser
import com.ai.assistance.operit.core.agent.SubagentToolPolicy
import com.ai.assistance.operit.core.agent.collaboration.CollaborationTools
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.core.tools.AIToolHandler
import com.ai.assistance.operit.core.tools.AIToolHookDecision
import com.ai.assistance.operit.core.tools.JsPackageToolExecutorMarker
import com.ai.assistance.operit.core.tools.PermissionReviewInternalTools
import com.ai.assistance.operit.core.tools.StringResultData
import com.ai.assistance.operit.core.tools.ToolExecutor
import com.ai.assistance.operit.core.tools.ToolExecutionLimits
import com.ai.assistance.operit.core.tools.ToolPackage
import com.ai.assistance.operit.core.tools.ToolExecutionTimingRepository
import com.ai.assistance.operit.core.tools.ToolErrorRepository
import com.ai.assistance.operit.core.tools.createToolErrorRecord
import com.ai.assistance.operit.core.tools.ToolParameterObservation
import com.ai.assistance.operit.core.tools.ToolCallRepairRouter
import com.ai.assistance.operit.core.tools.ToolCallRepairLogger
import com.ai.assistance.operit.core.tools.climode.CliToolModeSupport
import com.ai.assistance.operit.core.tools.climode.ToolExposureMode
import com.ai.assistance.operit.data.model.ToolInvocation
import com.ai.assistance.operit.data.model.ToolExecutionState
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.core.tools.packTool.PackageManager
import com.ai.assistance.operit.util.stream.StreamCollector
import com.ai.assistance.operit.data.preferences.CharacterCardToolAccessResolver
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolParameter
import com.ai.assistance.operit.ui.permissions.PermissionLevel
import com.ai.assistance.operit.ui.permissions.AUTOMATIC_REVIEW_CANCEL_PREFIX
import com.ai.assistance.operit.ui.permissions.PermissionReviewCircuitBreaker
import com.ai.assistance.operit.ui.permissions.PermissionReviewEventRepository
import com.ai.assistance.operit.ui.permissions.PermissionReviewStatus
import com.ai.assistance.operit.ui.permissions.ToolPermissionDecision
import com.ai.assistance.operit.ui.permissions.ToolPermissionDenialSource
import com.ai.assistance.operit.ui.permissions.ToolPermissionSystem
import com.ai.assistance.operit.ui.permissions.resolveApprovalDecisionWithPermanentOverride
import com.ai.assistance.operit.util.ChatUtils
import com.ai.assistance.operit.util.LocaleUtils
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import org.json.JSONObject

/** Utility class for managing tool executions */
object ToolExecutionManager {
    private const val TAG = "ToolExecutionManager"
    private const val PACKAGE_PROXY_TOOL_NAME = "package_proxy"
    private const val PACKAGE_CALLER_NAME_PARAM = "__operit_package_caller_name"
    private const val PACKAGE_CHAT_ID_PARAM = "__operit_package_chat_id"
    private const val PACKAGE_CALLER_CARD_ID_PARAM = "__operit_package_caller_card_id"
    private const val SUBAGENT_EXACT_REPEAT_REVIEW_THRESHOLD = 3
    private val toolRuntimeContextThreadLocal = ThreadLocal<ToolRuntimeContext?>()
    private val declaredSystemParameters by lazy {
        // Include the full file-tool declaration, not the capability-filtered model prompt.
        (SystemToolPrompts.getAllCategoriesEn().flatMap { it.tools } +
            SystemToolPrompts.fileSystemTools.tools + CliToolModeSupport.buildCliPublicToolPrompts(true))
            .associate { it.name to it.parametersStructured?.map { parameter -> parameter.name }?.toSet() }
    }

    internal fun observeToolParameterNames(tool: AITool, declaration: () -> Set<String>?) {
        currentToolRuntimeContext()?.parameterObserver?.inspect(tool, declaration)
    }

    internal fun inspectToolParameters(tool: AITool, executor: ToolExecutor) {
        observeToolParameterNames(tool) {
            executor.parameterNames(tool) ?: if (':' !in tool.name) declaredSystemParameters[tool.name] else null
        }
    }

    // System-reserved package context parameters. They are only ever set by the host: values the
    // model supplies (at the proxy top level or inside params) must not reach a package, or the
    // reviewed parameter set would differ from the executed one.
    private val reservedPackageContextParams =
        setOf(PACKAGE_CALLER_NAME_PARAM, PACKAGE_CHAT_ID_PARAM, PACKAGE_CALLER_CARD_ID_PARAM)

    private data class ExactToolInstruction(
        val name: String,
        val orderedParameters: List<Pair<String, String>>,
    )

    /**
     * Per model turn state. Equality is deliberately exact: tool name, parameter order, names and
     * raw values must all match. Similar or normalized instructions never share a count.
     */
    class SubagentToolLoopGuard {
        private var previous: ExactToolInstruction? = null
        private var consecutiveCount: Int = 0

        private fun fingerprint(tool: AITool): ExactToolInstruction =
            ExactToolInstruction(
                name = tool.name,
                orderedParameters = tool.parameters.map { it.name to it.value },
            )

        @Synchronized
        internal fun record(tool: AITool): Int {
            val current = fingerprint(tool)
            if (current == previous) {
                consecutiveCount += 1
            } else {
                previous = current
                consecutiveCount = 1
            }
            return consecutiveCount
        }

        /**
         * Simulates a batch without mutating turn state so calls before the first review boundary
         * can finish before the third identical call is paused.
         */
        @Synchronized
        internal fun firstReviewIndex(tools: List<AITool>, threshold: Int): Int {
            var simulatedPrevious = previous
            var simulatedCount = consecutiveCount
            tools.forEachIndexed { index, tool ->
                val current = fingerprint(tool)
                if (current == simulatedPrevious) {
                    simulatedCount += 1
                } else {
                    simulatedPrevious = current
                    simulatedCount = 1
                }
                if (simulatedCount >= threshold) {
                    return index
                }
            }
            return -1
        }
    }

    class SubagentLoopRejectedException(message: String) : IllegalStateException(message)

    data class ToolRuntimeContext(
        val callerName: String? = null,
        val callerCardId: String? = null,
        val callerChatId: String? = null,
        val toolExposureMode: ToolExposureMode = ToolExposureMode.FULL,
        val conversationLabel: String? = null,
        val workspacePath: String? = null,
        val workspaceEnv: String? = null,
        val parentModelConfigId: String? = null,
        val parentModelIndex: Int? = null,
        val parentModelSupportsVision: Boolean = false,
        val imageRecognitionModelAvailable: Boolean = false,
        val isSubagent: Boolean = false,
        val callId: String? = null,
        val invocationIndex: Int? = null,
        val timingScopeId: String? = null,
        val batchPosition: Int = 1,
        val batchSize: Int = 1,
        val permissionCheckedToolName: String? = null,
        val parameterObserver: ToolParameterObservation? = null,
        val resolvedMemorySpaceId: String? = null,
        val dynamicUiRunId: String? = null,
    )

    internal class BoundedToolResultAccumulator(
        private val maxChars: Int = ToolExecutionLimits.MAX_FINAL_TOOL_RESULT_MESSAGE_CHARS,
    ) {
        private val combinedResult = StringBuilder()

        var resultCount: Int = 0
            private set

        var lastResultSuccess: Boolean? = null
            private set

        var lastResultError: String? = null
            private set

        /** 聚合过程中是否出现过 interruptTurn=true 的结果（任一结果置位即保留）。 */
        var anyInterruptTurn: Boolean = false
            private set

        fun add(result: ToolResult) {
            val resultText =
                (if (result.success) {
                    result.result.toString()
                } else {
                    listOf("Step error: ${result.error ?: "Unknown error"}", result.result.toString())
                        .filter { it.isNotBlank() }.joinToString("\n")
                }).trim()

            if (resultCount > 0) {
                appendBounded("\n")
            }
            appendBounded(resultText)
            resultCount += 1
            lastResultSuccess = result.success
            lastResultError = result.error?.take(maxChars)
            anyInterruptTurn = anyInterruptTurn || result.interruptTurn
        }

        fun isEmpty(): Boolean = resultCount == 0

        fun combinedResultText(): String = combinedResult.toString().trim()

        private fun appendBounded(value: String) {
            val remainingChars = maxChars - combinedResult.length
            if (remainingChars <= 0) return
            combinedResult.append(value, 0, minOf(value.length, remainingChars))
        }
    }

    private data class ResolvedToolTarget(
        val tool: AITool,
        val displayName: String
    )

    private fun ensureEndsWithNewline(content: String): String {
        return if (content.endsWith("\n")) content else "$content\n"
    }

    private fun ToolResult.withExecutionMetadata(
        invocation: ToolInvocation,
        state: ToolExecutionState,
        durationMs: Long? = null,
        isFinal: Boolean = true,
    ): ToolResult =
        copy(
            callId = invocation.callId,
            invocationIndex = invocation.invocationIndex.takeIf { it >= 0 },
            executionDurationMs = durationMs,
            executionState = state,
            isFinal = isFinal,
        )

    private suspend fun emitFinalResult(
        collector: StreamCollector<String>,
        result: ToolResult,
    ) {
        collector.emit(
            ensureEndsWithNewline(ConversationMarkupManager.formatToolResultForMessage(result))
        )
    }

    internal fun createCancelledToolResult(
        displayToolName: String,
        invocation: ToolInvocation,
        durationMs: Long,
        partialResultText: String,
    ): ToolResult =
        ToolResult(
            toolName = displayToolName,
            success = false,
            result = StringResultData(partialResultText),
            error = "Tool execution cancelled.",
        ).withExecutionMetadata(
            invocation = invocation,
            state = ToolExecutionState.COMPLETED,
            durationMs = durationMs,
        )

    internal fun reserveToolInvocationIndices(
        nextInvocationIndex: AtomicInteger,
        invocationCount: Int,
    ) {
        if (invocationCount > 0) {
            nextInvocationIndex.addAndGet(invocationCount)
        }
    }

    internal suspend fun countDisplayedToolInvocations(content: String): Int {
        return ExecutableToolProtocolParser.countCompleteToolInvocations(content)
    }

    private fun resolveToolTarget(tool: AITool): ResolvedToolTarget {
        if (tool.name != PACKAGE_PROXY_TOOL_NAME &&
            tool.name != CliToolModeSupport.PROXY_TOOL_NAME
        ) {
            return ResolvedToolTarget(tool = tool, displayName = tool.name)
        }

        val targetToolName = tool.parameters
            .firstOrNull { it.name == "tool_name" }
            ?.value
            ?.trim()
            .orEmpty()
        if (targetToolName.isBlank()) {
            return ResolvedToolTarget(tool = tool, displayName = tool.name)
        }

        val forwardedParameters = resolveProxyParameters(tool)
        return ResolvedToolTarget(
            tool = AITool(name = targetToolName, parameters = forwardedParameters),
            displayName = targetToolName
        )
    }

    private fun resolveDisplayToolName(tool: AITool): String {
        return resolveToolTarget(tool).displayName
    }

    /**
     * Immutable snapshot of everything needed to decide whether a proxy target is a real
     * JavaScript package tool and therefore eligible for host package-context injection. Taken
     * once per batch and reused by binding and post-review injection so the reviewed parameter
     * set equals the executed one even when package initialization fails transiently. A degraded
     * snapshot carries an empty catalog and null caller values, so it strips forged parameters
     * and injects nothing (fail closed).
     */
    private data class HostPackageContext(
        val jsPackages: Map<String, ToolPackage> = emptyMap(),
        val toolHandler: AIToolHandler,
        val callerName: String? = null,
        val callerChatId: String? = null,
        val callerCardId: String? = null,
    ) {
        val hasHostContext: Boolean
            get() =
                !callerName.isNullOrBlank() ||
                    !callerChatId.isNullOrBlank() ||
                    !callerCardId.isNullOrBlank()

        /**
         * A proxy target is a real JavaScript package tool only when the package actually
         * declares the target tool (in its base tools or one of its states) AND the executor
         * currently registered for the exact "package:tool" name is a marked JavaScript package
         * executor. MCP tools share the same "server:tool" namespace and are registered with an
         * unmarked MCP executor, so an MCP target is never misclassified as a package and never
         * receives internal conversation metadata. Rejecting per exact tool name (instead of per
         * prefix) keeps colliding JS/MCP prefixes from vetoing tools that genuinely belong to the
         * JavaScript package. Advice-only tools are never registered and therefore never
         * injected. A not-yet-registered tool fails closed (no injection).
         */
        fun isJsPackageTool(toolName: String): Boolean {
            val toolNameParts = toolName.split(':', limit = 2)
            if (toolNameParts.size != 2) {
                return false
            }
            val packageName = toolNameParts[0]
            val targetToolName = toolNameParts[1]
            val toolPackage = jsPackages[packageName] ?: return false
            val declaredExecutableTools =
                toolPackage.tools.filterNot { it.advice }.map { it.name } +
                    toolPackage.states.flatMap { state ->
                        state.tools.filterNot { it.advice }.map { it.name }
                    }
            if (!declaredExecutableTools.contains(targetToolName)) {
                return false
            }
            return toolHandler.getToolExecutor(toolName) is JsPackageToolExecutorMarker
        }
    }

    /**
     * Resolves the package snapshot and host caller values once for the whole batch. The lookup
     * is restricted to batches that actually need it (host context present AND a proxy call or a
     * qualified "server:tool" call) and guarded, so a failing package initialization can never
     * abort a tool turn that does not involve packages: binding degrades to stripping forged
     * reserved parameters without host injection. The snapshot is intentionally reused after
     * permission review so a transient failure cannot make the reviewed parameter set differ
     * from the executed one.
     */
    private fun resolveHostPackageContext(
        invocations: List<ToolInvocation>,
        packageManager: PackageManager,
        toolHandler: AIToolHandler,
        callerName: String?,
        callerChatId: String?,
        callerCardId: String?,
    ): HostPackageContext {
        val hostHasAny =
            !callerName.isNullOrBlank() ||
                !callerChatId.isNullOrBlank() ||
                !callerCardId.isNullOrBlank()
        if (!hostHasAny) {
            return HostPackageContext(toolHandler = toolHandler)
        }
        val needsPackageLookup =
            invocations.any { invocation ->
                val toolName = invocation.tool.name
                toolName == PACKAGE_PROXY_TOOL_NAME ||
                    toolName == CliToolModeSupport.PROXY_TOOL_NAME ||
                    toolName.contains(':')
            }
        if (!needsPackageLookup) {
            return HostPackageContext(toolHandler = toolHandler)
        }
        // Fail closed: if the package catalog lookup fails, the snapshot is unusable and binding
        // degrades to stripping forged reserved parameters without host injection.
        val jsPackages = runCatching { packageManager.getAvailablePackages() }.getOrNull()
        if (jsPackages == null) {
            return HostPackageContext(toolHandler = toolHandler)
        }
        return HostPackageContext(
            jsPackages = jsPackages.toMap(),
            toolHandler = toolHandler,
            callerName = callerName,
            callerChatId = callerChatId,
            callerCardId = callerCardId,
        )
    }

    internal fun currentToolRuntimeContext(): ToolRuntimeContext? =
        toolRuntimeContextThreadLocal.get()

    internal fun toolRuntimeContextElement(
        runtimeContext: ToolRuntimeContext? = currentToolRuntimeContext(),
    ): ThreadContextElement<ToolRuntimeContext?> =
        toolRuntimeContextThreadLocal.asContextElement(runtimeContext)

    private fun addPackageContextParamIfMissing(
        params: MutableList<ToolParameter>,
        name: String,
        value: String?
    ) {
        if (value.isNullOrBlank()) {
            return
        }
        if (params.any { it.name == name }) {
            return
        }
        params.add(ToolParameter(name, value))
    }

    private fun injectPackageCallContext(
        invocation: ToolInvocation,
        hostContext: HostPackageContext,
    ): ToolInvocation {
        val resolvedTargetTool = resolveToolTarget(invocation.tool).tool
        if (!hostContext.isJsPackageTool(resolvedTargetTool.name)) {
            return invocation
        }

        val updatedParams = invocation.tool.parameters.toMutableList()
        addPackageContextParamIfMissing(updatedParams, PACKAGE_CALLER_NAME_PARAM, hostContext.callerName)
        addPackageContextParamIfMissing(updatedParams, PACKAGE_CHAT_ID_PARAM, hostContext.callerChatId)
        addPackageContextParamIfMissing(updatedParams, PACKAGE_CALLER_CARD_ID_PARAM, hostContext.callerCardId)

        if (updatedParams.size == invocation.tool.parameters.size) {
            return invocation
        }

        return invocation.copy(
            tool = invocation.tool.copy(parameters = updatedParams)
        )
    }

    private fun getParameterValue(tool: AITool, name: String): String? {
        return tool.parameters.firstOrNull { it.name == name }?.value?.trim()
    }

    /**
     * Binds a proxy/package_proxy call's package context parameters before permission review and
     * execution, so the reviewed parameter set is exactly the executed one. The model can never
     * supply a reserved package context value: forged values are always stripped (top level and
     * inside params JSON). Host values are only injected when the target is an actual JavaScript
     * package; other proxy targets (for example MCP tools) must not receive internal
     * conversation metadata that could be forwarded to a third-party server.
     */
    private fun bindProxyContextParameters(
        tool: AITool,
        hostContext: HostPackageContext,
    ): AITool {
        if (tool.name != PACKAGE_PROXY_TOOL_NAME &&
            tool.name != CliToolModeSupport.PROXY_TOOL_NAME
        ) {
            return tool
        }
        val targetToolName = tool.parameters
            .firstOrNull { it.name == "tool_name" }
            ?.value
            ?.trim()
            .orEmpty()
        val isPackageTarget = hostContext.isJsPackageTool(targetToolName)
        val contextValues =
            mapOf(
                PACKAGE_CALLER_NAME_PARAM to hostContext.callerName,
                PACKAGE_CHAT_ID_PARAM to hostContext.callerChatId,
                PACKAGE_CALLER_CARD_ID_PARAM to hostContext.callerCardId,
            )
        val topLevelHasReserved =
            tool.parameters.any { parameter -> parameter.name in reservedPackageContextParams }
        val paramsParam = tool.parameters.firstOrNull { it.name == "params" }
        val paramsJson = paramsParam?.value?.trim().orEmpty()
        val paramsObject =
            if (paramsJson.isNotBlank()) runCatching { JSONObject(paramsJson) }.getOrNull() else null
        val jsonHasReserved =
            paramsObject != null &&
                paramsObject.keys().asSequence().any { it in reservedPackageContextParams }
        val hostHasAny = hostContext.hasHostContext
        if (!topLevelHasReserved && !jsonHasReserved && !(isPackageTarget && hostHasAny)) {
            return tool
        }

        val updated = tool.parameters.toMutableList()
        contextValues.forEach { (name, value) ->
            if (!isPackageTarget || value.isNullOrBlank()) {
                updated.removeAll { it.name == name }
            } else {
                val existingIndex = updated.indexOfFirst { it.name == name }
                if (existingIndex >= 0) {
                    updated[existingIndex] = ToolParameter(name, value)
                } else {
                    updated.add(ToolParameter(name, value))
                }
            }
        }
        if (paramsObject != null && (hostHasAny || jsonHasReserved)) {
            reservedPackageContextParams.forEach { name ->
                if (paramsObject.has(name)) paramsObject.remove(name)
            }
            if (isPackageTarget) {
                contextValues.forEach { (name, value) ->
                    if (!value.isNullOrBlank() && !paramsObject.has(name)) {
                        paramsObject.put(name, value)
                    }
                }
            }
            val paramsIndex = updated.indexOfFirst { it.name == "params" }
            if (paramsIndex >= 0) {
                updated[paramsIndex] = ToolParameter("params", paramsObject.toString())
            }
        }
        return tool.copy(parameters = updated)
    }

    private fun isInvocationAllowedForRoleCard(
        invocation: ToolInvocation,
        roleCardToolAccess: com.ai.assistance.operit.data.preferences.ResolvedCharacterCardToolAccess
    ): Boolean {
        val toolName = invocation.tool.name.trim()
        val resolvedTarget = resolveToolTarget(invocation.tool).tool

        return when {
            toolName == CliToolModeSupport.SEARCH_TOOL_NAME -> true

            toolName == CliToolModeSupport.PROXY_TOOL_NAME -> {
                isResolvedTargetAllowedForRoleCard(resolvedTarget, roleCardToolAccess)
            }

            toolName == "use_package" -> {
                if (!roleCardToolAccess.isBuiltinToolAllowed("use_package")) {
                    false
                } else {
                    val sourceName = getParameterValue(invocation.tool, "package_name").orEmpty()
                    sourceName.isBlank() || roleCardToolAccess.isExternalSourceAllowed(sourceName)
                }
            }

            toolName == PACKAGE_PROXY_TOOL_NAME -> {
                if (!roleCardToolAccess.isBuiltinToolAllowed("package_proxy")) {
                    false
                } else {
                    val resolvedTargetName = resolvedTarget.name.trim()
                    if (resolvedTargetName.isBlank() || !resolvedTargetName.contains(':')) {
                        true
                    } else {
                        isResolvedTargetAllowedForRoleCard(resolvedTarget, roleCardToolAccess)
                    }
                }
            }

            toolName.contains(':') -> {
                val sourceName = toolName.substringBefore(':').trim()
                sourceName.isBlank() || roleCardToolAccess.isExternalSourceAllowed(sourceName)
            }

            else -> roleCardToolAccess.isBuiltinToolAllowed(toolName)
        }
    }

    private fun buildRoleCardDeniedResult(
        context: Context,
        invocation: ToolInvocation
    ): ToolResult {
        return ToolResult(
            toolName = resolveDisplayToolName(invocation.tool),
            success = false,
            result = StringResultData(""),
            error = context.getString(R.string.character_card_tool_access_denied_runtime)
        )
    }

    private fun isEnglishLanguage(context: Context): Boolean {
        return LocaleUtils.getCurrentLanguage(context).lowercase().startsWith("en")
    }

    private fun buildToolExposureDeniedResult(
        context: Context,
        invocation: ToolInvocation,
        toolExposureMode: ToolExposureMode
    ): ToolResult? {
        val toolName = invocation.tool.name.trim()
        if (PermissionReviewInternalTools.bypassesPermissionCheck(toolName)) {
            return null
        }
        val useEnglish = isEnglishLanguage(context)
        val errorMessage =
            when {
                toolExposureMode == ToolExposureMode.CLI &&
                    !CliToolModeSupport.isCliPublicTool(toolName) -> {
                    CliToolModeSupport.buildCliTopLevelRestrictionErrorMessage(
                        attemptedToolName = resolveDisplayToolName(invocation.tool),
                        useEnglish = useEnglish
                    )
                }

                toolExposureMode == ToolExposureMode.FULL &&
                    CliToolModeSupport.isCliPublicTool(toolName) -> {
                    CliToolModeSupport.buildCliModeUnavailableMessage(useEnglish)
                }

                else -> null
            } ?: return null

        val resultToolName =
            if (toolExposureMode == ToolExposureMode.CLI &&
                !CliToolModeSupport.isCliPublicTool(toolName)
            ) {
                resolveDisplayToolName(invocation.tool)
            } else {
                toolName
            }

        return ToolResult(
            toolName = resultToolName,
            success = false,
            result = StringResultData(""),
            error = errorMessage
        )
    }

    private fun isResolvedTargetAllowedForRoleCard(
        resolvedTarget: AITool,
        roleCardToolAccess: com.ai.assistance.operit.data.preferences.ResolvedCharacterCardToolAccess
    ): Boolean {
        val resolvedTargetName = resolvedTarget.name.trim()
        if (resolvedTargetName.isBlank()) {
            return true
        }

        val usePackageSourceName =
            if (resolvedTargetName == "use_package") {
                getParameterValue(resolvedTarget, "package_name")
            } else {
                null
            }

        return CliToolModeSupport.isToolNameAllowedForRoleCard(
            toolName = resolvedTargetName,
            usePackageSourceName = usePackageSourceName,
            roleCardToolAccess = roleCardToolAccess
        )
    }

    private fun resolveProxyParameters(tool: AITool): List<ToolParameter> {
        val paramsRaw = tool.parameters
            .firstOrNull { it.name == "params" }
            ?.value
            ?.trim()
            .orEmpty()
        if (paramsRaw.isBlank()) {
            return emptyList()
        }

        val paramsObject = runCatching { JSONObject(paramsRaw) }.getOrNull() ?: return emptyList()
        val forwardedParameters = mutableListOf<ToolParameter>()
        val keys = paramsObject.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = paramsObject.opt(key)
            val valueString = when (value) {
                null, JSONObject.NULL -> "null"
                is String -> value
                else -> value.toString()
            }
            forwardedParameters.add(ToolParameter(name = key, value = valueString))
        }
        return forwardedParameters
    }

    /**
     * 从 AI 响应中提取工具调用。
     * @param response AI 的响应字符串。
     * @return 检测到的工具调用列表。
     */
    suspend fun extractToolInvocations(response: String): List<ToolInvocation> {
        val invocations = ExecutableToolProtocolParser.parse(response)

        AppLogger.d(
            TAG,
            "Found ${invocations.size} tool invocations: ${invocations.map { resolveDisplayToolName(it.tool) }}"
        )
        return invocations
    }

    /**
     * Execute a tool safely, with parameter validation
     *
     * @param invocation The tool invocation to execute
     * @param executor The tool executor to use
     * @return The result of the tool execution
     */
    fun executeToolSafely(
        invocation: ToolInvocation,
        executor: ToolExecutor,
        toolHandler: AIToolHandler? = null
    ): Flow<ToolResult> {
        inspectToolParameters(invocation.tool, executor)
        val validationResult = executor.validateParameters(invocation.tool)
        if (!validationResult.valid) {
            return flow {
                emit(
                    ToolResult(
                        toolName = invocation.tool.name,
                        success = false,
                        result = StringResultData(""),
                        error = "Invalid parameters: ${validationResult.errorMessage}"
                    )
                )
            }
        }

        return executor.invokeAndStream(invocation.tool).catch { e ->
            AppLogger.e(TAG, "Tool execution error: ${invocation.tool.name}", e)
            toolHandler?.notifyToolExecutionError(invocation.tool, e)
            emit(
                ToolResult(
                    toolName = invocation.tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = "Tool execution error: ${e.message}"
                )
            )
        }
    }

    /**
     * Check if a tool requires permission and verify if it has permission
     *
     * @param toolHandler The AIToolHandler instance to use for permission checks
     * @param invocation The tool invocation to check permissions for
     * @return A pair containing (has permission, error result if no permission)
     */
    internal suspend fun checkToolPermission(
        toolHandler: AIToolHandler,
        invocation: ToolInvocation,
        toolExposureMode: ToolExposureMode = ToolExposureMode.FULL,
        conversationLabel: String? = null,
        workspacePath: String? = null,
        workspaceEnv: String? = null,
        callerChatId: String? = null,
        parentModelConfigId: String? = null,
        parentModelIndex: Int? = null,
        timingScopeId: String? = null,
        batchPosition: Int = 1,
        batchSize: Int = 1,
        deferCircuitBreaker: Boolean = false,
        liveAssistantContent: CharSequence? = null,
    ): ToolPermissionCheckResult {
        if (PermissionReviewInternalTools.bypassesPermissionCheck(invocation.tool.name)) {
            toolHandler.notifyToolPermissionChecked(
                invocation.tool,
                granted = true,
                reason =
                    "Capability-bound internal tool (permission review or reading commentary audit)",
            )
            return ToolPermissionCheckResult(ToolPermissionDecision.Allowed, null)
        }

        val resolvedTarget = resolveToolTarget(invocation.tool)
        val permissionTool = resolvedTarget.tool

        if (toolExposureMode == ToolExposureMode.CLI &&
            invocation.tool.name == CliToolModeSupport.SEARCH_TOOL_NAME
        ) {
            toolHandler.notifyToolPermissionChecked(
                permissionTool,
                granted = true,
                reason = "CLI public tool"
            )
            return ToolPermissionCheckResult(ToolPermissionDecision.Allowed, null)
        }

        // Always check the resolved target. Model-controlled raw XML must never bypass permission.
        val toolPermissionSystem = toolHandler.getToolPermissionSystem()
        val permissionDecision =
            toolPermissionSystem.checkToolPermission(
                tool = permissionTool,
                conversationLabel = conversationLabel,
                workspacePath = workspacePath,
                workspaceEnv = workspaceEnv,
                callerChatId = callerChatId,
                parentModelConfigId = parentModelConfigId,
                parentModelIndex = parentModelIndex,
                timingScopeId = timingScopeId,
                targetId = invocation.callId ?: "$timingScopeId:${invocation.invocationIndex}",
                invocationIndex = invocation.invocationIndex,
                batchPosition = batchPosition,
                batchSize = batchSize,
                deferCircuitBreaker = deferCircuitBreaker,
                liveAssistantContent = liveAssistantContent,
            )

        if (permissionDecision is ToolPermissionDecision.Denied) {
            val errorResult =
                ToolResult(
                    toolName = resolvedTarget.displayName,
                    success = false,
                    result = StringResultData(""),
                    error = permissionDecision.rejection,
                    interruptTurn = permissionDecision.interruptTurn,
                )
            toolHandler.notifyToolPermissionChecked(
                permissionTool,
                granted = false,
                reason = errorResult.error
            )
            return ToolPermissionCheckResult(permissionDecision, errorResult)
        }

        toolHandler.notifyToolPermissionChecked(permissionTool, granted = true)
        return ToolPermissionCheckResult(permissionDecision, null)
    }

    internal suspend fun extractExecutableToolInvocations(response: String): List<ToolInvocation> =
        extractToolInvocations(response)

    /**
     *
     * 执行工具调用，包括权限检查、并行/串行执行和结果聚合。
     * @param invocations 要执行的工具调用列表。
     * @param toolHandler AIToolHandler 的实例。
     * @param packageManager PackageManager 的实例。
     * @param collector 用于实时输出结果的 StreamCollector。
     * @return 所有工具执行结果的列表。
     */
    suspend fun executeInvocations(
        invocations: List<ToolInvocation>,
        context: Context,
        toolHandler: AIToolHandler,
        packageManager: PackageManager,
        collector: StreamCollector<String>,
        timingScopeId: String? = null,
        toolExposureMode: ToolExposureMode = ToolExposureMode.FULL,
        callerName: String? = null,
        callerChatId: String? = null,
        callerCardId: String? = null,
        conversationLabel: String? = null,
        workspacePath: String? = null,
        workspaceEnv: String? = null,
        parentModelConfigId: String? = null,
        parentModelIndex: Int? = null,
        parentModelSupportsVision: Boolean = false,
        imageRecognitionModelAvailable: Boolean = false,
        liveAssistantContent: CharSequence? = null,
        isSubagent: Boolean = false,
        subagentToolLoopGuard: SubagentToolLoopGuard? = null,
        resolvedMemorySpaceId: String? = null,
        dynamicUiRunId: String? = null,
    ): List<ToolResult> = coroutineScope {
        // Bind proxy context parameters to host values before any permission review or execution
        // happens, so the reviewed parameter set is exactly the executed one. The model can never
        // supply a reserved package context value. The package lookup is restricted to proxy
        // batches and guarded: a failing package initialization must never abort a tool turn, it
        // only degrades binding to stripping forged values without host injection.
        val hostContext =
            resolveHostPackageContext(
                invocations = invocations,
                packageManager = packageManager,
                toolHandler = toolHandler,
                callerName = callerName,
                callerChatId = callerChatId,
                callerCardId = callerCardId,
            )
        val boundInvocations =
            invocations.map { invocation ->
                val repaired = ToolCallRepairRouter.route(invocation)?.invocation ?: invocation
                if (
                    repaired.tool.name == PACKAGE_PROXY_TOOL_NAME ||
                    repaired.tool.name == CliToolModeSupport.PROXY_TOOL_NAME
                ) {
                    repaired.copy(
                        tool = bindProxyContextParameters(repaired.tool, hostContext)
                    )
                } else {
                    repaired
                }
            }
        // Exact-repeat detection must fingerprint the model's raw call, never the bound copy:
        // binding canonicalizes params JSON, so format-only differences such as {"x":1} vs
        // { "x": 1 } would otherwise be mistaken for identical instructions and trigger a
        // review on the third distinct call.
        val rawInvocations = invocations
        val runObserver = if (isSubagent) AgentRunObservers.forChat(callerChatId) else null
        if (
            runObserver == null &&
                isSubagent &&
                subagentToolLoopGuard != null &&
                rawInvocations.size > 1
        ) {
            val firstReviewIndex =
                subagentToolLoopGuard.firstReviewIndex(
                    tools = rawInvocations.map { it.tool },
                    threshold = SUBAGENT_EXACT_REPEAT_REVIEW_THRESHOLD,
                )
            if (firstReviewIndex > 0) {
                // Recurse on the RAW subset: each recursion re-derives its own bound copies, so
                // the fingerprint stays the model's raw call at every level. Passing already-bound
                // invocations here would canonicalize params before the deeper firstReviewIndex/
                // record calls and reintroduce the format-only false repeat.
                val commonPrefixResults =
                    executeInvocations(
                        context = context,
                        invocations = rawInvocations.take(firstReviewIndex),
                        toolHandler = toolHandler,
                        packageManager = packageManager,
                        collector = collector,
                        timingScopeId = timingScopeId,
                        toolExposureMode = toolExposureMode,
                        callerName = callerName,
                        callerChatId = callerChatId,
                        callerCardId = callerCardId,
                        conversationLabel = conversationLabel,
                        workspacePath = workspacePath,
                        workspaceEnv = workspaceEnv,
                        parentModelConfigId = parentModelConfigId,
                        parentModelIndex = parentModelIndex,
                        parentModelSupportsVision = parentModelSupportsVision,
                        imageRecognitionModelAvailable = imageRecognitionModelAvailable,
                        liveAssistantContent = liveAssistantContent,
                        isSubagent = true,
                        subagentToolLoopGuard = subagentToolLoopGuard,
                        resolvedMemorySpaceId = resolvedMemorySpaceId,
                        dynamicUiRunId = dynamicUiRunId,
                    )
                val reviewedSuffixResults =
                    executeInvocations(
                        context = context,
                        invocations = rawInvocations.drop(firstReviewIndex),
                        toolHandler = toolHandler,
                        packageManager = packageManager,
                        collector = collector,
                        timingScopeId = timingScopeId,
                        toolExposureMode = toolExposureMode,
                        callerName = callerName,
                        callerChatId = callerChatId,
                        callerCardId = callerCardId,
                        conversationLabel = conversationLabel,
                        workspacePath = workspacePath,
                        workspaceEnv = workspaceEnv,
                        parentModelConfigId = parentModelConfigId,
                        parentModelIndex = parentModelIndex,
                        parentModelSupportsVision = parentModelSupportsVision,
                        imageRecognitionModelAvailable = imageRecognitionModelAvailable,
                        liveAssistantContent = liveAssistantContent,
                        isSubagent = true,
                        subagentToolLoopGuard = subagentToolLoopGuard,
                        resolvedMemorySpaceId = resolvedMemorySpaceId,
                        dynamicUiRunId = dynamicUiRunId,
                    )
                return@coroutineScope commonPrefixResults + reviewedSuffixResults
            }
        }

        boundInvocations.forEach { invocation ->
            ToolExecutionTimingRepository.register(timingScopeId, invocation)
        }

        // Record after batch splitting so recursive execution does not log a repair twice.
        rawInvocations.forEach { invocation ->
            ToolCallRepairRouter.route(invocation)?.let { ToolCallRepairLogger.record(context, it) }
        }
        val errorBatchId = java.util.UUID.randomUUID().toString()
        val parameterObservers = java.util.concurrent.ConcurrentHashMap<String, ToolParameterObservation>()
        fun rawInvocation(invocation: ToolInvocation): ToolInvocation =
            rawInvocations.firstOrNull {
                it.callId == invocation.callId && it.invocationIndex == invocation.invocationIndex
            } ?: invocation
        fun routedRawInvocation(invocation: ToolInvocation): ToolInvocation {
            val raw = rawInvocation(invocation)
            return ToolCallRepairRouter.route(raw)?.invocation ?: raw
        }
        fun diagnosticKey(invocation: ToolInvocation) = "$errorBatchId:${invocation.callId ?: invocation.invocationIndex}"
        val recordDiagnostic: (ToolInvocation, ToolResult) -> Unit = { invocation, result ->
            try {
                // Use original parameters and the declaration captured BEFORE execution: a tool
                // can change its package's active state while running.
                createToolErrorRecord(
                    id = diagnosticKey(invocation),
                    invocation = rawInvocation(invocation),
                    result = result,
                    occurredAt = System.currentTimeMillis(),
                    undeclaredParameters = parameterObservers[diagnosticKey(invocation)]?.snapshot().orEmpty(),
                )?.let { ToolErrorRepository.getInstance(context).record(it) }
            } catch (e: Exception) {
                ToolErrorRepository.getInstance(context).reportRecordingFailure(e)
            }
        }

        try {
        // 默认工具注册现在可能在启动阶段被延后；这里确保在真正执行工具前已完成注册
        // registerDefaultTools() 是幂等且线程安全的，可安全重复调用
        withContext(Dispatchers.Default) {
            toolHandler.registerDefaultTools()
        }

        val loopApprovedInvocations =
            java.util.IdentityHashMap<ToolInvocation, Boolean>()
        if (runObserver != null) {
            runObserver.beforeToolBatch(rawInvocations.map { it.tool })
        } else if (isSubagent && subagentToolLoopGuard != null) {
            val permissionSystem = toolHandler.getToolPermissionSystem()
            val rawToolsByIndex = rawInvocations.map { it.tool }
            for ((index, invocation) in boundInvocations.withIndex()) {
                val resolvedTool = resolveToolTarget(invocation.tool).tool
                // Fingerprint the raw model call: the bound copy has canonicalized proxy params.
                val consecutiveCount = subagentToolLoopGuard.record(rawToolsByIndex[index])
                if (CollaborationTools.isCollaborationTool(resolvedTool.name)) {
                    // Handing work to another agent carries no side effect of its own and is allowed
                    // by default, so repeating it is not the runaway loop this guard looks for. The
                    // legacy task is also rejected below, where an approval dialog could not have
                    // made the invocation executable. Recording first still lets this distinct call
                    // break another tool's exact-repeat sequence.
                    continue
                }
                if (consecutiveCount < SUBAGENT_EXACT_REPEAT_REVIEW_THRESHOLD) {
                    continue
                }
                val isCliPublicTool =
                    toolExposureMode == ToolExposureMode.CLI &&
                        (
                            invocation.tool.name == CliToolModeSupport.SEARCH_TOOL_NAME ||
                                invocation.tool.name == CliToolModeSupport.PROXY_TOOL_NAME
                            )
                val effectivePermission =
                    permissionSystem.getEffectivePermissionLevel(resolvedTool.name)
                if (!isCliPublicTool && effectivePermission == PermissionLevel.FORBID) {
                    // The normal permission path will reject this call without offering an override.
                    continue
                }
                ToolExecutionTimingRepository.markWaitingAuthorization(
                    timingScopeId,
                    invocation,
                )
                val approvedByPrompt =
                    permissionSystem.requestExplicitApproval(
                        tool = resolvedTool,
                        operationDescription =
                            context.getString(
                                R.string.subagent_loop_review_operation,
                                resolveDisplayToolName(invocation.tool),
                                consecutiveCount,
                            ),
                        conversationLabel = conversationLabel,
                    )
                // Re-resolve the effective permission after the dialog: the user may have changed
                // the master switch or the per-tool setting while it was pending, and a global
                // FORBID must never be overridden by a stale dialog result.
                val approved =
                    resolveApprovalDecisionWithPermanentOverride(
                        approvalGranted = approvedByPrompt,
                        latestEffectiveLevel =
                            permissionSystem.getEffectivePermissionLevel(resolvedTool.name),
                    )
                if (!approved) {
                    throw SubagentLoopRejectedException(
                        context.getString(
                            R.string.subagent_loop_rejected,
                            resolveDisplayToolName(invocation.tool),
                        )
                    )
                }
                loopApprovedInvocations[invocation] = true
            }
        }

        val roleCardToolAccess = if (callerCardId.isNullOrBlank()) {
            null
        } else {
            runCatching {
                CharacterCardToolAccessResolver
                    .getInstance(context)
                    .resolve(callerCardId, packageManager)
            }.onFailure { error ->
                AppLogger.e(TAG, "角色卡工具权限解析失败: callerCardId=$callerCardId", error)
            }.getOrNull()
        }
        val toolRuntimeContext =
            ToolRuntimeContext(
                callerName = callerName,
                callerCardId = callerCardId,
                callerChatId = callerChatId,
                toolExposureMode = toolExposureMode,
                conversationLabel = conversationLabel,
                workspacePath = workspacePath,
                workspaceEnv = workspaceEnv,
                parentModelConfigId = parentModelConfigId,
                parentModelIndex = parentModelIndex,
                parentModelSupportsVision = parentModelSupportsVision,
                imageRecognitionModelAvailable = imageRecognitionModelAvailable,
                isSubagent = isSubagent,
                timingScopeId = timingScopeId,
                batchSize = boundInvocations.size.coerceAtLeast(1),
                resolvedMemorySpaceId = resolvedMemorySpaceId,
                dynamicUiRunId = dynamicUiRunId,
            )

        // 1. 顶层工具暴露模式拦截
        val toolExposurePermittedInvocations = mutableListOf<ToolInvocation>()
        val toolExposureDeniedResults = mutableListOf<ToolResult>()
        for (invocation in boundInvocations) {
            val deniedResult =
                if (
                    isSubagent &&
                        SubagentToolPolicy.isForbidden(resolveDisplayToolName(invocation.tool))
                ) {
                    val targetToolName = resolveDisplayToolName(invocation.tool)
                    ToolResult(
                        toolName = targetToolName,
                        success = false,
                        result = StringResultData(""),
                        error = "Subagents cannot invoke tools that start nested AI turns.",
                    )
                } else {
                    buildToolExposureDeniedResult(context, invocation, toolExposureMode)
                }
            if (deniedResult == null) {
                toolExposurePermittedInvocations.add(invocation)
            } else {
                val finalResult =
                    deniedResult.withExecutionMetadata(
                        invocation = invocation,
                        state = ToolExecutionState.NOT_EXECUTED,
                    )
                toolExposureDeniedResults.add(finalResult)
                ToolExecutionTimingRepository.markFinished(
                    timingScopeId,
                    invocation,
                    finalResult,
                    durationMs = null,
                    state = ToolExecutionState.NOT_EXECUTED,
                )
            }
        }

        // 2. 角色卡工具权限拦截（优先于权限弹窗与包自动激活）
        val roleCardPermittedInvocations = mutableListOf<ToolInvocation>()
        val roleCardDeniedResults = mutableListOf<ToolResult>()
        for (invocation in toolExposurePermittedInvocations) {
            val deniedResult = if (roleCardToolAccess?.customEnabled == true &&
                !isInvocationAllowedForRoleCard(invocation, roleCardToolAccess)
            ) {
                buildRoleCardDeniedResult(context, invocation)
            } else {
                null
            }

            if (deniedResult == null) {
                roleCardPermittedInvocations.add(invocation)
            } else {
                val finalResult =
                    deniedResult.withExecutionMetadata(
                        invocation = invocation,
                        state = ToolExecutionState.NOT_EXECUTED,
                    )
                roleCardDeniedResults.add(finalResult)
                ToolExecutionTimingRepository.markFinished(
                    timingScopeId,
                    invocation,
                    finalResult,
                    durationMs = null,
                    state = ToolExecutionState.NOT_EXECUTED,
                )
            }
        }

        // 3. Hook 拦截后并发完成整批权限检查。人工 ASK 仍由 ToolPermissionSystem 的
        // askMutex 串行显示；自动审核则可按模型并发能力并行运行。
        val permittedInvocations = mutableListOf<ToolInvocation>()
        val hookDeniedResults = mutableListOf<ToolResult>()
        val permissionDeniedResults = mutableListOf<ToolResult>()
        val permissionCandidates =
            mutableListOf<Triple<Int, ToolInvocation, AITool>>()
        for ((batchIndex, invocation) in roleCardPermittedInvocations.withIndex()) {
            toolHandler.notifyToolCallRequested(invocation.tool)
            val interceptionTool = resolveToolTarget(invocation.tool).tool
            val interception =
                if (interceptionTool.name in PermissionReviewInternalTools.names) {
                    AIToolHookDecision.Allow
                } else {
                    toolHandler.checkToolInterception(interceptionTool)
                }
            when (interception) {
                AIToolHookDecision.Allow -> {
                    ToolExecutionTimingRepository.markWaitingAuthorization(
                        timingScopeId,
                        invocation,
                    )
                    permissionCandidates += Triple(batchIndex, invocation, interceptionTool)
                }

                is AIToolHookDecision.Block -> {
                    val interceptedResult =
                        toolHandler.buildToolInterceptionResult(
                            resolveDisplayToolName(invocation.tool),
                            interception
                        )
                    val finalResult =
                        interceptedResult.withExecutionMetadata(
                            invocation = invocation,
                            state = ToolExecutionState.NOT_EXECUTED,
                        )
                    hookDeniedResults.add(finalResult)
                    ToolExecutionTimingRepository.markFinished(
                        timingScopeId,
                        invocation,
                        finalResult,
                        durationMs = null,
                        state = ToolExecutionState.NOT_EXECUTED,
                    )
                    toolHandler.notifyToolExecutionFinished(invocation.tool)
                }
            }
        }

        // 3.1 “自动审核”快速档的异步风险评分：在整批派发时启动，覆盖本批全部动作，但不阻塞
        // 本批的权限检查。评分只可能减少审核次数，任何异常都会退回阻塞审核。
        //
        // 空候选批次同样要走这一步：它会让旧分数按“一批一步”老化，否则被 hook 全部拦截的
        // 派发不会消耗分数的时间预算。子代理精确重复拆分会递归进入本函数两次，因此这种少见的
        // 派发会记两步——方向只会让分数更早失效，不会放宽任何判断。
        withContext(Dispatchers.IO) {
            toolHandler
                .getToolPermissionSystem()
                .prepareBatchRiskScores(
                    tools = permissionCandidates.map { (_, _, tool) -> tool },
                    callerChatId = callerChatId,
                    conversationLabel = conversationLabel,
                    workspacePath = workspacePath,
                    workspaceEnv = workspaceEnv,
                    parentModelConfigId = parentModelConfigId,
                    parentModelIndex = parentModelIndex,
                    timingScopeId = timingScopeId,
                    liveAssistantContent = liveAssistantContent,
                )
        }

        // 3.3 The independent reviewer answers a batch in the order it is handed the actions: the
        // first one it reaches builds the prompt, and the reviews behind it continue that same
        // reviewer conversation instead of each paying for a prompt of their own. Walking the batch
        // in the model's own call order is what makes that chain follow the order the actions are
        // executed in and read in. Handing the whole batch over at once left that order to a race
        // between coroutines: whichever arrived first built the prompt, and the ones behind it waited
        // for that review only up to the reviewer's continuation timeout, past which they sent a
        // prompt of their own. A level that never reaches the reviewer keeps the parallel dispatch,
        // where order can only decide which reply comes back first.
        val permissionSystem = toolHandler.getToolPermissionSystem()
        val candidateLevels =
            permissionCandidates.map { (_, _, interceptionTool) ->
                permissionSystem.getEffectivePermissionLevel(interceptionTool.name)
            }
        val reviewsInOrder = batchNeedsReviewOrder(candidateLevels)

        suspend fun checkCandidate(
            batchIndex: Int,
            invocation: ToolInvocation,
            interceptionTool: AITool,
        ): Triple<ToolInvocation, ToolPermissionCheckResult, ToolResult?> {
            val check =
                if (loopApprovedInvocations.containsKey(invocation)) {
                    toolHandler.notifyToolPermissionChecked(
                        interceptionTool,
                        granted = true,
                        reason = "Approved by Subagent exact-repeat loop review",
                    )
                    ToolPermissionCheckResult(ToolPermissionDecision.Allowed, null)
                } else {
                    checkToolPermission(
                        toolHandler,
                        invocation,
                        toolExposureMode,
                        conversationLabel,
                        workspacePath,
                        workspaceEnv,
                        callerChatId,
                        parentModelConfigId,
                        parentModelIndex,
                        timingScopeId,
                        batchIndex + 1,
                        roleCardPermittedInvocations.size,
                        deferCircuitBreaker = true,
                        liveAssistantContent = liveAssistantContent,
                    )
                }
            val finalError =
                check.errorResult?.withExecutionMetadata(
                    invocation = invocation,
                    state = ToolExecutionState.NOT_EXECUTED,
                )
            if (check.granted) {
                ToolExecutionTimingRepository.markWaitingExecution(
                    timingScopeId,
                    invocation,
                )
            } else if (finalError != null) {
                ToolExecutionTimingRepository.markFinished(
                    timingScopeId,
                    invocation,
                    finalError,
                    durationMs = null,
                    state = ToolExecutionState.NOT_EXECUTED,
                )
            }
            return Triple(invocation, check, finalError)
        }

        val permissionChecks =
            mapBatchInReviewOrder(permissionCandidates, ordered = reviewsInOrder) {
                    (batchIndex, invocation, interceptionTool) ->
                    checkCandidate(batchIndex, invocation, interceptionTool)
            }

        for ((invocation, permissionCheck, initialErrorResult) in permissionChecks) {
            var errorResult = initialErrorResult
            if (!callerChatId.isNullOrBlank()) {
                val adjustedDecision =
                    applyDeferredPermissionReviewCircuit(
                        parentChatId = callerChatId,
                        turnScopeId = timingScopeId,
                        decision = permissionCheck.decision,
                        wasAutomaticallyReviewed =
                            PermissionReviewEventRepository.findForInvocation(
                                callerChatId,
                                timingScopeId,
                                invocation.invocationIndex,
                            )
                                ?.let { event ->
                                    event.status in
                                        setOf(
                                            PermissionReviewStatus.APPROVED,
                                            PermissionReviewStatus.DENIED,
                                        ) &&
                                        // A reused approval reuses an older verdict; it is not a
                                        // fresh judgement, so it must not clear the circuit breaker.
                                        event.resolutionSource !=
                                            ToolPermissionSystem
                                                .FAST_REVIEW_RESOLUTION_SOURCE
                                } ?: false,
                    )
                if (adjustedDecision is ToolPermissionDecision.Denied &&
                    adjustedDecision.interruptTurn &&
                    errorResult != null
                ) {
                    errorResult = errorResult.copy(interruptTurn = true)
                    ToolExecutionTimingRepository.markFinished(
                        timingScopeId,
                        invocation,
                        errorResult,
                        durationMs = null,
                        state = ToolExecutionState.NOT_EXECUTED,
                    )
                }
            }
            if (permissionCheck.granted) {
                        permittedInvocations.add(invocation)
            } else if (errorResult != null) {
                permissionDeniedResults.add(errorResult)
            }
        }

        // A Guardian circuit-breaker decision stops the whole pending batch. Permission checks are
        // completed before execution, so previously approved siblings have not started yet and can
        // still be cancelled atomically instead of partially executing a suspicious model turn.
        if (shouldInterruptPendingToolBatch(permissionDeniedResults)) {
            toolHandler.getToolPermissionSystem().showAutomaticReviewCircuitBreakerWarning()
            val interruptedApprovedResults =
                permittedInvocations.map { invocation ->
                    val interrupted =
                        ToolResult(
                            toolName = resolveDisplayToolName(invocation.tool),
                            success = false,
                            result = StringResultData(""),
                            error =
                                "$AUTOMATIC_REVIEW_CANCEL_PREFIX " +
                                    "stopped this model turn after repeated denied actions.",
                            interruptTurn = true,
                        ).withExecutionMetadata(
                            invocation = invocation,
                            state = ToolExecutionState.NOT_EXECUTED,
                        )
                    ToolExecutionTimingRepository.markFinished(
                        timingScopeId,
                        invocation,
                        interrupted,
                        durationMs = null,
                        state = ToolExecutionState.NOT_EXECUTED,
                    )
                    interrupted
                }
            permittedInvocations.clear()
            permissionDeniedResults.addAll(interruptedApprovedResults)
        }

        val injectedInvocations =
            if (!hostContext.hasHostContext) {
                permittedInvocations
            } else {
                // Reuse the entry snapshot: a second lookup here could succeed after the entry
                // one failed, silently changing the executed parameter set from the reviewed one.
                permittedInvocations.map { invocation ->
                    injectPackageCallContext(
                        invocation = invocation,
                        hostContext = hostContext,
                    )
                }
            }

        // 4. 所有权限结果齐备后，严格按模型原始调用顺序返回或执行。
        // A rejected item occupies its original slot; a later rejection can no longer be emitted
        // before an earlier allowed tool has finished.
        // Subagent task invocations run in parallel: the coordinator already supports concurrent
        // child runs per parent, so a sequential loop would needlessly serialize independent
        // agents. Contiguous task runs form one parallel group; non-task tools stay serial and
        // act as ordering barriers. Results are published strictly in the model's original call
        // order from the single driver coroutine, so the shared collector and round manager are
        // never written concurrently and the transcript cannot reorder tool results.
        fun key(callId: String?, invocationIndex: Int?): String =
            callId?.takeIf(String::isNotBlank) ?: "index:${invocationIndex ?: -1}"

        val deniedByInvocation =
            (
                toolExposureDeniedResults +
                    roleCardDeniedResults +
                    hookDeniedResults +
                    permissionDeniedResults
                ).associateBy { result -> key(result.callId, result.invocationIndex) }
        val executableByInvocation =
            injectedInvocations.associateBy { invocation ->
                key(invocation.callId, invocation.invocationIndex.takeIf { it >= 0 })
            }
        val orderedResults = mutableListOf<ToolResult>()

        suspend fun runTool(
            invocation: ToolInvocation,
            deferredResultSink: java.util.concurrent.ConcurrentMap<ToolInvocation, ToolResult>? = null,
        ): ToolResult =
            executeAndEmitTool(
                invocation = invocation,
                toolHandler = toolHandler,
                packageManager = packageManager,
                collector = collector,
                runtimeContext =
                    toolRuntimeContext.copy(
                        callId = invocation.callId,
                        invocationIndex = invocation.invocationIndex.takeIf { it >= 0 },
                        batchPosition = invocation.invocationIndex + 1,
                        permissionCheckedToolName =
                            resolveToolTarget(invocation.tool).tool.name,
                        parameterObserver = ToolParameterObservation(
                            rawTool = routedRawInvocation(invocation).tool,
                            targetTool = resolveToolTarget(routedRawInvocation(invocation).tool).tool,
                            onFailure = { ToolErrorRepository.getInstance(context).reportRecordingFailure(it) },
                        ).also { parameterObservers[diagnosticKey(invocation)] = it },
                    ),
                timingScopeId = timingScopeId,
                deferredResultSink = deferredResultSink,
                recordDiagnostic = recordDiagnostic,
            )

        suspend fun publishResult(
            invocation: ToolInvocation,
            result: ToolResult,
            sendFinished: Boolean = false,
        ) {
            toolHandler.notifyToolExecutionResult(invocation.tool, result)
            emitFinalResult(collector, result)
            // Parallel group members skipped the worker-side finished event; emit it here so the
            // lifecycle stays started -> result -> finished in the driver's call order.
            if (sendFinished) {
                toolHandler.notifyToolExecutionFinished(invocation.tool)
            }
        }

        // Walk invocations in the model's original order, executing each item at its slot and
        // publishing its result before any later invocation is started.
        var index = 0
        while (index < boundInvocations.size) {
            val originalInvocation = boundInvocations[index]
            val invocationKey =
                key(
                    originalInvocation.callId,
                    originalInvocation.invocationIndex.takeIf { it >= 0 },
                )
            val denied = deniedByInvocation[invocationKey]
            if (denied != null) {
                publishResult(originalInvocation, denied)
                orderedResults += denied
                index += 1
                continue
            }
            val invocation = executableByInvocation[invocationKey]
            if (invocation == null) {
                index += 1
                continue
            }
            if (invocation.tool.name != "task") {
                val result = runTool(invocation)
                orderedResults += result
                index += 1
                continue
            }
            // A contiguous run of executable task calls: launch them concurrently, then publish
            // their results in call order once the whole group has finished.
            var groupEnd = index + 1
            while (groupEnd < boundInvocations.size) {
                val candidateKey =
                    key(
                        boundInvocations[groupEnd].callId,
                        boundInvocations[groupEnd].invocationIndex.takeIf { it >= 0 },
                    )
                val candidate = executableByInvocation[candidateKey]
                if (candidate == null || candidate.tool.name != "task") break
                groupEnd += 1
            }
            val groupInvocations =
                (index until groupEnd).mapNotNull { position ->
                    val slotKey =
                        key(
                            boundInvocations[position].callId,
                            boundInvocations[position].invocationIndex.takeIf { it >= 0 },
                        )
                    executableByInvocation[slotKey]
                }
            val executionResults =
                java.util.concurrent.ConcurrentHashMap<ToolInvocation, ToolResult>()
            runParallelGroupWithOrderedPublishing(
                items = groupInvocations,
                executionResults = executionResults,
                execute = { taskInvocation ->
                    runTool(taskInvocation, deferredResultSink = executionResults)
                },
                publish = { taskInvocation, result ->
                    publishResult(taskInvocation, result, sendFinished = true)
                },
                record = { result -> orderedResults += result },
            )
            index = groupEnd
        }

        orderedResults.sortedBy { result -> result.invocationIndex ?: Int.MAX_VALUE }
        } catch (cancellation: CancellationException) {
            withContext(NonCancellable) {
                boundInvocations.forEach { invocation ->
                    val snapshot =
                        ToolExecutionTimingRepository.get(
                            timingScopeId,
                            invocation.invocationIndex,
                        )
                    if (snapshot?.state != ToolExecutionState.WAITING_EXECUTION &&
                        snapshot?.state != ToolExecutionState.WAITING_AUTHORIZATION
                    ) {
                        return@forEach
                    }
                    val cancelledResult =
                        ToolResult(
                            toolName = resolveDisplayToolName(invocation.tool),
                            success = false,
                            result = StringResultData(""),
                            error = "Tool execution cancelled before it started.",
                        ).withExecutionMetadata(
                            invocation = invocation,
                            state = ToolExecutionState.NOT_EXECUTED,
                        )
                    ToolExecutionTimingRepository.markFinished(
                        timingScopeId,
                        invocation,
                        cancelledResult,
                        durationMs = null,
                        state = ToolExecutionState.NOT_EXECUTED,
                    )
                    try {
                        emitFinalResult(collector, cancelledResult)
                    } catch (emitError: Exception) {
                        AppLogger.w(
                            TAG,
                            "Failed to persist cancelled pending tool result: ${emitError.message}",
                        )
                    }
                }
            }
            throw cancellation
        } catch (failure: Exception) {
            withContext(NonCancellable) {
                boundInvocations.forEach { invocation ->
                    val snapshot =
                        ToolExecutionTimingRepository.get(
                            timingScopeId,
                            invocation.invocationIndex,
                        )
                    val terminalState =
                        when (snapshot?.state) {
                            ToolExecutionState.RUNNING -> ToolExecutionState.COMPLETED
                            ToolExecutionState.WAITING_EXECUTION,
                            ToolExecutionState.WAITING_AUTHORIZATION -> ToolExecutionState.NOT_EXECUTED
                            else -> return@forEach
                        }
                    val durationMs =
                        snapshot.startedAtElapsedMs?.let { startedAt ->
                            (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
                        }
                    val failureMessage =
                        failure.message
                            ?.take(ToolExecutionLimits.MAX_TEXT_RESULT_LENGTH)
                            ?.takeIf { it.isNotBlank() }
                            ?: failure::class.java.simpleName
                    val failedResult =
                        ToolResult(
                            toolName = resolveDisplayToolName(invocation.tool),
                            success = false,
                            result = StringResultData(""),
                            error = "Tool execution failed: $failureMessage",
                        ).withExecutionMetadata(
                            invocation = invocation,
                            state = terminalState,
                            durationMs = durationMs,
                        )
                    ToolExecutionTimingRepository.markFinished(
                        timingScopeId,
                        invocation,
                        failedResult,
                        durationMs = durationMs,
                        state = terminalState,
                    )
                    if (failure !is SubagentLoopRejectedException) {
                        recordDiagnostic(invocation, failedResult.copy(
                            error = "Tool execution failed: ${failure.message ?: failure::class.java.simpleName}",
                        ))
                    }
                    try {
                        emitFinalResult(collector, failedResult)
                    } catch (emitError: Exception) {
                        AppLogger.w(
                            TAG,
                            "Failed to persist failed tool result: ${emitError.message}",
                        )
                    }
                }
            }
            throw failure
        }
    }

    /**
     * Runs a parallel group of tool invocations and publishes the results strictly in the given
     * item order from the caller's (single driver) coroutine. Cancellation or failure of one item
     * is rethrown to the caller after the finished portion has been published in order, but
     * already-recorded results are never lost: publication runs in a NonCancellable context so a
     * cancelled caller cannot silently drop the finished portion.
     */
    internal suspend fun runParallelGroupWithOrderedPublishing(
        items: List<ToolInvocation>,
        executionResults: java.util.concurrent.ConcurrentMap<ToolInvocation, ToolResult>,
        execute: suspend (ToolInvocation) -> Unit,
        publish: suspend (ToolInvocation, ToolResult) -> Unit,
        record: (ToolResult) -> Unit = {},
    ) {
        try {
            coroutineScope {
                items.forEach { item ->
                    async { execute(item) }
                }
            }
        } catch (t: Throwable) {
            withContext(NonCancellable) {
                items.forEach { item ->
                    executionResults[item]?.let { result ->
                        publish(item, result)
                        record(result)
                    }
                }
            }
            throw t
        }
        // All workers have terminated, so the ordered publication below must never be interrupted
        // by a cancellation arriving in this window: a lost result would never be republished by
        // the outer cancellation handler (the worker already marked the invocation COMPLETED).
        withContext(NonCancellable) {
            items.forEach { item ->
                executionResults[item]?.let { result ->
                    publish(item, result)
                    record(result)
                }
            }
        }
        // Re-propagate a cancellation that arrived while the ordered publication was running so
        // the caller still observes the cancelled state after all results were published.
        currentCoroutineContext().ensureActive()
    }

    /**
     * 封装单个工具的执行、实时输出和结果聚合的辅助函数
     */
    private suspend fun executeAndEmitTool(
        invocation: ToolInvocation,
        toolHandler: AIToolHandler,
        packageManager: PackageManager,
        collector: StreamCollector<String>,
        runtimeContext: ToolRuntimeContext,
        timingScopeId: String?,
        deferredResultSink: java.util.concurrent.ConcurrentMap<ToolInvocation, ToolResult>? = null,
        recordDiagnostic: (ToolInvocation, ToolResult) -> Unit,
    ): ToolResult {
        val toolName = invocation.tool.name
        val displayToolName = resolveDisplayToolName(invocation.tool)

        return withContext(toolRuntimeContextElement(runtimeContext)) {
            var startedAtElapsedMs: Long? = null
            val collectedResults = BoundedToolResultAccumulator()
            var diagnosticError: String? = null
            // Parallel group members run with a deferred result sink and publish later from the
            // single driver coroutine so the shared collector, round manager and hook
            // notifications stay strictly ordered. The sink also captures cancellation/failure
            // results so the driver can still publish the finished portion of a group in order.
            suspend fun publishResult(result: ToolResult) {
                if (deferredResultSink != null) {
                    deferredResultSink[invocation] = result
                    return
                }
                toolHandler.notifyToolExecutionResult(invocation.tool, result)
                emitFinalResult(collector, result)
            }
            try {
                val executor = toolHandler.getToolExecutorOrActivate(toolName)
                if (executor == null) {
                    // 如果仍然为 null，则构建错误消息
                    val errorMessage =
                        buildToolNotAvailableErrorMessage(toolName, packageManager, toolHandler)
                    val notAvailableResult =
                        ToolResult(
                            toolName = displayToolName,
                            success = false,
                            result = StringResultData(""),
                            error = errorMessage
                        ).withExecutionMetadata(
                            invocation = invocation,
                            state = ToolExecutionState.NOT_EXECUTED,
                        )
                    ToolExecutionTimingRepository.markFinished(
                        timingScopeId,
                        invocation,
                        notAvailableResult,
                        durationMs = null,
                        state = ToolExecutionState.NOT_EXECUTED,
                    )
                    recordDiagnostic(invocation, notAvailableResult)
                    publishResult(notAvailableResult)
                    return@withContext notAvailableResult
                }

                toolHandler.notifyToolExecutionStarted(invocation.tool)
                val executionStartedAtMs = SystemClock.elapsedRealtime()
                startedAtElapsedMs = executionStartedAtMs
                ToolExecutionTimingRepository.markRunning(
                    timingScopeId,
                    invocation,
                    executionStartedAtMs,
                )

                // The binding-time classification can go stale within a batch: an earlier
                // invocation may have disabled the JavaScript package, and auto-activation may
                // then select an MCP executor for the same qualified name. Reserved host context
                // must never reach anything but a marked JavaScript package executor, so strip it
                // once more after the final executor is chosen.
                val executionInvocation =
                    if (executor is JsPackageToolExecutorMarker) {
                        invocation
                    } else {
                        invocation.copy(
                            tool =
                                invocation.tool.copy(
                                    parameters =
                                        invocation.tool.parameters.filterNot {
                                            it.name in reservedPackageContextParams
                                        }
                                )
                        )
                    }

                executeToolSafely(executionInvocation, executor, toolHandler).collect { result ->
                    diagnosticError = if (result.success) null
                        else result.error?.takeIf { it.isNotBlank() } ?: result.result.toString()
                    // Intermediate emissions belong to this same invocation. Aggregate them and
                    // publish one final row so streaming tools do not create duplicate result rows.
                    collectedResults.add(result)
                }

                // 为此调用聚合最终结果
                if (collectedResults.isEmpty()) {
                    val durationMs =
                        (SystemClock.elapsedRealtime() - executionStartedAtMs).coerceAtLeast(0L)
                    val emptyResult =
                        ToolResult(
                            toolName = displayToolName,
                            success = false,
                            result = StringResultData(""),
                            error = "The tool execution returned no results."
                        ).withExecutionMetadata(
                            invocation = invocation,
                            state = ToolExecutionState.COMPLETED,
                            durationMs = durationMs,
                        )
                    ToolExecutionTimingRepository.markFinished(
                        timingScopeId,
                        invocation,
                        emptyResult,
                        durationMs = durationMs,
                        state = ToolExecutionState.COMPLETED,
                    )
                    recordDiagnostic(invocation, emptyResult)
                    publishResult(emptyResult)
                    return@withContext emptyResult
                }

                val lastResultSuccess = requireNotNull(collectedResults.lastResultSuccess)
                val combinedResultString = collectedResults.combinedResultText()

                val durationMs =
                    (SystemClock.elapsedRealtime() - executionStartedAtMs).coerceAtLeast(0L)
                val finalResult =
                    ToolResult(
                        toolName = displayToolName,
                        success = lastResultSuccess,
                        result = StringResultData(combinedResultString),
                        error = collectedResults.lastResultError,
                        interruptTurn = collectedResults.anyInterruptTurn,
                    ).withExecutionMetadata(
                        invocation = invocation,
                        state = ToolExecutionState.COMPLETED,
                        durationMs = durationMs,
                    )
                ToolExecutionTimingRepository.markFinished(
                    timingScopeId,
                    invocation,
                    finalResult,
                    durationMs = durationMs,
                    state = ToolExecutionState.COMPLETED,
                )
                recordDiagnostic(invocation, finalResult.copy(error = diagnosticError))
                publishResult(finalResult)
                return@withContext finalResult
            } catch (cancellation: CancellationException) {
                val start = startedAtElapsedMs
                if (start != null) {
                    val durationMs =
                        (SystemClock.elapsedRealtime() - start).coerceAtLeast(0L)
                    val cancelledResult =
                        createCancelledToolResult(
                            displayToolName = displayToolName,
                            invocation = invocation,
                            durationMs = durationMs,
                            partialResultText = collectedResults.combinedResultText(),
                        )
                    withContext(NonCancellable) {
                        ToolExecutionTimingRepository.markFinished(
                            timingScopeId,
                            invocation,
                            cancelledResult,
                            durationMs = durationMs,
                            state = ToolExecutionState.COMPLETED,
                        )
                        try {
                            publishResult(cancelledResult)
                        } catch (emitError: Exception) {
                            AppLogger.w(
                                TAG,
                                "Failed to persist cancelled tool result: ${emitError.message}",
                            )
                        }
                    }
                }
                throw cancellation
            } finally {
                // Deferred (parallel group) members publish their result from the single driver
                // coroutine; the finished event must follow that result, so it is sent there too
                // instead of here. Non-deferred tools keep the original started -> result ->
                // finished order.
                if (deferredResultSink == null) {
                    toolHandler.notifyToolExecutionFinished(invocation.tool)
                }
            }
        }
    }

    /**
     * 构建工具不可用的错误信息，统一逻辑避免重复
     */
    private suspend fun buildToolNotAvailableErrorMessage(
        toolName: String,
        packageManager: PackageManager,
        toolHandler: AIToolHandler
    ): String {
        return when {
            toolName.contains('.') && !toolName.contains(':') -> {
                val parts = toolName.split('.', limit = 2)
                "Tool invocation syntax error: for tools inside a package, use the 'packName:toolName' format instead of '${toolName}'. You may want to call '${parts.getOrNull(0)}:${parts.getOrNull(1)}'."
            }

            toolName.contains(':') -> {
                val parts = toolName.split(':', limit = 2)
                val packName = parts[0]
                val toolNamePart = parts.getOrNull(1) ?: ""
                val isJsPackageAvailable = packageManager.getAvailablePackages().containsKey(packName)
                val isMcpServerAvailable = packageManager.getAvailableServerPackages().containsKey(packName)
                val isAvailable = isJsPackageAvailable || isMcpServerAvailable

                if (!isAvailable) {
                    "The tool package or MCP server '$packName' does not exist."
                } else {
                    // 包存在，检查是否已激活（通过检查该包的任何工具是否已注册）
                    val packageTools =
                        packageManager.getPackageTools(packName)?.tools ?: emptyList()
                    val isAdviceTool = packageTools.any { it.advice && it.name == toolNamePart }
                    val isPackageActivated = packageTools
                        .filter { !it.advice }
                        .any { toolHandler.getToolExecutor("$packName:${it.name}") != null }

                    if (isAdviceTool) {
                        "Tool '$toolNamePart' is an advice-only entry in package '$packName' and is not executable."
                    } else if (isPackageActivated) {
                        // 包已激活但工具不存在
                        "Tool '$toolNamePart' does not exist in tool package '$packName'. Please use the 'use_package' tool and specify package name '$packName' to list all available tools in this package."
                    } else {
                        // 包未激活
                        "Tool package '$packName' is not activated. Auto-activation was attempted but failed, or tool '$toolNamePart' does not exist. Please use 'use_package' with package name '$packName' to check available tools."
                    }
                }
            }

            else -> {
                // 检查是否直接把包名当作工具名调用了
                val isPackageName = packageManager.getAvailablePackages().containsKey(toolName)
                if (isPackageName) {
                    "Error: '$toolName' is a tool package, not a tool. Please use the 'use_package' tool with package name '$toolName' to activate this package before using its tools."
                } else {
                    "Tool '${toolName}' is unavailable or does not exist. If this is a tool inside a package, call it using the 'packName:toolName' format."
                }
            }
        }
    }

}

internal fun shouldInterruptPendingToolBatch(results: List<ToolResult>): Boolean =
    results.any { result -> result.interruptTurn }

internal fun applyDeferredPermissionReviewCircuit(
    parentChatId: String,
    turnScopeId: String?,
    decision: ToolPermissionDecision,
    wasAutomaticallyReviewed: Boolean,
): ToolPermissionDecision =
    when {
        decision is ToolPermissionDecision.Denied &&
            decision.source == ToolPermissionDenialSource.AUTOMATIC_REVIEW -> {
            val circuit = PermissionReviewCircuitBreaker.recordDenial(parentChatId, turnScopeId)
            // Keep a decision that already interrupts the turn: the breaker returns false once it
            // has interrupted, so copying only its flag would clear the earlier signal and let the
            // rest of the batch run tools that do not need a review.
            decision.copy(interruptTurn = decision.interruptTurn || circuit.interruptTurn)
        }
        decision is ToolPermissionDecision.Allowed && wasAutomaticallyReviewed -> {
            PermissionReviewCircuitBreaker.recordNonDenial(parentChatId, turnScopeId)
            decision
        }
        else -> decision
    }

internal data class ToolPermissionCheckResult(
    val decision: ToolPermissionDecision,
    val errorResult: ToolResult?,
) {
    val granted: Boolean
        get() = decision is ToolPermissionDecision.Allowed
}

internal suspend fun <T, R> parallelMapPreservingOrder(
    values: List<T>,
    transform: suspend (T) -> R,
): List<R> = coroutineScope {
    values.map { value -> async { transform(value) } }.awaitAll()
}

/**
 * Runs one batch's permission checks, one at a time in the order [values] was given when [ordered],
 * and through [parallelMapPreservingOrder] otherwise.
 *
 * Order belongs to the reviewer: the first action of a batch builds its prompt and the reviews behind
 * it continue that conversation, so a batch the reviewer serves has to reach it in the order the
 * model asked for its actions. Every other level decides each action on its own, and there a batch can
 * be worked through in parallel.
 */
internal suspend fun <T, R> mapBatchInReviewOrder(
    values: List<T>,
    ordered: Boolean,
    transform: suspend (T) -> R,
): List<R> =
    if (ordered) {
        values.map { value -> transform(value) }
    } else {
        parallelMapPreservingOrder(values, transform)
    }

/**
 * Whether one batch holds an action the independent reviewer serves, which is the only thing that
 * makes the batch's order matter: every other level decides each action on its own, and only
 * [PermissionLevel.AUTO_REVIEW] can route an action to the reviewer.
 */
internal fun batchNeedsReviewOrder(levels: List<PermissionLevel>): Boolean =
    levels.any { level -> level == PermissionLevel.AUTO_REVIEW }

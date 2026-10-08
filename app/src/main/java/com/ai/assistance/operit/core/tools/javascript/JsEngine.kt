package com.ai.assistance.operit.core.tools.javascript

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import androidx.annotation.Keep
import androidx.core.content.ContextCompat
import com.ai.assistance.operit.api.chat.ChatRuntimeHolder
import com.ai.assistance.operit.api.chat.ChatRuntimeSlot
import com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager
import com.ai.assistance.operit.api.chat.llmprovider.MediaLinkParser
import com.ai.assistance.operit.core.chat.logMessageTiming
import com.ai.assistance.operit.core.chat.messageTimingNow
import com.ai.assistance.operit.core.tools.AIToolHandler
import com.ai.assistance.operit.core.tools.packTool.PackageManager
import com.ai.assistance.operit.core.tools.packTool.ToolPkgApiCompatibility
import com.ai.assistance.operit.core.tools.packTool.ToolPkgApiVersion
import com.ai.assistance.operit.core.tools.packTool.TOOLPKG_EVENT_MESSAGE_PROCESSING
import com.ai.assistance.operit.data.db.AppDatabase
import com.ai.assistance.operit.features.draw.CodexDrawBridge
import com.ai.assistance.operit.data.preferences.CharacterCardManager
import com.ai.assistance.operit.data.repository.SubagentRunRepository
import com.ai.assistance.operit.features.reading.ReadingCompanionAudit
import com.ai.assistance.operit.features.reading.ReadingCompanionBridge
import com.ai.assistance.operit.features.reading.ReadingCompanionAutoCommentary
import com.ai.assistance.operit.features.reading.ReadingCompanionService
import com.ai.assistance.operit.features.reading.ReadingCompanionStore
import com.ai.assistance.operit.ui.main.navigation.AppRouteDiscoveryGateway
import com.ai.assistance.operit.ui.main.navigation.AppRouterGateway
import com.ai.assistance.operit.ui.main.navigation.RouteEntrySource
import com.ai.assistance.operit.ui.main.navigation.RouteRuntime
import com.ai.assistance.operit.ui.common.composedsl.ComposeDslFilePickerHostRegistry
import com.ai.assistance.operit.ui.common.composedsl.ComposeDslWebViewHostRegistry
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.util.ImagePoolManager
import com.ai.assistance.operit.util.LocaleUtils
import com.ai.assistance.operit.util.OperitPaths
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

internal fun isReadingCompanionBridgeCaller(
    packageName: String?,
    containerPackageName: String?,
): Boolean =
    containerPackageName == ReadingCompanionService.TOOLPKG_ID &&
        (
            packageName == ReadingCompanionService.SUBPACKAGE_NAME ||
                packageName == ReadingCompanionService.AUTO_COMMENTARY_SUBPACKAGE_NAME ||
                packageName == "reading_companion_tasks" ||
                packageName == "reading_companion_manage"
        )

/**
 * JavaScript 引擎 - 通过 QuickJS 执行 JavaScript 脚本并提供与 Android 原生代码的交互机制
 */
class JsEngine(private val context: Context) {
    companion object {
        private const val TAG = "JsEngine"
        private const val TOOLPKG_TAG = "ToolPkg"
        private const val BINARY_DATA_THRESHOLD = 32 * 1024
        private const val BINARY_HANDLE_PREFIX = "@binary_handle:"
        private const val DIRECT_SCRIPT_EXECUTION_FUNCTION = "__operit_run_inline_code__"
    }

    private val bitmapRegistry = ConcurrentHashMap<String, Bitmap>()
    private val binaryDataRegistry = ConcurrentHashMap<String, ByteArray>()
    private val javaObjectRegistry = ConcurrentHashMap<String, Any>()
    private val externalJavaCodeLoader = JsExternalJavaCodeLoader(context)

    private val toolHandler = AIToolHandler.getInstance(context)
    private val packageManager by lazy { PackageManager.getInstance(context, toolHandler) }
    private val toolCallInterface = JsToolCallInterface()

    @Volatile
    private var quickJsThread: Thread? = null

    private val quickJsExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "OperitQuickJsEngine").apply {
            isDaemon = true
            quickJsThread = this
        }
    }
    private val quickJsDispatcher = quickJsExecutor.asCoroutineDispatcher()
    private val engineScope = CoroutineScope(SupervisorJob() + quickJsDispatcher)
    private val nativeBridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val readingCompanionBridgeJobs = ConcurrentHashMap<String, Job>()
    private val codexDrawBridgeJobs = ConcurrentHashMap<String, Job>()
    private val quickJsInitLock = Any()
    private val destroyed = AtomicBoolean(false)

    @Volatile
    private var quickJs: OperitQuickJsEngine? = null

    @Volatile
    private var boundToolPkgContainerName: String? = null

    private data class ExecutionSession(
        val callId: String,
        val future: CompletableFuture<Any?>,
        val intermediateResultCallback: ((Any?) -> Unit)?,
        val dispatchIntermediateOnMain: Boolean,
        val envOverrides: Map<String, String>,
        val packageChatId: String?,
        val packageName: String?,
        val toolRuntimeContext: ToolExecutionManager.ToolRuntimeContext?,
        val toolPkgApiVersion: ToolPkgApiVersion?,
        val toolPkgLogSnapshot: JsToolPkgExecutionContext.LogSnapshot,
        val executionListener: JsExecutionListener?
    )

    private data class PendingJsBridgeCallback(
        val requestId: String,
        val jsObjectId: String,
        val methodName: String,
        val argsJson: String,
        val future: CompletableFuture<String>
    )

    private val activeExecutionSessions = ConcurrentHashMap<String, ExecutionSession>()
    private val pendingJsBridgeCallbacks = ConcurrentLinkedQueue<PendingJsBridgeCallback>()
    private val pendingJsBridgeCallbackMap = ConcurrentHashMap<String, PendingJsBridgeCallback>()
    private var jsEnvironmentInitialized = false

    private val toolPkgExecutionContext = JsToolPkgExecutionContext()
    private val toolPkgRegistrationSession = JsToolPkgRegistrationSession()

    internal fun bindToolPkgContainer(containerPackageName: String) {
        val normalized = containerPackageName.trim()
        require(normalized.isNotBlank()) { "ToolPkg execution container is required" }
        synchronized(this) {
            val current = boundToolPkgContainerName
            check(current == null || current == normalized) {
                "JsEngine is already bound to ToolPkg container '$current'"
            }
            boundToolPkgContainerName = normalized
        }
    }

    private fun canScheduleQuickJsWork(): Boolean {
        return !destroyed.get() && !quickJsExecutor.isShutdown && !quickJsExecutor.isTerminated
    }

    fun <T> withTemporaryToolPkgTextResourceResolver(
        resolver: (String, String) -> String?,
        block: () -> T
    ): T {
        return toolPkgExecutionContext.withTemporaryTextResourceResolver(resolver, block)
    }

    private fun resolveTemporaryToolPkgTextResource(
        packageNameOrSubpackageId: String,
        resourcePath: String
    ): String? {
        return toolPkgExecutionContext.resolveTemporaryTextResource(
            packageNameOrSubpackageId = packageNameOrSubpackageId,
            resourcePath = resourcePath,
            onResolverFailure = { e ->
                AppLogger.e(
                    TAG,
                    "Temporary toolpkg text resource resolver failed: package/subpackage=$packageNameOrSubpackageId, path=$resourcePath",
                    e
                )
            }
        )
    }

    private fun hasTemporaryToolPkgTextResourceResolver(): Boolean {
        return toolPkgExecutionContext.hasTemporaryTextResourceResolver()
    }

    private fun getJavaBridgeBaseClassLoader(): ClassLoader {
        return context.classLoader
            ?: this::class.java.classLoader
            ?: ClassLoader.getSystemClassLoader()
    }

    private fun getJavaBridgeClassLoader(): ClassLoader {
        return externalJavaCodeLoader.getEffectiveClassLoader(getJavaBridgeBaseClassLoader())
    }

    private fun <T> runOnQuickJsThreadBlocking(
        allowWhenDestroyed: Boolean = false,
        block: () -> T
    ): T {
        check(allowWhenDestroyed || !destroyed.get()) { "JsEngine already destroyed" }
        return if (Thread.currentThread() === quickJsThread) {
            block()
        } else {
            runBlocking(quickJsDispatcher) {
                block()
            }
        }
    }

    private fun ensureQuickJs() {
        check(!destroyed.get()) { "JsEngine already destroyed" }
        if (quickJs != null) {
            return
        }
        synchronized(quickJsInitLock) {
            check(!destroyed.get()) { "JsEngine already destroyed" }
            if (quickJs != null) {
                return
            }
            try {
                val engine = runOnQuickJsThreadBlocking {
                    OperitQuickJsEngine().also {
                        it.bindNativeInterface(toolCallInterface)
                    }
                }
                quickJs = engine
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error initializing QuickJS: ${e.message}", e)
                throw e
            }
        }
    }

    private fun interruptQuickJs(reason: String) {
        val engine = quickJs ?: return
        try {
            engine.interrupt()
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error interrupting QuickJS: $reason, ${e.message}", e)
        }
    }

    private fun disposeQuickJsForReinit(reason: String) {
        synchronized(quickJsInitLock) {
            val engine = quickJs
            if (engine == null) {
                jsEnvironmentInitialized = false
                return
            }
            // Closing is queued behind current QuickJS work, so a synchronous loop must be
            // interrupted first or reinitialization can wait forever.
            interruptQuickJs("reinitialize: $reason")
            try {
                runOnQuickJsThreadBlocking {
                    engine.close()
                }
            } catch (closeError: Exception) {
                AppLogger.e(TAG, "Error closing QuickJS for reinit: $reason, ${closeError.message}", closeError)
            } finally {
                quickJs = null
                jsEnvironmentInitialized = false
            }
        }
    }

    private fun <T> evaluateQuickJsBlocking(script: String, fileName: String = "<eval>"): T? {
        ensureQuickJs()
        val engine = quickJs ?: return null
        return if (Thread.currentThread() === quickJsThread) {
            runBlocking {
                engine.evaluate<T>(script, fileName)
            }
        } else {
            runBlocking(quickJsDispatcher) {
                engine.evaluate<T>(script, fileName)
            }
        }
    }

    private fun launchQuickJsEvaluation(
        script: String,
        fileName: String = "<eval>",
        onError: ((Exception) -> Unit)? = null
    ) {
        if (!canScheduleQuickJsWork()) {
            return
        }
        val engine = quickJs ?: return
        try {
            engineScope.launch {
                try {
                    if (!canScheduleQuickJsWork()) {
                        return@launch
                    }
                    engine.evaluate<Any?>(script, fileName)
                } catch (e: Exception) {
                    if (onError != null) {
                        onError(e)
                    } else {
                        AppLogger.e(TAG, "QuickJS evaluation failed: ${e.message}", e)
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
        }
    }

    private fun launchQuickJsFunctionCall(
        functionName: String,
        argsJson: String,
        callSite: String = "<call:$functionName>",
        onError: ((Exception) -> Unit)? = null
    ) {
        if (!canScheduleQuickJsWork()) {
            return
        }
        val engine = quickJs ?: return
        try {
            engineScope.launch {
                try {
                    if (!canScheduleQuickJsWork()) {
                        return@launch
                    }
                    engine.callFunction<Any?>(functionName, argsJson, callSite)
                } catch (e: Exception) {
                    if (onError != null) {
                        onError(e)
                    } else {
                        AppLogger.e(TAG, "QuickJS function call failed: ${e.message}", e)
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
        }
    }


    private fun nextExecutionCallId(): String {
        return "operit_call_${UUID.randomUUID().toString().replace("-", "")}" 
    }

    private fun createExecutionSession(
        callId: String,
        script: String,
        functionName: String,
        params: Map<String, Any?>,
        envOverrides: Map<String, String>,
        onIntermediateResult: ((Any?) -> Unit)?,
        dispatchIntermediateOnMain: Boolean,
        toolPkgApiVersion: ToolPkgApiVersion?,
        executionListener: JsExecutionListener?
    ): ExecutionSession {
        return ExecutionSession(
            callId = callId,
            future = CompletableFuture(),
            intermediateResultCallback = onIntermediateResult,
            dispatchIntermediateOnMain = dispatchIntermediateOnMain,
            envOverrides = envOverrides,
            packageChatId =
                params["__operit_package_chat_id"]
                    ?.toString()
                    ?.trim()
                    ?.ifBlank { null },
            packageName =
                params["__operit_package_name"]
                    ?.toString()
                    ?.trim()
                    ?.ifBlank { null },
            toolRuntimeContext = ToolExecutionManager.currentToolRuntimeContext(),
            toolPkgApiVersion = toolPkgApiVersion,
            toolPkgLogSnapshot = toolPkgExecutionContext.capture(script, functionName, params),
            executionListener = executionListener
        )
    }

    private fun resolveExecutionSession(callId: String): ExecutionSession? {
        return activeExecutionSessions[callId.trim()]
    }

    private fun resolveToolPkgApiVersionForExecution(
        params: Map<String, Any?>,
        explicitApiVersion: String?,
    ): ToolPkgApiVersion? {
        val declared = explicitApiVersion?.takeIf { it.isNotBlank() } ?: run {
            val packageName = (params["__operit_ui_package_name"] ?: params["__operit_package_name"])
                ?.toString()?.takeIf { it.isNotBlank() } ?: return null
            packageManager.getToolPkgApiVersion(packageName) ?: return null
        }
        return ToolPkgApiCompatibility.requireSupported(declared)
    }

    private fun buildToolPkgApiContextJson(apiVersion: ToolPkgApiVersion?): Any =
        apiVersion?.let { JSONObject().put("apiVersion", it.toString()) } ?: JSONObject.NULL

    private fun removeExecutionSession(callId: String): ExecutionSession? {
        val normalizedCallId = callId.trim()
        readingCompanionBridgeJobs.remove(normalizedCallId)?.cancel()
        codexDrawBridgeJobs.remove(normalizedCallId)?.cancel()
        return activeExecutionSessions.remove(normalizedCallId)
    }

    private fun clearPendingJsBridgeCallbacks(reason: String) {
        val pending = pendingJsBridgeCallbackMap.values.toList()
        pendingJsBridgeCallbackMap.clear()
        pendingJsBridgeCallbacks.clear()
        pending.forEach { request ->
            if (!request.future.isDone) {
                request.future.complete(
                    JSONObject()
                        .put("success", false)
                        .put("message", reason)
                        .toString()
                )
            }
        }
    }

    private fun requestPendingJsBridgeCallbackPump() {
        ensureQuickJs()
        if (quickJs == null || !jsEnvironmentInitialized) {
            return
        }
        launchQuickJsEvaluation(
            script =
                """
                (function() {
                    var processPending =
                        (typeof globalThis !== 'undefined' &&
                         typeof globalThis.__operitProcessPendingJavaBridgeCallbacks === 'function')
                            ? globalThis.__operitProcessPendingJavaBridgeCallbacks
                            : (typeof processPendingJavaBridgeCallbacks === 'function'
                                ? processPendingJavaBridgeCallbacks
                                : null);
                    if (typeof processPending !== 'function') {
                        return 0;
                    }

                    var total = 0;
                    while (total < 256) {
                        var processed = Number(processPending(32)) || 0;
                        if (processed <= 0) {
                            break;
                        }
                        total += processed;
                    }
                    return total;
                })();
                """.trimIndent(),
            fileName = "quickjs/runtime/java-bridge-pending-callback-pump.js",
            onError = { error ->
                AppLogger.e(
                    TAG,
                    "Error pumping pending Java bridge callbacks: ${error.message}",
                    error
                )
            }
        )
    }

    private fun pollPendingJsBridgeCallbackPayload(): String? {
        while (true) {
            val request = pendingJsBridgeCallbacks.poll() ?: return null
            val active = pendingJsBridgeCallbackMap[request.requestId]
            if (active == null || active.future.isDone) {
                pendingJsBridgeCallbackMap.remove(request.requestId, request)
                continue
            }
            return JSONObject()
                .put("id", request.requestId)
                .put("jsObjectId", request.jsObjectId)
                .put("methodName", request.methodName)
                .put("argsJson", request.argsJson)
                .toString()
        }
    }

    private fun resolvePendingJsBridgeCallback(requestId: String, responseJson: String) {
        val normalizedId = requestId.trim()
        if (normalizedId.isEmpty()) {
            return
        }
        val request = pendingJsBridgeCallbackMap.remove(normalizedId) ?: return
        if (!request.future.isDone) {
            request.future.complete(responseJson)
        }
    }

    private fun drainExecutionSessions(reason: String): List<ExecutionSession> {
        val sessions = activeExecutionSessions.values.toList()
        activeExecutionSessions.clear()
        sessions.forEach { session ->
            readingCompanionBridgeJobs.remove(session.callId)?.cancel()
            codexDrawBridgeJobs.remove(session.callId)?.cancel()
            if (!session.future.isDone) {
                session.future.complete(buildJsExecutionErrorPayload(reason))
            }
        }
        return sessions
    }

    private fun cancelAllExecutionSessions(reason: String) {
        drainExecutionSessions(reason).forEach { session ->
            cancelExecutionSessionInJs(
                callId = session.callId,
                reason = reason
            )
        }
    }

    private fun cancelExecutionSessionInJs(callId: String, reason: String) {
        ensureQuickJs()
        val safeCallId = JSONObject.quote(callId)
        val safeReason = JSONObject.quote(reason)
        launchQuickJsEvaluation(
            script =
                """
                    (function() {
                        var root = typeof globalThis !== 'undefined'
                            ? globalThis
                            : (typeof window !== 'undefined' ? window : this);
                        if (typeof root.__operitCancelCallSession === 'function') {
                            root.__operitCancelCallSession($safeCallId, $safeReason);
                        }
                    })();
                """.trimIndent(),
            fileName = "quickjs/runtime/cancel-call-session.js",
            onError = { e ->
                AppLogger.e(TAG, "Error canceling JS execution session $callId: ${e.message}", e)
            }
        )
    }

    private fun withToolPkgPluginTag(message: String): String {
        return toolPkgExecutionContext.withPluginTag(null, message)
    }

    private fun withToolPkgPluginTag(session: ExecutionSession?, message: String): String {
        return toolPkgExecutionContext.withPluginTag(session?.toolPkgLogSnapshot, message)
    }

    private fun withToolPkgCodeContext(session: ExecutionSession?, message: String): String {
        return toolPkgExecutionContext.withCodeContext(session?.toolPkgLogSnapshot, message)
    }

    private fun runtimeBootstrapModules(): List<JsBootstrapModule> {
        return buildRuntimeBootstrapModules(
            context = context,
            operitDownloadDir = OperitPaths.operitRootPathSdcard(),
            operitCleanOnExitDir = OperitPaths.cleanOnExitPathSdcard()
        )
    }

    private fun evaluateBootstrapModule(module: JsBootstrapModule) {
        if (module.source.isBlank()) {
            return
        }
        try {
            evaluateQuickJsBlocking<Any?>(module.source, module.fileName)
            exposeBootstrapGlobals(module)
        } catch (e: Exception) {
            val globalsSummary = module.globals.joinToString(prefix = "[", postfix = "]")
            AppLogger.e(
                TAG,
                "Bootstrap module failed: file=${module.fileName}, scriptLength=${module.source.length}, globals=$globalsSummary, preview=${summarizeJavaScriptForLog(module.source)}",
                e
            )
            throw IllegalStateException("Bootstrap failed for ${module.fileName}: ${e.message}", e)
        }
    }

    private fun summarizeJavaScriptForLog(source: String, maxLength: Int = 320): String {
        if (source.isBlank()) {
            return ""
        }
        val normalized =
            buildString(source.length) {
                source.forEach { ch ->
                    append(
                        when (ch) {
                            '\n', '\r', '\t' -> ' '
                            else -> ch
                        }
                    )
                }
            }.trim()
        return if (normalized.length <= maxLength) {
            normalized
        } else {
            normalized.take(maxLength - 3) + "..."
        }
    }

    private fun exposeBootstrapGlobals(module: JsBootstrapModule) {
        if (module.globals.isEmpty()) {
            return
        }
        evaluateQuickJsBlocking<Any?>(
            buildBootstrapGlobalExposureScript(module.globals),
            "${module.fileName}#globals"
        )
    }

    private fun buildBootstrapGlobalExposureScript(globalNames: List<String>): String {
        val exposeStatements =
            globalNames.joinToString("\n") { name ->
                val quotedName = JSONObject.quote(name)
                "expose($quotedName, typeof $name !== 'undefined' ? $name : undefined);"
            }

        return """
            (function() {
                var root = typeof globalThis !== 'undefined'
                    ? globalThis
                    : (typeof window !== 'undefined' ? window : this);
                var expose = typeof root.__operitExpose === 'function'
                    ? root.__operitExpose
                    : function(name, value) {
                        var key = String(name || '').trim();
                        if (!key || value === undefined) {
                            return;
                        }
                        try { root[key] = value; } catch (_error) {}
                        try { window[key] = value; } catch (_error2) {}
                    };
                $exposeStatements
            })();
        """.trimIndent()
    }

    private fun invokeJavaBridgeJsObjectCallbackSync(
        jsObjectId: String,
        methodName: String,
        argsJson: String
    ): String {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return JSONObject()
                .put("success", false)
                .put("message", "java bridge callback cannot synchronously invoke JS on main thread")
                .toString()
        }

        if (Thread.currentThread() === quickJsThread) {
            return JSONObject()
                .put("success", false)
                .put("message", "java bridge callback cannot synchronously invoke JS from quickjs thread")
                .toString()
        }

        ensureQuickJs()
        if (quickJs == null || !jsEnvironmentInitialized) {
            return JSONObject()
                .put("success", false)
                .put("message", "java bridge callback runtime unavailable")
                .toString()
        }

        val request =
            PendingJsBridgeCallback(
                requestId = UUID.randomUUID().toString(),
                jsObjectId = jsObjectId.trim(),
                methodName = methodName.trim(),
                argsJson = argsJson.trim().ifEmpty { "[]" },
                future = CompletableFuture()
            )
        pendingJsBridgeCallbackMap[request.requestId] = request
        pendingJsBridgeCallbacks.add(request)
        requestPendingJsBridgeCallbackPump()

        return try {
            request.future.get(30, TimeUnit.SECONDS)
        } catch (e: Exception) {
            JSONObject()
                .put("success", false)
                .put("message", "java bridge callback wait failed: ${e.message ?: e.javaClass.simpleName}")
                .toString()
        } finally {
            pendingJsBridgeCallbackMap.remove(request.requestId)
            pendingJsBridgeCallbacks.remove(request)
        }
    }

    private fun releaseJavaBridgeJsObjectSync(jsObjectId: String): Boolean {
        val normalizedId = jsObjectId.trim()
        if (normalizedId.isEmpty() || !jsEnvironmentInitialized) {
            return false
        }

        ensureQuickJs()
        if (quickJs == null) {
            return false
        }

        val releaseScript =
            """
            (function() {
                try {
                    var __release =
                        (typeof globalThis !== 'undefined' && typeof globalThis.__operitJavaBridgeReleaseJsObject === 'function')
                            ? globalThis.__operitJavaBridgeReleaseJsObject
                            : undefined;
                    if (!__release) {
                        return false;
                    }
                    return !!__release(${JSONObject.quote(normalizedId)});
                } catch (_error) {
                    return false;
                }
            })();
            """.trimIndent()

        return try {
            when (
                val result =
                    evaluateQuickJsBlocking<Any?>(
                        script = releaseScript,
                        fileName = "quickjs/runtime/java-bridge-release.js"
                    )
            ) {
                is Boolean -> result
                is String -> result.equals("true", ignoreCase = true)
                is Number -> result.toInt() != 0
                else -> false
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to auto-release JS interface object $normalizedId: ${e.message}", e)
            false
        }
    }

    private fun splitBridgeResult(raw: String): Pair<String?, Any?> {
        if (raw.isBlank()) {
            return Pair("empty bridge response", null)
        }
        return try {
            val token = JSONTokener(raw).nextValue()
            if (token is JSONObject) {
                val success = token.optBoolean("success", false)
                val data = token.opt("data")
                val error = token.optString("message").ifBlank { null }
                if (success) {
                    Pair(null, data)
                } else {
                    Pair(error ?: "bridge call failed", null)
                }
            } else {
                Pair("invalid bridge response format", null)
            }
        } catch (e: Exception) {
            Pair("failed to parse bridge response: ${e.message}", null)
        }
    }

    /** 初始化 JavaScript 环境，加上 QuickJS 兼容层、核心运行时与工具桥 */
    private fun initJavaScriptEnvironment() {
        synchronized(quickJsInitLock) {
            if (jsEnvironmentInitialized) {
                return
            }

            ensureQuickJs()
            try {
                runtimeBootstrapModules().forEach(::evaluateBootstrapModule)
                jsEnvironmentInitialized = true
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to initialize JS environment: ${e.message}", e)
                disposeQuickJsForReinit("bootstrap initialization failure")
            }
            if (!jsEnvironmentInitialized) {
                AppLogger.e(TAG, "QuickJS init script failed to produce runtime bridge")
            }
        }
    }

    /**
     * 执行 JavaScript 脚本并调用其中的特定函数
     * @param script 完整的JavaScript脚本内容
     * @param functionName 要调用的函数名称
     * @param params 要传递给函数的参数
     * @param timeoutSec 最长等待秒数；null 表示等待实际完成或主动取消
     * @return 函数执行结果
     */
    internal fun executeScriptFunction(
            script: String,
            functionName: String,
            params: Map<String, Any?>,
            envOverrides: Map<String, String> = emptyMap(),
            onIntermediateResult: ((Any?) -> Unit)? = null,
            dispatchIntermediateOnMain: Boolean = true,
            timeoutSec: Long? = JsTimeoutConfig.MAIN_TIMEOUT_SECONDS.toLong(),
            toolPkgApiVersion: String? = null,
            executionListener: JsExecutionListener? = null
    ): Any? {
        val effectiveParams = params.toMutableMap()
        val explicitLanguage = effectiveParams["__operit_package_lang"]?.toString()?.trim().orEmpty()
        if (explicitLanguage.isBlank()) {
            effectiveParams["__operit_package_lang"] =
                LocaleUtils.getCurrentLanguage(context).trim().ifBlank { "en" }
        }

        val timingEvent = effectiveParams["event"]?.toString()?.trim().orEmpty()
        val timingPluginId =
            effectiveParams["pluginId"]?.toString()?.trim().orEmpty()
                .ifBlank {
                    effectiveParams["__operit_plugin_id"]?.toString()?.trim().orEmpty()
                }
                .ifBlank { "none" }
        val shouldLogTiming = timingEvent.equals(TOOLPKG_EVENT_MESSAGE_PROCESSING, ignoreCase = true)
        val totalStartTime = if (shouldLogTiming) messageTimingNow() else 0L

        val initQuickJsStartTime = if (shouldLogTiming) messageTimingNow() else 0L
        ensureQuickJs()
        if (shouldLogTiming) {
            logMessageTiming(
                stage = "toolpkg.jsEngine.initQuickJs",
                startTimeMs = initQuickJsStartTime,
                details = "function=$functionName, plugin=$timingPluginId"
            )
        }

        if (!jsEnvironmentInitialized) {
            val initJavaScriptEnvironmentStartTime = if (shouldLogTiming) messageTimingNow() else 0L
            initJavaScriptEnvironment()
            if (shouldLogTiming) {
                logMessageTiming(
                    stage = "toolpkg.jsEngine.initJavaScriptEnvironment",
                    startTimeMs = initJavaScriptEnvironmentStartTime,
                    details = "function=$functionName, plugin=$timingPluginId"
                )
            }
            if (!jsEnvironmentInitialized) {
                val failureReason = "QuickJS runtime initialization failed"
                if (shouldLogTiming) {
                    logMessageTiming(
                        stage = "toolpkg.jsEngine.total",
                        startTimeMs = totalStartTime,
                        details = "function=$functionName, plugin=$timingPluginId, success=false, reason=$failureReason"
                    )
                }
                return buildJsExecutionErrorPayload(failureReason)
            }
        }

        val executionToolPkgApiVersion = resolveToolPkgApiVersionForExecution(effectiveParams, toolPkgApiVersion)
        val callId = nextExecutionCallId()
        val session =
            createExecutionSession(
                callId = callId,
                script = script,
                functionName = functionName,
                params = effectiveParams,
                envOverrides = envOverrides,
                onIntermediateResult = onIntermediateResult,
                dispatchIntermediateOnMain = dispatchIntermediateOnMain,
                toolPkgApiVersion = executionToolPkgApiVersion,
                executionListener = executionListener
            )
        activeExecutionSessions[callId] = session

        val buildExecutionScriptStartTime = if (shouldLogTiming) messageTimingNow() else 0L
        val paramsObject = JSONObject(effectiveParams)
        val paramsJson = paramsObject.toString()
        val safeTimeoutSec = timeoutSec?.let { if (it <= 0L) 1L else it }
        val preTimeoutMs =
            if (safeTimeoutSec == null) {
                null
            } else {
                (safeTimeoutSec - JsTimeoutConfig.PRE_TIMEOUT_LEAD_SECONDS)
                    .coerceAtLeast(1L) * 1000L
            }
        val executionArgsJson =
            JSONArray()
                .put(callId)
                .put(paramsObject)
                .put(script)
                .put(functionName)
                .put(safeTimeoutSec ?: JSONObject.NULL)
                .put(preTimeoutMs ?: JSONObject.NULL)
                .put(buildToolPkgApiContextJson(session.toolPkgApiVersion))
                .toString()
        if (shouldLogTiming) {
            logMessageTiming(
                stage = "toolpkg.jsEngine.buildExecutionScript",
                startTimeMs = buildExecutionScriptStartTime,
                details = "function=$functionName, plugin=$timingPluginId, scriptLength=${script.length}, paramsLength=${paramsJson.length}, argsLength=${executionArgsJson.length}, directInvoke=true"
            )
        }

        launchQuickJsFunctionCall(
            functionName = TOOLPKG_EXECUTION_ENTRY_FUNCTION,
            argsJson = executionArgsJson,
            callSite = "quickjs/runtime/execute-script.call",
            onError = { e ->
                AppLogger.e(
                    TAG,
                    "Failed to dispatch script execution: callId=$callId, function=$functionName, reason=${e.message}",
                    e
                )
                removeExecutionSession(callId)
                session.executionListener?.onFailed(callId, e.message ?: "dispatch failed")
                if (!session.future.isDone) {
                    session.future.complete(buildJsExecutionErrorPayload(e.message ?: "dispatch failed"))
                }
            }
        )

        val waitResultStartTime = if (shouldLogTiming) messageTimingNow() else 0L
        return try {
            val result =
                if (safeTimeoutSec == null) {
                    session.future.get()
                } else {
                    session.future.get(safeTimeoutSec, TimeUnit.SECONDS)
                }
            removeExecutionSession(callId)
            if (shouldLogTiming) {
                logMessageTiming(
                    stage = "toolpkg.jsEngine.waitResult",
                    startTimeMs = waitResultStartTime,
                    details = "function=$functionName, plugin=$timingPluginId, callId=$callId, success=true, resultType=${result?.javaClass?.simpleName ?: "null"}"
                )
                logMessageTiming(
                    stage = "toolpkg.jsEngine.total",
                    startTimeMs = totalStartTime,
                    details = "function=$functionName, plugin=$timingPluginId, callId=$callId, success=true"
                )
            }
            result
        } catch (e: Exception) {
            if (
                e is java.util.concurrent.TimeoutException ||
                    e is InterruptedException
            ) {
                // JS-side cancellation uses this engine's executor. Native interruption must
                // happen first because a synchronous loop prevents the queued cancellation.
                interruptQuickJs("callId=$callId, function=$functionName")
            }
            if (e is InterruptedException) {
                Thread.currentThread().interrupt()
            }
            val failureReason =
                when (e) {
                    is java.util.concurrent.TimeoutException ->
                        "Script execution timed out after ${safeTimeoutSec ?: 1L} seconds"
                    else -> e.message ?: e.javaClass.simpleName
                }
            AppLogger.e(
                TAG,
                "Script execution timed out or failed: callId=$callId, function=$functionName, reason=$failureReason",
                e
            )
            removeExecutionSession(callId)
            cancelExecutionSessionInJs(callId, failureReason)
            session.executionListener?.onFailed(callId, failureReason)
            if (shouldLogTiming) {
                logMessageTiming(
                    stage = "toolpkg.jsEngine.waitResult",
                    startTimeMs = waitResultStartTime,
                    details = "function=$functionName, plugin=$timingPluginId, callId=$callId, success=false, reason=$failureReason"
                )
                logMessageTiming(
                    stage = "toolpkg.jsEngine.total",
                    startTimeMs = totalStartTime,
                    details = "function=$functionName, plugin=$timingPluginId, callId=$callId, success=false, reason=$failureReason"
                )
            }
            buildJsExecutionErrorPayload(failureReason)
        }
    }

    internal fun executeScriptCode(
            script: String,
            params: Map<String, Any?> = emptyMap(),
            envOverrides: Map<String, String> = emptyMap(),
            onIntermediateResult: ((Any?) -> Unit)? = null,
            dispatchIntermediateOnMain: Boolean = true,
            timeoutSec: Long = JsTimeoutConfig.MAIN_TIMEOUT_SECONDS.toLong(),
            executionListener: JsExecutionListener? = null
    ): Any? {
        val directParams = params.toMutableMap()
        directParams["__operit_inline_function_name"] = DIRECT_SCRIPT_EXECUTION_FUNCTION
        directParams["__operit_inline_function_source"] = buildDirectScriptExecutionSource(script)
        return executeScriptFunction(
            script = "",
            functionName = DIRECT_SCRIPT_EXECUTION_FUNCTION,
            params = directParams,
            envOverrides = envOverrides,
            onIntermediateResult = onIntermediateResult,
            dispatchIntermediateOnMain = dispatchIntermediateOnMain,
            timeoutSec = timeoutSec,
            executionListener = executionListener
        )
    }

    private fun buildDirectScriptExecutionSource(script: String): String {
        val waitsForExplicitComplete =
            Regex("""(^|[^\w$.])complete\s*\(""")
                .containsMatchIn(script)
        return buildString {
            append("async function(params) {\n")
            append("  var __operitWaitForExplicitComplete = ")
            append(if (waitsForExplicitComplete) "true" else "false")
            append(";\n")
            append("  var __operitOriginalComplete = complete;\n")
            append("  var __operitCompleteResolved = false;\n")
            append("  var __operitResolveCompleteWait;\n")
            append("  var __operitCompleteWait = new Promise(function(resolve) {\n")
            append("    __operitResolveCompleteWait = resolve;\n")
            append("  });\n")
            append("  complete = function(value) {\n")
            append("    try {\n")
            append("      return __operitOriginalComplete(value);\n")
            append("    } finally {\n")
            append("      if (!__operitCompleteResolved) {\n")
            append("        __operitCompleteResolved = true;\n")
            append("        __operitResolveCompleteWait();\n")
            append("      }\n")
            append("    }\n")
            append("  };\n")
            append("  try {\n")
            append(script)
            if (!script.endsWith("\n")) {
                append('\n')
            }
            append("    if (__operitWaitForExplicitComplete) {\n")
            append("      await __operitCompleteWait;\n")
            append("    }\n")
            append("  } finally {\n")
            append("    complete = __operitOriginalComplete;\n")
            append("  }\n")
            append("}")
        }
    }

    private fun normalizeToolPkgModulePath(modulePath: String): String? {
        return modulePath
            .trim()
            .replace('\\', '/')
            .trimStart('/')
            .ifBlank { null }
    }

    fun executeToolPkgMainRegistrationFunction(
        script: String,
        functionName: String,
        apiVersion: String = ToolPkgApiCompatibility.LEGACY_API_VERSION,
        params: Map<String, Any?> = emptyMap()
    ): ToolPkgMainRegistrationCapture {
        synchronized(toolPkgRegistrationSession) {
            toolPkgRegistrationSession.begin()
            try {
                val executionResult =
                    executeScriptFunction(
                        script = script,
                        functionName = functionName,
                        params = params,
                        toolPkgApiVersion = apiVersion,
                        timeoutSec = 12L
                    )
                return toolPkgRegistrationSession.finish(executionResult)
            } finally {
                toolPkgRegistrationSession.end()
            }
        }
    }

    fun executeComposeDslScript(
            script: String,
            runtimeOptions: Map<String, Any?> = emptyMap(),
            envOverrides: Map<String, String> = emptyMap()
    ): Any? {
        return executeScriptFunction(
                script = buildComposeDslRuntimeWrappedScript(script),
                functionName = "__operit_render_compose_dsl",
                params = runtimeOptions,
                envOverrides = envOverrides,
                timeoutSec = null
        )
    }

    fun executeComposeDslAction(
            actionId: String,
            payload: Any? = null,
            runtimeOptions: Map<String, Any?> = emptyMap(),
            envOverrides: Map<String, String> = emptyMap(),
            onIntermediateResult: ((Any?) -> Unit)? = null
    ): Any? {
        val normalizedActionId = actionId.trim()
        if (normalizedActionId.isBlank()) {
            return buildJsExecutionErrorPayload("compose action id is required")
        }
        val params = runtimeOptions.toMutableMap()
        params["__action_id"] = normalizedActionId
        if (payload != null) {
            params["__action_payload"] = payload
        }
        return executeScriptFunction(
                script = "",
                functionName = "__operit_dispatch_compose_dsl_action",
                params = params,
                envOverrides = envOverrides,
                onIntermediateResult = onIntermediateResult,
                timeoutSec = null
        )
    }

    fun rerenderComposeDslTree(
            runtimeOptions: Map<String, Any?> = emptyMap(),
            envOverrides: Map<String, String> = emptyMap()
    ): Any? {
        return executeScriptFunction(
                script = "",
                functionName = "__operit_rerender_compose_dsl",
                params = runtimeOptions,
                envOverrides = envOverrides,
                timeoutSec = null
        )
    }

    fun dispatchComposeDslActionAsync(
            actionId: String,
            payload: Any? = null,
            runtimeOptions: Map<String, Any?> = emptyMap(),
            envOverrides: Map<String, String> = emptyMap(),
            onIntermediateResult: ((Any?) -> Unit)? = null,
            onFinalResult: ((Any?) -> Unit)? = null,
            onComplete: (() -> Unit)? = null,
            onError: ((String) -> Unit)? = null
    ): Boolean {
        val normalizedActionId = actionId.trim()
        if (normalizedActionId.isBlank()) {
            onError?.invoke("compose action id is required")
            onComplete?.invoke()
            return false
        }

        Thread {
            val result =
                try {
                    executeComposeDslAction(
                        actionId = normalizedActionId,
                        payload = payload,
                        runtimeOptions = runtimeOptions,
                        envOverrides = envOverrides,
                        onIntermediateResult = onIntermediateResult
                    )
                } catch (e: Exception) {
                    val errorText = e.message?.trim().orEmpty().ifBlank { "compose action dispatch failed" }
                    AppLogger.e(TAG, "dispatch compose action failed: actionId=$normalizedActionId, error=$errorText", e)
                    ContextCompat.getMainExecutor(context).execute {
                        onError?.invoke(errorText)
                        onComplete?.invoke()
                    }
                    return@Thread
                }

            val errorText = extractJsExecutionErrorMessage(result)
            ContextCompat.getMainExecutor(context).execute {
                if (errorText != null) {
                    AppLogger.e(
                        TAG,
                        "dispatch compose action failed: actionId=$normalizedActionId, error=$errorText"
                    )
                    onError?.invoke(errorText)
                } else if (result != null) {
                    onFinalResult?.invoke(result)
                }
                onComplete?.invoke()
            }
        }.start()

        return true
    }

    internal fun exposeNativeInterfaceBridgeObject(): String {
        val handle = UUID.randomUUID().toString()
        return runCatching {
            javaObjectRegistry[handle] = toolCallInterface
            JSONObject()
                .put("success", true)
                .put(
                    "data",
                    JSONObject()
                        .put("__javaHandle", handle)
                        .put("__javaClass", toolCallInterface.javaClass.name)
                )
                .toString()
        }.getOrElse { error ->
            AppLogger.e(TAG, "Failed to expose NativeInterface bridge object: ${error.message}", error)
            JSONObject()
                .put("success", false)
                .put("message", error.message ?: "failed to expose NativeInterface bridge object")
                .toString()
        }
    }

    internal fun callExposedBridgeObject(
        instanceHandle: String,
        methodName: String,
        argsJson: String
    ): String {
        return JsJavaBridgeDelegates.callInstance(
            instanceHandle = instanceHandle,
            methodName = methodName,
            argsJson = argsJson,
            objectRegistry = javaObjectRegistry,
            jsCallbackInvoker = { jsObjectId, callbackMethodName, callbackArgsJson ->
                invokeJavaBridgeJsObjectCallbackSync(
                    jsObjectId = jsObjectId,
                    methodName = callbackMethodName,
                    argsJson = callbackArgsJson
                )
            },
            bridgeClassLoader = getJavaBridgeClassLoader()
        )
    }

    internal fun releaseExposedBridgeObject(instanceHandle: String): String {
        return JsJavaBridgeDelegates.releaseInstance(
            instanceHandle = instanceHandle,
            objectRegistry = javaObjectRegistry
        )
    }

    private fun buildToolPkgIpcFailure(message: String): String =
        JSONObject()
            .put("success", false)
            .put("message", message)
            .toString()

    private fun inferToolPkgIpcRuntimeFromContextKey(contextKey: String): String {
        val normalized = contextKey.trim().lowercase()
        return when {
            normalized.startsWith("toolpkg_main:") -> "main"
            normalized.startsWith("toolpkg_provider:") -> "provider"
            normalized.startsWith("toolpkg_compose_dsl:") -> "ui"
            normalized.startsWith("toolpkg_xml_render:") -> "ui"
            else -> ""
        }
    }

    private fun invokeToolPkgIpc(
        packageTarget: String,
        callerContextKey: String,
        targetContextKey: String,
        targetRuntime: String,
        channel: String,
        payloadJson: String
    ): String {
        val normalizedTarget = packageTarget.trim()
        if (normalizedTarget.isEmpty()) {
            return buildToolPkgIpcFailure("ToolPkg.ipc package target is empty")
        }
        val normalizedChannel = channel.trim()
        if (normalizedChannel.isEmpty()) {
            return buildToolPkgIpcFailure("ToolPkg.ipc channel is required")
        }
        val requestedRuntime = targetRuntime.trim().lowercase()
        if (
            requestedRuntime.isNotEmpty() &&
                requestedRuntime != "main" &&
                requestedRuntime != "ui" &&
                requestedRuntime != "sandbox" &&
                requestedRuntime != "provider"
        ) {
            return buildToolPkgIpcFailure("ToolPkg.ipc targetRuntime is invalid: $requestedRuntime")
        }
        packageManager.ensureInitialized()
        val containerRuntime =
            packageManager.toolPkgContainersInternal[normalizedTarget]
                ?: return buildToolPkgIpcFailure("ToolPkg container not found: $normalizedTarget")
        val explicitTargetContextKey = targetContextKey.trim()
        val resolvedTargetContextKey =
            if (explicitTargetContextKey.isNotEmpty()) {
                explicitTargetContextKey
            } else if (requestedRuntime.isEmpty() || requestedRuntime == "main") {
                "toolpkg_main:$normalizedTarget"
            } else {
                return buildToolPkgIpcFailure(
                    "ToolPkg.ipc targetContextKey is required for targetRuntime=$requestedRuntime"
                )
            }
        val inferredRuntime = inferToolPkgIpcRuntimeFromContextKey(resolvedTargetContextKey)
        if (
            requestedRuntime.isNotEmpty() &&
                inferredRuntime.isNotEmpty() &&
                requestedRuntime != inferredRuntime
        ) {
            return buildToolPkgIpcFailure(
                "ToolPkg.ipc targetRuntime does not match targetContextKey: $requestedRuntime != $inferredRuntime"
            )
        }
        val resolvedTargetRuntime =
            if (requestedRuntime.isNotEmpty()) {
                requestedRuntime
            } else if (inferredRuntime.isNotEmpty()) {
                inferredRuntime
            } else {
                return buildToolPkgIpcFailure(
                    "ToolPkg.ipc targetRuntime is required for targetContextKey=$resolvedTargetContextKey"
                )
            }
        val isMainTarget = resolvedTargetRuntime == "main"
        if (isMainTarget && !resolvedTargetContextKey.equals("toolpkg_main:$normalizedTarget", ignoreCase = true)) {
            return buildToolPkgIpcFailure(
                "ToolPkg.ipc main targetContextKey is invalid: $resolvedTargetContextKey"
            )
        }
        if (!isMainTarget && explicitTargetContextKey.isEmpty()) {
            return buildToolPkgIpcFailure(
                "ToolPkg.ipc targetContextKey is required for targetRuntime=$resolvedTargetRuntime"
            )
        }
        val engine =
            if (isMainTarget) {
                packageManager.getToolPkgExecutionEngine(
                    contextKey = resolvedTargetContextKey,
                    containerPackageName = normalizedTarget
                )
            } else {
                packageManager.findToolPkgExecutionEngine(resolvedTargetContextKey)
                    ?: return buildToolPkgIpcFailure(
                        "ToolPkg.ipc target runtime is not active: $resolvedTargetContextKey"
                    )
            }
        var scriptPath = ""
        var script = ""
        if (isMainTarget) {
            scriptPath = containerRuntime.mainEntry.trim()
            if (scriptPath.isEmpty()) {
                return buildToolPkgIpcFailure("ToolPkg main entry is unavailable: $normalizedTarget")
            }
            script =
                packageManager.getToolPkgMainScriptInternal(normalizedTarget)
                    ?: return buildToolPkgIpcFailure(
                        "ToolPkg main script is unavailable: $normalizedTarget"
                    )
        }
        val dispatchFunctionName = "__operit_toolpkg_runtime_dispatch__"
        val dispatchFunctionSource =
            """
                async function(params) {
                    var dispatch = globalThis.__operitInvokeToolPkgIpcLocal;
                    if (typeof dispatch !== 'function') {
                        throw new Error('ToolPkg.ipc runtime is unavailable in target context');
                    }
                    var payloadJson =
                        params && typeof params.__operit_toolpkg_ipc_payload_json === 'string'
                            ? params.__operit_toolpkg_ipc_payload_json
                            : 'null';
                    var payload;
                    try {
                        payload = JSON.parse(payloadJson);
                    } catch (error) {
                        throw new Error(
                            'ToolPkg.ipc payload JSON is invalid: ' +
                            String(error && error.message ? error.message : error)
                        );
                    }
                    var channel =
                        params && typeof params.__operit_toolpkg_ipc_channel === 'string'
                            ? params.__operit_toolpkg_ipc_channel.trim()
                            : '';
                    if (!channel) {
                        throw new Error('ToolPkg.ipc channel is required');
                    }
                    var callerContextKey =
                        params && typeof params.__operit_toolpkg_ipc_caller_context_key === 'string'
                            ? params.__operit_toolpkg_ipc_caller_context_key
                            : '';
                    var currentContextKey =
                        params && typeof params.__operit_execution_context_key === 'string'
                            ? params.__operit_execution_context_key
                            : '';
                    var packageTarget =
                        params && typeof params.__operit_ui_package_name === 'string'
                            ? params.__operit_ui_package_name
                            : '';
                    var currentRuntime =
                        params && typeof params.__operit_toolpkg_runtime_kind === 'string'
                            ? params.__operit_toolpkg_runtime_kind.trim()
                            : '';
                    return await dispatch(channel, payload, {
                        channel: channel,
                        callerContextKey: callerContextKey,
                        currentContextKey: currentContextKey,
                        currentRuntime: currentRuntime,
                        packageTarget: packageTarget
                    });
                }
            """.trimIndent()
        return try {
            val result =
                engine.executeScriptFunction(
                    script = script,
                    functionName = dispatchFunctionName,
                    params =
                        mapOf(
                            "__operit_ui_package_name" to normalizedTarget,
                            "toolPkgId" to normalizedTarget,
                            "containerPackageName" to normalizedTarget,
                            "__operit_execution_context_key" to resolvedTargetContextKey,
                            "__operit_toolpkg_runtime_kind" to resolvedTargetRuntime,
                            "__operit_script_screen" to scriptPath,
                            "__operit_inline_function_name" to dispatchFunctionName,
                            "__operit_inline_function_source" to dispatchFunctionSource,
                            "__operit_toolpkg_ipc_channel" to normalizedChannel,
                            "__operit_toolpkg_ipc_payload_json" to payloadJson.trim().ifEmpty { "null" },
                            "__operit_toolpkg_ipc_caller_context_key" to callerContextKey.trim()
                        ),
                    timeoutSec = 15L
                )
            val errorMessage = extractJsExecutionErrorMessage(result)
            if (errorMessage != null) {
                JSONObject()
                    .put("success", false)
                    .put("message", errorMessage)
                    .toString()
            } else {
                val decodedResult = decodeJsExecutionResultValue(result)
                JSONObject()
                    .put("success", true)
                    .put("value", decodedResult ?: JSONObject.NULL)
                    .toString()
            }
        } catch (error: Exception) {
            AppLogger.e(TAG, "ToolPkg.ipc runtime invoke failed: ${error.message}", error)
            JSONObject()
                .put("success", false)
                .put("message", error.message ?: "ToolPkg.ipc runtime invoke failed")
                .toString()
        }
    }

    fun cancelCurrentExecution(reason: String = "Execution canceled: requested by caller") {
        resetState(cancellationMessage = reason)
    }

    fun cancelExecutionsForChat(
        chatId: String,
        reason: String = "Execution canceled: requested by caller"
    ): Boolean {
        val normalizedChatId = chatId.trim()
        if (normalizedChatId.isEmpty()) {
            return false
        }

        val matchingSessions =
            activeExecutionSessions.values
                .filter { session -> session.packageChatId == normalizedChatId }
        if (matchingSessions.isEmpty()) {
            return false
        }

        matchingSessions.forEach { session ->
            removeExecutionSession(session.callId)
            if (!session.future.isDone) {
                session.future.complete(buildJsExecutionErrorPayload(reason))
            }
            cancelExecutionSessionInJs(
                callId = session.callId,
                reason = reason
            )
        }
        return true
    }

    /** 重置引擎状态，避免多次调用时的状态干扰 */
    private fun resetState(cancellationMessage: String = "Execution canceled: new execution started") {
        cancelAllExecutionSessions(cancellationMessage)
        clearPendingJsBridgeCallbacks("java bridge callback canceled: $cancellationMessage")

        bitmapRegistry.values.forEach { it.recycle() }
        bitmapRegistry.clear()

        binaryDataRegistry.clear()
        javaObjectRegistry.clear()
        if (quickJs != null) {
            launchQuickJsEvaluation(
                script =
                    """
                        (function() {
                            var root = typeof globalThis !== 'undefined'
                                ? globalThis
                                : (typeof window !== 'undefined' ? window : this);
                            if (typeof root.__operitClearAllTimers === 'function') {
                                root.__operitClearAllTimers();
                            }
                        })();
                    """.trimIndent(),
                fileName = "quickjs/runtime/reset-state.js",
                onError = { e ->
                    AppLogger.e(TAG, "Error in QuickJS cleanup: ${e.message}", e)
                }
            )
        }
    }

    /** JavaScript 接口，提供 Native 调用方法 */
    @Keep
    inner class JsToolCallInterface {

        private val jsBridgeCallbackInvoker: (String, String, String) -> String =
            { jsObjectId, methodName, callbackArgsJson ->
                invokeJavaBridgeJsObjectCallbackSync(
                    jsObjectId = jsObjectId,
                    methodName = methodName,
                    argsJson = callbackArgsJson
                )
            }

        init {
            JsJavaBridgeDelegates.registerJsInterfaceReleaseInvoker(
                callbackInvoker = jsBridgeCallbackInvoker,
                releaseInvoker = ::releaseJavaBridgeJsObjectSync
            )
        }

        fun detachJavaBridgeLifecycle() {
            JsJavaBridgeDelegates.unregisterJsInterfaceReleaseInvoker(jsBridgeCallbackInvoker)
        }

        @JavascriptInterface
        fun decompress(data: String, algorithm: String): String {
            return JsNativeInterfaceDelegates.decompress(
                data = data,
                algorithm = algorithm,
                binaryDataRegistry = binaryDataRegistry,
                binaryHandlePrefix = BINARY_HANDLE_PREFIX
            )
        }

        @JavascriptInterface
        fun getEnvForCall(callId: String, key: String): String? {
            val session = resolveExecutionSession(callId)
            return JsNativeInterfaceDelegates.getEnv(
                context = context,
                key = key,
                envOverrides = session?.envOverrides ?: emptyMap()
            )
        }

        /** Asynchronous host capability available only to the enabled Reading Companion ToolPkg. */
        @JavascriptInterface
        fun executeReadingCompanionAsync(
            callId: String,
            callbackId: String,
            action: String,
            parametersJson: String,
        ) {
            val normalizedCallId = callId.trim()
            val session =
                resolveExecutionSession(normalizedCallId)?.takeIf {
                    isReadingCompanionBridgeCaller(
                        packageName = it.packageName,
                        containerPackageName = boundToolPkgContainerName,
                    )
                }
            if (session == null) {
                sendToolPkgIpcResult(
                    callbackId = callbackId,
                    result = "Reading Companion bridge is only available to its ToolPkg",
                    isError = true,
                )
                return
            }

            val job = nativeBridgeScope.launch(start = CoroutineStart.LAZY) {
                try {
                    val result = ReadingCompanionBridge.execute(
                        context = context,
                        callerPackageName = session.packageName.orEmpty(),
                        action = action,
                        parametersJson = parametersJson,
                        runtime = session.toolRuntimeContext,
                    )
                    if (resolveExecutionSession(normalizedCallId) === session) {
                        sendToolPkgIpcResult(
                            callbackId = callbackId,
                            result = result,
                            isError = false,
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    if (resolveExecutionSession(normalizedCallId) === session) {
                        sendToolPkgIpcResult(
                            callbackId = callbackId,
                            result = interrupted.message ?: "Reading Companion operation interrupted",
                            isError = true,
                        )
                    }
                    throw CancellationException("Reading Companion operation interrupted").apply {
                        initCause(interrupted)
                    }
                } finally {
                    readingCompanionBridgeJobs.remove(normalizedCallId, coroutineContext[Job])
                }
            }
            readingCompanionBridgeJobs.put(normalizedCallId, job)?.cancel()
            job.start()
        }

        @JavascriptInterface
        fun executeCodexDrawAsync(callId: String, callbackId: String, parametersJson: String) {
            val normalizedCallId = callId.trim()
            val session = resolveExecutionSession(normalizedCallId)?.takeIf {
                it.packageName == "codex_draw" && boundToolPkgContainerName == null
            }
            if (session == null) {
                sendToolPkgIpcResult(callbackId, "Codex 绘图仅供 Codex 绘图包使用", true)
                return
            }
            val job = nativeBridgeScope.launch(start = CoroutineStart.LAZY) {
                try {
                    val result = CodexDrawBridge.execute(context, parametersJson)
                    if (resolveExecutionSession(normalizedCallId) === session) {
                        sendToolPkgIpcResult(callbackId, result, false)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (resolveExecutionSession(normalizedCallId) === session) {
                        sendToolPkgIpcResult(
                            callbackId,
                            error.message ?: "Codex 绘图失败",
                            true,
                        )
                    }
                } finally {
                    codexDrawBridgeJobs.remove(normalizedCallId, coroutineContext[Job])
                }
            }
            codexDrawBridgeJobs.put(normalizedCallId, job)?.cancel()
            job.start()
        }

        @JavascriptInterface
        fun setEnv(key: String, value: String?) {
            JsNativeInterfaceDelegates.setEnv(context = context, key = key, value = value)
        }

        @JavascriptInterface
        fun setEnvs(valuesJson: String) {
            JsNativeInterfaceDelegates.setEnvs(context = context, valuesJson = valuesJson)
        }

        @JavascriptInterface
        fun isPackageImported(packageName: String): Boolean {
            return JsNativeInterfaceDelegates.isPackageImported(
                    packageManager = packageManager,
                    packageName = packageName
            )
        }

        @JavascriptInterface
        fun importPackage(packageName: String): String {
            return JsNativeInterfaceDelegates.importPackage(
                    packageManager = packageManager,
                    packageName = packageName
            )
        }

        @JavascriptInterface
        fun removePackage(packageName: String): String {
            return JsNativeInterfaceDelegates.removePackage(
                    packageManager = packageManager,
                    packageName = packageName
            )
        }

        @JavascriptInterface
        fun usePackage(packageName: String): String {
            return JsNativeInterfaceDelegates.usePackage(
                    packageManager = packageManager,
                    packageName = packageName
            )
        }

        @JavascriptInterface
        fun listImportedPackagesJson(): String {
            return JsNativeInterfaceDelegates.listImportedPackagesJson(
                    packageManager = packageManager
            )
        }

        @JavascriptInterface
        fun resolveToolName(
                packageName: String,
                subpackageId: String,
                toolName: String,
                preferImported: String
        ): String {
            return JsNativeInterfaceDelegates.resolveToolName(
                    packageManager = packageManager,
                    packageName = packageName,
                    subpackageId = subpackageId,
                    toolName = toolName,
                    preferImported = preferImported
            )
        }

        @JavascriptInterface
        fun readToolPkgResource(
                packageNameOrSubpackageId: String,
                resourceKey: String,
                outputFileName: String,
                internal: String
        ): String {
            return JsNativeInterfaceDelegates.readToolPkgResource(
                    context = context,
                    packageManager = packageManager,
                    packageNameOrSubpackageId = packageNameOrSubpackageId,
                    resourceKey = resourceKey,
                    outputFileName = outputFileName,
                    internal = internal
            )
        }

        @JavascriptInterface
        fun readToolPkgTextResource(
                packageNameOrSubpackageId: String,
                resourcePath: String
        ): String {
            val temporaryResolverActive = hasTemporaryToolPkgTextResourceResolver()
            resolveTemporaryToolPkgTextResource(
                packageNameOrSubpackageId = packageNameOrSubpackageId,
                resourcePath = resourcePath
            )?.let { resolved -> return resolved }
            if (temporaryResolverActive) {
                // During toolpkg parsing, PackageManager access can wait on initialization
                // and deadlock JavaBridge thread.
                return ""
            }
            return JsNativeInterfaceDelegates.readToolPkgTextResource(
                    packageManager = packageManager,
                    packageNameOrSubpackageId = packageNameOrSubpackageId,
                    resourcePath = resourcePath
            )
        }

        @JavascriptInterface
        fun callToolPkgWasm(
            packageNameOrSubpackageId: String,
            moduleId: String,
            exportName: String,
            argsJson: String
        ): String {
            return JsNativeInterfaceDelegates.callToolPkgWasm(
                packageManager = packageManager,
                packageNameOrSubpackageId = packageNameOrSubpackageId,
                moduleId = moduleId,
                exportName = exportName,
                argsJson = argsJson
            )
        }

        @JavascriptInterface
        fun getPluginConfigDir(pluginId: String): String {
            return JsNativeInterfaceDelegates.getPluginConfigDir(
                packageManager = packageManager,
                pluginId = pluginId
            )
        }

        @JavascriptInterface
        fun invokeToolPkgIpcAsync(
            callbackId: String,
            packageTarget: String,
            callerContextKey: String,
            targetContextKey: String,
            targetRuntime: String,
            channel: String,
            payloadJson: String
        ) {
            val normalizedCallback = callbackId.trim()
            if (normalizedCallback.isEmpty()) {
                return
            }
            Thread {
                try {
                    val resultJson =
                        invokeToolPkgIpc(
                            packageTarget = packageTarget,
                            callerContextKey = callerContextKey,
                            targetContextKey = targetContextKey,
                            targetRuntime = targetRuntime,
                            channel = channel,
                            payloadJson = payloadJson
                        )
                    sendToolPkgIpcResult(normalizedCallback, resultJson, false)
                } catch (error: Throwable) {
                    AppLogger.e(TAG, "ToolPkg.ipc async invoke failed: ${error.message}", error)
                    sendToolPkgIpcResult(
                        normalizedCallback,
                        error.message?.trim().orEmpty().ifBlank { "ToolPkg.ipc async invoke failed" },
                        true
                    )
                }
            }.start()
        }

        @JavascriptInterface
        fun measureComposeText(payloadJson: String): String {
            return JsNativeInterfaceDelegates.measureComposeText(
                context = context,
                payloadJson = payloadJson
            )
        }

        @JavascriptInterface
        fun navigateToRoute(routeId: String, argsJson: String) {
            val normalizedRouteId = routeId.trim()
            if (normalizedRouteId.isBlank()) {
                return
            }
            AppRouterGateway.navigate(
                routeId = normalizedRouteId,
                args = parseJsonObjectToMap(argsJson),
                source = RouteEntrySource.SCRIPT
            )
        }

        @JavascriptInterface
        fun getReadingCompanionCommentaryCharacter(bookId: String): String {
            return try {
                check(boundToolPkgContainerName == ReadingCompanionService.TOOLPKG_ID) {
                    "Reading Companion character selection is only available to its ToolPkg UI"
                }
                val normalizedBookId = bookId.trim()
                require(normalizedBookId.isNotBlank()) { "bookId is required" }
                val persona = ReadingCompanionStore(context.applicationContext).use { store ->
                    store.getAutoCommentPersona(normalizedBookId)
                }
                JSONObject()
                    .put("success", true)
                    .put(
                        "data",
                        JSONObject()
                            .put("bookId", normalizedBookId)
                            .put("configured", persona != null)
                            .put("roleCardId", persona?.roleCardId)
                            .put("roleCardName", persona?.roleCardName)
                            .put("updatedAt", persona?.updatedAt)
                            .apply {
                                if (persona != null) {
                                    runCatching {
                                        kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                                            com.ai.assistance.operit.features.reading.ReadingCompanionModelGateway(context)
                                                .previewAutoCommentConfiguration(persona.roleCardId)
                                        }
                                    }.onSuccess { preview ->
                                        put("configuration",JSONObject()
                                            .put("modelSource",preview.modelSource)
                                            .put("modelConfigName",preview.modelConfigName)
                                            .put("model",preview.model))
                                    }.onFailure { put("configurationError",it.message.orEmpty()) }
                                }
                            },
                    )
                    .toString()
            } catch (error: Throwable) {
                JSONObject()
                    .put("success", false)
                    .put(
                        "message",
                        error.message?.trim().orEmpty().ifBlank {
                            "Failed to load commentary character"
                        },
                    )
                    .toString()
            }
        }

        /**
         * 受限宿主动作：按 reading run 打开其审计子代理对话（仅 Reading Companion ToolPkg
         * UI 可用）。链路：runId -> child_chat_id（Store）-> ChatDao 实体；通过后才允许
         * 打开——隐藏路径要求 hiddenReason 为 READING_COMPANION_AUDIT_ 前缀；对话内路径
         * 要求 child 的 subagent run 与本 run 弱关联匹配。通过后主线程 switchChat(childId)
         * + 路由 native.ai_chat。任何失败返回 JSON error 且不 switch；不信任 JS 传入 chatId。
         */
        @JavascriptInterface
        fun openReadingAuditChat(runIdJson: String): String {
            return try {
                check(boundToolPkgContainerName == ReadingCompanionService.TOOLPKG_ID) {
                    "Reading Companion audit chat is only available to its ToolPkg UI"
                }
                val runId = runIdJson.trim().toLongOrNull()
                require(runId != null && runId > 0) { "runId is required" }
                val appContext = context.applicationContext
                val chatHistoryDelegate =
                    ChatRuntimeHolder.getInstance(appContext)
                        .getCore(ChatRuntimeSlot.MAIN)
                        .getChatHistoryDelegate()
                val returnChatIdSnapshot = chatHistoryDelegate.currentChatId.value
                val (resolvedChildChatId, safeReturnChatId) =
                    runBlocking(Dispatchers.IO) {
                        val run =
                            ReadingCompanionStore(appContext).use { store ->
                                store.getAutoCommentRun(runId)
                            } ?: error("段评任务记录不存在或已过期")
                        val childChatId =
                            run.childChatId?.takeIf(String::isNotBlank)
                                ?: error("该任务没有关联的子代理对话")
                        val chat =
                            AppDatabase.getDatabase(appContext)
                                .chatDao()
                                .getChatById(childChatId)
                                ?: error("关联的子代理对话不存在或已被删除")
                        // run 级绑定校验（纯函数，与 JVM 测试同一决策路径）：隐藏路径要求
                        // hiddenReason 精确等于本 run、parent 绑定一致、subagent run 弱关联
                        // 三重匹配、父根 hiddenReason 与 run.bookId 一致（防他书错链）；
                        // 对话内路径要求 parent 绑定与 subagent run 弱关联一致。前缀相同但
                        // run 不同 / 伪装 runId / 他书 child 一律拒绝。
                        val subagentRun =
                            SubagentRunRepository.getInstance(appContext)
                                .getByChildChatId(childChatId)
                        val parentChat =
                            chat.parentChatId?.let { parentId ->
                                AppDatabase.getDatabase(appContext)
                                    .chatDao()
                                    .getChatById(parentId)
                            }
                        val authorized =
                            ReadingCompanionAudit.isAuthorizedAuditChat(
                                runId = runId,
                                runBookId = run.bookId,
                                runParentChatId = run.parentChatId,
                                runSubagentRunId = run.subagentRunId,
                                chatIsHidden = chat.isHidden,
                                chatHiddenReason = chat.hiddenReason,
                                chatParentChatId = chat.parentChatId,
                                chatParentHiddenReason = parentChat?.hiddenReason,
                                subagentOwnerType = subagentRun?.externalOwnerType,
                                subagentOwnerId = subagentRun?.externalOwnerId,
                                subagentRunId = subagentRun?.id,
                            )
                        require(authorized) {
                            "该任务与所选聊天不匹配，无法打开审计对话"
                        }
                        val safeReturnChatId =
                            returnChatIdSnapshot
                                ?.takeIf { it != childChatId }
                                ?.let { returnChatId ->
                                    AppDatabase.getDatabase(appContext)
                                        .chatDao()
                                        .getChatById(returnChatId)
                                }
                                ?.takeIf { !it.isHidden }
                                ?.id
                        childChatId to safeReturnChatId
                    }
                Handler(Looper.getMainLooper()).post {
                    try {
                        ReadingCompanionAudit.rememberReturnChat(
                            auditChildChatId = resolvedChildChatId,
                            returnChatId = safeReturnChatId,
                        )
                        // 审计对话只在当前主聊天页临时显示，不写入全局 currentChatId。
                        // 即使进程在审计页被杀，下次进入聊天也仍回到原来的可见会话。
                        chatHistoryDelegate.switchChat(
                            resolvedChildChatId,
                            syncToGlobal = false,
                            scrollToBottom = false,
                        )
                        AppRouterGateway.navigate(
                            routeId = "native.ai_chat",
                            args = emptyMap(),
                            source = RouteEntrySource.SCRIPT,
                        )
                    } catch (navError: Throwable) {
                        AppLogger.w(
                            TAG,
                            "openReadingAuditChat navigation failed: ${navError.message}",
                            navError,
                        )
                    }
                }
                JSONObject()
                    .put("success", true)
                    .put(
                        "data",
                        JSONObject()
                            .put("chatId", resolvedChildChatId)
                            .put("route", "native.ai_chat"),
                    )
                    .toString()
            } catch (error: Throwable) {
                JSONObject()
                    .put("success", false)
                    .put(
                        "message",
                        error.message?.trim().orEmpty().ifBlank {
                            "Failed to open audit chat"
                        },
                    )
                    .toString()
            }
        }

        @JavascriptInterface
        fun setReadingCompanionCommentaryCharacter(optionsJson: String): String {
            return try {
                check(boundToolPkgContainerName == ReadingCompanionService.TOOLPKG_ID) {
                    "Reading Companion character selection is only available to its ToolPkg UI"
                }
                val options = optionsJson.takeIf(String::isNotBlank)?.let(::JSONObject)
                    ?: JSONObject()
                val bookId = options.optString("bookId").trim()
                val roleCardId = options.optString("roleCardId").trim()
                require(bookId.isNotBlank()) { "bookId is required" }
                require(roleCardId.isNotBlank()) { "roleCardId is required" }
                val (persona, roleChanged) =
                    runBlocking(Dispatchers.IO) {
                        val cardManager = CharacterCardManager.getInstance(context.applicationContext)
                        cardManager.initializeIfNeeded()
                        val roleCard = cardManager.getAllCharacterCards()
                            .firstOrNull { card -> card.id == roleCardId }
                            ?: error("所选角色卡已不存在，请重新选择")
                        val state =
                            ReadingCompanionService.getInstance(context.applicationContext)
                                .currentBook()
                        require(state.book.id == bookId) { "当前伴读书籍已变化，请刷新后重试" }
                        val (stored, changed) =
                            ReadingCompanionStore(context.applicationContext).use { store ->
                                store.updateBook(state)
                                val previous = store.getAutoCommentPersona(bookId)
                                val updated = store.setAutoCommentPersona(
                                    bookId = bookId,
                                    roleCardId = roleCard.id,
                                    roleCardName = roleCard.name,
                                )
                                updated to (
                                    previous?.roleCardId != roleCard.id ||
                                        previous?.roleCardName != roleCard.name
                                )
                            }
                        stored to changed
                    }
                if (
                    roleChanged &&
                    ReadingCompanionAutoCommentary.isEnabled(context.applicationContext)
                ) {
                    ReadingCompanionAutoCommentary.rescheduleAfterPersonaChange(
                        context.applicationContext
                    )
                }
                JSONObject()
                    .put("success", true)
                    .put(
                        "data",
                        JSONObject()
                            .put("bookId", persona.bookId)
                            .put("configured", true)
                            .put("roleCardId", persona.roleCardId)
                            .put("roleCardName", persona.roleCardName)
                            .put("updatedAt", persona.updatedAt),
                    )
                    .toString()
            } catch (error: Throwable) {
                JSONObject()
                    .put("success", false)
                    .put(
                        "message",
                        error.message?.trim().orEmpty().ifBlank {
                            "Failed to select commentary character"
                        },
                    )
                    .toString()
            }
        }

        @JavascriptInterface
        fun listRoutes(): String {
            return buildRoutesJson(includeOnlyNative = false)
        }

        @JavascriptInterface
        fun listHostRoutes(): String {
            return buildRoutesJson(includeOnlyNative = true)
        }

        @JavascriptInterface
        fun composeWebViewControllerCommand(payloadJson: String): String {
            return ComposeDslWebViewHostRegistry.handleControllerCommand(payloadJson)
        }

        @JavascriptInterface
        fun composeWebViewControllerCommandSuspend(payloadJson: String, callbackId: String) {
            val normalizedCallback = callbackId.trim()
            if (normalizedCallback.isEmpty()) {
                return
            }
            Thread {
                try {
                    val result = ComposeDslWebViewHostRegistry.handleControllerCommand(payloadJson)
                    sendToolResult(normalizedCallback, result, false)
                } catch (error: Throwable) {
                    AppLogger.e(
                        TAG,
                        "compose webview controller suspend command failed: ${error.message}",
                        error
                    )
                    sendToolResult(
                        normalizedCallback,
                        buildJsExecutionErrorPayload(
                            error.message?.trim().orEmpty().ifBlank {
                                "compose webview controller command failed"
                            }
                        ),
                        true
                    )
                }
            }.start()
        }

        @JavascriptInterface
        fun composeOpenFilePickerSuspend(payloadJson: String, callbackId: String) {
            val normalizedCallback = callbackId.trim()
            if (normalizedCallback.isEmpty()) {
                return
            }
            ComposeDslFilePickerHostRegistry.openPicker(
                payloadJson = payloadJson,
                onSuccess = { result ->
                    sendToolResult(normalizedCallback, result, false)
                },
                onError = { errorMessage ->
                    sendToolResult(
                        normalizedCallback,
                        buildJsExecutionErrorPayload(
                            errorMessage.trim().ifBlank { "compose file picker failed" }
                        ),
                        true
                    )
                }
            )
        }

        private fun buildRoutesJson(includeOnlyNative: Boolean): String {
            val routes = AppRouteDiscoveryGateway.listRoutes()
            val result = JSONArray()
            routes
                .asSequence()
                .filter { route ->
                    !includeOnlyNative || route.runtime == RouteRuntime.NATIVE
                }
                .sortedBy { it.routeId }
                .forEach { route ->
                    val item =
                        JSONObject()
                            .put("routeId", route.routeId)
                            .put("runtime", route.runtime.name.lowercase())
                            .put("title", route.title ?: route.routeId)
                            .put("ownerPackageName", route.ownerPackageName ?: JSONObject.NULL)
                            .put("toolPkgUiModuleId", route.toolPkgUiModuleId ?: JSONObject.NULL)
                    result.put(item)
                }
            return result.toString()
        }

        @JavascriptInterface
        fun registerToolPkgToolboxUiModule(specJson: String) {
            toolPkgRegistrationSession.appendToolboxUiModule(specJson)
        }

        @JavascriptInterface
        fun registerToolPkgUiRoute(specJson: String) {
            toolPkgRegistrationSession.appendUiRoute(specJson)
        }

        @JavascriptInterface
        fun registerToolPkgNavigationEntry(specJson: String) {
            toolPkgRegistrationSession.appendNavigationEntry(specJson)
        }

        @JavascriptInterface
        fun registerToolPkgDesktopWidget(specJson: String) {
            toolPkgRegistrationSession.appendDesktopWidget(specJson)
        }

        @JavascriptInterface
        fun registerToolPkgAppLifecycleHook(specJson: String) {
            toolPkgRegistrationSession.appendAppLifecycleHook(specJson)
        }

        @JavascriptInterface
        fun registerToolPkgMessageProcessingPlugin(specJson: String) {
            toolPkgRegistrationSession.appendMessageProcessingPlugin(specJson)
        }

        @JavascriptInterface
        fun registerToolPkgXmlRenderPlugin(specJson: String) {
            toolPkgRegistrationSession.appendXmlRenderPlugin(specJson)
        }

        @JavascriptInterface
        fun registerToolPkgInputMenuTogglePlugin(specJson: String) {
            toolPkgRegistrationSession.appendInputMenuTogglePlugin(specJson)
        }

        @JavascriptInterface
        fun registerToolPkgChatInputHook(specJson: String) {
            toolPkgRegistrationSession.appendChatInputHook(specJson)
        }

        @JavascriptInterface
        fun registerToolPkgChatViewHook(specJson: String) {
            toolPkgRegistrationSession.appendChatViewHook(specJson)
        }

        @JavascriptInterface
        fun registerToolPkgChatMessageMenuItem(specJson: String) {
            toolPkgRegistrationSession.appendChatMessageMenuItem(specJson)
        }

        @JavascriptInterface
        fun registerToolPkgChatRuntimeHook(specJson: String) {
            toolPkgRegistrationSession.appendChatRuntimeHook(specJson)
        }

        @JavascriptInterface
        fun registerToolPkgToolLifecycleHook(specJson: String) {
            toolPkgRegistrationSession.appendToolLifecycleHook(specJson)
        }

        @JavascriptInterface
        fun registerToolPkgPromptInputHook(specJson: String) {
            toolPkgRegistrationSession.appendPromptInputHook(specJson)
        }

        @JavascriptInterface
        fun registerToolPkgPromptHistoryHook(specJson: String) {
            toolPkgRegistrationSession.appendPromptHistoryHook(specJson)
        }

        @JavascriptInterface
        fun registerToolPkgPromptEstimateHistoryHook(specJson: String) {
            toolPkgRegistrationSession.appendPromptEstimateHistoryHook(specJson)
        }

        @JavascriptInterface
        fun registerToolPkgSystemPromptComposeHook(specJson: String) {
            toolPkgRegistrationSession.appendSystemPromptComposeHook(specJson)
        }

        @JavascriptInterface
        fun registerToolPkgToolPromptComposeHook(specJson: String) {
            toolPkgRegistrationSession.appendToolPromptComposeHook(specJson)
        }

        @JavascriptInterface
        fun registerToolPkgPromptFinalizeHook(specJson: String) {
            toolPkgRegistrationSession.appendPromptFinalizeHook(specJson)
        }

        @JavascriptInterface
        fun registerToolPkgPromptEstimateFinalizeHook(specJson: String) {
            toolPkgRegistrationSession.appendPromptEstimateFinalizeHook(specJson)
        }

        @JavascriptInterface
        fun registerToolPkgSummaryGenerateHook(specJson: String) {
            toolPkgRegistrationSession.appendSummaryGenerateHook(specJson)
        }

        @JavascriptInterface
        fun registerToolPkgAiProvider(specJson: String) {
            toolPkgRegistrationSession.appendAiProvider(specJson)
        }

        private fun bridgeClassLoader(): ClassLoader = getJavaBridgeClassLoader()

        private fun parseJsonObjectToMap(raw: String): Map<String, Any?> {
            return JsJavaBridgeDelegates.parsePlainJsonObjectToMap(raw)
        }

        private fun exposeJavaObject(target: Any, failureLabel: String): String {
            return try {
                val handle = UUID.randomUUID().toString()
                javaObjectRegistry[handle] = target
                JSONObject()
                    .put("success", true)
                    .put(
                        "data",
                        JSONObject()
                            .put("__javaHandle", handle)
                            .put("__javaClass", target.javaClass.name)
                    )
                    .toString()
            } catch (e: Exception) {
                AppLogger.e(TAG, "$failureLabel: ${e.message}", e)
                JSONObject()
                    .put("success", false)
                    .put("message", e.message ?: failureLabel.lowercase())
                    .toString()
            }
        }

        private fun launchSuspendJavaBridgeCall(
            callbackId: String,
            block: (normalizedCallbackId: String, resultCallback: (String) -> Unit) -> Unit
        ) {
            val normalizedCallback = callbackId.trim()
            if (normalizedCallback.isEmpty()) {
                return
            }
            Thread {
                block(
                    normalizedCallback,
                    createSuspendJavaBridgeResultCallback(normalizedCallback)
                )
            }.start()
        }

        private fun createSuspendJavaBridgeResultCallback(callbackId: String): (String) -> Unit {
            return { resultJson ->
                ensureQuickJs()
                try {
                    launchQuickJsEvaluation(
                        script = buildSuspendJavaBridgeCallbackScript(
                            callbackId = callbackId,
                            resultJson = resultJson
                        ),
                        fileName = "quickjs/runtime/java-bridge-suspend-callback.js",
                        onError = { error ->
                            AppLogger.e(
                                TAG,
                                "Error delivering suspend bridge callback to JavaScript: ${error.message}",
                                error
                            )
                        }
                    )
                } catch (error: Exception) {
                    AppLogger.e(
                        TAG,
                        "Error scheduling suspend bridge callback delivery: ${error.message}",
                        error
                    )
                }
            }
        }

        private fun buildSuspendJavaBridgeCallbackScript(
            callbackId: String,
            resultJson: String
        ): String {
            val safeCallbackId = JSONObject.quote(callbackId.trim())
            val safeResultJson = JSONObject.quote(resultJson)
            return """
                (function() {
                    var root = typeof globalThis !== 'undefined'
                        ? globalThis
                        : (typeof window !== 'undefined' ? window : this);
                    var invoke =
                        root && typeof root.__operitJavaBridgeInvokeJsObject === 'function'
                            ? root.__operitJavaBridgeInvokeJsObject
                            : undefined;
                    if (typeof invoke !== 'function') {
                        throw new Error('Java bridge callback entry is unavailable');
                    }

                    var parsed;
                    try {
                        parsed = JSON.parse($safeResultJson);
                    } catch (error) {
                        return invoke($safeCallbackId, '', [
                            'failed to parse bridge response: ' + String(error && error.message ? error.message : error),
                            null
                        ]);
                    }

                    if (parsed && parsed.success === true) {
                        return invoke($safeCallbackId, '', [null, parsed.data]);
                    }

                    var message =
                        parsed && typeof parsed.message === 'string' && parsed.message.length > 0
                            ? parsed.message
                            : '';
                    return invoke($safeCallbackId, '', [message, null]);
                })();
            """.trimIndent()
        }

        @JavascriptInterface
        fun javaClassExists(className: String): Boolean {
            return JsJavaBridgeDelegates.classExists(
                className = className,
                bridgeClassLoader = bridgeClassLoader()
            )
        }

        @JavascriptInterface
        fun javaLoadDex(path: String, optionsJson: String): String {
            return JSONObject()
                .put("success", false)
                .put("message", "Java bridge external code loading is disabled")
                .toString()
        }

        @JavascriptInterface
        fun javaLoadJar(path: String, optionsJson: String): String {
            return JSONObject()
                .put("success", false)
                .put("message", "Java bridge external code loading is disabled")
                .toString()
        }

        @JavascriptInterface
        fun javaListLoadedCodePaths(): String {
            return externalJavaCodeLoader.listLoadedArtifacts()
        }

        @JavascriptInterface
        fun javaGetApplicationContext(): String {
            return JSONObject()
                .put("success", false)
                .put("message", "Java bridge access to Android Context is disabled")
                .toString()
        }

        @JavascriptInterface
        fun javaGetCurrentActivity(): String {
            return JSONObject()
                .put("success", false)
                .put("message", "Java bridge access to Activity is disabled")
                .toString()
        }

        /** Lists the direct package example files bundled in this exact APK. */
        @JavascriptInterface
        fun listSandboxPackageDevPackageAssets(): String {
            return try {
                val files =
                    context.assets.list("packages")
                        ?.asSequence()
                        ?.filter { name ->
                            name.isNotBlank() && '/' !in name && '\\' !in name
                        }
                        ?.sorted()
                        ?.toList()
                        .orEmpty()
                require(files.isNotEmpty()) { "Bundled package assets are missing" }
                JSONObject()
                    .put("success", true)
                    .put("files", JSONArray(files))
                    .toString()
            } catch (e: Exception) {
                JSONObject()
                    .put("success", false)
                    .put("message", e.message ?: e.javaClass.name)
                    .toString()
            }
        }

        /**
         * Reads one direct package asset selected from the fixed APK asset list. The caller must
         * use the permissioned Tools.Files API for any external-storage write.
         */
        @JavascriptInterface
        fun readSandboxPackageDevPackageAssetBase64(fileName: String): String {
            return try {
                val normalized = fileName.trim()
                require(normalized.isNotEmpty() && '/' !in normalized && '\\' !in normalized) {
                    "Bundled package asset name is invalid"
                }
                val allowedFiles = context.assets.list("packages")?.toSet().orEmpty()
                require(normalized in allowedFiles) {
                    "Bundled package asset is not available: $normalized"
                }
                val encoded =
                    context.assets.open("packages/$normalized").use { input ->
                        Base64.encodeToString(input.readBytes(), Base64.NO_WRAP)
                    }
                JSONObject()
                    .put("success", true)
                    .put("file", normalized)
                    .put("base64", encoded)
                    .toString()
            } catch (e: Exception) {
                JSONObject()
                    .put("success", false)
                    .put("message", e.message ?: e.javaClass.name)
                    .toString()
            }
        }

        @JavascriptInterface
        fun javaNewInstance(className: String, argsJson: String): String {
            return JsJavaBridgeDelegates.newInstance(
                    className = className,
                    argsJson = argsJson,
                    objectRegistry = javaObjectRegistry,
                    jsCallbackInvoker = jsBridgeCallbackInvoker,
                    bridgeClassLoader = bridgeClassLoader()
            )
        }

        @JavascriptInterface
        fun javaCallStatic(className: String, methodName: String, argsJson: String): String {
            return JsJavaBridgeDelegates.callStatic(
                    className = className,
                    methodName = methodName,
                    argsJson = argsJson,
                    objectRegistry = javaObjectRegistry,
                    jsCallbackInvoker = jsBridgeCallbackInvoker,
                    bridgeClassLoader = bridgeClassLoader()
            )
        }

        @JavascriptInterface
        fun javaCallInstance(instanceHandle: String, methodName: String, argsJson: String): String {
            return JsJavaBridgeDelegates.callInstance(
                    instanceHandle = instanceHandle,
                    methodName = methodName,
                    argsJson = argsJson,
                    objectRegistry = javaObjectRegistry,
                    jsCallbackInvoker = jsBridgeCallbackInvoker,
                    bridgeClassLoader = bridgeClassLoader()
            )
        }

        @JavascriptInterface
        fun javaCallStaticSuspend(
                className: String,
                methodName: String,
                argsJson: String,
                callbackId: String
        ) {
            launchSuspendJavaBridgeCall(callbackId) { _normalizedCallback, resultCallback ->
                JsJavaBridgeDelegates.callStaticSuspend(
                    className = className,
                    methodName = methodName,
                    argsJson = argsJson,
                    objectRegistry = javaObjectRegistry,
                    callback = resultCallback,
                    jsCallbackInvoker = jsBridgeCallbackInvoker,
                    bridgeClassLoader = bridgeClassLoader()
                )
            }
        }

        @JavascriptInterface
        fun javaCallInstanceSuspend(
                instanceHandle: String,
                methodName: String,
                argsJson: String,
                callbackId: String
        ) {
            launchSuspendJavaBridgeCall(callbackId) { _normalizedCallback, resultCallback ->
                JsJavaBridgeDelegates.callInstanceSuspend(
                    instanceHandle = instanceHandle,
                    methodName = methodName,
                    argsJson = argsJson,
                    objectRegistry = javaObjectRegistry,
                    callback = resultCallback,
                    jsCallbackInvoker = jsBridgeCallbackInvoker,
                    bridgeClassLoader = bridgeClassLoader()
                )
            }
        }

        @JavascriptInterface
        fun javaGetStaticField(className: String, fieldName: String): String {
            return JsJavaBridgeDelegates.getStaticField(
                    className = className,
                    fieldName = fieldName,
                    objectRegistry = javaObjectRegistry,
                    bridgeClassLoader = bridgeClassLoader()
            )
        }

        @JavascriptInterface
        fun javaSetStaticField(className: String, fieldName: String, valueJson: String): String {
            return JsJavaBridgeDelegates.setStaticField(
                    className = className,
                    fieldName = fieldName,
                    valueJson = valueJson,
                    objectRegistry = javaObjectRegistry,
                    jsCallbackInvoker = jsBridgeCallbackInvoker,
                    bridgeClassLoader = bridgeClassLoader()
            )
        }

        @JavascriptInterface
        fun javaGetInstanceField(instanceHandle: String, fieldName: String): String {
            return JsJavaBridgeDelegates.getInstanceField(
                    instanceHandle = instanceHandle,
                    fieldName = fieldName,
                    objectRegistry = javaObjectRegistry
            )
        }

        @JavascriptInterface
        fun javaHasInstanceMethod(instanceHandle: String, methodName: String): String {
            return JsJavaBridgeDelegates.hasInstanceMethod(
                    instanceHandle = instanceHandle,
                    methodName = methodName,
                    objectRegistry = javaObjectRegistry
            )
        }

        @JavascriptInterface
        fun javaSetInstanceField(instanceHandle: String, fieldName: String, valueJson: String): String {
            return JsJavaBridgeDelegates.setInstanceField(
                    instanceHandle = instanceHandle,
                    fieldName = fieldName,
                    valueJson = valueJson,
                    objectRegistry = javaObjectRegistry,
                    jsCallbackInvoker = jsBridgeCallbackInvoker,
                    bridgeClassLoader = bridgeClassLoader()
            )
        }

        @JavascriptInterface
        fun javaPollPendingJsCallback(): String {
            return pollPendingJsBridgeCallbackPayload().orEmpty()
        }

        @JavascriptInterface
        fun javaResolvePendingJsCallback(requestId: String, responseJson: String) {
            resolvePendingJsBridgeCallback(
                requestId = requestId,
                responseJson = responseJson
            )
        }

        @JavascriptInterface
        fun javaSleepMillis(durationText: String?) {
            val durationMs =
                durationText
                    ?.trim()
                    ?.toLongOrNull()
                    ?.coerceAtLeast(0L)
                    ?.coerceAtMost(50L)
                    ?: 0L
            if (durationMs <= 0L) {
                return
            }
            Thread.sleep(durationMs)
        }

        @JavascriptInterface
        fun __javaReleaseInstanceInternal(instanceHandle: String): String {
            return JsJavaBridgeDelegates.releaseInstance(
                    instanceHandle = instanceHandle,
                    objectRegistry = javaObjectRegistry
            )
        }

        @JavascriptInterface
        fun registerImageFromBase64(base64: String, mimeType: String): String {
            return try {
                val finalMime = if (mimeType.isNotBlank()) mimeType else "image/png"
                val id = ImagePoolManager.addImageFromBase64(base64, finalMime)
                if (id != "error") {
                    "<link type=\"image\" id=\"$id\"></link>"
                } else {
                    "[image registration failed]"
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "registerImageFromBase64 failed: ${e.message}", e)
                "[image registration failed: ${e.message}]"
            }
        }

        @JavascriptInterface
        fun registerImageFromPath(path: String): String {
            return try {
                val source = com.ai.assistance.operit.util.ImageSourcePathPolicy.resolve(
                    path,
                    listOfNotNull(
                        android.os.Environment.getExternalStorageDirectory(),
                        com.ai.assistance.operit.util.OperitPaths.cleanOnExitInternalDir(context)
                    ) + context.getExternalFilesDirs(null).filterNotNull() +
                        context.externalCacheDirs.filterNotNull()
                )
                val id = ImagePoolManager.addImage(source.path)
                if (id != "error") {
                    // 带上源路径：图片池回收后历史消息还能从文件恢复这张图。
                    MediaLinkParser.buildImageLink(id, source.path)
                } else {
                    "[image registration failed]"
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "registerImageFromPath failed: ${e.message}", e)
                "[image registration failed: ${e.message}]"
            }
        }

        @JavascriptInterface
        fun image_processing(callbackId: String, operation: String, argsJson: String) {
            JsNativeInterfaceDelegates.imageProcessing(
                    callbackId = callbackId,
                    operation = operation,
                    argsJson = argsJson,
                    binaryDataRegistry = binaryDataRegistry,
                    bitmapRegistry = bitmapRegistry,
                    binaryHandlePrefix = BINARY_HANDLE_PREFIX
            ) { callback, result, isError ->
                sendToolResult(callback, result, isError)
            }
        }

        @JavascriptInterface
        fun crypto(algorithm: String, operation: String, argsJson: String): String {
            return JsNativeInterfaceDelegates.crypto(
                    algorithm = algorithm,
                    operation = operation,
                    argsJson = argsJson
            )
        }

        @JavascriptInterface
        fun sendCallIntermediateResult(callId: String, result: String) {
            try {
                val session = resolveExecutionSession(callId) ?: return
                session.executionListener?.onIntermediateResult(callId, result)
                if (session.dispatchIntermediateOnMain) {
                    ContextCompat.getMainExecutor(context).execute {
                        session.intermediateResultCallback?.invoke(result)
                    }
                    return
                }
                session.intermediateResultCallback?.invoke(result)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error processing call intermediate result: callId=$callId, reason=${e.message}", e)
            }
        }

        /** 同步工具调用 */
        @JavascriptInterface
        fun callTool(toolType: String, toolName: String, paramsJson: String): String {
            return JsNativeInterfaceDelegates.callToolSync(
                toolHandler = toolHandler,
                toolType = toolType,
                toolName = toolName,
                paramsJson = paramsJson,
                binaryDataRegistry = binaryDataRegistry,
                binaryHandlePrefix = BINARY_HANDLE_PREFIX,
                binaryDataThreshold = BINARY_DATA_THRESHOLD
            )
        }

        /** 异步工具调用（新版本，使用Promise） */
        @JavascriptInterface
        fun callToolAsync(
                callbackId: String,
                toolType: String,
                toolName: String,
                paramsJson: String
        ) {
            JsNativeInterfaceDelegates.callToolAsync(
                toolHandler = toolHandler,
                callbackId = callbackId,
                toolType = toolType,
                toolName = toolName,
                paramsJson = paramsJson,
                binaryDataRegistry = binaryDataRegistry,
                binaryHandlePrefix = BINARY_HANDLE_PREFIX,
                binaryDataThreshold = BINARY_DATA_THRESHOLD,
                sendToolResult = { callback, result, isError ->
                    sendToolResult(callback, result, isError)
                }
            )
        }

        @JavascriptInterface
        fun callToolAsyncStreaming(
                callbackId: String,
                intermediateCallbackId: String,
                toolType: String,
                toolName: String,
                paramsJson: String
        ) {
            JsNativeInterfaceDelegates.callToolAsyncStreaming(
                toolHandler = toolHandler,
                callbackId = callbackId,
                intermediateCallbackId = intermediateCallbackId,
                toolType = toolType,
                toolName = toolName,
                paramsJson = paramsJson,
                binaryDataRegistry = binaryDataRegistry,
                binaryHandlePrefix = BINARY_HANDLE_PREFIX,
                binaryDataThreshold = BINARY_DATA_THRESHOLD,
                sendToolResult = { callback, result, isError ->
                    sendToolResult(callback, result, isError)
                },
                sendIntermediateResult = { callback, result, isError ->
                    sendToolResult(callback, result, isError)
                }
            )
        }

        /** 向JavaScript发送工具调用结果 */
        private fun sendToolResult(callbackId: String, result: String, isError: Boolean) {
            if (!canScheduleQuickJsWork()) {
                return
            }
            try {
                ensureQuickJs()
                val jsCode =
                    JsNativeInterfaceDelegates.buildToolResultCallbackScript(
                        callbackId = callbackId,
                        result = result,
                        isError = isError
                    )
                launchQuickJsEvaluation(
                    script = jsCode,
                    onError = { e ->
                        AppLogger.e(TAG, "Error sending tool result to JavaScript: ${e.message}", e)
                    }
                )
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error sending tool result to JavaScript: ${e.message}", e)
            }
        }

        /** ToolPkg.ipc uses a JSON string envelope, so its callback must receive a string. */
        private fun sendToolPkgIpcResult(callbackId: String, result: String, isError: Boolean) {
            if (!canScheduleQuickJsWork()) {
                return
            }
            try {
                ensureQuickJs()
                val jsCode =
                    JsNativeInterfaceDelegates.buildStringResultCallbackScript(
                        callbackId = callbackId,
                        result = result,
                        isError = isError
                    )
                launchQuickJsEvaluation(
                    script = jsCode,
                    onError = { e ->
                        AppLogger.e(TAG, "Error sending ToolPkg.ipc result to JavaScript: ${e.message}", e)
                    }
                )
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error sending ToolPkg.ipc result to JavaScript: ${e.message}", e)
            }
        }

        @JavascriptInterface
        fun setCallResult(callId: String, result: String) {
            try {
                val session = resolveExecutionSession(callId)
                if (session == null) {
                    AppLogger.e(TAG, "Result callback is null when trying to complete: callId=$callId")
                    return
                }
                if (session.future.isDone) {
                    AppLogger.w(TAG, "Result callback is already completed when trying to set result: callId=$callId")
                    return
                }
                session.executionListener?.onCompleted(callId, result)
                completeCallFuture(
                    session = session,
                    value = result,
                    failureMessage = "Error completing result callback"
                )
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error setting result: callId=$callId, reason=${e.message}", e)
                resolveExecutionSession(callId)?.future?.completeExceptionally(e)
            }
        }

        @JavascriptInterface
        fun setCallError(callId: String, error: String) {
            try {
                val session = resolveExecutionSession(callId)
                AppLogger.d(
                    TAG,
                    "Bridge error from JavaScript: callId=$callId, length=${error.length}, callback=${session != null}, isDone=${session?.future?.isDone}"
                )
                if (session == null) {
                    AppLogger.e(TAG, "Result callback is null when trying to complete with error: callId=$callId")
                    return
                }
                if (session.future.isDone) {
                    AppLogger.w(TAG, "Result callback is already completed when trying to set error: callId=$callId")
                    return
                }

                val logMessage = extractErrorLogMessage(error)
                val enrichedLogMessage = withToolPkgCodeContext(session, logMessage)
                AppLogger.e(TOOLPKG_TAG, withToolPkgPluginTag(session, "JS ERROR: $enrichedLogMessage"))
                session.executionListener?.onFailed(callId, logMessage)

                completeCallFuture(
                    session = session,
                    value = error,
                    failureMessage = "Error completing error callback"
                )
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error setting error result: callId=$callId, reason=${e.message}", e)
                resolveExecutionSession(callId)?.future?.completeExceptionally(e)
            }
        }

        private fun completeCallFuture(
            session: ExecutionSession,
            value: String,
            failureMessage: String
        ) {
            try {
                if (!session.future.isDone) {
                    removeExecutionSession(session.callId)
                    session.future.complete(value)
                } else {
                    AppLogger.w(TAG, "Callback became complete between check and execution: callId=${session.callId}")
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "$failureMessage: ${e.message}", e)
                if (!session.future.isDone) {
                    session.future.completeExceptionally(e)
                }
            }
        }

        private fun extractErrorLogMessage(error: String): String {
            return try {
                if (error.startsWith("{") && error.endsWith("}")) {
                    val errorJson = JSONObject(error)
                    if (errorJson.has("formatted")) {
                        return errorJson.getString("formatted")
                    }
                    if (errorJson.has("message")) {
                        return errorJson.getString("message")
                    }
                }
                error
            } catch (e: Exception) {
                error
            }
        }

        @JavascriptInterface
        fun logInfo(message: String) {
            AppLogger.i(TOOLPKG_TAG, withToolPkgPluginTag(message))
        }

        @JavascriptInterface
        fun logWarnForCall(callId: String, message: String) {
            val session = resolveExecutionSession(callId)
            session?.executionListener?.onCallLog(callId, "warn", message)
            AppLogger.w(TOOLPKG_TAG, withToolPkgPluginTag(session, message))
        }

        @JavascriptInterface
        fun logInfoForCall(callId: String, message: String) {
            val session = resolveExecutionSession(callId)
            session?.executionListener?.onCallLog(callId, "info", message)
            AppLogger.i(TOOLPKG_TAG, withToolPkgPluginTag(session, message))
        }

        @JavascriptInterface
        fun logError(message: String) {
            AppLogger.e(TOOLPKG_TAG, withToolPkgPluginTag(message))
        }

        @JavascriptInterface
        fun logErrorForCall(callId: String, message: String) {
            val session = resolveExecutionSession(callId)
            session?.executionListener?.onCallLog(callId, "error", message)
            AppLogger.e(TOOLPKG_TAG, withToolPkgPluginTag(session, message))
        }

        @JavascriptInterface
        fun logDebug(message: String, data: String) {
        }

        @JavascriptInterface
        fun reportError(
                errorType: String,
                errorMessage: String,
                errorLine: Int,
                errorStack: String
        ) {
            AppLogger.e(
                    TOOLPKG_TAG,
                    withToolPkgPluginTag(
                        "DETAILED JS ERROR: \nType: $errorType\nMessage: $errorMessage\nLine: $errorLine\nStack: $errorStack"
                    )
            )
        }

        @JavascriptInterface
        fun reportErrorForCall(
                callId: String,
                errorType: String,
                errorMessage: String,
                errorLine: Int,
                errorStack: String
        ) {
            val session = resolveExecutionSession(callId)
            AppLogger.e(
                    TOOLPKG_TAG,
                    withToolPkgPluginTag(
                        session,
                        "DETAILED JS ERROR: \nType: $errorType\nMessage: $errorMessage\nLine: $errorLine\nStack: $errorStack"
                    )
            )
        }
    }

    /** 销毁引擎资源 */
    fun destroy() {
        if (!destroyed.compareAndSet(false, true)) {
            return
        }
        val engine =
            synchronized(quickJsInitLock) {
                quickJs.also {
                    quickJs = null
                    jsEnvironmentInitialized = false
                }
            }

        // The close task uses the same executor as script calls. Interrupt native execution
        // before waiting for that executor so synchronous package code cannot pin its threads.
        if (engine != null) {
            try {
                engine.interrupt()
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error interrupting QuickJS during destruction: ${e.message}", e)
            }
        }

        try {
            // 确保任何挂起的回调被完成
            drainExecutionSessions("Engine destroyed")
            nativeBridgeScope.cancel()
            clearPendingJsBridgeCallbacks("java bridge callback canceled: Engine destroyed")
            toolCallInterface.detachJavaBridgeLifecycle()

            // 清理Bitmap注册表
            bitmapRegistry.values.forEach { it.recycle() }
            bitmapRegistry.clear()

            // 清理二进制数据注册表
            binaryDataRegistry.clear()
            javaObjectRegistry.clear()

        } catch (e: Exception) {
            AppLogger.e(TAG, "Error during JsEngine destruction: ${e.message}", e)
        } finally {
            try {
                if (engine != null) {
                    runOnQuickJsThreadBlocking(allowWhenDestroyed = true) {
                        engine.close()
                    }
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error closing QuickJS: ${e.message}", e)
            } finally {
                quickJsThread = null
                try {
                    quickJsDispatcher.close()
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Error closing QuickJS dispatcher: ${e.message}", e)
                } finally {
                    try {
                        quickJsExecutor.shutdownNow()
                    } catch (e: Exception) {
                        AppLogger.e(TAG, "Error shutting down QuickJS executor: ${e.message}", e)
                    }
                }
            }
        }
    }

}

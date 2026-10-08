package com.ai.assistance.operit.api.chat.llmprovider

import android.content.Context
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import com.ai.assistance.operit.data.model.ApiProviderType
import com.ai.assistance.operit.data.model.ModelParameter
import com.ai.assistance.operit.data.model.ToolPrompt
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.util.ChatUtils
import com.ai.assistance.operit.util.stream.Stream
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Kimi K2.5 Provider (Moonshot API).
 * Mirrors DeepseekProvider behavior for reasoning_content handling when thinking is enabled.
 */
open class KimiProvider(
    apiEndpoint: String,
    apiKeyProvider: ApiKeyProvider,
    modelName: String,
    client: OkHttpClient,
    customHeaders: Map<String, String> = emptyMap(),
    providerType: ApiProviderType = ApiProviderType.MOONSHOT,
    supportsVision: Boolean = false,
    supportsAudio: Boolean = false,
    supportsVideo: Boolean = false,
    enableToolCall: Boolean = false,
    private val configureThinking: Boolean = true,
    /** Catalog-declared effort values; null means the catalog does not describe effort at all. */
    private val reasoningEfforts: List<String>? = null,
) : OpenAIProvider(
    apiEndpoint = apiEndpoint,
    apiKeyProvider = apiKeyProvider,
    modelName = modelName,
    client = client,
    customHeaders = customHeaders,
    providerType = providerType,
    supportsVision = supportsVision,
    supportsAudio = supportsAudio,
    supportsVideo = supportsVideo,
    enableToolCall = enableToolCall
) {
    // Reasoning is replayed on the wire, so it also occupies the context window.
    override val preserveReasoningForTokenEstimate: Boolean = !configureThinking
    private val requestProviderType = providerType
    private val requestEndpointHost = runCatching { java.net.URI(apiEndpoint).host }.getOrNull().orEmpty()

    override fun createRequestBody(
        context: Context,
        chatHistory: List<PromptTurn>,
        modelParameters: List<ModelParameter<*>>,
        enableThinking: Boolean,
        stream: Boolean,
        availableTools: List<ToolPrompt>?,
        preserveThinkInHistory: Boolean
    ): RequestBody {
        val automaticReasoning = consumeAutomaticReasoningSuppression(modelParameters)
        fun applyThinkingParams(jsonObject: JSONObject) {
            // Generic reasoning_content endpoints do not share Kimi's thinking switch.
            if (!configureThinking) return
            jsonObject.put(
                "thinking",
                JSONObject().apply {
                    put("type", if (enableThinking) "enabled" else "disabled")
                }
            )
        }

        if (!enableThinking && configureThinking) {
            val jsonObject =
                super.createRequestBodyInternal(context, chatHistory, automaticReasoning.modelParameters, stream, availableTools, preserveThinkInHistory)
            applyThinkingParams(jsonObject)
            return createJsonRequestBody(jsonObject.toString())
        }

        val jsonObject = JSONObject()
        jsonObject.put("model", modelName)
        jsonObject.put("stream", stream)
        jsonObject.putStreamUsageOption(stream)
        applyThinkingParams(jsonObject)

        for (param in automaticReasoning.modelParameters) {
            if (param.isEnabled) {
                when (param.valueType) {
                    com.ai.assistance.operit.data.model.ParameterValueType.INT ->
                        jsonObject.put(param.apiName, param.currentValue as Int)
                    com.ai.assistance.operit.data.model.ParameterValueType.FLOAT ->
                        jsonObject.put(param.apiName, param.currentValue as Float)
                    com.ai.assistance.operit.data.model.ParameterValueType.STRING ->
                        jsonObject.put(param.apiName, param.currentValue as String)
                    com.ai.assistance.operit.data.model.ParameterValueType.BOOLEAN ->
                        jsonObject.put(param.apiName, param.currentValue as Boolean)
                    com.ai.assistance.operit.data.model.ParameterValueType.OBJECT -> {
                        val raw = param.currentValue.toString().trim()
                        val parsed: Any? = try {
                            when {
                                raw.startsWith("{") -> JSONObject(raw)
                                raw.startsWith("[") -> JSONArray(raw)
                                else -> null
                            }
                        } catch (e: Exception) {
                            AppLogger.w("ReasoningChatProvider", "OBJECT参数解析失败: ${param.apiName}", e)
                            null
                        }
                        if (parsed != null) {
                            jsonObject.put(param.apiName, parsed)
                        } else {
                            jsonObject.put(param.apiName, raw)
                        }
                    }
                }
            }
        }

        if (!configureThinking && !automaticReasoning.suppressAutomaticReasoning &&
            !jsonObject.has("reasoning_effort")
        ) {
            val preferred = if (enableThinking) resolveOpenAiChatReasoningEffort(context) else "none"
            val effort = preferred?.let {
                ThinkingRequestSemantics.declaredCatalogReasoningEffort(it, reasoningEfforts)
            }
            if (effort != null) {
                jsonObject.put("reasoning_effort", effort)
                AppLogger.d("ReasoningChatProvider", "Generic reasoning_content request reasoning_effort=$effort")
            }
        }

        val effectiveEnableToolCall = enableToolCall && availableTools != null && availableTools.isNotEmpty()

        var toolsJson: String? = null
        if (effectiveEnableToolCall) {
            val tools = buildToolDefinitions(availableTools!!)
            if (tools.length() > 0) {
                jsonObject.put("tools", tools)
                jsonObject.put("tool_choice", "auto")
                toolsJson = tools.toString()
            }
        }

        val providerReadyHistory = prepareHistoryForProvider(chatHistory, effectiveEnableToolCall)
        calculateAndStoreInputTokens(
            providerReadyHistory,
            toolsJson,
            preserveThinkInHistory = true
        )
        val messagesArray =
            buildMessagesWithReasoning(
                context,
                providerReadyHistory,
                effectiveEnableToolCall
            )
        jsonObject.put("messages", messagesArray)

        logRequestBodyForDebugging("ReasoningChatProvider",
            "Request body [provider=$requestProviderType, model=$modelName, host=$requestEndpointHost]: ") {
            jsonObject
        }

        return createJsonRequestBody(jsonObject.toString())
    }

    private fun buildMessagesWithReasoning(
        context: Context,
        effectiveHistory: List<PromptTurn>,
        useToolCall: Boolean
    ): JSONArray {
        val messagesArray = JSONArray()

        var queuedAssistantToolText: String? = null
        val pendingAssistantImageSources = mutableListOf<String>()
        var queuedAssistantReasoning: String? = null
        var queuedToolCalls = JSONArray()
        val queuedOpenToolCalls = mutableListOf<StructuredToolCallBridge.OpenToolCall>()
        val openToolCalls = mutableListOf<StructuredToolCallBridge.OpenToolCall>()
        var nextToolCallOrdinal = 0

        fun appendQueuedAssistantToolText(text: String) {
            if (text.isBlank()) return
            queuedAssistantToolText =
                if (queuedAssistantToolText.isNullOrBlank()) {
                    text
                } else {
                    queuedAssistantToolText + "\n" + text
                }
        }

        fun appendQueuedAssistantReasoning(reasoningContent: String) {
            if (reasoningContent.isBlank()) return
            queuedAssistantReasoning =
                if (queuedAssistantReasoning.isNullOrBlank()) {
                    reasoningContent
                } else {
                    queuedAssistantReasoning + "\n" + reasoningContent
                }
        }

        fun queueToolCalls(textContent: String, toolCalls: JSONArray, reasoningContent: String = "") {
            appendQueuedAssistantToolText(textContent)
            appendQueuedAssistantReasoning(reasoningContent)
            for (i in 0 until toolCalls.length()) {
                val sourceToolCall = toolCalls.optJSONObject(i) ?: continue
                val toolCall = JSONObject(sourceToolCall.toString())
                val callId = generatedToolCallId(nextToolCallOrdinal++)
                toolCall.put("id", callId)
                queuedToolCalls.put(toolCall)
                queuedOpenToolCalls.add(
                    StructuredToolCallBridge.OpenToolCall(
                        callId,
                        StructuredToolCallBridge.toolCallName(toolCall)
                    )
                )
            }
        }

        fun emitQueuedToolCallsIfNeeded() {
            if (queuedToolCalls.length() == 0) return

            messagesArray.put(
                JSONObject().apply {
                    put("role", "assistant")
                    put("reasoning_content", queuedAssistantReasoning.orEmpty())
                    if (!queuedAssistantToolText.isNullOrBlank()) {
                        put("content", buildContentField(context, queuedAssistantToolText!!, role = "assistant"))
                    } else {
                        put("content", null)
                    }
                    put("tool_calls", queuedToolCalls)
                }
            )

            openToolCalls.addAll(queuedOpenToolCalls)
            queuedAssistantToolText?.let(pendingAssistantImageSources::add)
            queuedAssistantToolText = null
            queuedAssistantReasoning = null
            queuedToolCalls = JSONArray()
            queuedOpenToolCalls.clear()
        }

        fun flushOpenToolCallsAsUnmatched(reason: String) {
            emitQueuedToolCallsIfNeeded()
            if (openToolCalls.isEmpty()) {
                appendReadableImageMessageIfNeeded(messagesArray, pendingAssistantImageSources, "assistant tool-call message")
                pendingAssistantImageSources.clear()
                return
            }

            AppLogger.w(
                "ReasoningChatProvider",
                "发现未匹配的tool_calls，按工具结果未匹配处理: count=${openToolCalls.size}, reason=$reason"
            )
            for (openToolCall in openToolCalls) {
                messagesArray.put(
                    JSONObject().apply {
                        put("role", "tool")
                        put("tool_call_id", openToolCall.id)
                        put(
                            "content",
                            StructuredToolCallBridge.unmatchedToolResultContent(
                                reason,
                                openToolCall.matchingName
                            )
                        )
                    }
                )
            }
            openToolCalls.clear()
            appendReadableImageMessageIfNeeded(messagesArray, pendingAssistantImageSources, "assistant tool-call message")
            pendingAssistantImageSources.clear()
        }

        if (effectiveHistory.isNotEmpty()) {
            for (turn in effectiveHistory) {
                val originalContent = comparableContentForTurn(turn, preserveThinkInHistory = true)
                if (useToolCall) {
                    when (turn.kind) {
                        PromptTurnKind.SYSTEM -> {
                            flushOpenToolCallsAsUnmatched("system_boundary")
                            messagesArray.put(
                                JSONObject().apply {
                                    put("role", "system")
                                    put("content", buildContentField(context, originalContent, role = "system"))
                                }
                            )
                        }

                        PromptTurnKind.USER,
                        PromptTurnKind.SUMMARY -> {
                            flushOpenToolCallsAsUnmatched("user_boundary")
                            messagesArray.put(
                                JSONObject().apply {
                                    put("role", "user")
                                    put("content", buildContentField(context, originalContent))
                                }
                            )
                        }

                        PromptTurnKind.ASSISTANT -> {
                            val (content, reasoningContent) = ChatUtils.extractThinkingContent(originalContent)
                            val (textContent, parsedToolCalls) = parseXmlToolCalls(content)
                            val toolCalls =
                                if (parsedToolCalls != null) {
                                    wrapPackageToolCallsWithProxy(parsedToolCalls)
                                } else {
                                    null
                                }

                            if (toolCalls != null && toolCalls.length() > 0) {
                                if (openToolCalls.isNotEmpty()) {
                                    flushOpenToolCallsAsUnmatched("assistant_tool_call_before_result")
                                }
                                queueToolCalls(textContent, toolCalls, reasoningContent)
                            } else {
                                flushOpenToolCallsAsUnmatched("assistant_boundary")
                                messagesArray.put(
                                    JSONObject().apply {
                                        put("role", "assistant")
                                        put("reasoning_content", reasoningContent)
                                        put("content", buildContentField(context, content.ifBlank { "[Empty]" }, role = "assistant"))
                                    }
                                )
                                appendReadableImageMessageIfNeeded(
                                    messagesArray,
                                    content,
                                    "assistant message"
                                )
                            }
                        }

                        PromptTurnKind.TOOL_CALL -> {
                            val (textContent, parsedToolCalls) = parseXmlToolCalls(originalContent)
                            val toolCalls =
                                if (parsedToolCalls != null) {
                                    wrapPackageToolCallsWithProxy(parsedToolCalls)
                                } else {
                                    null
                                }

                            if (toolCalls != null && toolCalls.length() > 0) {
                                if (openToolCalls.isNotEmpty()) {
                                    flushOpenToolCallsAsUnmatched("typed_tool_call_before_result")
                                }
                                queueToolCalls(textContent, toolCalls)
                            } else {
                                flushOpenToolCallsAsUnmatched("typed_tool_call_without_payload")
                                messagesArray.put(
                                    JSONObject().apply {
                                        put("role", "assistant")
                                        put("reasoning_content", "")
                                        put("content", buildContentField(context, originalContent.ifBlank { "[Empty]" }, role = "assistant"))
                                    }
                                )
                                appendReadableImageMessageIfNeeded(
                                    messagesArray,
                                    originalContent,
                                    "assistant tool-call message"
                                )
                            }
                        }

                        PromptTurnKind.TOOL_RESULT -> {
                            emitQueuedToolCallsIfNeeded()
                            val (textContent, toolResults) = parseXmlToolResults(originalContent)
                            val resultsList = toolResults ?: emptyList()

                            if (resultsList.isNotEmpty() && openToolCalls.isNotEmpty()) {
                                val readableImageSources = mutableListOf<String>()
                                val matchedCalls =
                                    StructuredToolCallBridge.consumeMatchingToolCalls(
                                        openToolCalls,
                                        resultsList.map { it.first }
                                    )
                                matchedCalls.forEach { matchedCall ->
                                    val resultContent = resultsList[matchedCall.resultIndex].second
                                    readableImageSources.add(resultContent)
                                    messagesArray.put(
                                        JSONObject().apply {
                                            put("role", "tool")
                                            put("tool_call_id", matchedCall.call.id)
                                            put("content", buildContentField(context, resultContent, role = "tool"))
                                        }
                                    )
                                }

                                if (matchedCalls.size < resultsList.size) {
                                    AppLogger.w(
                                        "ReasoningChatProvider",
                                        "发现未匹配的tool_result: ${resultsList.size - matchedCalls.size}"
                                    )
                                }

                                flushOpenToolCallsAsUnmatched("tool_result_partial_batch")

                                appendReadableImageMessageIfNeeded(
                                    messagesArray,
                                    readableImageSources,
                                    "tool result"
                                )

                                if (textContent.isNotEmpty()) {
                                    messagesArray.put(
                                        JSONObject().apply {
                                            put("role", "user")
                                            put("content", buildContentField(context, textContent))
                                        }
                                    )
                                }
                            } else {
                                flushOpenToolCallsAsUnmatched("tool_result_without_structured_match")
                                if (textContent.isNotEmpty()) {
                                    messagesArray.put(
                                        JSONObject().apply {
                                            put("role", "user")
                                            put("content", buildContentField(context, textContent))
                                        }
                                    )
                                }
                            }
                        }
                    }
                } else {
                    when (turn.kind) {
                        PromptTurnKind.SYSTEM -> {
                            messagesArray.put(
                                JSONObject().apply {
                                    put("role", "system")
                                    put("content", buildContentField(context, originalContent, role = "system"))
                                }
                            )
                        }

                        PromptTurnKind.USER,
                        PromptTurnKind.SUMMARY -> {
                            messagesArray.put(
                                JSONObject().apply {
                                    put("role", "user")
                                    put("content", buildContentField(context, originalContent))
                                }
                            )
                        }

                        PromptTurnKind.TOOL_RESULT -> {
                            messagesArray.put(
                                JSONObject().apply {
                                    put("role", "user")
                                    put("content", buildContentField(context, originalContent))
                                }
                            )
                        }

                        PromptTurnKind.ASSISTANT -> {
                            val (content, reasoningContent) = ChatUtils.extractThinkingContent(originalContent)
                            messagesArray.put(
                                JSONObject().apply {
                                    put("role", "assistant")
                                    put("reasoning_content", reasoningContent)
                                    put("content", buildContentField(context, content.ifBlank { "[Empty]" }, role = "assistant"))
                                }
                            )
                            appendReadableImageMessageIfNeeded(
                                messagesArray,
                                content,
                                "assistant message"
                            )
                        }

                        PromptTurnKind.TOOL_CALL -> {
                            messagesArray.put(
                                JSONObject().apply {
                                    put("role", "assistant")
                                    put("reasoning_content", "")
                                    put("content", buildContentField(context, originalContent.ifBlank { "[Empty]" }, role = "assistant"))
                                }
                            )
                            appendReadableImageMessageIfNeeded(
                                messagesArray,
                                originalContent,
                                "assistant tool-call message"
                            )
                        }
                    }
                }
            }
        }

        flushOpenToolCallsAsUnmatched("history_end")
        return messagesArray
    }

    override suspend fun sendMessage(
        context: Context,
        chatHistory: List<PromptTurn>,
        modelParameters: List<ModelParameter<*>>,
        enableThinking: Boolean,
        stream: Boolean,
        availableTools: List<ToolPrompt>?,
        preserveThinkInHistory: Boolean,
        onTokensUpdated: suspend (input: Int, cachedInput: Int, output: Int) -> Unit,
        onUsageReported: (suspend (com.ai.assistance.operit.data.stats.ProviderUsageSnapshot, attempt: Int) -> Unit)?,
        onNonFatalError: suspend (error: String) -> Unit,
        enableRetry: Boolean,
        statsCategory: com.ai.assistance.operit.data.stats.TokenStatCategory?
    ): Stream<String> {
        return super.sendMessage(
            context,
            chatHistory,
            modelParameters,
            enableThinking,
            stream,
            availableTools,
            preserveThinkInHistory,
            onTokensUpdated,
            onUsageReported,
            onNonFatalError,
            enableRetry,
            statsCategory
        )
    }
}

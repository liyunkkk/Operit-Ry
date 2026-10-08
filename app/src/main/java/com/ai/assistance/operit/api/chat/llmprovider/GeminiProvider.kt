package com.ai.assistance.operit.api.chat.llmprovider

import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import com.ai.assistance.operit.core.chat.hooks.toPromptTurns
import com.ai.assistance.operit.data.model.ApiProviderType
import com.ai.assistance.operit.data.model.ModelOption
import com.ai.assistance.operit.data.model.ModelParameter
import com.ai.assistance.operit.data.model.ToolPrompt
import com.ai.assistance.operit.data.model.ParameterCategory
import com.ai.assistance.operit.data.stats.ProviderUsageNormalizer
import com.ai.assistance.operit.util.ChatUtils
import com.ai.assistance.operit.util.ChatMarkupRegex
import com.ai.assistance.operit.util.HttpLogSanitizer
import com.ai.assistance.operit.util.StreamingJsonXmlConverter
import com.ai.assistance.operit.util.TokenCacheManager
import com.ai.assistance.operit.util.exceptions.UserCancellationException
import com.ai.assistance.operit.util.stream.MutableSharedStream
import com.ai.assistance.operit.util.stream.Stream
import com.ai.assistance.operit.util.stream.StreamCollector
import com.ai.assistance.operit.util.stream.TextStreamEvent
import com.ai.assistance.operit.util.stream.TextStreamEventType
import com.ai.assistance.operit.util.stream.withEventChannel
import com.ai.assistance.operit.util.stream.stream
import android.content.Context
import android.net.Uri
import android.os.Environment
import com.ai.assistance.operit.R
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import com.ai.assistance.operit.api.chat.llmprovider.MediaLinkParser

internal fun buildGeminiThinkingConfig(
    enableThinking: Boolean,
    modelParameters: List<ModelParameter<*>>,
    modelName: String = "",
): JSONObject? {
    val thinkingBudget =
        (modelParameters.lastOrNull {
            it.apiName in setOf("thinking_budget", "thinkingBudget") && it.isEnabled
        }
            ?.currentValue as? Number)?.toInt()
    val nativeThinkingConfig =
        modelParameters
            .lastOrNull {
                it.apiName == "thinkingConfig" &&
                    it.isEnabled &&
                    it.category != ParameterCategory.OTHER
            }
            ?.currentValue
            ?.toString()
            ?.trim()
            ?.takeIf { it.startsWith("{") }
            ?.let { raw -> runCatching { JSONObject(raw) }.getOrNull() }
    val flatThinkingLevel =
        modelParameters
            .lastOrNull {
                it.apiName in setOf("thinking_level", "thinkingLevel") && it.isEnabled
            }
            ?.currentValue
            ?.toString()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    val nativeThinkingLevel =
        nativeThinkingConfig
            ?.optString("thinkingLevel", "")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    val requestedThinkingLevel = flatThinkingLevel ?: nativeThinkingLevel

    if (isGemini37FlashModel(modelName)) {
        // Gemini 3.7 Flash cannot disable thinking. When the chat switch is off, always use the
        // supported minimum; persisted flat or provider-native overrides must not silently
        // override the disabled UI state.
        val thinkingLevel =
            if (!enableThinking) {
                "low"
            } else {
                requestedThinkingLevel
                    ?.lowercase()
                    ?.let { if (it == "minimal") "low" else it }
                    ?.takeIf { it in setOf("low", "medium", "high") }
                    ?: "medium"
            }
        return JSONObject().apply {
            put("includeThoughts", enableThinking)
            put("thinkingLevel", thinkingLevel)
        }
    }

    if (!enableThinking) {
        return null
    }

    return JSONObject().apply {
        put("includeThoughts", true)
        if (!isGemini3Model(modelName)) {
            thinkingBudget?.let { put("thinkingBudget", it) }
        }
        requestedThinkingLevel?.let { put("thinkingLevel", it) }
    }
}

private const val GEMINI_REPLAY_INLINE_DATA_REF = "__operitGeminiInlineDataFile"

internal data class GeminiReplayInlineDataFile(
    val fileName: String,
    val sha256: String,
)

private fun geminiReplaySha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }

/**
 * Collects a streamed Gemini model turn for exact replay when the API returns state that cannot be
 * reconstructed from visible text (thought signatures, function calls, or server-side tool parts).
 *
 * Plain text is retained only in memory until the turn is known to require replay and adjacent
 * unsigned text chunks are coalesced. When an inline image has been persisted locally, its raw
 * Base64 is replaced only inside hidden metadata by a restricted file reference plus checksum; the
 * original Part fields and signature remain in place and are hydrated before the next request.
 */
internal class GeminiReplayContentAccumulator {
    private val parts = JSONArray()
    private var lastPlainTextPart: JSONObject? = null
    private var requiresReplay = false
    private var role = "model"

    fun appendContent(
        content: JSONObject,
        inlineDataFiles: Map<Int, GeminiReplayInlineDataFile> = emptyMap(),
    ) {
        content.optString("role", "").trim().takeIf { it.isNotEmpty() }?.let { role = it }
        val sourceParts = content.optJSONArray("parts") ?: return
        for (index in 0 until sourceParts.length()) {
            val sourcePart = sourceParts.optJSONObject(index) ?: continue
            appendPart(sourcePart, inlineDataFiles[index])
        }
    }

    fun buildContentOrNull(): JSONObject? {
        if (!requiresReplay || parts.length() == 0) return null
        return JSONObject().apply {
            put("role", role)
            put("parts", JSONArray(parts.toString()))
        }
    }

    private fun appendPart(
        sourcePart: JSONObject,
        inlineDataFile: GeminiReplayInlineDataFile?,
    ) {
        if (sourcePart.has("inlineData") || sourcePart.has("inline_data")) {
            val part = JSONObject(sourcePart.toString())
            if (inlineDataFile != null) {
                val inlineDataKey = if (part.has("inlineData")) "inlineData" else "inline_data"
                part.getJSONObject(inlineDataKey).remove("data")
                part.put(
                    GEMINI_REPLAY_INLINE_DATA_REF,
                    JSONObject().apply {
                        put("fileName", inlineDataFile.fileName)
                        put("sha256", inlineDataFile.sha256)
                    },
                )
            }
            // If local persistence failed, retain the original Part including Base64. Correct
            // replay takes priority over the storage optimization.
            requiresReplay = true
            parts.put(part)
            lastPlainTextPart = null
            return
        }

        val part = JSONObject(sourcePart.toString())
        val keys = mutableSetOf<String>()
        val keyIterator = part.keys()
        while (keyIterator.hasNext()) {
            keys += keyIterator.next()
        }
        val isPlainTextPart =
            part.has("text") && keys.all { key -> key == "text" || key == "thought" }

        if (!isPlainTextPart) {
            requiresReplay = true
            parts.put(part)
            lastPlainTextPart = null
            return
        }

        val thought = part.optBoolean("thought", false)
        val previous = lastPlainTextPart
        if (
            previous != null &&
                previous.optBoolean("thought", false) == thought
        ) {
            previous.put(
                "text",
                previous.optString("text", "") + part.optString("text", ""),
            )
        } else {
            parts.put(part)
            lastPlainTextPart = part
        }
    }
}

internal fun filterGeminiMediaLinks(
    links: List<MediaLink>,
    supportsAudio: Boolean,
    supportsVideo: Boolean,
): List<MediaLink> =
    links.filter { link ->
        when (link.type) {
            "audio" -> supportsAudio
            "video" -> supportsVideo
            else -> false
        }
    }

/** Google Gemini API的实现 支持标准Gemini接口流式传输 */
open class GeminiProvider(
    private val apiEndpoint: String,
    private val apiKeyProvider: ApiKeyProvider,
    private val modelName: String,
    private val client: OkHttpClient,
    private val customHeaders: Map<String, String> = emptyMap(),
    private val providerType: ApiProviderType = ApiProviderType.GOOGLE,
    private val enableGoogleSearch: Boolean = false,
    private val supportsVision: Boolean = true,
    private val supportsAudio: Boolean = true,
    private val supportsVideo: Boolean = true,
    private val enableToolCall: Boolean = false, // 是否启用Tool Call接口
    private val antigravity: AntigravityTransport? = null,
) : AIService {
    companion object {
        private const val TAG = "GeminiProvider"
        private const val DEBUG = true // 开启调试日志
    }

    // HTTP客户端
    // private val client: OkHttpClient = HttpClientFactory.instance

    private val JSON = "application/json".toMediaType()

    // 活跃请求，用于取消流式请求
    private var activeCall: Call? = null
    private var activeResponse: Response? = null
    @Volatile private var isManuallyCancelled = false

    /** Gemini HTTP 4xx 响应；是否继续尝试下一密钥由统一重试策略决定。 */
    class NonRetriableException(
        message: String,
        override val statusCode: Int,
        cause: Throwable? = null
    ) : IOException(message, cause), HttpStatusCodeException

    /** 本地历史媒体已经缺失或变化，重复请求及切换 API Key 都无法恢复。 */
    class ReplayMediaException(
        message: String,
        cause: Throwable? = null,
    ) : IOException(message, cause)

    // Token计数
    private val tokenCacheManager = TokenCacheManager()
    
    // 思考状态跟踪
    private var isInThinkingMode = false

    override val inputTokenCount: Int
        get() = tokenCacheManager.totalInputTokenCount
    override val cachedInputTokenCount: Int
        get() = tokenCacheManager.cachedInputTokenCount
    override val outputTokenCount: Int
        get() = tokenCacheManager.outputTokenCount

    // 供应商:模型标识符
    override val providerModel: String
        get() = "${providerType.name}:$modelName"

    // 取消当前流式传输
    override fun cancelStreaming() {
        isManuallyCancelled = true

        // 1. 强制关闭 Response（这会立即中断流读取操作）
        activeResponse?.let {
            try {
                it.close()
                AppLogger.d(TAG, "已强制关闭Response流")
            } catch (e: Exception) {
                AppLogger.w(TAG, "关闭Response时出错: ${e.message}")
            }
        }
        activeResponse = null

        // 2. 取消 Call
        activeCall?.let {
            if (!it.isCanceled()) {
                it.cancel()
                AppLogger.d(TAG, "已取消当前流式传输，Call已中断")
            }
        }
        activeCall = null

        AppLogger.d(TAG, "取消标志已设置，流读取将立即被中断")
    }

    // 重置Token计数
    override fun resetTokenCounts() {
        tokenCacheManager.resetTokenCounts()
        isInThinkingMode = false
    }

    override suspend fun calculateInputTokens(
            chatHistory: List<PromptTurn>,
            availableTools: List<ToolPrompt>?
    ): Int {
        // 构建工具定义的JSON字符串
        val toolsJson = buildToolsJson(availableTools)
        val comparableHistory =
            ChatUtils.stripGeminiThoughtSignatureMeta(
                chatHistory.map { turn ->
                    val comparableRole =
                        when (turn.kind) {
                            PromptTurnKind.SYSTEM -> "system"
                            PromptTurnKind.USER -> "user"
                            PromptTurnKind.ASSISTANT -> "assistant"
                            PromptTurnKind.TOOL_CALL -> "tool_call"
                            PromptTurnKind.TOOL_RESULT -> "tool_result"
                            PromptTurnKind.SUMMARY -> "summary"
                        }
                    val comparableContent =
                        if (turn.kind == PromptTurnKind.ASSISTANT) {
                            ChatUtils.removeThinkingContent(turn.content)
                        } else {
                            turn.content
                        }
                    comparableRole to comparableContent
                }
            )
        return tokenCacheManager.calculateInputTokens(
            comparableHistory,
            toolsJson,
            updateState = false
        )
    }
    
    /**
     * 构建工具定义的JSON字符串，用于token计算
     */
    private fun buildToolsJson(availableTools: List<ToolPrompt>?): String? {
        if (!enableToolCall || availableTools == null || availableTools.isEmpty()) {
            return if (enableGoogleSearch) {
                // 只有 Google Search
                JSONArray().apply {
                    put(JSONObject().apply {
                        put("googleSearch", JSONObject())
                    })
                }.toString()
            } else {
                null
            }
        }
        
        val tools = JSONArray()
        
        // 添加 Function Calling 工具
        val functionDeclarations = buildToolDefinitionsForGemini(availableTools)
        if (functionDeclarations.length() > 0) {
            tools.put(JSONObject().apply {
                put("function_declarations", functionDeclarations)
            })
        }
        
        // 添加 Google Search grounding 工具（如果启用）
        if (enableGoogleSearch) {
            tools.put(JSONObject().apply {
                put("googleSearch", JSONObject())
            })
        }
        
        return if (tools.length() > 0) tools.toString() else null
    }

    // ==================== Tool Call 支持 ====================
    
    /**
     * XML转义/反转义工具
     */
    private object XmlEscaper {
        fun escape(text: String): String {
            return text.replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\"", "&quot;")
                    .replace("'", "&apos;")
        }
        
        fun unescape(text: String): String {
            return text.replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace("&quot;", "\"")
                    .replace("&apos;", "'")
                    .replace("&amp;", "&")
        }
    }

    private data class GeminiThoughtSignaturePayload(
        val contentWithoutMeta: String,
        val thoughtSignature: String?
    )

    private data class GeminiFunctionCallPayload(
        val textContent: String,
        val functionCalls: List<JSONObject>,
        val thoughtSignature: String?
    )

    private data class GeminiPendingFunctionCall(
        val name: String,
        val id: String?,
        val matchingName: String = name,
    )

    private fun decodeGeminiThoughtSignature(signatureBase64: String): String? {
        return try {
            String(Base64.getMimeDecoder().decode(signatureBase64), Charsets.UTF_8)
                .takeIf { it.isNotEmpty() }
        } catch (e: IllegalArgumentException) {
            logDebug("Gemini thoughtSignature meta base64 无法解码，已忽略")
            null
        }
    }

    private fun extractGeminiThoughtSignaturePayload(content: String): GeminiThoughtSignaturePayload {
        val signatureBase64 = ChatMarkupRegex.extractGeminiThoughtSignature(content)
        val contentWithoutMeta = ChatMarkupRegex.removeGeminiThoughtSignatureMeta(content)
        val thoughtSignature = if (antigravity == null) signatureBase64?.let { decodeGeminiThoughtSignature(it) } else null
        return GeminiThoughtSignaturePayload(
            contentWithoutMeta = contentWithoutMeta,
            thoughtSignature = thoughtSignature
        )
    }

    private fun encodeGeminiContent(content: JSONObject): String {
        val encodedContent = JSONObject(content.toString())
        antigravity?.let { encodedContent.put("_operit_account_scope", it.activeReplayScope) }
        return Base64.getEncoder()
            .encodeToString(encodedContent.toString().toByteArray(Charsets.UTF_8))
    }

    private fun decodeGeminiContents(content: String): List<JSONObject> {
        return ChatMarkupRegex.extractGeminiContentPayloads(content).mapNotNull { payload ->
            try {
                val decoded = String(Base64.getMimeDecoder().decode(payload), Charsets.UTF_8)
                val value = prepareAccountReplay(JSONObject(decoded), antigravity?.currentReplayScope().orEmpty())
                hydrateGeminiReplayContent(value)
            } catch (e: ReplayMediaException) {
                throw e
            } catch (e: Exception) {
                logDebug("Gemini content meta 无法解码，已忽略: ${e.message}")
                null
            }
        }
    }

    private fun hydrateGeminiReplayContent(content: JSONObject): JSONObject {
        val parts = content.optJSONArray("parts") ?: return content
        for (index in 0 until parts.length()) {
            val part = parts.optJSONObject(index) ?: continue
            if (!part.has(GEMINI_REPLAY_INLINE_DATA_REF)) continue

            val reference =
                part.optJSONObject(GEMINI_REPLAY_INLINE_DATA_REF)
                    ?: throw geminiReplayMediaFailure("invalid-reference", "引用格式无效")
            val fileName = reference.optString("fileName", "").trim()
            val expectedSha256 = reference.optString("sha256", "").trim().lowercase()
            val inlineData =
                part.optJSONObject("inlineData") ?: part.optJSONObject("inline_data")
                    ?: throw geminiReplayMediaFailure(fileName, "缺少 inlineData Part")
            val bytes = readGeminiReplayMedia(fileName, expectedSha256)

            inlineData.put("data", Base64.getEncoder().encodeToString(bytes))
            part.remove(GEMINI_REPLAY_INLINE_DATA_REF)
        }
        return content
    }

    private fun readGeminiReplayMedia(
        fileName: String,
        expectedSha256: String,
    ): ByteArray {
        if (
            fileName.isBlank() ||
                fileName != File(fileName).name ||
                fileName.contains('/') ||
                fileName.contains('\\') ||
                !expectedSha256.matches(Regex("[0-9a-f]{64}"))
        ) {
            throw geminiReplayMediaFailure(fileName, "引用校验失败")
        }

        val outputDirectory =
            try {
                getOutputImagesDir().canonicalFile
            } catch (e: IOException) {
                throw geminiReplayMediaFailure(fileName, "无法解析输出目录", e)
            }
        val replayFile =
            try {
                File(outputDirectory, fileName).canonicalFile
            } catch (e: IOException) {
                throw geminiReplayMediaFailure(fileName, "无法解析图片文件", e)
            }
        if (replayFile.parentFile != outputDirectory || !replayFile.isFile) {
            throw geminiReplayMediaFailure(fileName, "图片文件不存在")
        }

        val bytes =
            try {
                replayFile.readBytes()
            } catch (e: IOException) {
                throw geminiReplayMediaFailure(fileName, "无法读取图片文件", e)
            }
        if (geminiReplaySha256(bytes) != expectedSha256) {
            throw geminiReplayMediaFailure(fileName, "图片文件内容已变化")
        }
        return bytes
    }

    private fun geminiReplayMediaFailure(
        fileName: String,
        detail: String,
        cause: Throwable? = null,
    ): ReplayMediaException {
        val safeName = fileName.take(120).ifBlank { "unknown" }
        return ReplayMediaException(
            message = "Gemini 历史图片无法精确回放（$detail）：$safeName",
            cause = cause,
        )
    }

    private fun appendGeminiContentMeta(
        contentBuilder: StringBuilder,
        content: JSONObject,
    ) {
        if (contentBuilder.isNotEmpty() && contentBuilder[contentBuilder.length - 1] != '\n') {
            contentBuilder.append('\n')
        }
        contentBuilder.append(
            ChatMarkupRegex.geminiContentMetaTag(encodeGeminiContent(content))
        )
    }

    /**
     * 解析XML格式的tool调用，转换为Gemini FunctionCall格式
     * @return 文本内容、functionCall对象列表、以及挂在Part级别的thought signature
     */
    private fun parseXmlToolCalls(content: String): GeminiFunctionCallPayload {
        if (!enableToolCall) {
            return GeminiFunctionCallPayload(
                textContent = content,
                functionCalls = emptyList(),
                thoughtSignature = null
            )
        }

        val thoughtSignaturePayload = extractGeminiThoughtSignaturePayload(content)
        val sanitizedContent = thoughtSignaturePayload.contentWithoutMeta
        val matches = ChatMarkupRegex.toolCallPattern.findAll(sanitizedContent).toList()
        
        if (matches.isEmpty()) {
            return GeminiFunctionCallPayload(
                textContent = sanitizedContent,
                functionCalls = emptyList(),
                thoughtSignature = null
            )
        }

        val functionCalls =
            matches.map { match ->
                val toolName = match.groupValues[2]
                val toolBody = match.groupValues[3]

                val args = JSONObject()
                ChatMarkupRegex.toolParamPattern.findAll(toolBody).forEach { paramMatch ->
                    val paramName = paramMatch.groupValues[1]
                    val paramValue = XmlEscaper.unescape(paramMatch.groupValues[2].trim())
                    args.put(paramName, paramValue)
                }

                AppLogger.d(TAG, "XML→GeminiFunctionCall: $toolName")
                JSONObject().apply {
                    put("name", toolName)
                    put("args", args)
                }
            }

        var textContent = sanitizedContent
        matches.forEach { match ->
            textContent = textContent.replace(match.value, "").trim()
        }
        
        return GeminiFunctionCallPayload(
            textContent = textContent,
            functionCalls = functionCalls,
            thoughtSignature = thoughtSignaturePayload.thoughtSignature
        )
    }
    
    /**
     * 解析XML格式的tool_result，转换为Gemini FunctionResponse格式
     * @return Pair<文本内容, functionResponse对象列表>
     */
    private fun parseXmlToolResults(content: String): Pair<String, List<JSONObject>?> {
        if (!enableToolCall) return Pair(content, null)
        
        val matches = ChatMarkupRegex.toolResultWithNameAnyPattern.findAll(content)
        
        if (!matches.any()) {
            return Pair(content, null)
        }
        
        val functionResponses = mutableListOf<JSONObject>()
        var textContent = content
        
        matches.forEach { match ->
            val toolName = match.groupValues[2]
            val fullContent = match.groupValues[3].trim()
            val contentMatch = ChatMarkupRegex.contentTag.find(fullContent)
            val resultContent = if (contentMatch != null) {
                contentMatch.groupValues[1].trim()
            } else {
                fullContent
            }
            
            // 构建functionResponse对象（Gemini格式）
            val functionResponse = JSONObject().apply {
                put("name", toolName)
                put("response", JSONObject().apply {
                    put("result", resultContent)
                })
            }
            
            functionResponses.add(functionResponse)
            AppLogger.d(TAG, "解析Gemini functionResponse: $toolName, content length=${resultContent.length}")
            
            textContent = textContent.replace(match.value, "").trim()
        }
        
        return Pair(textContent, functionResponses)
    }
    
    /**
     * 从ToolPrompt列表构建Gemini格式的Function Declarations
     */
    private fun buildToolDefinitionsForGemini(toolPrompts: List<ToolPrompt>): JSONArray {
        val functionDeclarations = JSONArray()
        
        for (tool in toolPrompts) {
            functionDeclarations.put(JSONObject().apply {
                put("name", tool.name)
                // 组合description和details作为完整描述
                val fullDescription = if (tool.details.isNotEmpty()) {
                    "${tool.description}\n${tool.details}"
                } else {
                    tool.description
                }
                put("description", fullDescription)
                
                // 使用结构化参数构建schema
                val parametersSchema = buildSchemaFromStructured(tool.parametersStructured ?: emptyList())
                put("parameters", parametersSchema)
            })
        }
        
        return functionDeclarations
    }
    
    /**
     * 从结构化参数构建JSON Schema（Gemini格式）
     */
    private fun buildSchemaFromStructured(params: List<com.ai.assistance.operit.data.model.ToolParameterSchema>): JSONObject {
        val schema = JSONObject().apply {
            put("type", "object")
        }
        
        val properties = JSONObject()
        val required = JSONArray()
        
        for (param in params) {
            properties.put(param.name, JSONObject().apply {
                put("type", param.type)
                put("description", param.description)
                if (param.default != null) {
                    put("default", param.default)
                }
            })
            
            if (param.required) {
                required.put(param.name)
            }
        }
        
        schema.put("properties", properties)
        if (required.length() > 0) {
            schema.put("required", required)
        }
        
        return schema
    }
    
    /** 构建包含文本和已启用多模态输入的 parts 数组。 */
    private fun buildPartsArray(text: String): JSONArray {
        val partsArray = JSONArray()

        val hasImages = MediaLinkParser.hasImageLinks(text)
        val hasMedia = MediaLinkParser.hasMediaLinks(text)

        if (hasImages || hasMedia) {
            val imageLinks =
                if (hasImages && supportsVision) {
                    MediaLinkParser.extractImageLinks(text)
                } else {
                    emptyList()
                }
            val allMediaLinks =
                if (hasMedia) MediaLinkParser.extractMediaLinks(text) else emptyList()
            val mediaLinks =
                filterGeminiMediaLinks(allMediaLinks, supportsAudio, supportsVideo)

            if (hasImages && !supportsVision) {
                AppLogger.d(TAG, "当前Gemini模型未启用识图，图片链接已省略")
            }
            if (allMediaLinks.any { it.type == "audio" } && !supportsAudio) {
                AppLogger.d(TAG, "当前Gemini模型未启用音频输入，音频链接已省略")
            }
            if (allMediaLinks.any { it.type == "video" } && !supportsVideo) {
                AppLogger.d(TAG, "当前Gemini模型未启用视频输入，视频链接已省略")
            }

            var textWithoutLinks = text
            if (hasImages) {
                textWithoutLinks = MediaLinkParser.removeImageLinks(textWithoutLinks)
            }
            if (hasMedia) {
                textWithoutLinks = MediaLinkParser.removeMediaLinks(textWithoutLinks)
            }
            textWithoutLinks = textWithoutLinks.trim()

            // 添加媒体（音频/视频）
            mediaLinks.forEach { link ->
                partsArray.put(JSONObject().apply {
                    put("inline_data", JSONObject().apply {
                        put("mime_type", link.mimeType)
                        put("data", link.base64Data)
                    })
                })
            }

            // 添加图片
            imageLinks.forEach { link ->
                partsArray.put(JSONObject().apply {
                    put("inline_data", JSONObject().apply {
                        put("mime_type", link.mimeType)
                        put("data", link.base64Data)
                    })
                })
            }

            // 添加文本（如果有）
            if (textWithoutLinks.isNotEmpty()) {
                partsArray.put(JSONObject().apply {
                    put("text", textWithoutLinks)
                })
            }
            if (partsArray.length() == 0) {
                partsArray.put(
                    JSONObject().apply {
                        put("text", "[Unsupported media omitted for current model]")
                    }
                )
            }
        } else {
            // 纯文本消息
            partsArray.put(JSONObject().apply {
                put("text", text)
            })
        }
        
        return partsArray
    }

    private fun buildContentsAndCountTokens(
            chatHistory: List<PromptTurn>,
            toolsJson: String? = null,
            preserveThinkInHistory: Boolean = false
    ): Pair<Pair<JSONArray, JSONObject?>, Int> {
        val contentsArray = JSONArray()
        var systemInstruction: JSONObject? = null

        val providerReadyHistory =
            StructuredToolCallBridge.compileHistoryForProvider(
                chatHistory,
                useToolCall = enableToolCall
            )

        // 使用TokenCacheManager计算token数量
        val sanitizedHistoryForTokenCount =
            ChatUtils.stripGeminiThoughtSignatureMeta(
                providerReadyHistory.map { turn ->
                    val comparableRole =
                        when (turn.kind) {
                            PromptTurnKind.SYSTEM -> "system"
                            PromptTurnKind.USER -> "user"
                            PromptTurnKind.ASSISTANT -> "assistant"
                            PromptTurnKind.TOOL_CALL -> "tool_call"
                            PromptTurnKind.TOOL_RESULT -> "tool_result"
                            PromptTurnKind.SUMMARY -> "summary"
                        }
                    val comparableContent =
                        if (!preserveThinkInHistory && turn.kind == PromptTurnKind.ASSISTANT) {
                            ChatUtils.removeThinkingContent(turn.content)
                        } else {
                            turn.content
                        }
                    comparableRole to comparableContent
                }
            )
        val tokenCount = tokenCacheManager.calculateInputTokens(
            sanitizedHistoryForTokenCount,
            toolsJson
        )

        val effectiveHistory = providerReadyHistory

        // Find and process system message first
        val systemMessages = effectiveHistory.filter { it.kind == PromptTurnKind.SYSTEM }
        if (systemMessages.isNotEmpty()) {
            val systemContent = systemMessages.joinToString("\n\n") { it.content }
            logDebug("发现系统消息: ${systemContent.take(50)}...")

            systemInstruction = JSONObject().apply {
                put("parts", JSONArray().apply {
                    put(JSONObject().apply { put("text", systemContent) })
                })
            }
        }

        // Process the rest of the history
        val historyWithoutSystem = effectiveHistory.filter { it.kind != PromptTurnKind.SYSTEM }
        var queuedAssistantToolText: String? = null
        var queuedAssistantThoughtSignature: String? = null
        val queuedFunctionCalls = mutableListOf<JSONObject>()
        val openFunctionCalls = mutableListOf<GeminiPendingFunctionCall>()

        fun appendParts(target: JSONArray, parts: JSONArray) {
            for (index in 0 until parts.length()) {
                target.put(parts.get(index))
            }
        }

        fun appendQueuedAssistantToolText(text: String) {
            if (text.isBlank()) return
            queuedAssistantToolText =
                if (queuedAssistantToolText.isNullOrBlank()) {
                    text
                } else {
                    queuedAssistantToolText + "\n" + text
                }
        }

        fun queueFunctionCalls(
            textContent: String,
            functionCalls: List<JSONObject>,
            thoughtSignature: String? = null
        ) {
            appendQueuedAssistantToolText(textContent)
            if (queuedAssistantThoughtSignature == null && !thoughtSignature.isNullOrBlank()) {
                queuedAssistantThoughtSignature = thoughtSignature
            }
            queuedFunctionCalls.addAll(functionCalls)
        }

        fun emitQueuedFunctionCallsIfNeeded() {
            if (queuedFunctionCalls.isEmpty()) return

            val partsArray = JSONArray()
            if (!queuedAssistantToolText.isNullOrBlank()) {
                appendParts(partsArray, buildPartsArray(queuedAssistantToolText!!))
            }
            queuedFunctionCalls.forEachIndexed { index, functionCall ->
                partsArray.put(
                    JSONObject().apply {
                        put("functionCall", functionCall)
                        if (index == 0) {
                            queuedAssistantThoughtSignature?.let { signature ->
                                put("thought_signature", signature)
                            }
                        }
                    }
                )
            }

            contentsArray.put(
                JSONObject().apply {
                    put("role", "model")
                    put("parts", partsArray)
                }
            )

            queuedFunctionCalls.forEach { functionCall ->
                openFunctionCalls.add(
                    GeminiPendingFunctionCall(
                        name = functionCall.optProviderToolName().orEmpty(),
                        id = functionCall.optString("id", "").trim().takeIf { it.isNotEmpty() },
                        matchingName = StructuredToolCallBridge.toolCallName(functionCall),
                    )
                )
            }
            queuedAssistantToolText = null
            queuedAssistantThoughtSignature = null
            queuedFunctionCalls.clear()
        }

        fun emitRawGeminiContents(rawContents: List<JSONObject>) {
            if (rawContents.isEmpty()) return
            emitQueuedFunctionCallsIfNeeded()

            val partsArray = JSONArray()
            rawContents.forEach { rawContent ->
                val rawParts = rawContent.optJSONArray("parts") ?: return@forEach
                for (index in 0 until rawParts.length()) {
                    val sourcePart = rawParts.optJSONObject(index) ?: continue
                    val part = JSONObject(sourcePart.toString())
                    partsArray.put(part)
                    part.optJSONObject("functionCall")?.let { functionCall ->
                        openFunctionCalls.add(
                            GeminiPendingFunctionCall(
                                name = functionCall.optProviderToolName().orEmpty(),
                                matchingName = StructuredToolCallBridge.toolCallName(functionCall),
                                id =
                                    functionCall
                                        .optString("id", "")
                                        .trim()
                                        .takeIf { it.isNotEmpty() },
                            )
                        )
                    }
                }
            }

            if (partsArray.length() > 0) {
                contentsArray.put(
                    JSONObject().apply {
                        put("role", "model")
                        put("parts", partsArray)
                    }
                )
            }
        }

        fun appendCancelledOpenFunctionResponses(target: JSONArray, reason: String): Boolean {
            emitQueuedFunctionCallsIfNeeded()
            if (openFunctionCalls.isEmpty()) return false

            logDebug("发现缺失结果的Gemini functionCall: count=${openFunctionCalls.size}, reason=$reason")
            openFunctionCalls.forEach { pendingCall ->
                target.put(
                    JSONObject().apply {
                        put(
                            "functionResponse",
                            JSONObject().apply {
                                put("name", pendingCall.name.ifBlank { "cancelled_function" })
                                pendingCall.id?.let { put("id", it) }
                                put(
                                    "response",
                                    JSONObject().apply {
                                        put("result", StructuredToolCallBridge.unmatchedToolResultContent(reason, pendingCall.matchingName))
                                    }
                                )
                            }
                        )
                    }
                )
            }
            openFunctionCalls.clear()
            return true
        }

        fun flushOpenFunctionCallsAsCancelled(reason: String) {
            val partsArray = JSONArray()
            if (!appendCancelledOpenFunctionResponses(partsArray, reason)) return
            contentsArray.put(
                JSONObject().apply {
                    put("role", "user")
                    put("parts", partsArray)
                }
            )
        }

        for (turn in historyWithoutSystem) {
            val rawGeminiContents =
                if (
                    turn.kind == PromptTurnKind.ASSISTANT ||
                        turn.kind == PromptTurnKind.TOOL_CALL
                ) {
                    decodeGeminiContents(turn.content)
                } else {
                    emptyList()
                }
            val content =
                if (!preserveThinkInHistory && turn.kind == PromptTurnKind.ASSISTANT) {
                    ChatUtils.removeThinkingContent(turn.content)
                } else {
                    turn.content
                }
            val contentWithoutGeminiMeta = ChatMarkupRegex.removeGeminiThoughtSignatureMeta(content)

            if (rawGeminiContents.isNotEmpty()) {
                if (openFunctionCalls.isNotEmpty()) {
                    flushOpenFunctionCallsAsCancelled("raw_model_content_before_result")
                }
                emitRawGeminiContents(rawGeminiContents)
                continue
            }

            if (enableToolCall) {
                when (turn.kind) {
                    PromptTurnKind.ASSISTANT -> {
                        val functionCallPayload = parseXmlToolCalls(content)
                        if (functionCallPayload.functionCalls.isNotEmpty()) {
                            if (openFunctionCalls.isNotEmpty()) {
                                flushOpenFunctionCallsAsCancelled("assistant_function_call_before_result")
                            }
                            queueFunctionCalls(
                                functionCallPayload.textContent,
                                functionCallPayload.functionCalls,
                                functionCallPayload.thoughtSignature
                            )
                        } else {
                            flushOpenFunctionCallsAsCancelled("assistant_boundary")
                            contentsArray.put(
                                JSONObject().apply {
                                    put("role", "model")
                                    put("parts", buildPartsArray(contentWithoutGeminiMeta))
                                }
                            )
                        }
                    }

                    PromptTurnKind.TOOL_CALL -> {
                        val functionCallPayload = parseXmlToolCalls(content)
                        if (functionCallPayload.functionCalls.isNotEmpty()) {
                            if (openFunctionCalls.isNotEmpty()) {
                                flushOpenFunctionCallsAsCancelled("typed_function_call_before_result")
                            }
                            queueFunctionCalls(
                                functionCallPayload.textContent,
                                functionCallPayload.functionCalls,
                                functionCallPayload.thoughtSignature
                            )
                        } else {
                            flushOpenFunctionCallsAsCancelled("typed_tool_call_without_payload")
                            contentsArray.put(
                                JSONObject().apply {
                                    put("role", "model")
                                    put("parts", buildPartsArray(contentWithoutGeminiMeta))
                                }
                            )
                        }
                    }

                    PromptTurnKind.USER,
                    PromptTurnKind.SUMMARY -> {
                        val partsArray = JSONArray()
                        appendCancelledOpenFunctionResponses(partsArray, "user_boundary")
                        appendParts(partsArray, buildPartsArray(contentWithoutGeminiMeta))
                        contentsArray.put(
                            JSONObject().apply {
                                put("role", "user")
                                put("parts", partsArray)
                            }
                        )
                    }

                    PromptTurnKind.TOOL_RESULT -> {
                        emitQueuedFunctionCallsIfNeeded()
                        val (textContent, functionResponses) = parseXmlToolResults(contentWithoutGeminiMeta)
                        val responsesList = functionResponses ?: emptyList()

                        if (responsesList.isNotEmpty() && openFunctionCalls.isNotEmpty()) {
                            val partsArray = JSONArray()
                            for (sourceResponse in responsesList) {
                                val response = JSONObject(sourceResponse.toString())
                                val resultName = response.optString("name", "").trim()
                                val callIndex = openFunctionCalls.indexOfFirst {
                                    resultName.isNotBlank() && it.matchingName == resultName
                                }
                                if (callIndex < 0) {
                                    logDebug("忽略未匹配的Gemini functionResponse")
                                    continue
                                }
                                val pendingCall = openFunctionCalls.removeAt(callIndex)
                                if (pendingCall.name.isNotBlank()) {
                                    response.put("name", pendingCall.name)
                                }
                                pendingCall.id?.let { response.put("id", it) }
                                partsArray.put(
                                    JSONObject().apply {
                                        put("functionResponse", response)
                                    }
                                )
                                logDebug("历史XML→GeminiFunctionResponse: ${response.optString("name")}")
                            }

                            appendCancelledOpenFunctionResponses(
                                partsArray,
                                "missing_tool_result_for_function_call",
                            )

                            if (textContent.isNotEmpty()) {
                                appendParts(partsArray, buildPartsArray(textContent))
                            }

                            contentsArray.put(
                                JSONObject().apply {
                                    put("role", "user")
                                    put("parts", partsArray)
                                }
                            )
                        } else {
                            val partsArray = JSONArray()
                            appendCancelledOpenFunctionResponses(partsArray, "tool_result_without_structured_match")
                            if (textContent.isNotEmpty()) appendParts(partsArray, buildPartsArray(textContent))
                            if (partsArray.length() > 0) contentsArray.put(
                                JSONObject().apply {
                                    put("role", "user")
                                    put("parts", partsArray)
                                }
                            )
                        }
                    }

                    PromptTurnKind.SYSTEM -> Unit
                }
            } else {
                val geminiRole =
                    when (turn.kind) {
                        PromptTurnKind.ASSISTANT,
                        PromptTurnKind.TOOL_CALL -> "model"
                        else -> "user"
                    }
                contentsArray.put(
                    JSONObject().apply {
                        put("role", geminiRole)
                        put("parts", buildPartsArray(contentWithoutGeminiMeta))
                    }
                )
            }
        }

        flushOpenFunctionCallsAsCancelled("history_end")

        return Pair(Pair(contentsArray, systemInstruction), tokenCount)
    }

    // 工具函数：分块打印大型文本日志
    private fun logLargeString(tag: String, message: String, prefix: String = "") {
        // 设置单次日志输出的最大长度（Android日志上限约为4000字符）
        val maxLogSize = 3000

        // 如果消息长度超过限制，分块打印
        if (message.length > maxLogSize) {
            // 计算需要分多少块打印（向上取整，否则长度正好整除时会多输出一个空块）
            val chunkCount = (message.length + maxLogSize - 1) / maxLogSize

            for (i in 0 until chunkCount) {
                val start = i * maxLogSize
                val end = minOf((i + 1) * maxLogSize, message.length)
                val chunkMessage = message.substring(start, end)

                // 打印带有编号的日志
                AppLogger.d(tag, "$prefix Part ${i+1}/$chunkCount: $chunkMessage")
            }
        } else {
            // 消息长度在限制之内，直接打印
            AppLogger.d(tag, "$prefix$message")
        }
    }

    private fun logFinalOutput(content: CharSequence, prefix: String = "Gemini final output: ") {
        val finalOutput = content.toString()
        if (finalOutput.isBlank()) {
            AppLogger.d(TAG, "${prefix.trimEnd()}[empty]")
            return
        }
        logLargeString(TAG, finalOutput, prefix)
    }

     protected open fun getOutputImagesDir(): File {
         val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
         return File(downloadsDir, "Operit/output images")
     }

     private fun fileExtensionForImageMime(mimeType: String): String {
         return when (mimeType.lowercase().substringBefore(';')) {
             "image/png" -> "png"
             "image/jpeg", "image/jpg" -> "jpg"
             "image/webp" -> "webp"
             "image/gif" -> "gif"
             else -> "png"
         }
     }

     protected open fun formatOutputImageUri(file: File): String = Uri.fromFile(file).toString()

     protected open fun writeOutputImage(bytes: ByteArray, mimeType: String, prefix: String): File? {
         return try {
             val dir = getOutputImagesDir()
             if (!dir.exists() && !dir.mkdirs()) {
                 throw IOException("Could not create Gemini output image directory")
             }
             val ext = fileExtensionForImageMime(mimeType)
             val uniqueId = UUID.randomUUID().toString().replace("-", "")
             val fileName = "${prefix}_${System.currentTimeMillis()}_$uniqueId.$ext"
             val outFile = File(dir, fileName)
             FileOutputStream(outFile).use { it.write(bytes) }
             outFile
         } catch (e: Exception) {
             logError("保存输出图片失败", e)
             null
         }
     }

    // 日志辅助方法
    private fun logDebug(message: String) {
        if (DEBUG) {
            AppLogger.d(TAG, message)
        }
    }

    private fun logError(message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            AppLogger.e(TAG, message, throwable)
        } else {
            AppLogger.e(TAG, message)
        }
    }

    private fun buildGeminiErrorDetail(error: JSONObject, fallback: String): String {
        val message = error.optString("message", "").trim().ifEmpty { fallback }
        val status = error.opt("status")?.toString()?.trim().orEmpty()
        val code = error.opt("code")?.toString()?.trim().orEmpty()

        if (status.isEmpty() && code.isEmpty()) {
            return message
        }

        return buildString {
            append(message)
            append(" [")
            if (status.isNotEmpty()) {
                append("status=").append(status)
            }
            if (status.isNotEmpty() && code.isNotEmpty()) {
                append(", ")
            }
            if (code.isNotEmpty()) {
                append("code=").append(code)
            }
            append("]")
        }
    }

    private fun throwIfGeminiErrorPayload(context: Context, json: JSONObject) {
        val error = json.optJSONObject("error") ?: return
        val detail = buildGeminiErrorDetail(error, context.getString(R.string.gemini_unknown_error))
        val exceptionMessage = context.getString(R.string.gemini_error_response_failed, detail)

        logError("API返回错误: $detail")
        throw IOException(exceptionMessage)
    }

    private fun resolveRetryErrorText(context: Context, exception: Exception): String {
        return when (exception) {
            is SocketTimeoutException -> context.getString(R.string.provider_error_timeout)
            is UnknownHostException -> context.getString(R.string.provider_error_unknown_host)
            else -> exception.message?.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.provider_error_network_interrupted)
        }
    }

    private suspend fun handleRetryableError(
        context: Context,
        exception: Exception,
        retryCount: Int,
        maxRetries: Int,
        enableRetry: Boolean,
        onNonFatalError: suspend (String) -> Unit,
        buildRetryMessage: (String, Int) -> String
    ): Int {
        if (exception is UserCancellationException || exception is kotlinx.coroutines.CancellationException) {
            throw exception
        }
        if (exception is ReplayMediaException) {
            throw exception
        }
        if (isManuallyCancelled) {
            logError("请求被用户取消，停止重试。", exception)
            throw UserCancellationException(context.getString(R.string.gemini_error_request_cancelled), exception)
        }

        val errorText = resolveRetryErrorText(context, exception)

        if (!enableRetry) {
            throw IOException(errorText, exception)
        }

        val newRetryCount = retryCount + 1
        if (newRetryCount > maxRetries) {
            logError("$errorText 且达到最大重试次数($maxRetries)", exception)
            throw IOException(
                context.getString(R.string.gemini_error_connection_timeout, maxRetries, errorText),
                exception
            )
        }

        val retryDelayMs = LlmRetryPolicy.nextDelayMs(newRetryCount)
        AppLogger.w(TAG, "$errorText，将在 ${retryDelayMs}ms 后进行第 $newRetryCount 次重试...", exception)
        if (!shouldSuppressKeyPoolRateLimitNotice(apiKeyProvider, exception, TAG)) {
            onNonFatalError(buildRetryMessage(errorText, newRetryCount))
        }
        delay(retryDelayMs)
        return newRetryCount
    }

    /** 发送消息到Gemini API */
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
        val eventChannel = MutableSharedStream<TextStreamEvent>(replay = Int.MAX_VALUE)
        val responseStream = stream {
        isManuallyCancelled = false
        val requestId = System.currentTimeMillis().toString()
        // 重置输出token计数（保留输入历史缓存）
        tokenCacheManager.addOutputTokens(-tokenCacheManager.outputTokenCount)
        isInThinkingMode = false
        
        onTokensUpdated(
                tokenCacheManager.totalInputTokenCount,
                tokenCacheManager.cachedInputTokenCount,
                tokenCacheManager.outputTokenCount
        )

        AppLogger.d(TAG, "发送消息到Gemini API, 模型: $modelName")

        val maxRetries = LlmRetryPolicy.MAX_RETRY_ATTEMPTS
        var retryCount = 0
        var lastException: Exception? = null

        // 用于保存已接收到的内容，以便在重试时使用
        val receivedContent = StringBuilder()
        val requestSavepointId = "attempt_${UUID.randomUUID().toString().replace("-", "")}"

        suspend fun emitSavepoint(id: String) {
            eventChannel.emit(TextStreamEvent(TextStreamEventType.SAVEPOINT, id))
        }

        suspend fun emitRollback(id: String) {
            if (receivedContent.isNotEmpty()) {
                receivedContent.setLength(0)
            }
            eventChannel.emit(TextStreamEvent(TextStreamEventType.ROLLBACK, id))
        }

        // 捕获stream collector的引用
        val streamCollector = this

        // 状态更新函数 - 在Stream中我们使用emit来传递连接状态
        val emitConnectionStatus: (String) -> Unit = { status ->
            // 这里可以根据需要处理连接状态，例如记录日志
            logDebug("连接状态: $status")
        }

        emitConnectionStatus(context.getString(R.string.gemini_connecting))
        emitSavepoint(requestSavepointId)

        while (retryCount <= maxRetries) {
            // 在循环开始时检查是否已被取消
            if (isManuallyCancelled) {
                logError("请求被用户取消，停止重试。")
                throw UserCancellationException(context.getString(R.string.gemini_error_request_cancelled))
            }
            
            try {
                if (retryCount > 0) {
                    AppLogger.d(
                        TAG,
                        "【Gemini 重试】原子回滚后重新请求，本轮已撤回内容长度: ${receivedContent.length}"
                    )
                }

                val requestBody = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    createRequestBody(context, chatHistory, modelParameters, enableThinking, availableTools, preserveThinkInHistory)
                }
                onTokensUpdated(
                        tokenCacheManager.totalInputTokenCount,
                        tokenCacheManager.cachedInputTokenCount,
                        tokenCacheManager.outputTokenCount
                )
                val request = createRequest(context, requestBody, stream, requestId) // 根据stream参数决定使用流式还是非流式

                val call = client.newCall(request)
                activeCall = call

                emitConnectionStatus(context.getString(R.string.gemini_connecting))

                val startTime = System.currentTimeMillis()
                withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val response = call.execute()
                    activeResponse = response
                    try {
                        val duration = System.currentTimeMillis() - startTime
                        AppLogger.d(TAG, "收到初始响应, 耗时: ${duration}ms, 状态码: ${response.code}")

                        emitConnectionStatus(context.getString(R.string.gemini_connected_success))

                        if (!response.isSuccessful) {
                            val errorBody = response.body?.string() ?: context.getString(R.string.gemini_error_no_error_details)
                            logError("API请求失败: ${response.code}, $errorBody")
                            // 保留 4xx 状态，让统一策略在多密钥配置下尝试下一密钥。
                            if (response.code in 400..499) {
                                throw NonRetriableException(
                                    context.getString(R.string.gemini_error_api_request_failed, response.code, errorBody),
                                    statusCode = response.code
                                )
                            }
                            // 对于5xx等服务端错误，允许重试
                            throw IOException(context.getString(R.string.gemini_error_api_request_failed, response.code, errorBody))
                        }

                        // 根据stream参数处理响应
                        if (stream) {
                            // 处理流式响应
                            processStreamingResponse(context, response, streamCollector, requestId, onTokensUpdated, receivedContent, onUsageReported, retryCount + 1)
                        } else {
                            // 处理非流式响应并转换为Stream
                            processNonStreamingResponse(context, response, streamCollector, requestId, onTokensUpdated, receivedContent, onUsageReported, retryCount + 1)
                        }
                    } finally {
                        response.close()
                        AppLogger.d(TAG, "关闭响应连接")
                    }
                }

                // 清理活跃引用
                activeCall = null
                activeResponse = null
                logFinalOutput(receivedContent, "Gemini final output summary: ")
                return@stream
            } catch (e: Exception) {
                lastException = e
                emitRollback(requestSavepointId)
                retryCount = handleRetryableError(
                    context,
                    e,
                    retryCount,
                    maxRetries,
                    enableRetry,
                    onNonFatalError
                ) { errorText, retryNumber ->
                    context.getString(R.string.provider_error_retry_message, errorText, retryNumber)
                }
            }
        }

        logError("重试${maxRetries}次后仍然失败", lastException)
        throw IOException(
            context.getString(
                R.string.gemini_error_connection_timeout,
                maxRetries,
                lastException?.message ?: context.getString(R.string.provider_error_network_interrupted)
            ),
            lastException
        )
        }
        return responseStream.withEventChannel(eventChannel)
    }

    private fun putGeminiModelParameter(
        requestJson: JSONObject,
        generationConfig: JSONObject,
        parameter: ModelParameter<*>,
    ) {
        if (parameter.valueType == com.ai.assistance.operit.data.model.ParameterValueType.OBJECT) {
            val raw = parameter.currentValue.toString().trim()
            val parsed: Any? =
                try {
                    when {
                        raw.startsWith("{") -> JSONObject(raw)
                        raw.startsWith("[") -> JSONArray(raw)
                        else -> null
                    }
                } catch (e: Exception) {
                    logError("Gemini OBJECT参数解析失败: ${parameter.apiName}", e)
                    null
                }
            val target =
                if (parameter.category == ParameterCategory.OTHER) {
                    requestJson
                } else {
                    generationConfig
                }
            target.put(parameter.apiName, parsed ?: raw)
        } else {
            generationConfig.put(parameter.apiName, parameter.currentValue)
        }
    }

    /** 创建请求体 */
    private fun createRequestBody(
            context: Context,
            chatHistory: List<PromptTurn>,
            modelParameters: List<ModelParameter<*>>,
            enableThinking: Boolean,
            availableTools: List<ToolPrompt>? = null,
            preserveThinkInHistory: Boolean = false
    ): RequestBody {
        val json = JSONObject()

        // 添加工具定义
        val tools = JSONArray()
        var hasFunctionDeclarations = false
        
        // 添加 Function Calling 工具（如果启用且有可用工具）
        if (enableToolCall && availableTools != null && availableTools.isNotEmpty()) {
            val functionDeclarations = buildToolDefinitionsForGemini(availableTools)
            if (functionDeclarations.length() > 0) {
                hasFunctionDeclarations = true
                tools.put(JSONObject().apply {
                    put("function_declarations", functionDeclarations)
                })
                logDebug("已添加 ${functionDeclarations.length()} 个 Function Declarations")
            }
        }
        
        // 添加 Google Search grounding 工具（如果启用）
        if (enableGoogleSearch) {
            tools.put(JSONObject().apply {
                put("googleSearch", JSONObject())
            })
            logDebug("已启用 Google Search Grounding")
        }
        
        // 将 tools 添加到请求中，并保存用于token计算
        val toolsJson = if (tools.length() > 0) {
            json.put("tools", tools)
            tools.toString()
        } else {
            null
        }
        if (hasFunctionDeclarations && enableGoogleSearch) {
            json.put(
                "toolConfig",
                JSONObject().apply {
                    put("includeServerSideToolInvocations", true)
                },
            )
        }

        val (contentsResult, _) = buildContentsAndCountTokens(chatHistory, toolsJson, preserveThinkInHistory)
        val (contentsArray, systemInstruction) = contentsResult

        if (systemInstruction != null) {
            json.put("systemInstruction", systemInstruction)
        }
        json.put("contents", contentsArray)

        // 添加生成配置
        val generationConfig = JSONObject()

        // 功能模型可为每次请求提供显式 budget/level；未提供时仍保留聊天开关语义。
        val thinkingConfig =
            buildGeminiThinkingConfig(
                enableThinking = enableThinking,
                modelParameters = modelParameters,
                modelName = modelName,
            )
        if (thinkingConfig != null) {
            generationConfig.put("thinkingConfig", thinkingConfig)
            logDebug("已应用Gemini思考配置: $thinkingConfig")
        }
        // 添加模型参数
        for (param in modelParameters) {
            if (param.isEnabled) {
                when (param.apiName) {
                    "temperature" ->
                            if (!isGemini3Model(modelName)) {
                                generationConfig.put(
                                        "temperature",
                                        (param.currentValue as Number).toFloat()
                                )
                            }
                    "top_p", "topP" ->
                            if (!isGemini3Model(modelName)) {
                                generationConfig.put(
                                    "topP",
                                    (param.currentValue as Number).toFloat(),
                                )
                            }
                    "top_k", "topK" ->
                            if (!isGemini3Model(modelName)) {
                                generationConfig.put(
                                    "topK",
                                    (param.currentValue as Number).toInt(),
                                )
                            }
                    "max_tokens" ->
                            generationConfig.put(
                                    "maxOutputTokens",
                                    (param.currentValue as Number).toInt()
                            )
                    "thinking_budget", "thinkingBudget", "thinking_level", "thinkingLevel" -> Unit
                    "thinkingConfig" -> {
                        if (!isGemini3Model(modelName)) {
                            putGeminiModelParameter(json, generationConfig, param)
                        }
                    }
                    "candidate_count", "candidateCount" -> {
                        if (!isGemini3Model(modelName)) {
                            generationConfig.put("candidateCount", param.currentValue)
                        }
                    }
                    else -> {
                        putGeminiModelParameter(json, generationConfig, param)
                    }
                }
            }
        }

        if (antigravity != null) {
            val selectedQuality = kotlinx.coroutines.runBlocking {
                com.ai.assistance.operit.data.preferences.ApiPreferences.getInstance(context)
                    .thinkingQualityLevelFlow.first()
            }
            generationConfig.put("thinkingConfig", JSONObject().put("thinkingLevel",
                antigravityThinkingEffort(modelName, selectedQuality, enableThinking, modelParameters)))
        }
        json.put("generationConfig", generationConfig)

        val jsonString = json.toString()
        if (AppLogger.logRequestBodies) {
            RequestBodyLog.write(TAG, context.getString(R.string.gemini_request_body_json), json)
        }

        return jsonString.toByteArray(Charsets.UTF_8).toRequestBody(JSON)
    }

    /** 创建HTTP请求 */
    private val openCodeGoHeaders = OpenCodeGoHeaders()

    private suspend fun createRequest(
            context: Context,
            requestBody: RequestBody,
            isStreaming: Boolean,
            requestId: String
    ): Request {
        antigravity?.let { return it.request(requestBody, isStreaming) }
        val currentApiKey = apiKeyProvider.getApiKey()
        val requestUrl =
            buildGeminiGenerateContentUrl(
                apiEndpoint = apiEndpoint,
                modelName = modelName,
                streaming = isStreaming,
                apiKey = currentApiKey,
            )
        val portSuffix =
            if (
                requestUrl.port == 80 && requestUrl.scheme == "http" ||
                    requestUrl.port == 443 && requestUrl.scheme == "https"
            ) {
                ""
            } else {
                ":${requestUrl.port}"
            }
        AppLogger.d(
            TAG,
            "请求URL: ${requestUrl.scheme}://${requestUrl.host}$portSuffix${requestUrl.encodedPath}",
        )

        // 创建Request Builder
        val builder = Request.Builder()

        // 添加自定义请求头
        customHeaders.forEach { (key, value) ->
            builder.addHeader(key, value)
        }

        builder.url(requestUrl)
        openCodeGoHeaders.applyTo(builder)
        val request = builder
                .post(requestBody)
                .addHeader("Content-Type", "application/json")
                .build()

        logLargeString(TAG, context.getString(R.string.gemini_request_headers, HttpLogSanitizer.headersForLog(request.headers)))
        return request
    }

    /** 处理API流式响应 */
    private suspend fun processStreamingResponse(
            context: Context,
            response: Response,
            streamCollector: StreamCollector<String>,
            requestId: String,
            onTokensUpdated: suspend (input: Int, cachedInput: Int, output: Int) -> Unit,
            receivedContent: StringBuilder,
            onUsageReported: (suspend (com.ai.assistance.operit.data.stats.ProviderUsageSnapshot, attempt: Int) -> Unit)? = null,
            attemptNumber: Int = 1
    ) {
        AppLogger.d(TAG, "开始处理响应流")
        val responseBody = response.body ?: throw IOException(context.getString(R.string.gemini_response_empty))
        val reader = responseBody.charStream().buffered()

        // 注意：不再使用fullContent累积所有内容
        var lineCount = 0
        var dataCount = 0
        var jsonCount = 0
        var contentCount = 0
        val replayContentAccumulator = GeminiReplayContentAccumulator()

        // 恢复JSON累积逻辑，用于处理分段JSON
        val completeJsonBuilder = StringBuilder()
        var isCollectingJson = false
        var jsonDepth = 0
        var jsonStartSymbol = ' ' // 记录JSON是以 { 还是 [ 开始的

        suspend fun extractStreamingContent(json: JSONObject): String {
            return extractContentFromJson(
                context = context,
                json = antigravity?.unwrap(json) ?: json,
                requestId = requestId,
                onTokensUpdated = onTokensUpdated,
                onUsageReported = onUsageReported,
                attemptNumber = attemptNumber,
                includeReplayMetadata = false,
                replayContentAccumulator = replayContentAccumulator,
            )
        }

        try {
            reader.useLines { lines ->
                lines.forEach { line ->
                    lineCount++
                    // 检查是否已取消
                    if (activeCall?.isCanceled() == true) {
                        return@forEach
                    }

                    // 处理SSE数据
                    if (line.startsWith("data: ")) {
                        val data = line.substring(6).trim()
                        dataCount++

                        // 跳过结束标记
                        if (data == "[DONE]") {
                            logDebug("收到流结束标记 [DONE]")
                            return@forEach
                        }

                        try {
                            // 立即解析每个SSE数据行的JSON
                            val json = JSONObject(data)
                            jsonCount++

                            val content = extractStreamingContent(json)
                            if (content.isNotEmpty()) {
                                contentCount++
                                logDebug("提取SSE内容，长度: ${content.length}")
                                receivedContent.append(content)

                                // 只发送新增的内容
                                streamCollector.emit(content)
                            }
                        } catch (e: IOException) {
                            throw e
                        } catch (e: Exception) {
                            logError("解析SSE响应数据失败: ${e.message}", e)
                        }
                    } else if (line.trim().isNotEmpty()) {
                        // 处理可能分段的JSON数据
                        val trimmedLine = line.trim()

                        // 检查是否开始收集JSON
                        if (!isCollectingJson &&
                                        (trimmedLine.startsWith("{") || trimmedLine.startsWith("["))
                        ) {
                            isCollectingJson = true
                            jsonDepth = 0
                            completeJsonBuilder.clear()
                            jsonStartSymbol = trimmedLine[0]
                            logDebug("开始收集JSON，起始符号: $jsonStartSymbol")
                        }

                        if (isCollectingJson) {
                            completeJsonBuilder.append(trimmedLine)

                            // 更新JSON深度
                            for (char in trimmedLine) {
                                if (char == '{' || char == '[') jsonDepth++
                                if (char == '}' || char == ']') jsonDepth--
                            }

                            // 尝试作为完整JSON解析
                            val possibleComplete = completeJsonBuilder.toString()
                            try {
                                if (jsonDepth == 0) {
                                    logDebug("尝试解析完整JSON: ${possibleComplete.take(50)}...")
                                    val jsonContent =
                                            if (jsonStartSymbol == '[') {
                                                JSONArray(possibleComplete)
                                            } else {
                                                JSONObject(possibleComplete)
                                            }

                                    // 解析成功，处理内容
                                    logDebug("成功解析完整JSON，长度: ${possibleComplete.length}")

                                    when (jsonContent) {
                                        is JSONArray -> {
                                            // 处理JSON数组
                                            for (i in 0 until jsonContent.length()) {
                                                val jsonObject = jsonContent.optJSONObject(i)
                                                if (jsonObject != null) {
                                                    jsonCount++
                                                    val content = extractStreamingContent(jsonObject)
                                                    if (content.isNotEmpty()) {
                                                        contentCount++
                                                        logDebug(
                                                                "从JSON数组[$i]提取内容，长度: ${content.length}"
                                                        )
                                                        receivedContent.append(content)

                                                        // 只发送这个单独对象产生的内容
                                                        streamCollector.emit(content)
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    // 解析成功后重置收集器
                                    isCollectingJson = false
                                    completeJsonBuilder.clear()
                                }
                            } catch (e: IOException) {
                                throw e
                            } catch (e: Exception) {
                                // JSON尚未完整，继续收集
                                if (jsonDepth > 0) {
                                    // 仍在收集，这是预期的
                                    logDebug("继续收集JSON，当前深度: $jsonDepth")
                                } else {
                                    // 深度为0但解析失败，可能是无效JSON
                                    logError("JSON解析失败: ${e.message}", e)
                                    isCollectingJson = false
                                    completeJsonBuilder.clear()
                                }
                            }
                        }
                    }
                }
            }

            AppLogger.d(TAG, "响应处理完成: 共${lineCount}行, ${jsonCount}个JSON块, 提取${contentCount}个内容块")

            // 检查是否还有未解析完的JSON
            if (isCollectingJson && completeJsonBuilder.isNotEmpty()) {
                try {
                    val finalJson = completeJsonBuilder.toString()
                    AppLogger.d(TAG, "处理最终收集的JSON，长度: ${finalJson.length}")

                    val jsonContent =
                            if (jsonStartSymbol == '[') {
                                JSONArray(finalJson)
                            } else {
                                JSONObject(finalJson)
                            }
                    // 处理内容
                    when (jsonContent) {
                        is JSONArray -> {
                            for (i in 0 until jsonContent.length()) {
                                val jsonObject = jsonContent.optJSONObject(i) ?: continue
                                jsonCount++
                                val content = extractStreamingContent(jsonObject)
                                if (content.isNotEmpty()) {
                                    contentCount++
                                    logDebug("从最终JSON数组[$i]提取内容，长度: ${content.length}")
                                    receivedContent.append(content)
                                    streamCollector.emit(content)
                                }
                            }
                        }
                        is JSONObject -> {
                            jsonCount++
                            val content = extractStreamingContent(jsonContent)
                            if (content.isNotEmpty()) {
                                contentCount++
                                logDebug("从最终JSON对象提取内容，长度: ${content.length}")
                                receivedContent.append(content)
                                streamCollector.emit(content)
                            }
                        }
                    }
                } catch (e: IOException) {
                    throw e
                } catch (e: Exception) {
                    logError("解析最终收集的JSON失败: ${e.message}", e)
                }
            }

            // 确保思考模式正确结束
            if (isInThinkingMode) {
                logDebug("流结束时仍在思考模式，添加结束标签")
                val thinkingEndTag = "</think>"
                receivedContent.append(thinkingEndTag)
                streamCollector.emit(thinkingEndTag)
                isInThinkingMode = false
            }
            
            // 确保至少发送一次内容
            if (contentCount == 0) {
                logDebug("未检测到内容，发送空格")
                receivedContent.append(" ")
                streamCollector.emit(" ")
            }

            replayContentAccumulator.buildContentOrNull()?.let { replayContent ->
                val replayTag =
                    ChatMarkupRegex.geminiContentMetaTag(encodeGeminiContent(replayContent))
                val replayChunk =
                    if (
                        receivedContent.isNotEmpty() &&
                            receivedContent[receivedContent.length - 1] != '\n'
                    ) {
                        "\n$replayTag"
                    } else {
                        replayTag
                    }
                receivedContent.append(replayChunk)
                streamCollector.emit(replayChunk)
            }
        } catch (e: Exception) {
            logError("处理响应时发生异常: ${e.message}", e)
            throw e
        } finally {
            activeCall = null
        }
    }

    /** 处理API非流式响应 */
    private suspend fun processNonStreamingResponse(
            context: Context,
            response: Response,
            streamCollector: StreamCollector<String>,
            requestId: String,
            onTokensUpdated: suspend (input: Int, cachedInput: Int, output: Int) -> Unit,
            receivedContent: StringBuilder,
            onUsageReported: (suspend (com.ai.assistance.operit.data.stats.ProviderUsageSnapshot, attempt: Int) -> Unit)? = null,
            attemptNumber: Int = 1
    ) {
        AppLogger.d(TAG, "开始处理非流式响应")
        val responseBody = response.body ?: throw IOException(context.getString(R.string.gemini_response_empty))
        
        try {
            val responseText = responseBody.string()
            logDebug("收到完整响应，长度: ${responseText.length}")
            
            // 解析JSON响应
            val envelope = JSONObject(responseText)
            val json = antigravity?.unwrap(envelope) ?: envelope
            
            // 提取内容
            val content = extractContentFromJson(context, json, requestId, onTokensUpdated, onUsageReported, attemptNumber)
            
            if (content.isNotEmpty()) {
                receivedContent.append(content)
                
                // 直接发送整个内容块，下游会自己处理
                streamCollector.emit(content)
                
                logDebug("非流式响应处理完成，总长度: ${content.length}")
            } else {
                logDebug("未检测到内容，发送空格")
                streamCollector.emit(" ")
            }
            
            // 确保思考模式正确结束
            if (isInThinkingMode) {
                logDebug("非流式响应结束时仍在思考模式，添加结束标签")
                streamCollector.emit("</think>")
                isInThinkingMode = false
            }
        } catch (e: Exception) {
            logError("处理非流式响应时发生异常: ${e.message}", e)
            throw e
        } finally {
            activeCall = null
        }
    }

    /** 从Gemini响应JSON中提取内容 */
    internal suspend fun extractContentFromJson(
        context: Context,
        json: JSONObject,
        requestId: String,
        onTokensUpdated: suspend (input: Int, cachedInput: Int, output: Int) -> Unit,
        onUsageReported: (suspend (com.ai.assistance.operit.data.stats.ProviderUsageSnapshot, attempt: Int) -> Unit)? = null,
        attemptNumber: Int = 1,
        includeReplayMetadata: Boolean = true,
        replayContentAccumulator: GeminiReplayContentAccumulator? = null,
    ): String {
        val contentBuilder = StringBuilder()
        val searchSourcesBuilder = StringBuilder()

        try {
            throwIfGeminiErrorPayload(context, json)

            // Gemini may return billable usage without any candidates (for example, a response
            // blocked by safety policy). Record server usage before candidate-dependent content
            // parsing so the completed request does not fall back to unknown token counts.
            val usageMetadata = json.optJSONObject("usageMetadata")
            if (usageMetadata != null) {
                // Explicit all-zero usageMetadata is still observed usage: test field presence,
                // not whether values are positive. Long parsing is saturated at the legacy Int UI.
                val hasServerUsage =
                    usageMetadata.has("promptTokenCount") ||
                        usageMetadata.has("cachedContentTokenCount") ||
                        usageMetadata.has("candidatesTokenCount")
                if (hasServerUsage) {
                    val promptTokenCount = usageMetadata.optLong("promptTokenCount", 0).saturateToInt()
                    val cachedContentTokenCount =
                        usageMetadata.optLong("cachedContentTokenCount", 0).saturateToInt()
                    val candidatesTokenCount = usageMetadata.optLong("candidatesTokenCount", 0).saturateToInt()
                    val actualInputTokens = (promptTokenCount - cachedContentTokenCount).coerceAtLeast(0)
                    tokenCacheManager.updateActualTokens(actualInputTokens, cachedContentTokenCount)
                    tokenCacheManager.setOutputTokens(candidatesTokenCount)

                    logDebug("API实际Token使用: 输入=$actualInputTokens, 缓存=$cachedContentTokenCount, 输出=$candidatesTokenCount")
                    onTokensUpdated(
                        tokenCacheManager.totalInputTokenCount,
                        tokenCacheManager.cachedInputTokenCount,
                        tokenCacheManager.outputTokenCount
                    )
                    onUsageReported?.let { callback ->
                        ProviderUsageNormalizer.gemini(usageMetadata)?.let { callback(it, attemptNumber) }
                    }
                }
            }

            // 提取候选项
            val candidates = json.optJSONArray("candidates")
            if (candidates == null || candidates.length() == 0) {
                logDebug("未找到候选项")
                return ""
            }

            // 处理第一个candidate
            val candidate = candidates.getJSONObject(0)
            
            // 提取 Google Search grounding metadata（搜索来源信息）
            if (enableGoogleSearch) {
                val groundingMetadata = candidate.optJSONObject("groundingMetadata")
                if (groundingMetadata != null) {
                    // 提取搜索查询
                    val webSearchQueries = groundingMetadata.optJSONArray("webSearchQueries")
                    if (webSearchQueries != null && webSearchQueries.length() > 0) {
                        searchSourcesBuilder.append("\n<search>\n\n")
                        searchSourcesBuilder.append(context.getString(R.string.gemini_search_sources_title))

                        for (i in 0 until webSearchQueries.length()) {
                            val query = webSearchQueries.optString(i)
                            searchSourcesBuilder.append(context.getString(R.string.gemini_search_query, query))
                            logDebug("搜索查询 [$i]: $query")
                        }
                        
                        // 提取搜索结果的URL来源
                        val groundingSupports = groundingMetadata.optJSONArray("groundingSupports")
                        if (groundingSupports != null && groundingSupports.length() > 0) {
                            searchSourcesBuilder.append(context.getString(R.string.gemini_reference_sources_title))
                            
                            for (i in 0 until groundingSupports.length()) {
                                val support = groundingSupports.getJSONObject(i)
                                val segment = support.optJSONObject("segment")
                                val groundingChunkIndices = support.optJSONArray("groundingChunkIndices")
                                
                                // 如果有chunk indices，提取对应的URL
                                if (groundingChunkIndices != null) {
                                    for (j in 0 until groundingChunkIndices.length()) {
                                        val chunkIndex = groundingChunkIndices.getInt(j)
                                        val retrievalMetadata = groundingMetadata.optJSONObject("retrievalMetadata")
                                        if (retrievalMetadata != null) {
                                            val webDynamicRetrievalScore = retrievalMetadata.optDouble("webDynamicRetrievalScore", -1.0)
                                            if (webDynamicRetrievalScore > 0) {
                                                logDebug("搜索动态检索分数: $webDynamicRetrievalScore")
                                            }
                                        }
                                    }
                                }
                            }
                            
                            // 提取 grounding chunks（包含URL）
                            val groundingChunks = groundingMetadata.optJSONArray("groundingChunks")
                            if (groundingChunks != null && groundingChunks.length() > 0) {
                                for (i in 0 until groundingChunks.length()) {
                                    val chunk = groundingChunks.getJSONObject(i)
                                    val web = chunk.optJSONObject("web")
                                    if (web != null) {
                                        val uri = web.optString("uri", "")
                                        val title = web.optString("title", "")
                                        if (uri.isNotEmpty()) {
                                            if (title.isNotEmpty()) {
                                                searchSourcesBuilder.append("${i + 1}. [${title}](${uri})\n")
                                            } else {
                                                searchSourcesBuilder.append("${i + 1}. <${uri}>\n")
                                            }
                                            logDebug("搜索来源 [$i]: $title - $uri")
                                        }
                                    }
                                }
                            }
                        }
                        
                        searchSourcesBuilder.append("\n</search>\n\n")
                    }
                }
            }

            // 检查finish_reason
            val finishReason = candidate.optString("finishReason", "")
            if (finishReason.isNotEmpty() && finishReason != "STOP") {
                logDebug("收到完成原因: $finishReason")
            }

            // 提取content对象
            val content = candidate.optJSONObject("content")
            if (content == null) {
                logDebug("未找到content对象")
                return ""
            }

            // 提取parts数组
            val parts = content.optJSONArray("parts")
            if (parts == null || parts.length() == 0) {
                logDebug("未找到parts数组或为空")
                return ""
            }
            val replayInlineDataFiles = mutableMapOf<Int, GeminiReplayInlineDataFile>()

            // 遍历parts，提取text内容和functionCall
            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                val text = part.optString("text", "")
                val isThought = part.optBoolean("thought", false)
                val functionCall = part.optJSONObject("functionCall")

                 val inlineData = part.optJSONObject("inline_data") ?: part.optJSONObject("inlineData")
                 if (inlineData != null) {
                     val mimeType = inlineData.optString("mime_type", inlineData.optString("mimeType", ""))
                     val b64 = inlineData.optString("data", "")
                     if (mimeType.startsWith("image/", ignoreCase = true) && b64.isNotEmpty()) {
                         if (isInThinkingMode) {
                             contentBuilder.append("</think>")
                             isInThinkingMode = false
                         }
                         val bytes = try {
                             Base64.getMimeDecoder().decode(b64)
                         } catch (_: Exception) {
                             null
                         }
                         if (bytes != null && bytes.isNotEmpty()) {
                             val outputFile = writeOutputImage(bytes, mimeType, "gemini_image_$i")
                             if (outputFile != null) {
                                 val uri = formatOutputImageUri(outputFile)
                                 contentBuilder.append("\n![gemini_image_$i]($uri)\n")
                                 replayInlineDataFiles[i] =
                                     GeminiReplayInlineDataFile(
                                         fileName = outputFile.name,
                                         sha256 = geminiReplaySha256(bytes),
                                     )
                             }
                         }
                         continue
                     }
                 }

                // 处理 functionCall（流式转换为XML）
                if (functionCall != null && enableToolCall) {
                    val toolName = functionCall.optProviderToolName() ?: ""
                    if (toolName.isNotEmpty()) {
                        // 工具调用必须在思考模式之外，如果当前在思考中，先关闭
                        if (isInThinkingMode) {
                            contentBuilder.append("</think>")
                            isInThinkingMode = false
                            logDebug("检测到工具调用，提前结束思考模式")
                        }
                        
                        // 输出工具开始标签
                        val toolTagName = ChatMarkupRegex.generateRandomToolTagName()
                        contentBuilder.append("\n<$toolTagName name=\"$toolName\">")
                        
                        // 使用 StreamingJsonXmlConverter 流式转换参数
                        val args = functionCall.optJSONObject("args")
                        if (args != null) {
                            val converter = StreamingJsonXmlConverter()
                            val argsJson = args.toString()
                            val events = converter.feed(argsJson)
                            events.forEach { event ->
                                when (event) {
                                    is StreamingJsonXmlConverter.Event.Tag -> contentBuilder.append(event.text)
                                    is StreamingJsonXmlConverter.Event.Content -> contentBuilder.append(event.text)
                                }
                            }
                            // 刷新剩余内容
                            val flushEvents = converter.flush()
                            flushEvents.forEach { event ->
                                when (event) {
                                    is StreamingJsonXmlConverter.Event.Tag -> contentBuilder.append(event.text)
                                    is StreamingJsonXmlConverter.Event.Content -> contentBuilder.append(event.text)
                                }
                            }
                        }
                        
                        // 输出工具结束标签
                        contentBuilder.append("\n</$toolTagName>\n")
                        logDebug("Gemini FunctionCall流式转XML: $toolName")
                    }
                }

                if (text.isNotEmpty()) {
                    // 处理思考模式状态切换
                    if (isThought && !isInThinkingMode) {
                        // 开始思考模式
                        contentBuilder.append(ChatUtils.PROVIDER_REASONING_OPEN_TAG)
                        isInThinkingMode = true
                        logDebug("开始思考模式")
                    } else if (!isThought && isInThinkingMode) {
                        // 结束思考模式
                        contentBuilder.append("</think>")
                        isInThinkingMode = false
                        logDebug("结束思考模式")
                    }
                    
                    // Provider-owned thought text is data inside our <think> envelope. Escape
                    // markup at this boundary so it cannot close the envelope and inject a tool.
                    contentBuilder.append(
                        if (isThought) ChatUtils.escapeProviderReasoningMarkup(text) else text
                    )
                    
                    if (isThought) {
                        logDebug("提取思考内容，长度=${text.length}")
                    } else {
                        logDebug("提取文本，长度=${text.length}")
                    }

                    // 估算token
                    val tokens = ChatUtils.estimateTokenCount(text)
                    tokenCacheManager.addOutputTokens(tokens)
                    onTokensUpdated(
                            tokenCacheManager.totalInputTokenCount,
                            tokenCacheManager.cachedInputTokenCount,
                            tokenCacheManager.outputTokenCount
                    )
                }
            }

            val effectiveReplayAccumulator =
                replayContentAccumulator
                    ?: if (includeReplayMetadata) GeminiReplayContentAccumulator() else null
            effectiveReplayAccumulator?.appendContent(content, replayInlineDataFiles)
            if (includeReplayMetadata) {
                effectiveReplayAccumulator
                    ?.buildContentOrNull()
                    ?.let { replayContent ->
                        appendGeminiContentMeta(contentBuilder, replayContent)
                    }
            }

            // 将搜索来源拼接到内容最前面
            val finalContent = if (searchSourcesBuilder.isNotEmpty()) {
                searchSourcesBuilder.toString() + contentBuilder.toString()
            } else {
                contentBuilder.toString()
            }
            
            return finalContent
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            logError("提取内容时发生错误: ${e.message}", e)
            return ""
        }
    }

    /** 获取模型列表 */
    override suspend fun getModelsList(context: Context): Result<List<ModelOption>> {
        if (antigravity != null) return try { Result.success(antigravity.models()) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { Result.failure(error) }
        return ModelListFetcher.getModelsList(
            context = context,
            apiKey = apiKeyProvider.getApiKey(),
            apiEndpoint = apiEndpoint,
            apiProviderType = providerType,
            customHeaders = customHeaders,
        )
    }

    override suspend fun testConnection(
        context: Context,
        onUsageReported: (suspend (com.ai.assistance.operit.data.stats.ProviderUsageSnapshot, attempt: Int) -> Unit)?
    ): Result<String> {
        return try {
            // 通过发送一条短消息来测试完整的连接、认证和API端点。
            // 这比getModelsList更可靠，因为它直接命中了聊天API。
            // 提供一个通用的系统提示，以防止某些需要它的模型出现错误。
            val testHistory = listOf("system" to "You are a helpful assistant.").toPromptTurns()
            val stream = sendMessage(
                context,
                testHistory + PromptTurn(kind = PromptTurnKind.USER, content = "Hi"),
                emptyList(),
                false,
                false,
                null,
                onTokensUpdated = { _, _, _ -> },
                onUsageReported = onUsageReported,
                onNonFatalError = {},
                enableRetry = false
            )

            // 消耗流以确保连接有效。
            // 对 "Hi" 的响应应该很短，所以这会很快完成。
            var hasReceivedData = false
            stream.collect {
                hasReceivedData = true
            }

            // 某些情况下，即使连接成功，也可能不会返回任何数据（例如，如果模型只处理了提示而没有生成响应）。
            // 因此，只要不抛出异常，我们就认为连接成功。
            Result.success(context.getString(R.string.gemini_connection_success))
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 取消必须原样传播，不能变成 Result.failure
            throw e
        } catch (e: Exception) {
            logError("连接测试失败", e)
            Result.failure(IOException(context.getString(R.string.gemini_connection_test_failed, e.message ?: ""), e))
        }
    }
}

/** 旧 UI 计数边界（P2-1）：Long 饱和为 Int，绝不回绕为负。 */
private fun Long.saturateToInt(): Int = coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

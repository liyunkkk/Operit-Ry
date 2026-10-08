package com.ai.assistance.operit.api.chat.llmprovider

import android.content.Context
import android.net.Uri
import android.os.Environment
import com.ai.assistance.operit.R
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import com.ai.assistance.operit.data.model.ApiProviderType
import com.ai.assistance.operit.data.model.ModelOption
import com.ai.assistance.operit.data.model.ModelParameter
import com.ai.assistance.operit.data.model.ToolPrompt
import com.ai.assistance.operit.data.preferences.ApiPreferences
import com.ai.assistance.operit.api.chat.llmprovider.EndpointCompleter
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.util.ChatMarkupRegex
import com.ai.assistance.operit.util.ChatUtils
import com.ai.assistance.operit.util.HttpLogSanitizer
import com.ai.assistance.operit.util.LocaleUtils
import com.ai.assistance.operit.util.StreamingJsonXmlConverter
import com.ai.assistance.operit.util.TokenCacheManager
import com.ai.assistance.operit.util.exceptions.UserCancellationException
import com.ai.assistance.operit.util.stream.MutableSharedStream
import com.ai.assistance.operit.util.stream.SharedStream
import com.ai.assistance.operit.util.stream.Stream
import com.ai.assistance.operit.util.stream.StreamCollector
import com.ai.assistance.operit.util.stream.StreamLogger
import com.ai.assistance.operit.util.stream.TextStreamEvent
import com.ai.assistance.operit.util.stream.TextStreamEventType
import com.ai.assistance.operit.util.stream.withEventChannel
import com.ai.assistance.operit.util.stream.stream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import com.ai.assistance.operit.api.chat.llmprovider.MediaLinkParser

/**
 * 流式响应的应用层停滞检测参数。
 *
 * OkHttp 的 readTimeout 计的是“距上次收到任意字节”，SSE 空行或 `: ping` 心跳都能一直续命，
 * 因此“连接活着但不再产生数据”只能由应用层自己判定。
 */
private const val STREAM_STALL_CHECK_INTERVAL_MS = 1_000L
private const val STREAM_STALL_TIMEOUT_MS = 120_000L

/** 连续停滞允许的重试次数：停滞每次都要等满超时，不能像普通网络错误那样重试 5 次。 */
private const val MAX_STREAM_STALL_RETRIES = 2

/** 首包单独放宽：网关排队或超长上下文的首字延迟可能明显超过常规块间隔。 */
private const val STREAM_FIRST_CHUNK_TIMEOUT_MS = 300_000L

/** 单调时钟（毫秒）。用 nanoTime 而不是 SystemClock，后者在 JVM 单测里是未实现的桩。 */
private fun streamNowMs(): Long = System.nanoTime() / 1_000_000L

/**
 * 流式读取进度：只有 `data:` 行算有效进度，空行和 `: ping` 注释心跳都不算。
 *
 * 看门狗与读取循环在不同线程上读写，因此用原子量传递。
 */
private class StreamProgress {
    val lastProgressAt = AtomicLong(streamNowMs())
    val stallTimeoutMs = AtomicLong(STREAM_FIRST_CHUNK_TIMEOUT_MS)
    val nonDataLineCount = AtomicLong(0)
    val nonDataLineCountAtLastData = AtomicLong(0)

    /** 只在看门狗线程读写，用于避免反复打印同一次停滞。 */
    var stallReported: Boolean = false
}

/**
 * 看门狗调度器：用独立守护线程而不是协程。
 *
 * 挂起的子协程会让读取协程的续体改由线程池线程恢复，而 JVM 单测只在测试线程上静态 mock 了
 * AppLogger，落到别的线程就会执行真实的 android.util.Log。独立线程既不受协程调度影响，也不需要
 * 额外的结构化并发收尾。
 */
private val streamStallWatchdogExecutor: ScheduledExecutorService by lazy {
    Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "OperitStreamStallWatchdog").apply { isDaemon = true }
    }
}

internal fun JSONObject.applyChatCompletionsStreamUsageOption(
    stream: Boolean,
    providerType: ApiProviderType,
    useResponsesApi: Boolean,
) {
    val supportsIncludeUsage =
        providerType == ApiProviderType.OPENAI ||
            providerType == ApiProviderType.GROK_ACCOUNT ||
            providerType == ApiProviderType.DEEPSEEK ||
            providerType == ApiProviderType.MOONSHOT
    if (stream && !useResponsesApi && supportsIncludeUsage) {
        put("stream_options", JSONObject().put("include_usage", true))
    }
}

internal fun supportsAutomaticOpenAiChatReasoning(providerType: ApiProviderType): Boolean =
    providerType == ApiProviderType.OPENAI ||
        providerType == ApiProviderType.OPENAI_GENERIC ||
        // The Zen free tier lists each model's effort levels in the catalog, and the gateway accepts
        // reasoning_effort for its OpenAI-compatible models. A model that declares no effort sends
        // none, so this only adds the control for models that take one.
        providerType == ApiProviderType.OPENCODE_ZEN_FREE

/**
 * OpenAI API格式的实现，支持标准OpenAI接口和兼容此格式的其他提供商
 *
 * ## enableToolCall 参数说明
 *
 * `enableToolCall` 用于启用/禁用 OpenAI Tool Call API 原生格式。
 *
 * ### 工作原理
 *
 * 当 `enableToolCall = true` 时，本Provider会执行双向格式转换：
 *
 * 1. **发送请求前**：将内部XML格式的工具调用转换为OpenAI Tool Call格式
 *    - `<tool name="xxx"><param name="yyy">value</param></tool>`
 *    - → `{"tool_calls": [{"function": {"name": "xxx", "arguments": "{\"yyy\": \"value\"}"}}]}`
 *
 * 2. **接收响应后**：将API返回的Tool Call格式转换回XML格式
 *    - API返回的tool_calls对象 → XML格式
 *    - 保持上层代码对XML格式的兼容性
 *
 * ### 历史记录处理
 *
 * - **Assistant消息**：XML工具调用 → OpenAI `tool_calls` 字段
 * - **User消息**：XML `tool_result` → OpenAI `role: "tool"` 消息
 * - **tool_call_id追踪**：自动生成和匹配ID，确保工具调用与结果正确关联
 *
 * ### 适用场景
 *
 * - 使用支持原生Tool Call API的模型（GPT-4、Claude、Qwen等）
 * - 需要更结构化的工具调用处理
 * - 希望利用模型的自动工具选择功能
 *
 * ### 注意事项
 *
 * - 默认值为 `false`，需要显式启用
 * - 启用后会自动添加 `tools` 和 `tool_choice` 到请求体
 * - 流式响应中也支持增量工具调用数据的处理
 *
 * @param enableToolCall 是否启用Tool Call API格式转换（默认false）
 */
open class OpenAIProvider(
    private val apiEndpoint: String,
    private val apiKeyProvider: ApiKeyProvider,
    val modelName: String,
    private val client: OkHttpClient,
    private val customHeaders: Map<String, String> = emptyMap(),
    private val providerType: ApiProviderType = ApiProviderType.OPENAI,
    protected val supportsVision: Boolean = false, // 是否支持图片处理
    protected val supportsAudio: Boolean = false, // 是否支持音频输入
    protected val supportsVideo: Boolean = false, // 是否支持视频输入
    protected val supportsFiles: Boolean = false, // 是否支持文件输入
    val enableToolCall: Boolean = false, // 是否启用Tool Call接口
    /** Catalog-declared effort values; null means the catalog does not describe effort at all. */
    protected val catalogReasoningEfforts: List<String>? = null,
) : AIService {
    // private val client: OkHttpClient = HttpClientFactory.instance

    protected val JSON = "application/json".toMediaType()

    // 当前活跃的Call对象，用于取消流式传输
    @Volatile
    private var activeCall: Call? = null

    // 当前活跃的Response对象，用于强制关闭流
    @Volatile
    private var activeResponse: Response? = null

    @Volatile
    private var isManuallyCancelled = false

    /** 停滞看门狗是否关闭过连接，用于对停滞单独限次（看门狗在其它线程上写）。 */
    private val stallCloseRequested = AtomicBoolean(false)

    /**
     * 由客户端错误（如4xx状态码）触发的API异常，是否重试由统一策略决定
     */
    class NonRetriableException(
        message: String,
        override val statusCode: Int,
        cause: Throwable? = null
    ) : IOException(message, cause), HttpStatusCodeException

    internal class RepetitionTruncationException(message: String) : IOException(message)

    private fun rejectRepetitionTruncation(context: Context, response: JSONObject) {
        val choices = response.optJSONArray("choices") ?: return
        for (index in 0 until choices.length()) {
            if (choices.optJSONObject(index)?.optString("finish_reason") == "repetition_truncation") {
                throw RepetitionTruncationException(context.getString(R.string.openai_error_repetition_truncation))
            }
        }
    }

    // Token缓存管理器
    val tokenCacheManager = TokenCacheManager()

    protected open val useResponsesApi: Boolean = false

    // 公开token计数
    override val inputTokenCount: Int
        get() = tokenCacheManager.totalInputTokenCount
    override val outputTokenCount: Int
        get() = tokenCacheManager.outputTokenCount
    override val cachedInputTokenCount: Int
        get() = tokenCacheManager.cachedInputTokenCount

    // 供应商:模型标识符
    override val providerModel: String
        get() = "${providerType.name}:$modelName"

    private suspend fun applyUsageToCounters(
        usage: JSONObject?,
        onTokensUpdated: suspend (input: Int, cachedInput: Int, output: Int) -> Unit,
        onUsageReported: (suspend (com.ai.assistance.operit.data.stats.ProviderUsageSnapshot, attempt: Int) -> Unit)? = null,
        attemptNumber: Int = 1
    ) {
        val parsed = OpenAIResponsesPayloadAdapter.parseUsageCounts(usage) ?: return
        tokenCacheManager.updateActualTokens(parsed.actualInputTokens, parsed.cachedInputTokens)
        tokenCacheManager.setOutputTokens(parsed.outputTokens)
        onTokensUpdated(
            parsed.totalInputTokens,
            parsed.cachedInputTokens,
            tokenCacheManager.outputTokenCount
        )
        onUsageReported?.invoke(
            if (useResponsesApi) {
                com.ai.assistance.operit.data.stats.ProviderUsageNormalizer.openAiResponses(usage)
            } else {
                com.ai.assistance.operit.data.stats.ProviderUsageNormalizer.openAiChatCompletions(usage)
            } ?: return,
            attemptNumber
        )
    }

    private fun buildOpenAiErrorDetail(error: JSONObject, fallback: String): String {
        val message = error.optString("message", "").trim().ifEmpty { fallback }
        val type = error.optString("type", "").trim()
        val code = error.opt("code")?.toString()?.trim().orEmpty()

        if (type.isEmpty() && code.isEmpty()) {
            return message
        }

        return buildString {
            append(message)
            append(" [")
            if (type.isNotEmpty()) {
                append("type=").append(type)
            }
            if (type.isNotEmpty() && code.isNotEmpty()) {
                append(", ")
            }
            if (code.isNotEmpty()) {
                append("code=").append(code)
            }
            append("]")
        }
    }

    private fun throwIfOpenAiErrorPayload(context: Context, jsonResponse: JSONObject) {
        val error = jsonResponse.optJSONObject("error") ?: return
        val detail = buildOpenAiErrorDetail(
            error,
            context.getString(R.string.openai_error_no_error_details)
        )
        val exceptionMessage = context.getString(R.string.openai_error_response_failed, detail)

        AppLogger.e("AIService", "【发送消息】响应中包含错误对象: $detail")
        throw IOException(exceptionMessage)
    }

    // 重置token计数
    override fun resetTokenCounts() {
        tokenCacheManager.resetTokenCounts()
    }

    protected open fun customizeFinalRequestObject(
        requestObject: JSONObject,
        messagesArray: JSONArray,
        toolsJson: String?
    ) {
    }

    protected open val requiresStreamingResponse: Boolean = false
    protected open fun applyRequestIdentityHeaders(builder: Request.Builder, logicalRequestId: String) {}

    protected open suspend fun applyAuthenticationHeaders(
        builder: Request.Builder,
        currentApiKey: String
    ) {
        if (currentApiKey.isNotEmpty()) {
            builder.addHeader("Authorization", "Bearer $currentApiKey")
        } else if (providerType == ApiProviderType.OPENCODE_ZEN_FREE) {
            builder.addHeader("Authorization", "Bearer public")
        }
    }

     override fun cancelStreaming() {
         isManuallyCancelled = true
         runCatching { activeResponse?.close() }
         activeResponse = null
         activeCall?.let {
             if (!it.isCanceled()) {
                 runCatching { it.cancel() }
             }
         }
         activeCall = null
     }

     override suspend fun getModelsList(context: Context): Result<List<ModelOption>> {
         return ModelListFetcher.getModelsList(
             context = context,
             apiKey = apiKeyProvider.getApiKey(),
             apiEndpoint = apiEndpoint,
             apiProviderType = providerType
         )
     }

     override suspend fun testConnection(
         context: Context,
         onUsageReported: (suspend (com.ai.assistance.operit.data.stats.ProviderUsageSnapshot, attempt: Int) -> Unit)?
     ): Result<String> {
         return try {
             val testHistory =
                 listOf(
                     PromptTurn(kind = PromptTurnKind.SYSTEM, content = "You are a helpful assistant."),
                     PromptTurn(kind = PromptTurnKind.USER, content = "Hi")
                 )
             val stream =
                 sendMessage(
                     context,
                     testHistory,
                     emptyList(),
                     false,
                     onTokensUpdated = { _, _, _ -> },
                     onUsageReported = onUsageReported,
                     onNonFatalError = {},
                     enableRetry = false
                 )

             stream.collect { _ -> }
             Result.success(context.getString(R.string.openai_connection_success))
         } catch (e: kotlinx.coroutines.CancellationException) {
             // 取消必须原样传播，不能变成 Result.failure
             throw e
         } catch (e: Exception) {
             AppLogger.e("AIService", "连接测试失败", e)
             Result.failure(IOException(context.getString(R.string.openai_connection_test_failed, e.message ?: ""), e))
         }
     }

    // 工具函数：分块打印大型文本日志
    protected fun logLargeString(tag: String, message: String, prefix: String = "") {
        // 设置单次日志输出的最大长度（Android日志上限约为4000字符）
        val maxLogSize = 3000

        // 如果消息长度超过限制，分块打印
        if (message.length > maxLogSize) {
            // 计算需要分多少块打印（向上取整，否则长度正好整除时会多出一个空块）
            val chunkCount = (message.length + maxLogSize - 1) / maxLogSize

            for (i in 0 until chunkCount) {
                val start = i * maxLogSize
                val end = minOf((i + 1) * maxLogSize, message.length)
                val chunkMessage = message.substring(start, end)

                // 打印带有编号的日志
                AppLogger.d(tag, "$prefix Part ${i + 1}/$chunkCount: $chunkMessage")
            }

        } else {
            // 消息长度在限制之内，直接打印
            AppLogger.d(tag, "$prefix$message")
        }
    }

    /** Logging walks the existing request; it never materializes a second request-sized JSON. */
    protected fun logRequestBodyForDebugging(tag: String, prefix: String, body: () -> JSONObject) {
        if (!AppLogger.logRequestBodies) return
        RequestBodyLog.write(tag, prefix, body())
    }

    protected fun logFinalOutput(tag: String, content: CharSequence, prefix: String = "Final output: ") {
        val finalOutput = content.toString()
        if (finalOutput.isBlank()) {
            AppLogger.d(tag, "${prefix.trimEnd()}[empty]")
            return
        }
        logLargeString(tag, finalOutput, prefix)
    }

    private fun getOutputImagesDir(): File {
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

    private fun outputMimeTypeFromFormat(format: String?): String {
        return when (format?.lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            else -> "image/png"
        }
    }

    protected open fun writeOutputImage(bytes: ByteArray, mimeType: String, prefix: String): Uri? {
        return try {
            val dir = getOutputImagesDir()
            if (!dir.exists()) {
                dir.mkdirs()
            }
            val ext = fileExtensionForImageMime(mimeType)
            val fileName = "${prefix}_${System.currentTimeMillis()}.$ext"
            val outFile = File(dir, fileName)
            FileOutputStream(outFile).use { it.write(bytes) }
            Uri.fromFile(outFile)
        } catch (e: Exception) {
            AppLogger.e("AIService", "保存输出图片失败", e)
            null
        }
    }

    private suspend fun downloadBytes(url: String): ByteArray? {
        return try {
            val request = Request.Builder().url(url).get().build()
            val response = withContext(Dispatchers.IO) { client.newCall(request).execute() }
            response.use {
                if (!it.isSuccessful) return null
                val body = it.body ?: return null
                body.bytes()
            }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun emitImageMarkdown(emitter: StreamEmitter, imageUri: Uri, alt: String) {
        val safeAlt = alt.ifBlank { "image" }
        emitter.emitContent("\n![${safeAlt}](${imageUri})\n")
    }

    private data class ImageBufferState(
        var bytes: ByteArray = ByteArray(0),
        var mimeType: String = "image/png"
    )

    private suspend fun emitImageBytes(
        emitter: StreamEmitter,
        bytes: ByteArray,
        mimeType: String,
        prefix: String,
        alt: String,
    ) {
        if (bytes.isEmpty()) return
        val uri = writeOutputImage(bytes, mimeType, prefix) ?: return
        emitImageMarkdown(emitter, uri, alt)
    }

    private suspend fun emitOrDeferImage(
        state: StreamingState?,
        emitter: StreamEmitter,
        bytes: ByteArray,
        mimeType: String,
        prefix: String,
        alt: String,
    ) {
        if (bytes.isEmpty()) return
        if (state == null) {
            emitImageBytes(emitter, bytes, mimeType, prefix, alt)
            return
        }
        state.deferredOutput.addLast(
            DeferredOutputSegment.Image(bytes, mimeType, prefix, alt)
        )
        flushDeferredOutput(state, emitter)
    }

    private suspend fun flushImageBuffers(state: StreamingState, emitter: StreamEmitter) {
        if (state.imageBuffers.isEmpty()) return
        val pending = state.imageBuffers.toMap()
        state.imageBuffers.clear()
        pending.forEach { (index, bufferState) ->
            val bytes = bufferState.bytes
            if (bytes.isNotEmpty()) {
                emitOrDeferImage(
                    state,
                    emitter,
                    bytes,
                    bufferState.mimeType,
                    "openai_image_$index",
                    "openai_image_$index",
                )
            }
        }
    }

    private suspend fun tryHandleOpenAiImageResponse(
        json: JSONObject,
        emitter: StreamEmitter,
        state: StreamingState?
    ): Boolean {
        val dataArr = json.optJSONArray("data")
        if (dataArr != null && dataArr.length() > 0) {
            for (i in 0 until dataArr.length()) {
                val obj = dataArr.optJSONObject(i) ?: continue
                val b64 = obj.optString("b64_json", "")
                val url = obj.optString("url", "")
                val mimeType = outputMimeTypeFromFormat(obj.optString("output_format", "").ifBlank { null })
                if (b64.isNotEmpty()) {
                    val bytes = try {
                        Base64.getMimeDecoder().decode(b64)
                    } catch (_: Exception) {
                        null
                    }
                    if (bytes != null && bytes.isNotEmpty()) {
                        emitOrDeferImage(state, emitter, bytes, mimeType, "openai_image_$i", "openai_image_$i")
                    }
                } else if (url.isNotEmpty()) {
                    val bytes = downloadBytes(url)
                    if (bytes != null && bytes.isNotEmpty()) {
                        emitOrDeferImage(state, emitter, bytes, mimeType, "openai_image_$i", "openai_image_$i")
                    }
                }
            }
            return true
        }

        val eventType = json.optString("type", "")
        if (eventType.startsWith("image_generation.")) {
            val b64 = json.optString("b64_json", "")
            val idx = json.optInt("partial_image_index", 0)
            val format = json.optString("output_format", "").ifBlank { null }
            val mimeType = outputMimeTypeFromFormat(format)
            if (state != null && b64.isNotEmpty()) {
                val decoded = try {
                    Base64.getMimeDecoder().decode(b64)
                } catch (_: Exception) {
                    null
                }
                if (decoded != null) {
                    // This endpoint emits progressive full-image snapshots. Retain only the
                    // newest one so stream completion cannot publish stale previews as extras.
                    state.imageBuffers.clear()
                    val buf = state.imageBuffers.getOrPut(idx) { ImageBufferState() }
                    buf.mimeType = mimeType
                    // Partial-image events are complete preview snapshots, not byte chunks.
                    // Keep only the latest snapshot until a final image or stream end arrives.
                    buf.bytes = decoded
                }
                if (eventType != "image_generation.partial_image") {
                    flushImageBuffers(state, emitter)
                }
            }
            return true
        }

        val outputArr = json.optJSONArray("output")
        if (outputArr != null && outputArr.length() > 0) {
            var handledAny = false
            for (i in 0 until outputArr.length()) {
                val item = outputArr.optJSONObject(i) ?: continue
                val contentArr = item.optJSONArray("content") ?: continue
                for (j in 0 until contentArr.length()) {
                    val part = contentArr.optJSONObject(j) ?: continue
                    val partType = part.optString("type", "")
                    if (partType == "output_text" || partType == "text") {
                        val text = part.optString("text", "")
                        if (text.isNotEmpty()) {
                            if (state == null) {
                                emitter.emitContent(text)
                            } else {
                                processContentDelta("", text, state, emitter)
                            }
                            handledAny = true
                        }
                    }
                    val mimeType = part.optString("mime_type", part.optString("mimeType", "image/png"))
                    val b64 = part.optString("b64_json", part.optString("data", ""))
                    val imageUrlObj = part.optJSONObject("image_url")
                    val url = part.optString("url", imageUrlObj?.optString("url", "") ?: part.optString("image_url", ""))
                    val isImage = partType.contains("image") || mimeType.startsWith("image/")
                    if (isImage) {
                        if (b64.isNotEmpty()) {
                            val bytes = try {
                                Base64.getMimeDecoder().decode(b64)
                            } catch (_: Exception) {
                                null
                            }
                            if (bytes != null && bytes.isNotEmpty()) {
                                emitOrDeferImage(
                                    state,
                                    emitter,
                                    bytes,
                                    mimeType,
                                    "openai_image_${i}_$j",
                                    "openai_image_${i}_$j",
                                )
                                handledAny = true
                            }
                        } else if (url.isNotEmpty()) {
                            val bytes = downloadBytes(url)
                            if (bytes != null && bytes.isNotEmpty()) {
                                emitOrDeferImage(
                                    state,
                                    emitter,
                                    bytes,
                                    mimeType,
                                    "openai_image_${i}_$j",
                                    "openai_image_${i}_$j",
                                )
                                handledAny = true
                            }
                        }
                    }
                }
            }
            return handledAny
        }

        return false
    }

    /**
     * 解析服务器返回的内容，不再需要处理<think>标签
     */
    private fun parseResponse(content: String): String {
        return content
    }

    // 创建请求体
    protected open fun createRequestBody(
        context: Context,
        chatHistory: List<PromptTurn>,
        modelParameters: List<ModelParameter<*>> = emptyList(),
        enableThinking: Boolean = false,
        stream: Boolean = true,
        availableTools: List<ToolPrompt>? = null,
        preserveThinkInHistory: Boolean = false
    ): RequestBody {
        val automaticReasoningRequestParameters =
            consumeAutomaticReasoningSuppression(modelParameters)
        val requestJson =
            createRequestBodyInternal(
                context,
                chatHistory,
                automaticReasoningRequestParameters.modelParameters,
                stream,
                availableTools,
                preserveThinkInHistory,
            )
        if (
            automaticReasoningRequestParameters.suppressAutomaticReasoning ||
                !supportsOpenAiChatReasoningEffort()
        ) {
            return createJsonRequestBody(requestJson.toString())
        }
        applyOpenAiChatReasoning(context, requestJson, enableThinking)
        return createJsonRequestBody(requestJson.toString())
    }

    private fun applyOpenAiChatReasoning(
        context: Context,
        requestJson: JSONObject,
        enableThinking: Boolean
    ) {
        if (!supportsOpenAiChatReasoningEffort()) {
            return
        }

        val existingEffort = requestJson.optString("reasoning_effort", "").trim()
        if (existingEffort.isNotEmpty()) {
            AppLogger.d(
                "OpenAIProvider",
                "Preserving caller-supplied Chat Completions reasoning_effort=$existingEffort"
            )
            return
        }

        val effort = if (enableThinking) {
            resolveOpenAiChatReasoningEffort(context)
        } else {
            "none"
        } ?: return
        val declaredEffort =
            ThinkingRequestSemantics.declaredCatalogReasoningEffort(effort, catalogReasoningEfforts)
                ?: return
        requestJson.put("reasoning_effort", declaredEffort)
        AppLogger.d(
            "OpenAIProvider",
            "OpenAI Chat Completions reasoning_effort=$declaredEffort"
        )
    }

    protected open fun resolveOpenAiChatReasoningEffort(context: Context): String? {
        val qualityLevel = runCatching {
            runBlocking {
                ApiPreferences.getInstance(context).thinkingQualityLevelFlow.first()
            }
        }.getOrElse {
            AppLogger.w(
                "OpenAIProvider",
                "Failed to read thinking quality level; reasoning_effort not applied",
                it
            )
            return null
        }

        return ThinkingRequestSemantics.defaultReasoningEffort(providerType, qualityLevel)
    }

    private fun supportsOpenAiChatReasoningEffort(): Boolean =
        supportsAutomaticOpenAiChatReasoning(providerType)

    protected fun createJsonRequestBody(jsonString: String): RequestBody {
        return jsonString.toByteArray(Charsets.UTF_8).toRequestBody(JSON)
    }

    /**
     * 流式 Chat Completions 请求体附加 usage 返回选项：OpenAI 只在显式请求时于
     * 末块返回 usage；Responses API 始终自带 usage（response.completed），不需要
     * 也不接受 stream_options。仅对明确支持 include_usage 的服务发送，避免通用或
     * 本地兼容端点因未知字段拒绝请求。DeepSeek/Kimi 自建请求体复用本方法。
     */
    protected fun JSONObject.putStreamUsageOption(stream: Boolean) {
        applyChatCompletionsStreamUsageOption(stream, providerType, useResponsesApi)
    }

    /**
     * Build a mutable request so providers can add options without copying the entire payload.
     */
    protected fun createRequestBodyInternal(
        context: Context,
        chatHistory: List<PromptTurn>,
        modelParameters: List<ModelParameter<*>> = emptyList(),
        stream: Boolean = true,
        availableTools: List<ToolPrompt>? = null,
        preserveThinkInHistory: Boolean = false
    ): JSONObject {
        val jsonObject = JSONObject()
        jsonObject.put("model", modelName)
        jsonObject.put("stream", stream) // 根据stream参数设置
        jsonObject.putStreamUsageOption(stream)

        // 添加已启用的模型参数
        for (param in modelParameters) {
            if (param.isEnabled) {
                val mappedApiName =
                    if (useResponsesApi) {
                        OpenAIResponsesPayloadAdapter.mapParameterNameForResponses(param.apiName)
                    } else {
                        param.apiName
                    }
                when (param.valueType) {
                    com.ai.assistance.operit.data.model.ParameterValueType.INT ->
                        jsonObject.put(mappedApiName, param.currentValue as Int)

                    com.ai.assistance.operit.data.model.ParameterValueType.FLOAT ->
                        jsonObject.put(mappedApiName, param.currentValue as Float)

                    com.ai.assistance.operit.data.model.ParameterValueType.STRING ->
                        jsonObject.put(mappedApiName, param.currentValue as String)

                    com.ai.assistance.operit.data.model.ParameterValueType.BOOLEAN ->
                        jsonObject.put(mappedApiName, param.currentValue as Boolean)

                    com.ai.assistance.operit.data.model.ParameterValueType.OBJECT -> {
                        val raw = param.currentValue.toString().trim()
                        val parsed: Any? = try {
                            when {
                                raw.startsWith("{") -> JSONObject(raw)
                                raw.startsWith("[") -> JSONArray(raw)
                                else -> null
                            }
                        } catch (e: Exception) {
                            AppLogger.w("AIService", "OBJECT参数解析失败: ${param.apiName}", e)
                            null
                        }
                        if (parsed != null) {
                            jsonObject.put(mappedApiName, parsed)
                        } else {
                            // 解析失败则按字符串传递，避免崩溃
                            jsonObject.put(mappedApiName, raw)
                        }
                    }
                }
                AppLogger.d("AIService", "添加参数 ${param.apiName} = ${param.currentValue}")
            }
        }

        // 当工具为空时，将enableToolCall视为false
        val effectiveEnableToolCall =
            enableToolCall && availableTools != null && availableTools.isNotEmpty()

        // 如果启用Tool Call且传入了工具列表，添加tools定义
        var toolsJson: String? = null
        if (effectiveEnableToolCall) {
            val tools = buildToolDefinitions(availableTools!!)
            if (tools.length() > 0) {
                jsonObject.put("tools", tools)
                jsonObject.put("tool_choice", "auto") // 让模型自动决定是否使用工具
                toolsJson = tools.toString() // 保存工具定义用于token计算
                AppLogger.d("AIService", "Tool Call已启用，添加了 ${tools.length()} 个工具定义")
            }
        }

        // 使用新的核心逻辑构建消息并获取token计数
        val (messagesArray, tokenCount) = buildMessagesAndCountTokens(
            context,
            chatHistory,
            effectiveEnableToolCall,
            toolsJson,
            preserveThinkInHistory
        )
        jsonObject.put("messages", messagesArray)

        val finalRequestObject =
            if (useResponsesApi) {
                OpenAIResponsesPayloadAdapter.toResponsesRequest(jsonObject)
            } else {
                jsonObject
            }

        customizeFinalRequestObject(finalRequestObject, messagesArray, toolsJson)
        if (providerType == ApiProviderType.OPENCODE_ZEN_FREE) {
            OpenCodeZenFree.enforceFreeModel(finalRequestObject, modelName)
            OpenCodeZenFree.ensureAnonymousRequestShape(finalRequestObject)
        }

        // 使用分块日志函数记录请求体（省略过长的 tools 字段），可用 AppLogger.logRequestBodies 关闭
        logRequestBodyForDebugging("AIService", "Request body: ") {
            finalRequestObject
        }
        return finalRequestObject
    }

    protected open fun comparableRoleForTurn(turn: PromptTurn): String {
        return when (turn.kind) {
            PromptTurnKind.SYSTEM -> "system"
            PromptTurnKind.USER -> "user"
            PromptTurnKind.ASSISTANT -> "assistant"
            PromptTurnKind.TOOL_CALL -> "tool_call"
            PromptTurnKind.TOOL_RESULT -> "tool_result"
            PromptTurnKind.SUMMARY -> "summary"
        }
    }

    protected open fun providerRoleForTurn(turn: PromptTurn): String {
        return when (turn.kind) {
            PromptTurnKind.SYSTEM -> "system"
            PromptTurnKind.USER,
            PromptTurnKind.SUMMARY,
            PromptTurnKind.TOOL_RESULT -> "user"
            PromptTurnKind.ASSISTANT,
            PromptTurnKind.TOOL_CALL -> "assistant"
        }
    }

    protected open fun comparableContentForTurn(
        turn: PromptTurn,
        preserveThinkInHistory: Boolean
    ): String {
        return if (!preserveThinkInHistory && turn.kind == PromptTurnKind.ASSISTANT) {
            ChatUtils.removeThinkingContent(turn.content)
        } else {
            turn.content
        }
    }

    protected open fun buildComparableHistory(
        chatHistory: List<PromptTurn>,
        preserveThinkInHistory: Boolean
    ): List<Pair<String, String>> {
        return chatHistory.map { turn ->
            comparableRoleForTurn(turn) to comparableContentForTurn(turn, preserveThinkInHistory)
        }
    }

    protected open fun buildEffectiveHistory(
        chatHistory: List<PromptTurn>
    ): List<PromptTurn> {
        return chatHistory
    }

    protected open fun prepareHistoryForProvider(
        chatHistory: List<PromptTurn>,
        useToolCall: Boolean
    ): List<PromptTurn> {
        return StructuredToolCallBridge.compileHistoryForProvider(
            buildEffectiveHistory(chatHistory),
            useToolCall = useToolCall
        )
    }

    protected open fun generatedToolCallId(ordinal: Int): String {
        return generatedShortToolCallId("tool_call:$ordinal", ordinal)
    }

    protected fun calculateAndStoreInputTokens(
        providerReadyHistory: List<PromptTurn>,
        toolsJson: String? = null,
        preserveThinkInHistory: Boolean = false
    ): Int {
        val comparableHistory = buildComparableHistory(providerReadyHistory, preserveThinkInHistory)
        return tokenCacheManager.calculateInputTokens(comparableHistory, toolsJson)
    }

    protected fun audioFormatFromMime(mimeType: String): String {
        return when (mimeType.lowercase()) {
            "audio/wav", "audio/x-wav" -> "wav"
            "audio/mpeg", "audio/mp3" -> "mp3"
            "audio/ogg" -> "ogg"
            "audio/webm" -> "webm"
            else -> mimeType.substringAfter("/", "wav")
        }
    }

    protected open fun buildInputAudioPayload(link: MediaLink): JSONObject {
        return JSONObject().apply {
            put("data", link.base64Data)
            put("format", audioFormatFromMime(link.mimeType))
        }
    }

    /**
     * 构建content字段（可能是字符串或数组）
     * @param text 要处理的文本内容
     * @return 纯文本字符串或包含图片和文本的JSONArray
     */
    private fun inputContentText(text: String): String {
        return if (useResponsesApi) {
            text
        } else {
            ChatUtils.stripOpenAiResponsesProtocolMarkup(text)
        }
    }

    private fun canCarryUserRichContent(role: String): Boolean {
        return role.equals("user", ignoreCase = true) ||
            role.equals("summary", ignoreCase = true)
    }

    private fun canCarryResponsesToolImages(role: String): Boolean {
        return useResponsesApi && role.equals("tool", ignoreCase = true)
    }

    protected fun appendReadableImageMessageIfNeeded(
        messagesArray: JSONArray,
        sourceContent: String,
        sourceLabel: String
    ) {
        appendReadableImageMessageIfNeeded(messagesArray, listOf(sourceContent), sourceLabel)
    }

    protected fun appendReadableImageMessageIfNeeded(
        messagesArray: JSONArray,
        sourceContents: List<String>,
        sourceLabel: String
    ) {
        if (!supportsVision) return

        val imageLinks = mutableListOf<ImageLink>()
        val seenIds = mutableSetOf<String>()
        sourceContents.forEach { sourceContent ->
            val contentText = inputContentText(sourceContent)
            if (!MediaLinkParser.hasImageLinks(contentText)) return@forEach
            MediaLinkParser.extractImageLinks(contentText).forEach { link ->
                if (seenIds.add(link.id)) {
                    imageLinks.add(link)
                }
            }
        }

        if (imageLinks.isEmpty()) return

        val contentArray = JSONArray().apply {
            put(
                JSONObject().apply {
                    put("type", "text")
                    put(
                        "text",
                        if (imageLinks.size == 1) {
                            "The previous $sourceLabel included this image."
                        } else {
                            "The previous $sourceLabel included these images."
                        }
                    )
                }
            )
        }
        imageLinks.forEach { link ->
            contentArray.put(
                JSONObject().apply {
                    put("type", "image_url")
                    put(
                        "image_url",
                        JSONObject().apply {
                            put("url", "data:${link.mimeType};base64,${link.base64Data}")
                        }
                    )
                }
            )
        }

        messagesArray.put(
            JSONObject().apply {
                put("role", "user")
                put("content", contentArray)
            }
        )
    }

    fun buildContentField(context: Context, text: String, role: String = "user"): Any {
        val contentText = inputContentText(text)
        val allowUserRichContent = canCarryUserRichContent(role)
        val allowResponsesToolImages = canCarryResponsesToolImages(role)
        val hasImages = MediaLinkParser.hasImageLinks(contentText)
        val hasMedia = MediaLinkParser.hasMediaLinks(contentText)

        val mediaLinks = if (hasMedia) MediaLinkParser.extractMediaLinks(contentText) else emptyList()
        val imageLinks = if (hasImages) MediaLinkParser.extractImageLinks(contentText) else emptyList()

        val audioLinks = mediaLinks.filter { it.type == "audio" }
        val videoLinks = mediaLinks.filter { it.type == "video" }
        val fileLinks = mediaLinks.filter { it.type == "file" }

        val hasSupportedMedia =
            (supportsAudio && audioLinks.isNotEmpty()) ||
                (supportsVideo && videoLinks.isNotEmpty()) ||
                (supportsFiles && fileLinks.isNotEmpty())

        var textWithoutLinks = contentText
        if (hasMedia) {
            textWithoutLinks = MediaLinkParser.removeMediaLinks(textWithoutLinks)
        }
        if (hasImages) {
            textWithoutLinks = MediaLinkParser.removeImageLinks(textWithoutLinks)
        }
        textWithoutLinks = textWithoutLinks.trim()

        if (audioLinks.isNotEmpty() && !supportsAudio) {
            AppLogger.w("AIService", "检测到音频链接，但当前Provider不支持音频多模态输入，已移除音频。原始文本长度: ${contentText.length}, 处理后: ${textWithoutLinks.length}")
        }
        if (videoLinks.isNotEmpty() && !supportsVideo) {
            AppLogger.w("AIService", "检测到视频链接，但当前Provider不支持视频多模态输入，已移除视频。原始文本长度: ${contentText.length}, 处理后: ${textWithoutLinks.length}")
        }
        if (imageLinks.isNotEmpty() && !supportsVision) {
            AppLogger.w("AIService", "检测到图片链接，但当前Provider不支持图片处理，已移除图片。原始文本长度: ${contentText.length}, 处理后: ${textWithoutLinks.length}")
        }

        val hasAnySupportedRichContent =
            (allowUserRichContent && hasSupportedMedia) ||
                ((allowUserRichContent || allowResponsesToolImages) &&
                    supportsVision &&
                    imageLinks.isNotEmpty())
        if (!hasAnySupportedRichContent) {
            if (textWithoutLinks.isNotEmpty()) return textWithoutLinks

            return when {
                audioLinks.isNotEmpty() || videoLinks.isNotEmpty() -> context.getString(R.string.openai_audio_video_omitted)
                imageLinks.isNotEmpty() -> context.getString(R.string.openai_image_omitted)
                fileLinks.isNotEmpty() -> context.getString(R.string.openai_file_omitted)
                else -> "[Empty]"
            }
        }

        val contentArray = JSONArray()

        if (allowUserRichContent && supportsAudio) {
            audioLinks.forEach { link ->
                contentArray.put(JSONObject().apply {
                    put("type", "input_audio")
                    put("input_audio", buildInputAudioPayload(link))
                })
            }
        }

        if (allowUserRichContent && supportsVideo) {
            videoLinks.forEach { link ->
                contentArray.put(JSONObject().apply {
                    put("type", "video_url")
                    put(
                        "video_url",
                        JSONObject().apply {
                            put("url", "data:${link.mimeType};base64,${link.base64Data}")
                        }
                    )
                })
            }
        }

        if (allowUserRichContent && supportsFiles) {
            fileLinks.forEach { link ->
                val fileName = requireNotNull(link.fileName?.takeIf { it.isNotBlank() })
                contentArray.put(JSONObject().apply {
                    put("type", "input_file")
                    put("filename", fileName)
                    put("file_data", "data:${link.mimeType};base64,${link.base64Data}")
                })
            }
        }

        if ((allowUserRichContent || allowResponsesToolImages) && supportsVision) {
            imageLinks.forEach { link ->
                contentArray.put(JSONObject().apply {
                    put("type", "image_url")
                    put("image_url", JSONObject().apply {
                        put("url", "data:${link.mimeType};base64,${link.base64Data}")
                    })
                })
            }
        }

        if (textWithoutLinks.isNotEmpty()) {
            contentArray.put(JSONObject().apply {
                put("type", "text")
                put("text", textWithoutLinks)
            })
        }

        return contentArray
    }

    /**
     * 构建消息列表并计算token（核心逻辑）
     * @param message 用户消息
     * @param chatHistory 聊天历史
     * @param useToolCall 是否启用Tool Call格式转换（会根据工具可用性动态决定）
     * @param toolsJson 工具定义的JSON字符串，用于token计算
     * @return Pair(消息列表JSONArray, 输入token计数)
     */
    protected fun buildMessagesAndCountTokens(
        context: Context,
        chatHistory: List<PromptTurn>,
        useToolCall: Boolean = false,
        toolsJson: String? = null,
        preserveThinkInHistory: Boolean = false
    ): Pair<JSONArray, Int> {
        val messagesArray = JSONArray()
        val providerReadyHistory = prepareHistoryForProvider(chatHistory, useToolCall)

        // 使用TokenCacheManager计算token数量（包含工具定义）
        val tokenCount =
            calculateAndStoreInputTokens(
                providerReadyHistory,
                toolsJson,
                preserveThinkInHistory
            )
        val effectiveHistory = providerReadyHistory

        var queuedAssistantToolText: String? = null
        val pendingAssistantImageSources = mutableListOf<String>()
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

        fun queueToolCalls(textContent: String, toolCalls: JSONArray) {
            appendQueuedAssistantToolText(textContent)
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

            val historyMessage = JSONObject()
            historyMessage.put("role", "assistant")
            val effectiveContent = when {
                !queuedAssistantToolText.isNullOrBlank() -> queuedAssistantToolText
                else -> null
            }
            if (effectiveContent != null) {
                historyMessage.put("content", buildContentField(context, effectiveContent, role = "assistant"))
            }
            historyMessage.put("tool_calls", queuedToolCalls)
            messagesArray.put(historyMessage)

            openToolCalls.addAll(queuedOpenToolCalls)
            queuedAssistantToolText?.let(pendingAssistantImageSources::add)
            queuedAssistantToolText = null
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
                "AIService",
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

        // 添加聊天历史
        if (effectiveHistory.isNotEmpty()) {
            for (turn in effectiveHistory) {
                val content = comparableContentForTurn(turn, preserveThinkInHistory)
                // 当启用Tool Call API时，转换XML格式的工具调用
                if (useToolCall) {
                    when (turn.kind) {
                        PromptTurnKind.SYSTEM -> {
                            flushOpenToolCallsAsUnmatched("system_boundary")
                            messagesArray.put(
                                JSONObject().apply {
                                    put("role", "system")
                                    put("content", buildContentField(context, content, role = "system"))
                                }
                            )
                        }

                        PromptTurnKind.USER,
                        PromptTurnKind.SUMMARY -> {
                            flushOpenToolCallsAsUnmatched("user_boundary")
                            messagesArray.put(
                                JSONObject().apply {
                                    put("role", "user")
                                    put("content", buildContentField(context, content))
                                }
                            )
                        }

                        PromptTurnKind.ASSISTANT -> {
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
                                queueToolCalls(textContent, toolCalls)
                            } else {
                                flushOpenToolCallsAsUnmatched("assistant_boundary")
                                val effectiveContent = if (content.isBlank()) {
                                    AppLogger.d("AIService", "发现空的assistant消息，填充为[空消息]")
                                    "[Empty]"
                                } else {
                                    content
                                }
                                messagesArray.put(
                                    JSONObject().apply {
                                        put("role", "assistant")
                                        put("content", buildContentField(context, effectiveContent, role = "assistant"))
                                    }
                                )
                                appendReadableImageMessageIfNeeded(
                                    messagesArray,
                                    effectiveContent,
                                    "assistant message"
                                )
                            }
                        }

                        PromptTurnKind.TOOL_CALL -> {
                            val (textContent, parsedToolCalls) = parseXmlToolCalls(content)
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
                                val effectiveContent = if (content.isBlank()) "[Empty]" else content
                                messagesArray.put(
                                    JSONObject().apply {
                                        put("role", "assistant")
                                        put("content", buildContentField(context, effectiveContent, role = "assistant"))
                                    }
                                )
                                appendReadableImageMessageIfNeeded(
                                    messagesArray,
                                    effectiveContent,
                                    "assistant tool-call message"
                                )
                            }
                        }

                        PromptTurnKind.TOOL_RESULT -> {
                            emitQueuedToolCallsIfNeeded()
                            val (textContent, toolResults) = parseXmlToolResults(content)
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
                                        "AIService",
                                        "发现未匹配的tool_result: ${resultsList.size - matchedCalls.size}"
                                    )
                                }

                                flushOpenToolCallsAsUnmatched("tool_result_partial_batch")

                                if (!useResponsesApi) {
                                    appendReadableImageMessageIfNeeded(
                                        messagesArray,
                                        readableImageSources,
                                        "tool result"
                                    )
                                }

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
                    flushOpenToolCallsAsUnmatched("tool_call_api_disabled")
                    val role = providerRoleForTurn(turn)
                    // 不启用Tool Call API时，保持原样
                    val historyMessage = JSONObject()
                    historyMessage.put("role", role)

                    // 检查assistant角色的空消息
                    val effectiveContent = if (role == "assistant" && content.isBlank()) {
                        AppLogger.d("AIService", "发现空的assistant消息，填充为[空消息]")
                        "[Empty]"
                    } else {
                        content
                    }
                    historyMessage.put("content", buildContentField(context, effectiveContent, role = role))
                    messagesArray.put(historyMessage)
                    if (role == "assistant") {
                        appendReadableImageMessageIfNeeded(
                            messagesArray,
                            effectiveContent,
                            "assistant message"
                        )
                    }
                }
            }
        }

        flushOpenToolCallsAsUnmatched("history_end")

        return Pair(messagesArray, tokenCount)
    }

    protected open val preserveReasoningForTokenEstimate: Boolean = false

    override suspend fun calculateInputTokens(
        chatHistory: List<PromptTurn>,
        availableTools: List<ToolPrompt>?
    ): Int {
        // 构建工具定义的JSON字符串
        val toolsJson =
            if (enableToolCall && availableTools != null && availableTools.isNotEmpty()) {
                val tools = buildToolDefinitions(availableTools)
                if (tools.length() > 0) tools.toString() else null
            } else {
                null
        }
        // 使用TokenCacheManager计算token数量
        return tokenCacheManager.calculateInputTokens(
            buildComparableHistory(
                prepareHistoryForProvider(chatHistory, enableToolCall && !availableTools.isNullOrEmpty()),
                preserveThinkInHistory = preserveReasoningForTokenEstimate
            ),
            toolsJson,
            updateState = false
        )
    }

    // ==================== Tool Call 支持 ====================

    /**
     * 从ToolPrompt列表构建Tool Call的JSON Schema定义
     */
    fun buildToolDefinitions(toolPrompts: List<ToolPrompt>): JSONArray {
        val tools = JSONArray()

        for (tool in toolPrompts) {
            tools.put(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", tool.name)
                    // 组合description和details作为完整描述
                    val fullDescription = if (tool.details.isNotEmpty()) {
                        "${tool.description}\n${tool.details}"
                    } else {
                        tool.description
                    }
                    put("description", fullDescription)

                    // 只使用结构化参数
                    val parametersSchema =
                        buildSchemaFromStructured(tool.parametersStructured ?: emptyList())
                    put("parameters", parametersSchema)
                })
            })
        }

        return tools
    }

    /**
     * 从结构化参数构建JSON Schema
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
        // OpenAI-compatible validators expect `required` to keep its array type even when empty.
        schema.put("required", required)

        return schema
    }

    /**
     * 将API返回的tool_calls转换为XML格式
     * 这样上层代码无需修改，继续使用XML解析逻辑
     * @param toolCalls tool_calls JSON数组
     * @param isStreaming 是否为流式响应（流式响应中tool_calls是增量的）
     */
    private fun convertToolCallsToXml(toolCalls: JSONArray, _isStreaming: Boolean = false): String {
        val xml = StringBuilder()

        for (i in 0 until toolCalls.length()) {
            val toolCall = toolCalls.getJSONObject(i)
            val function = toolCall.optJSONObject("function") ?: continue

            // 流式响应中，name和arguments可能不在同一个delta中
            val name = function.optProviderToolName() ?: ""
            if (name.isEmpty()) {
                // 如果没有name，说明这是增量更新，跳过
                continue
            }

            val argumentsJson = function.optString("arguments", "")

            // 解析参数JSON
            val params = if (argumentsJson.isNotEmpty()) {
                try {
                    JSONObject(argumentsJson)
                } catch (e: Exception) {
                    AppLogger.w("OpenAIProvider", "Failed to parse tool arguments: $argumentsJson", e)
                    JSONObject()
                }
            } else {
                JSONObject()
            }

            // 构建XML格式
            val toolTagName = ChatMarkupRegex.generateRandomToolTagName()
            xml.append("\n<$toolTagName name=\"$name\">")

            // 添加所有参数
            val keys = params.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = params.get(key)
                // 必须对值进行XML转义，否则会破坏XML结构
                val escapedValue = escapeXml(value.toString())
                xml.append("\n<param name=\"$key\">$escapedValue</param>")
            }

            xml.append("\n</$toolTagName>\n")
        }

        return xml.toString()
    }

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

    private fun sanitizeToolCallId(raw: String): String {
        val cleaned = raw.filter { it.isLetterOrDigit() }
        if (cleaned.isEmpty()) {
            return "call00000"
        }
        if (cleaned.length == 9) {
            return cleaned
        }
        if (cleaned.length > 9) {
            return cleaned.takeLast(9)
        }

        val filler = stableIdHashPart(raw)
        return (cleaned + filler + "000000000").take(9)
    }

    protected fun generatedShortToolCallId(seed: String, ordinal: Int): String {
        return sanitizeToolCallId("${stableIdHashPart(seed)}_$ordinal")
    }

    private fun stableIdHashPart(raw: String): String {
        val hash = raw.hashCode()
        val positive = if (hash == Int.MIN_VALUE) 0 else kotlin.math.abs(hash)
        var base = positive.toString(36)
        base = base.filter { it.isLetterOrDigit() }.lowercase()
        return if (base.isEmpty()) "0" else base
    }

    // 向后兼容的快捷方法
    private fun escapeXml(text: String) = XmlEscaper.escape(text)

    /**
     * 字符串非空且非"null"检查
     */
    private fun String.isNotNullOrEmpty() = this.isNotEmpty() && this != "null"

    /**
     * Tool Call流式输出状态管理
     */
    private data class ToolCallState(
        val tagNames: MutableMap<Int, String> = mutableMapOf()
    ) {
        fun getTagName(index: Int) =
            tagNames.getOrPut(index) { ChatMarkupRegex.generateRandomToolTagName() }

        fun clear(index: Int) {
            tagNames.remove(index)
        }

        fun clear() {
            tagNames.clear()
        }
    }

    /**
     * 流式内容发送辅助类
     */
    private inner class StreamEmitter(
        private val receivedContent: StringBuilder,
        private val emit: suspend (String) -> Unit,
        private val eventChannel: com.ai.assistance.operit.util.stream.MutableSharedStream<TextStreamEvent>,
        private val onTokensUpdated: suspend (Int, Int, Int) -> Unit
    ) {
        private val savepointLengths = mutableMapOf<String, Int>()

        suspend fun emitContent(content: String) {
            if (content.isNotNullOrEmpty()) {
                emit(content)
                receivedContent.append(content)
                tokenCacheManager.addOutputTokens(ChatUtils.estimateTokenCount(content))
                onTokensUpdated(
                    tokenCacheManager.totalInputTokenCount,
                    tokenCacheManager.cachedInputTokenCount,
                    tokenCacheManager.outputTokenCount
                )
            }
        }

        suspend fun emitThinkContent(thinkContent: String, tag: String = "think") {
            if (thinkContent.isNotNullOrEmpty()) {
                val protectedContent =
                    if (tag.equals("think", ignoreCase = true) ||
                        tag.equals("thinking", ignoreCase = true)
                    ) {
                        ChatUtils.escapeProviderReasoningMarkup(thinkContent)
                    } else {
                        thinkContent
                    }
                val openingTag =
                    if (tag.equals("think", ignoreCase = true)) {
                        ChatUtils.PROVIDER_REASONING_OPEN_TAG
                    } else {
                        "<$tag>"
                    }
                val wrapped = "$openingTag$protectedContent</$tag>"
                emit(wrapped)
                receivedContent.append(wrapped)
                tokenCacheManager.addOutputTokens(ChatUtils.estimateTokenCount(thinkContent))
                onTokensUpdated(
                    tokenCacheManager.totalInputTokenCount,
                    tokenCacheManager.cachedInputTokenCount,
                    tokenCacheManager.outputTokenCount
                )
            }
        }

        suspend fun emitReasoningContent(thinkContent: String) {
            if (thinkContent.isNotNullOrEmpty()) {
                val protectedContent = ChatUtils.escapeProviderReasoningMarkup(thinkContent)
                emit(protectedContent)
                receivedContent.append(protectedContent)
                tokenCacheManager.addOutputTokens(ChatUtils.estimateTokenCount(thinkContent))
                onTokensUpdated(
                    tokenCacheManager.totalInputTokenCount,
                    tokenCacheManager.cachedInputTokenCount,
                    tokenCacheManager.outputTokenCount
                )
            }
        }

        suspend fun emitTag(tag: String) {
            emit(tag)
            receivedContent.append(tag)
        }

        suspend fun emitSavepoint(id: String) {
            savepointLengths[id] = receivedContent.length
            eventChannel.emit(TextStreamEvent(TextStreamEventType.SAVEPOINT, id))
        }

        fun getSavepointLength(id: String): Int? = savepointLengths[id]

        suspend fun emitRollback(id: String): Boolean {
            val savepointLength = savepointLengths[id] ?: return false
            if (receivedContent.length > savepointLength) {
                receivedContent.setLength(savepointLength)
            }
            eventChannel.emit(TextStreamEvent(TextStreamEventType.ROLLBACK, id))
            return true
        }

        /**
         * 处理 StreamingJsonXmlConverter 事件，转换为 XML 输出
         */
        suspend fun handleJsonEvents(events: List<StreamingJsonXmlConverter.Event>) {
            for (event in events) {
                when (event) {
                    is StreamingJsonXmlConverter.Event.Tag -> emitTag(event.text)
                    is StreamingJsonXmlConverter.Event.Content -> emitContent(event.text)
                }
            }
        }
    }

    /**
     * 创建 Tool Call 累积对象
     */
    private fun createToolCallAccumulator(index: Int): JSONObject {
        return JSONObject().apply {
            put("index", index)
            put("id", "")
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "")
                put("arguments", "")
            })
        }
    }

    /**
     * 检查是否已被取消，如果是则抛出异常
     */
    private fun checkCancellation(context: Context, exception: Exception? = null) {
        if (isManuallyCancelled) {
            AppLogger.d("AIService", "请求被用户取消，停止重试。")
            throw UserCancellationException(context.getString(R.string.openai_error_request_cancelled), exception)
        }
    }

    private fun resolveRetryErrorText(context: Context, exception: Exception): String {
        return when (exception) {
            is SocketTimeoutException -> context.getString(R.string.openai_error_timeout)
            is UnknownHostException -> context.getString(R.string.openai_error_cannot_resolve_host)
            else -> exception.message?.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.openai_error_network_interrupted)
        }
    }

    /**
     * 处理可重试错误的统一逻辑
     */
    private suspend fun handleRetryableError(
        context: Context,
        exception: Exception,
        retryCount: Int,
        maxRetries: Int,
        enableRetry: Boolean,
        onNonFatalError: suspend (String) -> Unit,
        buildRetryMessage: (String, Int) -> String
    ): Int {
        if (exception is UserCancellationException || exception is CancellationException) {
            throw exception
        }
        checkCancellation(context, exception)

        if (exception is CommandCodeProtocolException || exception is RepetitionTruncationException) throw exception

        val errorText = resolveRetryErrorText(context, exception)

        if (!enableRetry) {
            throw IOException(errorText, exception)
        }

        val newRetryCount = retryCount + 1
        if (newRetryCount > maxRetries) {
            AppLogger.e("AIService", "【发送消息】$errorText 且达到最大重试次数($maxRetries)", exception)
            throw IOException(
                context.getString(R.string.openai_error_connection_timeout, maxRetries, errorText),
                exception
            )
        }

        val retryDelayMs = LlmRetryPolicy.nextDelayMs(newRetryCount)
        AppLogger.w("AIService", "【发送消息】$errorText，将在 ${retryDelayMs}ms 后进行第 $newRetryCount 次重试...", exception)
        if (!shouldSuppressKeyPoolRateLimitNotice(apiKeyProvider, exception, "AIService")) {
            onNonFatalError(buildRetryMessage(errorText, newRetryCount))
        }
        delay(retryDelayMs)

        return newRetryCount
    }

    protected fun wrapPackageToolCallsWithProxy(toolCalls: JSONArray): JSONArray {
        val wrappedToolCalls = JSONArray()
        var wrappedCount = 0

        for (i in 0 until toolCalls.length()) {
            val toolCall = toolCalls.optJSONObject(i) ?: continue
            val function = toolCall.optJSONObject("function")
            if (function == null) {
                wrappedToolCalls.put(toolCall)
                continue
            }

            val toolName = function.optProviderToolName() ?: ""
            if (!toolName.contains(":") || toolName == "package_proxy") {
                wrappedToolCalls.put(toolCall)
                continue
            }

            val rawArguments = function.optString("arguments", "{}")
            val originalArguments = JSONObject(if (rawArguments.isBlank()) "{}" else rawArguments)
            val proxyArguments = JSONObject().apply {
                put("tool_name", toolName)
                put("params", originalArguments)
            }

            val wrappedFunction = JSONObject(function.toString()).apply {
                put("name", "package_proxy")
                put("arguments", proxyArguments.toString())
            }

            val wrappedToolCall = JSONObject(toolCall.toString()).apply {
                put("function", wrappedFunction)
            }
            wrappedToolCalls.put(wrappedToolCall)
            wrappedCount++
        }

        if (wrappedCount > 0) {
            AppLogger.d("AIService", "已代理封装 $wrappedCount 个带冒号工具调用到 package_proxy")
        }
        return wrappedToolCalls
    }

    /**
     * 解析XML格式的tool调用，转换为OpenAI Tool Call格式
     * @return Pair<文本内容, tool_calls数组>
     */
    open fun parseXmlToolCalls(content: String): Pair<String, JSONArray?> {
        val matches = ChatMarkupRegex.toolCallPattern.findAll(content)

        if (!matches.any()) {
            return Pair(content, null)
        }

        val toolCalls = JSONArray()
        var textContent = content
        var callIndex = 0

        matches.forEach { match ->
            val toolName = match.groupValues[2]
            val toolBody = match.groupValues[3]

            // 解析参数
            val params = JSONObject()

            ChatMarkupRegex.toolParamPattern.findAll(toolBody).forEach { paramMatch ->
                val paramName = paramMatch.groupValues[1]
                val paramValue = XmlEscaper.unescape(paramMatch.groupValues[2].trim())
                params.put(paramName, paramValue)
            }

            // 构建tool_call对象
            // 使用工具名和参数的哈希生成确定性ID
            val toolNamePart = sanitizeToolCallId(toolName)
            val hashPart = stableIdHashPart("${toolName}:${params}")
            val callId = sanitizeToolCallId("call_${toolNamePart}_${hashPart}_$callIndex")
            toolCalls.put(JSONObject().apply {
                put("id", callId)
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", toolName)
                    put("arguments", params.toString())
                })
            })

            callIndex++

            // 从文本内容中移除tool标签
            textContent = textContent.replace(match.value, "")
        }

        return Pair(textContent.trim(), toolCalls)
    }

    /**
     * 解析XML格式的tool_result，转换为OpenAI Tool消息格式
     * @return List<Pair<tool_name, result_content>>
     */
    fun parseXmlToolResults(content: String): Pair<String, List<Pair<String, String>>?> {
        // 匹配带属性的tool_result标签，例如: <tool_result name="..." status="...">...</tool_result>
        val matches = ChatMarkupRegex.toolResultAnyPattern.findAll(content)

        if (!matches.any()) {
            return Pair(content, null)
        }

        val results = mutableListOf<Pair<String, String>>()
        var textContent = content
        matches.forEach { match ->
            // 提取<content>标签内的内容，如果有的话
            val fullContent = match.groupValues[2].trim()
            val contentMatch = ChatMarkupRegex.contentTag.find(fullContent)
            val resultContent = if (contentMatch != null) {
                contentMatch.groupValues[1].trim()
            } else {
                fullContent
            }

            val openingTag = match.value.substringBefore('>')
            val name = ChatMarkupRegex.nameAttr.find(openingTag)?.groupValues?.getOrNull(1).orEmpty()
            results.add(name to resultContent)

            // 从文本内容中移除tool_result标签（包括前后的空白符）
            textContent = textContent.replace(match.value, "").trim()
        }

        // trim 确保移除所有空白字符
        return Pair(textContent.trim(), results)
    }

    // 创建请求
    private val openCodeGoHeaders = OpenCodeGoHeaders()
    private val openCodeZenFreeHeaders = OpenCodeZenFreeHeaders()

    private suspend fun createRequest(
        requestBody: RequestBody,
        requestTraceId: String,
        stream: Boolean,
        attemptNumber: Int,
        logicalRequestId: String,
    ): Request {
        val currentApiKey = apiKeyProvider.getApiKey().trim()
        val endpointUrl = EndpointCompleter.completeEndpoint(apiEndpoint, providerType)
        val traceContext =
            LlmRequestTraceContext(
                requestId = requestTraceId,
                provider = providerType.name,
                model = modelName,
                stream = stream,
                attempt = attemptNumber,
                endpointLabel = endpointUrl.substringBefore('?')
            )
        val builder = Request.Builder()
            .url(endpointUrl)
            .tag(LlmRequestTraceContext::class.java, traceContext)
            .addHeader("Content-Type", "application/json")

        applyAuthenticationHeaders(builder, currentApiKey)

        // 添加自定义请求头
        customHeaders.forEach { (key, value) ->
            builder.addHeader(key, value)
        }

        openCodeGoHeaders.applyTo(builder)
        if (providerType == ApiProviderType.OPENCODE_ZEN_FREE) {
            openCodeZenFreeHeaders.applyTo(builder, logicalRequestId)
        }
        applyRequestIdentityHeaders(builder, logicalRequestId)
        val request = builder.post(requestBody).build()
        val bodyBytes = runCatching { requestBody.contentLength() }.getOrDefault(-1L)
        AppLogger.d(
            "AIService",
            "[req=$requestTraceId] Request trace summary: provider=${traceContext.provider}, model=${traceContext.model}, stream=$stream, attempt=$attemptNumber, bodyBytes=$bodyBytes, endpoint=${traceContext.endpointLabel}"
        )
        logLargeString("AIService", "Request headers: \n${HttpLogSanitizer.headersForLog(request.headers)}")
        return request
    }

    /**
     * 流式响应处理状态
     */
    private sealed interface DeferredOutputSegment {
        data class Tool(val index: Int) : DeferredOutputSegment

        data class Text(val content: StringBuilder) : DeferredOutputSegment

        data class Image(
            val bytes: ByteArray,
            val mimeType: String,
            val prefix: String,
            val alt: String,
        ) : DeferredOutputSegment
    }

    private data class StreamingState(
        var chunkCount: Int = 0,
        var isInReasoningMode: Boolean = false,
        var hasEmittedThinkStart: Boolean = false,
        var hasEmittedRegularContent: Boolean = false,
        var streamedReasoningContentLength: Int = 0,
        var reasoningObserved: Boolean = false,
        var suppressedReasoningAfterTool: Boolean = false,
        var isFirstResponse: Boolean = true,
        val accumulatedToolCalls: MutableMap<Int, JSONObject> = mutableMapOf(),
        val toolCallState: ToolCallState = ToolCallState(),
        var lastProcessedToolIndex: Int? = null,
        val imageBuffers: MutableMap<Int, ImageBufferState> = mutableMapOf(),
        val deferredOutput: java.util.ArrayDeque<DeferredOutputSegment> = java.util.ArrayDeque(),
        val queuedToolIndices: MutableSet<Int> = mutableSetOf(),
        val completedToolIndices: MutableSet<Int> = mutableSetOf(),
        val emittedResponsesToolIndices: MutableSet<Int> = mutableSetOf(),
    )

    private suspend fun closeReasoningBlockIfOpen(
        state: StreamingState,
        emitter: StreamEmitter,
    ): Boolean {
        if (!state.isInReasoningMode) return false
        state.isInReasoningMode = false
        emitter.emitTag("</think>")
        state.hasEmittedThinkStart = false
        return true
    }

    private fun queueToolOutput(index: Int, state: StreamingState) {
        if (state.queuedToolIndices.add(index)) {
            state.deferredOutput.addLast(DeferredOutputSegment.Tool(index))
        }
    }

    /**
     * 处理单个工具调用的增量数据
     */
    private fun accumulateToolCallChunk(
        index: Int,
        deltaCall: JSONObject,
        state: StreamingState,
    ): Boolean {
        // Responses output indices identify items for the entire response. Completion
        // snapshots and late deltas must not reopen an item that has already been emitted.
        if (useResponsesApi && index in state.emittedResponsesToolIndices) return false
        queueToolOutput(index, state)

        // 获取或创建该index的累积对象
        val accumulated = state.accumulatedToolCalls.getOrPut(index) {
            createToolCallAccumulator(index)
        }

        // 更新id和type
        deltaCall.optString("id", "").let {
            if (it.isNotEmpty()) accumulated.put("id", it)
        }
        deltaCall.optString("type", "").let {
            if (it.isNotEmpty()) accumulated.put("type", it)
        }

        // 处理function字段
        val deltaFunction = deltaCall.optJSONObject("function") ?: return false
        val accFunction = accumulated.getJSONObject("function")
        
        // 处理工具名
        val name = deltaFunction.optProviderToolName() ?: ""
        if (name.isNotEmpty()) {
            accFunction.put("name", name)
        }
        
        // 处理参数
        val args = deltaFunction.optString("arguments", "")
        if (args.isNotEmpty()) {
            val currentArgs = accFunction.optString("arguments", "")
            val mergedArgs = mergeCanonicalArgs(currentArgs, args)
            val changed = mergedArgs != currentArgs
            if (changed) {
                accFunction.put("arguments", mergedArgs)
            }
        }

        return true
    }

    private suspend fun processToolCallChunk(
        index: Int,
        deltaCall: JSONObject,
        state: StreamingState,
        emitter: StreamEmitter,
    ) {
        val previousIndex = state.lastProcessedToolIndex
        if (previousIndex != null && previousIndex != index &&
            isToolCallCompleteAtSwitch(previousIndex, state)
        ) {
            state.completedToolIndices.add(previousIndex)
            flushDeferredOutput(state, emitter)
        }

        if (!accumulateToolCallChunk(index, deltaCall, state)) {
            state.lastProcessedToolIndex = index
            return
        }
        state.lastProcessedToolIndex = index
    }

    private fun isToolCallCompleteAtSwitch(index: Int, state: StreamingState): Boolean {
        val function = state.accumulatedToolCalls[index]?.optJSONObject("function") ?: return false
        if (function.optProviderToolName().isNullOrEmpty()) return false
        val arguments = function.optString("arguments", "")
        if (arguments.isEmpty()) return false
        return runCatching { JSONObject(arguments) }.isSuccess
    }

    private suspend fun emitCompletedToolCall(
        index: Int,
        state: StreamingState,
        emitter: StreamEmitter,
    ): Boolean {
        val function = state.accumulatedToolCalls[index]?.optJSONObject("function") ?: return false
        val name = function.optProviderToolName() ?: ""
        if (name.isEmpty()) return false

        val arguments = function.optString("arguments", "")
        if (arguments.isNotEmpty() && runCatching { JSONObject(arguments) }.isFailure) {
            StreamLogger.w(
                "OpenAIProvider",
                "丢弃无法安全闭合的流式 tool envelope，并保留其后的常规正文",
            )
            return false
        }

        // Build and validate every JSON-to-XML event before publishing the opening tag. A
        // malformed provider payload therefore cannot require a mid-response rollback.
        val parser = StreamingJsonXmlConverter()
        val events =
            buildList {
                if (arguments.isNotEmpty()) addAll(parser.feed(arguments))
                addAll(parser.flush())
            }
        if (parser.hasUnfinishedParam()) {
            StreamLogger.w(
                "OpenAIProvider",
                "丢弃 JSON-to-XML 解析器无法闭合的流式 tool envelope",
            )
            return false
        }

        val toolTagName = state.toolCallState.getTagName(index)
        emitter.emitTag("\n<$toolTagName name=\"$name\">")
        emitter.handleJsonEvents(events)
        emitter.emitTag("\n</$toolTagName>")
        return true
    }

    /**
     * 合并 tool arguments（单一路径）：
     * - 默认将 incoming 视为增量追加；
     * - 若 incoming 为前缀扩展快照（incoming startsWith existing），则直接替换为快照。
     */
    private fun mergeCanonicalArgs(existing: String, incoming: String): String {
        if (incoming.isEmpty()) return existing
        if (existing.isEmpty()) return incoming

        // 增量通道：默认直接追加；若供应商偶发回传完整快照，则直接切换为快照值。
        return if (incoming.startsWith(existing)) incoming else existing + incoming
    }

    /**
     * 处理工具调用的增量数据
     */
    private suspend fun processToolCallsDelta(
        toolCallsDeltas: JSONArray,
        state: StreamingState,
        emitter: StreamEmitter
    ) {
        // Structured tool markup must never be emitted inside provider-controlled reasoning.
        closeReasoningBlockIfOpen(state, emitter)

        for (i in 0 until toolCallsDeltas.length()) {
            val deltaCall = toolCallsDeltas.getJSONObject(i)
            val index = deltaCall.optInt("index", -1)
            if (index < 0) continue

            // Chat Completions 的 tool_calls.arguments 为增量片段。
            processToolCallChunk(index, deltaCall, state, emitter)
        }
    }

    private fun hasOpenToolCalls(state: StreamingState): Boolean {
        return state.queuedToolIndices.isNotEmpty()
    }

    private fun queueRegularContent(content: String, state: StreamingState) {
        val tail = state.deferredOutput.peekLast()
        if (tail is DeferredOutputSegment.Text) {
            tail.content.append(content)
        } else {
            state.deferredOutput.addLast(DeferredOutputSegment.Text(StringBuilder(content)))
        }
    }

    private suspend fun emitRegularContentDirect(
        content: String,
        state: StreamingState,
        emitter: StreamEmitter,
    ) {
        if (content.isEmpty()) return
        closeReasoningBlockIfOpen(state, emitter)
        state.hasEmittedRegularContent = true
        if (state.isFirstResponse) {
            state.isFirstResponse = false
            AppLogger.d("AIService", "【发送消息】收到首个有效内容片段")
        }
        emitter.emitContent(content)
    }

    private suspend fun flushDeferredOutput(
        state: StreamingState,
        emitter: StreamEmitter,
    ) {
        while (state.deferredOutput.isNotEmpty()) {
            when (val segment = state.deferredOutput.peekFirst()) {
                is DeferredOutputSegment.Text -> {
                    state.deferredOutput.removeFirst()
                    emitRegularContentDirect(segment.content.toString(), state, emitter)
                }

                is DeferredOutputSegment.Tool -> {
                    if (segment.index !in state.completedToolIndices) return
                    state.deferredOutput.removeFirst()
                    emitCompletedToolCall(segment.index, state, emitter)
                    if (useResponsesApi) state.emittedResponsesToolIndices.add(segment.index)
                    state.completedToolIndices.remove(segment.index)
                    state.queuedToolIndices.remove(segment.index)
                    state.accumulatedToolCalls.remove(segment.index)
                    state.toolCallState.clear(segment.index)
                    if (state.lastProcessedToolIndex == segment.index) {
                        state.lastProcessedToolIndex = null
                    }
                }

                is DeferredOutputSegment.Image -> {
                    state.deferredOutput.removeFirst()
                    emitImageBytes(
                        emitter,
                        segment.bytes,
                        segment.mimeType,
                        segment.prefix,
                        segment.alt,
                    )
                }
            }
        }
    }

    private suspend fun completeToolCall(
        index: Int,
        state: StreamingState,
        emitter: StreamEmitter,
    ) {
        if (index !in state.queuedToolIndices) return
        state.completedToolIndices.add(index)
        flushDeferredOutput(state, emitter)
    }

    private suspend fun finalizeOpenToolSequence(
        state: StreamingState,
        emitter: StreamEmitter,
    ) {
        state.completedToolIndices.addAll(state.accumulatedToolCalls.keys)
        flushDeferredOutput(state, emitter)
    }

    private fun hasResponsesReasoningItem(responseObj: JSONObject?): Boolean {
        val output = responseObj?.optJSONArray("output") ?: return false
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            if (item.optString("type", "") == "reasoning") {
                return true
            }
        }
        return false
    }

    private fun extractResponsesReasoningText(responseObj: JSONObject?): String {
        val output = responseObj?.optJSONArray("output") ?: return ""
        val chunks = mutableListOf<String>()

        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            if (item.optString("type", "") != "reasoning") {
                continue
            }

            val itemText = extractResponsesReasoningTextFromItem(item)
            if (itemText.isNotEmpty()) {
                chunks.add(itemText)
            }
        }

        return chunks.joinToString("\n\n")
    }

    private fun extractResponsesReasoningTextFromItem(item: JSONObject): String {
        val chunks = mutableListOf<String>()

        val summaryArray = item.optJSONArray("summary")
        if (summaryArray != null) {
            for (i in 0 until summaryArray.length()) {
                val summaryPart = summaryArray.optJSONObject(i) ?: continue
                val text = summaryPart.optString("text", "").trim()
                if (text.isNotEmpty()) {
                    chunks.add(text)
                }
            }
        }

        val contentArray = item.optJSONArray("content")
        if (contentArray != null) {
            for (i in 0 until contentArray.length()) {
                val contentPart = contentArray.optJSONObject(i) ?: continue
                val text = contentPart.optString("text", "").trim()
                if (text.isNotEmpty()) {
                    chunks.add(text)
                }
            }
        }

        return chunks.joinToString("\n\n")
    }

    private suspend fun processResponsesStreamingEvent(
        context: Context,
        jsonResponse: JSONObject,
        state: StreamingState,
        emitter: StreamEmitter,
        onTokensUpdated: suspend (input: Int, cachedInput: Int, output: Int) -> Unit,
        onUsageReported: (suspend (com.ai.assistance.operit.data.stats.ProviderUsageSnapshot, attempt: Int) -> Unit)? = null,
        attemptNumber: Int = 1
    ) {
        val eventType = jsonResponse.optString("type", "")

        if (eventType.startsWith("response.image_generation_call.")) {
            // Responses partial images are complete preview snapshots under
            // `partial_image_b64`; the authoritative final image arrives as
            // `response.output_item.done.item.result`. Do not concatenate previews.
            return
        }

        when (eventType) {
            "response.output_text.delta" -> {
                val delta = jsonResponse.optString("delta", "")
                if (delta.isNotEmpty()) {
                    processContentDelta("", delta, state, emitter)
                }
            }

            "response.reasoning_text.delta", "response.reasoning_summary_text.delta" -> {
                state.reasoningObserved = true
                val delta = jsonResponse.optString("delta", "")
                if (delta.isNotEmpty()) {
                    processContentDelta(delta, "", state, emitter)
                }
            }

            "response.reasoning_text.done", "response.reasoning_summary_text.done" -> {
                state.reasoningObserved = true
                val text = jsonResponse.optString("text", "")
                if (text.isNotEmpty() && state.streamedReasoningContentLength == 0) {
                    emitCompletedResponsesReasoningText(text, state, emitter)
                }
            }

            "response.reasoning_summary_part.done" -> {
                state.reasoningObserved = true
                val part = jsonResponse.optJSONObject("part")
                val text = part?.optString("text", "") ?: ""
                if (text.isNotEmpty() && state.streamedReasoningContentLength == 0) {
                    emitCompletedResponsesReasoningText(text, state, emitter)
                }
            }

            "response.output_item.added", "response.output_item.done" -> {
                val outputIndex = jsonResponse.optInt("output_index", -1)
                val item = jsonResponse.optJSONObject("item")
                if (outputIndex < 0 || item == null) {
                    return
                }

                if (item.optString("type", "") == "reasoning") {
                    state.reasoningObserved = true
                    val itemReasoningText =
                        if (eventType == "response.output_item.done") {
                            extractResponsesReasoningTextFromItem(item)
                        } else {
                            ""
                        }
                    if (itemReasoningText.isNotEmpty() && state.streamedReasoningContentLength == 0) {
                        emitCompletedResponsesReasoningText(itemReasoningText, state, emitter)
                    }
                    if (eventType == "response.output_item.done") {
                        closeReasoningBlockIfOpen(state, emitter)
                        if (!hasOpenToolCalls(state)) {
                            OpenAIResponsesPayloadAdapter.createReasoningMetadataTag(item)?.let { metadataTag ->
                                emitter.emitTag(metadataTag)
                            }
                        } else {
                            AppLogger.d(
                                "AIService",
                                "Ignoring reasoning metadata received after a pending tool call",
                            )
                        }
                    }
                    return
                }

                if (item.optString("type", "") == "image_generation_call") {
                    if (eventType == "response.output_item.done") {
                        val result = item.optString("result", "")
                        val bytes =
                            if (result.isNotEmpty()) {
                                runCatching { Base64.getMimeDecoder().decode(result) }.getOrNull()
                            } else {
                                null
                            }
                        if (bytes != null && bytes.isNotEmpty()) {
                            emitOrDeferImage(
                                state,
                                emitter,
                                bytes,
                                "image/png",
                                "openai_image_$outputIndex",
                                "openai_image_$outputIndex",
                            )
                        }
                    }
                    return
                }

                if (!enableToolCall || item.optString("type", "") != "function_call") {
                    return
                }

                closeReasoningBlockIfOpen(state, emitter)

                val functionObj = JSONObject().apply {
                    val name = item.optProviderToolName() ?: ""
                    if (name.isNotEmpty()) {
                        put("name", name)
                    }
                }

                val deltaCall = JSONObject().apply {
                    put("index", outputIndex)
                    val callId = item.optString("call_id", item.optString("id", ""))
                    if (callId.isNotEmpty()) {
                        put("id", callId)
                    }
                    put("type", "function")
                    put("function", functionObj)
                }

                // 对于 output_item 事件，仅更新工具元信息（name/id）。
                // 参数统一由 response.function_call_arguments.delta 通道累积，避免快照+增量混拼导致 JSON 破坏。
                accumulateToolCallChunk(outputIndex, deltaCall, state)
                // 某些供应商会先发送 output_item.done，随后才发送 function_call_arguments.delta。
                // 因此不在 output_item.done 阶段关闭工具调用，改由
                // response.function_call_arguments.done / response.completed 统一收口。
            }

            "response.function_call_arguments.delta" -> {
                if (!enableToolCall) return
                val outputIndex = jsonResponse.optInt("output_index", -1)
                if (outputIndex < 0) return

                closeReasoningBlockIfOpen(state, emitter)

                val deltaCall = JSONObject().apply {
                    put("index", outputIndex)
                    put("type", "function")
                    put(
                        "function",
                        JSONObject().apply {
                            val name = jsonResponse.optProviderToolName() ?: ""
                            if (name.isNotEmpty()) {
                                put("name", name)
                            }
                            val delta = jsonResponse.optString("delta", "")
                            if (delta.isNotEmpty()) {
                                put("arguments", delta)
                            }
                        }
                    )
                }

                processToolCallChunk(outputIndex, deltaCall, state, emitter)
            }

            "response.function_call_arguments.done" -> {
                if (!enableToolCall) return
                val outputIndex = jsonResponse.optInt("output_index", -1)
                if (outputIndex >= 0) {
                    completeToolCall(outputIndex, state, emitter)
                }
            }

            "response.completed" -> {
                val responseObj = jsonResponse.optJSONObject("response")
                val usage = responseObj?.optJSONObject("usage")
                val reasoningTokens =
                    usage
                        ?.optJSONObject("output_tokens_details")
                        ?.optInt("reasoning_tokens", 0)
                        ?: 0
                if (reasoningTokens > 0 || hasResponsesReasoningItem(responseObj)) {
                    state.reasoningObserved = true
                }

                val reasoningWasOpen = closeReasoningBlockIfOpen(state, emitter)
                finalizeOpenToolSequence(state, emitter)
                if (!reasoningWasOpen && !state.suppressedReasoningAfterTool) {
                    val lateReasoningText = extractResponsesReasoningText(responseObj)
                    if (lateReasoningText.isNotEmpty() && state.streamedReasoningContentLength == 0) {
                        emitCompletedResponsesReasoningText(lateReasoningText, state, emitter)
                    }
                }
                closeReasoningBlockIfOpen(state, emitter)
                applyUsageToCounters(usage, onTokensUpdated, onUsageReported, attemptNumber)
            }

            "response.failed", "response.error" -> {
                val error = jsonResponse.optJSONObject("error")
                val responseObj = jsonResponse.optJSONObject("response")
                val errorMessage =
                    error?.optString("message", "")
                        ?.takeIf { it.isNotBlank() }
                        ?: responseObj?.optJSONObject("error")
                            ?.optString("message", "")
                            ?.takeIf { it.isNotBlank() }
                        ?: responseObj?.optString("status", "")
                            ?.takeIf { it.isNotBlank() }
                            ?.let { "Responses stream failed with status: $it" }
                        ?: "Responses stream returned $eventType"

                AppLogger.w("AIService", "Responses流式事件错误: $errorMessage")
                throw IOException(context.getString(R.string.openai_error_response_failed, errorMessage))
            }
        }
    }

    /**
     * 处理完成原因
     */
    private suspend fun handleFinishReason(
        finishReason: String,
        state: StreamingState,
        emitter: StreamEmitter,
        onTokensUpdated: suspend (input: Int, cachedInput: Int, output: Int) -> Unit
    ) {
        val normalizedFinishReason = finishReason.trim()
        if (normalizedFinishReason.isEmpty() ||
            normalizedFinishReason.equals("null", ignoreCase = true) ||
            normalizedFinishReason.equals("none", ignoreCase = true)
        ) {
            return
        }

        if (hasOpenToolCalls(state)) {
            finalizeOpenToolSequence(state, emitter)
            AppLogger.d("AIService", "Tool Call流式收尾，finish_reason=$normalizedFinishReason")

            onTokensUpdated(
                tokenCacheManager.totalInputTokenCount,
                tokenCacheManager.cachedInputTokenCount,
                tokenCacheManager.outputTokenCount
            )

            // 清空累积器
            state.accumulatedToolCalls.clear()
            state.lastProcessedToolIndex = null
        }
        flushDeferredOutput(state, emitter)
    }

    /**
     * 处理内容增量（思考和常规内容）
     */
    private suspend fun processContentDelta(
        reasoningContent: String,
        regularContent: String,
        state: StreamingState,
        emitter: StreamEmitter
    ) {
        val hasReasoning = reasoningContent.isNotNullOrEmpty()
        val hasRegular = regularContent.isNotNullOrEmpty()

        // Once a structured tool has been observed, preserve provider order in a deferred output
        // queue. Reasoning that arrives out of protocol order remains untrusted and is discarded;
        // visible content is committed only after every earlier tool segment is complete.
        if (hasOpenToolCalls(state) && (hasReasoning || hasRegular)) {
            if (hasReasoning) {
                state.suppressedReasoningAfterTool = true
                AppLogger.d("AIService", "Ignoring provider reasoning received during an open tool call")
            }
            if (hasRegular) {
                queueRegularContent(regularContent, state)
                AppLogger.d("AIService", "Queueing regular content behind a pending tool call")
            }
            return
        }

        // 处理思考内容
        if (hasReasoning && !state.hasEmittedRegularContent) {
            state.streamedReasoningContentLength += reasoningContent.length
            if (!state.isInReasoningMode) {
                state.isInReasoningMode = true
                if (!state.hasEmittedThinkStart) {
                    emitter.emitTag(ChatUtils.PROVIDER_REASONING_OPEN_TAG)
                    state.hasEmittedThinkStart = true
                }
            }
            emitter.emitReasoningContent(reasoningContent)
        }
        // 处理常规内容
        if (hasRegular) {
            // 如果之前在思考模式，现在切换到了常规内容，需要关闭思考标签
            closeReasoningBlockIfOpen(state, emitter)

            // 硬切策略：正文一旦开始输出，后续到达的推理内容全部忽略
            state.hasEmittedRegularContent = true

            // 当收到第一个有效内容时，标记不再是首次响应
            if (state.isFirstResponse) {
                state.isFirstResponse = false
                AppLogger.d("AIService", "【发送消息】收到首个有效内容片段")
            }

            emitter.emitContent(regularContent)
        }
    }

    private suspend fun emitCompletedResponsesReasoningText(
        reasoningText: String,
        state: StreamingState,
        emitter: StreamEmitter
    ) {
        if (hasOpenToolCalls(state)) {
            state.suppressedReasoningAfterTool = true
            AppLogger.d("AIService", "Ignoring completed reasoning received during an open tool call")
            return
        }
        if (state.hasEmittedRegularContent) {
            emitter.emitThinkContent(reasoningText)
            state.streamedReasoningContentLength += reasoningText.length
        } else {
            processContentDelta(reasoningText, "", state, emitter)
        }
    }

    /**
     * 处理单个响应块
     */
    private suspend fun processResponseChunk(
        jsonResponse: JSONObject,
        state: StreamingState,
        emitter: StreamEmitter,
        onTokensUpdated: suspend (input: Int, cachedInput: Int, output: Int) -> Unit,
        onUsageReported: (suspend (com.ai.assistance.operit.data.stats.ProviderUsageSnapshot, attempt: Int) -> Unit)? = null,
        attemptNumber: Int = 1
    ) {
        val usage = jsonResponse.optJSONObject("usage")
        val choices = jsonResponse.optJSONArray("choices")
        if (choices == null || choices.length() == 0) {
            applyUsageToCounters(usage, onTokensUpdated, onUsageReported, attemptNumber)
            return
        }

        val choice = choices.getJSONObject(0)

        // 处理delta格式（流式响应）
        val delta = choice.optJSONObject("delta")
        if (delta != null) {
            val finishReason =
                if (choice.has("finish_reason") && !choice.isNull("finish_reason")) {
                    choice.optString("finish_reason", "").trim()
                } else {
                    ""
                }

            // Resolve reasoning/content boundaries before emitting structured tool markup. Some
            // compatible endpoints include reasoning_content and tool_calls in the same delta.
            val reasoningContent = delta.optString("reasoning_content", "").ifBlank {
                delta.optString("reasoning", "")
            }
            val regularContent = delta.optString("content", "")
            processContentDelta(reasoningContent, regularContent, state, emitter)

            // 处理工具调用
            val toolCallsDeltas = delta.optJSONArray("tool_calls")
            if (toolCallsDeltas != null && toolCallsDeltas.length() > 0 && enableToolCall) {
                processToolCallsDelta(toolCallsDeltas, state, emitter)
            }

            // 处理完成原因
            if (finishReason.isNotEmpty()) {
                handleFinishReason(finishReason, state, emitter, onTokensUpdated)
            }
        }
        // 处理message格式（非流式响应）
        else {
            val message = choice.optJSONObject("message")
            if (message != null) {
                val reasoningContent = message.optString("reasoning_content", "").ifBlank {
                    message.optString("reasoning", "")
                }
                val regularContent = message.optString("content", "")
                processContentDelta(reasoningContent, regularContent, state, emitter)
            }
        }

        applyUsageToCounters(usage, onTokensUpdated, onUsageReported, attemptNumber)
    }

    /**
     * 启动流式响应停滞看门狗：只有收到 `data:` 有效载荷才算有进度。
     *
     * 停滞超时后关闭当前响应（并兜底 cancel 当前 Call），让阻塞中的 `readLine()` 抛
     * IOException，从而进入既有的重试与原子回滚链路；用户手动取消走各自的路径，互不影响。
     * 调用方负责在结束时取消返回的任务。
     */
    private fun startStreamStallWatchdog(progress: StreamProgress): ScheduledFuture<*> =
        streamStallWatchdogExecutor.scheduleWithFixedDelay(
            {
                // 看门狗自身不能抛异常，否则会被调度器静默吞掉后续检查。
                runCatching {
                    val idleMs = streamNowMs() - progress.lastProgressAt.get()
                    if (idleMs < progress.stallTimeoutMs.get()) {
                        return@runCatching
                    }
                    // 先处置再打日志：日志本身可能失败（例如单测线程上没有 Android Log 实现），
                    // 不能因此跳过关闭连接。
                    // 置位必须在关连接之前：close 会立刻唤醒阻塞的读协程，异常先到就会把这次停滞
                    // 误判成普通网络错误。
                    stallCloseRequested.set(true)
                    runCatching { activeResponse?.close() }
                    runCatching { activeCall?.cancel() }
                    if (!progress.stallReported) {
                        progress.stallReported = true
                        runCatching {
                            // 先读基准再读累计值：中途若恰好落下一个数据块，得到的是偏大而非负值
                            val baseline = progress.nonDataLineCountAtLastData.get()
                            val heartbeats =
                                (progress.nonDataLineCount.get() - baseline).coerceAtLeast(0)
                            AppLogger.w(
                                "AIService",
                                "【发送消息】流式响应已停滞 ${idleMs}ms 未收到数据块" +
                                    "（期间收到 $heartbeats 行非数据行），关闭连接以触发重试"
                            )
                        }
                    }
                    // 每轮重新取当前连接：重试会替换 activeResponse/activeCall，只关本次的。
                    // 关一次没唤醒阻塞读时下一秒继续兜底，直到读取协程结束并取消本任务。
                }
            },
            STREAM_STALL_CHECK_INTERVAL_MS,
            STREAM_STALL_CHECK_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        )

    /**
     * 处理 OpenAI 流式响应
     */
    private suspend fun processStreamingResponse(
        reader: java.io.BufferedReader,
        emitter: StreamEmitter,
        onTokensUpdated: suspend (input: Int, cachedInput: Int, output: Int) -> Unit,
        context: Context,
        onUsageReported: (suspend (com.ai.assistance.operit.data.stats.ProviderUsageSnapshot, attempt: Int) -> Unit)? = null,
        attemptNumber: Int = 1
    ) {
        val state = StreamingState()
        // 只认 data: 有效载荷的进度时间戳：OkHttp 的 readTimeout 会被任意字节（空行、注释心跳）
        // 重置，覆盖不了“连接活着但不再产生数据”的死连接。
        val progress = StreamProgress()
        // 每次 attempt 复位：provider 实例会被同一会话反复复用，上一次尝试留下的置位会把
        // 下一个请求的普通失败误记成停滞。
        stallCloseRequested.set(false)
        val stallWatchdog = startStreamStallWatchdog(progress)

        // 记一次进度，并在真正产生内容后把首包宽限收紧到常规块间隔：role-only / usage-only
        // 这类空壳数据块不算内容，否则宽限会在最需要它的时候提前失效。
        fun markProgress() {
            progress.lastProgressAt.set(streamNowMs())
            val hasPayload =
                state.hasEmittedRegularContent ||
                    state.reasoningObserved ||
                    // Chat Completions 的思考增量走 processContentDelta，不置位 reasoningObserved，
                    // 靠这两个标记识别，否则纯思考流会一直停在首包宽限上
                    state.isInReasoningMode ||
                    state.streamedReasoningContentLength > 0 ||
                    state.accumulatedToolCalls.isNotEmpty() ||
                    state.imageBuffers.isNotEmpty()
            if (hasPayload && progress.stallTimeoutMs.get() != STREAM_STALL_TIMEOUT_MS) {
                progress.stallTimeoutMs.set(STREAM_STALL_TIMEOUT_MS)
            }
        }

        try {
            // 使用 while 循环读取流式响应
            while (true) {
                val line = reader.readLine() ?: break

                if (!line.startsWith("data:")) {
                    progress.nonDataLineCount.incrementAndGet()
                    continue
                }
                val nowMs = streamNowMs()
                val idleMs = nowMs - progress.lastProgressAt.get()
                progress.lastProgressAt.set(nowMs)
                // 记录此刻的非数据行累计值，停滞告警只报这一段窗口内的心跳行数
                progress.nonDataLineCountAtLastData.set(progress.nonDataLineCount.get())
                
                val data = line.substring(5).trim()
                if (data == "[DONE]") {
                    flushImageBuffers(state, emitter)
                    closeReasoningBlockIfOpen(state, emitter)
                    finalizeOpenToolSequence(state, emitter)
                    AppLogger.d("AIService", "【发送消息】收到流结束标记[DONE]")
                    break
                }

                state.chunkCount++
                // 每 10 个数据块记一次心跳；卡住时最后一条心跳里的块间隔就是现场
                if (state.chunkCount % 10 == 0) {
                    AppLogger.d(
                        "AIService",
                        "【发送消息】已接收 ${state.chunkCount} 个数据块，距上一块 ${idleMs}ms"
                    )
                }

                try {
                    val jsonResponse = JSONObject(data)
                    throwIfOpenAiErrorPayload(context, jsonResponse)
                    rejectRepetitionTruncation(context, jsonResponse)

                    if (useResponsesApi) {
                        processResponsesStreamingEvent(context, jsonResponse, state, emitter, onTokensUpdated, onUsageReported, attemptNumber)
                        markProgress()
                        continue
                    }

                    if (!jsonResponse.has("choices")) {
                        val handled = tryHandleOpenAiImageResponse(jsonResponse, emitter, state)
                        if (handled) {
                            markProgress()
                            continue
                        }
                    }
                    processResponseChunk(jsonResponse, state, emitter, onTokensUpdated, onUsageReported, attemptNumber)
                    // 处理完成后再刷新一次：下游（UI/落盘）很慢时不要把处理耗时算成网络停滞。
                    markProgress()
                } catch (e: IOException) {
                    throw e
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.w("AIService", "【发送消息】JSON解析错误: ${e.message}")
                    logLargeString("AIService", data, "[Send message] Original data when JSON parsing failed: ")
                }
            }

            // 流已正常读完：立刻停掉看门狗，避免在收尾（下游 flush）期间误报停滞并关连接。
            stallWatchdog.cancel(false)

            // Some OpenAI-compatible endpoints terminate the SSE body without a [DONE] sentinel.
            // Close any stateful reasoning envelope before returning so it cannot leak into the
            // next enhanced-conversation round when display content is concatenated.
            closeReasoningBlockIfOpen(state, emitter)
            finalizeOpenToolSequence(state, emitter)

            AppLogger.d(
                "AIService",
                "【发送消息】响应流处理完成，总块数: ${state.chunkCount}，输出token: ${tokenCacheManager.outputTokenCount}"
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 协程被取消（外层 scope 取消），直接退出
            AppLogger.d("AIService", "【发送消息】协程已取消")
            throw e
        } catch (e: IOException) {
            // 捕获IO异常，可能是由于 response.close() 导致的取消，也可能是网络中断
            if (isManuallyCancelled) {
                AppLogger.d("AIService", "【发送消息】流式传输已被用户取消")
                throw UserCancellationException(context.getString(R.string.openai_error_request_cancelled), e)
            } else {
                // 网络中断，准备重试
                AppLogger.e("AIService", "【发送消息】流式读取时发生IO异常，准备重试", e)
                throw e
            }
        } finally {
            stallWatchdog.cancel(false)
            runCatching { flushImageBuffers(state, emitter) }
            // 确保 reader 被关闭
            try {
                reader.close()
            } catch (ignored: Exception) {
            }
        }
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
        val effectiveStream = stream || requiresStreamingResponse ||
            providerType == ApiProviderType.OPENCODE_ZEN_FREE
        val eventChannel = MutableSharedStream<TextStreamEvent>(replay = Int.MAX_VALUE)
        val responseStream = stream {
            val logicalRequestId = UUID.randomUUID().toString()
            isManuallyCancelled = false
            // 重置输出token计数（输入token由TokenCacheManager管理）
            tokenCacheManager.addOutputTokens(-tokenCacheManager.outputTokenCount)
            onTokensUpdated(
                tokenCacheManager.totalInputTokenCount,
                tokenCacheManager.cachedInputTokenCount,
                tokenCacheManager.outputTokenCount
            )

            AppLogger.d(
                "AIService",
                "【发送消息】开始处理sendMessage请求，历史记录数量: ${chatHistory.size}，最后一条长度: ${chatHistory.lastOrNull()?.content?.length ?: 0}"
            )

            val maxRetries = LlmRetryPolicy.MAX_RETRY_ATTEMPTS
            var retryCount = 0
            var repetitionRetryCount = 0
            var lastException: Exception? = null

            // 用于保存当前 attempt 已接收到的内容；一旦需要重试，会整体回滚到请求起点
            val receivedContent = StringBuilder()
            val emitter = StreamEmitter(receivedContent, ::emit, eventChannel, onTokensUpdated)
            val requestSavepointId = "attempt_${UUID.randomUUID().toString().replace("-", "")}"
            emitter.emitSavepoint(requestSavepointId)

            // 停滞重试单独计数：不占用普通网络错误的重试额度
            var stallRetryCount = 0

            while (retryCount <= maxRetries) {
                // 在循环开始时检查是否已被取消
                checkCancellation(context)

                try {
                    if (retryCount > 0) {
                        AppLogger.d(
                            "AIService",
                            "【重试】原子回滚后重新请求，本轮已撤回内容长度: ${receivedContent.length}"
                        )
                    }

                    val currentHistory = chatHistory

                AppLogger.d(
                    "AIService",
                    "【发送消息】准备构建请求体，模型参数数量: ${modelParameters.size}，已启用参数: ${modelParameters.count { it.isEnabled }}"
                )
                // 直接传递原始历史记录给createRequestBody，让具体的Provider决定如何处理（例如Deepseek需要保留<think>标签）
                val requestBody = withContext(Dispatchers.IO) { createRequestBody(
                    context,
                    currentHistory,
                    modelParameters,
                    enableThinking,
                    effectiveStream,
                    availableTools,
                    preserveThinkInHistory
                ) }
                onTokensUpdated(
                    tokenCacheManager.totalInputTokenCount,
                    tokenCacheManager.cachedInputTokenCount,
                    tokenCacheManager.outputTokenCount
                )
                val attemptNumber = retryCount + 1
                val requestTraceId = "llm_${attemptNumber}_${UUID.randomUUID().toString().substring(0, 8)}"
                val request = createRequest(requestBody, requestTraceId, effectiveStream, attemptNumber, logicalRequestId)
                AppLogger.d(
                    "AIService",
                    "[req=$requestTraceId] 【发送消息】请求体构建完成，目标模型: $modelName，API端点: $apiEndpoint"
                )

                AppLogger.d("AIService", "[req=$requestTraceId] 【发送消息】准备连接到AI服务...")

                // 创建Call对象并保存到activeCall中，以便可以取消
                val call = client.newCall(request)
                activeCall = call

                AppLogger.d("AIService", "[req=$requestTraceId] 【发送消息】正在建立连接到服务器...")

                // 确保在IO线程执行网络请求和响应体读取
                AppLogger.d("AIService", "[req=$requestTraceId] 【发送消息】切换到IO线程执行网络请求")
                withContext(Dispatchers.IO) {
                    val executeStartNs = System.nanoTime()
                    AppLogger.d("AIService", "[req=$requestTraceId] 【发送消息】进入 call.execute()，开始等待响应头")
                    val response = call.execute()
                    val executeElapsedMs = (System.nanoTime() - executeStartNs) / 1_000_000
                    AppLogger.d("AIService", "[req=$requestTraceId] 【发送消息】call.execute() 返回，耗时=${executeElapsedMs}ms")

                    // 保存response引用，以便取消时能强制关闭
                    activeResponse = response

                    try {
                        if (!response.isSuccessful) {
                            val errorBody =
                                response.body?.string()
                                    ?: context.getString(R.string.openai_error_no_error_details)
                            AppLogger.e(
                                "AIService",
                                "【发送消息】API请求失败，状态码: ${response.code}，错误信息: $errorBody"
                            )
                            // 4xx错误仍保留单独的异常类型，具体是否重试由统一策略决定
                            if (response.code in 400..499) {
                                val errorMessage =
                                    if (providerType == ApiProviderType.OPENCODE_ZEN_FREE &&
                                        response.code == 403 &&
                                        apiKeyProvider.getApiKey().isBlank()) {
                                        context.getString(R.string.provider_opencode_zen_free_anonymous_denied)
                                    } else {
                                        context.getString(R.string.openai_error_api_request_failed_with_status, response.code, errorBody)
                                    }
                                throw NonRetriableException(
                                    errorMessage,
                                    statusCode = response.code
                                )
                            }
                            // 对于5xx等服务端错误，允许重试
                            throw IOException(context.getString(R.string.openai_error_api_request_failed_with_status, response.code, errorBody))
                        }

                        AppLogger.d(
                            "AIService",
                            "[req=$requestTraceId] 【发送消息】连接成功(状态码: ${response.code})，准备处理响应..."
                        )
                        val responseBody = response.body ?: throw IOException(context.getString(R.string.openai_error_response_empty))

                        // 根据stream参数处理响应
                        if (effectiveStream) {
                            AppLogger.d("AIService", "[req=$requestTraceId] 【发送消息】开始读取流式响应")
                            val reader = responseBody.charStream().buffered()
                            processStreamingResponse(
                                reader,
                                emitter,
                                onTokensUpdated,
                                context,
                                onUsageReported,
                                attemptNumber
                            )
                        } else {
                            AppLogger.d("AIService", "[req=$requestTraceId] 【发送消息】开始读取非流式响应")
                            val responseText = responseBody.string()
                            AppLogger.d("AIService", "[req=$requestTraceId] 收到完整响应，长度: ${responseText.length}")

                            var hasEmittedRegularContent = false

                            try {
                                val jsonResponse = JSONObject(responseText)
                                throwIfOpenAiErrorPayload(context, jsonResponse)
                                val handledImages = tryHandleOpenAiImageResponse(jsonResponse, emitter, null)

                                if (useResponsesApi) {
                                    val parsed = OpenAIResponsesPayloadAdapter.parseNonStreamingResponse(jsonResponse)

                                    parsed.reasoningChunks.forEach { reasoningChunk ->
                                        if (reasoningChunk.isNotEmpty()) {
                                            emitter.emitThinkContent(reasoningChunk)
                                        }
                                    }
                                    parsed.reasoningMetadataTags.forEach { metadataTag ->
                                        emitter.emitTag(metadataTag)
                                    }

                                    if (!handledImages) {
                                        parsed.textChunks.forEach { textChunk ->
                                            if (textChunk.isNotEmpty()) {
                                                hasEmittedRegularContent = true
                                                emitter.emitContent(textChunk)
                                            }
                                        }
                                    }

                                    if (parsed.toolCalls.length() > 0 && enableToolCall) {
                                        val xmlToolCalls = convertToolCallsToXml(parsed.toolCalls)
                                        if (xmlToolCalls.isNotEmpty()) {
                                            emitter.emitContent(xmlToolCalls)
                                            AppLogger.d(
                                                "AIService",
                                                "Tool Call转XML (Responses非流式): $xmlToolCalls"
                                            )
                                        }
                                    }
                                } else if (!handledImages) {
                                    val choices = jsonResponse.getJSONArray("choices")

                                    if (choices.length() > 0) {
                                        val choice = choices.getJSONObject(0)
                                        rejectRepetitionTruncation(context, jsonResponse)
                                        val messageObj = choice.optJSONObject("message")

                                        if (messageObj != null) {
                                            // 检查是否有tool_calls（Tool Call API）
                                            val toolCalls = messageObj.optJSONArray("tool_calls")
                                            if (toolCalls != null && toolCalls.length() > 0 && enableToolCall) {
                                                val xmlToolCalls = convertToolCallsToXml(toolCalls)
                                                if (xmlToolCalls.isNotEmpty()) {
                                                    emitter.emitContent(xmlToolCalls)
                                                    AppLogger.d(
                                                        "AIService",
                                                        "Tool Call转XML (非流式): $xmlToolCalls"
                                                    )
                                                }
                                            }

                                            val reasoningContent =
                                                messageObj.optString("reasoning_content", "")
                                            val regularContent = messageObj.optString("content", "")

                                            // 处理思考内容（如果有）
                                            if (reasoningContent.isNotNullOrEmpty() && !hasEmittedRegularContent) {
                                                emitter.emitThinkContent(reasoningContent)
                                            }

                                            // 处理常规内容
                                            if (regularContent.isNotNullOrEmpty()) {
                                                hasEmittedRegularContent = true
                                                emitter.emitContent(regularContent)
                                            }
                                        }
                                    }
                                }

                                applyUsageToCounters(jsonResponse.optJSONObject("usage"), onTokensUpdated, onUsageReported, attemptNumber)

                                AppLogger.d("AIService", "[req=$requestTraceId] 【发送消息】非流式响应处理完成")
                            } catch (e: IOException) {
                                throw e
                            } catch (e: Exception) {
                                AppLogger.e("AIService", "【发送消息】解析非流式响应失败", e)
                                throw IOException(context.getString(R.string.openai_error_parse_response_failed, e.message ?: ""), e)
                            }
                        }
                    } finally {
                        response.close()
                        AppLogger.d("AIService", "[req=$requestTraceId] 【发送消息】关闭响应连接")
                    }
                }

                // 清理活跃引用
                activeCall = null
                activeResponse = null
                AppLogger.d("AIService", "【发送消息】响应处理完成，已清理活跃引用")
                logFinalOutput("AIService", receivedContent, "Final output summary: ")

                // 成功处理后返回
                AppLogger.d(
                    "AIService",
                    "【发送消息】请求成功完成，输入token: ${tokenCacheManager.totalInputTokenCount}(缓存:${tokenCacheManager.cachedInputTokenCount})，输出token: ${tokenCacheManager.outputTokenCount}"
                )
                return@stream
            } catch (e: Exception) {
                lastException = e
                emitter.emitRollback(requestSavepointId)
                if (e is RepetitionTruncationException) {
                    if (!enableRetry || repetitionRetryCount >= 1 || retryCount >= maxRetries) throw e
                    repetitionRetryCount++
                    retryCount++
                    AppLogger.w("AIService", "Repetition truncation: discarded response; retrying once")
                    onNonFatalError(context.getString(R.string.openai_retry_repetition_truncation))
                    continue
                }
                // 停滞单独限次：每次停滞都要等满超时，全量重试会把用户晾十几分钟
                // 取消类异常与手动取消优先：看门狗恰好与用户取消重合时，不能把取消改写成停滞错误
                val cancelled =
                    e is UserCancellationException || e is CancellationException || isManuallyCancelled
                if (!cancelled && stallCloseRequested.compareAndSet(true, false)) {
                    stallRetryCount++
                    if (stallRetryCount > MAX_STREAM_STALL_RETRIES) {
                        AppLogger.e(
                            "AIService",
                            "【发送消息】连续 $stallRetryCount 次流式停滞，停止重试"
                        )
                        // 用停滞专属文案，避免复用「已重试 5 次」这种与实际次数不符的提示
                        throw IOException(
                            context.getString(
                                R.string.openai_error_stream_stalled,
                                stallRetryCount - 1,
                                resolveRetryErrorText(context, e)
                            ),
                            e
                        )
                    }
                }
                retryCount = handleRetryableError(
                    context,
                    e,
                    retryCount,
                    maxRetries,
                    enableRetry,
                    onNonFatalError
                ) { errorText, retryNumber ->
                    "【${context.getString(R.string.openai_retry_with_count, errorText, retryNumber)}】"
                }
            }
            }

            // 所有重试都失败
            lastException?.let { ex ->
                AppLogger.e(
                    "AIService",
                    "【发送消息】重试失败，请检查网络连接，最大重试次数: $maxRetries",
                    ex
                )
            } ?: AppLogger.e(
                "AIService",
                "【发送消息】重试失败，请检查网络连接，最大重试次数: $maxRetries"
            )
            throw IOException(
                context.getString(
                    R.string.openai_error_connection_timeout,
                    maxRetries,
                    lastException?.message ?: context.getString(R.string.openai_error_network_interrupted)
                ),
                lastException
            )
        }
        return responseStream.withEventChannel(eventChannel)
    }
}

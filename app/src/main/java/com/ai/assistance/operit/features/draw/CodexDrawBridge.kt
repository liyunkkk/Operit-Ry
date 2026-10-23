package com.ai.assistance.operit.features.draw

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Base64
import com.ai.assistance.operit.data.api.CodexAuthManager
import com.ai.assistance.operit.data.api.CodexHttpException
import com.ai.assistance.operit.data.api.CodexOAuthProtocol
import com.ai.assistance.operit.data.api.isCodexExpiredToken
import com.ai.assistance.operit.util.ImageSourcePathPolicy
import com.ai.assistance.operit.util.OperitPaths
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** Keeps Codex credentials and image bytes in the host; the package only receives saved file paths. */
object CodexDrawBridge {
    private const val ENDPOINT_BASE = "https://chatgpt.com/backend-api/codex"
    private const val MAX_INPUT_BYTES = 20 * 1024 * 1024
    private const val MAX_REFERENCE_IMAGES = 5
    private const val MAX_OUTPUT_BYTES = 30 * 1024 * 1024
    private const val REQUEST_TIMEOUT_MINUTES = 25L
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(2, TimeUnit.MINUTES)
        .callTimeout(REQUEST_TIMEOUT_MINUTES, TimeUnit.MINUTES)
        .build()

    suspend fun execute(context: Context, parametersJson: String): String {
        val params = JSONObject(parametersJson)
        val prompt = params.optString("prompt").trim()
        require(prompt.isNotEmpty()) { "请提供绘图提示词" }
        require(prompt.length <= 12_000) { "绘图提示词过长" }
        val editPath = params.optString("image_path").trim()
        val isEdit = editPath.isNotEmpty()
        val referencePaths = params.optJSONArray("referenced_image_paths")?.let { paths ->
            require(paths.length() <= MAX_REFERENCE_IMAGES) { "参考图最多 $MAX_REFERENCE_IMAGES 张" }
            (0 until paths.length()).map { index ->
                val path = paths.opt(index)
                require(path is String && path.isNotBlank()) { "参考图路径必须是非空字符串" }
                path.trim()
            }
        } ?: run {
            require(!params.has("referenced_image_paths")) { "referenced_image_paths 必须是路径数组" }
            emptyList()
        }
        require(referencePaths.size + (if (isEdit) 1 else 0) <= MAX_REFERENCE_IMAGES) {
            "输入图片最多 $MAX_REFERENCE_IMAGES 张"
        }
        val size = option(params, "size", "1024x1024", setOf("1024x1024", "1024x1536", "1536x1024", "auto"))
        val quality = option(params, "quality", "auto", setOf("auto", "low", "medium", "high"))
        val background = option(params, "background", "auto", setOf("auto", "opaque", "transparent"))
        val imageModel = option(params, "model", "gpt-image-2", setOf("gpt-image-2"))
        val chatModel = params.optString("chat_model").trim().ifBlank { "gpt-5.5" }
        require(Regex("""gpt-[a-zA-Z0-9.\-]+""").matches(chatModel)) { "chat_model 格式无效" }

        val auth = CodexAuthManager.getInstance(context)
        require(auth.authState.value != null) { "请先在模型设置中登录 Codex" }
        val inputPaths = (if (isEdit) listOf(editPath) else emptyList()) + referencePaths
        val permittedRoots = inputImageRoots(context)
        val imageData = inputPaths.map { loadInputImage(it, permittedRoots) }
        // Codex sends any image-conditioned request to the edits endpoint, even when the intent is a new image.
        val hasImageInput = imageData.isNotEmpty()

        val direct = JSONObject()
            .put("model", imageModel)
            .put("prompt", prompt)
            .put("size", size)
            .put("quality", quality)
            .put("background", background)
            .put("output_format", "png")
        if (hasImageInput) {
            val images = JSONArray()
            imageData.forEach { images.put(JSONObject().put("image_url", it)) }
            direct.put("images", images)
        }
        val directPath = if (hasImageInput) "/images/edits" else "/images/generations"
        val imageBase64 = auth.withAuthRecovery { account ->
            val accessToken = account.accessToken
            val directResult = request(context, accessToken, account.accountId, account.residency, directPath, direct, false)
            if (directResult.status in setOf(404, 405, 501)) {
                val content = JSONArray().put(JSONObject().put("type", "input_text").put("text", prompt))
                imageData.forEach { content.put(JSONObject().put("type", "input_image").put("image_url", it)) }
                val imageTool = JSONObject()
                    .put("type", "image_generation")
                    .put("action", if (hasImageInput) "edit" else "generate")
                    .put("model", imageModel)
                    .put("size", size)
                    .put("quality", quality)
                    .put("background", background)
                    .put("output_format", "png")
                val responses = JSONObject()
                    .put("model", chatModel)
                    .put(
                        "instructions",
                        if (isEdit) "Edit the supplied image as requested by the user."
                        else "Create a new image as requested by the user. Treat supplied images as references, not edit targets."
                    )
                    .put("input", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
                    .put("tools", JSONArray().put(imageTool))
                    .put("tool_choice", JSONObject().put("type", "image_generation"))
                    .put("stream", true)
                    .put("store", false)
                val fallback = request(context, accessToken, account.accountId, account.residency, "/responses", responses, true)
                if (fallback.status !in 200..299) throw upstreamError(fallback)
                fallback.imageBase64 ?: throw IOException(fallback.error ?: "Codex 未返回图片")
            } else {
                if (directResult.status !in 200..299) throw upstreamError(directResult)
                directResult.imageBase64 ?: throw IOException("Codex 未返回图片")
            }
        }
        currentCoroutineContext().ensureActive()
        val bytes = decodeImage(imageBase64)
        val extension = imageExtension(bytes)
        val outputDir = codexDrawOutputDir()
        if (!outputDir.isDirectory && !outputDir.mkdirs()) throw IOException("无法创建绘图目录")
        val requestedName = params.optString("file_name").trim()
        val baseName = requestedName
            .replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_")
            .trim('.', ' ')
            .take(64)
            .ifBlank { "codex_image" }
        val file = File(outputDir, "${baseName}_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}.$extension")
        val temporary = File.createTempFile(".codex_draw_", ".tmp", outputDir)
        try {
            temporary.writeBytes(bytes)
            currentCoroutineContext().ensureActive()
            if (!temporary.renameTo(file)) throw IOException("无法保存生成的图片")
        } finally {
            temporary.delete()
        }
        val uri = Uri.fromFile(file).toString().replace("(", "%28").replace(")", "%29")
        return JSONObject()
            .put("success", true)
            .put("file_path", file.absolutePath)
            .put("file_uri", uri)
            .put("markdown", "![Codex 生成的图片]($uri)")
            .put("message", "图片已保存到 ${file.absolutePath}")
            .toString()
    }

    private fun option(params: JSONObject, name: String, defaultValue: String, allowed: Set<String>): String =
        params.optString(name).trim().ifBlank { defaultValue }.also {
            require(it in allowed) { "$name 不支持：$it" }
        }

    private fun loadInputImage(path: String, permittedRoots: List<File>): String {
        val localPath = if (path.startsWith("file://")) Uri.parse(path).path.orEmpty() else path
        val file = resolveScriptImagePath(localPath, permittedRoots)
        require(file.length() in 1..MAX_INPUT_BYTES.toLong()) { "参考图必须小于 20 MB" }
        val bytes = file.readBytes()
        val mime = when (imageExtension(bytes)) {
            "png" -> "image/png"
            "jpg" -> "image/jpeg"
            "webp" -> "image/webp"
            else -> throw IllegalArgumentException("不支持的参考图格式")
        }
        return "data:$mime;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}"
    }

    /**
     * Script-provided image paths stay inside permitted storage, matching the boundary the other
     * script image entries already use. The draw output directory is part of the allowlist so a
     * generated image can be edited again without copying it out of the app first.
     */
    private fun inputImageRoots(context: Context): List<File> =
        listOfNotNull(
            Environment.getExternalStorageDirectory(),
            OperitPaths.cleanOnExitInternalDir(context),
        ) + context.getExternalFilesDirs(null).filterNotNull() + context.externalCacheDirs.filterNotNull() +
            listOf(codexDrawOutputDir())

    private fun codexDrawOutputDir() = File(OperitPaths.pluginConfigDir("draw"), "codex_draw/draws")

    internal fun resolveScriptImagePath(path: String, permittedRoots: List<File>): File {
        require(File(path).isAbsolute) { "参考图路径必须是绝对路径" }
        val source = try {
            ImageSourcePathPolicy.resolve(path, permittedRoots)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("参考图必须位于允许的存储目录内")
        } catch (_: IOException) {
            throw IllegalArgumentException("无法读取参考图")
        }
        require(source.isFile && source.canRead()) { "无法读取参考图" }
        return source
    }

    private fun decodeImage(value: String): ByteArray {
        require(value.length <= MAX_OUTPUT_BYTES * 4 / 3 + 16) { "生成图片超过 30 MB" }
        val bytes = try {
            Base64.decode(value.substringAfter("base64,", value), Base64.DEFAULT)
        } catch (_: IllegalArgumentException) {
            throw IOException("Codex 返回的图片编码无效")
        }
        require(bytes.isNotEmpty() && bytes.size <= MAX_OUTPUT_BYTES) { "生成图片大小无效" }
        imageExtension(bytes)
        return bytes
    }

    private fun imageExtension(bytes: ByteArray): String = when {
        bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(
            byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
        ) -> "png"
        bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte() -> "jpg"
        bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "webp"
        else -> throw IOException("Codex 返回的不是受支持图片")
    }

    private data class ImageResponse(val status: Int, val imageBase64: String?, val error: String?)

    private suspend fun request(
        context: Context,
        token: String,
        accountId: String,
        residency: String?,
        path: String,
        payload: JSONObject,
        stream: Boolean,
    ): ImageResponse {
        val builder = Request.Builder()
            .url(ENDPOINT_BASE + path)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer $token")
            .header("ChatGPT-Account-ID", accountId)
            .header("originator", "codex_cli_rs")
            .header("User-Agent", "Operit/${context.packageName}")
            .header("Accept", if (stream) "text/event-stream" else "application/json")
            .header("OpenAI-Beta", "responses=experimental")
            .header("session-id", UUID.randomUUID().toString())
        residency?.let { builder.header("x-openai-internal-codex-residency", it) }
        val call = client.newCall(builder.build())
        val cancellationWatcher = CoroutineScope(currentCoroutineContext()).launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            call.execute().use { response ->
                val body = response.body ?: throw IOException("Codex 返回空响应")
                if (!response.isSuccessful) {
                    if (response.code == 401) {
                        throw CodexHttpException(401, isCodexExpiredToken(401, response.peekBody(16_384).string()))
                    }
                    val errorBody = body.source().readUtf8Line().orEmpty().take(1000)
                    return ImageResponse(response.code, null, extractError(errorBody))
                }
                if (!stream) {
                    val root = JSONObject(body.string())
                    val first = root.optJSONArray("data")?.optJSONObject(0)
                    return ImageResponse(response.code, first?.optString("b64_json")?.takeIf { it.isNotBlank() }, null)
                }
                var image: String? = null
                var failed = false
                var failureMessage: String? = null
                val source = body.source()
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data: ")) continue
                    val data = line.removePrefix("data: ").trim()
                    if (data == "[DONE]") break
                    val event = try { JSONObject(data) } catch (_: Exception) { continue }
                    when (event.optString("type")) {
                        "response.output_item.done" -> {
                            val item = event.optJSONObject("item")
                            if (item?.optString("type") == "image_generation_call") {
                                image = item.optString("result").takeIf { it.isNotBlank() } ?: image
                            }
                        }
                        "response.completed" -> {
                            val output = event.optJSONObject("response")?.optJSONArray("output")
                            for (index in 0 until (output?.length() ?: 0)) {
                                val item = output?.optJSONObject(index)
                                if (item?.optString("type") == "image_generation_call") {
                                    image = item.optString("result").takeIf { it.isNotBlank() } ?: image
                                }
                            }
                            return ImageResponse(response.code, image, null)
                        }
                        "response.failed" -> {
                            failed = true
                            failureMessage = event.optJSONObject("response")
                                ?.optJSONObject("error")?.optString("message")
                        }
                    }
                }
                return ImageResponse(response.code, if (failed) null else image, failureMessage)
            }
        } catch (error: InterruptedIOException) {
            currentCoroutineContext().ensureActive()
            throw IOException("Codex 生图请求超时，请稍后重试", error)
        } finally {
            cancellationWatcher.cancel()
        }
    }

    private fun extractError(body: String): String =
        try { JSONObject(body).optJSONObject("error")?.optString("message").orEmpty() }
        catch (_: Exception) { "" }

    private fun upstreamError(response: ImageResponse): IOException =
        IOException("Codex 生图请求失败（HTTP ${response.status}）${response.error?.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()}")
}

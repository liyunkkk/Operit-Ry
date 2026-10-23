package com.ai.assistance.operit.api.voice

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.preferences.SpeechServicesPreferences
import com.ai.assistance.operit.util.AppLogger
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject

class OpenAIRealtimeVoiceProvider(
    private val context: Context,
    private val endpointUrl: String,
    private val apiKey: String,
    private val model: String,
    initialVoiceId: String
) : VoiceService {

    companion object {
        private const val TAG = "OpenAIRealtimeVoiceProvider"
        private const val DEFAULT_TIMEOUT_SECONDS = 30L
        private const val OUTPUT_AUDIO_FORMAT = "audio/pcm"
        private const val OUTPUT_SAMPLE_RATE = 24_000
        private const val OUTPUT_CHANNEL_COUNT = 1

        val AVAILABLE_VOICES = listOf(
            VoiceService.Voice("alloy", "alloy", "en-US", "NEUTRAL"),
            VoiceService.Voice("ash", "ash", "en-US", "NEUTRAL"),
            VoiceService.Voice("ballad", "ballad", "en-US", "NEUTRAL"),
            VoiceService.Voice("cedar", "cedar", "en-US", "NEUTRAL"),
            VoiceService.Voice("coral", "coral", "en-US", "NEUTRAL"),
            VoiceService.Voice("echo", "echo", "en-US", "NEUTRAL"),
            VoiceService.Voice("marin", "marin", "en-US", "NEUTRAL"),
            VoiceService.Voice("sage", "sage", "en-US", "NEUTRAL"),
            VoiceService.Voice("shimmer", "shimmer", "en-US", "NEUTRAL"),
            VoiceService.Voice("verse", "verse", "en-US", "NEUTRAL")
        )
    }

    private val webSocketClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }

    private var voiceId: String = initialVoiceId

    private val _isInitialized = MutableStateFlow(false)
    override val isInitialized: Boolean
        get() = _isInitialized.value

    private val _isSpeaking = MutableStateFlow(false)
    override val isSpeaking: Boolean
        get() = _isSpeaking.value
    override val isPlayingThroughHeadphones get() = pcmPlayer?.isPlayingThroughHeadphones ?: false

    override val speakingStateFlow: Flow<Boolean> = _isSpeaking.asStateFlow()

    private val playbackMutex = Mutex()
    private val stateLock = Any()

    private var currentResponseDeferred: CompletableDeferred<Unit>? = null
    private var currentWebSocket: WebSocket? = null

    override suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (endpointUrl.isBlank()) {
                throw TtsException(context.getString(R.string.openai_realtime_tts_error_url_not_set))
            }
            if (!endpointUrl.startsWith("ws://") && !endpointUrl.startsWith("wss://")) {
                throw TtsException(context.getString(R.string.openai_realtime_tts_error_url_invalid_scheme))
            }
            if (apiKey.isBlank()) {
                throw TtsException(context.getString(R.string.openai_realtime_tts_error_api_key_not_set))
            }
            if (model.isBlank()) {
                throw TtsException(context.getString(R.string.openai_realtime_tts_error_model_not_set))
            }
            if (voiceId.isBlank()) {
                throw TtsException(context.getString(R.string.openai_realtime_tts_error_voice_not_set))
            }

            _isInitialized.value = true
            true
        } catch (e: Exception) {
            _isInitialized.value = false
            AppLogger.e(TAG, "OpenAI Realtime TTS initialize failed", e)
            if (e is TtsException) throw e
            throw TtsException(context.getString(R.string.openai_realtime_tts_error_init_failed), cause = e)
        }
    }

    @Volatile private var pcmPlayer: PcmSpeechPlayer? = null
    private val generation = java.util.concurrent.atomic.AtomicLong()

    override suspend fun speak(
        text: String,
        interrupt: Boolean,
        rate: Float?,
        pitch: Float?,
        extraParams: Map<String, String>
    ): Boolean = withContext(Dispatchers.IO) {
        if (interrupt) stop()
        val epoch = generation.get()
        playbackMutex.withLock {
            if (epoch != generation.get()) return@withLock false
            if (!isInitialized) {
                val initOk = initialize()
                if (!initOk) return@withLock false
            }

            try {

                val profile = com.ai.assistance.operit.data.preferences.SpeechServiceProfilesPreferences(context.applicationContext).getCurrentTtsProfile()
                val effectiveRate = rate ?: profile.speechRate
                val requestModel = extraParams["model"]?.takeIf { it.isNotBlank() } ?: model
                val requestVoice = extraParams["voice"]?.takeIf { it.isNotBlank() } ?: voiceId
                val speed = effectiveRate.coerceIn(0.25f, 1.5f)

                val timing = com.ai.assistance.operit.api.speech.VoiceTiming("realtime_synthesis")
                timing.mark("request_started")
                val player = PcmSpeechPlayer(24000) {
                    _isSpeaking.value = it
                    if (it) timing.mark("first_playback")
                }
                pcmPlayer = player
                try {
                    requestAudio(text, requestModel, requestVoice, speed, player, epoch)
                    return@withLock epoch == generation.get() && player.drain()
                } finally {
                    player.close()
                    timing.mark("playback_ended")
                    if (pcmPlayer === player) pcmPlayer = null
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (epoch != generation.get()) return@withLock false
                AppLogger.e(TAG, "OpenAI Realtime TTS speak failed", e)
                if (e is TtsException) throw e
                throw TtsException(context.getString(R.string.openai_realtime_tts_error_request_failed), cause = e)
            }
        }
    }

    private suspend fun requestAudio(
        text: String,
        requestModel: String,
        requestVoice: String,
        speed: Float,
        player: PcmSpeechPlayer,
        epoch: Long
    ): Unit = withContext(Dispatchers.IO) {
        val deferred = CompletableDeferred<Unit>()
        val received = java.util.concurrent.atomic.AtomicBoolean(false)
        val realtimeUrl = buildRealtimeUrl(endpointUrl, requestModel)

        synchronized(stateLock) {
            currentResponseDeferred = deferred
        }

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (epoch != generation.get()) { webSocket.cancel(); return }
                synchronized(stateLock) {
                    currentWebSocket = webSocket
                }

                if (!webSocket.send(buildConversationItemCreateEvent(text).toString())) {
                    failResponse(deferred, context.getString(R.string.openai_realtime_tts_error_send_failed))
                    webSocket.cancel()
                    return
                }

                if (!webSocket.send(buildResponseCreateEvent(requestVoice, speed).toString())) {
                    failResponse(deferred, context.getString(R.string.openai_realtime_tts_error_send_failed))
                    webSocket.cancel()
                }
            }

            override fun onMessage(webSocket: WebSocket, textMessage: String) {
                if (deferred.isCompleted || epoch != generation.get()) return
                val json = runCatching { JSONObject(textMessage) }.getOrNull() ?: return
                val type = json.optString("type")

                when (type) {
                    "error" -> {
                        val error = json.optJSONObject("error")
                        val message = error?.optString("message").orEmpty()
                            .ifBlank { context.getString(R.string.openai_realtime_tts_error_request_failed) }
                        failResponse(deferred, message)
                        webSocket.cancel()
                    }
                    // OpenAI docs currently show both response.output_audio.* and response.audio.* names.
                    "response.output_audio.delta", "response.audio.delta" -> {
                        val delta = json.optString("delta")
                        if (delta.isNotBlank()) {
                            runCatching {
                                val chunk = Base64.decode(delta, Base64.DEFAULT)
                                player.write(chunk)
                                if (chunk.isNotEmpty()) received.set(true)
                            }.onFailure {
                                failResponse(
                                    deferred,
                                    context.getString(R.string.openai_realtime_tts_error_request_failed)
                                )
                                webSocket.cancel()
                            }
                        }
                    }
                    "response.output_audio.done", "response.audio.done" -> {
                        completeAudioResponse(deferred, received.get())
                        webSocket.close(1000, "audio_complete")
                    }
                    "response.done" -> {
                        val responseJson = json.optJSONObject("response")
                        val status = responseJson?.optString("status").orEmpty()
                        if (status.equals("failed", ignoreCase = true)) {
                            val details = responseJson.optJSONObject("status_details")
                            val errorMessage = details?.optString("error").orEmpty()
                                .ifBlank { context.getString(R.string.openai_realtime_tts_error_request_failed) }
                            failResponse(deferred, errorMessage)
                            webSocket.cancel()
                        } else if (!deferred.isCompleted) {
                            completeAudioResponse(deferred, received.get())
                            webSocket.close(1000, "response_complete")
                        }
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                synchronized(stateLock) {
                    if (currentWebSocket === webSocket) {
                        currentWebSocket = null
                    }
                    if (currentResponseDeferred === deferred) {
                        currentResponseDeferred = null
                    }
                }

                if (deferred.isCompleted) {
                    return
                }

                val errorMessage =
                    runCatching {
                        response?.body?.string()
                    }.getOrNull().orEmpty().ifBlank {
                        t.message ?: context.getString(R.string.openai_realtime_tts_error_request_failed)
                    }

                deferred.completeExceptionally(
                    TtsException(
                        message = errorMessage,
                        httpStatusCode = response?.code,
                        cause = t
                    )
                )
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                synchronized(stateLock) {
                    if (currentWebSocket === webSocket) {
                        currentWebSocket = null
                    }
                    if (currentResponseDeferred === deferred) {
                        currentResponseDeferred = null
                    }
                }

                if (!deferred.isCompleted) {
                    failResponse(deferred, "Audio connection closed before completion")
                }
            }
        }

        val request = Request.Builder()
            .url(realtimeUrl)
            .addHeader("Authorization", "Bearer $apiKey")
            .build()

        val webSocket = webSocketClient.newWebSocket(request, listener)
        synchronized(stateLock) {
            currentWebSocket = webSocket
        }

        try {
            kotlinx.coroutines.withTimeout(180_000) { deferred.await() }
        } finally {
            webSocket.cancel()
            synchronized(stateLock) {
                if (currentWebSocket === webSocket) {
                    currentWebSocket = null
                }
                if (currentResponseDeferred === deferred) {
                    currentResponseDeferred = null
                }
            }
        }
    }

    private fun completeAudioResponse(
        deferred: CompletableDeferred<Unit>,
        received: Boolean
    ) {
        if (deferred.isCompleted) return
        if (received) {
            deferred.complete(Unit)
        } else {
            deferred.completeExceptionally(
                TtsException(context.getString(R.string.openai_realtime_tts_error_empty_audio))
            )
        }
    }

    private fun failResponse(
        deferred: CompletableDeferred<Unit>,
        message: String
    ) {
        if (!deferred.isCompleted) {
            deferred.completeExceptionally(TtsException(message))
        }
    }

    private fun buildRealtimeUrl(baseUrl: String, model: String): String {
        val uri = Uri.parse(baseUrl)
        val builder = uri.buildUpon().clearQuery()
        val queryNames = uri.queryParameterNames
        queryNames.forEach { key ->
            if (!key.equals("model", ignoreCase = true)) {
                uri.getQueryParameters(key).forEach { value ->
                    builder.appendQueryParameter(key, value)
                }
            }
        }
        builder.appendQueryParameter("model", model)
        return builder.build().toString()
    }

    private fun buildConversationItemCreateEvent(text: String): JSONObject {
        return JSONObject().apply {
            put("type", "conversation.item.create")
            put(
                "item",
                JSONObject().apply {
                    put("type", "message")
                    put("role", "user")
                    put(
                        "content",
                        JSONArray().put(
                            JSONObject().apply {
                                put("type", "input_text")
                                put("text", text)
                            }
                        )
                    )
                }
            )
        }
    }

    private fun buildResponseCreateEvent(voice: String, speed: Float): JSONObject {
        return JSONObject().apply {
            put("type", "response.create")
            put(
                "response",
                JSONObject().apply {
                    put("output_modalities", JSONArray().put("audio"))
                    put(
                        "audio",
                        JSONObject().apply {
                            put(
                                "output",
                                JSONObject().apply {
                                    put("format", JSONObject().put("type", OUTPUT_AUDIO_FORMAT))
                                    put("voice", buildVoiceValue(voice))
                                    put("speed", speed.toDouble())
                                }
                            )
                        }
                    )
                }
            )
        }
    }

    private fun buildVoiceValue(voice: String): Any {
        return if (voice.startsWith("voice_")) {
            JSONObject().put("id", voice)
        } else {
            voice
        }
    }

    override suspend fun stop(): Boolean {
        generation.incrementAndGet()
        pcmPlayer?.close()
        synchronized(stateLock) {
            currentWebSocket?.cancel()
            currentWebSocket = null
            currentResponseDeferred?.cancel()
            currentResponseDeferred = null
        }
        _isSpeaking.value = false
        return true
    }

    override suspend fun pause(): Boolean = pcmPlayer?.pause() ?: false
    override suspend fun resume(): Boolean = pcmPlayer?.resume() ?: false

    override fun shutdown() {
        generation.incrementAndGet()
        pcmPlayer?.close()
        synchronized(stateLock) {
            currentWebSocket?.cancel()
            currentWebSocket = null
            currentResponseDeferred?.cancel()
            currentResponseDeferred = null
        }
        _isSpeaking.value = false
        _isInitialized.value = false
    }

    override suspend fun getAvailableVoices(): List<VoiceService.Voice> {
        return AVAILABLE_VOICES
    }

    override suspend fun setVoice(voiceId: String): Boolean = withContext(Dispatchers.IO) {
        this@OpenAIRealtimeVoiceProvider.voiceId = voiceId
        true
    }
}

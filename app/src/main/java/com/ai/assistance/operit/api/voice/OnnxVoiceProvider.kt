package com.ai.assistance.operit.api.voice

import android.content.Context
import com.ai.assistance.operit.api.speech.LocalVoiceModels
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.channels.Channel
import com.ai.assistance.operit.util.TtsSegmenter

internal class OnnxVoiceProvider(private val context: Context, private val modelId: String,
                                 initialSpeaker: Int, private val defaultRate: Float) : VoiceService {
    private val nativeMutex = Mutex()
    private val generation = AtomicLong()
    private var engine: OfflineTts? = null
    @Volatile private var closed = false
    @Volatile private var player: PcmSpeechPlayer? = null
    private var speaker = initialSpeaker
    override var isInitialized = false
        private set
    override val speakingStateFlow = MutableStateFlow(false)
    override val supportsLongText = true
    override val isSpeaking get() = speakingStateFlow.value
    override val isPlayingThroughHeadphones get() = player?.isPlayingThroughHeadphones ?: false
    override suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        LocalVoiceModels.withModelFiles(context, modelId) { directory ->
        nativeMutex.withLock {
            check(!closed)
            if (engine == null) {
                val model = LocalVoiceModels.find(modelId).files.first { it.id.endsWith(".onnx") }.id
                engine = OfflineTts(config = OfflineTtsConfig(
                    model = OfflineTtsModelConfig(vits = OfflineTtsVitsModelConfig(
                        model = directory.resolve(model).absolutePath,
                        tokens = directory.resolve("tokens.txt").absolutePath,
                        lexicon = directory.resolve("lexicon.txt").absolutePath), numThreads = 2),
                    ruleFsts = listOf("date.fst","number.fst","phone.fst").joinToString(",") {
                        directory.resolve(it).absolutePath
                    }))
            }
            isInitialized = true
        }
        }
        true
    }
    override suspend fun speak(text: String, interrupt: Boolean, rate: Float?, pitch: Float?,
                               extraParams: Map<String,String>): Boolean = withContext(Dispatchers.IO) {
        if (interrupt) stop()
        val session = generation.get()
        if (!isInitialized) initialize()
        nativeMutex.withLock {
            if (session != generation.get() || closed) return@withLock false
            val tts = checkNotNull(engine)
            val job = currentCoroutineContext()
            val timing = com.ai.assistance.operit.api.speech.VoiceTiming("local_synthesis")
            timing.mark("synthesis_start")
            val sink = PcmSpeechPlayer(tts.sampleRate(), { !job.isActive }) {
                speakingStateFlow.value = it
                if (it) timing.mark("first_playback")
            }
            player = sink
            coroutineScope {
            // At most eight seconds queued, plus the current write. Inference can prepare the
            // next sentence while playback consumes the previous one without retaining a book.
            val audio = Channel<FloatArray>(4)
            val playback = launch(Dispatchers.IO) {
                try { for (samples in audio) sink.write(samples) }
                finally { audio.cancel() }
            }
            // Also runs if cancellation prevents the consumer from ever entering its body.
            playback.invokeOnCompletion { audio.cancel() }
            try {
                var callbackFailure: Throwable? = null
                val segments = TtsSegmenter.split(text).flatMap { it.chunked(160) }
                for (segment in segments) {
                job.ensureActive()
                if (session != generation.get()) break
                tts.generateWithCallback(segment, speaker.coerceIn(0,tts.numSpeakers()-1),
                    (rate ?: defaultRate).coerceIn(0.5f,2f), SherpaAudioCallback { samples ->
                    if (session != generation.get() || !job.isActive) 0
                    else try {
                        timing.mark("first_audio")
                        var offset = 0
                        while (offset < samples.size) {
                            if (session != generation.get() || !job.isActive)
                                throw CancellationException("Speech stopped")
                            val end = minOf(samples.size, offset + tts.sampleRate() * 2)
                            val chunk = samples.copyOfRange(offset, end)
                            // The consumer closes the channel on cancellation or playback error.
                            runBlocking { audio.send(chunk) }
                            offset = end
                        }
                        1
                    }
                    catch (failure: Throwable) {
                        // Never leave a pending Java exception across Sherpa's JNI callback.
                        callbackFailure = failure
                        0
                    }
                })
                callbackFailure?.let { throw it }
                }
                audio.close()
                playback.join()
                job.ensureActive()
                session == generation.get() && sink.drain()
            } finally {
                audio.cancel()
                playback.cancel()
                sink.close(); timing.mark("playback_ended"); if (player === sink) player = null
            }
            }
        }
    }
    override suspend fun stop(): Boolean {
        generation.incrementAndGet(); player?.close(); return true
    }
    override suspend fun pause() = player?.pause() ?: false
    override suspend fun resume() = player?.resume() ?: false
    override fun shutdown() {
        closed = true
        generation.incrementAndGet()
        player?.close()
        CoroutineScope(Dispatchers.IO).launch {
            nativeMutex.withLock { engine?.release(); engine = null; isInitialized = false }
        }
    }
    override suspend fun getAvailableVoices(): List<VoiceService.Voice> {
        if (!isInitialized) initialize()
        return nativeMutex.withLock {
            (0 until checkNotNull(engine).numSpeakers()).map {
                VoiceService.Voice(it.toString(),"${it + 1}", if (modelId=="melo-int8") "zh,en" else "zh","")
            }
        }
    }
    override suspend fun setVoice(voiceId: String): Boolean {
        val value = voiceId.toIntOrNull() ?: return false
        if (!isInitialized) initialize()
        return nativeMutex.withLock {
            if (value !in 0 until checkNotNull(engine).numSpeakers()) false
            else { speaker = value; true }
        }
    }
}

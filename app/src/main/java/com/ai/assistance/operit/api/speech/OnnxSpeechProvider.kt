package com.ai.assistance.operit.api.speech

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import com.ai.assistance.operit.R
import com.ai.assistance.operit.util.AppLogger
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** On-device utterance recognition. VAD endpoints audio; inference never runs on the UI thread. */
internal class OnnxSpeechProvider(private val context: Context, private val modelId: String) : SpeechService {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val nativeMutex = Mutex()
    private val lifecycle = Mutex()
    private val epoch = SpeechRequestEpoch()
    private var recognizer: OfflineRecognizer? = null
    private var record: AudioRecord? = null
    private var effects: CaptureAudioEffects? = null
    private var captureJob: Job? = null
    private var samples = FloatArray(0)
    private var length = 0
    private var segmentHasSpeech = false
    private var continuousDecoder: ContinuousSpeechDecoder? = null
    @Volatile private var closed = false
    override val isInitialized = MutableStateFlow(false)
    override val recognitionStateFlow = MutableStateFlow(SpeechService.RecognitionState.UNINITIALIZED)
    override val recognitionResultFlow = MutableStateFlow(SpeechService.RecognitionResult(""))
    override val recognitionErrorFlow = MutableStateFlow(SpeechService.RecognitionError(0, ""))
    override val volumeLevelFlow = MutableStateFlow(0f)
    override val speechActivityFlow = MutableStateFlow(false)
    override val currentState get() = recognitionStateFlow.value
    override val isRecognizing get() = currentState == SpeechService.RecognitionState.RECOGNIZING
    // Voice conversation keeps its single-utterance default; manual dictation opts into segments.
    override val supportsContinuousDictation = true

    override fun canStartWithoutPrompt(context: Context): Boolean =
        isInitialized.value || LocalVoiceModels.find(modelId)?.downloaded(context) == true

    override suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            LocalVoiceModels.withModelFiles(context, modelId) { directory ->
            nativeMutex.withLock {
                check(!closed) { "Speech service closed" }
                if (recognizer == null) {
                    val model = directory.resolve("model.int8.onnx").absolutePath
                    val config = OfflineModelConfig(
                        tokens = directory.resolve("tokens.txt").absolutePath, numThreads = 2,
                        provider = "cpu",
                    )
                    if (modelId == "sensevoice-int8") {
                        config.senseVoice = OfflineSenseVoiceModelConfig(model = model, language = "auto",
                            useInverseTextNormalization = true)
                    } else {
                        config.paraformer = OfflineParaformerModelConfig(model = model)
                        config.modelType = "paraformer"
                    }
                    recognizer = OfflineRecognizer(config = OfflineRecognizerConfig(modelConfig = config))
                }
                isInitialized.value = true
            }
            }
            recognitionStateFlow.value = SpeechService.RecognitionState.IDLE
            true
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { failure(e); false }
    }

    @SuppressLint("MissingPermission")
    override suspend fun startRecognition(languageCode: String, continuousMode: Boolean,
                                          partialResults: Boolean, audioSource: Int): Boolean =
        lifecycle.withLock {
            if (closed) return@withLock false
            if (isRecognizing || currentState == SpeechService.RecognitionState.PROCESSING) return@withLock false
            if (!isInitialized.value && !initialize()) return@withLock false
            val session = epoch.begin()
            recognitionResultFlow.value = SpeechService.RecognitionResult("")
            recognitionErrorFlow.value = SpeechService.RecognitionError(0, "")
            recognitionStateFlow.value = SpeechService.RecognitionState.PREPARING
            var vad: OnnxSileroVad? = null
            try {
                withContext(Dispatchers.IO) {
                    val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                    check(min > 0) { "Microphone unavailable" }
                    // Built before recording starts: while it loads, the microphone buffer would overflow.
                    val detector = OnnxSileroVad(context, speechDurationMs = 100,
                        silenceDurationMs = if (continuousMode) 2000 else 700).also { vad = it }
                    // Room for the audio that arrives while the handed-off samples are being checked
                    // (five seconds, enough for a long preroll on a slow phone).
                    val mic = AudioRecord(audioSource,16000,AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 2, 16000 * 2 * 5))
                    record = mic
                    check(mic.state == AudioRecord.STATE_INITIALIZED) { "Microphone unavailable" }
                    effects = CaptureAudioEffects(mic.audioSessionId)
                    // One extra frame avoids dropping the end of a read at the segment boundary.
                    samples = FloatArray(16000 * 60 + 512)
                    length = 0
                    segmentHasSpeech = false
                    val decoder = if (continuousMode) ContinuousSpeechDecoder(
                        scope = scope,
                        decode = { pcm -> decodeText(pcm, session) },
                        onResult = { text, final ->
                            epoch.publish(session) {
                                if (currentState == SpeechService.RecognitionState.RECOGNIZING ||
                                    currentState == SpeechService.RecognitionState.PROCESSING) {
                                    if (final) recognitionStateFlow.value = SpeechService.RecognitionState.IDLE
                                    recognitionResultFlow.value = SpeechService.RecognitionResult(text, final)
                                }
                            }
                        },
                        onFailure = { failCapture(session, it) },
                    ) else null
                    continuousDecoder = decoder
                    val preroll = SpeechPrerollStore.consumePending(audioSource)
                    mic.startRecording()
                    recognitionStateFlow.value = SpeechService.RecognitionState.RECOGNIZING
                    // Atomic start: the job always reaches `use` and closes the detector, even if cancelled first.
                    captureJob = scope.launch(start = CoroutineStart.ATOMIC) {
                        try {
                            detector.use { vad ->
                                val frame = ShortArray(512)
                                val loudness = SpeechLoudness()
                                var prerollAt = 0
                                // A stop right after start must still see all of the earlier audio, so
                                // that part is finished even once this job is cancelled.
                                while ((isActive || (preroll != null && prerollAt < preroll.size)) &&
                                    session == epoch.current()) {
                                    // Audio heard before this microphone opened goes through the same path first.
                                    val fromPreroll = preroll != null && prerollAt < preroll.size
                                    val count = if (preroll != null && prerollAt < preroll.size) {
                                        val n = minOf(frame.size, preroll.size - prerollAt)
                                        preroll.copyInto(frame, 0, prerollAt, prerollAt + n)
                                        prerollAt += n
                                        n
                                    } else mic.read(frame,0,frame.size)
                                    // Stopping the microphone wakes a pending read with nothing in it.
                                    // That is the end of capture, not a broken microphone.
                                    if (count <= 0 && (session != epoch.current() ||
                                            currentState != SpeechService.RecognitionState.RECOGNIZING)) break
                                    check(count > 0) { "Microphone read failed: $count" }
                                    // The meter shows what the microphone heard; detection and the
                                    // model get the levelled audio.
                                    var energy = 0f
                                    for (i in 0 until count) {
                                        val value = frame[i] / 32768f
                                        energy += value * value
                                    }
                                    loudness.apply(frame, count)
                                    for (i in 0 until count) {
                                        if (length < samples.size) samples[length++] = frame[i] / 32768f
                                    }
                                    // Match the other speech providers' -60..0 dB display scale.
                                    // Raw RMS makes normal speech almost invisible in the recorder.
                                    val rms = kotlin.math.sqrt(energy / count)
                                    val db = 20f * kotlin.math.log10(rms + 1e-6f)
                                    volumeLevelFlow.value = ((db + 60f) / 60f).coerceIn(0f, 1f)
                                    val voice = if (count == frame.size) vad.isSpeech(frame) else segmentHasSpeech
                                    if (voice) segmentHasSpeech = true
                                    speechActivityFlow.value = voice
                                    if ((segmentHasSpeech && !voice) || length >= 16000 * 60) {
                                        if (decoder == null) {
                                            scope.launch { stopRecognition(session) }
                                            break
                                        }
                                        if (segmentHasSpeech) {
                                            val sent = decoder.offer(samples.copyOf(length))
                                            // Earlier audio arrives faster than real time; with the queue
                                            // full, the segment keeps growing instead of failing the session.
                                            if (!sent && fromPreroll && length < 16000 * 60) continue
                                            check(sent) {
                                                context.getString(R.string.voice_dictation_cannot_keep_up)
                                            }
                                        }
                                        length = 0
                                        segmentHasSpeech = false
                                    }
                                    if (continuousMode && !segmentHasSpeech && length > 16000) {
                                        // Retain onset context, not minutes of silence for the model to hallucinate.
                                        samples.copyInto(samples, 0, length - 16000, length)
                                        length = 16000
                                    }
                                }
                            }
                        } catch (e: CancellationException) { throw e
                        } catch (e: Exception) {
                            failCapture(session, e)
                        }
                    }
                }
                true
            } catch (e: CancellationException) {
                if (captureJob == null) vad?.close()
                withContext(NonCancellable) { releaseCapture(); discardDecoder() }
                epoch.publish(session) {
                    recognitionStateFlow.value = SpeechService.RecognitionState.IDLE
                }
                throw e
            } catch (e: Exception) {
                if (captureJob == null) vad?.close()
                releaseCapture(); discardDecoder(); failure(e); false
            }
        }

    override suspend fun stopRecognition(): Boolean = stopRecognition(epoch.current())

    private suspend fun stopRecognition(expected: Long): Boolean {
        val captured = lifecycle.withLock {
            if (!isRecognizing || expected != epoch.current()) return false
            val session = epoch.current()
            recognitionStateFlow.value = SpeechService.RecognitionState.PROCESSING
            releaseCapture()
            val decoder = continuousDecoder
            // A manual stop may precede VAD's minimum speech duration. Keep a short audible tail
            // rather than losing e.g. a quick "yes". The audio is levelled, so the meter's -60 dB
            // floor is raised by the largest gain: silence lifted that far still counts as silence.
            val audibleTail = length > 0 &&
                (0 until length).sumOf { samples[it].toDouble() * samples[it] } / length >
                1e-6 * SpeechLoudness.MAX_GAIN * SpeechLoudness.MAX_GAIN
            val pcm = if (decoder == null || segmentHasSpeech || audibleTail) samples.copyOf(length) else null
            samples = FloatArray(0)
            length = 0
            Triple(session, pcm, decoder)
        }
        val decoder = captured.third
        return if (decoder != null) decoder.finish(captured.second)
            else decode(captured.second ?: FloatArray(0), captured.first)
    }

    private suspend fun decodeText(pcm: FloatArray, session: Long): String = nativeMutex.withLock {
        if (session != epoch.current() || pcm.isEmpty()) return@withLock ""
        val engine = checkNotNull(recognizer)
        val peak = pcm.maxOf { kotlin.math.abs(it) }
        AppLogger.d("OnnxSpeech", "Decoding ${pcm.size / 16} ms, peak ${"%.1f".format(20 * kotlin.math.log10(peak + 1e-6f))} dBFS")
        val stream = engine.createStream()
        try {
            stream.acceptWaveform(pcm, 16000)
            engine.decode(stream)
            engine.getResult(stream).text
        } finally { stream.release() }
    }

    private suspend fun decode(pcm: FloatArray, session: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            val text = decodeText(pcm, session)
            epoch.publish(session) {
                recognitionStateFlow.value = SpeechService.RecognitionState.IDLE
                recognitionResultFlow.value = SpeechService.RecognitionResult(text, true)
            }
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            epoch.publish(session) { failure(e) }
            false
        }
    }
    override suspend fun cancelRecognition() {
        val session = epoch.begin()
        lifecycle.withLock {
            if (session != epoch.current()) return
            releaseCapture()
            discardDecoder()
            samples = FloatArray(0)
            epoch.publish(session) {
                recognitionResultFlow.value = SpeechService.RecognitionResult("")
                recognitionStateFlow.value = SpeechService.RecognitionState.IDLE
            }
        }
    }
    private fun discardDecoder() {
        continuousDecoder?.cancel()
        continuousDecoder = null
    }

    private fun failCapture(session: Long, error: Exception) {
        scope.launch {
            lifecycle.withLock {
                if (session == epoch.current() &&
                    (isRecognizing || currentState == SpeechService.RecognitionState.PROCESSING)) {
                    releaseCapture()
                    discardDecoder()
                    samples = FloatArray(0)
                    length = 0
                    failure(error)
                }
            }
        }
    }
    private suspend fun releaseCapture() {
        runCatching { record?.stop() }
        captureJob?.cancelAndJoin()
        captureJob = null
        record?.release()
        record = null
        effects?.close()
        effects = null
        volumeLevelFlow.value = 0f
        speechActivityFlow.value = false
    }
    private fun failure(e: Exception) {
        AppLogger.e("OnnxSpeech", "Local recognition failed", e)
        recognitionStateFlow.value = SpeechService.RecognitionState.ERROR
        recognitionErrorFlow.value = SpeechService.RecognitionError(-1,e.message.orEmpty())
    }
    override fun shutdown() {
        closed = true
        epoch.begin()
        scope.launch {
            cancelRecognition()
            nativeMutex.withLock { closed = true; recognizer?.release(); recognizer = null }
            isInitialized.value = false
            scope.cancel()
        }
    }
    override suspend fun getSupportedLanguages() =
        if (modelId == "sensevoice-int8") listOf("zh","yue","en","ja","ko") else listOf("zh","en")
    override suspend fun recognize(audioData: FloatArray) {
        if (!isInitialized.value && !initialize()) return
        require(audioData.size <= 16000 * 60) { "Audio exceeds 60 seconds" }
        decode(audioData,epoch.begin())
    }
}

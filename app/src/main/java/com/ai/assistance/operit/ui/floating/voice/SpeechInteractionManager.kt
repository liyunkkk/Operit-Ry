package com.ai.assistance.operit.ui.floating.voice

import android.content.Intent
import android.content.Context
import android.content.Context.INPUT_METHOD_SERVICE
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.util.OnDemandResources
import com.ai.assistance.operit.R
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ai.assistance.operit.api.chat.AIForegroundService
import com.ai.assistance.operit.api.speech.EarlyCapture
import com.ai.assistance.operit.api.speech.SpeechPrerollStore
import com.ai.assistance.operit.api.speech.WakePhraseSnapshot
import com.ai.assistance.operit.api.speech.SpeechServiceFactory
import com.ai.assistance.operit.api.voice.VoiceServiceFactory
import com.ai.assistance.operit.util.TtsCleaner
import com.ai.assistance.operit.util.WaifuMessageProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "SpeechInteractionManager"

/**
 * 通用语音交互管理器
 * 封装了：语音识别、TTS、焦点获取、文本累积、静默检测等逻辑
 */
class SpeechInteractionManager(
    private val context: Context,
    private val coroutineScope: CoroutineScope,
    private val onSpeechResult: (String, Boolean) -> Unit, // (text, isFinal)
    private val onStateChange: (String) -> Unit, // 更新状态提示文本
    /** A session ended without any recognised words; the microphone is already stopping. */
    private val onNothingHeard: () -> Unit = {},
) {
    companion object {
        private val captureOwnership = Mutex()
        private var captureOwner: SpeechInteractionManager? = null
    }
    private var closed = false
    // ===== 状态 =====
    var isRecording by mutableStateOf(false)
        private set
    var isPreparing by mutableStateOf(false)
        private set
    var isProcessingSpeech by mutableStateOf(false)
        private set
    var hasFocus by mutableStateOf(false)
        private set
    /**
     * The microphone is already kept by [EarlyCapture] while the recognizer still loads. The
     * session counts as recording: what is said now reaches the recognizer once it starts.
     */
    var isHearingEarly by mutableStateOf(false)
        private set
    private var finishOnStart = false

    // 文本累积
    var userMessage by mutableStateOf("")
        private set
    private var accumulatedText = ""
    private var latestPartialText = ""

    private var wakePhraseSnapshot: WakePhraseSnapshot? = null

    // 任务控制
    private var timeoutJob: Job? = null
    private var silenceTimeoutJob: Job? = null
    private var startJob: Job? = null
    private var stopJob: Job? = null
    private val lifecycleMutex get() = captureOwnership
    private var generation = 0L
    private var timing: com.ai.assistance.operit.api.speech.VoiceTiming? = null

    // ===== 服务 =====
    val speechService = SpeechServiceFactory.getInstance(context)
    val voiceService
        get() = VoiceServiceFactory.getInstance(context)
    private val inputMethodManager = context.getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager

    // 暴露 Flow 给外部使用
    val volumeLevelFlow get() = speechService.volumeLevelFlow
    val recognitionResultFlow get() = speechService.recognitionResultFlow

    // ===== 初始化与清理 =====
    suspend fun initialize() {
        resetState()
        try {
            // The recognition model is prepared lazily by startRecognition, so merely showing the
            // floating ball never triggers a large download the user did not ask for.
            voiceService.initialize()
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to initialize speech services", e)
        }
    }

    fun cleanup() {
        if (closed) return
        closed = true
        ++generation
        startJob?.cancel()
        timeoutJob?.cancel()
        silenceTimeoutJob?.cancel()
        isPreparing = false
        isRecording = false
        isProcessingSpeech = false
        stopJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            lifecycleMutex.withLock {
                if (captureOwner === this@SpeechInteractionManager) {
                    try { speechService.cancelRecognition() }
                    catch (e: Exception) { AppLogger.w(TAG, "Speech cleanup failed", e) }
                    captureOwner = null
                }
            }
        }
    }

    private fun resetState() {
        isPreparing = false
        isRecording = false
        isProcessingSpeech = false
        isHearingEarly = false
        finishOnStart = false
        userMessage = ""
        accumulatedText = ""
        latestPartialText = ""
        wakePhraseSnapshot = null
        timeoutJob?.cancel()
        silenceTimeoutJob?.cancel()
    }

    // ===== 焦点管理 =====
    fun requestFocus(view: View?): Boolean {
        if (view == null) {
            hasFocus = false
            return false
        }
        view.requestFocus()
        inputMethodManager.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        inputMethodManager.hideSoftInputFromWindow(view.windowToken, 0)
        hasFocus = true
        return true
    }

    /**
     * For composers inside the app window, which already has focus. The overlay trick above makes
     * the keyboard pop up and drop again; here the keyboard only goes away.
     */
    fun claimFocusWithoutKeyboard(view: View?): Boolean {
        if (view == null) {
            hasFocus = false
            return false
        }
        inputMethodManager.hideSoftInputFromWindow(view.windowToken, 0)
        hasFocus = true
        return true
    }

    fun releaseFocus(view: View?) {
        view?.let {
            inputMethodManager.hideSoftInputFromWindow(it.windowToken, 0)
        }
        hasFocus = false // 逻辑上释放，虽然物理上可能还在
    }

    // ===== 语音识别流程 =====

    fun startListening(
        continuousMode: Boolean = speechService.supportsContinuousRecognition,
        onStarted: (() -> Unit)? = null,
        captureEarly: Boolean = false,
        onStartFailure: ((String) -> Unit)? = null,
    ) {
        if (isPreparing || isRecording || isProcessingSpeech) return
        if (!hasFocus) {
            onStartFailure?.invoke(context.getString(R.string.floating_cannot_get_focus))
            return
        }

        // 重置超时
        timeoutJob?.cancel()
        
        // 重置文本状态
        val session = ++generation
        timing = com.ai.assistance.operit.api.speech.VoiceTiming("recognition").also { it.mark("start_requested") }
        isPreparing = true
        isRecording = false
        isHearingEarly = false
        finishOnStart = false
        userMessage = ""
        accumulatedText = ""
        latestPartialText = ""
        wakePhraseSnapshot = SpeechPrerollStore.consumePendingWakePhrase()
        onStateChange(context.getString(R.string.voice_preparing))

        // 启动监听
        startJob = coroutineScope.launch {
          var stopAfterStart = false
          stopJob?.join()
          lifecycleMutex.withLock {
            if (closed || session != generation) return@withLock
            captureOwner?.takeIf { it !== this@SpeechInteractionManager }?.let { previous ->
                ++previous.generation
                previous.startJob?.cancel()
                previous.resetState()
                previous.speechService.cancelRecognition()
            }
            captureOwner = this@SpeechInteractionManager
            // Wake sessions already hand over the audio around the wake word.
            var earlyToken = 0L
            try {
                // Not while a model download waits for the user: the microphone would stay open
                // throughout, and keep the wrong audio.
                if (captureEarly && wakePhraseSnapshot == null &&
                    withContext(Dispatchers.IO) { speechService.canStartWithoutPrompt(context) }
                ) {
                    // Not cancellable: once the microphone opens, its token must reach `finally`.
                    // Assigned inside: a cancel landing meanwhile still throws when withContext
                    // returns, and would otherwise drop the token and leave the microphone open.
                    withContext(NonCancellable + Dispatchers.IO) { earlyToken = EarlyCapture.start(context) }
                    if (earlyToken != 0L && session == generation) {
                        timing?.mark("capturing")
                        isPreparing = false
                        isRecording = true
                        isHearingEarly = true
                        onStateChange(context.getString(R.string.floating_listening))
                    }
                } else {
                    // A capture left by another session must not be handed to this one.
                    withContext(NonCancellable + Dispatchers.IO) { EarlyCapture.stop() }
                }

                try {
                    AIForegroundService.ensureMicrophoneForeground(context, forceStart = true)
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Failed to request microphone foreground", e)
                }

                try {
                    context.startService(Intent(context, AIForegroundService::class.java).apply {
                        action = AIForegroundService.ACTION_PREPARE_WAKE_HANDOFF
                    })
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Failed to request wake handoff prepare", e)
                }

                // Give AIForegroundService a moment to stop wake listening and release microphone
                delay(180)

                var ok = false
                var attempt = 0
                while (!ok && attempt < 12) {
                    if (attempt > 0) {
                        delay(160)
                    }
                    ok = speechService.startRecognition(
                        languageCode = "zh-CN",
                        continuousMode = continuousMode,
                        partialResults = true
                    )
                    attempt++
                    // A recognizer that never initialized cannot become ready by retrying, and
                    // retrying would ask for its download again right after the user declined.
                    if (!ok && !speechService.isInitialized.value) break
                }

                if (session != generation) {
                    speechService.cancelRecognition()
                    return@withLock
                }
                isPreparing = false
                isHearingEarly = false
                if (ok && finishOnStart) {
                    // Stopped while the recognizer was still loading: it has the audio, so finish now.
                    // finishOnStart stays set until the stop below, so another stop tap stays a no-op.
                    timing?.mark("recording")
                    stopAfterStart = true
                    return@withLock
                }
                isRecording = ok
                if (ok) {
                    timing?.mark("recording")
                    onStateChange(context.getString(R.string.floating_listening))
                    onStarted?.invoke()
                }
                if (!ok) {
                    isRecording = false
                    isProcessingSpeech = false
                    finishOnStart = false
                    onStateChange(context.getString(R.string.floating_hold_microphone))
                    // Only the download layer produces a reason that is safe to show here: the
                    // provider's own error text is engine wording, sometimes not even translated.
                    val reason = OnDemandResources.recentSpeechFailure() ?: ""
                    onStartFailure?.invoke(
                        reason.ifBlank { context.getString(R.string.floating_start_recording_failed) }
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (session != generation) return@withLock
                isPreparing = false
                isRecording = false
                isProcessingSpeech = false
                isHearingEarly = false
                finishOnStart = false
                onStateChange(context.getString(R.string.floating_hold_microphone))
                onStartFailure?.invoke(e.message ?: context.getString(R.string.floating_start_recording_failed))
            } finally {
                // Normally the recognizer has taken it already; otherwise the audio is dropped here.
                if (earlyToken != 0L) withContext(NonCancellable + Dispatchers.IO) { EarlyCapture.stop(earlyToken) }
            }
          }
          if (stopAfterStart && session == generation && isProcessingSpeech) {
              onStateChange(context.getString(R.string.floating_recognizing))
              speechService.stopRecognition()
              finishOnStart = false
              if (session == generation && isProcessingSpeech) startFallbackTimeout()
          }
        }

        // 监听结果流 (假设这是 ViewModel 或者外部调用者通过 collectLatest 连接到 handleRecognitionResult)
        // 注意：SpeechService 的结果是通过 Flow 发出的，这里我们需要一个桥接方式，
        // 或者让 ViewModel 监听 Flow 并调用这里的 handleRecognitionResult。
        // 为了简化，这里我们假设 ViewModel 负责监听 SpeechService 的 Flow 并调用本类的 handleRecognitionResult
    }

    fun stopListening(isCancel: Boolean) {
        timing?.mark(if (isCancel) "cancel_requested" else "recording_stopped")
        if (!isRecording && !isPreparing && !isProcessingSpeech) return
        // Already finishing once the recognizer is up; another stop must not throw the audio away.
        if (!isCancel && finishOnStart) return
        if (!isCancel && isHearingEarly) {
            // The recognizer is still loading. It finishes as soon as it has taken the audio.
            finishOnStart = true
            isHearingEarly = false
            isRecording = false
            isProcessingSpeech = true
            silenceTimeoutJob?.cancel()
            onStateChange(context.getString(R.string.floating_recognizing))
            return
        }
        val cancel = isCancel || isPreparing
        val session = if (cancel) ++generation else generation
        startJob?.cancel()
        isPreparing = false
        isHearingEarly = false
        finishOnStart = false

        isRecording = false
        isProcessingSpeech = !cancel
        silenceTimeoutJob?.cancel()

        stopJob = coroutineScope.launch {
            if (cancel) {
                lifecycleMutex.withLock {
                    if (captureOwner !== this@SpeechInteractionManager) return@withLock
                    speechService.cancelRecognition()
                    if (session == generation) {
                        resetState()
                        onStateChange(context.getString(R.string.floating_hold_microphone))
                    }
                }
            } else {
                if (session != generation || captureOwner !== this@SpeechInteractionManager) return@launch
                onStateChange(context.getString(R.string.floating_recognizing))
                speechService.stopRecognition()
                if (session == generation && isProcessingSpeech) startFallbackTimeout()
            }
        }
    }

    // 处理识别结果
    fun handleRecognitionResult(resultText: String, isFinal: Boolean, autoSendSilence: Boolean = false) {
        if (closed || captureOwner !== this) return
        val effectiveText = stripWakePhrasePrefixIfNeeded(resultText)
        if (isRecording) {
            if (effectiveText.isNotBlank()) {
                // Partial hypotheses replace the current segment, including corrections.
                latestPartialText = effectiveText
                userMessage = accumulatedText + latestPartialText
                if (isFinal) {
                    accumulatedText = userMessage
                    latestPartialText = ""
                    if (autoSendSilence) {
                        finalizeSpeechInput()
                        return
                    }
                }

                // 静默检测
                if (autoSendSilence && !isFinal) {
                    silenceTimeoutJob?.cancel()
                    silenceTimeoutJob = coroutineScope.launch {
                        delay(2000)
                        AppLogger.d(TAG, "Silence timeout, sending...")
                        finalizeSpeechInput()
                    }
                }
            }
            userMessage = accumulatedText + (if (accumulatedText.isNotEmpty() || latestPartialText.isNotEmpty()) latestPartialText else "")
            
        } else if (isProcessingSpeech && isFinal) {
            timeoutJob?.cancel()
            userMessage = accumulatedText + effectiveText
            finalizeSpeechInput()
        }
    }

    private fun stripWakePhrasePrefixIfNeeded(text: String): String {
        val snapshot = wakePhraseSnapshot ?: return text
        if (text.isBlank()) return text

        val leadingRegex = Regex("^[\\s\\p{Punct}，。！？；：、“”‘’【】（）()\\[\\]{}<>《》]+")
        val leadingMatch = leadingRegex.find(text)
        val startIdx = leadingMatch?.value?.length ?: 0
        val trimmed = text.substring(startIdx)

        if (trimmed.isBlank()) return text

        if (snapshot.regexEnabled) {
            return try {
                val m = Regex(snapshot.phrase).find(trimmed)
                if (m != null && m.range.first == 0) {
                    val remainder = trimmed.substring(m.value.length)
                    remainder.replaceFirst(leadingRegex, "")
                } else {
                    text
                }
            } catch (_: Exception) {
                text
            }
        }

        val phrase = snapshot.phrase
        if (phrase.isBlank()) return text
        return if (trimmed.startsWith(phrase)) {
            val remainder = trimmed.substring(phrase.length)
            remainder.replaceFirst(leadingRegex, "")
        } else {
            text
        }
    }

    private fun startFallbackTimeout() {
        val session = generation
        timeoutJob = coroutineScope.launch {
            delay(3000)
            if (!closed && captureOwner === this@SpeechInteractionManager &&
                session == generation && isProcessingSpeech) {
                AppLogger.w(TAG, "Fallback timeout")
                finalizeSpeechInput()
            }
        }
    }

    private fun finalizeSpeechInput() {
        timeoutJob?.cancel()
        silenceTimeoutJob?.cancel()
        isRecording = false
        isProcessingSpeech = false
        val session = ++generation
        stopJob = coroutineScope.launch {
            lifecycleMutex.withLock {
                if (captureOwner === this@SpeechInteractionManager) {
                    speechService.cancelRecognition()
                }
            }
        }
        val text = userMessage.ifBlank { accumulatedText }
        timing?.mark("final_result")
        
        if (text.isNotBlank()) {
            onStateChange(context.getString(R.string.floating_thinking_2))
            onSpeechResult(text, true)
        } else {
            onStateChange(context.getString(R.string.floating_didnt_hear_clearly))
            onNothingHeard()
        }

        // 如果不是静默超时触发的，重置文本（静默超时可能意味着这一句结束，准备下一句，或者直接发送）
        // 这里我们统一重置，因为 onSpeechResult 通常会触发发送
        userMessage = ""
        accumulatedText = ""
        latestPartialText = ""
    }
    
    // ===== TTS 辅助 =====
    
    fun speak(text: String, interrupt: Boolean = true) {
        if (text.isBlank()) return
        coroutineScope.launch {
            try {
                voiceService.speak(text, interrupt)
            } catch (e: Exception) {
                AppLogger.e(TAG, "TTS Error", e)
            }
        }
    }
    
    fun cleanTextForTts(text: String, regexs: List<String>): String {
        return WaifuMessageProcessor.cleanContentForWaifu(TtsCleaner.clean(text, regexs))
    }
}

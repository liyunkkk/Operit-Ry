package com.ai.assistance.operit.ui.features.chat.components.style.input.common

import android.content.Context
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.ai.assistance.operit.R
import com.ai.assistance.operit.api.speech.EarlyCapture
import com.ai.assistance.operit.ui.features.chat.viewmodel.ChatViewModel
import com.ai.assistance.operit.ui.floating.voice.SpeechInteractionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * One dictation session for the composer.
 *
 * Reading [isRecording] and friends during composition is observable, because they are backed by
 * the manager's snapshot state. Transcription only ever lands in the draft — speaking never sends.
 */
@Stable
class DraftVoiceController
internal constructor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val view: View,
) {
    private var activeState by mutableStateOf(false)
    private var statusState by mutableStateOf("")
    private var errorState by mutableStateOf<String?>(null)
    private var failedTextState by mutableStateOf<String?>(null)
    private var holdingState by mutableStateOf(false)
    private var holdTargetState by mutableStateOf(HoldRelease.Draft)
    /** Started by holding the composer: nothing heard then just closes, there is no retry. */
    private var heldSession = false
    private var sendAfterResult = false

    internal val manager: SpeechInteractionManager by lazy {
        SpeechInteractionManager(
            context = context,
            coroutineScope = scope,
            onSpeechResult = { text, final -> if (final) closeFromResult(text) },
            onStateChange = { setStatus(it) },
            // The manager has already stopped; a held session just goes back to the composer.
            onNothingHeard = { if (heldSession) close() },
        )
    }

    private var insertText: (String) -> Unit = {}
    private var requestSend: () -> Unit = {}

    val isActive: Boolean get() = activeState
    val isRecording: Boolean get() = manager.isRecording
    val isPreparing: Boolean get() = manager.isPreparing
    val isProcessing: Boolean get() = manager.isProcessingSpeech
    /** Listening while the recognizer still loads; the audio is kept for it. */
    val isHearingEarly: Boolean get() = manager.isHearingEarly

    /** A finger is holding the composer: the panel shows the stop/send targets. */
    val isHolding: Boolean get() = holdingState
    /**
     * Whether letting go now sends instead of only filling the message box. Before anything is
     * being heard, letting go can only cancel, so the send side does not light up yet.
     */
    val releaseToSend: Boolean get() = holdTargetState == HoldRelease.Send && manager.isRecording
    /** Letting go now throws the recording away. */
    val releaseToCancel: Boolean get() = holdTargetState == HoldRelease.Cancel

    /** What has been recognised so far, partial results included. */
    val liveText: String get() = failedTextState ?: manager.userMessage
    val statusText: String get() = statusState
    val errorText: String? get() = errorState
    val volumeLevelFlow: StateFlow<Float>
        get() = if (manager.isHearingEarly) EarlyCapture.level else manager.volumeLevelFlow
    val recognitionStateFlow get() = manager.speechService.recognitionStateFlow

    internal fun bindInsert(block: (String) -> Unit) {
        insertText = block
    }

    internal fun bindSend(block: () -> Unit) {
        requestSend = block
    }

    /** Starts listening. Safe to call again to retry after a failure. */
    fun open(holding: Boolean = false) {
        errorState = null
        failedTextState = null
        holdingState = holding
        heldSession = holding
        holdTargetState = HoldRelease.Draft
        sendAfterResult = false
        activeState = true
        manager.claimFocusWithoutKeyboard(view)
        manager.startListening(
            onStartFailure = { errorState = it },
            continuousMode = manager.speechService.supportsContinuousDictation ||
                manager.speechService.supportsContinuousRecognition,
            // People start talking as soon as they tap; keep that while the model loads.
            captureEarly = true,
        )
    }

    /** Stops the microphone and waits for the final transcription. */
    fun finishRecording(thenSend: Boolean = false) {
        sendAfterResult = thenSend
        manager.stopListening(isCancel = false)
    }

    fun setHoldTarget(target: HoldRelease) {
        holdTargetState = target
    }

    /**
     * The holding finger let go. A session that already finished on its own has put its text in
     * the message box, so sending then means sending that draft; cancelling then leaves it there.
     */
    fun releaseHold(target: HoldRelease) {
        if (!holdingState) return
        holdingState = false
        holdTargetState = HoldRelease.Draft
        val send = target == HoldRelease.Send
        when {
            target == HoldRelease.Cancel -> if (activeState) cancel()
            !activeState -> if (send) requestSend()
            isPreparing -> cancel()
            isRecording -> finishRecording(thenSend = send)
        }
    }

    /** Takes whatever has been recognised so far and closes, without waiting for the final pass. */
    fun confirmNow() {
        val text = liveText
        resetHold()
        manager.stopListening(isCancel = true)
        activeState = false
        insertText(text)
    }

    fun cancel() {
        resetHold()
        manager.stopListening(isCancel = true)
        activeState = false
    }

    private fun close() {
        resetHold()
        activeState = false
    }

    /**
     * Stops the microphone but keeps the panel up, so the reason stays on screen instead of the
     * composer silently going back to normal.
     */
    fun fail(message: String) {
        failedTextState = liveText
        resetHold()
        manager.stopListening(isCancel = true)
        errorState = message
        activeState = true
    }

    /** Nothing was recognised. A held session goes straight back to the empty composer. */
    fun nothingHeard(message: String) {
        if (heldSession) cancel() else fail(message)
    }

    internal fun closeFromResult(text: String) {
        val send = sendAfterResult
        sendAfterResult = false
        activeState = false
        insertText(text)
        if (send && text.isNotBlank()) requestSend()
    }

    private fun resetHold() {
        holdingState = false
        holdTargetState = HoldRelease.Draft
        sendAfterResult = false
    }

    internal fun setStatus(text: String) {
        statusState = text
    }

    internal fun cleanup() {
        manager.cleanup()
    }
}

/**
 * Builds the controller for the current chat and keeps its transcription flowing into the draft.
 * The manager is keyed on the chat, so switching conversations ends a session the same way closing
 * the old dialog did.
 */
@Composable
fun rememberDraftVoiceController(
    viewModel: ChatViewModel,
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
): DraftVoiceController {
    val chatId by viewModel.currentChatId.collectAsState()
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val latestValue by rememberUpdatedState(value)
    val latestChange by rememberUpdatedState(onValueChange)
    val didNotHearText = stringResource(R.string.floating_didnt_hear_clearly)

    return key(chatId) {
        val controller = remember { DraftVoiceController(context, scope, view) }
        controller.bindInsert { text ->
            if (text.isNotBlank()) {
                val current = latestValue
                val start = current.selection.min.coerceIn(0, current.text.length)
                val end = current.selection.max.coerceIn(start, current.text.length)
                latestChange(
                    TextFieldValue(
                        current.text.replaceRange(start, end, text),
                        TextRange(start + text.length),
                    )
                )
            }
        }
        DisposableEffect(controller) { onDispose { controller.cleanup() } }

        LaunchedEffect(controller) {
            controller.manager.recognitionResultFlow.collect {
                if (
                    it.isFinal &&
                        it.text.isBlank() &&
                        controller.liveText.isBlank() &&
                        (controller.isProcessing ||
                            (controller.isRecording &&
                                !controller.manager.speechService.supportsContinuousDictation))
                ) {
                    controller.nothingHeard(didNotHearText)
                    return@collect
                }
                // Finalises into the draft-only callback, never a chat send action.
                controller.manager.handleRecognitionResult(
                    it.text,
                    it.isFinal,
                    autoSendSilence = !controller.manager.speechService.supportsContinuousDictation,
                )
            }
        }
        LaunchedEffect(controller) {
            controller.manager.speechService.recognitionErrorFlow.collect {
                if (it.message.isNotBlank() && (controller.isRecording || controller.isProcessing)) {
                    controller.fail(it.message)
                }
            }
        }

        controller
    }
}

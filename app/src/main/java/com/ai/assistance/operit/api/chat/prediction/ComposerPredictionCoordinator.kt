package com.ai.assistance.operit.api.chat.prediction

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Owns only auxiliary state. Draft changes are deliberately absent from its request identity. */
class ComposerPredictionCoordinator(
    private val scope: CoroutineScope,
    private val predict: suspend (ComposerPredictionSnapshot) -> String?,
) {
    enum class Status { IDLE, RUNNING, READY, EMPTY, FAILED }
    private data class Request(val chatId: String, val sourceTurnId: String, val requestId: String)
    private var request: Request? = null
    private var contextKey: Any? = null
    private var job: Job? = null
    private val attempted = mutableSetOf<Pair<String, String>>()
    private val _text = MutableStateFlow<String?>(null)
    val text: StateFlow<String?> = _text.asStateFlow()
    var status: Status = Status.IDLE
        private set

    @Synchronized
    fun invalidate() {
        request = null
        contextKey = null
        job?.cancel()
        job = null
        _text.value = null
        status = Status.IDLE
    }

    /** A finished suggestion is still useful after adjusting the next request's thinking settings. */
    @Synchronized
    fun invalidateForThinkingChange(): Boolean {
        if (status == Status.READY) return false
        invalidate()
        return true
    }

    @Synchronized
    fun update(
        enabled: Boolean,
        key: Any?,
        snapshot: ComposerPredictionSnapshot?,
        stillValid: () -> Boolean,
    ) {
        if (!enabled || key == null || snapshot == null) {
            invalidate()
            return
        }
        if (contextKey == key) return
        invalidate()
        contextKey = key
        // Includes failed and empty completions, so clearing a draft never spends another call.
        if (!attempted.add(snapshot.chatId to snapshot.sourceTurnId)) return
        val identity = Request(snapshot.chatId, snapshot.sourceTurnId, UUID.randomUUID().toString())
        request = identity
        status = Status.RUNNING
        job = scope.launch {
            try {
                val result = predict(snapshot)
                synchronized(this@ComposerPredictionCoordinator) {
                    if (request != identity || !stillValid()) return@synchronized
                    _text.value = result
                    status = if (result == null) Status.EMPTY else Status.READY
                }
            } catch (_: TimeoutCancellationException) {
                // A nested request timeout ends this attempt. Cancellation of the owning scope
                // still propagates, and a late timeout must not change a newer request's state.
                currentCoroutineContext().ensureActive()
                synchronized(this@ComposerPredictionCoordinator) {
                    if (request == identity) status = Status.FAILED
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                synchronized(this@ComposerPredictionCoordinator) {
                    if (request == identity) status = Status.FAILED
                }
            }
        }
    }

    /** Adoption is a read, not consumption: clearing the real draft reveals the same cached text. */
    @Synchronized
    fun textForAdoption(expected: String?, draftEmpty: Boolean, stillValid: () -> Boolean): String? {
        if (!draftEmpty || status != Status.READY || request == null || !stillValid()) return null
        return _text.value?.takeIf { expected == null || expected == it }
    }
}

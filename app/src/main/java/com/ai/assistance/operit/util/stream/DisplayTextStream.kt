package com.ai.assistance.operit.util.stream

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Display-only replay: keep one text buffer, not a queue of every token per renderer.
 * Subscribers use offsets into that buffer. Conflating notifications cannot lose text.
 * A revision uses a new stream, so offsets always refer to an append-only document.
 */
internal class DisplayTextStream : Stream<String> {
    private companion object {
        const val BATCH_CHARS = 16 * 1024
        const val COALESCE_MS = 16L
    }
    private val monitor = Any()
    private val text = StringBuilder()
    private val subscribers = mutableSetOf<Channel<Unit>>()
    private var closed = false
    private var failure: Throwable? = null
    private val completion = CompletableDeferred<Unit>()
    private data class Batch(val text: String, val finished: Boolean, val failure: Throwable?)

    private fun readBatch(offset: Int): Batch = synchronized(monitor) {
        val end = offset + minOf(text.length - offset, BATCH_CHARS)
        Batch(text.substring(offset, end), closed && end == text.length, failure)
    }

    private fun subscribe(channel: Channel<Unit>) {
        synchronized(monitor) { subscribers.add(channel) }
    }

    private fun unsubscribe(channel: Channel<Unit>) {
        synchronized(monitor) { subscribers.remove(channel) }
    }

    override val isLocked = false
    override val bufferedCount = 0
    override suspend fun lock() = Unit
    override suspend fun unlock() = Unit
    override fun clearBuffer() = Unit

    fun emit(chunk: String) {
        synchronized(monitor) {
            if (closed) return
            text.append(chunk)
            subscribers.forEach { it.trySend(Unit) }
        }
    }

    fun close(cause: Throwable? = null) {
        synchronized(monitor) {
            if (closed) return
            closed = true
            failure = cause
            subscribers.forEach { it.trySend(Unit) }
        }
        completion.complete(Unit)
    }

    override suspend fun collect(collector: StreamCollector<String>) {
        val changed = Channel<Unit>(Channel.CONFLATED)
        subscribe(changed)
        var offset = 0
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val batch = readBatch(offset)
                if (batch.text.isNotEmpty()) {
                    offset += batch.text.length
                    collector.emit(batch.text)
                }
                if (batch.finished) {
                    if (batch.failure != null) throw batch.failure
                    return
                }
                when {
                    batch.text.isEmpty() -> changed.receive()
                    // Drain a replay/backlog at full speed, yielding fairly between batches.
                    batch.text.length == BATCH_CHARS -> yield()
                    // First content was already delivered. Read the *next* batch after the
                    // window, merging tiny deltas without delaying execution or revision events.
                    // Close interrupts the wait; cancellation remains immediately cancellable.
                    else -> withTimeoutOrNull(COALESCE_MS) { completion.await() }
                }
            }
        } finally {
            unsubscribe(changed)
            changed.cancel()
        }
    }
}

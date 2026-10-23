package com.ai.assistance.operit.util

data class LogFlushResult(
    val pendingRecords: Long,
    val droppedRecords: Long,
    val writeFailed: Boolean,
    val interrupted: Boolean = false,
) {
    val complete: Boolean get() = pendingRecords == 0L && droppedRecords == 0L && !writeFailed && !interrupted
}

/** Bounded diagnostic queue. UI callers never wait for a slow disk; overload is reported by the sink. */
internal class BatchLogQueue<T>(
    private val capacity: Int = 128,
    private val batchSize: Int = 32,
    private val sink: (List<T>, Long) -> Boolean,
) {
    private data class Item<T>(val sequence: Long, val value: T, val reset: Boolean = false)
    private val lock = Object()
    private val pending = ArrayDeque<Item<T>>()
    private var submitted = 0L
    private var completed = 0L
    private var dropped = 0L
    private var totalDropped = 0L
    private var failed = false
    private val worker = Thread(::consume, "OperitAppLogger").apply { isDaemon = true; start() }

    fun submit(value: T, waitForSpace: Boolean): Boolean = synchronized(lock) {
        while (pending.size >= capacity) {
            if (!waitForSpace || Thread.currentThread() === worker) {
                dropped++
                totalDropped++
                return@synchronized false
            }
            try { lock.wait() } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                dropped++
                totalDropped++
                return@synchronized false
            }
        }
        pending.addLast(Item(++submitted, value))
        lock.notifyAll()
        true
    }

    /** A user-requested reset discards earlier queued records, but executes after the active batch. */
    fun reset(value: T) = synchronized(lock) {
        pending.clear()
        dropped = 0
        totalDropped = 0
        pending.addLast(Item(++submitted, value, reset = true))
        lock.notifyAll()
    }

    fun flush(timeoutMs: Long): Boolean = flushResult(timeoutMs).complete

    fun flushResult(timeoutMs: Long): LogFlushResult = synchronized(lock) {
        val target = submitted
        val lostAtStart = totalDropped
        fun result(interrupted: Boolean = false) = LogFlushResult(
            (target - completed).coerceAtLeast(0), lostAtStart, failed, interrupted)
        if (Thread.currentThread() === worker) return@synchronized result(interrupted = true)
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        // Wait for the export's watermark, not batches produced after export started.
        while (completed < target) {
            val remaining = (deadline - System.nanoTime()) / 1_000_000
            if (remaining <= 0) return@synchronized result()
            try { lock.wait(remaining.coerceAtLeast(1)) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return@synchronized result(interrupted = true)
            }
        }
        result()
    }

    private fun consume() {
        while (true) {
            val (batch, lost) = synchronized(lock) {
                while (pending.isEmpty() && dropped == 0L) lock.wait()
                val items = List(minOf(batchSize, pending.size)) { pending.removeFirst() }
                val lost = dropped
                dropped = 0
                lock.notifyAll()
                items to lost
            }
            val success = try { sink(batch.map { it.value }, lost) } catch (_: Throwable) { false }
            synchronized(lock) {
                if (batch.any { it.reset }) failed = false
                if (!success) failed = true
                if (batch.isNotEmpty()) completed = batch.last().sequence
                lock.notifyAll()
            }
        }
    }
}

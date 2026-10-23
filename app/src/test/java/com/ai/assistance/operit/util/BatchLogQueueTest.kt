package com.ai.assistance.operit.util

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class BatchLogQueueTest {
    @Test fun `overload returns promptly and is reported while background records remain ordered`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val received = Collections.synchronizedList(mutableListOf<Int>())
        var lost = 0L
        val queue = BatchLogQueue<Int>(capacity = 2, batchSize = 2) { batch, dropped ->
            if (batch.contains(0)) { entered.countDown(); release.await(3, TimeUnit.SECONDS) }
            received.addAll(batch)
            lost += dropped
            true
        }
        queue.submit(0, true)
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        assertTrue(queue.submit(1, false))
        assertTrue(queue.submit(2, false))
        assertFalse(queue.submit(3, false))
        assertFalse(queue.flush(10))
        release.countDown()
        val status = queue.flushResult(3000)
        assertFalse(status.complete)
        assertEquals(1L, status.droppedRecords)
        assertEquals(0L, status.pendingRecords)
        assertEquals(listOf(0, 1, 2), received)
        assertEquals(1L, lost)
    }

    @Test fun `reset separates active batch from all new records`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val received = Collections.synchronizedList(mutableListOf<Int>())
        val queue = BatchLogQueue<Int>(capacity = 2) { batch, _ ->
            if (batch.contains(0)) { entered.countDown(); release.await(3, TimeUnit.SECONDS) }
            batch.forEach { if (it == -1) received.clear() else received.add(it) }
            true
        }
        queue.submit(0, true)
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        queue.submit(1, true)
        queue.reset(-1)
        queue.submit(2, true)
        release.countDown()
        assertTrue(queue.flush(3000))
        assertEquals(listOf(2), received)
    }

    @Test fun `failed sink is not reported as a successful flush`() {
        val queue = BatchLogQueue<Int> { _, _ -> false }
        queue.submit(1, true)
        assertFalse(queue.flush(3000))
    }

    @Test fun `successful reset clears failures belonging to removed logs`() {
        val queue = BatchLogQueue<Int> { batch, _ -> !batch.contains(1) }
        queue.submit(1, true)
        assertTrue(queue.flushResult(3000).writeFailed)
        queue.reset(-1)
        assertTrue(queue.flushResult(3000).complete)
    }

    @Test fun `failed reset cannot report complete logs`() {
        val queue = BatchLogQueue<Int> { _, _ -> false }
        queue.submit(1, true)
        assertTrue(queue.flushResult(3000).writeFailed)
        queue.reset(-1)
        assertTrue(queue.flushResult(3000).writeFailed)
        assertFalse(queue.flushResult(3000).complete)
    }

    @Test fun `export watermark does not wait for a later blocked batch`() {
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val laterEntered = CountDownLatch(1)
        val releaseLater = CountDownLatch(1)
        val flushed = CountDownLatch(1)
        val result = java.util.concurrent.atomic.AtomicReference<LogFlushResult>()
        val queue = BatchLogQueue<Int>(batchSize = 1) { batch, _ ->
            if (batch.contains(1)) {
                firstEntered.countDown()
                releaseFirst.await(5, TimeUnit.SECONDS)
            } else {
                laterEntered.countDown()
                releaseLater.await(5, TimeUnit.SECONDS)
            }
            true
        }
        queue.submit(1, true)
        assertTrue(firstEntered.await(3, TimeUnit.SECONDS))
        val waiter = Thread {
            result.set(queue.flushResult(3000))
            flushed.countDown()
        }
        try {
            waiter.start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (waiter.state != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) Thread.yield()
            assertEquals(Thread.State.TIMED_WAITING, waiter.state)
            queue.submit(2, true)
            releaseFirst.countDown()
            assertTrue(laterEntered.await(3, TimeUnit.SECONDS))
            assertTrue(flushed.await(1, TimeUnit.SECONDS))
            assertTrue(result.get().complete)
        } finally {
            releaseFirst.countDown()
            releaseLater.countDown()
            waiter.join(3000)
        }
    }
}

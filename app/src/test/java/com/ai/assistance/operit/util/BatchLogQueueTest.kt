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
        assertTrue(queue.flush(3000))
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
}

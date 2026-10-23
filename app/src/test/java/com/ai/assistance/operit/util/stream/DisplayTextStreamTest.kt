package com.ai.assistance.operit.util.stream

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle

@OptIn(ExperimentalCoroutinesApi::class)
class DisplayTextStreamTest {
    @Test
    fun thousandTinyDeltasPerSecondKeepThinkingAndToolMarkupExactly() = runTest {
        val stream = DisplayTextStream()
        val expected = "<think>思考与推理😀</think><tool name=\"terminal\"><param>echo ok</param></tool>".repeat(20)
        val result = StringBuilder()
        var batches = 0
        val reader = launch {
            stream.collect {
                result.append(it)
                batches++
            }
        }
        stream.emit(expected.take(1))
        runCurrent()
        assertEquals(expected.take(1), result.toString()) // First content is immediate.
        expected.drop(1).forEach {
            delay(1) // 1,000 one-character deltas/s, including split tags and surrogate pairs.
            stream.emit(it.toString())
        }
        stream.close()
        reader.join()
        assertEquals(expected, result.toString())
        assertTrue("Expected batching, received $batches", batches < expected.length / 10)
    }

    @Test
    fun closeInterruptsBatchWaitAndDrainsTailBeforeFailure() = runTest {
        val stream = DisplayTextStream()
        val failure = IllegalStateException("failed after tool prefix")
        val result = StringBuilder()
        val reader = async {
            runCatching { stream.collect { result.append(it) } }.exceptionOrNull()
        }
        stream.emit("<tool")
        runCurrent()
        stream.emit(">tail</tool>")
        stream.close(failure)
        runCurrent()
        assertTrue(reader.isCompleted)
        assertSame(failure, reader.await())
        assertEquals("<tool>tail</tool>", result.toString())
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun cancelledConsumerDoesNotDelayOtherReadersOrLoseReplay() = runTest {
        val stream = DisplayTextStream()
        val reader = launch { stream.collect {} }
        stream.emit("head")
        runCurrent()
        advanceTimeBy(1)
        reader.cancelAndJoin()
        stream.emit("tail")
        stream.close()
        val result = StringBuilder()
        stream.collect { result.append(it) }
        assertEquals("headtail", result.toString())
        advanceUntilIdle()
    }

    @Test
    fun returningRendererReceivesBoundedBatchesAndCompleteHistory() = runBlocking {
        val stream = DisplayTextStream()
        repeat(100_000) { stream.emit("abcdef") }
        stream.close()
        val result = StringBuilder()
        var batches = 0
        stream.collect {
            assertTrue(it.length <= 16 * 1024)
            result.append(it)
            batches++
        }
        assertEquals("abcdef".repeat(100_000), result.toString())
        assertTrue(batches < 100)
    }

    @Test
    fun slowSubscriberAndReturningSubscriberBothReceiveAllText() = runBlocking {
        val stream = DisplayTextStream()
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        stream.emit("prefix")
        val slow = async(start = CoroutineStart.UNDISPATCHED) {
            val result = StringBuilder()
            stream.collect {
                result.append(it)
                entered.complete(Unit)
                resume.await()
            }
            result.toString()
        }
        entered.await()
        repeat(20_000) { stream.emit("x") }
        stream.close()
        val replay = StringBuilder()
        stream.collect { replay.append(it) }
        resume.complete(Unit)
        withTimeout(2_000) {
            assertEquals("prefix" + "x".repeat(20_000), slow.await())
            assertEquals(slow.await(), replay.toString())
        }
    }

    @Test
    fun failureIsDeliveredAfterBufferedText() = runBlocking {
        val stream = DisplayTextStream()
        val failure = IllegalStateException("test failure")
        stream.emit("kept")
        stream.close(failure)
        var text = ""
        val caught = runCatching { stream.collect { text += it } }.exceptionOrNull()
        assertSame(failure, caught)
        assertEquals("kept", text)
    }
}

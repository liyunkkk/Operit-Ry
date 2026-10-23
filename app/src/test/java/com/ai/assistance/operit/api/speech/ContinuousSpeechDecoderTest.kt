package com.ai.assistance.operit.api.speech

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ContinuousSpeechDecoderTest {
    @Test fun pausesPublishCumulativeTextAndOnlyManualStopPublishesFinal() = runTest {
        val results = mutableListOf<Pair<String, Boolean>>()
        val decoder = ContinuousSpeechDecoder(this, { "段${it[0].toInt()}" },
            { text, final -> results += text to final }, { throw AssertionError(it) })
        assertTrue(decoder.offer(floatArrayOf(1f)))
        runCurrent()
        assertEquals(listOf("段1" to false), results)
        assertTrue(decoder.offer(floatArrayOf(2f)))
        runCurrent()
        assertEquals("段1\n段2" to false, results.last())
        assertTrue(decoder.finish(floatArrayOf(3f)))
        assertEquals("段1\n段2\n段3" to true, results.last())
        assertEquals(1, results.count { it.second })
    }

    @Test fun stopWaitsForPendingInferenceAndRetainsTailInOrder() = runTest {
        val gate = CompletableDeferred<Unit>()
        val results = mutableListOf<Pair<String, Boolean>>()
        val decoder = ContinuousSpeechDecoder(this, {
            if (it[0] == 1f) gate.await()
            it[0].toInt().toString()
        }, { text, final -> results += text to final }, { throw AssertionError(it) })
        decoder.offer(floatArrayOf(1f))
        runCurrent()
        decoder.offer(floatArrayOf(2f))
        val finish = async { decoder.finish(floatArrayOf(3f)) }
        runCurrent()
        assertFalse(finish.isCompleted)
        gate.complete(Unit)
        assertTrue(finish.await())
        assertEquals(listOf("1" to false, "1\n2" to false, "1\n2\n3" to false,
            "1\n2\n3" to true), results)
    }

    @Test fun cancelSuppressesNonCancellableNativeResultAndDiscardsQueuedAudio() = runTest {
        val gate = CompletableDeferred<Unit>()
        val results = mutableListOf<String>()
        var decoded = 0
        val decoder = ContinuousSpeechDecoder(this, {
            decoded++
            withContext(NonCancellable) { gate.await() }
            "late"
        }, { text, _ -> results += text }, { throw AssertionError(it) })
        decoder.offer(floatArrayOf(1f))
        runCurrent()
        decoder.offer(floatArrayOf(2f))
        decoder.cancel()
        gate.complete(Unit)
        runCurrent()
        assertTrue(results.isEmpty())
        assertEquals(1, decoded)
        assertFalse(decoder.offer(floatArrayOf(3f)))
    }

    @Test fun slowDecoderHasBoundedBacklogInsteadOfGrowingWithoutLimit() = runTest {
        val gate = CompletableDeferred<Unit>()
        val decoder = ContinuousSpeechDecoder(this, { gate.await(); "" },
            { _, _ -> }, { throw AssertionError(it) })
        decoder.offer(floatArrayOf(1f))
        runCurrent()
        assertTrue(decoder.offer(floatArrayOf(2f)))
        assertTrue(decoder.offer(floatArrayOf(3f)))
        assertFalse(decoder.offer(floatArrayOf(4f)))
        decoder.cancel()
        runCurrent()
    }

    @Test fun decoderFailureDoesNotPublishSuccessfulFinal() = runTest {
        val results = mutableListOf<Boolean>()
        val errors = mutableListOf<Exception>()
        val decoder = ContinuousSpeechDecoder(this, { error("decode failed") },
            { _, final -> results += final }, { errors += it })
        decoder.offer(floatArrayOf(1f))
        runCurrent()
        assertFalse(decoder.finish(null))
        assertTrue(results.isEmpty())
        assertEquals("decode failed", errors.single().message)
    }

    @Test fun noSpeechFinishesEmptyWithoutCallingModel() = runTest {
        val results = mutableListOf<Pair<String, Boolean>>()
        val decoder = ContinuousSpeechDecoder(this, { error("No audio expected") },
            { text, final -> results += text to final }, { throw AssertionError(it) })
        assertTrue(decoder.finish(null))
        assertEquals(listOf("" to true), results)
    }
}

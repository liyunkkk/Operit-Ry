package com.ai.assistance.operit.api.chat.library

import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class MemoryLearningOutcomeTest {
    @Test fun `committed completion reports success`() {
        assertEquals("success", memoryLearningFinalStatus(null, 3, 0))
    }
    @Test fun `unfinished text receives exactly one continuation`() = kotlinx.coroutines.runBlocking {
        val attempts = mutableListOf<Boolean>()
        var finished = false
        runLearningWithFinishRecovery({ finished }) { recovery ->
            attempts += recovery
            if (recovery) finished = true
        }
        assertEquals(listOf(false, true), attempts)
        attempts.clear()
        runLearningWithFinishRecovery({ true }) { attempts += it }
        assertEquals(listOf(false), attempts)
        attempts.clear()
        runLearningWithFinishRecovery({ false }) { attempts += it }
        assertEquals(listOf(false, true), attempts)
    }
    @Test fun `cancelled review is never continued`() = kotlinx.coroutines.runBlocking {
        var attempts = 0
        try {
            runLearningWithFinishRecovery({ false }) {
                attempts++
                throw CancellationException("cancel")
            }
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        assertEquals(1, attempts)
    }
    @Test fun `tool errors remain auditable without marking a completed run failed`() {
        assertEquals("warnings", memoryLearningFinalStatus(null, 3, 1))
    }
    @Test fun `failure after writes is distinguishable from no writes and cancellation`() {
        assertEquals("partial", memoryLearningFinalStatus(IllegalStateException(), 3, 1))
        assertEquals("failed", memoryLearningFinalStatus(IllegalStateException(), 0, 1))
        assertEquals("cancelled", memoryLearningFinalStatus(CancellationException(), 3, 1))
    }
    @Test fun `failure detail includes actionable validation message`() {
        assertEquals("skill_create: IllegalArgumentException: underscores are not allowed",
            learningFailureDetail("skill_create", IllegalArgumentException("underscores are not allowed")))
    }
    @Test fun `round notice warns only near the limit and names the finish tool`() {
        assertNull(learningRoundNotice(5))
        assertNull(learningRoundNotice(3))
        assertEquals(true, learningRoundNotice(2)!!.contains("2 model rounds left"))
        assertEquals(true, learningRoundNotice(1)!!.contains("1 model round left"))
        assertEquals(false, learningRoundNotice(2)!!.contains("1 model round left"))
        assertEquals(true, learningRoundNotice(1)!!.contains(MemoryLearningCoordinator.FINISH))
        // With no rounds left the batch is already lost, so the notice states the outcome instead of
        // telling the reviewer to call a tool it no longer has a request with which to call.
        assertEquals("No model rounds left; this batch is discarded and its source reviewed again.", learningRoundNotice(0))
        assertEquals("No model rounds left; this batch is discarded and its source reviewed again.", learningRoundNotice(-1))
    }
}

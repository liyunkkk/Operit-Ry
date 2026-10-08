package com.ai.assistance.operit.api.chat.library

import org.junit.Assert.*
import org.junit.Test

class LearningRetryGuardTest {
    @Test fun identicalFailuresBlockButCorrectedInputsAndSuccessfulWorkRecover() {
        val guard = LearningRetryGuard()
        val key = learningRetryKey("memory_change", mapOf("content" to "long", "operation" to "add"))
        val reordered = learningRetryKey("memory_change", mapOf("operation" to "add", "content" to "long"))
        assertEquals(key,reordered)
        repeat(2) { guard.onFailure(key) }
        assertFalse(guard.isBlocked(key))
        guard.onFailure(key)
        assertTrue(guard.isBlocked(key))
        assertFalse(guard.isBlocked(learningRetryKey("memory_change", mapOf("content" to "short","operation" to "add"))))
        guard.onSuccess()
        assertFalse(guard.isBlocked(key))
    }

    @Test fun capacityErrorReportsTheProposedReplacementSize() {
        val error = memoryCapacityError("memory.md",5900,6100,6000)
        assertTrue(error.contains("current_chars=5900"))
        assertTrue(error.contains("proposed_chars=6100"))
        assertTrue(error.contains("over_by=100"))
    }

    @Test fun exactEditsRemainExactAndGiveRecoveryAdvice() {
        for ((text,old,reason) in listOf(Triple("abc","xyz","0 matches"),Triple("abc abc","abc","more than 1"))) {
            val error = runCatching { editText(text,"replace","new",old) }.exceptionOrNull()!!
            assertTrue(error.message!!.contains(reason))
            assertTrue(error.message!!.contains("skill_read"))
        }
        assertEquals("a NEW c",editText("a old c","replace","NEW","old"))
    }
}

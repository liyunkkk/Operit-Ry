package com.ai.assistance.operit.ui.permissions

import com.ai.assistance.operit.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionReviewFailureResultTest {
    @Test
    fun reviewFailuresReturnRetryableUnexecutedResultsNotRefusals() {
        PermissionReviewFailureKind.entries.forEach { kind ->
            val decision = permissionBlockedByReviewFailure(kind, "Diagnostic for $kind")
            assertEquals(ToolPermissionDenialSource.AUTOMATIC_REVIEW_FAILED, decision.source)
            assertFalse(decision.interruptTurn)
            assertTrue(decision.rejection.contains("[$kind]"))
            assertTrue(decision.rejection.contains("Diagnostic for $kind"))
            assertTrue(decision.rejection.contains("NOT executed"))
            assertTrue(decision.rejection.contains("retry the same action"))
            assertTrue(decision.rejection.contains("model configuration"))
            assertEquals(decision.source, permissionDenialSourceForMessage(decision.rejection))
            assertEquals(R.string.permission_review_failed_result, permissionDenialSummaryResId(decision.rejection))
            assertFalse(decision.rejection.startsWith(AUTOMATIC_REVIEW_DENIAL_PREFIX))
        }
    }

    @Test
    fun timeoutIsReportedAsTimeout() {
        val result = permissionBlockedByReviewFailure(PermissionReviewFailureKind.TIMED_OUT, "")
        assertTrue(result.rejection.contains("timed out"))
    }
}

package com.ai.assistance.operit.core.tools.defaultTool.standard

import org.junit.Assert.*
import org.junit.Test

class WebVisitFailureTest {
    @Test fun explicitErrorsAreNotSuccessfulPageContent() {
        assertEquals("Page load failed", webVisitFailure("""{"error":"Page load failed"}"""))
        assertNull(webVisitFailure("""{"content":"article with error in its text"}"""))
        assertNull(webVisitFailure("legacy plain text"))
    }
}

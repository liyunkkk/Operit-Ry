package com.ai.assistance.operit.ui.features.chat.components.part

import org.junit.Assert.*
import org.junit.Test

class ShellTaskResultDisplayTest {
    @Test fun `task state replaces call success even with a long command`() {
        val prefix = """{"runId":"r","command":"${"x".repeat(3000)}","status":"""
        assertEquals(ShellResultState.RUNNING, shellResultState("super_admin:shell", prefix + "\"running\"}"))
        assertEquals(ShellResultState.TIMED_OUT, shellResultState("super_admin:shell_wait", prefix + "\"timed_out\"}"))
        assertEquals(ShellResultState.CANCELLING, shellResultState("super_admin:shell_cancel", prefix + "\"cancelling\"}"))
        assertEquals(ShellResultState.CANCELLED, shellResultState("super_admin:shell_cancel", prefix + "\"cancelled\"}"))
    }
    @Test fun `completion is successful only with zero exit code`() {
        assertEquals(ShellResultState.COMPLETED, shellResultState("super_admin:shell", """{"runId":"r","status":"completed","exitCode":0}"""))
        assertEquals(ShellResultState.FAILED, shellResultState("super_admin:shell", """{"runId":"r","status":"completed","exitCode":7}"""))
        assertEquals(ShellResultState.UNKNOWN, shellResultState("super_admin:shell", """{"runId":"r","status":"completed","exitCode":null}"""))
    }
    @Test fun `unrelated tools and legacy results keep existing rendering`() {
        assertNull(shellResultState("other", """{"runId":"r","status":"failed"}"""))
        assertNull(shellResultState("super_admin:shell", """{"output":"old","exitCode":0}"""))
        assertNull(shellResultState("super_admin:shell", """{"runId":"r","status":{}}"""))
    }
}

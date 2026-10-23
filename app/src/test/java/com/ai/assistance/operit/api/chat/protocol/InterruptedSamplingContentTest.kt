package com.ai.assistance.operit.api.chat.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class InterruptedSamplingContentTest {
    @Test fun discardsCompleteAndPartialCallsWithoutExecutingOrRepairingThem() {
        listOf(
            "<tool name=\"terminal\"><param name=\"command\">rm",
            "<tool name=\"terminal\"><param name=\"command\">echo ok</param></tool>",
        ).forEach { call ->
            assertEquals("kept text\n", ExecutableToolProtocolParser.retainBeforeUnexecutedTool("kept text\n$call"))
        }
    }

    @Test fun preservesReasoningAndCodeExamples() {
        val content = "<think>example <tool name=\"foo\">abc</tool></think>\n```xml\n<tool name=\"foo\">abc</tool>\n```\ntext"
        assertEquals(content, ExecutableToolProtocolParser.retainBeforeUnexecutedTool(content))
    }
}

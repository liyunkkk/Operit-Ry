package com.ai.assistance.operit.api.voice

import org.junit.Assert.*
import org.junit.Test

class OpenAiSpeechEndpointTest {
    @Test fun standardBasesAreCompletedWithoutChangingCustomPaths() {
        assertEquals("https://example.com/v1/audio/speech", resolveOpenAiSpeechEndpoint(" https://example.com/ "))
        assertEquals("https://example.com/v1/audio/speech?region=x", resolveOpenAiSpeechEndpoint("https://example.com/v1/?region=x"))
        for (url in listOf("https://example.com/custom/audio/speech", "https://example.com/proxy/v2", "invalid")) {
            assertEquals(url, resolveOpenAiSpeechEndpoint(url))
        }
    }
    @Test fun speechPathMustBeInThePathNotTheQuery() {
        assertTrue(isOpenAiSpeechEndpoint("https://example.com/proxy/audio/speech"))
        assertFalse(isOpenAiSpeechEndpoint("https://example.com/?next=/audio/speech"))
        assertFalse(isOpenAiSpeechEndpoint("https://example.com/audio/speech/wrong"))
    }
}

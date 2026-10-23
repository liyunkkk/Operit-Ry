package com.ai.assistance.operit.ui.features.chat.util

import org.junit.Assert.*
import org.junit.Test

class ChatShortcutTest {
    @Test fun contextDisplayDropsOnlyRedundantDecimalZeros() {
        assertEquals("400", formatContextK(400f))
        assertEquals("300.5", formatContextK(300.5f))
        assertEquals("0.25", formatContextK(0.25f))
        assertEquals(300.5f, parseContextK("${formatContextK(300.5f)}K")!!, 0f)
    }
    @Test fun onlyWholeSingleLineDraftGetsCommandCandidates() {
        assertEquals(listOf(ChatShortcut.COMPACT), chatShortcutSuggestions("/comp"))
        assertEquals(4, chatShortcutSuggestions("/").size)
        listOf("https://example.com/compact", "/sdcard/test", "解释 /compact", "/compact\nhello")
            .forEach { assertTrue(chatShortcutSuggestions(it).isEmpty()) }
    }

    @Test fun parametersRemainDiscoverableAndAreValidated() {
        assertEquals(listOf(ChatShortcut.AUTO_COMPACT), chatShortcutSuggestions("/autocompact 300K"))
        assertEquals(300f, parseContextK("300k")!!, 0f)
        assertNull(parseContextK("-1K"))
        assertNull(parseContextK("NaNK"))
        assertFalse(isValidShortcutArgument(ChatShortcut.COMPACT, "hello"))
        assertTrue(isValidShortcutArgument(ChatShortcut.AUTO_COMPACT, "auto"))
        assertFalse(isValidShortcutArgument(ChatShortcut.MAX, "maybe"))
        assertEquals("", chatShortcutArgument("/com", ChatShortcut.COMPACT))
    }
}

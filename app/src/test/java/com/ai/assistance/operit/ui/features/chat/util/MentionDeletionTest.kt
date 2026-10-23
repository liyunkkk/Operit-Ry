package com.ai.assistance.operit.ui.features.chat.util

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.*
import org.junit.Test

class MentionDeletionTest {
    @Test fun slashCommandDeletesOnlyOneCharacter() {
        val before = TextFieldValue("/compact", TextRange(8))
        val after = TextFieldValue("/compac", TextRange(7))
        assertEquals(after, normalizeAttachedMentionDeletion(before, after) { false }.value)
    }

    @Test fun composingWordRemainsOwnedByImeEvenIfItMatchesAnAttachment() {
        val before = TextFieldValue("/compact", TextRange(8), TextRange(1, 8))
        val after = TextFieldValue("/compac", TextRange(7), TextRange(1, 7))
        assertEquals(after, normalizeAttachedMentionDeletion(before, after) { true }.value)
    }

    @Test fun committedAttachmentCanStillBeDeletedAsOneToken() {
        val before = TextFieldValue("看 /dynamic_ui ", TextRange(14))
        val after = TextFieldValue("看 /dynamic_ui", TextRange(13))
        val result = normalizeAttachedMentionDeletion(before, after) { it == "dynamic_ui" }
        assertEquals("看 ", result.value.text)
        assertEquals("dynamic_ui", result.removedMentionToken)
    }

    @Test fun selectedTextDeletionIsNotExpanded() {
        val before = TextFieldValue("/compact", TextRange(6, 8))
        val after = TextFieldValue("/compa", TextRange(6))
        assertEquals(after, normalizeAttachedMentionDeletion(before, after) { true }.value)
    }
}

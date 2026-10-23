package com.ai.assistance.operit.ui.features.chat.util

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

internal data class MentionDeletionNormalization(
    val value: TextFieldValue,
    val removedMentionToken: String? = null,
)

internal fun normalizeAttachedMentionDeletion(
    previous: TextFieldValue,
    proposed: TextFieldValue,
    isAttached: (String) -> Boolean,
): MentionDeletionNormalization {
    fun unchanged() = MentionDeletionNormalization(proposed)
    // The IME owns composing text, including predictive-word corrections and backspace.
    if (previous.composition != null || proposed.composition != null) return unchanged()
    if (!previous.selection.collapsed || !proposed.selection.collapsed) return unchanged()
    if (previous.text.length != proposed.text.length + 1) return unchanged()
    val oldCursor = previous.selection.start.coerceIn(0, previous.text.length)
    val newCursor = proposed.selection.start.coerceIn(0, proposed.text.length)
    if (newCursor != oldCursor - 1 ||
        previous.text.removeRange(newCursor, oldCursor) != proposed.text) return unchanged()
    val token = findMentionTokenEndingAtCursor(previous.text, oldCursor) ?: return unchanged()
    val name = previous.text.substring(token.start + 1, token.contentEndExclusive)
    if (!isAttached(name)) return unchanged()
    return MentionDeletionNormalization(
        proposed.copy(
            text = previous.text.removeRange(token.start, token.endExclusive),
            selection = TextRange(token.start),
        ),
        name,
    )
}

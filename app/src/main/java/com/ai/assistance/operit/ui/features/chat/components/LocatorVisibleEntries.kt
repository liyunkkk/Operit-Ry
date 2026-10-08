package com.ai.assistance.operit.ui.features.chat.components

import com.ai.assistance.operit.data.model.ChatMessageLocatorPreview
import kotlin.math.abs

/**
 * Rows the transcript hides must not hold a position of their own: the locator numbers the rows it
 * actually lists, one after another and with no gaps. Agent input cards stay out of that list, so
 * they cannot leave a hole in the numbering the way database indices do.
 */
internal fun locatorVisibleEntries(
    entries: List<ChatMessageLocatorPreview>,
): List<ChatMessageLocatorPreview> =
    entries.filterNot { it.resolvedDisplayMode.isCollaborationEvent }
        .mapIndexed { index, entry -> entry.copy(messageIndex = index) }

/**
 * The listed row that stands for a reading position. Search results are numbered within their own
 * list, so the position is matched by timestamp instead of by a number only one list shares. Ties
 * prefer the later row, the way a hidden row already prefers the reply that follows it.
 */
internal fun locatorNearestTimestamp(
    timestamps: List<Long>,
    referenceTimestamp: Long,
): Long? =
    timestamps.minWithOrNull(
        compareBy<Long> { abs(it - referenceTimestamp) }.thenByDescending { it },
    )

/** Hidden collaboration rows locate the closest ordinary row; ties prefer the following reply. */
internal fun locatorCurrentVisiblePosition(
    entries: List<ChatMessageLocatorPreview>,
    timestamp: Long,
): Int {
    val visible = locatorVisibleEntries(entries)
    val exact = visible.indexOfFirst { it.timestamp == timestamp }
    if (exact >= 0 || visible.isEmpty()) return exact
    val sourceIndex = entries.indexOfFirst { it.timestamp == timestamp }
    if (sourceIndex >= 0) {
        val nearest = entries.indices
            .filter { !entries[it].resolvedDisplayMode.isCollaborationEvent }
            .minWithOrNull(compareBy<Int> { abs(it - sourceIndex) }.thenByDescending { it })
        return visible.indexOfFirst { it.timestamp == nearest?.let { index -> entries[index].timestamp } }
    }
    // The visible anchor may have just been removed or not yet persisted in the preview snapshot.
    return visible.indices.minByOrNull {
        abs(visible[it].timestamp.toDouble() - timestamp.toDouble())
    } ?: -1
}

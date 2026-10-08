package com.ai.assistance.operit.ui.features.chat.components

import com.ai.assistance.operit.data.model.ChatMessageLocatorPreview
import org.junit.Assert.assertEquals
import org.junit.Test

class LocatorVisibleEntriesTest {
    @Test fun hiddenEventLocatesNearbyReplyInsteadOfMinusOne() {
        val entries = listOf(
            preview("ASSISTANT_INTERMEDIATE").copy(timestamp = 10),
            preview("COLLABORATION_EVENT").copy(timestamp = 20),
            preview("NORMAL").copy(timestamp = 30),
        )
        assertEquals(1, locatorCurrentVisiblePosition(entries, 20))
        assertEquals(0, locatorCurrentVisiblePosition(entries, 10))
        assertEquals(1, locatorCurrentVisiblePosition(entries, 30))
    }

    @Test fun hiddenTailAndRemovedAnchorStayNearTheirReadingPosition() {
        val entries = listOf(
            preview("NORMAL").copy(timestamp = 10),
            preview("NORMAL").copy(timestamp = 30),
            preview("COLLABORATION_EVENT").copy(timestamp = 40),
        )
        assertEquals(1, locatorCurrentVisiblePosition(entries, 40))
        assertEquals(1, locatorCurrentVisiblePosition(entries, 29))
        assertEquals(-1, locatorCurrentVisiblePosition(emptyList(), 29))
        assertEquals(-1, locatorCurrentVisiblePosition(listOf(preview("COLLABORATION_EVENT")), 1))
    }

    @Test fun agentInputsAreExcludedWithoutHidingHumanSteering() {
        val entries = listOf(
            preview("NORMAL"),
            preview("COLLABORATION_EVENT"),
            preview("COLLABORATION_TASK"),
            preview("ASSISTANT_INTERMEDIATE").copy(sender = "ai"),
            preview("NORMAL"),
        )
        val visible = locatorVisibleEntries(entries)
        assertEquals(listOf("user", "ai", "user"), visible.map { it.sender })
        assertEquals(listOf(0, 1, 2), visible.map { it.messageIndex })
    }

    @Test fun agentCardsDoNotLeaveHolesInTheLocatorNumbers() {
        val entries = listOf(
            preview("NORMAL").copy(timestamp = 10, messageIndex = 0),
            preview("NORMAL").copy(timestamp = 20, messageIndex = 1, sender = "ai"),
            preview("COLLABORATION_EVENT").copy(timestamp = 30, messageIndex = 2),
            preview("NORMAL").copy(timestamp = 40, messageIndex = 3, sender = "ai"),
            preview("COLLABORATION_TASK").copy(timestamp = 50, messageIndex = 4),
            preview("NORMAL").copy(timestamp = 60, messageIndex = 5, sender = "ai"),
        )
        val visible = locatorVisibleEntries(entries)
        // The rows that are listed stay 1, 2, 3, 4 instead of 1, 2, 4, 6.
        assertEquals(listOf(0, 1, 2, 3), visible.map { it.messageIndex })
        assertEquals(listOf(10L, 20L, 40L, 60L), visible.map { it.timestamp })
    }

    @Test fun searchResultsNumberOnlyTheRowsTheyShow() {
        val entries = listOf(
            preview("COLLABORATION_EVENT").copy(messageIndex = 22),
            preview("HIDDEN_PLACEHOLDER").copy(messageIndex = 30),
            preview("future-mode").copy(messageIndex = 42),
        )
        val visible = locatorVisibleEntries(entries)
        assertEquals(listOf("HIDDEN_PLACEHOLDER", "future-mode"), visible.map { it.displayMode })
        assertEquals(listOf(0, 1), visible.map { it.messageIndex })
    }

    @Test fun nearestHitFallsBackToTheLaterRowOnATie() {
        assertEquals(30L, locatorNearestTimestamp(listOf(10L, 30L), 20L))
        assertEquals(40L, locatorNearestTimestamp(listOf(40L, 30L, 10L), 35L))
        assertEquals(30L, locatorNearestTimestamp(listOf(10L, 30L), 31L))
    }

    @Test fun nearestHitWithoutACandidateHighlightsNothing() {
        assertEquals(null, locatorNearestTimestamp(emptyList(), 20L))
    }

    private fun preview(mode: String) = ChatMessageLocatorPreview(
        timestamp = 1, sender = "user", previewContent = "content", contentLength = 7,
        displayMode = mode, isFavorite = false,
    )
}

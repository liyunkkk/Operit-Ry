package com.ai.assistance.operit.api.chat.prediction

import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import com.ai.assistance.operit.data.model.ToolPrompt
import org.junit.Assert.*
import org.junit.Test

class ComposerPredictionSnapshotTest {
    @Test fun effectiveHistoryIncludesFinalAssistantAndIsDetachedFromHookContainers() {
        val values = mutableListOf<Any?>("original")
        val metadata = mutableMapOf<String, Any?>("nested" to values)
        val history = mutableListOf(
            PromptTurn(PromptTurnKind.SYSTEM, "Effective system prefix"),
            PromptTurn(PromptTurnKind.USER, "Question", metadata = metadata),
            PromptTurn(PromptTurnKind.ASSISTANT, "Final answer"),
        )
        val frozen = freezePredictionHistory(history)
        values[0] = "changed"
        metadata["new"] = "extra"
        history.clear()
        assertEquals(3, frozen.size)
        assertEquals("Final answer", frozen.last().content)
        assertEquals(listOf("original"), frozen[1].metadata["nested"])
        assertFalse(frozen[1].metadata.containsKey("new"))
        assertTrue(runCatching { (frozen as MutableList<PromptTurn>).clear() }.isFailure)
    }

    @Test fun unknownMutableMetadataIsRejectedRatherThanShared() {
        assertTrue(runCatching {
            freezePredictionHistory(listOf(PromptTurn(
                PromptTurnKind.ASSISTANT, "Final", metadata = mapOf("unsafe" to StringBuilder("mutable")),
            )))
        }.isFailure)
    }

    @Test fun frozenToolsDetachFromLaterEditsAndTolerateAnAbsentList() {
        val tools = mutableListOf(ToolPrompt(name = "shell", description = "Original"))
        val frozen = freezePredictionTools(tools)
        tools.clear()
        assertEquals(1, frozen.size)
        assertEquals("shell", frozen.single().name)
        assertTrue(runCatching { (frozen as MutableList<ToolPrompt>).clear() }.isFailure)
        assertEquals(emptyList<ToolPrompt>(), freezePredictionTools(null))
    }
}

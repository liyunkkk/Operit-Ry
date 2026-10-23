package com.ai.assistance.operit.ui.features.chat.components

import org.junit.Assert.*
import org.junit.Test

class MemoryEditorProcessTest {
    @Test fun realGeneratedToolTagsStayInsideCollapsedProcessWithoutRewritingNames() {
        val tags = listOf("think", "tool_iWGR", "tool_result_CpjL", "tool_bLxw",
            "tool_result_14pi", "tool_Xonz", "tool_result_Srld", "TOOL_A1", "TOOL_RESULT_B2")
        val processSource = tags.joinToString("\n") { "<$it name=\"package_proxy\">record</$it>" }
        val original = "$processSource\n正文\n<details>正文补充</details>\n```a2ui\n{}\n```\n"
        val parts = parseMessageContentForEditor(original)
        val process = memoryEditorProcessIndices(parts, emptyList())
        assertEquals(tags, process.map { parts[it].tag })
        val visible = parts.filterIndexed { index, part -> index !in process && !part.isSeparator }
        assertEquals(listOf(PartType.TEXT, PartType.XML, PartType.DYNAMIC_UI), visible.map { it.type })
        assertEquals("\n正文\n", visible.first().content)
        assertEquals(original, recomposeMessageFromParts(parts))
    }

    @Test fun foldsThinkingAndToolsButLeavesBodyAndCardsAvailable() {
        val original = "<think>plan</think><tool name=\"x\">args</tool><tool_result>result</tool_result>\nAnswer\n```a2ui\n{}\n```\n<details>useful detail</details>"
        val parts = parseMessageContentForEditor(original)
        val process = memoryEditorProcessIndices(parts, emptyList())
        assertEquals(listOf("think", "tool", "tool_result"), process.map { parts[it].tag })
        assertTrue(parts.indices.filterNot { it in process }.any { parts[it].type == PartType.DYNAMIC_UI })
        assertEquals(original, recomposeMessageFromParts(parts))
    }

    @Test fun groupsIntermediateTextAndCollaborationWithoutTouchingFinalSource() {
        val fragments = listOf(
            MemoryEditFragment(0, 1, "ai", "", "Checking", "Checking", isAssistant = true, isProcess = true),
            MemoryEditFragment(1, 2, "agent", "message", "event", "event"),
            MemoryEditFragment(2, 3, "ai", "", "<think>done</think>\nFinal", "<think>done</think>\nFinal", isAssistant = true),
        )
        val parts = memoryEditorParts(fragments)
        val process = memoryEditorProcessIndices(parts, fragments)
        assertEquals(setOf(0, 1, 2), process)
        val changed = parts.mapIndexed { index, part ->
            if (index !in process && part.type == PartType.TEXT) part.copy(content = "\nEdited") else part
        }
        val saved = updatedMemoryEditorFragments(fragments, parts, changed)
        assertEquals(fragments.take(2), saved.take(2))
        assertEquals("<think>done</think>\nEdited", saved.last().body)
    }

    @Test fun ordinaryMessagesDoNotGetAnEmptyProcessFold() {
        assertTrue(memoryEditorProcessIndices(parseMessageContentForEditor("Just text"), emptyList()).isEmpty())
    }
}

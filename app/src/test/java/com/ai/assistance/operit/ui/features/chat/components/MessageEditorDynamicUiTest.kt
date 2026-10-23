package com.ai.assistance.operit.ui.features.chat.components

import org.junit.Assert.*
import org.junit.Test

class MessageEditorDynamicUiTest {
    private val card = "```a2ui\r\n{\"html\":\"<div class='glass'>hello</div>\",\"value\":1}\r\n```\r\n"

    @Test fun cardHtmlStaysOpaqueAndEditingTextPreservesSource() {
        val original = "Before\r\n\r\n${card}After"
        val parts = parseMessageContentForEditor(original)
        assertEquals(listOf(PartType.TEXT, PartType.DYNAMIC_UI, PartType.TEXT), parts.map { it.type })
        assertEquals(card, parts[1].content)
        assertEquals(original, recomposeMessageFromParts(parts))
        val changed = parts.toMutableList().apply { this[0] = this[0].copy(content = "Updated\n") }
        assertEquals("Updated\n${card}After", recomposeMessageFromParts(changed))
    }

    @Test fun multipleCardsAndXmlPreserveAllWhitespace() {
        val original = "<think>plan</think>\n\n${card} \n```a2ui-ref\nref-id\n```\n\n"
        val parts = parseMessageContentForEditor(original)
        assertEquals(2, parts.count { it.type == PartType.DYNAMIC_UI })
        assertEquals(original, recomposeMessageFromParts(parts))
        val withoutCards = parts.filter { it.type != PartType.DYNAMIC_UI }
        assertEquals("<think>plan</think>\n\n \n\n", recomposeMessageFromParts(withoutCards))
    }

    @Test fun normalCodeFenceDoesNotParseEmbeddedCardOrHtml() {
        val original = "````markdown\n$card<div>example</div>\n````\n"
        val parts = parseMessageContentForEditor(original)
        assertEquals(listOf(PartType.TEXT), parts.map { it.type })
        assertEquals(original, recomposeMessageFromParts(parts))
    }

    @Test fun unfinishedCardIsNotRepairedOrTruncatedByEditor() {
        val original = "Text\n```a2ui\n{\"html\":\"<div>incomplete</div>\""
        val parts = parseMessageContentForEditor(original)
        assertEquals(PartType.DYNAMIC_UI, parts.last().type)
        assertEquals(original, recomposeMessageFromParts(parts))
    }

    @Test fun xmlToolResultsRemainOneCollapsedPart() {
        val original = "<tool_result name=\"card\">$card</tool_result>\n"
        val parts = parseMessageContentForEditor(original)
        assertEquals(PartType.XML, parts.first().type)
        assertEquals(original, recomposeMessageFromParts(parts))
    }
}

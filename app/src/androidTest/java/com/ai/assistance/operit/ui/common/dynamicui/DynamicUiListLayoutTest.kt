package com.ai.assistance.operit.ui.common.dynamicui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Uses only in-memory cards without a chat owner; never reads or writes user chat/state files. */
@RunWith(AndroidJUnit4::class)
class DynamicUiListLayoutTest {
    @get:Rule val compose = createComposeRule()

    private fun show(source: String) {
        val document = CardExpress.compile(source)
        compose.setContent {
            MaterialTheme {
                LazyColumn(Modifier.fillMaxSize()) {
                    item { DynamicUiCard(document, closed = true, streaming = false, blockIndex = 0) }
                }
            }
        }
    }

    private fun awaitText(text: String) {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(text).assertIsDisplayed()
    }

    @Test fun tabListsAndNestedListsMeasureInsideChat() {
        show("""
            $/tab = "first"
            root = Column(children=[tabs])
            tabs = Tabs(label="Sections", labels=["First tab","Second tab"], children=[first,second], value=$/tab)
            first = List(children=[Text(text="First row"),nested])
            nested = List(children=[Text(text="Nested row")])
            second = List(children=[Text(text="Second row")])
        """.trimIndent())
        awaitText("Nested row")
        compose.onNodeWithText("Second tab").performClick()
        awaitText("Second row")
        compose.onNodeWithText("First tab").performClick()
        awaitText("First row")
    }

    @Test fun boundedScrollCanContainFullStaticList() {
        val children = (1..32).joinToString(",") { "Text(text=\"Row $it\")" }
        show("root = Scroll(maxHeight=120, child=List(children=[$children]))")
        awaitText("Row 1")
        compose.onNodeWithText("Row 32").performScrollTo().assertIsDisplayed()
    }
}

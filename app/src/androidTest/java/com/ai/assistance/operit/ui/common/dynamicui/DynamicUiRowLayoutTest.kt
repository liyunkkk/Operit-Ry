package com.ai.assistance.operit.ui.common.dynamicui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** In-memory fixtures only. No owner, persisted card state, or user chat data. */
@RunWith(AndroidJUnit4::class)
class DynamicUiRowLayoutTest {
    @get:Rule val compose = createComposeRule()

    private fun showHeader(width: Int, weighted: Boolean, fontScale: Float = 1f) {
        val document = CardExpress.compile("""
            root=Row(children=[logo,caption],gap="md",align="center"${if (weighted) ",weights=[0,1]" else ""})
            logo=Panel(padding="sm",background="inset",child=Vector(label="Orbit logo",width=32,height=32,shapes=[{type:"circle",cx:16,cy:16,r:10,stroke:2.5}]))
            caption=Column(gap="xs",children=[Text(text="Orbit",variant="h1"),RichText(runs=[{text:"Your companion, your choice.",tone:"muted"}])])
        """.trimIndent())
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                MaterialTheme {
                    Column(Modifier.width(width.dp).testTag("host")) {
                        DynamicUiCard(document, closed = true, streaming = false, blockIndex = 0)
                    }
                }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Orbit").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun existingUnweightedLogoDoesNotExpandToTheWholeRow() {
        showHeader(300, weighted = false)
        compose.onNodeWithContentDescription("Orbit logo").assertWidthIsEqualTo(32.dp)
        val text = compose.onNodeWithText("Your companion, your choice.").getUnclippedBoundsInRoot()
        assertTrue(text.right - text.left >= 120.dp)
        assertTrue(text.bottom - text.top < 100.dp)
        compose.onNodeWithText("Orbit").assertIsDisplayed()
    }

    @Test fun weightedHeaderKeepsLogoBesideTitle() {
        showHeader(300, weighted = true)
        val logo = compose.onNodeWithContentDescription("Orbit logo").getUnclippedBoundsInRoot()
        val title = compose.onNodeWithText("Orbit").getUnclippedBoundsInRoot()
        assertTrue(title.left >= logo.right)
        compose.onNodeWithText("Your companion, your choice.").assertIsDisplayed()
    }

    @Test fun narrowLargeFontHeaderStacksAndKeepsFullText() {
        showHeader(180, weighted = true, fontScale = 1.5f)
        val logo = compose.onNodeWithContentDescription("Orbit logo").getUnclippedBoundsInRoot()
        val title = compose.onNodeWithText("Orbit").getUnclippedBoundsInRoot()
        assertTrue(title.top >= logo.bottom)
        val text = compose.onNodeWithText("Your companion, your choice.").getUnclippedBoundsInRoot()
        assertTrue(text.right - text.left >= 120.dp)
    }

    @Test fun legacyNativeSingleChildRowUsesOneMeasuredSlot() {
        val document = """
            {"version":"v0.9","createSurface":{"surfaceId":"card","catalogId":"${DynamicUiProtocol.CATALOG}"}}
            {"version":"v0.9","updateComponents":{"surfaceId":"card","components":[{"id":"root","component":"Row","child":"title","wrap":false},{"id":"title","component":"Text","text":"Legacy single child"}]}}
        """.trimIndent()
        compose.setContent {
            MaterialTheme { DynamicUiCard(document, closed = true, streaming = false, blockIndex = 0) }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Legacy single child").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Legacy single child").assertIsDisplayed()
    }

    @Test fun reflowPreservesInputStateWithoutRemeasuringAChildTwice() {
        var width by mutableIntStateOf(300)
        compose.setContent {
            MaterialTheme {
                CardAdaptiveRow(
                    listOf(0, 1), 16.dp, 120.dp, Arrangement.spacedBy(16.dp),
                    Alignment.CenterVertically, Modifier.width(width.dp),
                ) {
                    Box(Modifier.size(48.dp).testTag("logo"))
                    Box(propagateMinConstraints = true) {
                        var value by remember { mutableStateOf("") }
                        OutlinedTextField(value, { value = it }, Modifier.testTag("input"))
                    }
                }
            }
        }
        compose.onNodeWithTag("input").performTextInput("Keep this draft")
        compose.runOnIdle { width = 170 }
        compose.onNodeWithTag("input").assertTextContains("Keep this draft")
        assertTrue(compose.onNodeWithTag("input").getUnclippedBoundsInRoot().top >=
            compose.onNodeWithTag("logo").getUnclippedBoundsInRoot().bottom)
        compose.runOnIdle { width = 300 }
        compose.onNodeWithTag("input").assertTextContains("Keep this draft")
    }
}

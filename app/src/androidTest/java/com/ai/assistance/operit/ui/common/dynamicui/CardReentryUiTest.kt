package com.ai.assistance.operit.ui.common.dynamicui

import android.content.ContextWrapper
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated files only. Force the same disposal/restoration used by a keyed lazy item. */
@RunWith(AndroidJUnit4::class)
class CardReentryUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun restoredCardNeverDisplaysDefaultsOrCollapsesItsMeasuredSlot() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val isolated = File(base.cacheDir, "card-reentry-${UUID.randomUUID()}")
        val context = object : ContextWrapper(base) {
            override fun getFilesDir() = isolated
        }
        val document = CardExpress.compile("""
            ${'$'}/view = {selected:false,rows:[{id:"initial",title:"INITIAL_ROW"}]}
            root = Column(children=[change, status, details, rows])
            change = Button(child=Text(text="Change"),action=Event(name="local_action",context={op:"set",path:"/view",value:{selected:true,rows:[{id:"restored",title:"RESTORED_ROW"}]}}))
            status = Text(text=if(condition=${'$'}/view/selected,then="RESTORED_STATE",else="INITIAL_STATE"))
            details = Conditional(visible=${'$'}/view/selected,children=[Text(text="First detail"),Text(text="Second detail"),Text(text="Third detail")])
            rows = Repeat(items=${'$'}/view/rows,key="id",child=Text(text=item(field="title")))
        """.trimIndent())
        val owner = DynamicUiOwner("isolated-reentry", 1L, 0, document)
        var attached by mutableStateOf(true)
        val heights = mutableListOf<Int>()
        compose.setContent {
            val holder = rememberSaveableStateHolder()
            MaterialTheme {
                CompositionLocalProvider(LocalContext provides context, LocalDynamicUiOwner provides owner) {
                    if (attached) holder.SaveableStateProvider("card") {
                        Box(Modifier.width(280.dp).onSizeChanged { heights.add(it.height) }) {
                            DynamicUiCard(document, closed = true, streaming = false, blockIndex = 0)
                        }
                    }
                }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Change") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Change").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("RESTORED_ROW").fetchSemanticsNodes().isNotEmpty() }
        val previousHeight = compose.runOnIdle { heights.last() }
        compose.runOnIdle { attached = false }
        compose.waitForIdle()
        compose.runOnIdle { heights.clear() }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { attached = true }
        try {
            val deadline = System.nanoTime() + 10_000_000_000L
            var restored = false
            while (System.nanoTime() < deadline && !restored) {
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                compose.onNodeWithText("INITIAL_STATE").assertDoesNotExist()
                compose.onNodeWithText("INITIAL_ROW").assertDoesNotExist()
                restored = compose.onAllNodesWithText("RESTORED_ROW").fetchSemanticsNodes().isNotEmpty()
                if (!restored) Thread.sleep(10)
            }
            assertTrue("Restoration did not finish", restored)
            compose.runOnIdle {
                assertTrue(heights.isNotEmpty())
                assertTrue("Heights before/after recovery: $previousHeight / $heights",
                    heights.all { kotlin.math.abs(it - previousHeight) <= 1 })
            }
        } finally {
            compose.mainClock.autoAdvance = true
            // No cleanup of real app data; the unique cache directory belongs solely to this test.
        }
    }
}

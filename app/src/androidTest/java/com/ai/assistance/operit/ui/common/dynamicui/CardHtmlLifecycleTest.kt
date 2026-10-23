package com.ai.assistance.operit.ui.common.dynamicui

import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ai.assistance.operit.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated in-memory content only; no user chats, files or preferences. */
@RunWith(AndroidJUnit4::class)
class CardHtmlLifecycleTest {
    @get:Rule val compose = createComposeRule()

    private fun visuals(view: View): List<OfflineCardWebView> = buildList {
        if (view is OfflineCardWebView) add(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) addAll(visuals(view.getChildAt(i)))
    }

    @Test fun tapIsRequiredAndOffscreenViewsAreReleasedThenRecreated() {
        lateinit var root: View
        lateinit var scroll: androidx.compose.foundation.ScrollState
        lateinit var scope: CoroutineScope
        val document = CardExpress.compile("""root=Html(label="Preview",html="<p>Offline visual</p>")""")
        compose.setContent {
            root = LocalView.current.rootView
            scroll = rememberScrollState()
            scope = rememberCoroutineScope()
            MaterialTheme {
                Column(Modifier.width(300.dp).height(260.dp).verticalScroll(scroll)) {
                    DynamicUiCard(document, closed = true, streaming = false, blockIndex = 0)
                    Spacer(Modifier.height(1200.dp))
                }
            }
        }
        compose.runOnIdle { assertTrue(visuals(root).isEmpty()) }
        val loadLabel = root.context.getString(R.string.dynamic_ui_html_click_to_load)
        compose.waitUntil(10_000) { compose.onAllNodesWithText(loadLabel).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(loadLabel).performClick()
        compose.waitUntil(10_000) { compose.runOnIdle { visuals(root).size == 1 } }
        val first = compose.runOnIdle { visuals(root).single() }
        compose.runOnIdle { scope.launch { scroll.scrollTo(scroll.maxValue) } }
        compose.waitUntil(10_000) { compose.runOnIdle { visuals(root).isEmpty() } }
        compose.runOnIdle { scope.launch { scroll.scrollTo(0) } }
        compose.waitUntil(10_000) { compose.runOnIdle { visuals(root).size == 1 } }
        compose.runOnIdle { assertNotSame(first, visuals(root).single()) }
    }

    @Test fun loadedHtmlSurvivesRepeatedLazyItemReuse() {
        lateinit var root: View
        lateinit var list: androidx.compose.foundation.lazy.LazyListState
        lateinit var scope: CoroutineScope
        val document = CardExpress.compile("""
            root=Html(label="Glass",html="<div class='glass'>Glass preview</div>",css=".glass{height:600px;background:linear-gradient(45deg,#aaf,#afa);backdrop-filter:blur(12px)}")
        """.trimIndent())
        compose.setContent {
            root = LocalView.current.rootView
            list = rememberLazyListState()
            scope = rememberCoroutineScope()
            MaterialTheme {
                LazyColumn(Modifier.width(300.dp).height(260.dp), state = list) {
                    item(key = "html") {
                        DynamicUiCard(document, closed = true, streaming = false, blockIndex = 0)
                    }
                    items(30, key = { "spacer-$it" }) { Spacer(Modifier.height(200.dp)) }
                }
            }
        }
        val loadLabel = root.context.getString(R.string.dynamic_ui_html_click_to_load)
        compose.waitUntil(10_000) { compose.onAllNodesWithText(loadLabel).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(loadLabel).performClick()
        fun awaitLoadedVisual() {
            compose.waitUntil(10_000) {
                compose.runOnIdle {
                    val view = visuals(root).singleOrNull()
                    val prefix = view?.heightToken?.let { "$it:" }
                    val title = view?.title.orEmpty()
                    prefix != null && title.startsWith(prefix) &&
                        (title.removePrefix(prefix).toIntOrNull() ?: 0) >= 600
                }
            }
        }
        awaitLoadedVisual()
        repeat(5) {
            val previous = compose.runOnIdle { visuals(root).single() }
            compose.runOnIdle { scope.launch { list.scrollToItem(25) } }
            compose.waitUntil(10_000) { compose.runOnIdle { visuals(root).isEmpty() } }
            compose.runOnIdle {
                assertNull(previous.parent)
                assertNull(previous.heightToken)
            }
            compose.runOnIdle { scope.launch { list.scrollToItem(0) } }
            awaitLoadedVisual()
            compose.runOnIdle { assertNotSame(previous, visuals(root).single()) }
        }
    }
}

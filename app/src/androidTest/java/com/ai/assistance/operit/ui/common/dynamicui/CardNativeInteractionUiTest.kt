package com.ai.assistance.operit.ui.common.dynamicui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Memory-only cards, no owner or access to the user's chat and state files. */
@RunWith(AndroidJUnit4::class)
class CardNativeInteractionUiTest {
    @get:Rule val compose = createComposeRule()
    private fun show(source: String, dark: Boolean = false, large: Boolean = false) {
        val document = CardExpress.compile(source)
        compose.setContent {
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, if (large) 1.8f else 1f)) {
                    LazyColumn(Modifier.width(280.dp).fillMaxHeight()) {
                        item { DynamicUiCard(document, true, false, 0) }
                    }
                }
            }
        }
    }
    private fun await(text: String) = compose.waitUntil(10_000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun nodeHasClickAndSelectionSemanticsAndDisabledCannotChangeState() {
        show("""
            $/state = {chosen:false}
            root = Column(children=[Pressable(label="Select step",selected=$/state/chosen,
                disabled=$/state/chosen,child=Text(text="Long accessible step title"),
                action=Event(name="local_action",context={op:"set",path:"/state/chosen",value:true})),
                Text(text=if(condition=$/state/chosen,then="Selected",else="Waiting"))])
        """.trimIndent(), dark = true, large = true)
        await("Waiting")
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasContentDescription("Select step") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Select step").assertHasClickAction().performClick()
        await("Selected")
        compose.onNodeWithContentDescription("Select step").assertIsSelected().assertIsNotEnabled()
    }

    @Test fun changeDoesNotFireAtInitializationAndStillFiresWhenInputDisablesItself() {
        show("""
            $/state = {done:false,echo:"Untouched"}
            root = Column(children=[Switch(label="Finish",checked=$/state/done,disabled=$/state/done,
                onChange=Event(name="local_action",context={op:"set",path:"/state/echo",value:"Changed"})),
                Text(text=$/state/echo)])
        """.trimIndent())
        await("Untouched")
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText("Finish") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Finish").performClick()
        await("Changed")
        compose.onNodeWithText("Finish").assertIsNotEnabled()
    }

    @Test fun invalidVectorCanRecoverWithoutFreezingItsInput() {
        show("""
            $/number = "2"
            root = Column(children=[TextField(label="Divisor",value=$/number),
                Vector(label="Arrow",width=100,height=50,shapes=[
                    {type:"arrow",x1:1,y1:10,x2:divide(a=80,b=$/number),y2:10}])])
        """.trimIndent())
        await("Divisor")
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("Arrow").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodes(hasSetTextAction() and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasSetTextAction()).performTextReplacement("0")
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Arrow").fetchSemanticsNodes().isEmpty() }
        compose.onNode(hasSetTextAction()).assertIsEnabled().performTextReplacement("2")
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Arrow").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun loadingPreventsActionsAndStepperCanReturnToPreviousSteps() {
        show("""
            $/state={busy:true,step:1,echo:"Waiting"}
            root=Column(children=[
                Switch(label="Busy",checked=$/state/busy),
                Button(child=Text(text="Run"),loading=$/state/busy,
                    action=Event(name="local_action",context={op:"set",path:"/state/echo",value:"Ran"})),
                Stepper(label="Steps",labels=["First","Second"],current=$/state/step),
                Text(text=$/state/echo)
            ])
        """.trimIndent(), dark = true, large = true)
        await("Waiting")
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Busy") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Run").assertIsNotEnabled()
        compose.onNodeWithText("First").performClick().assertIsSelected()
        compose.onNodeWithText("Second").performClick().assertIsSelected()
        compose.onNodeWithText("Busy").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Run") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Run").performClick()
        await("Ran")
    }

    @Test fun formulaRecoversFromPartialInputEvenWhenThereIsARepeat() {
        show("""
            $/formula="x"
            $/items=[{id:"a"}]
            root=Column(children=[TextField(label="Formula input",value=$/formula),
                Formula(label="Equation",text=$/formula),
                Repeat(items=$/items,key="id",child=Text(text="Row"))])
        """.trimIndent())
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("Equation").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodes(hasSetTextAction() and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasSetTextAction()).performTextReplacement("\\frac{")
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Equation").fetchSemanticsNodes().isEmpty() }
        compose.onNode(hasSetTextAction()).assertTextContains("\\frac{")
            .performTextReplacement("\\frac{1}{2}")
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Equation").fetchSemanticsNodes().isNotEmpty() }
    }
}

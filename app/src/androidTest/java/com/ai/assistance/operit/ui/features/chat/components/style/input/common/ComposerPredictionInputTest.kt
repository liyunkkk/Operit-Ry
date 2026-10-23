package com.ai.assistance.operit.ui.features.chat.components.style.input.common

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Run on a device for Basic, outlined and filled editors; this does not substitute for real IME testing. */
@RunWith(Parameterized::class)
class ComposerPredictionInputTest(private val style: String) {
    @get:Rule val compose = createComposeRule()
    private val draft = mutableStateOf(TextFieldValue(""))
    private var prediction by mutableStateOf("A multiline suggestion\nwith another line")
    private var adoptions = 0
    private var trailingClicks = 0

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "style={0}")
        fun fields() = listOf(arrayOf("basic"), arrayOf("outlined"), arrayOf("filled"))
    }

    private fun showField() {
        compose.setContent {
            MaterialTheme {
                val transformation = rememberComposerPredictionTransformation(
                    draft.value, prediction, VisualTransformation.None,
                )
                val modifier = Modifier.width(220.dp).height(180.dp).testTag("composer")
                val accept: (String) -> Boolean = { text ->
                    if (draft.value.text.isEmpty() && draft.value.composition == null) {
                        draft.value = TextFieldValue(text, TextRange(text.length))
                        adoptions++
                        true
                    } else false
                }
                if (style != "basic") {
                    ComposerPredictionTextField(
                        value = draft.value, onValueChange = { draft.value = it },
                        prediction = prediction, onAcceptPrediction = accept, modifier = modifier,
                        outlined = style == "outlined",
                        visualTransformation = transformation,
                        maxLines = if (style == "filled") Int.MAX_VALUE else 3,
                        trailingIcon = {
                            IconButton(onClick = { trailingClicks++ }, Modifier.testTag("trailing")) { Text("+") }
                        },
                    )
                } else {
                    BasicTextField(
                        value = draft.value, onValueChange = { draft.value = it },
                        modifier = modifier
                            .composerPredictionSemantics(draft.value, prediction, onAccept = accept)
                            .composerPredictionGesture(draft.value, prediction, onAccept = accept),
                        textStyle = MaterialTheme.typography.bodyLarge,
                        visualTransformation = transformation,
                        maxLines = if (visibleComposerPrediction(draft.value, prediction) == null) 3 else Int.MAX_VALUE,
                        decorationBox = { inner ->
                            ComposerPredictionViewport(draft.value, prediction,
                                MaterialTheme.typography.bodyLarge, 3,
                                innerTextField = inner)
                        },
                    )
                }
            }
        }
    }

    @Test fun ghostIsNotEditableTextAndTypingThenClearingRestoresIt() {
        showField()
        val field = compose.onNodeWithTag("composer")
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        field.performTextInput("typed")
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("typed")))
        field.performTextClearance()
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, prediction))
        compose.runOnIdle { assertEquals(0, adoptions) }
    }

    @Test fun accessibilityAdoptionCanBeRepeatedAfterClearWithoutSending() {
        showField()
        val field = compose.onNodeWithTag("composer")
        repeat(3) {
            val actions = field.fetchSemanticsNode().config[SemanticsActions.CustomActions]
            compose.runOnIdle { assertTrue(actions.single().action()) }
            compose.runOnIdle {
                assertEquals(prediction, draft.value.text)
                assertEquals(TextRange(prediction.length), draft.value.selection)
            }
            field.performTextClearance()
        }
        compose.runOnIdle { assertEquals(3, adoptions) }
    }

    @Test fun singleTapAndLongPressRemainNativeAndOnlyDoubleTapAdopts() {
        showField()
        val field = compose.onNodeWithTag("composer")
        field.performTouchInput { click() }
        field.assertIsFocused()
        compose.runOnIdle { assertEquals("", draft.value.text) }
        field.performTouchInput { longClick() }
        compose.runOnIdle { assertEquals(0, adoptions) }
        val layouts = mutableListOf<TextLayoutResult>()
        field.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val target = layouts.single().getBoundingBox(0).center
        compose.onNodeWithTag("composer_prediction_text", useUnmergedTree = true)
            .performTouchInput { doubleClick(target) }
        compose.runOnIdle { assertEquals(1, adoptions) }
        field.performTouchInput { doubleClick() }
        compose.runOnIdle { assertEquals(1, adoptions) }
    }
    @Test fun blankEditorAreaAdoptsButTrailingControlsDoNot() {
        prediction = "Hi"
        showField()
        if (style != "basic") compose.onNodeWithTag("trailing").performTouchInput { doubleClick() }
        compose.runOnIdle {
            assertEquals(0, adoptions)
            assertEquals("", draft.value.text)
            if (style != "basic") assertEquals(2, trailingClicks)
        }
        val field = compose.onNodeWithTag("composer")
        for (top in listOf(true, false)) {
            field.performTouchInput {
                advanceEventTime(600)
                doubleClick(Offset(width / 2f, if (top) 2f else height - 2f))
            }
            compose.runOnIdle { assertEquals(prediction, draft.value.text) }
            field.performTextClearance()
        }
        compose.runOnIdle { assertEquals(2, adoptions) }
    }

    @Test fun doubleTapOnWordSpaceAdoptsPrediction() {
        showField()
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("composer").performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
            it(layouts)
        }
        val space = layouts.single().getBoundingBox(prediction.indexOf(' ')).center
        compose.onNodeWithTag("composer_prediction_text", useUnmergedTree = true)
            .performTouchInput { doubleClick(space) }
        compose.runOnIdle { assertEquals(1, adoptions) }
    }

    @Test fun doubleTapAllowsNativeDistanceBetweenTapsWithoutAcceptingADrag() {
        showField()
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("composer").performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
            it(layouts)
        }
        val layout = layouts.single()
        val first = layout.getBoundingBox(0).center
        val second = layout.getBoundingBox(5).center
        val config = android.view.ViewConfiguration.get(
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext,
        )
        assertTrue((second - first).getDistance() > config.scaledTouchSlop)
        assertTrue((second - first).getDistance() < config.scaledDoubleTapSlop)
        val viewport = compose.onNodeWithTag("composer_prediction_text", useUnmergedTree = true)
        viewport.performTouchInput {
            down(first)
            moveTo(second, delayMillis = 50)
            up()
        }
        compose.runOnIdle { assertEquals(0, adoptions) }
        viewport.performTouchInput {
            advanceEventTime(600)
            click(first)
            advanceEventTime(100)
            click(second)
        }
        compose.runOnIdle { assertEquals(1, adoptions) }
    }

    @Test fun cachedPredictionKeepsScrollButNewPredictionStartsAtBeginning() {
        prediction = (1..24).joinToString("\n") { "Old prediction line $it" }
        showField()
        fun viewport() = compose.onNodeWithTag("composer_prediction_text", useUnmergedTree = true)
        viewport().performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 80f) }
        compose.waitForIdle()
        val oldPosition = viewport().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertTrue(oldPosition > 0f)

        compose.runOnIdle { draft.value = TextFieldValue("typed draft") }
        compose.runOnIdle { draft.value = TextFieldValue("") }
        assertEquals(oldPosition,
            viewport().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(), 0f)

        compose.runOnIdle { draft.value = TextFieldValue("another draft") }
        compose.runOnIdle {
            prediction = (1..24).joinToString("\n") { "New prediction line $it" }
            draft.value = TextFieldValue("")
        }
        assertEquals(0f,
            viewport().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(), 0f)
    }

    @Test fun scrolledUnfocusedGhostCanBeAdoptedWithoutFirstTapMovingTheTarget() {
        prediction = (1..24).joinToString("\n") { "Prediction line $it" }
        showField()
        val field = compose.onNodeWithTag("composer")
        val viewport = compose.onNodeWithTag("composer_prediction_text", useUnmergedTree = true)
        viewport.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100f) }
        compose.waitForIdle()
        field.assertIsNotFocused()

        val layouts = mutableListOf<TextLayoutResult>()
        field.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val node = viewport.fetchSemanticsNode()
        val scroll = node.config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertTrue(scroll > 0f)
        val layout = layouts.single()
        val line = layout.getLineForVerticalPosition(scroll + node.boundsInRoot.height * 0.65f)
        val glyph = layout.getBoundingBox(layout.getLineStart(line)).center
        val target = Offset(glyph.x, glyph.y - scroll)

        // Give focus and bring-into-view work frames between taps, as real separate taps would.
        compose.mainClock.autoAdvance = false
        try {
            viewport.performTouchInput { click(target) }
            compose.mainClock.advanceTimeBy(80)
            viewport.performTouchInput {
                advanceEventTime(80)
                click(target)
            }
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, adoptions)
            assertEquals(prediction, draft.value.text)
        }
    }

}

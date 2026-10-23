package com.ai.assistance.operit.ui.features.chat.components.style.input.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import org.junit.Assert.*
import org.junit.Test

class ComposerPredictionTransformationTest {
    @Test fun ghostNeverChangesDraftAndAllOffsetsMapToEmptyDraft() {
        val suggestion = "请继续解释\n" + "a long prediction ".repeat(50)
        val draft = TextFieldValue("")
        val result = ComposerPredictionTransformation(suggestion, Color.Gray, VisualTransformation.None)
            .filter(draft.annotatedString)
        assertEquals(suggestion, result.text.text)
        assertEquals("", draft.text)
        assertEquals(0, result.offsetMapping.originalToTransformed(0))
        for (offset in 0..suggestion.length) {
            assertEquals(0, result.offsetMapping.transformedToOriginal(offset))
        }
    }

    @Test fun userTextKeepsItsNativeTransformation() {
        val input = AnnotatedString("@person pasted\nvoice draft")
        val result = ComposerPredictionTransformation("ghost", Color.Gray, VisualTransformation.None)
            .filter(input)
        assertEquals(input, result.text)
        for (offset in 0..input.length) {
            assertEquals(offset, result.offsetMapping.originalToTransformed(offset))
            assertEquals(offset, result.offsetMapping.transformedToOriginal(offset))
        }
    }

    @Test fun typingAndCompositionHideWithoutChangingCachedPrediction() {
        val cached = "next message"
        repeat(3) {
            assertEquals(cached, visibleComposerPrediction(TextFieldValue(""), cached))
            assertNull(visibleComposerPrediction(TextFieldValue("draft"), cached))
            assertNull(visibleComposerPrediction(TextFieldValue(cached), cached))
            assertNull(visibleComposerPrediction(TextFieldValue("", composition = TextRange.Zero), cached))
            assertEquals(cached, visibleComposerPrediction(TextFieldValue(""), cached))
        }
        assertNull(visibleComposerPrediction(TextFieldValue(""), " \n"))
        assertNull(visibleComposerPrediction(TextFieldValue(""), null))
    }
}

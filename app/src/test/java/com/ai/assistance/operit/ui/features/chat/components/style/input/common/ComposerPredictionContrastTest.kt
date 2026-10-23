package com.ai.assistance.operit.ui.features.chat.components.style.input.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import com.ai.assistance.operit.ui.theme.rainyBaseColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerPredictionContrastTest {
    @Test
    fun `muted predictions meet small text contrast in light and dark composers`() {
        for (dark in listOf(false, true)) {
            val scheme = rainyBaseColorScheme(dark)
            val ghost = composerPredictionColor(scheme)
            assertEquals(scheme.onSurfaceVariant, ghost)
            val surfaces = listOf(
                scheme.surface, // Classic and fullscreen
                if (dark) lerp(scheme.surface, scheme.onSurface, 0.08f) else scheme.surface,
            )
            for (surface in surfaces) {
                val ratio = contrast(ghost, surface)
                assertTrue("dark=$dark surface=$surface contrast=$ratio", ratio >= 4.5f)
            }
        }
    }

    private fun contrast(foreground: Color, background: Color): Float {
        val text = foreground.compositeOver(background).luminance()
        val surface = background.luminance()
        return (maxOf(text, surface) + 0.05f) / (minOf(text, surface) + 0.05f)
    }
}

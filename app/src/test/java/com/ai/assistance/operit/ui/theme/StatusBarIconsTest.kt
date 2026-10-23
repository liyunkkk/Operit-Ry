package com.ai.assistance.operit.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** System icons remain legible on default and custom chrome. */
class StatusBarIconsTest {
    @Test
    fun `default chrome gets dark icons in light mode and light icons in dark mode`() {
        assertTrue(resolveStatusBarDarkIcons(rainyBaseColorScheme(false).surfaceContainerHigh))
        assertFalse(resolveStatusBarDarkIcons(rainyBaseColorScheme(true).surfaceContainerHigh))
        // The bar actually paints rainyAppBarFill.
        assertTrue(resolveStatusBarDarkIcons(rainyBaseColorScheme(false).rainyAppBarFill))
        assertFalse(resolveStatusBarDarkIcons(rainyBaseColorScheme(true).rainyAppBarFill))
    }

    @Test
    fun `a custom bar colour is answered for that colour`() {
        val nearBlack = Color(0xFF102030.toInt())
        val white = Color(0xFFFFFFFF.toInt())

        // The flip used to draw dark icons here, at 1.27:1; light ones measure 16.52:1.
        assertTrue(contrastRatio(Color.Black, nearBlack) < 1.5)
        assertFalse(resolveStatusBarDarkIcons(nearBlack))
        assertTrue(contrastRatio(Color.White, nearBlack) >= 4.5)

        // And white icons on a white bar measured 1:1.
        assertTrue(resolveStatusBarDarkIcons(white))
        assertTrue(contrastRatio(Color.Black, white) >= 4.5)
        assertTrue(resolveStatusBarDarkIcons(Color(0xFFFFF0F5.toInt())))
    }

    /** Independent WCAG contrast check, so the assertion does not reuse the production maths. */
    private fun contrastRatio(first: Color, second: Color): Double {
        fun channel(value: Float): Double =
            if (value <= 0.03928f) value.toDouble() / 12.92
            else Math.pow(((value + 0.055f) / 1.055f).toDouble(), 2.4)

        fun luminance(color: Color): Double =
            0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)

        val firstLuminance = luminance(first)
        val secondLuminance = luminance(second)
        return (maxOf(firstLuminance, secondLuminance) + 0.05) /
            (minOf(firstLuminance, secondLuminance) + 0.05)
    }
}

package com.ai.assistance.operit.ui.main.components

import androidx.compose.ui.graphics.Color
import com.ai.assistance.operit.data.preferences.UserPreferencesManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Opaque toolbars protect text contrast; transparent toolbars preserve the chosen image overlay.
 */
class AppBarContentColorTest {
    @Test
    fun `forced text is rescued on the default opaque bar but retained on transparent bars`() {
        for ((fill, mode, expected) in listOf(
            Triple(Color(0xFFFFF1F5), UserPreferencesManager.APP_BAR_CONTENT_COLOR_MODE_LIGHT, Color.Black),
            Triple(Color(0xFF241A20), UserPreferencesManager.APP_BAR_CONTENT_COLOR_MODE_DARK, Color.White),
        )) {
            val actual = resolveAppBarContentColor(
                customAppBarFill = null,
                forceContentColor = true,
                contentColorMode = mode,
                themeContentColor = expected,
                defaultAppBarFill = fill,
            )
            assertEquals(expected, actual)
            assertTrue(contrastRatio(actual, fill) >= 4.5)
        }
        assertEquals(
            Color.White,
            resolveAppBarContentColor(
                customAppBarFill = null,
                forceContentColor = true,
                contentColorMode = UserPreferencesManager.APP_BAR_CONTENT_COLOR_MODE_LIGHT,
                themeContentColor = Color.Black,
                defaultAppBarFill = null,
            ),
        )
    }

    @Test
    fun `without a custom fill the theme label tone is kept`() {
        val themeOnPrimary = Color(0xFF392B33.toInt())

        assertEquals(
            themeOnPrimary,
            appBarColor(fill = null, force = false, mode = UserPreferencesManager.ON_COLOR_MODE_AUTO, themeOnPrimary),
        )
        assertEquals(
            Color.White,
            appBarColor(fill = null, force = true, mode = UserPreferencesManager.APP_BAR_CONTENT_COLOR_MODE_LIGHT, themeOnPrimary),
        )
        assertEquals(
            Color.Black,
            appBarColor(fill = null, force = true, mode = UserPreferencesManager.APP_BAR_CONTENT_COLOR_MODE_DARK, themeOnPrimary),
        )
        assertEquals(
            themeOnPrimary,
            appBarColor(fill = null, force = true, mode = "auto", themeOnPrimary),
        )
    }

    @Test
    fun `a custom fill gets a label that reads on it`() {
        val themeOnPrimary = Color(0xFFFFF0F5.toInt())
        val whiteFill = Color(0xFFFFFFFF.toInt())

        val measured =
            appBarColor(whiteFill, force = false, mode = UserPreferencesManager.ON_COLOR_MODE_AUTO, themeOnPrimary)
        assertEquals(Color.Black, measured)
        assertTrue(contrastRatio(measured, whiteFill) >= 4.5)
        // The theme tone would have been invisible there.
        assertTrue(contrastRatio(themeOnPrimary, whiteFill) < 1.5)
    }

    @Test
    fun `a forced tone is kept while it is visible and rescued when it is not`() {
        val themeOnPrimary = Color(0xFFFFF0F5.toInt())

        // White text on a dark custom app bar reads, so the forced tone is honoured.
        val darkFill = Color(0xFF336699.toInt())
        assertEquals(
            Color.White,
            appBarColor(
                darkFill,
                force = true,
                mode = UserPreferencesManager.APP_BAR_CONTENT_COLOR_MODE_LIGHT,
                themeOnPrimary,
            ),
        )

        // White on a white custom app bar is 1:1, so the measured side takes over.
        assertEquals(
            Color.Black,
            appBarColor(
                Color(0xFFFFFFFF.toInt()),
                force = true,
                mode = UserPreferencesManager.APP_BAR_CONTENT_COLOR_MODE_LIGHT,
                themeOnPrimary,
            ),
        )

        // The mirror case: black text on a near-black app bar is invisible too.
        assertEquals(
            Color.White,
            appBarColor(
                Color(0xFF102030.toInt()),
                force = true,
                mode = UserPreferencesManager.APP_BAR_CONTENT_COLOR_MODE_DARK,
                themeOnPrimary,
            ),
        )
    }

    @Test
    fun `only a custom colour that the bar really draws becomes the fill`() {
        val custom = 0xFF336699.toInt()
        assertEquals(Color(custom), resolveAppBarFill(true, false, custom))
        // A transparent toolbar draws the page, not the custom colour, so the label stays themed.
        assertNull(resolveAppBarFill(true, true, custom))
        assertNull(resolveAppBarFill(false, false, custom))
        assertNull(resolveAppBarFill(true, false, null))
    }

    private fun appBarColor(fill: Color?, force: Boolean, mode: String, themeOnPrimary: Color) =
        resolveAppBarContentColor(
            customAppBarFill = fill,
            forceContentColor = force,
            contentColorMode = mode,
            themeContentColor = themeOnPrimary,
        )

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

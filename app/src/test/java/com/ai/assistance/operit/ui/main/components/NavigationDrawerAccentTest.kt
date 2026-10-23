package com.ai.assistance.operit.ui.main.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.ai.assistance.operit.ui.theme.RainyDarkPanel
import com.ai.assistance.operit.ui.theme.RainyLightPanel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The drawer's custom accent is painted as *text* (its title and its status line), so the wiring
 * that guards it is worth pinning: the switch is independent of the main custom colors, and the
 * container can be the user's own background colour.
 */
class NavigationDrawerAccentTest {
    @Test
    fun `a pale custom accent is strengthened for the drawer container`() {
        // 0xFFFFCDE8 on the default white drawer is 1.3862:1: the title would be a smudge.
        val pale = 0xFFFFCDE8.toInt()
        val resolved =
            resolveDrawerAccentColor(
                pale,
                useCustomAccent = true,
                containerColor = RainyLightPanel,
                enforceContrast = true,
            )

        assertTrue(contrastRatio(Color(pale), RainyLightPanel) < 3.0)
        assertTrue(contrastRatio(resolved!!, RainyLightPanel) >= 3.0)
    }

    @Test
    fun `with enhanced contrast off the drawer accent is shown as picked`() {
        val pale = 0xFFFFCDE8.toInt()
        val resolved =
            resolveDrawerAccentColor(
                pale,
                useCustomAccent = true,
                containerColor = RainyLightPanel,
                enforceContrast = false,
            )

        assertEquals(Color(pale), resolved)
    }

    @Test
    fun `a dark custom accent is lightened on a dark drawer container`() {
        val dark = 0xFF102030.toInt()
        val resolved =
            resolveDrawerAccentColor(
                dark,
                useCustomAccent = true,
                containerColor = RainyDarkPanel,
                enforceContrast = true,
            )

        assertTrue(contrastRatio(Color(dark), RainyDarkPanel) < 3.0)
        assertTrue(contrastRatio(resolved!!, RainyDarkPanel) >= 3.0)
        // The direction follows the container instead of always deepening.
        assertTrue(resolved.luminance() > Color(dark).luminance())
    }

    @Test
    fun `an accent that already reads on the container is kept`() {
        val readable = 0xFF336699.toInt()
        assertEquals(
            Color(readable),
            resolveDrawerAccentColor(
                readable,
                useCustomAccent = true,
                containerColor = RainyLightPanel,
                enforceContrast = true,
            ),
        )
    }

    @Test
    fun `no custom accent leaves the callers on their own default`() {
        assertNull(
            resolveDrawerAccentColor(
                0xFFFFCDE8.toInt(),
                useCustomAccent = false,
                containerColor = RainyLightPanel,
            )
        )
        assertNull(
            resolveDrawerAccentColor(null, useCustomAccent = true, containerColor = RainyLightPanel)
        )
        assertNull(resolveDrawerAccentColor(null, useCustomAccent = false, containerColor = RainyDarkPanel))
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

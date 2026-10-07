package com.ai.assistance.operit.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import com.ai.assistance.operit.data.preferences.ThemePreferenceSnapshot
import com.ai.assistance.operit.data.preferences.UserPreferencesManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeColorSchemeResolverTest {
    @Test
    fun `custom colors off resolves Rainy light scheme despite stale colors`() {
        val snapshot = snapshot(
            useCustomColors = false,
            primary = 0xFF0000FF.toInt(),
            secondary = 0xFF00FF00.toInt(),
        )

        val scheme = resolveThemeColorScheme(snapshot, darkTheme = false)

        assertEquals(RainyPinkHover, scheme.primary)
        assertEquals(RainyPink, scheme.secondary)
        assertEquals(RainyLightHover, scheme.primaryContainer)
        assertEquals(RainyLightText, scheme.onPrimaryContainer)
        // YUNKe local patch: the light page and its containers are plain white, not the Rainy wash.
        assertEquals(Color.White, scheme.background)
        assertEquals(Color.White, scheme.surface)
        assertEquals(Color.White, scheme.surfaceVariant)
    }

    @Test
    fun `custom colors off resolves Rainy dark scheme`() {
        val scheme = resolveThemeColorScheme(snapshot(), darkTheme = true)

        assertEquals(RainySakura, scheme.primary)
        assertEquals(RainyPink, scheme.secondary)
        assertEquals(RainyDarkElement, scheme.primaryContainer)
        assertEquals(RainySakura, scheme.onPrimaryContainer)
        assertEquals(RainyDarkBackground, scheme.background)
        assertEquals(RainyDarkPanel, scheme.surface)
        assertEquals(RainyDarkElement, scheme.surfaceVariant)
    }

    @Test
    fun `custom colors on resolves selected colors`() {
        // Both are readable on the light surfaces, so they have to come through untouched.
        val primary = 0xFF336699.toInt()
        val secondary = 0xFF7B1FA2.toInt()

        val scheme = resolveThemeColorScheme(
            snapshot(useCustomColors = true, primary = primary, secondary = secondary),
            darkTheme = false,
        )

        assertEquals(Color(primary), scheme.primary)
        assertEquals(Color(secondary), scheme.secondary)
    }

    @Test
    fun `pale custom accent is strengthened until it separates from the light surfaces`() {
        // 0xFFFFCDE8 is what left the statistics page blank: 1.24:1 on the statistics card and
        // 1.02:1 on the card behind it.
        val pale = 0xFFFFCDE8.toInt()

        val scheme = resolveThemeColorScheme(
            snapshot(useCustomColors = true, primary = pale, secondary = pale),
            darkTheme = false,
        )

        assertTrue(contrastRatio(scheme.primary, RainyLightHover) >= CONTRAST_TARGET)
        assertTrue(contrastRatio(scheme.secondary, RainyLightHover) >= CONTRAST_TARGET)
        // The pick is deepened, not discarded: the accent keeps its hue.
        assertEquals(hueOf(Color(pale)), hueOf(scheme.primary), HUE_TOLERANCE)
        // The container tint is still a light tint, so the accent and the tint stay distinguishable.
        assertTrue(scheme.primaryContainer.red > scheme.primary.red)
        assertTrue(scheme.primaryContainer.green > scheme.primary.green)
        assertTrue(scheme.primaryContainer.blue > scheme.primary.blue)
    }

    @Test
    fun `pale custom accent is strengthened in the composed app scheme as well`() {
        val pale = Color(0xFFFFCDE8.toInt())

        val scheme =
            generateLightColorScheme(pale, pale, UserPreferencesManager.ON_COLOR_MODE_AUTO)

        assertTrue(contrastRatio(scheme.primary, RainyLightHover) >= CONTRAST_TARGET)
        assertTrue(contrastRatio(scheme.secondary, RainyLightHover) >= CONTRAST_TARGET)
    }

    @Test
    fun `every unreadable pick reaches the target, including achromatic and worst case ones`() {
        // 0xFF20C634 is the worst input found by sweeping all 24-bit colours; the greys and white
        // have no hue at all, and the rest are pastels in the range the guard has to rescue.
        val lightInputs = listOf(0xFFFFCDE8, 0xFFFFFFFF, 0xFF808080, 0xFF20C634, 0xFFF5E6EA, 0xFFA5D6A7)
        for (argb in lightInputs) {
            val accent = ensureResolvedLightAccentContrast(Color(argb.toInt()))
            assertTrue(
                "light $argb resolved to an unreadable accent",
                contrastRatio(accent, RainyLightHover) >= CONTRAST_TARGET,
            )
        }
    }

    @Test
    fun `a very dark custom accent is strengthened on the dark surfaces too`() {
        // The fixed 0.2 lightening in the dark generator is not enough for these.
        val darkInputs = listOf(0xFF102030, 0xFF000000, 0xFF1A1A5E, 0xFF0A2A0A)
        for (argb in darkInputs) {
            val accent = ensureResolvedDarkAccentContrast(Color(argb.toInt()))
            assertTrue(
                "dark $argb resolved to an unreadable accent",
                contrastRatio(accent, RainyDarkBorder) >= CONTRAST_TARGET,
            )
        }

        val scheme =
            generateDarkColorScheme(
                Color(0xFF102030.toInt()),
                Color(0xFF102030.toInt()),
                UserPreferencesManager.ON_COLOR_MODE_AUTO,
            )
        assertTrue(contrastRatio(scheme.primary, RainyDarkBorder) >= CONTRAST_TARGET)
        assertTrue(contrastRatio(scheme.secondary, RainyDarkBorder) >= CONTRAST_TARGET)
    }

    @Test
    fun `the composed dark scheme pins the shipped accent, not the helper's own output`() {
        // The guard runs on the already lightened color, so the shipped accent is not what
        // ensureResolvedDarkAccentContrast returns for the raw pick (#5990C8 for #102030).
        val composed =
            generateDarkColorScheme(
                Color(0xFF102030.toInt()),
                Color(0xFF1A1A5E.toInt()),
                UserPreferencesManager.ON_COLOR_MODE_AUTO,
            )

        assertEquals(Color(0xFF8295A5.toInt()), composed.primary)
        assertEquals(Color(0xFF8686BA.toInt()), composed.secondary)
    }

    @Test
    fun `custom colors resolve the guarded dark scheme too`() {
        // This is the path the web chat's dark palette comes from, and it had no coverage.
        val scheme =
            resolveThemeColorScheme(
                snapshot(
                    useCustomColors = true,
                    primary = 0xFF102030.toInt(),
                    secondary = 0xFF102030.toInt(),
                ),
                darkTheme = true,
            )

        assertEquals(Color(0xFF8295A5.toInt()), scheme.primary)
        assertTrue(contrastRatio(scheme.primary, RainyDarkBorder) >= CONTRAST_TARGET)
        assertTrue(contrastRatio(scheme.secondary, RainyDarkBorder) >= CONTRAST_TARGET)
    }

    @Test
    fun `a label on a filled accent picks the side that actually reads`() {
        // Either black or white always clears ~4.58:1 on an opaque fill, so the pair must too.
        // 0xFFFFFFFF is the pick that used to get a forced white container label at 2.1:1.
        val picks =
            listOf(
                0xFFFFCDE8,
                0xFF102030,
                0xFF808080,
                0xFF336699,
                0xFFF5E6EA,
                0xFFFFFFFF,
                0xFF000000,
            )
        for (argb in picks) {
            val light =
                generateLightColorScheme(
                    Color(argb.toInt()),
                    Color(argb.toInt()),
                    UserPreferencesManager.ON_COLOR_MODE_AUTO,
                )
            assertLabelPairsRead("light $argb", light)

            val dark =
                generateDarkColorScheme(
                    Color(argb.toInt()),
                    Color(argb.toInt()),
                    UserPreferencesManager.ON_COLOR_MODE_AUTO,
                )
            assertLabelPairsRead("dark $argb", dark)

            // The same two generators behind the resolver, which is the web chat's source.
            val resolvedLight =
                resolveThemeColorScheme(
                    snapshot(useCustomColors = true, primary = argb.toInt(), secondary = argb.toInt()),
                    darkTheme = false,
                )
            assertLabelPairsRead("resolved light $argb", resolvedLight)

            val resolvedDark =
                resolveThemeColorScheme(
                    snapshot(useCustomColors = true, primary = argb.toInt(), secondary = argb.toInt()),
                    darkTheme = true,
                )
            assertLabelPairsRead("resolved dark $argb", resolvedDark)
        }
    }

    /** Every label the user can read on a filled surface in [scheme], at WCAG AA for body text. */
    private fun assertLabelPairsRead(label: String, scheme: androidx.compose.material3.ColorScheme) {
        val pairs =
            listOf(
                "onPrimary" to (scheme.onPrimary to scheme.primary),
                "onSecondary" to (scheme.onSecondary to scheme.secondary),
                "onPrimaryContainer" to (scheme.onPrimaryContainer to scheme.primaryContainer),
                "onSecondaryContainer" to (scheme.onSecondaryContainer to scheme.secondaryContainer),
            )
        for ((name, pair) in pairs) {
            val (ink, fill) = pair
            assertTrue(
                "$label $name $ink on $fill",
                contrastRatio(ink, fill) >= 4.5,
            )
        }
    }

    @Test
    fun `a requested label tone is kept while it is visible and rescued when it is not`() {
        // The shipped light palette draws its own app bar at 2.7:1, so a soft requested tone is a
        // legitimate choice and has to survive; only an invisible one is replaced.
        val soft = Color(0xFFFF6B8E.toInt())
        assertEquals(Color.White, resolveContrastingTextColor(soft, UserPreferencesManager.ON_COLOR_MODE_LIGHT))
        assertEquals(Color.Black, resolveContrastingTextColor(soft, UserPreferencesManager.ON_COLOR_MODE_DARK))
        assertTrue(contrastRatio(Color.White, soft) >= 1.5)

        // A black label on a black accent (and the mirror case) is not a tone: it measures 1:1.
        val black = Color(0xFF000000.toInt())
        val white = Color(0xFFFFFFFF.toInt())
        assertEquals(1.0, contrastRatio(Color.Black, black), 1e-9)
        assertEquals(Color.White, resolveContrastingTextColor(black, UserPreferencesManager.ON_COLOR_MODE_DARK))
        assertEquals(Color.Black, resolveContrastingTextColor(white, UserPreferencesManager.ON_COLOR_MODE_LIGHT))
        // The measured side is still what an automatic setting gets.
        assertEquals(Color.Black, resolveContrastingTextColor(soft, UserPreferencesManager.ON_COLOR_MODE_AUTO))
    }

    @Test
    fun `the secondary ink the picker labels with reads on the surface behind it`() {
        // The colour picker drew its contrast rating in its own green/red, which measured 3.19:1 on
        // the dark surface and 4.12:1 on the light one. It now uses onSurfaceVariant, so this pins
        // that the theme keeps that pair above AA - for the base palettes and both custom paths.
        for (dark in listOf(false, true)) {
            val base = rainyBaseColorScheme(dark)
            assertTrue(
                "base scheme, dark=$dark",
                contrastRatio(base.onSurfaceVariant.compositeOver(base.surface), base.surface) >= 4.5,
            )
        }

        for (dark in listOf(false, true)) {
            val custom =
                resolveThemeColorScheme(
                    snapshot(
                        useCustomColors = true,
                        primary = 0xFFFFCDE8.toInt(),
                        secondary = 0xFFFFCDE8.toInt(),
                    ),
                    darkTheme = dark,
                )
            assertTrue(
                "custom colours, dark=$dark",
                contrastRatio(
                    custom.onSurfaceVariant.compositeOver(custom.surface),
                    custom.surface,
                ) >= 4.5,
            )
        }
    }

    @Test
    fun `the rescued label reaches the accent target through both generators`() {
        val light =
            generateLightColorScheme(
                Color(0xFF000000.toInt()),
                Color(0xFF000000.toInt()),
                UserPreferencesManager.ON_COLOR_MODE_DARK,
            )
        assertTrue(contrastRatio(light.onPrimary, light.primary) >= 4.5)
        assertTrue(contrastRatio(light.onSecondary, light.secondary) >= 4.5)

        val dark =
            generateDarkColorScheme(
                Color(0xFFFFFFFF.toInt()),
                Color(0xFFFFFFFF.toInt()),
                UserPreferencesManager.ON_COLOR_MODE_LIGHT,
            )
        assertTrue(contrastRatio(dark.onPrimary, dark.primary) >= 4.5)
        assertTrue(contrastRatio(dark.onSecondary, dark.secondary) >= 4.5)

        // The resolver behind the web chat answers the same way.
        val resolved =
            resolveThemeColorScheme(
                snapshot(
                    useCustomColors = true,
                    primary = 0xFF000000.toInt(),
                    secondary = 0xFF000000.toInt(),
                    onColorMode = UserPreferencesManager.ON_COLOR_MODE_DARK,
                ),
                darkTheme = false,
            )
        assertTrue(contrastRatio(resolved.onPrimary, resolved.primary) >= 4.5)
    }

    @Test
    fun `a custom drawer accent is readable on the container it is painted on`() {
        // The drawer switch is independent of the main custom colors, so this is the same defect in
        // a second place: a pale pick is drawn as the title and the status line there.
        val pale = Color(0xFFFFCDE8.toInt())
        val onPanel = ensureAccentReadableOn(pale, RainyLightPanel)
        assertTrue(contrastRatio(onPanel, RainyLightPanel) >= CONTRAST_TARGET)
        assertTrue(contrastRatio(pale, RainyLightPanel) < CONTRAST_TARGET)
        // Deepened, not replaced: the pick keeps its hue.
        assertEquals(hueOf(pale), hueOf(onPanel), HUE_TOLERANCE)

        // A dark pick on a dark drawer lightens instead.
        val dark = Color(0xFF102030.toInt())
        val onDarkPanel = ensureAccentReadableOn(dark, RainyDarkPanel)
        assertTrue(contrastRatio(onDarkPanel, RainyDarkPanel) >= CONTRAST_TARGET)
        assertTrue(contrastRatio(dark, RainyDarkPanel) < CONTRAST_TARGET)
        assertTrue(onDarkPanel.luminance() > dark.luminance())

        // A pick that already reads on the container comes back untouched.
        val readable = Color(0xFF336699.toInt())
        assertEquals(readable, ensureAccentReadableOn(readable, RainyLightPanel))
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

    /** Independent hue read-out, so "the pick keeps its hue" is checked without the production HSL. */
    private fun hueOf(color: Color): Double {
        val red = color.red.toDouble()
        val green = color.green.toDouble()
        val blue = color.blue.toDouble()
        val max = maxOf(red, green, blue)
        val min = minOf(red, green, blue)
        val delta = max - min
        if (delta == 0.0) return 0.0
        val hue =
            when (max) {
                red -> (green - blue) / delta + if (green < blue) 6.0 else 0.0
                green -> (blue - red) / delta + 2.0
                else -> (red - green) / delta + 4.0
            } * 60.0
        return hue
    }

    private companion object {
        /** WCAG AA for large text; the guard is a floor, not a goal. */
        const val CONTRAST_TARGET = 3.0
        const val HUE_TOLERANCE = 1.0
    }

    @Test
    fun `custom colors on without primary falls back to Rainy`() {
        val scheme = resolveThemeColorScheme(
            snapshot(useCustomColors = true, primary = null, secondary = 0xFF00FF00.toInt()),
            darkTheme = false,
        )

        assertEquals(RainyPinkHover, scheme.primary)
        assertEquals(RainyPink, scheme.secondary)
    }

    private fun snapshot(
        useCustomColors: Boolean = false,
        primary: Int? = null,
        secondary: Int? = null,
        onColorMode: String = UserPreferencesManager.ON_COLOR_MODE_AUTO,
    ) = ThemePreferenceSnapshot(
        source = "test",
        themeMode = UserPreferencesManager.THEME_MODE_LIGHT,
        useSystemTheme = false,
        useCustomColors = useCustomColors,
        customPrimaryColor = primary,
        customSecondaryColor = secondary,
        onColorMode = onColorMode,
        useBackgroundImage = false,
        backgroundMediaType = UserPreferencesManager.MEDIA_TYPE_IMAGE,
        backgroundImageOpacity = 0.3f,
        chatHeaderTransparent = false,
        chatHeaderOverlayMode = false,
        chatInputTransparent = false,
        chatInputFloating = true,
        chatInputLiquidGlass = false,
        chatInputWaterGlass = false,
        chatStyle = UserPreferencesManager.CHAT_STYLE_BUBBLE,
        inputStyle = UserPreferencesManager.INPUT_STYLE_CLASSIC,
        bubbleShowAvatar = true,
        bubbleWideLayoutEnabled = true,
        cursorUserBubbleFollowTheme = true,
        bubbleUserUseImage = false,
        bubbleAiUseImage = false,
        bubbleImageRenderMode = UserPreferencesManager.BUBBLE_IMAGE_RENDER_MODE_TILED_NINE_SLICE,
        bubbleUserRoundedCornersEnabled = false,
        bubbleAiRoundedCornersEnabled = false,
        bubbleUserContentPaddingLeft = 12f,
        bubbleUserContentPaddingRight = 12f,
        bubbleAiContentPaddingLeft = 12f,
        bubbleAiContentPaddingRight = 12f,
        avatarShape = UserPreferencesManager.AVATAR_SHAPE_CIRCLE,
        avatarCornerRadius = 8f,
        fontType = UserPreferencesManager.FONT_TYPE_SYSTEM,
        fontScale = 1f,
        showThinkingProcess = true,
        showStatusTags = true,
        showModelProvider = true,
        showModelName = true,
        showRoleName = true,
        showUserName = true,
        showMessageTokenStats = true,
        showMessageTimingStats = true,
        showMessageTimestamp = true,
        showInputProcessingStatus = true,
    )
}

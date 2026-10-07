package com.ai.assistance.operit.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.ai.assistance.operit.data.preferences.UserPreferencesManager
import com.ai.assistance.operit.data.preferences.UserPreferencesManager.Companion.ON_COLOR_MODE_AUTO

@Immutable
data class AppThemeStyle(val colorScheme: ColorScheme, val typography: Typography, val darkTheme: Boolean)

/** No Activity or system-bar side effects, so overlays can use the live app theme too. */
@Composable
fun rememberAppThemeStyle(): AppThemeStyle {
    val context = LocalContext.current
    val preferencesManager = remember(context) { UserPreferencesManager.getInstance(context) }
    // YUNKe local patch: the theme used to be seeded with hardcoded defaults, so the very first
    // frame painted the product's pink accent and the UI visibly flashed to the user's colour once
    // DataStore answered. Seed the first frame with the values that are actually stored. The read
    // is cached per process, so only the first composition pays for it; the flows below then take
    // over as usual.
    val initialSnapshot = remember(context) { preferencesManager.firstFrameThemeSnapshot() }
    // 获取主题设置
    val useSystemTheme by
            preferencesManager.useSystemTheme.collectAsState(
                    initial = initialSnapshot?.useSystemTheme ?: true
            )
    val themeMode by
            preferencesManager.themeMode.collectAsState(
                    initial = initialSnapshot?.themeMode
                            ?: UserPreferencesManager.THEME_MODE_LIGHT
            )
    val useCustomColors by
            preferencesManager.useCustomColors.collectAsState(
                    initial = initialSnapshot?.useCustomColors ?: false
            )
    val customPrimaryColor by
            preferencesManager.customPrimaryColor.collectAsState(
                    initial = initialSnapshot?.customPrimaryColor
            )
    val customSecondaryColor by
            preferencesManager.customSecondaryColor.collectAsState(
                    initial = initialSnapshot?.customSecondaryColor
            )
    val onColorMode by
            preferencesManager.onColorMode.collectAsState(
                    initial = initialSnapshot?.onColorMode ?: ON_COLOR_MODE_AUTO
            )

    // 获取字体设置
    val useCustomFont by preferencesManager.useCustomFont.collectAsState(initial = false)
    val fontType by preferencesManager.fontType.collectAsState(initial = UserPreferencesManager.FONT_TYPE_SYSTEM)
    val systemFontName by preferencesManager.systemFontName.collectAsState(initial = UserPreferencesManager.SYSTEM_FONT_DEFAULT)
    val customFontPath by preferencesManager.customFontPath.collectAsState(initial = null)
    val fontScale by preferencesManager.fontScale.collectAsState(initial = 1.0f)

    // 创建自定义 Typography
    val customTypography = remember(useCustomFont, fontType, systemFontName, customFontPath, fontScale) {
        createCustomTypography(
            context = context,
            useCustomFont = useCustomFont,
            fontType = fontType,
            systemFontName = systemFontName,
            customFontPath = customFontPath,
            fontScale = fontScale
        )
    }

    // 确定是否使用暗色主题
    val systemDarkTheme = isSystemInDarkTheme()
    val darkTheme =
            if (useSystemTheme) {
                systemDarkTheme
            } else {
                themeMode == UserPreferencesManager.THEME_MODE_DARK
            }

    // Rainy is the product default. System settings only choose light or dark mode.
    var colorScheme = rainyBaseColorScheme(darkTheme)

    // 应用自定义颜色和文本颜色
    if (useCustomColors) {
        customPrimaryColor?.let { primaryArgb ->
            val primary = Color(primaryArgb)
            val secondary = customSecondaryColor?.let { Color(it) } ?: colorScheme.secondary

            colorScheme = if (darkTheme) {
                generateDarkColorScheme(primary, secondary, onColorMode)
                    } else {
                generateLightColorScheme(primary, secondary, onColorMode)
                    }
        }
    }

    return AppThemeStyle(colorScheme, customTypography, darkTheme)
}

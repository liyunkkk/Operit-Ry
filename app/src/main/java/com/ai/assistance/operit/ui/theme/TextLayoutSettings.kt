package com.ai.assistance.operit.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalContext
import com.ai.assistance.operit.data.preferences.UserPreferencesManager

/**
 * AI Markdown 文本布局设置。
 *
 * - lineHeightMultiplier：额外行距倍率，1f 为默认值
 * - letterSpacingSp：额外字距，单位 sp
 * - paragraphSpacingDp：段落之间额外增加的间距，单位 dp
 * - tonedIconStyle：正文里带颜色的小图标（`:icon[check]{tone=success}`）怎么画
 */
data class AiMarkdownTextLayoutSettings(
    val lineHeightMultiplier: Float = 1f,
    val letterSpacingSp: Float = 0f,
    val paragraphSpacingDp: Float = 12f,
    val tonedIconStyle: TonedIconStyle = TonedIconStyle.Plain,
)

/**
 * Plain draws the color straight on the bubble; Dot sets it on a small tinted disc and Halo rings it
 * with a thin outline, so image, glass or mid-tone bubbles cannot swallow it.
 */
enum class TonedIconStyle(val key: String) {
    Plain("plain"), Dot("dot"), Halo("halo");

    companion object {
        fun fromKey(key: String?): TonedIconStyle = entries.firstOrNull { it.key == key } ?: Plain
    }
}

val LocalAiMarkdownTextLayoutSettings = compositionLocalOf {
    AiMarkdownTextLayoutSettings()
}
private val LocalAiMarkdownSettingsProvided = compositionLocalOf { false }

@Composable
fun ProvideAiMarkdownTextLayoutSettings(content: @Composable () -> Unit) {
    if (LocalAiMarkdownSettingsProvided.current) {
        content()
        return
    }
    val context = LocalContext.current
    val preferencesManager = remember { UserPreferencesManager.getInstance(context) }
    val lineHeightMultiplier by preferencesManager.aiMarkdownLineHeightMultiplier.collectAsState(initial = 1f)
    val letterSpacing by preferencesManager.aiMarkdownLetterSpacing.collectAsState(initial = 0f)
    val paragraphSpacing by preferencesManager.aiMarkdownParagraphSpacing.collectAsState(initial = 12f)
    val tonedIconStyle by preferencesManager.aiMarkdownTonedIconStyle.collectAsState(initial = TonedIconStyle.Plain)

    val settings = remember(lineHeightMultiplier, letterSpacing, paragraphSpacing, tonedIconStyle) {
        AiMarkdownTextLayoutSettings(
            lineHeightMultiplier = lineHeightMultiplier,
            letterSpacingSp = letterSpacing,
            paragraphSpacingDp = paragraphSpacing,
            tonedIconStyle = tonedIconStyle,
        )
    }

    CompositionLocalProvider(
        LocalAiMarkdownTextLayoutSettings provides settings,
        LocalAiMarkdownSettingsProvided provides true,
    ) {
        content()
    }
}

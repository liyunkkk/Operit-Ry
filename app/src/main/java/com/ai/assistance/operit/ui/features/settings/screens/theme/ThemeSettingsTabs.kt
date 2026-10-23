package com.ai.assistance.operit.ui.features.settings.screens.theme

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import com.ai.assistance.operit.ui.features.settings.components.SettingsCard
import com.ai.assistance.operit.ui.features.settings.components.SettingsNavRow
import androidx.compose.foundation.layout.Box
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import android.content.Context
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.ai.assistance.operit.ui.features.settings.components.rememberSettingsCardColor
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.ScrollState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import kotlinx.coroutines.yield

internal enum class ThemeSettingsTab(val titleRes: Int) {
    BASIC(R.string.theme_tab_basic),
    BACKGROUND(R.string.theme_tab_background),
    CHAT(R.string.theme_tab_chat),
    INPUT(R.string.theme_tab_input),
    INTERFACE(R.string.theme_tab_interface),
}

@Composable
internal fun ThemeSettingsTabbedContent(
    selectedTab: ThemeSettingsTab,
    onSelectedTabChange: (ThemeSettingsTab) -> Unit,
    basicContent: @Composable () -> Unit,
    backgroundContent: @Composable () -> Unit,
    chatContent: @Composable () -> Unit,
    inputContent: @Composable () -> Unit,
    interfaceContent: @Composable () -> Unit,
    footerContent: @Composable () -> Unit,
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
) {
    var renderedTab by remember { mutableStateOf(selectedTab) }
    var isSwitchingTab by remember { mutableStateOf(false) }

    LaunchedEffect(selectedTab) {
        if (renderedTab != selectedTab) {
            isSwitchingTab = true
            yield()
            scrollState.scrollTo(0)
            renderedTab = selectedTab
            isSwitchingTab = false
        }
    }

    Column(modifier = modifier) {
        ThemeSettingsSegmentedControl(
            selectedTab = selectedTab,
            onSelectedTabChange = onSelectedTabChange,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 4.dp),
        )

        if (isSwitchingTab) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .verticalScroll(scrollState)
                    .padding(
                        start = 16.dp,
                        top = 8.dp,
                        end = 16.dp,
                        bottom = 16.dp,
                    ),
        ) {
            when (renderedTab) {
                ThemeSettingsTab.BASIC -> basicContent()
                ThemeSettingsTab.BACKGROUND -> backgroundContent()
                ThemeSettingsTab.CHAT -> chatContent()
                ThemeSettingsTab.INPUT -> inputContent()
                ThemeSettingsTab.INTERFACE -> interfaceContent()
            }

            footerContent()
        }
    }
}

@Composable
internal fun ThemeSettingsFooter(
    onReset: () -> Unit,
) {
    var showConfirm by remember { mutableStateOf(false) }

    SettingsCard(
        backgroundColor = rememberSettingsCardColor(),
        modifier = Modifier.padding(top = 24.dp, bottom = 72.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
    ) {
        SettingsNavRow(
            title = stringResource(id = R.string.theme_reset),
            onClick = { showConfirm = true },
            icon = Icons.Default.RestartAlt,
            destructive = true,
        )
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text(stringResource(id = R.string.theme_reset_confirm_title)) },
            text = { Text(stringResource(id = R.string.theme_reset_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConfirm = false
                        onReset()
                    },
                ) {
                    Text(
                        text = stringResource(id = R.string.reset),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) {
                    Text(stringResource(id = R.string.cancel))
                }
            },
        )
    }
}

/**
 * A small pill that floats up from the bottom after a change is saved, so the user sees it
 * wherever the page is scrolled. A new [ThemeSettingsToast.id] restarts the timer.
 */
internal data class ThemeSettingsToast(val messageRes: Int, val id: Long = System.nanoTime())

@Composable
internal fun ThemeSettingsToastHost(
    toast: ThemeSettingsToast?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Keeps the text during the exit animation, after the toast itself is gone.
    var lastMessageRes by remember { mutableStateOf(R.string.theme_saved) }
    val messageRes = toast?.messageRes ?: lastMessageRes
    LaunchedEffect(toast?.id) {
        if (toast != null) {
            lastMessageRes = toast.messageRes
            kotlinx.coroutines.delay(1800)
            onDismiss()
        }
    }
    AnimatedVisibility(
        visible = toast != null,
        modifier = modifier,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
    ) {
        Row(
            modifier =
                Modifier
                    .shadow(6.dp, RoundedCornerShape(50), clip = false)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.inverseSurface)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.inversePrimary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(id = messageRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.inverseOnSurface,
            )
        }
    }
}

/**
 * iOS-style segmented control: a soft track with a pill that slides to the chosen tab. Long
 * labels in other languages wrap to a second line instead of being cut off.
 */
@Composable
private fun ThemeSettingsSegmentedControl(
    selectedTab: ThemeSettingsTab,
    onSelectedTabChange: (ThemeSettingsTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tabs = ThemeSettingsTab.values()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val cardColor = rememberSettingsCardColor()
    val onSurface = MaterialTheme.colorScheme.onSurface
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val trackColor = onSurface.copy(alpha = 0.07f).compositeOver(cardColor)
    // Like iOS: in dark mode the chosen pill is lighter than the track, in light mode it is white.
    val pillColor =
        if (isDark) onSurface.copy(alpha = 0.18f).compositeOver(cardColor)
        else MaterialTheme.colorScheme.surface
    var trackWidthPx by remember { mutableStateOf(0) }
    val segmentWidth = with(density) { (trackWidthPx / tabs.size).toDp() }
    Box(
        modifier =
            modifier
                .clip(RoundedCornerShape(12.dp))
                .background(trackColor)
                .padding(3.dp)
                .onSizeChanged { trackWidthPx = it.width },
    ) {
        if (trackWidthPx > 0) {
            // Created only once the width is known, so the pill starts on the chosen tab.
            val indicatorOffset by animateDpAsState(
                targetValue = segmentWidth * selectedTab.ordinal,
                animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow),
                label = "themeTabIndicator",
            )
            Box(
                modifier =
                    Modifier
                        .matchParentSize()
                        .wrapContentWidth(Alignment.Start)
                        .offset(x = indicatorOffset)
                        .width(segmentWidth)
                        .fillMaxHeight()
                        .shadow(1.dp, RoundedCornerShape(9.dp))
                        .clip(RoundedCornerShape(9.dp))
                        .background(pillColor),
            )
        }
        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            tabs.forEach { tab ->
                val selected = tab == selectedTab
                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .heightIn(min = 32.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .selectable(
                                selected = selected,
                                role = Role.Tab,
                                onClick = {
                                    if (!selected) {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onSelectedTabChange(tab)
                                    }
                                },
                            )
                            .padding(horizontal = 2.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(id = tab.titleRes),
                        style = MaterialTheme.typography.labelLarge.copy(lineHeight = 16.sp),
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color =
                            if (selected) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** The tab that shows a theme option, so a settings search result opens the right tab. */
internal fun themeSettingsTabForSearch(context: Context, title: String?): ThemeSettingsTab? {
    if (title == null) return null
    val byTab =
        mapOf(
            ThemeSettingsTab.BASIC to listOf(
                R.string.theme_title_mode,
                R.string.theme_title_color,
                R.string.theme_custom_color,
                R.string.theme_enforce_contrast,
                R.string.theme_font_settings,
            ),
            ThemeSettingsTab.BACKGROUND to listOf(
                R.string.theme_title_background,
                R.string.theme_background_blur,
            ),
            ThemeSettingsTab.CHAT to listOf(
                R.string.chat_style_title,
                R.string.chat_style_bubble_color_title,
                R.string.chat_style_bubble_user_image_title,
                R.string.chat_style_bubble_ai_image_title,
                R.string.avatar_customization_title,
                R.string.avatar_shape_title,
                R.string.display_options_title,
                R.string.show_thinking_process,
                R.string.show_model_name,
                R.string.show_user_name,
            ),
            ThemeSettingsTab.INPUT to listOf(
                R.string.input_style_title,
                R.string.theme_chat_input_transparent_title,
            ),
            ThemeSettingsTab.INTERFACE to listOf(
                R.string.theme_statusbar_color,
                R.string.theme_navigation_drawer_title,
                R.string.theme_chat_header_transparent_title,
            ),
        )
    return byTab.entries.firstOrNull { (_, ids) -> ids.any { context.getString(it) == title } }?.key
}

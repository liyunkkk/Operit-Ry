package com.ai.assistance.operit.ui.features.settings.components

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.data.preferences.UserPreferencesManager
import kotlinx.coroutines.delay

/*
 * Shared building blocks for settings pages. They follow the look of the context and summary
 * page: 20dp cards, a primary section title with an icon, and a tinted info banner.
 */

/**
 * The option a settings search result just opened. The page that shows a row with this title
 * scrolls it into view and flashes it once, so the user lands on the option instead of the top.
 */
object SettingsSearchFocus {
    private var pendingTitle: String? = null
    private var requestedAt = 0L

    fun request(title: String) {
        pendingTitle = title
        requestedAt = SystemClock.uptimeMillis()
    }

    /** The fresh target title without using it up, so a page can open a folded section first. */
    fun peek(): String? =
        pendingTitle?.takeIf { SystemClock.uptimeMillis() - requestedAt <= 3_000L }

    /** True once for the row matching a fresh request; a stale request is dropped. */
    fun consume(title: String): Boolean {
        val target = pendingTitle ?: return false
        if (SystemClock.uptimeMillis() - requestedAt > 3_000L) {
            pendingTitle = null
            return false
        }
        if (target != title) return false
        pendingTitle = null
        return true
    }
}

/** Lets a settings search land on this row: scroll it into view and flash it. */
fun Modifier.settingsSearchTarget(title: String): Modifier = composed {
    val requester = remember { BringIntoViewRequester() }
    val highlight = remember { Animatable(0f) }
    LaunchedEffect(title) {
        if (SettingsSearchFocus.consume(title)) {
            // Wait for the page transition so the scroll lands on the final layout.
            delay(350)
            requester.bringIntoView()
            highlight.snapTo(1f)
            highlight.animateTo(0f, tween(durationMillis = 1200, delayMillis = 700))
        }
    }
    val color = MaterialTheme.colorScheme.primary
    this.bringIntoViewRequester(requester).drawBehind {
        val alpha = highlight.value
        if (alpha > 0f) {
            drawRoundRect(
                color = color.copy(alpha = 0.16f * alpha),
                cornerRadius = CornerRadius(14.dp.toPx())
            )
        }
    }
}

/** Card color for settings pages: opaque over a background image so text stays readable. */
@Composable
fun settingsCardColor(hasBackgroundImage: Boolean): Color =
    if (hasBackgroundImage) {
        MaterialTheme.colorScheme.surface
    } else {
        MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
    }

/** Last value seen, so cards scrolled in later start with the right color instead of flashing. */
private var lastKnownBackgroundImage = false

/** Same as [settingsCardColor], reading the background image switch itself. */
@Composable
fun rememberSettingsCardColor(): Color {
    val context = LocalContext.current
    val hasBackgroundImage by
        remember { UserPreferencesManager.getInstance(context) }.useBackgroundImage
            .collectAsState(initial = lastKnownBackgroundImage)
    lastKnownBackgroundImage = hasBackgroundImage
    return settingsCardColor(hasBackgroundImage)
}

@Composable
fun SettingsSectionTitle(text: String, icon: ImageVector, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.padding(start = 4.dp, bottom = 10.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

/** Corner shape shared by every settings card. */
val SettingsCardShape = RoundedCornerShape(20.dp)

/** One rounded block of related settings. */
@Composable
fun SettingsCard(
    backgroundColor: Color,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = SettingsCardShape,
        color = backgroundColor
    ) {
        Column(modifier = Modifier.padding(contentPadding), content = content)
    }
}

@Composable
fun SettingsInfoBanner(text: String, backgroundColor: Color, modifier: Modifier = Modifier) {
    // The tint sits on the page's card color, which is opaque over a background image.
    Row(
        modifier =
            modifier.fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(backgroundColor)
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f))
                .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = Icons.Outlined.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * A row that opens another page: icon, title, subtitle and a trailing arrow. A [destructive] row
 * runs a data-deleting action instead, so it is red and has no arrow.
 */
@Composable
fun SettingsNavRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    destructive: Boolean = false,
    /** False for rows that only link to a page, such as the settings home and its search results. */
    searchable: Boolean = true,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier =
            (if (searchable) modifier.settingsSearchTarget(title) else modifier)
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            if (destructive) {
                SettingsIconBadge(
                    icon = icon,
                    size = 36,
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            } else {
                SettingsIconBadge(icon = icon, size = 36)
            }
            Spacer(modifier = Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (destructive) MaterialTheme.colorScheme.error else Color.Unspecified,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (trailing != null) {
            Spacer(modifier = Modifier.width(8.dp))
            trailing()
        } else if (!destructive) {
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/** A whole-row switch with a title and an optional subtitle. */
@Composable
fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    /** False for rows that only link to a page, such as the settings home and its search results. */
    searchable: Boolean = true,
    /** 0.dp lines the text up with other content in a card that already has its own padding. */
    horizontalPadding: Dp = 8.dp
) {
    Row(
        modifier =
            (if (searchable) modifier.settingsSearchTarget(title) else modifier)
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .clickable(enabled = enabled) { onCheckedChange(!checked) }
                .padding(horizontal = horizontalPadding, vertical = 10.dp)
                .alpha(if (enabled) 1f else 0.38f),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f).padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/** Header of a large foldable card: icon block, title, subtitle and an expand arrow. */
@Composable
fun SettingsExpandableHeader(
    title: String,
    icon: ImageVector,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null
) {
    Row(
        modifier =
            modifier.settingsSearchTarget(title)
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .clickable { onExpandedChange(!expanded) }
                .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SettingsIconBadge(icon = icon, size = 40)
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Icon(
            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Thin divider between rows inside a [SettingsCard]. */
@Composable
fun SettingsRowDivider(inset: Boolean = true) {
    HorizontalDivider(
        modifier = Modifier.padding(start = if (inset) 56.dp else 8.dp, end = 8.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    )
}

@Composable
fun SettingsIconBadge(
    icon: ImageVector,
    size: Int = 36,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer
) {
    Box(
        modifier =
            Modifier.size(size.dp)
                .clip(RoundedCornerShape((size / 3).dp))
                .background(containerColor),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size((size * 0.55f).dp)
        )
    }
}

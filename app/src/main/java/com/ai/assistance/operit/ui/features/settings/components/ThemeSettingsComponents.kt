package com.ai.assistance.operit.ui.features.settings.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * A selectable theme mode option: a soft block that turns tinted with a check mark when chosen.
 */
@Composable
fun ThemeModeOption(
    title: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    ThemeChoiceBlock(selected = selected, modifier = modifier, onClick = onClick) { contentColor ->
        Text(
            text = title,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = contentColor
        )
    }
}

/**
 * A selectable media type option with an icon.
 */
@Composable
fun MediaTypeOption(
    title: String,
    icon: ImageVector,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    ThemeChoiceBlock(selected = selected, modifier = modifier, onClick = onClick) { contentColor ->
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = title,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = contentColor
            )
        }
    }
}

/**
 * Shared look for choice blocks on the theme page. The chosen block is tinted, outlined and
 * carries a small check in the corner, so the choice reads at a glance.
 */
@Composable
internal fun ThemeChoiceBlock(
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    content: @Composable (contentColor: Color) -> Unit
) {
    val shape = RoundedCornerShape(14.dp)
    val primary = MaterialTheme.colorScheme.primary
    val containerColor by animateColorAsState(
        if (selected) primary.copy(alpha = 0.14f)
        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
        label = "choiceContainer"
    )
    val borderColor by animateColorAsState(
        if (selected) primary else Color.Transparent,
        label = "choiceBorder"
    )
    val contentColor = if (selected) primary else MaterialTheme.colorScheme.onSurface
    Box(
        modifier = modifier
            .heightIn(min = 52.dp)
            .clip(shape)
            .background(containerColor)
            .border(1.5.dp, borderColor, shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        content(contentColor)
        if (selected) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = primary,
                modifier = Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-6).dp).size(16.dp)
            )
        }
    }
}

/**
 * A color row in the iOS style: the name on the left and the current color as a dot on the
 * right. The whole row is the tap target.
 */
@Composable
fun ColorSelectionItem(
    title: String,
    color: Color,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val alpha = if (enabled) 1f else 0.38f
    Row(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Box(
            modifier = Modifier.size(28.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = if (enabled) color.alpha else color.alpha * 0.5f))
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = alpha),
                    CircleShape
                )
        )
    }
}

/**
 * A preset color item for the color picker
 */
@Composable
fun PresetColorItem(color: Color, onSelect: (Color) -> Unit) {
    Box(
        modifier = Modifier.size(32.dp)
            .clip(CircleShape)
            .background(color)
            .border(1.dp, Color.White, CircleShape)
            .clickable { onSelect(color) }
    )
} 
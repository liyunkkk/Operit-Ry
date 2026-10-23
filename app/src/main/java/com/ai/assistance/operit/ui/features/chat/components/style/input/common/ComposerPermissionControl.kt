package com.ai.assistance.operit.ui.features.chat.components.style.input.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.GppMaybe
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.ai.assistance.operit.R
import com.ai.assistance.operit.ui.permissions.ToolPermissionStop
import com.ai.assistance.operit.ui.permissions.descriptionRes
import com.ai.assistance.operit.ui.permissions.labelRes

/** A compact shortcut to the same five tool permissions offered by the settings menu. */
@Composable
fun ComposerPermissionControl(
    enableTools: Boolean,
    permissionStop: ToolPermissionStop,
    onSetPermissionStop: (ToolPermissionStop) -> Unit,
    onToggleTools: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val effectiveStop = if (enableTools) permissionStop else ToolPermissionStop.FORBID
    val popupOffset = with(LocalDensity.current) { IntOffset(0, -84.dp.roundToPx()) }
    val tint = MaterialTheme.colorScheme.onSurfaceVariant

    Box(modifier) {
        Box(
            modifier = Modifier.size(36.dp).clip(CircleShape)
                .clickable(role = Role.Button) { expanded = true },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = effectiveStop.composerIcon,
                contentDescription =
                    stringResource(R.string.permission_level) + ": " +
                        stringResource(effectiveStop.labelRes),
                tint = when {
                    effectiveStop == ToolPermissionStop.ALLOW -> MaterialTheme.colorScheme.error
                    expanded -> MaterialTheme.colorScheme.primary
                    else -> tint
                },
                modifier = Modifier.size(20.dp),
            )
        }
        if (expanded) {
            Popup(
                alignment = Alignment.BottomStart,
                offset = popupOffset,
                onDismissRequest = { expanded = false },
                properties = PopupProperties(focusable = true),
            ) {
                PermissionPopup(
                    stop = effectiveStop,
                    onSelected = { selected ->
                        // Match the existing tools menu: the leftmost stop also disables tools.
                        if (enableTools == (selected == ToolPermissionStop.FORBID)) {
                            onToggleTools()
                        }
                        if (permissionStop != selected) onSetPermissionStop(selected)
                    },
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun PermissionPopup(
    stop: ToolPermissionStop,
    onSelected: (ToolPermissionStop) -> Unit,
) {
    var localValue by remember { mutableFloatStateOf(stop.ordinal.toFloat()) }
    LaunchedEffect(stop) { localValue = stop.ordinal.toFloat() }
    val shownStop = ToolPermissionStop.at(localValue)
    val isFullAccess = shownStop == ToolPermissionStop.ALLOW
    val activeColor =
        if (isFullAccess) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val idleColor = MaterialTheme.colorScheme.onSurfaceVariant
    val stops = ToolPermissionStop.values()
    val sliderState = remember {
        SliderState(
            value = localValue,
            steps = stops.size - 2,
            trackRange = 0f..stops.lastIndex.toFloat(),
        )
    }
    SideEffect { sliderState.value = localValue }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
        shadowElevation = 6.dp,
    ) {
        Column(Modifier.width(288.dp).padding(16.dp)) {
            Text(
                text = stringResource(R.string.permission_level),
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                text = stringResource(shownStop.labelRes),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = activeColor,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            Slider(
                state = sliderState,
                onValueChange = { localValue = it },
                // Read the current state here: a tap may finish before recomposition.
                onValueChangeFinished = { onSelected(ToolPermissionStop.at(localValue)) },
                thumb = {
                    Box(Modifier.size(50.dp), contentAlignment = Alignment.Center) {
                        Box(
                            Modifier.size(34.dp)
                                .background(MaterialTheme.colorScheme.surface, CircleShape)
                                .border(3.dp, activeColor, CircleShape),
                        )
                    }
                },
                track = { state ->
                    ComposerSliderTrack(
                        state = state,
                        activeColor = activeColor,
                        inactiveColor = MaterialTheme.colorScheme.surfaceVariant,
                        activeDotColor =
                            (if (isFullAccess) MaterialTheme.colorScheme.onError
                            else MaterialTheme.colorScheme.onPrimary).copy(alpha = 0.25f),
                        inactiveDotColor = idleColor.copy(alpha = 0.32f),
                    )
                },
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                stops.forEach { item ->
                    Box(Modifier.size(50.dp), contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = item.composerIcon,
                            contentDescription = stringResource(item.labelRes),
                            tint = when {
                                item == ToolPermissionStop.ALLOW -> MaterialTheme.colorScheme.error
                                shownStop == item -> activeColor
                                else -> idleColor
                            },
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
            // Keep the bottom-anchored popup and its slider still while previewing longer stops.
            val descriptionScroll = rememberScrollState()
            LaunchedEffect(shownStop) { descriptionScroll.scrollTo(0) }
            Box(
                Modifier.fillMaxWidth().height(112.dp)
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (isFullAccess) MaterialTheme.colorScheme.errorContainer
                        else androidx.compose.ui.graphics.Color.Transparent,
                    )
                    .verticalScroll(descriptionScroll)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                Text(
                    text = stringResource(shownStop.descriptionRes),
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        if (isFullAccess) MaterialTheme.colorScheme.onErrorContainer else idleColor,
                )
            }
        }
    }
}

private val ToolPermissionStop.composerIcon: ImageVector
    get() = when (this) {
        ToolPermissionStop.FORBID -> Icons.Outlined.Block
        ToolPermissionStop.ASK -> Icons.Outlined.PanTool
        ToolPermissionStop.AUTO_REVIEW_STRICT -> Icons.Outlined.Security
        ToolPermissionStop.AUTO_REVIEW_FAST -> Icons.Outlined.VerifiedUser
        ToolPermissionStop.ALLOW -> Icons.Outlined.GppMaybe
    }

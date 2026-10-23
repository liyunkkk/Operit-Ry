package com.ai.assistance.operit.ui.features.chat.components.style.input.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.preferences.ApiPreferences
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private val ThinkingSliderTrackHeight = 50.dp
private val ThinkingSliderThumbSlot = ThinkingSliderTrackHeight
private val ThinkingSliderThumbSize = 34.dp
private val ThinkingSliderRingWidth = 3.dp

/**
 * The composer's quick control for how hard the model should think.
 *
 * The chip reports the stored level; the popup is where it changes. It shows the preference rather
 * than the value a provider finally sends, because providers normalise it — the settings menu
 * keeps the full picture, including what actually goes on the wire.
 */
@Composable
fun ThinkingStrengthControl(
    thinkingEnabled: Boolean,
    qualityLevel: Int,
    onSetThinkingMode: (Boolean) -> Unit,
    onQualityLevelChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // A transition state doubles as the visibility flag: `targetState` opens and closes the popup,
    // while `currentState` keeps it composed until the closing animation has actually run.
    val visibility = remember { MutableTransitionState(false) }
    val minLevel = ApiPreferences.MIN_THINKING_QUALITY_LEVEL
    val maxLevel = ApiPreferences.MAX_THINKING_QUALITY_LEVEL
    val storedLevel = qualityLevel.coerceIn(minLevel, maxLevel)
    val activeColor = MaterialTheme.colorScheme.primary
    val idleColor = MaterialTheme.colorScheme.onSurfaceVariant
    val chipColor = if (thinkingEnabled) activeColor else idleColor
    val chipLabel =
        if (thinkingEnabled) stringResource(thinkingQualityLevelLabelRes(storedLevel))
        else stringResource(R.string.disabled)
    val chipShape = RoundedCornerShape(16.dp)
    val chipInteraction = remember { MutableInteractionSource() }
    // The dial floats clear of the composer card instead of covering the draft, and leans towards
    // the send side where the thumb already is. The lift moves the window itself; padding inside it
    // would leave a strip of popup below the panel, and a tap landing there counts as an inside
    // tap, so the popup would look stuck.
    val popupOffset =
        with(LocalDensity.current) { IntOffset(x = 48.dp.roundToPx(), y = -84.dp.roundToPx()) }

    Box(modifier = modifier) {
        Row(
            modifier =
                Modifier
                    .padding(horizontal = 2.dp)
                    .clip(chipShape)
                    .border(1.dp, chipColor.copy(alpha = 0.35f), chipShape)
                    .clickable(
                        interactionSource = chipInteraction,
                        indication = null,
                    ) { visibility.targetState = true }
                    .padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Speed,
                contentDescription = stringResource(R.string.thinking_quality),
                tint = chipColor,
                modifier = Modifier.size(14.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(text = chipLabel, fontSize = 12.sp, color = chipColor, maxLines = 1)
        }

        if (visibility.targetState || visibility.currentState) {
            Popup(
                alignment = Alignment.BottomEnd,
                offset = popupOffset,
                onDismissRequest = { visibility.targetState = false },
                properties =
                    PopupProperties(
                        focusable = true,
                        dismissOnBackPress = true,
                        dismissOnClickOutside = true,
                    ),
            ) {
                AnimatedVisibility(
                    visibleState = visibility,
                    enter =
                        scaleIn(
                            animationSpec =
                                spring(
                                    dampingRatio = 0.45f,
                                    stiffness = Spring.StiffnessMediumLow,
                                ),
                            initialScale = 0.85f,
                            transformOrigin = TransformOrigin(1f, 1f),
                        ) + fadeIn(animationSpec = tween(durationMillis = 120)),
                    exit =
                        androidx.compose.animation.fadeOut(tween(durationMillis = 90)) +
                            scaleOut(
                                animationSpec = tween(durationMillis = 90),
                                targetScale = 0.92f,
                                transformOrigin = TransformOrigin(1f, 1f),
                            ),
                ) {
                    ThinkingStrengthPopup(
                        storedLevel = storedLevel,
                        minLevel = minLevel,
                        maxLevel = maxLevel,
                        thinkingEnabled = thinkingEnabled,
                        activeColor = activeColor,
                        idleColor = idleColor,
                        onSetThinkingMode = onSetThinkingMode,
                        onQualityLevelChange = onQualityLevelChange,
                    )
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ThinkingStrengthPopup(
    storedLevel: Int,
    minLevel: Int,
    maxLevel: Int,
    thinkingEnabled: Boolean,
    activeColor: androidx.compose.ui.graphics.Color,
    idleColor: androidx.compose.ui.graphics.Color,
    onSetThinkingMode: (Boolean) -> Unit,
    onQualityLevelChange: (Int) -> Unit,
) {
    // The dial follows the finger, so the level lives here until the gesture settles. Writing the
    // stored preference on every frame would rewrite the whole preference file mid-drag.
    var localLevel by remember { mutableFloatStateOf(storedLevel.toFloat()) }
    var touched by remember { mutableStateOf(false) }
    val shownLevel = localLevel.roundToInt().coerceIn(minLevel, maxLevel)

    LaunchedEffect(localLevel, touched) {
        if (!touched) return@LaunchedEffect
        delay(180)
        if (!thinkingEnabled) onSetThinkingMode(true)
        if (shownLevel != storedLevel) onQualityLevelChange(shownLevel)
    }

    // The debounce above only fires while the popup is still composed. Lifting the finger commits
    // right away, so closing the popup immediately after a drag cannot drop the last level. A tap
    // changes the value and finishes in one gesture with no recomposition in between, so the level
    // is read from the state here rather than from this composition's `shownLevel`.
    val latestStored by rememberUpdatedState(storedLevel)
    val latestEnabled by rememberUpdatedState(thinkingEnabled)
    val commitLevel = {
        val level = localLevel.roundToInt().coerceIn(minLevel, maxLevel)
        if (!latestEnabled) onSetThinkingMode(true)
        if (level != latestStored) onQualityLevelChange(level)
        // Stops the debounce effect above from writing the same value a second time.
        touched = false
        Unit
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        tonalElevation = 4.dp,
        shadowElevation = 6.dp,
    ) {
        Column(modifier = Modifier.width(268.dp).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.thinking_quality),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text =
                        if (thinkingEnabled) {
                            stringResource(thinkingQualityLevelLabelRes(shownLevel))
                        } else {
                            stringResource(R.string.disabled)
                        },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (thinkingEnabled) activeColor else idleColor,
                )
            }

            val sliderInteraction = remember { MutableInteractionSource() }
            val sliderState = remember(minLevel, maxLevel) {
                SliderState(
                    value = localLevel,
                    steps = (maxLevel - minLevel - 1).coerceAtLeast(0),
                    trackRange = minLevel.toFloat()..maxLevel.toFloat(),
                )
            }
            androidx.compose.runtime.SideEffect { sliderState.value = localLevel }
            Slider(
                state = sliderState,
                onValueChange = {
                    localLevel = it
                    touched = true
                },
                interactionSource = sliderInteraction,
                onValueChangeFinished = { if (touched) commitLevel() },
                thumb = { state ->
                    val pressed by sliderInteraction.collectIsPressedAsState()
                    val scale by
                        animateFloatAsState(
                            targetValue = if (pressed || state.isDragging) 1.12f else 1f,
                            animationSpec = spring(dampingRatio = 0.35f, stiffness = 900f),
                            label = "thinkingThumb",
                        )
                    // The slot is as tall as the track: that is what puts the thumb's centre on the
                    // stop circles at either end. The circle drawn inside it is smaller.
                    Box(
                        modifier = Modifier.size(ThinkingSliderThumbSlot),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier =
                                Modifier.size(ThinkingSliderThumbSize)
                                    .graphicsLayer {
                                        scaleX = scale
                                        scaleY = scale
                                    }
                                    .background(MaterialTheme.colorScheme.surface, CircleShape)
                                    .border(ThinkingSliderRingWidth, activeColor, CircleShape)
                        )
                    }
                },
                track = { state ->
                    ComposerSliderTrack(
                        state = state,
                        activeColor = activeColor,
                        inactiveColor = MaterialTheme.colorScheme.surfaceVariant,
                        activeDotColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.25f),
                        inactiveDotColor =
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.32f),
                    )
                },
            )

            // The stops sit half a thumb width in from either end, so each caption is centred in a
            // thumb-wide box and allowed to overflow it: the five centres then land exactly on the
            // five stops whatever the label text measures.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                for (stop in minLevel..maxLevel) {
                    Box(
                        modifier = Modifier.width(ThinkingSliderThumbSlot),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(thinkingQualityLevelLabelRes(stop)),
                            modifier = Modifier.wrapContentWidth(unbounded = true),
                            fontSize = 10.sp,
                            color =
                                if (stop == shownLevel) activeColor
                                else idleColor.copy(alpha = 0.7f),
                            fontWeight =
                                if (stop == shownLevel) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.size(10.dp))

            val switchInteraction = remember { MutableInteractionSource() }
            val switchShape = RoundedCornerShape(8.dp)
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(switchShape)
                        .toggleable(
                            value = thinkingEnabled,
                            role = Role.Switch,
                            interactionSource = switchInteraction,
                            indication = null,
                            onValueChange = { onSetThinkingMode(!thinkingEnabled) },
                        ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.thinking_mode),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = thinkingEnabled, onCheckedChange = null)
            }

            if (!thinkingEnabled) {
                Text(
                    text = stringResource(R.string.thinking_strength_turns_on_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The dial's track: a pill that spans the whole panel, a slimmer filled rail that grows out of the
 * first stop, and one dot per level. The dots sit where the thumb's centre lands, so the geometry
 * is shared with the slider rather than guessed. Colours come from the theme.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun ComposerSliderTrack(
    state: SliderState,
    activeColor: Color,
    inactiveColor: Color,
    activeDotColor: Color,
    inactiveDotColor: Color,
) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val span = state.trackRange.endInclusive - state.trackRange.start
    val fraction =
        if (span <= 0f) 0f
        else ((state.value - state.trackRange.start) / span).coerceIn(0f, 1f)
    val stopCount = state.steps + 2

    Canvas(modifier = Modifier.fillMaxWidth().height(ThinkingSliderTrackHeight)) {
        val radius = size.height / 2f
        val railRadius = size.height / 3f
        val railInset = radius - railRadius
        val width = size.width
        // Mirroring happens here rather than on the layer, so the bleed below cannot be clipped.
        fun mirrored(value: Float) = if (rtl) width - value else value

        // The pill bleeds half a track height past the slot on both sides: that fills the panel and
        // puts its end circles exactly on the first and last stops.
        drawRoundRect(
            color = inactiveColor,
            topLeft = Offset(-radius, 0f),
            size = Size(width + radius * 2f, size.height),
            cornerRadius = CornerRadius(radius, radius),
        )

        val railStart = mirrored(-railRadius)
        val railEnd = mirrored(width * fraction)
        // Widened first, then anchored: growing a too-narrow rail towards +x would put the circle
        // on the wrong side of the track in RTL.
        val railWidth = abs(railEnd - railStart).coerceAtLeast(railRadius * 2f)
        drawRoundRect(
            color = activeColor,
            topLeft = Offset(if (rtl) railStart - railWidth else railStart, railInset),
            size = Size(railWidth, size.height - railInset * 2f),
            cornerRadius = CornerRadius(railRadius, railRadius),
        )

        val dotRadius = size.height / 12f
        for (index in 0 until stopCount) {
            val stopFraction = index.toFloat() / (stopCount - 1).toFloat()
            drawCircle(
                color = if (stopFraction <= fraction + 0.001f) activeDotColor else inactiveDotColor,
                radius = dotRadius,
                center = Offset(mirrored(width * stopFraction), radius),
            )
        }
    }
}

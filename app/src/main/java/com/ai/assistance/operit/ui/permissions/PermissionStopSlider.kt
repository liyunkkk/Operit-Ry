package com.ai.assistance.operit.ui.permissions

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R

/**
 * The whole tool permission choice as one control: five stops, the most restrictive on the left.
 *
 * The name of the stop the handle sits on is shown above it and follows the finger while dragging,
 * so the range can be read without leaving the row. [onStopPreview] reports that same stop while
 * the finger moves, so a caller showing extra text for the stop can follow the drag too; only
 * [onStopSelected] is called on release.
 */
@Composable
fun PermissionStopSlider(
    stop: ToolPermissionStop,
    onStopSelected: (ToolPermissionStop) -> Unit,
    modifier: Modifier = Modifier,
    onStopPreview: ((ToolPermissionStop) -> Unit)? = null,
) {
    var sliderValue by remember { mutableStateOf(stop.ordinal.toFloat()) }
    LaunchedEffect(stop) { sliderValue = stop.ordinal.toFloat() }
    val stopCount = ToolPermissionStop.values().size

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(ToolPermissionStop.at(sliderValue).labelRes),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Slider(
            value = sliderValue,
            onValueChange = { value ->
                sliderValue = value
                onStopPreview?.invoke(ToolPermissionStop.at(value))
            },
            onValueChangeFinished = { onStopSelected(ToolPermissionStop.at(sliderValue)) },
            valueRange = 0f..(stopCount - 1).toFloat(),
            steps = (stopCount - 2).coerceAtLeast(0),
            modifier = Modifier.fillMaxWidth().height(32.dp),
        )
    }
}

internal val ToolPermissionStop.labelRes: Int
    get() =
        when (this) {
            ToolPermissionStop.FORBID -> R.string.permission_level_forbid
            ToolPermissionStop.ASK -> R.string.permission_level_ask
            ToolPermissionStop.AUTO_REVIEW_STRICT -> R.string.permission_stop_auto_review_strict
            ToolPermissionStop.AUTO_REVIEW_FAST -> R.string.permission_stop_auto_review_fast
            ToolPermissionStop.ALLOW -> R.string.permission_stop_full_access
        }

internal val ToolPermissionStop.descriptionRes: Int
    get() =
        when (this) {
            ToolPermissionStop.FORBID -> R.string.permission_stop_forbid_description
            ToolPermissionStop.ASK -> R.string.permission_stop_ask_description
            ToolPermissionStop.AUTO_REVIEW_STRICT ->
                R.string.permission_stop_auto_review_strict_description
            ToolPermissionStop.AUTO_REVIEW_FAST ->
                R.string.permission_stop_auto_review_fast_description
            ToolPermissionStop.ALLOW -> R.string.permission_stop_allow_description
        }

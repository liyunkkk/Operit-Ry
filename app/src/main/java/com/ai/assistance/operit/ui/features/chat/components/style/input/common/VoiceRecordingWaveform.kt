package com.ai.assistance.operit.ui.features.chat.components.style.input.common

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.math.max
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive

/**
 * A bounded history of microphone levels, newest at the right. Sample on a clock because a
 * StateFlow does not emit repeated equal levels: silence and sustained sound still take time.
 * Stopping freezes the last recording while transcription finishes; a new recording resets it.
 */
@Composable
fun VoiceRecordingWaveform(
    volumeLevelFlow: StateFlow<Float>,
    isRecording: Boolean,
    isCapturing: Boolean,
    modifier: Modifier = Modifier,
) {
    val barCount = 40
    var levels by remember(volumeLevelFlow) { mutableStateOf(List(barCount) { 0f }) }
    LaunchedEffect(volumeLevelFlow, isRecording) {
        if (isRecording) levels = List(barCount) { 0f }
    }
    LaunchedEffect(volumeLevelFlow, isRecording, isCapturing) {
        if (!isRecording || !isCapturing) return@LaunchedEffect
        while (isActive) {
            delay(80)
            val level = volumeLevelFlow.value.let { if (it.isFinite()) it.coerceIn(0f, 1f) else 0f }
            levels = levels.drop(1) + level
        }
    }
    val color = MaterialTheme.colorScheme.onSurface

    Canvas(modifier = modifier) {
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        val step = size.width / barCount
        val barWidth = (step * 0.42f).coerceAtMost(size.height)
        val centerY = size.height / 2f
        for (index in 0 until barCount) {
            // Quiet samples become dots; old bars keep their recorded height as they move left.
            val height = max(barWidth, size.height * levels[index])
            val x = index * step + step / 2f
            drawRoundRect(
                color = color,
                topLeft = Offset(x - barWidth / 2f, centerY - height / 2f),
                size = Size(barWidth, height),
                cornerRadius = CornerRadius(barWidth / 2f),
            )
        }
    }
}

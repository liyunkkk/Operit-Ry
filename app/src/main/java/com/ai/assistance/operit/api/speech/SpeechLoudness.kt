package com.ai.assistance.operit.api.speech

import kotlin.math.abs
import kotlin.math.pow

/**
 * Lifts quiet speech to a steady level before voice detection and recognition see it.
 *
 * The communication audio source and noise suppression often deliver normal speech far below full
 * scale, and the voice detector then misses it, so dictation drops the words. The gain follows the
 * loudest recent sample: loud speech is never pushed past [TARGET_PEAK], and quiet input is raised
 * by at most [MAX_GAIN].
 */
internal class SpeechLoudness {
    private var peak = 0f

    fun apply(frame: ShortArray, count: Int) {
        var framePeak = 0
        for (i in 0 until count) {
            val value = abs(frame[i].toInt())
            if (value > framePeak) framePeak = value
        }
        // Instant attack keeps the current frame below the target; the slow release lets the gain
        // come back up within a few seconds after a loud word. The release follows the samples
        // read, so callers reading longer buffers decay at the same speed.
        val release = RELEASE_PER_FRAME.toDouble().pow(count / FRAME_SAMPLES.toDouble()).toFloat()
        peak = maxOf(framePeak / 32768f, peak * release)
        val gain = (TARGET_PEAK / maxOf(peak, 1e-4f)).coerceIn(1f, MAX_GAIN)
        if (gain == 1f) return
        for (i in 0 until count) {
            frame[i] = (frame[i] * gain).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    companion object {
        const val MAX_GAIN = 8f
        private const val TARGET_PEAK = 0.5f
        /** 32 ms at 16 kHz. */
        private const val FRAME_SAMPLES = 512
        private const val RELEASE_PER_FRAME = 0.995f
    }
}

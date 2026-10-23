package com.ai.assistance.operit.api.speech

import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor

/** Hardware support varies; these effects do not imply guaranteed full-duplex echo rejection. */
internal class CaptureAudioEffects(sessionId: Int) : AutoCloseable {
    private val echo = runCatching {
        if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
        else null
    }.getOrNull()
    private val noise = runCatching {
        if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(sessionId)?.apply { enabled = true } else null
    }.getOrNull()
    override fun close() {
        runCatching { echo?.release() }; runCatching { noise?.release() }
    }
}

package com.ai.assistance.operit.api.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

/** Process-wide audio focus ownership. Releasing an old lease cannot abandon a new session. */
internal object VoiceAudioSession {
    private var owner: Any? = null
    private var manager: AudioManager? = null
    private var request: AudioFocusRequest? = null
    private var lost: (() -> Unit)? = null
    @Synchronized fun acquire(context: Context, onLoss: () -> Unit): AutoCloseable {
        val token = Any()
        lost?.invoke()
        request?.let { manager?.abandonAudioFocusRequest(it) }
        val audio = context.applicationContext.getSystemService(AudioManager::class.java)
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    val current = synchronized(this) { owner === token }
                    if (current) onLoss()
                }
            }.build()
        owner = token; manager = audio; request = focus; lost = onLoss
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            owner = null; request = null; lost = null
            throw IllegalStateException("Audio focus unavailable")
        }
        return AutoCloseable {
            synchronized(this) {
                if (owner === token) {
                    audio.abandonAudioFocusRequest(focus)
                    owner = null; request = null; lost = null
                }
            }
        }
    }
}

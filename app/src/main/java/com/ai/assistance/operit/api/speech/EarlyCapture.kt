package com.ai.assistance.operit.api.speech

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Keeps the microphone open from the tap until a recognizer opens its own.
 *
 * Loading a local model can take seconds, and people start talking the moment they tap. The
 * recognizer takes these samples through [SpeechPrerollStore.consumePending] right before it starts
 * its own recording, so the two captures meet with only a few milliseconds between them.
 */
internal object EarlyCapture {
    private const val TAG = "EarlyCapture"
    private const val SAMPLE_RATE = 16000
    private const val MAX_SAMPLES = SAMPLE_RATE * 15

    private class Capture(val token: Long, val mic: AudioRecord, val source: Int) {
        val buffer = ShortArray(MAX_SAMPLES)
        var length = 0
        @Volatile var running = true
        val reader = Thread({ readLoop(this) }, "EarlyCapture")
    }

    private val lock = Any()
    private var current: Capture? = null
    private var nextToken = 0L

    private val levelState = MutableStateFlow(0f)
    /** Loudness on the same 0..1 scale the recognizers report, so the waveform moves from the tap. */
    val level: StateFlow<Float> get() = levelState

    /**
     * Starts keeping the microphone. Returns a token for [stop], or 0 when the microphone could not
     * be opened (for example while another capture holds it on older Android); recognition then
     * starts as before.
     */
    @SuppressLint("MissingPermission")
    fun start(
        context: Context,
        audioSource: Int = MediaRecorder.AudioSource.VOICE_COMMUNICATION,
    ): Long {
        stop()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) return 0L
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return 0L
        val mic = try {
            AudioRecord(audioSource, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 2, SAMPLE_RATE * 2))
        } catch (e: Exception) {
            AppLogger.w(TAG, "Early capture unavailable", e)
            return 0L
        }
        try {
            check(mic.state == AudioRecord.STATE_INITIALIZED)
            mic.startRecording()
            check(mic.recordingState == AudioRecord.RECORDSTATE_RECORDING)
        } catch (e: Exception) {
            AppLogger.w(TAG, "Early capture could not start", e)
            mic.release()
            return 0L
        }
        val capture = synchronized(lock) { Capture(++nextToken, mic, audioSource).also { current = it } }
        capture.reader.start()
        return capture.token
    }

    /** The reader owns the microphone: it releases it once reading has really ended. */
    private fun readLoop(capture: Capture) {
        val frame = ShortArray(512)
        try {
            while (capture.running) {
                val count = capture.mic.read(frame, 0, frame.size)
                if (count <= 0) break
                var energy = 0f
                for (i in 0 until count) {
                    val value = frame[i] / 32768f
                    energy += value * value
                }
                val db = 20f * kotlin.math.log10(kotlin.math.sqrt(energy / count) + 1e-6f)
                synchronized(lock) {
                    if (current !== capture) return
                    levelState.value = ((db + 60f) / 60f).coerceIn(0f, 1f)
                    val n = minOf(count, capture.buffer.size - capture.length)
                    if (n > 0) {
                        frame.copyInto(capture.buffer, capture.length, 0, n)
                        capture.length += n
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Early capture read failed", e)
        } finally {
            runCatching { capture.mic.stop() }
            capture.mic.release()
        }
    }

    /**
     * Stops the capture and returns what it heard, or null when nothing was kept. A recognizer
     * opening another source (wake listening uses the plain microphone) is not the one this
     * capture was started for, so it gets nothing and the capture keeps running.
     */
    fun handOff(forSource: Int? = null): ShortArray? = finish(forSource = forSource, token = null)

    /**
     * Drops the capture without handing its audio to anyone. With a [token], only that capture is
     * dropped, so a session ending late cannot close the one that started after it.
     */
    fun stop(token: Long? = null) {
        finish(forSource = null, token = token)
    }

    private fun finish(forSource: Int?, token: Long?): ShortArray? {
        val capture = synchronized(lock) {
            val capture = current ?: return null
            if (token != null && token != capture.token) return null
            if (forSource != null && forSource != capture.source) return null
            capture.running = false
            capture
        }
        // Unblocks a pending read; the reader then releases the microphone.
        runCatching { capture.mic.stop() }
        capture.reader.join(500)
        val samples = synchronized(lock) {
            if (current === capture) current = null
            levelState.value = 0f
            if (capture.length > 0) capture.buffer.copyOf(capture.length) else null
        }
        if (samples != null) AppLogger.d(TAG, "Kept ${samples.size * 1000 / SAMPLE_RATE} ms before recognition")
        return samples
    }
}

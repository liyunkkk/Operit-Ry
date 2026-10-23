package com.ai.assistance.operit.api.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** One utterance owns one AudioTrack; the producer waits outside the native resource lock. */
internal class PcmSpeechPlayer(private val sampleRate: Int,
    private val cancelled: () -> Boolean = { false },
    private val state: (Boolean) -> Unit) : AutoCloseable {
    private val stopped = AtomicBoolean(false)
    private val trackLock = Any()
    @Volatile private var paused = false
    private val track = AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
        .setTransferMode(AudioTrack.MODE_STREAM)
        .setBufferSizeInBytes(maxOf(sampleRate / 5 * 2,
            AudioTrack.getMinBufferSize(sampleRate,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT)))
        .build()
    init {
        if (android.os.Build.VERSION.SDK_INT >= 31) track.setStartThresholdInFrames(1)
    }
    private var started = false
    private var bytesWritten = 0L
    private var oddByte: Byte? = null
    val isPlayingThroughHeadphones get() = synchronized(trackLock) {
        !stopped.get() && started && !paused && track.routedDevice.isPrivateVoiceOutput()
    }

    /** Called serially by the network/native producer, never on Main. */
    fun write(chunk: ByteArray) {
        if (stopped.get()) throw java.util.concurrent.CancellationException("Playback stopped")
        require(chunk.size <= 2 * 1024 * 1024) { "Audio chunk too large" }
        val bytes = oddByte?.let { byteArrayOf(it) + chunk } ?: chunk
        val size = bytes.size - bytes.size % 2
        oddByte = if (size < bytes.size) bytes.last() else null
        if (size == 0) return
        var offset = 0
        var stalledMs = 0
        while (offset < size && !stopped.get()) {
            if (cancelled()) throw java.util.concurrent.CancellationException("Playback cancelled")
            val written = synchronized(trackLock) {
                if (stopped.get() || paused) 0
                else {
                    if (!started) { track.play(); started = true }
                    track.write(bytes,offset,size-offset,AudioTrack.WRITE_NON_BLOCKING).also {
                        if (it > 0 && bytesWritten == 0L) state(true)
                    }
                }
            }
            if (written < 0) throw IOException("Audio playback write failed: $written")
            if (written == 0) {
                if (!paused) stalledMs += 10
                if (stalledMs > 10000) throw IOException("Audio output stopped consuming samples")
                Thread.sleep(10)
                continue
            }
            stalledMs = 0
            offset += written
            bytesWritten += written
        }
    }
    fun write(samples: FloatArray) {
        val bytes = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, value ->
            val pcm = (value.coerceIn(-1f,1f) * 32767f).toInt()
            bytes[i*2] = pcm.toByte()
            bytes[i*2+1] = (pcm shr 8).toByte()
        }
        write(bytes)
    }
    suspend fun drain(): Boolean {
        require(oddByte == null) { "Incomplete PCM sample" }
        val targetFrames = bytesWritten / 2
        if (android.os.Build.VERSION.SDK_INT < 31 && targetFrames > 0) {
            val padding = synchronized(trackLock) {
                if (stopped.get()) 0 else (track.bufferSizeInFrames - targetFrames).coerceAtLeast(0).toInt()
            }
            if (padding > 0) write(ByteArray(padding * 2))
        }
        var waitingMs = 0L
        while (synchronized(trackLock) {
            !stopped.get() && (track.playbackHeadPosition.toLong() and 0xffffffffL) < targetFrames
        }) {
            currentCoroutineContext().ensureActive()
            delay(10)
            if (!paused) waitingMs += 10
            if (waitingMs > bytesWritten * 1000 / (sampleRate * 2) + 10000)
                throw IOException("Audio playback stalled")
        }
        return started && !stopped.get()
    }
    fun pause(): Boolean = synchronized(trackLock) {
        if (stopped.get()) return@synchronized false
        paused = true; track.pause(); state(false); true
    }
    fun resume(): Boolean = synchronized(trackLock) {
        if (stopped.get()) return@synchronized false
        paused = false; if (started) track.play(); state(started); true
    }
    override fun close() {
        if (!stopped.compareAndSet(false,true)) return
        synchronized(trackLock) {
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.release() }
            state(false)
        }
    }
}

package com.ai.assistance.operit.api.speech

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** Serial offline inference without blocking microphone capture or retaining unlimited audio. */
internal class ContinuousSpeechDecoder(
    scope: CoroutineScope,
    decode: suspend (FloatArray) -> String,
    onResult: (String, Boolean) -> Unit,
    onFailure: (Exception) -> Unit,
) {
    private val pending = Channel<FloatArray>(2)
    private val worker = scope.async {
        try {
            val transcript = StringBuilder()
            for (pcm in pending) {
                val text = decode(pcm).trim()
                coroutineContext.ensureActive()
                if (text.isNotEmpty()) {
                    if (transcript.isNotEmpty()) transcript.append('\n')
                    transcript.append(text)
                    // Cumulative hypotheses survive StateFlow conflation and a stop during decode.
                    onResult(transcript.toString(), false)
                }
            }
            coroutineContext.ensureActive()
            onResult(transcript.toString(), true)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onFailure(e)
            false
        } finally {
            pending.cancel()
        }
    }

    /** Capture must never wait for inference. The caller explicitly reports a full queue. */
    fun offer(pcm: FloatArray): Boolean = pending.trySend(pcm).isSuccess

    /** Only a deliberate stop closes gracefully; cancel/error must not publish a final result. */
    suspend fun finish(tail: FloatArray?): Boolean {
        try {
            if (tail != null) pending.send(tail)
            pending.close()
        } catch (e: CancellationException) {
            // A decoder failure closes the channel too; report that failure through the worker.
            coroutineContext.ensureActive()
        }
        return worker.await()
    }

    fun cancel() {
        pending.cancel()
        worker.cancel()
    }
}

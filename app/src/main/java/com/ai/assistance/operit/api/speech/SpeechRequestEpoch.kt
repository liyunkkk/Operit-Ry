package com.ai.assistance.operit.api.speech

import java.util.concurrent.CancellationException
import okhttp3.Call

/** Invalidates both an HTTP request and its late result when a recording is cancelled. */
internal class SpeechRequestEpoch {
    private var generation = 0L
    private var call: Call? = null

    @Synchronized fun begin(): Long {
        call?.cancel()
        call = null
        return ++generation
    }
    @Synchronized fun current(): Long = generation
    @Synchronized fun publish(epoch: Long, block: () -> Unit): Boolean {
        if (epoch != generation) return false
        block()
        return true
    }
    @Synchronized fun attach(epoch: Long, request: Call) {
        if (epoch != generation) {
            request.cancel()
            throw CancellationException("Speech request cancelled")
        }
        call = request
    }
    @Synchronized fun detach(request: Call) {
        if (call === request) call = null
    }
}

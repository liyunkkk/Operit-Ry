package com.ai.assistance.operit.api.speech

import com.ai.assistance.operit.util.AppLogger
import java.util.concurrent.atomic.AtomicLong

/** Timing only: never include audio, transcript text, credentials or endpoint parameters. */
internal class VoiceTiming(private val kind: String) {
    companion object { private val sequence = AtomicLong() }
    private val id = sequence.incrementAndGet()
    private val start = System.nanoTime()
    private val seen = mutableSetOf<String>()

    @Synchronized fun mark(phase: String) {
        if (seen.add(phase)) {
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            AppLogger.i("VoiceTiming", "kind=$kind session=$id phase=$phase elapsedMs=$elapsedMs")
        }
    }
}

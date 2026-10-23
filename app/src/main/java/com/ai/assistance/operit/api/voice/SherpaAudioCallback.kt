package com.ai.assistance.operit.api.voice

/**
 * Sherpa JNI looks up invoke([F)Ljava/lang/Integer; on the concrete callback class.
 * An invokedynamic lambda only guarantees the erased invoke(Object) method.
 * Keep this named adapter (and its typed method) in optimized builds too.
 */
internal class SherpaAudioCallback(
    private val consume: (FloatArray) -> Int
) : (FloatArray) -> Int {
    override fun invoke(samples: FloatArray): Int = consume(samples)
}

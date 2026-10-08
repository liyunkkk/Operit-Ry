package com.ai.assistance.operit.util

/** Keep enough decoded pixels for the final resize, including after a 90-degree EXIF rotation. */
internal fun imageDecodeSampleSize(width: Int, height: Int, maxLongEdge: Int): Int {
    if (width <= 0 || height <= 0 || maxLongEdge <= 0) return 1
    val longEdge = maxOf(width, height)
    var sample = 1
    while (sample <= Int.MAX_VALUE / 2 && longEdge / (sample * 2) >= maxLongEdge) {
        sample *= 2
    }
    return sample
}

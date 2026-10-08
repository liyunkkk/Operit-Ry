package com.ai.assistance.operit.util

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageDecodeSizingTest {
    @Test fun `large photos are sampled without undershooting the output`() {
        assertEquals(2, imageDecodeSampleSize(8000, 6000, 2048))
        assertEquals(2, imageDecodeSampleSize(6000, 8000, 2048))
        assertEquals(4, imageDecodeSampleSize(8192, 6144, 2048))
        assertEquals(1, imageDecodeSampleSize(4000, 3000, 2048))
    }

    @Test fun `small original and invalid bounds are not sampled`() {
        assertEquals(1, imageDecodeSampleSize(1000, 500, 2048))
        assertEquals(1, imageDecodeSampleSize(-1, -1, 2048))
        assertEquals(1, imageDecodeSampleSize(8000, 6000, 0))
    }
}

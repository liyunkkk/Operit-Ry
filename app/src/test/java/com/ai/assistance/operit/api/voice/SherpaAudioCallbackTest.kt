package com.ai.assistance.operit.api.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class SherpaAudioCallbackTest {
    @Test
    fun exposesTheExactBoxedSignatureLookedUpByJni() {
        val callback = SherpaAudioCallback { if (it.isEmpty()) 0 else 1 }
        val method = callback.javaClass.getMethod("invoke", FloatArray::class.java)
        assertEquals(Integer::class.java, method.returnType)
        assertEquals(1, method.invoke(callback, floatArrayOf(0.1f)))
        assertEquals(0, method.invoke(callback, floatArrayOf()))
    }
}

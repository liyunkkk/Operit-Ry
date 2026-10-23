package com.ai.assistance.operit.ui.common.markdown

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InlineLucideIconTest {
    @Test fun outlinedLucideKeepsTheCircleCenterEmpty() {
        val paths = requireNotNull(InlineIconPaths.get("lucide:circle")).paths
        assertTrue(paths.all { it.fillAlpha == null && it.strokeWidth == 2f && it.cap == Paint.Cap.ROUND })
        val paint = Paint().apply { textSize = 40f }
        val bitmap = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888)
        InlineIconSpan("lucide:circle", Color.BLACK, 1f).draw(Canvas(bitmap), "x", 0, 1, 0f, 0, 50, 80, paint)
        val centerY = (50 + (paint.ascent() + paint.descent()) / 2).toInt()
        assertEquals(0, Color.alpha(bitmap.getPixel(21, centerY)))
        assertTrue((0 until 80).any { x -> Color.alpha(bitmap.getPixel(x, centerY)) > 0 })
        bitmap.recycle()
    }

    @Test fun sizesAdjustSpanMetricsAndAllThreeStylesDraw() {
        val paint = Paint().apply { textSize = 40f }
        for ((fill, halo) in listOf(null to null, Color.LTGRAY to null, null to Color.WHITE)) {
            val small = InlineIconSpan("lucide:orbit", Color.BLUE, 1f, fill, halo, 0.85f)
            val large = InlineIconSpan("lucide:orbit", Color.BLUE, 1f, fill, halo, 1.25f)
            val smallMetrics = Paint.FontMetricsInt()
            val largeMetrics = Paint.FontMetricsInt()
            assertTrue(large.getSize(paint, "x", 0, 1, largeMetrics) > small.getSize(paint, "x", 0, 1, smallMetrics))
            assertTrue(largeMetrics.descent - largeMetrics.ascent >= smallMetrics.descent - smallMetrics.ascent)
            val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
            large.draw(Canvas(bitmap), "x", 0, 1, 0f, 0, 60, 100, paint)
            val pixels = IntArray(10000)
            bitmap.getPixels(pixels, 0, 100, 0, 0, 100, 100)
            assertTrue(pixels.any { Color.alpha(it) > 0 })
            bitmap.recycle()
        }
    }
}

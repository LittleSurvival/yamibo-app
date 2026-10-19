package me.thenano.yamibo.yamibo_app.thread.image

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class ReaderImagePainterCacheTest {
    private fun sizedPainter(width: Float, height: Float) = object : androidx.compose.ui.graphics.painter.Painter() {
        override val intrinsicSize = androidx.compose.ui.geometry.Size(width, height)
        override fun androidx.compose.ui.graphics.drawscope.DrawScope.onDraw() = Unit
    }

    @Test
    fun cacheBoundsPixelRetentionAndRejectsGiantImages() {
        val cache = createReaderImagePainterCache(maxEntries = 24, maxPixels = 100)
        cache.put("first", sizedPainter(8f, 8f))
        cache.put("second", sizedPainter(8f, 8f))
        assertEquals(1, cache.size)
        assertNull(cache["first"])
        cache.put("giant", sizedPainter(1000f, 1000f))
        assertNull(cache["giant"])
        assertEquals(1, cache.size)
        cache.put("second", sizedPainter(1000f, 1000f))
        assertEquals(0, cache.size)
    }

    @Test
    fun cacheEvictsLeastRecentlyUsedPainter() {
        val cache = createReaderImagePainterCache(maxEntries = 2)
        val first = ColorPainter(Color.Red)
        val second = ColorPainter(Color.Green)
        val third = ColorPainter(Color.Blue)

        cache.put("first", first)
        cache.put("second", second)
        assertSame(first, cache["first"])
        cache.put("third", third)

        assertEquals(2, cache.size)
        assertNull(cache["second"])
        assertSame(first, cache["first"])
        assertSame(third, cache["third"])
    }
}

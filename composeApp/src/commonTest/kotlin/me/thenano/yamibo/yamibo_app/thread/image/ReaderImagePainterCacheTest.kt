package me.thenano.yamibo.yamibo_app.thread.image

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class ReaderImagePainterCacheTest {
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

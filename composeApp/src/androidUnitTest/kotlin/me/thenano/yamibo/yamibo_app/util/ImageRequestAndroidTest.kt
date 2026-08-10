package me.thenano.yamibo.yamibo_app.util

import android.app.Application
import coil3.network.httpHeaders
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ImageRequestAndroidTest {
    @Test
    fun inlineRequestKeepsDataIdentityWithoutYamiboHeaders() {
        val inlinePng = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAAB"
        val request = buildImageRequest(
            context = Application(),
            url = "http://$inlinePng",
            cookie = "auth-cookie",
            referer = "https://bbs.yamibo.com/thread",
        )

        assertEquals(inlinePng, request.data)
        assertEquals(inlinePng, request.memoryCacheKey)
        assertEquals(inlinePng, request.diskCacheKey)
        assertNull(request.httpHeaders["Cookie"])
        assertNull(request.httpHeaders["Referer"])
    }
}

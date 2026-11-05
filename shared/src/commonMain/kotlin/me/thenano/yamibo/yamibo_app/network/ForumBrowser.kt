package me.thenano.yamibo.yamibo_app.network

import io.github.littlesurvival.fetch.FetchFactory

/**
 * Browser identity used for forum HTML and for the images embedded in that HTML.
 *
 * Baidu WAF in front of bbs.yamibo.com answers attachment URLs with HTTP 403 when the
 * client identifies itself as the raw app or as Ktor. Page fetches already send
 * [FetchFactory.Device.MOBILE]; image fetches must use the same identity.
 */
object ForumBrowser {
    val userAgent: String
        get() = FetchFactory.Device.MOBILE.userAgent

    /** Prefer real image types, and include star-slash-star so Ktor does not also advertise JSON. */
    const val IMAGE_ACCEPT = "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8"
}

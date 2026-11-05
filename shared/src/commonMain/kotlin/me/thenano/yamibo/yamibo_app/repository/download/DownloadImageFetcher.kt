package me.thenano.yamibo.yamibo_app.repository.download

import io.github.littlesurvival.YamiboRoute
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import me.thenano.yamibo.yamibo_app.factory.HttpClientFactory
import me.thenano.yamibo.yamibo_app.network.ForumBrowser

open class DownloadImageFetcher(
    private val cookieProvider: suspend () -> String,
) {
    private val client = HttpClientFactory.create()

    open suspend fun fetch(url: String): ByteArray {
        val normalized = normalizeDownloadImageUrl(url)
        return client.get(normalized) {
            val cookie = cookieProvider()
            header(HttpHeaders.UserAgent, ForumBrowser.userAgent)
            header(HttpHeaders.Accept, ForumBrowser.IMAGE_ACCEPT)
            if (cookie.isNotBlank()) header(HttpHeaders.Cookie, cookie)
            header(HttpHeaders.Referrer, YamiboRoute.Domain.build())
        }.body()
    }
}

fun normalizeDownloadImageUrl(url: String): String =
    if (url.startsWith("http")) url else "${YamiboRoute.Domain.build()}${url.removePrefix("/")}"

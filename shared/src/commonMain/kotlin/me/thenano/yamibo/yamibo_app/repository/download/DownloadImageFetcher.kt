package me.thenano.yamibo.yamibo_app.repository.download

import io.github.littlesurvival.YamiboRoute
import io.github.littlesurvival.fetch.FetchFactory
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import me.thenano.yamibo.yamibo_app.factory.HttpClientFactory
import me.thenano.yamibo.yamibo_app.factory.IMAGE_ACCEPT

open class DownloadImageFetcher(
    private val cookieProvider: suspend () -> String,
) {
    private val client = HttpClientFactory.create()

    open suspend fun fetch(url: String): ByteArray {
        val normalized = normalizeDownloadImageUrl(url)
        return client.get(normalized) {
            val cookie = cookieProvider()
            header(HttpHeaders.UserAgent, FetchFactory.Device.MOBILE.userAgent)
            header(HttpHeaders.Accept, IMAGE_ACCEPT)
            if (cookie.isNotBlank()) header(HttpHeaders.Cookie, cookie)
            header(HttpHeaders.Referrer, YamiboRoute.Domain.build())
        }.body()
    }
}

fun normalizeDownloadImageUrl(url: String): String =
    if (url.startsWith("http")) url else "${YamiboRoute.Domain.build()}${url.removePrefix("/")}"

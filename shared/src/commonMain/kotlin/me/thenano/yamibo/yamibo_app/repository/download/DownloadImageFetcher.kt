package me.thenano.yamibo.yamibo_app.repository.download

import io.github.littlesurvival.YamiboRoute
import io.ktor.client.call.body
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.URLBuilder
import io.ktor.http.takeFrom
import me.thenano.yamibo.yamibo_app.factory.HttpClientFactory

open class DownloadImageFetcher(
    private val sourceClient: HttpClient,
    private val cookieProvider: suspend () -> String,
) {
    constructor(cookieProvider: suspend () -> String) : this(HttpClientFactory.create(), cookieProvider)

    private val client = sourceClient.config { followRedirects = false }

    fun close() { client.close(); sourceClient.close() }

    open suspend fun fetch(url: String): ByteArray {
        var target = Url(normalizeDownloadImageUrl(url))
        repeat(6) {
            require(target.protocol.name in setOf("http", "https") && target.user == null && target.password == null) { "Unsupported image URL" }
            val response = client.get(target) {
                if (target.protocol.name == "https" && target.host.equals("bbs.yamibo.com", true) && target.port == 443) {
                    val cookie = cookieProvider()
                    if (cookie.isNotBlank()) header(HttpHeaders.Cookie, cookie)
                }
                header(HttpHeaders.Referrer, YamiboRoute.Domain.build())
            }
            if (response.status.value in setOf(301, 302, 303, 307, 308)) {
                val location = requireNotNull(response.headers[HttpHeaders.Location]) { "Missing image redirect location" }
                target = URLBuilder(target).takeFrom(location).build()
            } else {
                check(response.status.value in 200..299) { "Image download failed: HTTP ${response.status.value}" }
                return response.body()
            }
        }
        error("Too many image redirects")
    }
}

fun normalizeDownloadImageUrl(url: String): String =
    when {
        url.startsWith("//") -> "https:$url"
        url.startsWith("http://", true) || url.startsWith("https://", true) -> url
        else -> "${YamiboRoute.Domain.build()}${url.removePrefix("/")}"
    }

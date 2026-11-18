package me.thenano.yamibo.yamibo_app.repository.download

import io.github.littlesurvival.YamiboRoute
import io.github.littlesurvival.fetch.FetchFactory
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.isSuccess
import io.ktor.http.contentType
import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.parser.Parser
import me.thenano.yamibo.yamibo_app.factory.HttpClientFactory
import me.thenano.yamibo.yamibo_app.factory.IMAGE_ACCEPT

open class DownloadImageFetcher(
    private val cookieProvider: suspend () -> String,
) {
    private val client = HttpClientFactory.create()

    open suspend fun fetch(url: String): ByteArray {
        val normalized = normalizeDownloadImageUrl(url)
        val forum = Url(YamiboRoute.Domain.build())
        val target = Url(normalized)
        val response = client.get(target) {
            header(HttpHeaders.UserAgent, FetchFactory.Device.MOBILE.userAgent)
            header(HttpHeaders.Accept, IMAGE_ACCEPT)
            if (target.protocol == forum.protocol && target.host == forum.host && target.port == forum.port) {
                val cookie = cookieProvider()
                if (cookie.isNotBlank()) header(HttpHeaders.Cookie, cookie)
                header(HttpHeaders.Referrer, forum.toString())
            }
        }
        check(response.status.isSuccess() && response.status.value != 206) { "HTTP ${response.status.value}" }
        val bytes = response.body<ByteArray>()
        val length = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        check(bytes.isNotEmpty() && (response.headers[HttpHeaders.ContentEncoding] != null ||
            length == null || length == bytes.size.toLong())) { "圖片資料不完整" }
        val isSvg = response.contentType()?.contentSubtype == "svg+xml" ||
            bytes.decodeToString(0, minOf(bytes.size, 256)).trimStart().let { it.startsWith("<svg") || it.startsWith("<?xml") }
        val valid = if (isSvg) {
            val xml = bytes.decodeToString().trim()
            val root = Ksoup.parse(xml, parser = Parser.xmlParser()).children().firstOrNull()
            root?.tagName() == "svg" && (xml.endsWith("</svg>") || xml.endsWith("/>"))
        } else {
            isReadableDownloadedBitmap(bytes)
        }
        check(valid) { "圖片資料不完整" }
        return bytes
    }
}

internal expect fun isReadableDownloadedBitmap(bytes: ByteArray): Boolean

fun normalizeDownloadImageUrl(url: String): String {
    val source = url.trim()
    return when {
        source.startsWith("//") -> "https:$source"
        source.contains("://") || source.startsWith("data:", ignoreCase = true) -> source
        else -> "${YamiboRoute.Domain.build().trimEnd('/')}/${source.removePrefix("/")}"
    }
}

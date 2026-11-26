package me.thenano.yamibo.yamibo_app.repository.download

import io.github.littlesurvival.YamiboRoute
import io.github.littlesurvival.fetch.FetchFactory
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import me.thenano.yamibo.yamibo_app.repository.thread.readerAccessScope
import io.ktor.http.contentType
import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.parser.Parser
import me.thenano.yamibo.yamibo_app.factory.HttpClientFactory
import me.thenano.yamibo.yamibo_app.factory.IMAGE_ACCEPT

open class DownloadImageFetcher(
    private val cookieProvider: suspend () -> String,
    private val readFreshImage: (suspend (url: String, cookie: String) -> ByteArray?)?,
) {
    constructor(cookieProvider: suspend () -> String) : this(cookieProvider, null)

    private val client = HttpClientFactory.create()

    suspend fun fetchForDownload(url: String): ByteArray {
        val readerCache = readFreshImage ?: return fetch(url)
        val cookie = cookieProvider()
        val cached = try {
            readerCache(normalizeDownloadImageUrl(url), cookie)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        return if (cached != null && readerAccessScope(cookie) == readerAccessScope(cookieProvider())) cached
        else fetch(url)
    }

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
        check(isCompleteDownloadedImage(bytes, response.contentType()?.toString())) { "圖片資料不完整" }
        return bytes
    }
}

/** Check the original body before it becomes an offline copy, including bodies read from Coil. */
fun isCompleteDownloadedImage(bytes: ByteArray, contentType: String?): Boolean {
    if (bytes.isEmpty()) return false
    val isSvg = contentType?.substringBefore(';')?.trim()?.equals("image/svg+xml", ignoreCase = true) == true ||
        bytes.decodeToString(0, minOf(bytes.size, 256)).trimStart().let { it.startsWith("<svg") || it.startsWith("<?xml") }
    if (!isSvg) return isReadableDownloadedBitmap(bytes)
    val xml = bytes.decodeToString().trim()
    val root = Ksoup.parse(xml, parser = Parser.xmlParser()).children().firstOrNull()
    return root?.tagName() == "svg" && (xml.endsWith("</svg>") || xml.endsWith("/>"))
}

internal expect fun isReadableDownloadedBitmap(bytes: ByteArray): Boolean

fun normalizeDownloadImageUrl(url: String): String {
    val source = repairMalformedImageUri(url.trim())
    return when {
        source.startsWith("//") -> "https:$source"
        source.contains("://") || source.startsWith("data:", ignoreCase = true) -> source
        else -> "${YamiboRoute.Domain.build().trimEnd('/')}/${source.removePrefix("/")}"
    }
}

private fun repairMalformedImageUri(url: String): String {
    val domain = YamiboRoute.Domain.build().trimEnd('/')
    val repaired = when {
        url.startsWith("http://data:image/", ignoreCase = true) -> url.substring("http://".length)
        url.startsWith("https://data:image/", ignoreCase = true) -> url.substring("https://".length)
        url.startsWith("$domain/yamibo-image://") -> url.removePrefix("$domain/")
        url.startsWith("$domain/content://") -> url.removePrefix("$domain/")
        url.startsWith("$domain/file://") -> url.removePrefix("$domain/")
        url.startsWith("$domain/data:", ignoreCase = true) -> url.substring(domain.length + 1)
        else -> url
    }
    return if (repaired.startsWith("data:", ignoreCase = true)) {
        "data:${repaired.substring("data:".length)}"
    } else {
        repaired
    }
}

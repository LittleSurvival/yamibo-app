package me.thenano.yamibo.yamibo_app.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import me.thenano.yamibo.yamibo_app.repository.download.normalizeDownloadImageUrl
import me.thenano.yamibo.yamibo_app.repository.thread.readerAccessScope
import coil3.ImageLoader
import coil3.Uri
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import me.thenano.yamibo.yamibo_app.repository.DownloadRepository
import okio.buffer
import coil3.PlatformContext
import coil3.compose.LocalPlatformContext
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Precision
import io.github.littlesurvival.YamiboRoute
import io.github.littlesurvival.fetch.FetchFactory
import me.thenano.yamibo.yamibo_app.LocalAuthRepository
import me.thenano.yamibo.yamibo_app.factory.IMAGE_ACCEPT

/**
 * Converts an image source into the URL or URI passed to Coil.
 *
 * Absolute network URLs and local `data:`, `content://`, or `file://` URIs are preserved. Relative
 * forum attachment paths are resolved against [YamiboRoute.Domain].
 */
fun normalizeImageUrl(url: String): String = normalizeDownloadImageUrl(url)

internal fun buildImageNetworkHeaders(
    normalizedUrl: String,
    cookie: String,
    referer: String,
): NetworkHeaders = NetworkHeaders.Builder().apply {
    if (normalizedUrl.startsWith("http://") || normalizedUrl.startsWith("https://")) {
        add("User-Agent", FetchFactory.Device.MOBILE.userAgent)
        add("Accept", IMAGE_ACCEPT)
    }
    if (normalizedUrl.startsWith(YamiboRoute.Domain.build())) {
        if (cookie.isNotBlank()) add("Cookie", cookie)
        add("Referer", referer)
    }
}.build()

internal fun imageSourceForDiagnostics(url: String): String {
    val normalized = normalizeImageUrl(url)
    if (!normalized.startsWith("data:", ignoreCase = true)) return normalized

    val commaIndex = normalized.indexOf(',')
    val metadata = normalized
        .substring(0, if (commaIndex >= 0) commaIndex else normalized.length)
        .take(80)
    val payloadLength = if (commaIndex >= 0) normalized.length - commaIndex - 1 else 0
    return "$metadata,<inline data: $payloadLength chars>"
}

internal fun imageErrorForDiagnostics(errorMessage: String, url: String): String {
    val normalized = normalizeImageUrl(url)
    if (!normalized.startsWith("data:", ignoreCase = true)) return errorMessage

    val sourceSummary = imageSourceForDiagnostics(normalized)
    return when {
        errorMessage.contains(normalized) -> errorMessage.replace(normalized, sourceSummary)
        errorMessage.contains("data:", ignoreCase = true) -> "Failed to load $sourceSummary"
        else -> errorMessage
    }
}

fun buildImageRequest(
    context: PlatformContext,
    url: String,
    cookie: String = "",
    referer: String = YamiboRoute.Domain.build(),
    retryKey: Int = 0,
    enableCrossfade: Boolean = true,
): ImageRequest {
    val fullUrl = normalizeImageUrl(url)
    val cacheKey = if (fullUrl.startsWith("https://") || fullUrl.startsWith("http://")) {
        readerAccessScope(cookie) + "|" + fullUrl
    } else fullUrl
    return ImageRequest.Builder(context)
        .data(fullUrl)
        .memoryCacheKey(cacheKey)
        .diskCacheKey(cacheKey)
        .precision(Precision.INEXACT)
        .httpHeaders(buildImageNetworkHeaders(fullUrl, cookie, referer))
        .crossfade(enableCrossfade)
        .apply {
            if (retryKey > 0) {
                memoryCachePolicy(CachePolicy.DISABLED)
                diskCachePolicy(CachePolicy.DISABLED)
            }
        }
        .build()
}

@Composable
fun rememberImageRequest(url: String, retryKey: Int = 0, enableCrossfade: Boolean = true): ImageRequest {
    val context = LocalPlatformContext.current
    val authRepo = LocalAuthRepository.current
    val fullUrl = normalizeImageUrl(url)
    val cookie = authRepo.cookieStore.load().orEmpty()

    return remember(fullUrl, cookie, retryKey, enableCrossfade) {
        buildImageRequest(
            context = context,
            url = fullUrl,
            cookie = cookie,
            retryKey = retryKey,
            enableCrossfade = enableCrossfade,
        )
    }
}

internal object ArchivedImageFetcher : Fetcher.Factory<Uri> {
    val downloads = kotlinx.coroutines.flow.MutableStateFlow<DownloadRepository?>(null)

    override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
        if (data.scheme != "yamibo-image") return null
        return Fetcher {
            val source = checkNotNull(downloads.value).openArchivedImage(data.toString())
            SourceFetchResult(ImageSource(source.buffer(), options.fileSystem), null, DataSource.DISK)
        }
    }
}

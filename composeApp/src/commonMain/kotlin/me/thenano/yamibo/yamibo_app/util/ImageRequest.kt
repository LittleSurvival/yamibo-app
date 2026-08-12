package me.thenano.yamibo.yamibo_app.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import coil3.PlatformContext
import coil3.compose.LocalPlatformContext
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Precision
import io.github.littlesurvival.YamiboRoute
import me.thenano.yamibo.yamibo_app.LocalAuthRepository

/**
 * Converts an image source into the URL or URI passed to Coil.
 *
 * Absolute network URLs and local `data:`, `content://`, or `file://` URIs are preserved. Relative
 * forum attachment paths are resolved against [YamiboRoute.Domain].
 */
fun normalizeImageUrl(url: String): String {
    val cleaned = repairMalformedImageUri(url)
    return if (cleaned.contains("://") || cleaned.startsWith("data:", ignoreCase = true)) {
        cleaned
    } else {
        "${YamiboRoute.Domain.build().trimEnd('/')}/${cleaned.removePrefix("/")}"
    }
}

/**
 * Restores an inline or local URI that was accidentally prefixed with an HTTP origin.
 *
 * For example, `http://data:image/...` must become `data:image/...`, and
 * `https://bbs.yamibo.com/content://...` must become `content://...`, before the source is passed
 * to Coil. Without this repair, Coil treats the value as an HTTP URL instead of inline or local
 * data.
 */
private fun repairMalformedImageUri(url: String): String {
    val domain = YamiboRoute.Domain.build().trimEnd('/')
    val repaired = when {
        url.startsWith("http://data:image/", ignoreCase = true) -> url.substring("http://".length)
        url.startsWith("https://data:image/", ignoreCase = true) -> url.substring("https://".length)
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

internal fun buildImageNetworkHeaders(
    normalizedUrl: String,
    cookie: String,
    referer: String,
): NetworkHeaders = NetworkHeaders.Builder().apply {
    if (normalizedUrl.startsWith(YamiboRoute.Domain.build())) {
        add("Cookie", cookie)
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
    return ImageRequest.Builder(context)
        .data(fullUrl)
        .memoryCacheKey(fullUrl)
        .diskCacheKey(fullUrl)
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

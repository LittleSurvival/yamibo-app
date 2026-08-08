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
 * Absolute network URLs and local `content://` or `file://` URIs are preserved. Relative forum
 * attachment paths are resolved against [YamiboRoute.Domain].
 */
fun normalizeImageUrl(url: String): String {
    val cleaned = repairDomainPrefixedLocalUri(url)
    return if (cleaned.contains("://")) {
        cleaned
    } else {
        "${YamiboRoute.Domain.build().trimEnd('/')}/${cleaned.removePrefix("/")}"
    }
}

/**
 * Restores a local URI that was accidentally prefixed with the Yamibo forum origin.
 *
 * For example, `https://bbs.yamibo.com/content://...` must become `content://...` before it is
 * passed to Coil. Without this repair, Coil treats the value as an HTTP URL instead of a local
 * content or file URI.
 */
private fun repairDomainPrefixedLocalUri(url: String): String {
    val domain = YamiboRoute.Domain.build().trimEnd('/')
    return when {
        url.startsWith("$domain/content://") -> url.removePrefix("$domain/")
        url.startsWith("$domain/file://") -> url.removePrefix("$domain/")
        else -> url
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
    val isYamiboDomain = fullUrl.startsWith(YamiboRoute.Domain.build())

    return ImageRequest.Builder(context)
        .data(fullUrl)
        .memoryCacheKey(fullUrl)
        .diskCacheKey(fullUrl)
        .precision(Precision.INEXACT)
        .httpHeaders(
            NetworkHeaders.Builder().apply {
                if (isYamiboDomain) {
                    add("Cookie", cookie)
                    add("Referer", referer)
                }
            }.build()
        )
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

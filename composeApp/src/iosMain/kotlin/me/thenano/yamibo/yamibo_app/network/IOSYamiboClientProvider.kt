package me.thenano.yamibo.yamibo_app.network

import io.github.littlesurvival.YamiboClient
import io.github.littlesurvival.YamiboRoute
import kotlinx.coroutines.suspendCancellableCoroutine
import me.thenano.yamibo.yamibo_app.store.auth.CookieStore
import me.thenano.yamibo.yamibo_app.util.auth.composeYamiboClientCookieHeader
import platform.Foundation.NSHTTPCookie
import platform.Foundation.NSHTTPCookieStorage
import platform.Foundation.NSURL
import kotlin.coroutines.resume

/** Owns the single Yamibo client for the lifetime of the iOS app process. */
internal object IOSYamiboClientProvider {
    private val client: YamiboClient by lazy { YamiboClient(timeoutMillis = 60_000L) }

    fun get(cookieStore: CookieStore): YamiboClient = hydrate(
        authenticationCookieHeader = cookieStore.load(),
        platformCookieHeader = foundationCookieHeader(),
    )

    suspend fun getForBackground(cookieStore: CookieStore): YamiboClient {
        val webViewCookieHeader = suspendCancellableCoroutine { continuation ->
            WKWebViewCookieBridge.readAll { cookies ->
                if (continuation.isActive) {
                    continuation.resume(cookies.toCookieHeader())
                }
            }
        }
        return hydrate(
            authenticationCookieHeader = cookieStore.load(),
            platformCookieHeader = listOf(foundationCookieHeader(), webViewCookieHeader)
                .filter { it.isNotBlank() }
                .joinToString("; "),
        )
    }

    private fun hydrate(
        authenticationCookieHeader: String?,
        platformCookieHeader: String?,
    ): YamiboClient {
        client.setCookie(
            composeYamiboClientCookieHeader(authenticationCookieHeader, platformCookieHeader),
            importNox = true,
        )
        return client
    }

    private fun foundationCookieHeader(): String {
        val url = NSURL.URLWithString(YamiboRoute.Domain.build()) ?: return ""
        return NSHTTPCookieStorage.sharedHTTPCookieStorage.cookiesForURL(url)
            .orEmpty()
            .filterIsInstance<NSHTTPCookie>()
            .toCookieHeader()
    }

    private fun List<NSHTTPCookie>.toCookieHeader(): String =
        joinToString("; ") { cookie -> "${cookie.name}=${cookie.value}" }
}

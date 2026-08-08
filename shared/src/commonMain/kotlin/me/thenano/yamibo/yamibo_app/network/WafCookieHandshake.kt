package me.thenano.yamibo.yamibo_app.network

import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.SendingRequest
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.client.plugins.cookies.CookiesStorage
import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.parseClientCookiesHeader
import io.ktor.http.parseServerSetCookieHeader
import io.ktor.http.renderCookieHeader
import io.github.littlesurvival.YamiboRoute

private const val WAF_COOKIE_NAME = "abymg_id"

/**
 * Completes the Baidu image-WAF cookie handshake without taking ownership of the user's forum
 * session cookies.
 *
 * The WAF can answer the first image request with a same-URL redirect and an `abymg_id` cookie.
 * Ktor must save that cookie before following the redirect and add it to the next request, or the
 * server repeats the redirect indefinitely. Ktor's standard `HttpCookies` plugin is intentionally
 * not used here: it also captures the login cookie supplied by the image request and then replaces
 * the request's complete `Cookie` header from its own storage. This plugin stores only `abymg_id`
 * and merges it into the existing header instead.
 *
 * The storage is in memory and belongs to one `HttpClient`. Coil keeps its network client lazily for
 * the lifetime of the singleton image loader, so the WAF cookie is reused by subsequent images while
 * remaining isolated from unrelated hosts and application restarts.
 */
internal val WafCookieHandshake = createClientPlugin("WafCookieHandshake") {
    val storage = WafCookieStorage()
    val yamiboDomain = YamiboRoute.Domain.build()

    on(SendingRequest) { request, _ ->
        val requestUrl = request.url.build()
        if (!requestUrl.toString().startsWith(yamiboDomain)) return@on

        val wafCookies = storage.get(requestUrl)
        if (wafCookies.isEmpty()) return@on

        request.headers[HttpHeaders.Cookie] = mergeWafCookies(
            existingHeader = request.headers[HttpHeaders.Cookie],
            wafCookies = wafCookies,
        )
    }

    on(Send) { request ->
        val call = proceed(request)
        if (call.request.url.toString().startsWith(yamiboDomain)) {
            call.response.headers.getAll(HttpHeaders.SetCookie)
                .orEmpty()
                .mapNotNull { header -> runCatching { parseServerSetCookieHeader(header) }.getOrNull() }
                .filter { cookie -> cookie.name == WAF_COOKIE_NAME }
                .forEach { cookie -> storage.addCookie(call.request.url, cookie) }
        }
        call
    }

    onClose(storage::close)
}

/**
 * Delegates expiry, path, domain, and secure-cookie matching to Ktor while rejecting every cookie
 * except the WAF token. In particular, response cookies and the manually supplied forum login
 * cookie must never accumulate in this client-owned storage.
 */
private class WafCookieStorage : CookiesStorage {
    private val delegate = AcceptAllCookiesStorage()

    override suspend fun get(requestUrl: Url): List<Cookie> = delegate.get(requestUrl)

    override suspend fun addCookie(requestUrl: Url, cookie: Cookie) {
        if (cookie.name == WAF_COOKIE_NAME) {
            delegate.addCookie(requestUrl, cookie)
        }
    }

    override fun close() {
        delegate.close()
    }
}

private fun mergeWafCookies(existingHeader: String?, wafCookies: List<Cookie>): String {
    val existingCookies = existingHeader
        ?.let(::parseClientCookiesHeader)
        .orEmpty()
        .filterKeys { name -> name != WAF_COOKIE_NAME }
        .map { (name, value) -> "$name=$value" }

    return (existingCookies + wafCookies.map(::renderCookieHeader)).joinToString("; ")
}

package me.thenano.yamibo.yamibo_app.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import platform.Foundation.NSDate
import platform.Foundation.NSHTTPCookie
import platform.Foundation.NSHTTPCookieStorage
import platform.Foundation.NSURL
import platform.WebKit.WKHTTPCookieStore
import platform.WebKit.WKWebsiteDataStore
import kotlin.coroutines.resume

/**
 * Bridges WKWebView's cookie store (WKHTTPCookieStore) to NSHTTPCookieStorage.
 *
 * WKWebView cookies live in the WebKit network stack and never surface in
 * NSHTTPCookieStorage, so the app's ktor/NSURLSession stack (and the auth
 * cookie sync that reads NSHTTPCookieStorage) cannot see them. This mirrors
 * Android, where the WebView shares one global CookieManager.
 */
object WKWebViewCookieBridge {
    private val defaultStore: WKHTTPCookieStore
        get() = WKWebsiteDataStore.defaultDataStore().httpCookieStore

    /** WebKit is main-thread only; a stalled callback must not block startup or a request. */
    suspend fun cookieHeaderForUrl(url: String): String? = withContext(Dispatchers.Main) {
        val target = NSURL.URLWithString(url) ?: return@withContext null
        val host = target.host?.lowercase() ?: return@withContext null
        val path = target.path?.ifEmpty { "/" } ?: "/"
        withTimeoutOrNull(3_000L) {
            suspendCancellableCoroutine { continuation ->
                readAll { cookies ->
                    val header = cookies.filter { cookie ->
                        val domain = cookie.domain.removePrefix(".").lowercase()
                        val domainMatches = host == domain ||
                            (cookie.domain.startsWith(".") && host.endsWith(".$domain"))
                        val cookiePath = cookie.path.ifEmpty { "/" }
                        domainMatches &&
                            (path == cookiePath || path.startsWith(cookiePath.trimEnd('/') + "/")) &&
                            (!cookie.secure || target.scheme == "https") &&
                            (cookie.expiresDate?.timeIntervalSinceReferenceDate?.let {
                                it > NSDate().timeIntervalSinceReferenceDate
                            } ?: true)
                    }.joinToString("; ") { "${it.name}=${it.value}" }
                    if (continuation.isActive) continuation.resume(header)
                }
            }
        }
    }

    /** Reads all cookies from [store] (defaults to the app-wide persistent store). */
    fun readAll(
        store: WKHTTPCookieStore = defaultStore,
        onResult: (List<NSHTTPCookie>) -> Unit,
    ) {
        store.getAllCookies { cookies ->
            onResult(cookies.orEmpty().filterIsInstance<NSHTTPCookie>())
        }
    }

    /** Copies cookies from [store] into NSHTTPCookieStorage, then invokes [onComplete]. */
    fun bridgeToNSHTTPCookieStorage(
        store: WKHTTPCookieStore = defaultStore,
        onComplete: () -> Unit,
    ) {
        readAll(store) { cookies ->
            if (cookies.isNotEmpty()) {
                val storage = NSHTTPCookieStorage.sharedHTTPCookieStorage
                cookies.forEach { storage.setCookie(it) }
            }
            onComplete()
        }
    }

    /** Deletes cookies except [preservingNames] after WebKit confirms every deletion. */
    suspend fun clearAll(
        store: WKHTTPCookieStore = defaultStore,
        preservingNames: Set<String> = emptySet(),
    ) {
        val cookies = suspendCancellableCoroutine { continuation ->
            readAll(store) { continuation.resume(it) }
        }
        cookies.filterNot { it.name in preservingNames }.forEach { cookie ->
            suspendCancellableCoroutine { continuation ->
                store.deleteCookie(cookie) {
                    continuation.resume(Unit)
                }
            }
        }
    }
}

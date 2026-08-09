package me.thenano.yamibo.yamibo_app.network

import platform.Foundation.NSHTTPCookie
import platform.Foundation.NSHTTPCookieStorage
import platform.WebKit.WKHTTPCookieStore
import platform.WebKit.WKWebsiteDataStore

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

    /** Deletes every cookie from [store] (defaults to the app-wide persistent store). */
    fun clearAll(store: WKHTTPCookieStore = defaultStore) {
        readAll(store) { cookies ->
            cookies.forEach { store.deleteCookie(it, completionHandler = null) }
        }
    }
}

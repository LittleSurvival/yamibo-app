package me.thenano.yamibo.yamibo_app.repository

import me.thenano.yamibo.yamibo_app.util.auth.setNativeCookies

import me.thenano.yamibo.yamibo_app.repository.servernotice.isForum524Failure
import io.github.littlesurvival.YamiboClient
import io.github.littlesurvival.YamiboRoute
import io.github.littlesurvival.core.YamiboResult
import io.github.littlesurvival.dto.page.ProfilePage
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import me.thenano.yamibo.yamibo_app.i18n.i18n
import me.thenano.yamibo.yamibo_app.network.WKWebViewCookieBridge
import me.thenano.yamibo.yamibo_app.store.auth.CookieStore
import me.thenano.yamibo.yamibo_app.store.auth.UserStore
import me.thenano.yamibo.yamibo_app.store.forum.ForumFavoriteStore
import me.thenano.yamibo.yamibo_app.util.auth.parseCookieStringToMap
import platform.Foundation.NSDate
import platform.Foundation.NSHTTPCookie
import platform.Foundation.NSHTTPCookieDomain
import platform.Foundation.NSHTTPCookieExpires
import platform.Foundation.NSHTTPCookieName
import platform.Foundation.NSHTTPCookiePath
import platform.Foundation.NSHTTPCookieSecure
import platform.Foundation.NSHTTPCookieStorage
import platform.Foundation.NSHTTPCookieValue
import platform.Foundation.NSURL
import platform.WebKit.WKWebsiteDataStore
import kotlin.time.Duration.Companion.milliseconds

class IOSAuthRepository(
    override val cookieStore: CookieStore,
    override val userStore: UserStore,
    override val yamiboClient: YamiboClient,
    private val forumFavoriteStore: ForumFavoriteStore? = null,
) : AuthRepository {
    override suspend fun isLoggedIn(): Boolean {
        return AuthRepository.hasCompleteAuthenticationCookies(cookieStore.load())
    }

    override suspend fun fetchStatus(): YamiboResult<Boolean> {
        if (!isLoggedIn()) return YamiboResult.Failure(i18n("查無登入資料，請重新登入"))

        yamiboClient.setNativeCookies(cookieStore.load() ?: "")
        // Profile enrichment must not hold the completed WebView login behind WAF/network retries.
        val profileResult = withTimeoutOrNull(20_000L) { yamiboClient.fetchProfileInfo() }
            ?: return YamiboResult.Failure(i18n("獲取用戶資料逾時，請稍後重試"))
        when (profileResult) {
            is YamiboResult.Success -> {
                userStore.save(profileResult.value)
                return YamiboResult.Success(true)
            }

            is YamiboResult.NotLoggedIn -> {
                logOut()
                return YamiboResult.Failure(i18n("登入資訊過期，請重新登入"))
            }

            is YamiboResult.Maintenance -> {
                return YamiboResult.Failure(i18n("伺服器正在維護中"))
            }

            is YamiboResult.Failure -> {
                if (isForum524Failure(profileResult.reason)) return profileResult
                return YamiboResult.Failure(i18n("獲取用戶資料失敗: {}", profileResult.reason))
            }

            is YamiboResult.NoPermission -> {
                return YamiboResult.Failure(i18n("獲取用戶資料失敗: {}", profileResult.reason))
            }

            is YamiboResult.WafChallenge -> return profileResult
        }
    }

    override suspend fun startLoginDetect(onSuccess: suspend () -> Unit, onTimeOut: () -> Unit) {
        val detected = withTimeoutOrNull(loginTimeout) {
            while (true) {
                // Login may complete through XHR while subresources keep didFinish pending.
                WKWebViewCookieBridge.cookieHeaderForUrl(YamiboRoute.Domain.build())?.let {
                    importCookieHeader(it)
                }
                if (isLoggedIn()) return@withTimeoutOrNull true
                delay(loginDetectInterval.milliseconds)
            }
        }
        if (detected == true) onSuccess() else onTimeOut()
    }

    override fun restoreCookiesToWebView(onComplete: () -> Unit) {
        val cookies = persistentAuthenticationCookies(cookieStore.load())
        if (cookies.isEmpty()) {
            onComplete()
            return
        }

        val storage = NSHTTPCookieStorage.sharedHTTPCookieStorage
        val webViewStore = WKWebsiteDataStore.defaultDataStore().httpCookieStore
        var remaining = cookies.size
        cookies.forEach { cookie ->
            storage.setCookie(cookie)
            webViewStore.setCookie(cookie) {
                remaining -= 1
                if (remaining == 0) onComplete()
            }
        }
    }

    override fun syncCookieFromWebView() {
        val url = NSURL.URLWithString(YamiboRoute.Domain.build()) ?: return
        val cookies = NSHTTPCookieStorage.sharedHTTPCookieStorage.cookiesForURL(url)
        if (!cookies.isNullOrEmpty()) {
            val cookieStrings = mutableListOf<String>()
            for (cookie in cookies) {
                if (cookie is NSHTTPCookie) {
                    cookieStrings.add("${cookie.name}=${cookie.value}")
                }
            }
            val cookieHeader = cookieStrings.joinToString("; ")
            importCookieHeader(cookieHeader)
        }
    }

    private fun importCookieHeader(cookieHeader: String) {
        val previousAuth = AuthRepository.completeAuthenticationCookies(cookieStore.load())
        cookieStore.save(cookieHeader)
        yamiboClient.setNativeCookies(cookieHeader, importNox = true)
        if (AuthRepository.completeAuthenticationCookies(cookieHeader) != previousAuth) {
            restoreCookiesToWebView()
        }
    }

    override fun currentUser(): ProfilePage? {
        return userStore.load()
    }

    override suspend fun logOut() {
        yamiboClient.clearCookies(clearNox = false)
        cookieStore.clear()
        userStore.clear()
        forumFavoriteStore?.clear()

        WKWebViewCookieBridge.clearAll(preservingNames = setOf(NOX_COOKIE_NAME))
        val storage = NSHTTPCookieStorage.sharedHTTPCookieStorage
        val cookies = storage.cookies
        if (cookies != null) {
            for (cookie in cookies) {
                val httpCookie = cookie as NSHTTPCookie
                if (httpCookie.name != NOX_COOKIE_NAME) {
                    storage.deleteCookie(httpCookie)
                }
            }
        }
    }

    private companion object {
        const val NOX_COOKIE_NAME = "nox_jst_v1"
    }

    private fun persistentAuthenticationCookies(cookieHeader: String?): List<NSHTTPCookie> {
        val host = NSURL.URLWithString(YamiboRoute.Domain.build())?.host ?: return emptyList()
        val expires = NSDate(
            timeIntervalSinceReferenceDate = NSDate().timeIntervalSinceReferenceDate +
                AuthRepository.PERSISTENT_COOKIE_MAX_AGE_SECONDS.toDouble(),
        )
        return AuthRepository.completeAuthenticationCookies(cookieHeader).mapNotNull { (name, value) ->
            NSHTTPCookie.cookieWithProperties(
                mapOf(
                    NSHTTPCookieName to name,
                    NSHTTPCookieValue to value,
                    NSHTTPCookieDomain to host,
                    NSHTTPCookiePath to "/",
                    NSHTTPCookieSecure to "TRUE",
                    NSHTTPCookieExpires to expires,
                    "HttpOnly" to "TRUE",
                ),
            )
        }
    }
}

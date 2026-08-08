package me.thenano.yamibo.yamibo_app.repository

import io.github.littlesurvival.YamiboClient
import io.github.littlesurvival.YamiboRoute
import io.github.littlesurvival.core.YamiboResult
import io.github.littlesurvival.dto.page.ProfilePage
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import me.thenano.yamibo.yamibo_app.Logger
import me.thenano.yamibo.yamibo_app.i18n.i18n
import me.thenano.yamibo.yamibo_app.store.auth.CookieStore
import me.thenano.yamibo.yamibo_app.store.auth.UserStore
import me.thenano.yamibo.yamibo_app.store.forum.ForumFavoriteStore
import me.thenano.yamibo.yamibo_app.util.auth.parseCookieStringToMap
import platform.Foundation.NSHTTPCookie
import platform.Foundation.NSHTTPCookieStorage
import platform.Foundation.NSURL
import platform.WebKit.WKWebsiteDataStore
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds

class IOSAuthRepository(
    override val cookieStore: CookieStore,
    override val userStore: UserStore,
    override val yamiboClient: YamiboClient,
    private val forumFavoriteStore: ForumFavoriteStore? = null,
) : AuthRepository {
    override suspend fun isLoggedIn(): Boolean {
        return parseCookieStringToMap(cookieStore.load()).containsKey(authCookieKey)
    }

    override suspend fun fetchStatus(): YamiboResult<Boolean> {
        if (!isLoggedIn()) return YamiboResult.Failure(i18n("查無登入資料，請重新登入"))

        yamiboClient.setCookie(cookieStore.load() ?: "")
        when (val profileResult = yamiboClient.fetchProfileInfo()) {
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
                return YamiboResult.Failure(i18n("獲取用戶資料失敗: {}", profileResult.reason))
            }

            is YamiboResult.NoPermission -> {
                return YamiboResult.Failure(i18n("獲取用戶資料失敗: {}", profileResult.reason))
            }

            is YamiboResult.WafChallenge -> return profileResult
        }
    }

    override suspend fun prewarmWafCookie(): Boolean = runWafCookiePrewarm(
        readPlatformCookieHeader = ::readPlatformCookieHeader,
        loadStoredCookieHeader = cookieStore::load,
        saveStoredCookieHeader = cookieStore::save,
        importCookieHeader = { cookieHeader ->
            yamiboClient.setCookie(cookieHeader, importNox = true)
        },
        triggerChallenge = {
            yamiboClient.setCookie(cookieStore.load().orEmpty())
            yamiboClient.fetchHomePage()
        },
        onFailure = { error ->
            Logger.w(
                "IOSAuthRepository",
                "WAF cookie prewarm failed; falling back to login WebView",
                error,
            )
        },
    )

    @OptIn(ExperimentalForeignApi::class)
    private suspend fun readPlatformCookieHeader(): String =
        suspendCancellableCoroutine { continuation ->
            WKWebsiteDataStore.defaultDataStore().httpCookieStore.getAllCookies { cookies ->
                val cookieHeader = cookies.orEmpty()
                    .filterIsInstance<NSHTTPCookie>()
                    .filter { cookie ->
                        cookie.domain
                            .removePrefix(".")
                            .equals(YAMIBO_HOST, ignoreCase = true)
                    }
                    .joinToString("; ") { cookie -> "${cookie.name}=${cookie.value}" }
                if (continuation.isActive) continuation.resume(cookieHeader)
            }
        }

    override suspend fun startLoginDetect(onSuccess: suspend () -> Unit, onTimeOut: () -> Unit) {
        var elapsed = 0L

        while (elapsed < loginTimeout) {
            if (isLoggedIn()) {
                onSuccess()
                return
            }
            delay(loginDetectInterval.milliseconds)
            elapsed += loginDetectInterval
        }

        onTimeOut()
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
            cookieStore.save(cookieHeader)
            yamiboClient.setCookie(cookieHeader, importNox = true)
        }
    }

    override fun currentUser(): ProfilePage? {
        return userStore.load()
    }

    override suspend fun logOut() {
        yamiboClient.clearCookies()
        cookieStore.clear()
        userStore.clear()
        forumFavoriteStore?.clear()

        /** remove cookie from webview */
        val storage = NSHTTPCookieStorage.sharedHTTPCookieStorage
        val cookies = storage.cookies
        if (cookies != null) {
            for (cookie in cookies) {
                storage.deleteCookie(cookie as NSHTTPCookie)
            }
        }
    }

    private companion object {
        const val YAMIBO_HOST = "bbs.yamibo.com"
    }
}

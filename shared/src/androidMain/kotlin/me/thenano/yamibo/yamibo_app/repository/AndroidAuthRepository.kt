package me.thenano.yamibo.yamibo_app.repository

import android.webkit.CookieManager
import io.github.littlesurvival.YamiboClient
import io.github.littlesurvival.YamiboRoute
import io.github.littlesurvival.core.YamiboResult
import io.github.littlesurvival.dto.page.ProfilePage
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import me.thenano.yamibo.yamibo_app.i18n.i18n
import me.thenano.yamibo.yamibo_app.store.auth.CookieStore
import me.thenano.yamibo.yamibo_app.store.auth.UserStore
import me.thenano.yamibo.yamibo_app.store.forum.ForumFavoriteStore
import me.thenano.yamibo.yamibo_app.util.auth.parseCookieStringToMap
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds

class AndroidAuthRepository(
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
        val cookie = CookieManager.getInstance().getCookie(YamiboRoute.Domain.build()) ?: return
        cookieStore.save(cookie)
        yamiboClient.setCookie(cookie, importNox = true)
    }

    override fun currentUser(): ProfilePage? {
        return userStore.load()
    }

    override suspend fun logOut() {
        yamiboClient.clearCookies(clearNox = false)
        cookieStore.clear()
        userStore.clear()
        forumFavoriteStore?.clear()
        CookieManager.getInstance().clearAllExceptNox(YamiboRoute.Domain.build())
    }

    private suspend fun CookieManager.clearAllExceptNox(url: String) {
        val nox = parseCookieStringToMap(getCookie(url))[NOX_COOKIE_NAME]
        suspendCancellableCoroutine { continuation ->
            removeAllCookies {
                if (nox == null) {
                    flush()
                    if (continuation.isActive) continuation.resume(Unit)
                } else {
                    setCookie(url, "$NOX_COOKIE_NAME=$nox; Path=/; Secure") {
                        flush()
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                }
            }
        }
    }

    private companion object {
        const val NOX_COOKIE_NAME = "nox_jst_v1"
    }
}

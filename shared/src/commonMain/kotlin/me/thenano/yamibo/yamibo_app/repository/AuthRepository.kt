package me.thenano.yamibo.yamibo_app.repository

import io.github.littlesurvival.YamiboClient
import io.github.littlesurvival.core.YamiboResult
import io.github.littlesurvival.dto.page.ProfilePage
import me.thenano.yamibo.yamibo_app.store.auth.CookieStore
import me.thenano.yamibo.yamibo_app.store.auth.UserStore

interface AuthRepository {
    /** constants */
    val authCookieKey get() = "EeqY_2132_auth"
    val loginDetectInterval get() = 1000L
    val loginTimeout get() = 300_000L

    /** constructor */
    val cookieStore: CookieStore
    val userStore: UserStore
    val yamiboClient: YamiboClient

    /** auth function */
    suspend fun isLoggedIn(): Boolean
    suspend fun fetchStatus(): YamiboResult<Boolean>

    /**
     * Best-effort WAF cookie prewarm for the login WebView.
     *
     * Returns [WafPrewarmResult.Success] only when the platform WebView cookie store contains
     * a non-empty nox_jst_v1 after the attempt; the caller fail-opens on any other outcome.
     * Coroutine cancellation propagates.
     */
    suspend fun prewarmWafCookie(): WafPrewarmResult

    suspend fun startLoginDetect(onSuccess: suspend () -> Unit, onTimeOut: () -> Unit = {})
    fun syncCookieFromWebView()

    fun currentUser(): ProfilePage?

    suspend fun logOut()
}

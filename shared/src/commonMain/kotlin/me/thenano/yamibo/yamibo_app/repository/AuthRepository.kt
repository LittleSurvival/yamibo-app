package me.thenano.yamibo.yamibo_app.repository

import io.github.littlesurvival.YamiboClient
import io.github.littlesurvival.core.YamiboResult
import io.github.littlesurvival.dto.page.ProfilePage
import me.thenano.yamibo.yamibo_app.store.auth.CookieStore
import me.thenano.yamibo.yamibo_app.store.auth.UserStore
import me.thenano.yamibo.yamibo_app.util.auth.parseCookieStringToMap

interface AuthRepository {
    val loginDetectInterval get() = 1000L
    val loginTimeout get() = 300_000L

    /** constructor */
    val cookieStore: CookieStore
    val userStore: UserStore
    val yamiboClient: YamiboClient

    /** auth function */
    suspend fun isLoggedIn(): Boolean
    suspend fun fetchStatus(): YamiboResult<Boolean>

    suspend fun startLoginDetect(onSuccess: suspend () -> Unit, onTimeOut: () -> Unit = {})
    fun restoreCookiesToWebView(onComplete: () -> Unit = {}) = onComplete()
    fun syncCookieFromWebView()

    fun currentUser(): ProfilePage?

    suspend fun logOut()

    companion object {
        const val AUTH_COOKIE_KEY = "EeqY_2132_auth"
        const val SALT_KEY_COOKIE_KEY = "EeqY_2132_saltkey"
        const val PERSISTENT_COOKIE_MAX_AGE_SECONDS = 400L * 24 * 60 * 60

        internal fun completeAuthenticationCookies(cookieHeader: String?): Map<String, String> {
            val cookies = parseCookieStringToMap(cookieHeader)
            val authenticationCookies = listOf(AUTH_COOKIE_KEY, SALT_KEY_COOKIE_KEY)
                .mapNotNull { name -> cookies[name]?.takeIf(String::isNotBlank)?.let { name to it } }
                .toMap()
            return authenticationCookies.takeIf { it.size == 2 }.orEmpty()
        }

        internal fun hasCompleteAuthenticationCookies(cookieHeader: String?): Boolean =
            completeAuthenticationCookies(cookieHeader).isNotEmpty()
    }
}

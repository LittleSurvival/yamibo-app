package me.thenano.yamibo.yamibo_app.repository

import io.github.littlesurvival.YamiboClient
import io.github.littlesurvival.core.YamiboResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.thenano.yamibo.yamibo_app.store.auth.CookieStore
import me.thenano.yamibo.yamibo_app.store.auth.UserStore
import me.thenano.yamibo.yamibo_app.store.forum.ForumFavoriteStore

class DesktopAuthRepository(
    override val cookieStore: CookieStore,
    override val userStore: UserStore,
    override val yamiboClient: YamiboClient,
    private val forumFavorites: ForumFavoriteStore,
) : AuthRepository {
    private val generation = MutableStateFlow(0L)
    val browserGeneration = generation.asStateFlow()
    var requestBrowserSync: (() -> Unit)? = null

    override suspend fun isLoggedIn() = AuthRepository.hasCompleteAuthenticationCookies(cookieStore.load())
    override fun currentUser() = userStore.load()
    override fun syncCookieFromWebView() { requestBrowserSync?.invoke() }

    @Synchronized fun acceptBrowserCookies(expectedGeneration: Long, header: String) {
        if (generation.value != expectedGeneration) return
        val lostAuthentication = AuthRepository.hasCompleteAuthenticationCookies(cookieStore.load()) &&
            !AuthRepository.hasCompleteAuthenticationCookies(header)
        cookieStore.save(header)
        if (lostAuthentication) {
            generation.value += 1
            requestBrowserSync = null
            userStore.clear()
        }
        yamiboClient.setCookie(header, importNox = true)
    }

    override suspend fun fetchStatus(): YamiboResult<Boolean> {
        val snapshot = synchronized(this) {
            val cookie = cookieStore.load().orEmpty()
            if (!AuthRepository.hasCompleteAuthenticationCookies(cookie)) return YamiboResult.Failure("查無登入資料，請重新登入")
            yamiboClient.setCookie(cookie)
            generation.value to AuthRepository.completeAuthenticationCookies(cookie)
        }
        return when (val result = yamiboClient.fetchProfileInfo()) {
            is YamiboResult.Success -> synchronized(this) {
                if (!isCurrent(snapshot)) YamiboResult.Failure("登入狀態已變更")
                else { userStore.save(result.value); YamiboResult.Success(true) }
            }
            is YamiboResult.NotLoggedIn -> {
                val cleared = synchronized(this) {
                    if (isCurrent(snapshot)) { clearSession(); true } else false
                }
                if (cleared) forumFavorites.clear()
                YamiboResult.Failure(if (cleared) "登入資訊過期，請重新登入" else "登入狀態已變更")
            }
            is YamiboResult.WafChallenge -> result
            else -> YamiboResult.Failure(result.message())
        }
    }

    override suspend fun startLoginDetect(onSuccess: suspend () -> Unit, onTimeOut: () -> Unit) {
        val deadline = System.nanoTime() + loginTimeout * 1_000_000
        while (System.nanoTime() < deadline) {
            if (isLoggedIn()) { onSuccess(); return }
            delay(loginDetectInterval)
        }
        onTimeOut()
    }

    override suspend fun logOut() {
        synchronized(this) { clearSession() }
        forumFavorites.clear()
    }

    private fun isCurrent(snapshot: Pair<Long, Map<String, String>>) =
        generation.value == snapshot.first && AuthRepository.completeAuthenticationCookies(cookieStore.load()) == snapshot.second

    private fun clearSession() {
            generation.value += 1
            requestBrowserSync = null
            yamiboClient.clearCookies(clearNox = true)
            cookieStore.clear()
            userStore.clear()
    }
}

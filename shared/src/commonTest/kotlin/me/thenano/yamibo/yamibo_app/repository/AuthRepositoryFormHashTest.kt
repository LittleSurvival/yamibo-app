package me.thenano.yamibo.yamibo_app.repository

import io.github.littlesurvival.YamiboClient
import io.github.littlesurvival.core.YamiboResult
import io.github.littlesurvival.dto.page.ProfilePage
import io.github.littlesurvival.dto.value.FormHash
import kotlinx.coroutines.runBlocking
import me.thenano.yamibo.yamibo_app.store.auth.CookieStore
import me.thenano.yamibo.yamibo_app.store.auth.UserStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class AuthRepositoryFormHashTest {
    @Test
    fun refreshReplacesExpiredFormHashBeforeReading() = runBlocking {
        val events = mutableListOf<String>()
        val auth = FormHashAuthFake(events)

        val result = assertIs<YamiboResult.Success<FormHash>>(auth.refreshFormHash())

        assertEquals(listOf("refresh", "read-profile"), events)
        assertEquals(FormHash("fresh"), result.value)
        assertEquals(result.value, auth.userStore.load()?.formHash)
    }

    @Test
    fun missingLocalProfileCanBeRecoveredByRefresh() = runBlocking {
        val auth = FormHashAuthFake().apply { profile = null }

        assertEquals(FormHash("fresh"), assertIs<YamiboResult.Success<FormHash>>(auth.refreshFormHash()).value)
    }

    @Test
    fun refreshFailuresArePreservedWithoutReadingOldProfileOrRetrying() = runBlocking {
        val failures: List<YamiboResult<Nothing>> = listOf(
            YamiboResult.Failure("offline"),
            YamiboResult.NoPermission("沒有權限"),
            YamiboResult.NotLoggedIn,
            YamiboResult.Maintenance,
            YamiboResult.WafChallenge(statusCode = 405, url = "https://bbs.yamibo.com/home.php"),
        )
        for (failure in failures) {
            val events = mutableListOf<String>()
            val auth = FormHashAuthFake(events).apply { refreshResult = failure }

            assertSame(failure, auth.refreshFormHash())
            assertEquals(listOf("refresh"), events)
        }
    }

    @Test
    fun successfulRefreshWithoutFormHashReturnsActionableFailure() = runBlocking {
        for (profile in listOf(null, UserStore.Preview.copy(formHash = null))) {
            val auth = FormHashAuthFake().apply { refreshedProfile = profile }

            val failure = assertIs<YamiboResult.Failure>(auth.refreshFormHash())

            assertEquals("無法取得搜尋校驗碼，請刷新個人資料後重試", failure.reason)
        }
    }

    @Test
    fun unsuccessfulLoginStatusDoesNotReadCachedFormHash() = runBlocking {
        val events = mutableListOf<String>()
        val auth = FormHashAuthFake(events).apply { refreshResult = YamiboResult.Success(false) }

        assertSame(YamiboResult.NotLoggedIn, auth.refreshFormHash())
        assertEquals(listOf("refresh"), events)
    }

    @Test
    fun everyRefreshReadsNewFormHashRatherThanReusingPreviousValue() = runBlocking {
        val events = mutableListOf<String>()
        val auth = FormHashAuthFake(events)
        assertEquals(FormHash("fresh"), assertIs<YamiboResult.Success<FormHash>>(auth.refreshFormHash()).value)
        auth.refreshedProfile = UserStore.Preview.copy(formHash = FormHash("newer"))

        assertEquals(FormHash("newer"), assertIs<YamiboResult.Success<FormHash>>(auth.refreshFormHash()).value)
        assertEquals(listOf("refresh", "read-profile", "refresh", "read-profile"), events)
    }
}

private class FormHashAuthFake(private val events: MutableList<String> = mutableListOf()) : AuthRepository {
    var profile: ProfilePage? = UserStore.Preview.copy(formHash = FormHash("expired"))
    var refreshedProfile: ProfilePage? = UserStore.Preview.copy(formHash = FormHash("fresh"))
    var refreshResult: YamiboResult<Boolean> = YamiboResult.Success(true)
    override val cookieStore: CookieStore = object : CookieStore {
        override fun save(value: String) = Unit
        override fun load(): String? = null
        override fun clear() = Unit
    }
    override val userStore: UserStore = object : UserStore {
        override fun load() = profile
        override fun save(userInfo: ProfilePage) { profile = userInfo }
        override fun clear() { profile = null }
    }
    override val yamiboClient = YamiboClient()
    override suspend fun isLoggedIn() = true
    override suspend fun fetchStatus(): YamiboResult<Boolean> {
        events += "refresh"
        if (refreshResult == YamiboResult.Success(true)) {
            refreshedProfile?.let(userStore::save) ?: userStore.clear()
        }
        return refreshResult
    }
    override fun currentUser(): ProfilePage? {
        events += "read-profile"
        return userStore.load()
    }
    override suspend fun startLoginDetect(onSuccess: suspend () -> Unit, onTimeOut: () -> Unit) = onSuccess()
    override fun syncCookieFromWebView() = Unit
    override suspend fun logOut() = userStore.clear()
}

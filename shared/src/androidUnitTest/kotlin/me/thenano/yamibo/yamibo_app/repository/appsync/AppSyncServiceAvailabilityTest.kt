package me.thenano.yamibo.yamibo_app.repository.appsync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.littlesurvival.YamiboClient
import io.github.littlesurvival.core.YamiboResult
import io.github.littlesurvival.dto.page.ProfilePage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import me.thenano.yamibo.yamibo_app.Database
import me.thenano.yamibo.yamibo_app.repository.AuthRepository
import me.thenano.yamibo.yamibo_app.store.auth.CookieStore
import me.thenano.yamibo.yamibo_app.store.auth.UserStore
import me.thenano.yamibo.yamibo_app.store.settings.SettingsStore

class AppSyncServiceAvailabilityTest {
    @Test
    fun serviceIsAvailableWithoutHiddenRolloutSetting() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        val database = Database(driver)
        val settings = MemorySettingsStore()
        val service = AppSyncService(database, settings, FakeAuthRepository())

        assertEquals(AppSyncServicePhase.BootstrapRequired, service.currentStatus().phase)
        assertNotSame(settings, service.operationRecordingSettingsStore(database, settings))
    }

    private class MemorySettingsStore : SettingsStore {
        private val values = mutableMapOf<String, Any>()

        override fun getInt(key: String, defaultValue: Int) = values[key] as? Int ?: defaultValue
        override fun putInt(key: String, value: Int) = set(key, value)
        override fun getFloat(key: String, defaultValue: Float) = values[key] as? Float ?: defaultValue
        override fun putFloat(key: String, value: Float) = set(key, value)
        override fun getString(key: String, defaultValue: String) =
            values[key] as? String ?: defaultValue
        override fun putString(key: String, value: String) = set(key, value)
        override fun getBoolean(key: String, defaultValue: Boolean) =
            values[key] as? Boolean ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) = set(key, value)
        override fun remove(key: String) {
            values.remove(key)
        }
        override fun hasKey(key: String) = key in values

        private fun set(key: String, value: Any) {
            values[key] = value
        }
    }

    private class FakeAuthRepository : AuthRepository {
        override val cookieStore: CookieStore = object : CookieStore {
            override fun save(value: String) = Unit
            override fun load(): String? = null
            override fun clear() = Unit
        }
        override val userStore: UserStore = object : UserStore {
            override fun load(): ProfilePage? = null
            override fun save(userInfo: ProfilePage) = Unit
            override fun clear() = Unit
        }
        override val yamiboClient = YamiboClient()

        override suspend fun isLoggedIn() = false
        override suspend fun fetchStatus(): YamiboResult<Boolean> = YamiboResult.Success(false)
        override suspend fun startLoginDetect(
            onSuccess: suspend () -> Unit,
            onTimeOut: () -> Unit,
        ) = onTimeOut()
        override fun syncCookieFromWebView() = Unit
        override fun currentUser(): ProfilePage? = null
        override suspend fun logOut() = Unit
    }
}

package me.thenano.yamibo.yamibo_app.repository.pancloud

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import me.thenano.yamibo.yamibo_app.repository.settings.AppSettingsRepository
import me.thenano.yamibo.yamibo_app.store.settings.SettingsStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PanCloudAccountRepositoryTest {

    private class MemorySettingsStore : SettingsStore {
        private val values = mutableMapOf<String, String>()

        override fun getInt(key: String, defaultValue: Int): Int = values[key]?.toIntOrNull() ?: defaultValue
        override fun putInt(key: String, value: Int) {
            values[key] = value.toString()
        }

        override fun getFloat(key: String, defaultValue: Float): Float = values[key]?.toFloatOrNull() ?: defaultValue
        override fun putFloat(key: String, value: Float) {
            values[key] = value.toString()
        }

        override fun getString(key: String, defaultValue: String): String = values[key] ?: defaultValue
        override fun putString(key: String, value: String) {
            values[key] = value
        }

        override fun getBoolean(key: String, defaultValue: Boolean): Boolean = values[key]?.toBooleanStrictOrNull() ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) {
            values[key] = value.toString()
        }

        override fun remove(key: String) {
            values.remove(key)
        }

        override fun hasKey(key: String): Boolean = key in values
    }

    private class Harness {
        private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
        val settingsStore = MemorySettingsStore()
        val appSettings = AppSettingsRepository(settingsStore)
        val listFilesCalls = mutableListOf<String?>()
        val createFolderCalls = mutableListOf<String>()
        var refreshUnauthorized = false
        var refreshNetworkFail = false
        var loginUnauthorized = false
        var loginNetworkFail = false

        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/auth/register" -> respond(
                    """{"success":true,"data":{"user":{"id":1,"username":"alice"},"access_token":"at-1","refresh_token":"rt-1","expires_in":900}}""",
                    HttpStatusCode.Created,
                    jsonHeaders,
                )
                "/api/auth/login" -> when {
                    loginNetworkFail -> throw java.io.IOException("network down")
                    loginUnauthorized -> respond(
                        """{"success":false,"error":"用户名或密码错误"}""",
                        HttpStatusCode.Unauthorized,
                        jsonHeaders,
                    )
                    else -> respond(
                        """{"success":true,"data":{"user":{"id":1,"username":"alice"},"access_token":"at-login","refresh_token":"rt-login","expires_in":900}}""",
                        HttpStatusCode.OK,
                        jsonHeaders,
                    )
                }
                "/api/auth/refresh" -> when {
                    refreshNetworkFail -> throw java.io.IOException("network down")
                    refreshUnauthorized -> respond(
                        """{"success":false,"error":"Unauthorized"}""",
                        HttpStatusCode.Unauthorized,
                        jsonHeaders,
                    )
                    else -> respond(
                        """{"success":true,"data":{"access_token":"at-refreshed","refresh_token":"rt-refreshed","expires_in":900}}""",
                        HttpStatusCode.OK,
                        jsonHeaders,
                    )
                }
                "/api/auth/logout" -> respond(
                    """{"success":true,"message":"已登出"}""",
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
                "/api/files" -> {
                    if (request.method == HttpMethod.Get) {
                        listFilesCalls += request.url.parameters["parent_id"]
                        respond("""{"success":true,"data":[]}""", HttpStatusCode.OK, jsonHeaders)
                    } else {
                        respond("""{"success":false,"error":"not found"}""", HttpStatusCode.NotFound, jsonHeaders)
                    }
                }
                "/api/files/folder" -> {
                    createFolderCalls += "yamibo"
                    respond(
                        """{"success":true,"data":{"id":"folder-1","name":"yamibo","parent_id":null}}""",
                        HttpStatusCode.Created,
                        jsonHeaders,
                    )
                }
                else -> respond("""{"success":false,"error":"not found"}""", HttpStatusCode.NotFound, jsonHeaders)
            }
        }

        val apiClient = PanCloudApiClient(HttpClient(engine), baseUrl = "https://pan.test/api")
        val repository = PanCloudAccountRepository(apiClient, appSettings)
    }

    @Test
    fun registerPersistsCredentialAndBindsFolder() = runBlocking {
        val h = Harness()
        val result = h.repository.register("alice", "password123")

        assertTrue(result.isSuccess)
        assertEquals("rt-1", h.appSettings.panCloudRefreshToken.getValue())
        assertEquals("alice", h.appSettings.panCloudUsername.getValue())
        assertEquals("at-1", h.apiClient.accessToken)
        // 根列表为空 → 自动创建 yamibo 文件夹并绑定。
        assertEquals(1, h.createFolderCalls.size)
        assertEquals("folder-1", h.appSettings.panCloudFolderId.getValue())
    }

    @Test
    fun loginPersistsCredential() = runBlocking {
        val h = Harness()
        val result = h.repository.login("alice", "password123")

        assertTrue(result.isSuccess)
        assertEquals("rt-login", h.appSettings.panCloudRefreshToken.getValue())
        assertEquals("at-login", h.apiClient.accessToken)
    }

    @Test
    fun restoreSessionRefreshesToken() = runBlocking {
        val h = Harness()
        h.appSettings.panCloudRefreshToken.setValue("old-rt")
        val result = h.repository.restoreSession()

        assertTrue(result.isSuccess)
        assertEquals("rt-refreshed", h.appSettings.panCloudRefreshToken.getValue())
        assertEquals("at-refreshed", h.apiClient.accessToken)
    }

    @Test
    fun ensureFolderBoundIsIdempotent() = runBlocking {
        val h = Harness()
        h.appSettings.panCloudFolderId.setValue("folder-existing")

        val folderId = h.repository.ensureFolderBound().getOrThrow()

        assertEquals("folder-existing", folderId)
        assertEquals(0, h.listFilesCalls.size)
        assertEquals(0, h.createFolderCalls.size)
    }

    @Test
    fun logoutClearsCredentials() = runBlocking {
        val h = Harness()
        h.repository.register("alice", "password123")
        assertTrue(h.repository.status.loggedIn)

        h.repository.logout()

        assertFalse(h.repository.status.loggedIn)
        assertNull(h.apiClient.accessToken)
        assertEquals("", h.appSettings.panCloudRefreshToken.getValue())
        assertEquals("", h.appSettings.panCloudFolderId.getValue())
    }

    @Test
    fun restoreSessionMarksExpiredOn401() = runBlocking {
        val h = Harness()
        h.appSettings.panCloudRefreshToken.setValue("old-rt")
        h.refreshUnauthorized = true

        val result = h.repository.restoreSession()

        assertTrue(result.isFailure)
        assertEquals(PanCloudSessionState.Expired, h.repository.status.state)
        assertEquals("", h.appSettings.panCloudRefreshToken.getValue())
        assertNull(h.apiClient.accessToken)
    }

    @Test
    fun restoreSessionKeepsCredentialOnNetworkFailure() = runBlocking {
        val h = Harness()
        h.appSettings.panCloudRefreshToken.setValue("old-rt")
        h.refreshNetworkFail = true

        val result = h.repository.restoreSession()

        assertTrue(result.isFailure)
        assertEquals("old-rt", h.appSettings.panCloudRefreshToken.getValue())
    }

    @Test
    fun loginStoresPassword() = runBlocking {
        val h = Harness()
        h.repository.login("alice", "password123")

        assertEquals("password123", h.appSettings.panCloudPassword.getValue())
    }

    @Test
    fun logoutClearsPassword() = runBlocking {
        val h = Harness()
        h.repository.login("alice", "password123")

        h.repository.logout()

        assertEquals("", h.appSettings.panCloudPassword.getValue())
    }

    @Test
    fun restoreSessionReloginOnExpiredRefresh() = runBlocking {
        val h = Harness()
        h.appSettings.panCloudRefreshToken.setValue("old-rt")
        h.appSettings.panCloudUsername.setValue("alice")
        h.appSettings.panCloudPassword.setValue("password123")
        h.refreshUnauthorized = true

        val result = h.repository.restoreSession()

        assertTrue(result.isSuccess)
        assertEquals("rt-login", h.appSettings.panCloudRefreshToken.getValue())
        assertEquals(PanCloudSessionState.Active, h.repository.status.state)
    }

    @Test
    fun restoreSessionMarksExpiredWhenReloginFails() = runBlocking {
        val h = Harness()
        h.appSettings.panCloudRefreshToken.setValue("old-rt")
        h.appSettings.panCloudUsername.setValue("alice")
        h.appSettings.panCloudPassword.setValue("password123")
        h.refreshUnauthorized = true
        h.loginUnauthorized = true

        val result = h.repository.restoreSession()

        assertTrue(result.isFailure)
        assertEquals(PanCloudSessionState.Expired, h.repository.status.state)
        assertEquals("", h.appSettings.panCloudPassword.getValue())
    }

    @Test
    fun restoreSessionKeepsCredentialWhenReloginNetworkFails() = runBlocking {
        val h = Harness()
        h.appSettings.panCloudRefreshToken.setValue("old-rt")
        h.appSettings.panCloudUsername.setValue("alice")
        h.appSettings.panCloudPassword.setValue("password123")
        h.refreshUnauthorized = true
        h.loginNetworkFail = true

        val result = h.repository.restoreSession()

        assertTrue(result.isFailure)
        assertEquals("old-rt", h.appSettings.panCloudRefreshToken.getValue())
        assertEquals("password123", h.appSettings.panCloudPassword.getValue())
    }
}

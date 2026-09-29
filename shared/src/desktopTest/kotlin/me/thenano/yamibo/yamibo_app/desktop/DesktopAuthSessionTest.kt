package me.thenano.yamibo.yamibo_app.desktop

import io.github.littlesurvival.YamiboClient
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import me.thenano.yamibo.yamibo_app.repository.DesktopAuthRepository
import me.thenano.yamibo.yamibo_app.store.*
import me.thenano.yamibo.yamibo_app.store.auth.UserStore
import me.thenano.yamibo.yamibo_app.store.settings.DesktopSettingsStore
import kotlin.test.*

class DesktopAuthSessionTest {
    private val authentication = "EeqY_2132_auth=test-auth; EeqY_2132_saltkey=test-salt"
    private val key = object : DesktopSecretKeyProvider {
        override fun load() = ByteArray(32) { it.toByte() }
        override fun create() = load()
    }

    @Test fun logoutAndBrowserExpiryCannotRestoreOldPersistentAuthentication() = runBlocking {
        val directory = Files.createTempDirectory("yamibo-auth-test-")
        val file = directory.resolve("session.enc")
        val client = YamiboClient()
        try {
            val secrets = DesktopSecretStore(file, key)
            val cookies = DesktopCookieStore(secrets)
            val user = DesktopUserStore(secrets)
            val forums = DesktopForumFavoriteStore(DesktopSettingsStore(directory.resolve("settings.properties")))
            val auth = DesktopAuthRepository(cookies, user, client, forums)
            for (expired in listOf("", "EeqY_2132_saltkey=test-salt", "guest=1")) {
                auth.acceptBrowserCookies(auth.browserGeneration.value, authentication)
                user.save(UserStore.Preview)
                val generation = auth.browserGeneration.value
                assertTrue(auth.isLoggedIn())
                auth.acceptBrowserCookies(generation, expired)
                assertFalse(auth.isLoggedIn())
                assertNull(user.load())
                assertNull(DesktopCookieStore(DesktopSecretStore(file, key)).load())
                auth.acceptBrowserCookies(generation, authentication)
                assertFalse(auth.isLoggedIn(), "An older browser must not restore an expired session")
            }
            auth.acceptBrowserCookies(auth.browserGeneration.value, authentication)
            val generation = auth.browserGeneration.value
            auth.logOut()
            auth.acceptBrowserCookies(generation, authentication)
            assertFalse(auth.isLoggedIn())
            assertNull(DesktopCookieStore(DesktopSecretStore(file, key)).load())
        } finally {
            client.close()
            Files.deleteIfExists(file)
            Files.deleteIfExists(directory.resolve("settings.properties"))
            Files.delete(directory)
        }
    }
}

package me.thenano.yamibo.yamibo_app.desktop

import com.github.javakeyring.Keyring
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*
import org.junit.Assume.assumeTrue
import me.thenano.yamibo.yamibo_app.store.DesktopSecretStore
import me.thenano.yamibo.yamibo_app.store.DesktopSecretKeyProvider
import me.thenano.yamibo.yamibo_app.store.OsDesktopSecretKeyProvider

class DesktopNativeSecretStoreTest {
    @Test fun osCredentialKeyRestoresEncryptedSession() {
        assumeTrue(java.lang.Boolean.getBoolean("yamibo.test.nativeKeyring"))
        val service = "me.thenano.yamibo.test.${UUID.randomUUID()}"
        val account = "integration-test"
        val directory = Files.createTempDirectory("yamibo-keyring-test-")
        val file = directory.resolve("session.enc")
        var created = false
        try {
            val native = OsDesktopSecretKeyProvider(service, account)
            val provider = object : DesktopSecretKeyProvider {
                override fun load() = native.load()
                override fun create() = native.create().also { created = true }
            }
            DesktopSecretStore(file, provider).put("test", "synthetic-session-only")
            assertEquals("synthetic-session-only", DesktopSecretStore(file, OsDesktopSecretKeyProvider(service, account)).get("test"))
            assertFalse(Files.readAllBytes(file).decodeToString().contains("synthetic-session-only"))
            DesktopSecretStore(file, provider).remove("test")
            assertNull(DesktopSecretStore(file, provider).get("test"))
        } finally {
            if (created) Keyring.create().use { it.deletePassword(service, account) }
            Files.deleteIfExists(file)
            Files.delete(directory)
        }
    }
}

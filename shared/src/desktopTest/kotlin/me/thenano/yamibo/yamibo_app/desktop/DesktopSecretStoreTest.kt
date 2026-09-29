package me.thenano.yamibo.yamibo_app.desktop

import java.nio.file.Files
import me.thenano.yamibo.yamibo_app.store.*
import kotlin.test.*

class DesktopSecretStoreTest {
    @Test fun encryptedSessionRestoresAndRejectsTamperingWithoutPlaintextFallback() {
        val root = Files.createTempDirectory("yamibo-secret-test-")
        val file = root.resolve("session.enc")
        val key = ByteArray(32) { it.toByte() }
        val provider = object : DesktopSecretKeyProvider {
            override fun load() = key
            override fun create() = key
        }
        try {
            DesktopSecretStore(file, provider).put("cookie", "私密登入資料-test-cookie")
            assertFalse(Files.readAllBytes(file).decodeToString().contains("test-cookie"))
            assertEquals("私密登入資料-test-cookie", DesktopSecretStore(file, provider).get("cookie"))
            DesktopSecretStore(file, provider).remove("cookie")
            assertNull(DesktopSecretStore(file, provider).get("cookie"))
            val damaged = Files.readAllBytes(file).apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
            Files.write(file, damaged)
            assertFailsWith<IllegalStateException> { DesktopSecretStore(file, provider).get("cookie") }
            assertContentEquals(damaged, Files.readAllBytes(file))
            Files.list(root).use { assertEquals(1L, it.count()) }
        } finally {
            Files.deleteIfExists(file)
            Files.deleteIfExists(root)
        }
    }
}

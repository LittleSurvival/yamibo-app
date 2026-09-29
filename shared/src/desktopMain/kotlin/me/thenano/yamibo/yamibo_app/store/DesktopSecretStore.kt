package me.thenano.yamibo.yamibo_app.store

import com.github.javakeyring.Keyring
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.*
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.Json
import me.thenano.yamibo.yamibo_app.desktop.DesktopDirectories

/** Session data is encrypted on disk; only the AES key lives in the OS credential service. */
class DesktopSecretStore(
    private val file: Path = DesktopDirectories.dataRoot().resolve("session.enc"),
    private val keyProvider: DesktopSecretKeyProvider = OsDesktopSecretKeyProvider(),
) {
    private var encryptionKey: ByteArray? = null
    private var values: Map<String, String>? = null

    @Synchronized fun get(name: String): String? = load()[name]
    @Synchronized fun put(name: String, value: String) = write(load() + (name to value))
    @Synchronized fun remove(name: String) {
        val old = load()
        if (name in old) write(old - name)
    }

    private fun load(): Map<String, String> {
        values?.let { return it }
        if (!Files.exists(file)) return emptyMap<String, String>().also { values = it }
        try {
            val bytes = Files.readAllBytes(file)
            require(bytes.size >= 29 && bytes[0] == 1.toByte())
            val key = keyProvider.load().also { encryptionKey = it }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
            cipher.updateAAD(AAD)
            return Json.decodeFromString<Map<String, String>>(cipher.doFinal(bytes.copyOfRange(13, bytes.size)).decodeToString())
                .also { values = it }
        } catch (_: Exception) {
            // Native errors and damaged ciphertext must not silently become a new signed-out profile.
            throw IllegalStateException("無法解鎖登入資料，請確認系統鑰匙圈已解鎖且可用")
        }
    }

    private fun write(updated: Map<String, String>) {
        val key = encryptionKey ?: try {
            keyProvider.create().also { encryptionKey = it }
        } catch (_: Exception) { throw IllegalStateException("無法使用系統鑰匙圈儲存登入資料") }
        val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(AAD)
        val encrypted = byteArrayOf(1) + nonce + cipher.doFinal(Json.encodeToString(updated).encodeToByteArray())
        val target = file.toAbsolutePath()
        Files.createDirectories(target.parent)
        val temp = Files.createTempFile(target.parent, ".session-", ".tmp")
        try {
            Files.write(temp, encrypted)
            try { Files.move(temp, target, ATOMIC_MOVE, REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temp, target, REPLACE_EXISTING) }
            values = updated
        } finally { Files.deleteIfExists(temp) }
    }

    private companion object { val AAD = "yamibo-desktop-session-v1".encodeToByteArray() }
}

interface DesktopSecretKeyProvider {
    fun load(): ByteArray
    fun create(): ByteArray
}

class OsDesktopSecretKeyProvider(
    private val service: String = "me.thenano.yamibo.desktop",
    private val account: String = "session-encryption-key-v1",
) : DesktopSecretKeyProvider {
    override fun load(): ByteArray = Keyring.create().use {
        Base64.getDecoder().decode(it.getPassword(service, account))
    }
    override fun create(): ByteArray {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().encoded
        Keyring.create().use { it.setPassword(service, account, Base64.getEncoder().encodeToString(key)) }
        return key
    }
}

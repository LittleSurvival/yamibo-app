package me.thenano.yamibo.yamibo_app.notification.bridge

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Only installation proof persists. AES-GCM/AndroidKeyStore; excluded from cloud/device backup. */
internal class NotificationInstallationStore(context: Context) {
    private val file = AtomicFile(context.noBackupFilesDir.resolve("notification-installation"))
    private val keyAlias = "yamibo.notification.installation"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
    }

    @Synchronized
    fun load(): InstallationProof? {
        if (!file.baseFile.exists()) return null
        // A corrupt/lost proof is a conflict requiring recovery; never silently claim a fresh binding.
        val bytes = file.readFully()
        require(bytes.size in 29..4096)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        val json = notificationJson.parseToJsonElement(cipher.doFinal(bytes.copyOfRange(12, bytes.size)).decodeToString()).jsonObject
        return InstallationProof(json.getValue("id").jsonPrimitive.content, json.getValue("secret").jsonPrimitive.content).also {
            require(cursorPattern.matches(it.id) && secretPattern.matches(it.secret))
        }
    }

    @Synchronized
    fun save(proof: InstallationProof) {
        val body = buildJsonObject { put("id", proof.id); put("secret", proof.secret) }.toString().encodeToByteArray()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val output = file.startWrite()
        try {
            output.write(cipher.iv + cipher.doFinal(body))
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }
}

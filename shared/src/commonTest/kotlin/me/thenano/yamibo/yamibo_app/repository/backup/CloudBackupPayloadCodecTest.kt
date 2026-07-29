package me.thenano.yamibo.yamibo_app.repository.backup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CloudBackupPayloadCodecTest {
    private val codec = CloudBackupPayloadCodec()

    @Test
    fun roundTripUsesExistingBackupWireModel() {
        val original = YamiboBackupFile(
            appVersionCode = 5,
            createdAt = 1234L,
            settings = listOf(
                BackupSetting("reader.fontSize", BackupSettingType.Float, "18.5"),
            ),
            notes = listOf(
                BackupDetailNote("thread", 42L, 7L, "note", 100L, 200L),
            ),
        )

        val encoded = codec.encode(original).getOrThrow()
        val decoded = codec.decode(encoded).getOrThrow()

        assertTrue(encoded.startsWith("yamibo-app-sync:gzip-base64:1:"))
        assertEquals(original, decoded)
    }

    @Test
    fun rejectsUnframedInput() {
        val failure = codec.decode("plain text")

        assertTrue(failure.isFailure)
    }
}

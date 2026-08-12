package me.thenano.yamibo.yamibo_app.repository.appsync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import me.thenano.yamibo.yamibo_app.repository.appsync.domain.stableAppSyncFingerprint
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncAccountBinding
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncCausalContext
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncCheckpointEnvelopeCodec
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncCheckpointValidation
import me.thenano.yamibo.yamibo_app.repository.backup.YamiboBackupFile

class AppSyncCheckpointEnvelopeCodecTest {
    private val codec = AppSyncCheckpointEnvelopeCodec()

    @Test
    fun checkpointRoundTripRetainsBackupProjection() {
        val snapshot = YamiboBackupFile(appVersionCode = 4, createdAt = 123)
        val payload = codec.createPayload(
            checkpointId = "checkpoint-1",
            accountBinding = SyncAccountBinding("account"),
            coverage = SyncCausalContext(),
            snapshot = snapshot,
            tombstones = emptyList(),
            createdAtEpochMillis = 124,
        )

        val encoded = codec.encode(payload)
        val result = assertIs<AppSyncCheckpointValidation.Valid>(codec.validate(encoded))

        assertEquals(snapshot, result.envelope.snapshot)
        assertEquals(payload, result.envelope.payload)
        assertTrue(encoded.contains("schema=2"))
        assertTrue(encoded.contains("payload=gzip-base64:"))
    }

    @Test
    fun legacySchemaOneCheckpointRemainsReadable() {
        val payload = codec.createPayload(
            checkpointId = "checkpoint-legacy",
            accountBinding = SyncAccountBinding("account"),
            coverage = SyncCausalContext(),
            snapshot = YamiboBackupFile(appVersionCode = 4, createdAt = 123),
            tombstones = emptyList(),
            createdAtEpochMillis = 124,
        )
        val json = Json {
            encodeDefaults = true
            explicitNulls = true
        }.encodeToString(
            me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncCheckpointPayload.serializer(),
            payload,
        )
        val encoded = """
            [YAMIBO_APP_SYNC_CHECKPOINT:v1:BEGIN]
            schema=1
            fingerprint=${stableAppSyncFingerprint(json)}
            payload=$json
            [YAMIBO_APP_SYNC_CHECKPOINT:v1:END]
        """.trimIndent()

        val result = assertIs<AppSyncCheckpointValidation.Valid>(codec.validate(encoded))

        assertEquals(payload, result.envelope.payload)
    }

    @Test
    fun corruptedCompressedCheckpointFailsClosed() {
        val payload = codec.createPayload(
            checkpointId = "checkpoint-corrupt",
            accountBinding = SyncAccountBinding("account"),
            coverage = SyncCausalContext(),
            snapshot = YamiboBackupFile(appVersionCode = 4, createdAt = 123),
            tombstones = emptyList(),
            createdAtEpochMillis = 124,
        )
        val damaged = codec.encode(payload)
            .replace("payload=gzip-base64:", "payload=gzip-base64:!")

        val result = assertIs<AppSyncCheckpointValidation.Invalid>(codec.validate(damaged))

        assertTrue(result.reason.contains("compressed payload"))
    }

    @Test
    fun checkpointFingerprintMismatchFailsClosed() {
        val payload = codec.createPayload(
            checkpointId = "checkpoint-1",
            accountBinding = SyncAccountBinding("account"),
            coverage = SyncCausalContext(),
            snapshot = YamiboBackupFile(appVersionCode = 4, createdAt = 123),
            tombstones = emptyList(),
            createdAtEpochMillis = 124,
        )
        val damaged = codec.encode(payload).replace("fingerprint=", "fingerprint=0")

        val result = assertIs<AppSyncCheckpointValidation.Invalid>(codec.validate(damaged))

        assertTrue(result.reason.contains("fingerprint"))
    }
}

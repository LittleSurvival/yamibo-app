package me.thenano.yamibo.yamibo_app.repository.appsync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
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

        val result = assertIs<AppSyncCheckpointValidation.Valid>(
            codec.validate(codec.encode(payload)),
        )

        assertEquals(snapshot, result.envelope.snapshot)
        assertEquals(payload, result.envelope.payload)
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

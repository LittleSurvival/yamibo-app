package me.thenano.yamibo.yamibo_app.repository.appsync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncAccountBinding
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncCausalContext
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncDeviceEpoch
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncDeviceId
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncDomainId
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncEntityId
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperation
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperationKind
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperationOrigin
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncSequence
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncWriterNonce
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncJournalEnvelopeCodec
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncJournalPayload
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncJournalValidation

class AppSyncJournalEnvelopeCodecTest {
    private val codec = AppSyncJournalEnvelopeCodec()

    @Test
    fun roundTripPreservesJournal() {
        val payload = payload()

        val validated = assertIs<AppSyncJournalValidation.Valid>(codec.validate(codec.encode(payload)))

        assertEquals(payload, validated.envelope.payload)
        assertTrue(validated.envelope.fingerprint.isNotBlank())
    }

    @Test
    fun wrongFingerprintFailsClosed() {
        val encoded = codec.encode(payload()).replace("fingerprint=", "fingerprint=0")

        val invalid = assertIs<AppSyncJournalValidation.Invalid>(codec.validate(encoded))

        assertTrue(invalid.markerPresent)
        assertTrue(invalid.reason.contains("fingerprint"))
    }

    @Test
    fun nonContiguousSequenceFailsClosed() {
        val first = operation(1)
        val third = operation(3)
        val payload = payload(listOf(first, third), firstSequence = 1, lastSequence = 3)

        val error = runCatching { codec.encode(payload) }.exceptionOrNull()

        assertTrue(error?.message.orEmpty().contains("contiguous"))
    }

    @Test
    fun operationFromDifferentOwnerFailsClosed() {
        val foreign = operation(1, device = SyncDeviceId("other"))
        val payload = payload(listOf(foreign), firstSequence = 1, lastSequence = 1)

        val error = runCatching { codec.encode(payload) }.exceptionOrNull()

        assertTrue(error?.message.orEmpty().contains("identity"))
    }

    @Test
    fun malformedMarkerAndUnsupportedSchemaFailClosed() {
        val encoded = codec.encode(payload())

        val malformed = assertIs<AppSyncJournalValidation.Invalid>(
            codec.validate(encoded.replace(":END]", ":BROKEN]")),
        )
        val unsupported = assertIs<AppSyncJournalValidation.Invalid>(
            codec.validate(encoded.replace("schema=1", "schema=99")),
        )

        assertTrue(malformed.markerPresent)
        assertTrue(unsupported.reason.contains("Unsupported"))
    }

    @Test
    fun operationFromWrongAccountFailsClosed() {
        val foreign = operation(1, account = SyncAccountBinding("other"))

        val error = runCatching {
            codec.encode(payload(listOf(foreign), firstSequence = 1, lastSequence = 1))
        }.exceptionOrNull()

        assertTrue(error?.message.orEmpty().contains("identity"))
    }

    private fun payload(
        operations: List<SyncOperation> = listOf(operation(1)),
        firstSequence: Long = operations.firstOrNull()?.sequence?.value ?: 0,
        lastSequence: Long = operations.lastOrNull()?.sequence?.value ?: 0,
    ) = AppSyncJournalPayload(
        accountBinding = SyncAccountBinding("account"),
        deviceId = SyncDeviceId("device"),
        deviceEpoch = SyncDeviceEpoch("epoch"),
        writerNonce = SyncWriterNonce("nonce"),
        firstSequence = firstSequence,
        lastSequence = lastSequence,
        operations = operations,
        observed = SyncCausalContext(),
        heartbeatAtEpochMillis = 123,
    )

    private fun operation(
        sequence: Long,
        device: SyncDeviceId = SyncDeviceId("device"),
        account: SyncAccountBinding = SyncAccountBinding("account"),
    ): SyncOperation {
        val epoch = SyncDeviceEpoch("epoch")
        val syncSequence = SyncSequence(sequence)
        return SyncOperation(
            operationId = SyncOperation.idFor(device, epoch, syncSequence),
            deviceId = device,
            deviceEpoch = epoch,
            sequence = syncSequence,
            accountBinding = account,
            domainId = SyncDomainId("settings"),
            entityId = SyncEntityId("theme"),
            kind = SyncOperationKind.Patch,
            fields = mapOf("value" to "dark"),
            createdAtEpochMillis = 123,
            origin = SyncOperationOrigin.UserAction,
        )
    }
}

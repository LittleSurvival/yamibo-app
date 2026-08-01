package me.thenano.yamibo.yamibo_app.repository.appsync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import me.thenano.yamibo.yamibo_app.repository.appsync.domain.stableAppSyncFingerprint
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
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.resolvedPublishedThroughSequence

class AppSyncJournalEnvelopeCodecTest {
    private val codec = AppSyncJournalEnvelopeCodec()

    @Test
    fun roundTripPreservesJournal() {
        val payload = payload()
        val encoded = codec.encode(payload)

        val validated = assertIs<AppSyncJournalValidation.Valid>(codec.validate(encoded))

        assertEquals(payload, validated.envelope.payload)
        assertTrue(validated.envelope.fingerprint.isNotBlank())
        assertTrue(encoded.contains("schema=2"))
        assertTrue(encoded.contains("payload=gzip-base64:"))
    }

    @Test
    fun legacySchemaOneJournalRemainsReadable() {
        val payload = payload()
        val json = Json {
            encodeDefaults = true
            explicitNulls = true
        }.encodeToString(AppSyncJournalPayload.serializer(), payload)
        val encoded = """
            [YAMIBO_APP_SYNC_JOURNAL:v1:BEGIN]
            schema=1
            fingerprint=${stableAppSyncFingerprint(json)}
            payload=$json
            [YAMIBO_APP_SYNC_JOURNAL:v1:END]
        """.trimIndent()

        val validated = assertIs<AppSyncJournalValidation.Valid>(codec.validate(encoded))

        assertEquals(payload, validated.envelope.payload)
    }

    @Test
    fun repetitiveOperationJournalCompressesBelowPlainJsonSize() {
        val operations = (1L..100L).map(::operation)
        val payload = payload(operations, firstSequence = 1, lastSequence = 100)
        val plainJson = Json {
            encodeDefaults = true
            explicitNulls = true
        }.encodeToString(AppSyncJournalPayload.serializer(), payload)

        val encoded = codec.encode(payload)

        assertTrue(encoded.length < plainJson.length / 2)
        assertEquals(
            payload,
            assertIs<AppSyncJournalValidation.Valid>(codec.validate(encoded)).envelope.payload,
        )
    }

    @Test
    fun corruptedCompressedPayloadFailsClosed() {
        val encoded = codec.encode(payload())
        val corrupted = encoded.replace("payload=gzip-base64:", "payload=gzip-base64:!")

        val invalid = assertIs<AppSyncJournalValidation.Invalid>(codec.validate(corrupted))

        assertTrue(invalid.markerPresent)
        assertTrue(invalid.reason.contains("compressed payload"))
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
            codec.validate(encoded.replace("schema=2", "schema=99")),
        )

        assertTrue(malformed.markerPresent)
        assertTrue(unsupported.reason.contains("Unsupported"))
    }

    @Test
    fun publishedThroughSequenceCannotFallBelowObservedOrRetainedSequence() {
        val replica = me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncReplicaKey(
            SyncDeviceId("device"),
            SyncDeviceEpoch("epoch"),
        )
        val payload = payload().copy(
            observed = SyncCausalContext().advance(replica, SyncSequence(2)),
            publishedThroughSequence = 1,
        )

        val error = runCatching { codec.encode(payload) }.exceptionOrNull()

        assertTrue(error?.message.orEmpty().contains("published-through"))
    }

    @Test
    fun legacyPublishedThroughIsDerivedOnlyFromMatchingOwnWatermark() {
        val replica = me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncReplicaKey(
            SyncDeviceId("device"),
            SyncDeviceEpoch("epoch"),
        )
        val derivable = payload().copy(
            observed = SyncCausalContext().advance(replica, SyncSequence(1)),
            publishedThroughSequence = null,
        )
        val ambiguous = derivable.copy(
            operations = emptyList(),
            firstSequence = 0,
            lastSequence = 0,
        )

        assertEquals(1, derivable.resolvedPublishedThroughSequence())
        assertNull(ambiguous.resolvedPublishedThroughSequence())
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
        publishedThroughSequence = lastSequence,
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

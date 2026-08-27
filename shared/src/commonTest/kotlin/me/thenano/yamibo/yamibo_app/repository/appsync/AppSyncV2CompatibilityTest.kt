package me.thenano.yamibo.yamibo_app.repository.appsync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.OperationReducer
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
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncCapacityFeatureFlags
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncJournalDefaults
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncJournalEnvelopeCodec
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncJournalPayload
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncJournalValidation
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncProtocolCapabilities
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.forSegmentedSession
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.segmentedSessionFingerprint

class AppSyncV2CompatibilityTest {
    private val codec = AppSyncJournalEnvelopeCodec()

    @Test
    fun mixedReadersRemainV1WritersAndBlockV2UntilEveryActiveReplicaCanReadIt() {
        val legacy = payload("legacy", readVersion = 1)
        val readerFirst = payload("reader", readVersion = 2)

        assertEquals(1, readerFirst.protocolWriteVersion)
        assertFalse(AppSyncProtocolCapabilities.canWriteV2(listOf(legacy, readerFirst)))
        assertTrue(AppSyncProtocolCapabilities.canWriteV2(listOf(readerFirst)))
        assertFalse(AppSyncProtocolCapabilities.canWriteV2(emptyList()))
    }

    @Test
    fun sameOperationFromV1AndV2ReadersDeduplicatesBeforeDeterministicReduction() {
        val operation = operation()
        val v1 = decoded(payload("legacy", 1, operation)).operations
        val v2 = decoded(payload("reader", 2, operation)).operations
        val unique = (v2 + v1).distinctBy { it.operationId }

        val forward = OperationReducer().reduce(operations = v1 + v2)
        val reverse = OperationReducer().reduce(operations = v2 + v1)

        assertEquals(1, unique.size)
        assertEquals(forward.entities, reverse.entities)
        assertEquals("dark", forward.entities.values.single().fields.getValue("value").value)
    }

    @Test
    fun productionNamespaceDoesNotAdoptHistoricalDevelopmentTitles() {
        val production = AppSyncJournalDefaults.journalTitle(
            SyncDeviceId("device"),
            SyncDeviceEpoch("epoch"),
        )
        val historical = "ymb-sync-9f4c2a7-journal-device"

        assertTrue(production.startsWith(AppSyncJournalDefaults.JOURNAL_TITLE_PREFIX))
        assertFalse(historical.startsWith(AppSyncJournalDefaults.JOURNAL_TITLE_PREFIX))
    }

    @Test
    fun rollbackFlagsPauseNewWorkButNeverStrandCommittedV2References() {
        val paused = AppSyncCapacityFeatureFlags(
            v2ReadsEnabled = false,
            v2WritesEnabled = false,
            automaticLegacyRecoveryEnabled = false,
            cleanupDryRun = true,
            cleanupDeletionEnabled = true,
        )

        assertFalse(paused.mayReadV2(committedIndexReference = false))
        assertTrue(paused.mayReadV2(committedIndexReference = true))
        assertFalse(paused.mayDeleteCleanupCandidates())
    }

    @Test
    fun segmentedJournalRetryFreezesHeartbeatAndSessionIdentity() {
        val first = payload("reader", 2).copy(heartbeatAtEpochMillis = 100)
        val retry = first.copy(heartbeatAtEpochMillis = 9_999)

        assertEquals(
            first.segmentedSessionFingerprint(codec),
            retry.segmentedSessionFingerprint(codec),
        )
        assertEquals(
            codec.encode(first.forSegmentedSession(77)),
            codec.encode(retry.forSegmentedSession(77)),
        )
    }

    private fun decoded(payload: AppSyncJournalPayload): AppSyncJournalPayload =
        assertIs<AppSyncJournalValidation.Valid>(codec.validate(codec.encode(payload))).envelope.payload

    private fun payload(
        device: String,
        readVersion: Int,
        operation: SyncOperation = operation(device),
    ) = AppSyncJournalPayload(
        accountBinding = operation.accountBinding,
        deviceId = operation.deviceId,
        deviceEpoch = operation.deviceEpoch,
        writerNonce = SyncWriterNonce("nonce-$device"),
        firstSequence = operation.sequence.value,
        lastSequence = operation.sequence.value,
        operations = listOf(operation),
        observed = SyncCausalContext(),
        heartbeatAtEpochMillis = 1,
        protocolReadVersion = readVersion,
        protocolWriteVersion = AppSyncProtocolCapabilities.READER_FIRST_WRITE_VERSION,
        publishedThroughSequence = operation.sequence.value,
    )

    private fun operation(device: String = "same"): SyncOperation {
        val deviceId = SyncDeviceId(device)
        val epoch = SyncDeviceEpoch("epoch")
        val sequence = SyncSequence(1)
        return SyncOperation(
            operationId = SyncOperation.idFor(deviceId, epoch, sequence),
            deviceId = deviceId,
            deviceEpoch = epoch,
            sequence = sequence,
            accountBinding = SyncAccountBinding("account"),
            domainId = SyncDomainId("settings"),
            entityId = SyncEntityId("theme"),
            kind = SyncOperationKind.Patch,
            fields = mapOf("value" to "dark"),
            createdAtEpochMillis = 1,
            origin = SyncOperationOrigin.UserAction,
        )
    }
}

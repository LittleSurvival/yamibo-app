package me.thenano.yamibo.yamibo_app.repository.appsync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.littlesurvival.dto.value.FormHash
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import me.thenano.yamibo.yamibo_app.Database
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncJournalLoadResult
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncJournalPublishResult
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncJournalRemote
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncJournalRetirementMaintenanceResult
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncJournalRetirementRemoteResult
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.JournalRetirementCoordinator
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.JournalRetirementProofEvaluator
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.LoadedAppSyncCheckpoint
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.LoadedAppSyncJournal
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncInstallationState
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncJournalRetirementIntent
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncJournalRetirementStage
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncAccountBinding
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncCausalContext
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncDeviceEpoch
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncDeviceId
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncReplicaKey
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncSequence
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncWriterNonce
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncCheckpointAcknowledgement
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncCheckpointEnvelopeCodec
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncCheckpointValidation
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncJournalPayload
import me.thenano.yamibo.yamibo_app.store.appsync.SqlDelightAppSyncOperationStore
import me.thenano.yamibo.yamibo_app.repository.backup.YamiboBackupFile

class JournalRetirementCoordinatorTest {
    @Test
    fun reportOnlyRecordsCandidateWithoutRemoteMutation() = runBlocking {
        val fixture = fixture()

        val result = fixture.coordinator.maintain(ACCOUNT, FORM_HASH, true, false)

        assertIs<AppSyncJournalRetirementMaintenanceResult.Candidate>(result)
        assertTrue(fixture.store.retirementIntents(ACCOUNT).isEmpty())
        assertEquals(1, fixture.remote.loadCalls)
        assertEquals(0, fixture.remote.indexCalls)
        assertEquals(0, fixture.remote.deleteCalls)
    }

    @Test
    fun stagedRetirementResumesAcrossMaintenanceRuns() = runBlocking {
        val fixture = fixture()

        assertIs<AppSyncJournalRetirementMaintenanceResult.Pending>(
            fixture.coordinator.maintain(ACCOUNT, FORM_HASH, true, true),
        )
        assertEquals(
            AppSyncJournalRetirementStage.IndexRetirementPublished,
            fixture.store.retirementIntents(ACCOUNT).single().stage,
        )

        val restarted = coordinator(fixture.store, fixture.remote)
        assertIs<AppSyncJournalRetirementMaintenanceResult.Pending>(
            restarted.maintain(ACCOUNT, FORM_HASH, true, true),
        )
        assertEquals(
            AppSyncJournalRetirementStage.DeleteRequested,
            fixture.store.retirementIntents(ACCOUNT).single().stage,
        )
        assertIs<AppSyncJournalRetirementMaintenanceResult.Completed>(
            restarted.maintain(ACCOUNT, FORM_HASH, true, true),
        )
        assertEquals(
            AppSyncJournalRetirementStage.Completed,
            fixture.store.retirementIntents(ACCOUNT).single().stage,
        )
        assertEquals(1, fixture.remote.indexCalls)
        assertEquals(1, fixture.remote.deleteCalls)
    }

    @Test
    fun retryableDeleteKeepsDeleteRequestedIntent() = runBlocking {
        val fixture = fixture()
        fixture.coordinator.maintain(ACCOUNT, FORM_HASH, true, true)
        fixture.coordinator.maintain(ACCOUNT, FORM_HASH, true, true)
        fixture.remote.deleteResult =
            AppSyncJournalRetirementRemoteResult.RetryableFailure("timeout")

        assertIs<AppSyncJournalRetirementMaintenanceResult.RetryableFailure>(
            fixture.coordinator.maintain(ACCOUNT, FORM_HASH, true, true),
        )
        assertEquals(
            AppSyncJournalRetirementStage.DeleteRequested,
            fixture.store.retirementIntents(ACCOUNT).single().stage,
        )
        assertEquals(2, fixture.store.retirementIntents(ACCOUNT).single().attempts)
    }

    @Test
    fun authExpiryKeepsIntentAtCurrentStage() = runBlocking {
        val fixture = fixture()
        fixture.remote.indexResult = AppSyncJournalRetirementRemoteResult.FormExpired

        assertIs<AppSyncJournalRetirementMaintenanceResult.PausedAuth>(
            fixture.coordinator.maintain(ACCOUNT, FORM_HASH, true, true),
        )
        assertEquals(
            AppSyncJournalRetirementStage.IntentRecorded,
            fixture.store.retirementIntents(ACCOUNT).single().stage,
        )
        assertEquals(0, fixture.remote.deleteCalls)
    }

    @Test
    fun permissionFailureBlocksIntentWithoutDelete() = runBlocking {
        val fixture = fixture()
        fixture.remote.indexResult =
            AppSyncJournalRetirementRemoteResult.TerminalFailure("permission denied")

        assertIs<AppSyncJournalRetirementMaintenanceResult.TerminalFailure>(
            fixture.coordinator.maintain(ACCOUNT, FORM_HASH, true, true),
        )
        assertEquals(
            AppSyncJournalRetirementStage.Blocked,
            fixture.store.retirementIntents(ACCOUNT).single().stage,
        )
        assertEquals(0, fixture.remote.deleteCalls)
    }

    @Test
    fun timeoutAfterDeleteRetriesIdempotentlyAndCompletedRunDoesNotDeleteAgain() = runBlocking {
        val fixture = fixture()
        fixture.coordinator.maintain(ACCOUNT, FORM_HASH, true, true)
        fixture.coordinator.maintain(ACCOUNT, FORM_HASH, true, true)
        fixture.remote.deleteResult =
            AppSyncJournalRetirementRemoteResult.RetryableFailure("timeout")
        assertIs<AppSyncJournalRetirementMaintenanceResult.RetryableFailure>(
            fixture.coordinator.maintain(ACCOUNT, FORM_HASH, true, true),
        )

        fixture.remote.deleteResult = AppSyncJournalRetirementRemoteResult.Verified
        assertIs<AppSyncJournalRetirementMaintenanceResult.Completed>(
            fixture.coordinator.maintain(ACCOUNT, FORM_HASH, true, true),
        )
        assertIs<AppSyncJournalRetirementMaintenanceResult.Completed>(
            fixture.coordinator.maintain(ACCOUNT, FORM_HASH, true, true),
        )
        assertEquals(2, fixture.remote.deleteCalls)
    }

    @Test
    fun candidateChangeAfterIntentPreventsDelete() = runBlocking {
        val fixture = fixture()
        fixture.coordinator.maintain(ACCOUNT, FORM_HASH, true, true)
        val candidate = fixture.remote.cloud.journals.first {
            it.remoteId == "42"
        }
        fixture.remote.cloud = fixture.remote.cloud.copy(
            journals = fixture.remote.cloud.journals.map {
                if (it.remoteId == candidate.remoteId) {
                    it.copy(
                        fingerprint = "changed",
                        payload = it.payload.copy(publishedThroughSequence = 6),
                    )
                } else {
                    it
                }
            },
        )

        assertIs<AppSyncJournalRetirementMaintenanceResult.Observed>(
            fixture.coordinator.maintain(ACCOUNT, FORM_HASH, true, true),
        )
        assertEquals(0, fixture.remote.deleteCalls)
        assertEquals(
            AppSyncJournalRetirementStage.IndexRetirementPublished,
            fixture.store.retirementIntents(ACCOUNT).single().stage,
        )
    }

    @Test
    fun maintenanceNotDueDoesNotLoadCloud() = runBlocking {
        val fixture = fixture()
        fixture.store.updateDiscoveryTime(200)

        assertIs<AppSyncJournalRetirementMaintenanceResult.NotDue>(
            fixture.coordinator.maintain(ACCOUNT, FORM_HASH, false, false),
        )
        assertEquals(0, fixture.remote.loadCalls)
    }

    private fun fixture(): Fixture {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        val store = SqlDelightAppSyncOperationStore(Database(driver))
        store.initialize("generation")
        store.bindAccount(ACCOUNT, AppSyncInstallationState.Active)
        val installation = requireNotNull(store.installation())
        val own = SyncReplicaKey(installation.deviceId, installation.deviceEpoch)
        val candidate = SyncReplicaKey(SyncDeviceId("candidate"), SyncDeviceEpoch("old"))
        val coverage = SyncCausalContext()
            .advance(candidate, SyncSequence(5))
            .advance(own, SyncSequence(1))
        val cloud = AppSyncJournalLoadResult.Success(
            journals = listOf(
                journal(candidate, 42, "candidate", coverage, 5),
                journal(
                    own,
                    43,
                    "active",
                    coverage,
                    1,
                    listOf(AppSyncCheckpointAcknowledgement("checkpoint", coverage)),
                ),
            ),
            checkpoints = listOf(checkpoint(coverage)),
        )
        store.recordReplicaObservation(
            ACCOUNT,
            candidate.stableKey,
            42,
            "candidate",
            5,
            0,
            1_000,
        )
        store.recordReplicaObservation(
            ACCOUNT,
            own.stableKey,
            43,
            "active",
            1,
            190,
            1_000,
        )
        val remote = FakeRemote(cloud)
        return Fixture(store, remote, coordinator(store, remote))
    }

    private fun coordinator(
        store: SqlDelightAppSyncOperationStore,
        remote: FakeRemote,
    ) = JournalRetirementCoordinator(
        store = store,
        remote = remote,
        nowMillis = { 200 },
        ownerId = { "owner" },
        evaluator = JournalRetirementProofEvaluator(inactiveAfterMillis = 100),
        maintenanceIntervalMillis = 1_000,
        maximumObservationGapMillis = 1_000,
    )

    private fun journal(
        key: SyncReplicaKey,
        blogId: Long,
        fingerprint: String,
        observed: SyncCausalContext,
        publishedThrough: Long,
        acknowledgements: List<AppSyncCheckpointAcknowledgement> = emptyList(),
    ) = LoadedAppSyncJournal(
        blogId.toString(),
        fingerprint,
        AppSyncJournalPayload(
            accountBinding = ACCOUNT,
            deviceId = key.deviceId,
            deviceEpoch = key.deviceEpoch,
            writerNonce = SyncWriterNonce("writer"),
            firstSequence = 0,
            lastSequence = 0,
            operations = emptyList(),
            observed = observed,
            checkpointAcknowledgements = acknowledgements,
            heartbeatAtEpochMillis = 0,
            publishedThroughSequence = publishedThrough,
        ),
    )

    private fun checkpoint(coverage: SyncCausalContext): LoadedAppSyncCheckpoint {
        val codec = AppSyncCheckpointEnvelopeCodec()
        val payload = codec.createPayload(
            checkpointId = "checkpoint",
            accountBinding = ACCOUNT,
            coverage = coverage,
            snapshot = YamiboBackupFile(appVersionCode = 1, createdAt = 1),
            tombstones = emptyList(),
            createdAtEpochMillis = 1,
        )
        val envelope = assertIs<AppSyncCheckpointValidation.Valid>(
            codec.validate(codec.encode(payload)),
        ).envelope
        return LoadedAppSyncCheckpoint("99", envelope)
    }

    private data class Fixture(
        val store: SqlDelightAppSyncOperationStore,
        val remote: FakeRemote,
        val coordinator: JournalRetirementCoordinator,
    )

    private class FakeRemote(
        var cloud: AppSyncJournalLoadResult.Success,
    ) : AppSyncJournalRemote {
        var loadCalls = 0
        var indexCalls = 0
        var deleteCalls = 0
        var deleteResult: AppSyncJournalRetirementRemoteResult =
            AppSyncJournalRetirementRemoteResult.Verified
        var indexResult: AppSyncJournalRetirementRemoteResult =
            AppSyncJournalRetirementRemoteResult.Verified

        override suspend fun loadJournals(
            accountBinding: SyncAccountBinding,
            forceDiscovery: Boolean,
        ): AppSyncJournalLoadResult {
            loadCalls += 1
            return cloud
        }

        override suspend fun publishOwnJournal(
            payload: AppSyncJournalPayload,
            expectedFingerprint: String?,
            formHash: FormHash,
        ): AppSyncJournalPublishResult =
            AppSyncJournalPublishResult.TerminalFailure("unused")

        override suspend fun publishRetirementIndex(
            intent: AppSyncJournalRetirementIntent,
            formHash: FormHash,
        ): AppSyncJournalRetirementRemoteResult {
            indexCalls += 1
            return indexResult
        }

        override suspend fun deleteRetiredJournal(
            intent: AppSyncJournalRetirementIntent,
            formHash: FormHash,
        ): AppSyncJournalRetirementRemoteResult {
            deleteCalls += 1
            return deleteResult
        }
    }

    private companion object {
        val ACCOUNT = SyncAccountBinding("account")
        val FORM_HASH = FormHash("hash")
    }
}

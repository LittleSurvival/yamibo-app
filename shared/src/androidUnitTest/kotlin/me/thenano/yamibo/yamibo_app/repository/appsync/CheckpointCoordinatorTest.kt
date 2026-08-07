package me.thenano.yamibo.yamibo_app.repository.appsync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.littlesurvival.dto.value.FormHash
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import me.thenano.yamibo.yamibo_app.Database
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncCheckpointPublishResult
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncCheckpointRetentionResult
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncJournalLoadResult
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncJournalPublishResult
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncJournalRemote
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.CheckpointCoordinator
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.CheckpointCreationResult
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.CheckpointProjection
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.LoadedAppSyncCheckpoint
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.OperationReductionResult
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.OperationReducer
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.ResolvedSyncEntity
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.SyncDomainStateAdapter
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.SyncEntityKey
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncInstallationState
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncAccountBinding
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncDomainId
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncEntityId
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperationKind
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperationOrigin
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncCheckpointEnvelopeCodec
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncCheckpointPayload
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncCheckpointValidation
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncJournalPayload
import me.thenano.yamibo.yamibo_app.repository.backup.YamiboBackupFile
import me.thenano.yamibo.yamibo_app.store.appsync.SqlDelightAppSyncOperationStore

class CheckpointCoordinatorTest {
    private val account = SyncAccountBinding("account")
    private val formHash = FormHash("form")

    @Test
    fun mismatchedAuthoritativeReloadIsNotSaved() = runBlocking {
        val fixture = fixture()
        fixture.remote.returnMismatchedPayload = true

        val result = fixture.coordinator.createIfNeeded(account, formHash)

        assertIs<CheckpointCreationResult.RetryableFailure>(result)
        assertTrue(fixture.store.verifiedCheckpoints().isEmpty())
    }

    @Test
    fun retryUsesSameDeterministicCheckpointIdAndSavesOnlyVerifiedReload() = runBlocking {
        val fixture = fixture()
        fixture.remote.returnUnknownOnce = true

        assertIs<CheckpointCreationResult.RetryableFailure>(
            fixture.coordinator.createIfNeeded(account, formHash),
        )
        assertTrue(fixture.store.verifiedCheckpoints().isEmpty())

        val verified = assertIs<CheckpointCreationResult.Verified>(
            fixture.coordinator.createIfNeeded(account, formHash),
        )

        assertEquals(2, fixture.remote.publishedIds.size)
        assertEquals(fixture.remote.publishedIds[0], fixture.remote.publishedIds[1])
        assertEquals(verified.checkpointId, fixture.store.verifiedCheckpoints().single().checkpointId)
        assertEquals(3, fixture.remote.retentionCalls)
    }

    @Test
    fun invalidLocalProjectionReturnsRetryableFailureWithoutPublishing() = runBlocking {
        val fixture = fixture(inconsistentFavoriteUpdateProjection = true)

        val result = fixture.coordinator.createIfNeeded(account, formHash)

        val failure = assertIs<CheckpointCreationResult.RetryableFailure>(result)
        assertTrue(failure.reason.contains("projection validation failed"))
        assertTrue(fixture.remote.publishedIds.isEmpty())
        assertTrue(fixture.store.verifiedCheckpoints().isEmpty())
    }

    private fun fixture(inconsistentFavoriteUpdateProjection: Boolean = false): Fixture {
        val database = Database(
            JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also(Database.Schema::create),
        )
        val store = SqlDelightAppSyncOperationStore(database).also {
            it.initialize("generation")
            it.bindAccount(account, AppSyncInstallationState.Active)
        }
        val domain = FakeDomainState()
        val operation = store.appendLocalOperation(
            accountBinding = account,
            domainId = SyncDomainId("settings"),
            entityId = SyncEntityId("theme"),
            entityGeneration = 1,
            kind = SyncOperationKind.Put,
            fields = mapOf("type" to "string", "value" to "dark"),
            causalContext = store.causalContext(),
            createdAtEpochMillis = 10,
            origin = SyncOperationOrigin.UserAction,
        )
        domain.apply(OperationReducer().reduce(operations = listOf(operation)))
        store.markAcknowledged(setOf(operation.operationId), atEpochMillis = 20)
        if (inconsistentFavoriteUpdateProjection) {
            val inconsistent = store.appendLocalOperation(
                accountBinding = account,
                domainId = SyncDomainId("favorite.update-fid-filter"),
                entityId = SyncEntityId("fid:12"),
                entityGeneration = 1,
                kind = SyncOperationKind.Put,
                fields = mapOf("fid" to "12", "enabled" to "true"),
                causalContext = store.causalContext(),
                createdAtEpochMillis = 11,
                origin = SyncOperationOrigin.UserAction,
            )
            domain.apply(OperationReducer().reduce(domain.currentState(), listOf(inconsistent)))
            store.markAcknowledged(setOf(inconsistent.operationId), atEpochMillis = 21)
        }
        val remote = FakeCheckpointRemote()
        return Fixture(
            store = store,
            remote = remote,
            coordinator = CheckpointCoordinator(
                store = store,
                remote = remote,
                captureProjection = {
                    CheckpointProjection(
                        coverage = store.causalContext(),
                        entities = domain.currentState().values,
                        snapshot = YamiboBackupFile(appVersionCode = 1, createdAt = 20),
                        pendingOperationCount = store.pendingOperations().size,
                        acknowledgedOperationCount = store.allOutboxOperations().count {
                            it.second == me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncOperationLifecycle.Acknowledged
                        },
                    )
                },
                nowMillis = { 30 },
                minimumAcknowledgedOperations = 1,
            ),
        )
    }

    private data class Fixture(
        val store: SqlDelightAppSyncOperationStore,
        val remote: FakeCheckpointRemote,
        val coordinator: CheckpointCoordinator,
    )

    private class FakeDomainState : SyncDomainStateAdapter {
        private var entities = emptyMap<SyncEntityKey, ResolvedSyncEntity>()

        override fun currentState(): Map<SyncEntityKey, ResolvedSyncEntity> = entities

        override fun apply(result: OperationReductionResult) {
            entities = result.entities
        }
    }

    private class FakeCheckpointRemote : AppSyncJournalRemote {
        var returnMismatchedPayload = false
        var returnUnknownOnce = false
        val publishedIds = mutableListOf<String>()
        var retentionCalls = 0
        private val codec = AppSyncCheckpointEnvelopeCodec()

        override suspend fun loadJournals(
            accountBinding: SyncAccountBinding,
            forceDiscovery: Boolean,
        ): AppSyncJournalLoadResult = AppSyncJournalLoadResult.Success(emptyList())

        override suspend fun publishOwnJournal(
            payload: AppSyncJournalPayload,
            expectedFingerprint: String?,
            formHash: FormHash,
        ): AppSyncJournalPublishResult =
            AppSyncJournalPublishResult.TerminalFailure("Not used")

        override suspend fun publishCheckpoint(
            payload: AppSyncCheckpointPayload,
            formHash: FormHash,
        ): AppSyncCheckpointPublishResult {
            publishedIds += payload.checkpointId
            if (returnUnknownOnce) {
                returnUnknownOnce = false
                return AppSyncCheckpointPublishResult.Unknown("timeout")
            }
            val reloadedPayload = if (returnMismatchedPayload) {
                payload.copy(checkpointId = "wrong-checkpoint")
            } else {
                payload
            }
            val validation = codec.validate(codec.encode(reloadedPayload))
                as AppSyncCheckpointValidation.Valid
            return AppSyncCheckpointPublishResult.Verified(
                LoadedAppSyncCheckpoint("77", validation.envelope),
            )
        }

        override suspend fun enforceCheckpointRetention(
            accountBinding: SyncAccountBinding,
            formHash: FormHash,
            maximumCheckpoints: Int,
            pinnedCheckpointIds: Set<String>,
        ): AppSyncCheckpointRetentionResult {
            retentionCalls += 1
            return AppSyncCheckpointRetentionResult.Verified(
                retainedCheckpointIds = publishedIds.toSet(),
                deletedBlogCount = 0,
            )
        }
    }
}

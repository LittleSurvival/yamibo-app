package me.thenano.yamibo.yamibo_app.repository.appsync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import me.thenano.yamibo.yamibo_app.Database
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncJournalLoadResult
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncJournalPublishResult
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncJournalRemote
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.LoadedAppSyncJournal
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.ManualSyncApplyResult
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.ManualSyncOverrideCoordinator
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.ManualSyncOverrideDirection
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.ManualSyncPreviewResult
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.OperationReductionResult
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.OperationReducer
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.ResolvedSyncEntity
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.SyncDomainStateAdapter
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.SyncEntityKey
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncInstallationState
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
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncJournalPayload
import me.thenano.yamibo.yamibo_app.store.appsync.SqlDelightAppSyncOperationStore

class ManualSyncOverrideCoordinatorTest {
    private val account = SyncAccountBinding("account")

    @Test
    fun changedCloudAfterPreviewIsRejectedWithoutLocalMutation() = runBlocking {
        val store = activeStore()
        val localOperation = settingOperation("local", "local-device")
        val domain = FakeDomainState(
            OperationReducer().reduce(operations = listOf(localOperation)).entities,
        )
        val remote = FakeRemote(settingOperation("cloud-a", "remote-device"))
        val coordinator = ManualSyncOverrideCoordinator(
            store = store,
            remote = remote,
            domainState = domain,
            nowMillis = { 1_000L },
        )
        val preview = assertIs<ManualSyncPreviewResult.Ready>(
            coordinator.preview(account, ManualSyncOverrideDirection.ForcePull),
        ).preview

        remote.operation = settingOperation("cloud-b", "remote-device-2")
        val result = coordinator.apply(account, preview)

        assertIs<ManualSyncApplyResult.StalePreview>(result)
        assertEquals("local", domain.value())
        assertTrue(store.pendingOperations().isEmpty())
    }

    private fun activeStore(): SqlDelightAppSyncOperationStore {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        return SqlDelightAppSyncOperationStore(Database(driver)).also {
            it.initialize("generation")
            it.bindAccount(account, AppSyncInstallationState.Active)
        }
    }

    private fun settingOperation(value: String, deviceValue: String): SyncOperation {
        val device = SyncDeviceId(deviceValue)
        val epoch = SyncDeviceEpoch("epoch")
        val sequence = SyncSequence(1)
        return SyncOperation(
            operationId = SyncOperation.idFor(device, epoch, sequence),
            deviceId = device,
            deviceEpoch = epoch,
            sequence = sequence,
            accountBinding = account,
            domainId = SyncDomainId("settings"),
            entityId = SyncEntityId("theme"),
            kind = SyncOperationKind.Put,
            fields = mapOf("type" to "string", "value" to value),
            createdAtEpochMillis = 10,
            origin = SyncOperationOrigin.UserAction,
        )
    }

    private class FakeDomainState(
        private var state: Map<SyncEntityKey, ResolvedSyncEntity>,
    ) : SyncDomainStateAdapter {
        override fun currentState(): Map<SyncEntityKey, ResolvedSyncEntity> = state
        override fun apply(result: OperationReductionResult) {
            state = result.entities
        }
        override fun adoptCheckpoint(entities: Collection<ResolvedSyncEntity>) {
            state = entities.associateBy { it.key }
        }
        fun value(): String? = state.values.single().fields["value"]?.value
    }

    private class FakeRemote(
        var operation: SyncOperation,
    ) : AppSyncJournalRemote {
        override suspend fun loadJournals(
            accountBinding: SyncAccountBinding,
            forceDiscovery: Boolean,
        ): AppSyncJournalLoadResult = AppSyncJournalLoadResult.Success(
            journals = listOf(
                LoadedAppSyncJournal(
                    remoteId = "1",
                    fingerprint = operation.operationId.value,
                    payload = AppSyncJournalPayload(
                        accountBinding = accountBinding,
                        deviceId = operation.deviceId,
                        deviceEpoch = operation.deviceEpoch,
                        writerNonce = SyncWriterNonce("writer"),
                        firstSequence = operation.sequence.value,
                        lastSequence = operation.sequence.value,
                        operations = listOf(operation),
                        observed = SyncCausalContext(),
                        heartbeatAtEpochMillis = 10,
                    ),
                ),
            ),
        )

        override suspend fun publishOwnJournal(
            payload: AppSyncJournalPayload,
            expectedFingerprint: String?,
            formHash: io.github.littlesurvival.dto.value.FormHash,
        ): AppSyncJournalPublishResult = error("not used")
    }
}

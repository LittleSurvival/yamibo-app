package me.thenano.yamibo.yamibo_app.repository.appsync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import me.thenano.yamibo.yamibo_app.Database
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.OperationReducer
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.DatabaseSyncDomainMaterializer
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.SqlDelightSyncDomainStateAdapter
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncInstallationState
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncOperationLifecycle
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncVerifiedCheckpoint
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.CompactionCoordinator
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.LoadedAppSyncJournal
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncCheckpointAcknowledgement
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncJournalPayload
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncWriterNonce
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
import me.thenano.yamibo.yamibo_app.store.appsync.SqlDelightAppSyncOperationStore
import me.thenano.yamibo.yamibo_app.store.appsync.LocalSyncOperationDraft
import me.thenano.yamibo.yamibo_app.store.settings.SettingsStore

class SqlDelightAppSyncOperationStoreTest {
    @Test
    fun localMutationAndOutboxCommitTogether() {
        val db = inMemoryDatabase()
        val store = activeStore(db)

        val operation = store.appendLocalOperation(
            accountBinding = SyncAccountBinding("account"),
            domainId = SyncDomainId("detail-note"),
            entityId = SyncEntityId("thread:1:author:2"),
            entityGeneration = 1,
            kind = SyncOperationKind.Put,
            fields = mapOf("content" to "note"),
            causalContext = SyncCausalContext(),
            createdAtEpochMillis = 100,
            origin = SyncOperationOrigin.UserAction,
        ) {
            db.detailNoteQueries.upsert("thread", 1, 2, "note", 100, 100)
        }

        assertEquals(operation, store.pendingOperations().single())
        assertEquals("note", db.detailNoteQueries.getByTarget("thread", 1, 2).executeAsOne().content)
        assertEquals(2L, store.installation()?.nextSequence)
    }

    @Test
    fun exceptionRollsBackDomainMutationSequenceAndOutbox() {
        val db = inMemoryDatabase()
        val store = activeStore(db)

        assertFailsWith<IllegalStateException> {
            store.appendLocalOperation(
                accountBinding = SyncAccountBinding("account"),
                domainId = SyncDomainId("detail-note"),
                entityId = SyncEntityId("thread:1:author:2"),
                entityGeneration = 1,
                kind = SyncOperationKind.Put,
                fields = mapOf("content" to "note"),
                causalContext = SyncCausalContext(),
                createdAtEpochMillis = 100,
                origin = SyncOperationOrigin.UserAction,
            ) {
                db.detailNoteQueries.upsert("thread", 1, 2, "note", 100, 100)
                error("injected")
            }
        }

        assertTrue(store.pendingOperations().isEmpty())
        assertNull(db.detailNoteQueries.getByTarget("thread", 1, 2).executeAsOneOrNull())
        assertEquals(1L, store.installation()?.nextSequence)
    }

    @Test
    fun operationBatchAllocatesContiguousSequencesAndRollsBackAsOneCommand() {
        val db = inMemoryDatabase()
        val store = activeStore(db)
        val drafts = listOf(
            LocalSyncOperationDraft(
                SyncDomainId("settings"),
                SyncEntityId("one"),
                kind = SyncOperationKind.Put,
                fields = mapOf("type" to "string", "value" to "1"),
            ),
            LocalSyncOperationDraft(
                SyncDomainId("settings"),
                SyncEntityId("two"),
                kind = SyncOperationKind.Put,
                fields = mapOf("type" to "string", "value" to "2"),
            ),
        )

        assertFailsWith<IllegalStateException> {
            store.appendLocalOperations(
                accountBinding = SyncAccountBinding("account"),
                drafts = drafts,
                causalContext = SyncCausalContext(),
                createdAtEpochMillis = 100,
                origin = SyncOperationOrigin.UserAction,
            ) {
                db.detailNoteQueries.upsert("thread", 1, 2, "note", 100, 100)
                error("injected")
            }
        }
        assertTrue(store.pendingOperations().isEmpty())
        assertEquals(1L, store.installation()?.nextSequence)
        assertNull(db.detailNoteQueries.getByTarget("thread", 1, 2).executeAsOneOrNull())

        val operations = store.appendLocalOperations(
            accountBinding = SyncAccountBinding("account"),
            drafts = drafts,
            causalContext = SyncCausalContext(),
            createdAtEpochMillis = 100,
            origin = SyncOperationOrigin.UserAction,
        )
        assertEquals(listOf(1L, 2L), operations.map { it.sequence.value })
        assertEquals(3L, store.installation()?.nextSequence)
    }

    @Test
    fun acknowledgementRequiresExplicitVerifiedTransition() {
        val store = activeStore(inMemoryDatabase())
        val operation = appendSetting(store)

        store.markPublishedUnverified(setOf(operation.operationId))
        assertEquals(
            AppSyncOperationLifecycle.PublishedUnverified,
            store.allOutboxOperations().single().second,
        )

        store.markAcknowledged(setOf(operation.operationId), 200)
        assertEquals(
            AppSyncOperationLifecycle.Acknowledged,
            store.allOutboxOperations().single().second,
        )
    }

    @Test
    fun remoteApplyAndWatermarkRollbackTogether() {
        val db = inMemoryDatabase()
        val store = activeStore(db)
        val remote = remoteOperation()
        val reduction = OperationReducer().reduce(operations = listOf(remote))

        assertFailsWith<IllegalStateException> {
            store.applyRemoteReduction(reduction, 200) {
                db.detailNoteQueries.upsert("thread", 1, 2, "remote", 100, 200)
                error("injected")
            }
        }

        assertFalse(store.isApplied(remote.operationId))
        assertEquals(emptyMap(), store.causalContext().asStableMap())
        assertNull(db.detailNoteQueries.getByTarget("thread", 1, 2).executeAsOneOrNull())
    }

    @Test
    fun duplicateRemoteApplyIsIdempotentlyRecorded() {
        val store = activeStore(inMemoryDatabase())
        val remote = remoteOperation()
        val reduction = OperationReducer().reduce(operations = listOf(remote, remote))

        store.applyRemoteReduction(reduction, 200) {}
        store.applyRemoteReduction(reduction, 300) {}

        assertTrue(store.isApplied(remote.operationId))
        assertEquals(
            remote.sequence.value,
            store.causalContext()[remote.replicaKey],
        )
    }

    @Test
    fun expiredLeaseCanBeRecoveredButLiveLeaseCannotBeStolen() {
        val store = activeStore(inMemoryDatabase())

        assertTrue(store.acquireLease("foreground", nowEpochMillis = 100, durationMillis = 100))
        assertFalse(store.acquireLease("worker", nowEpochMillis = 150, durationMillis = 100))
        assertTrue(store.acquireLease("worker", nowEpochMillis = 200, durationMillis = 100))
        assertEquals("worker", store.currentLease()?.ownerId)
        store.releaseLease("foreground")
        assertEquals("worker", store.currentLease()?.ownerId)
        store.releaseLease("worker")
        assertNull(store.currentLease())
    }

    @Test
    fun databaseGenerationMismatchRequiresRebootstrap() {
        val store = SqlDelightAppSyncOperationStore(inMemoryDatabase())
        store.initialize("generation-a")
        val originalEpoch = store.installation()?.deviceEpoch

        val changed = store.initialize("generation-b")

        assertEquals(AppSyncInstallationState.RebootstrapRequired, changed.state)
        assertEquals(originalEpoch, changed.deviceEpoch)
    }

    @Test
    fun rotatingEpochDoesNotReuseWriterIdentity() {
        val store = activeStore(inMemoryDatabase())
        val before = requireNotNull(store.installation())
        val pending = appendSetting(store)

        store.rotateDeviceEpoch(SyncAccountBinding("account"), AppSyncInstallationState.Bootstrapping)
        val after = requireNotNull(store.installation())

        assertNotEquals(before.deviceId, after.deviceId)
        assertNotEquals(before.deviceEpoch, after.deviceEpoch)
        assertNotEquals(before.writerNonce, after.writerNonce)
        assertEquals(1L, after.nextSequence)
        assertTrue(store.pendingOperations().isEmpty())
        assertEquals(
            AppSyncOperationLifecycle.DiscardedByRebootstrap,
            store.allOutboxOperations().single { it.first.operationId == pending.operationId }.second,
        )
    }

    @Test
    fun compactionRequiresExactAcknowledgementFromEveryActiveJournal() {
        val store = activeStore(inMemoryDatabase())
        val operation = appendSetting(store)
        store.markAcknowledged(setOf(operation.operationId), 200)
        val coverage = SyncCausalContext().advance(operation.replicaKey, operation.sequence)
        store.saveVerifiedCheckpoint(
            AppSyncVerifiedCheckpoint(
                checkpointId = "checkpoint",
                blogId = 42,
                coverage = coverage,
                payloadFingerprint = "fingerprint",
                createdAtEpochMillis = 100,
                verifiedAtEpochMillis = 200,
            ),
        )
        val coordinator = CompactionCoordinator(store, nowMillis = { 300 })
        val withoutAck = journalFor(operation, coverage, acknowledgements = emptyList())

        assertNull(coordinator.compactIfSafe(listOf(withoutAck)))
        assertEquals(
            AppSyncOperationLifecycle.Acknowledged,
            store.allOutboxOperations().single().second,
        )

        val exactAck = AppSyncCheckpointAcknowledgement("checkpoint", coverage)
        assertEquals(
            coverage,
            coordinator.compactIfSafe(
                listOf(journalFor(operation, coverage, listOf(exactAck))),
            ),
        )
        assertEquals(
            AppSyncOperationLifecycle.Compacted,
            store.allOutboxOperations().single().second,
        )
    }

    @Test
    fun inactiveJournalDoesNotBlockCompactionAfterNinetyDays() {
        val store = activeStore(inMemoryDatabase())
        val operation = appendSetting(store)
        store.markAcknowledged(setOf(operation.operationId), 200)
        val coverage = SyncCausalContext().advance(operation.replicaKey, operation.sequence)
        store.saveVerifiedCheckpoint(
            AppSyncVerifiedCheckpoint(
                checkpointId = "checkpoint",
                blogId = 42,
                coverage = coverage,
                payloadFingerprint = "fingerprint",
                createdAtEpochMillis = 100,
                verifiedAtEpochMillis = 200,
            ),
        )
        val now = 90L * 24 * 60 * 60 * 1_000 + 1_000
        val coordinator = CompactionCoordinator(store, nowMillis = { now })
        val acknowledgement = AppSyncCheckpointAcknowledgement("checkpoint", coverage)
        val active = journalFor(
            operation,
            coverage,
            listOf(acknowledgement),
            heartbeatAtEpochMillis = now,
        )
        val inactive = journalFor(
            remoteOperation(),
            SyncCausalContext(),
            emptyList(),
            heartbeatAtEpochMillis = 1,
        )

        assertEquals(coverage, coordinator.compactIfSafe(listOf(active, inactive)))
        assertEquals(
            AppSyncOperationLifecycle.Compacted,
            store.allOutboxOperations().single().second,
        )
    }

    @Test
    fun cloudResetPreparationPreservesLocalOperationsButForcesPullOnlyBootstrap() {
        val store = activeStore(inMemoryDatabase())
        val operation = appendSetting(store)
        store.markAcknowledged(setOf(operation.operationId), 200)
        store.updateVerifiedHeartbeat(300, journalBlogId = 42)
        store.setAutomaticEnabled(true)

        store.prepareForCloudReset()

        val installation = requireNotNull(store.installation())
        assertNull(installation.accountBinding)
        assertEquals(AppSyncInstallationState.Unbound, installation.state)
        assertNull(installation.lastVerifiedHeartbeatAt)
        assertNull(installation.journalBlogId)
        assertTrue(installation.automaticEnabled)
        assertEquals(
            AppSyncOperationLifecycle.Acknowledged,
            store.allOutboxOperations().single().second,
        )
    }

    @Test
    fun forcePullReplacementDiscardsUnpublishedOperationsAndAdoptsCloudCoverage() {
        val db = inMemoryDatabase()
        val store = activeStore(db)
        val local = appendSetting(store)
        val remote = remoteOperation()
        val reduction = OperationReducer().reduce(operations = listOf(remote))
        val coverage = SyncCausalContext().advance(remote.replicaKey, remote.sequence)

        store.replaceWithVerifiedCloudState(
            result = reduction,
            coverage = coverage,
            cloudOperationIds = setOf(remote.operationId),
            appliedAtEpochMillis = 300,
            domainMutation = {},
        )

        assertTrue(store.pendingOperations().isEmpty())
        assertEquals(
            AppSyncOperationLifecycle.DiscardedByForcePull,
            store.allOutboxOperations().single { it.first.operationId == local.operationId }.second,
        )
        assertTrue(store.isApplied(remote.operationId))
        assertEquals(coverage.asStableMap(), store.causalContext().asStableMap())
    }

    @Test
    fun forcePullReplacementRollsBackDiscardAndCausalMetadataOnDomainFailure() {
        val store = activeStore(inMemoryDatabase())
        val local = appendSetting(store)
        val remote = remoteOperation()
        val reduction = OperationReducer().reduce(operations = listOf(remote))

        assertFailsWith<IllegalStateException> {
            store.replaceWithVerifiedCloudState(
                result = reduction,
                coverage = SyncCausalContext().advance(remote.replicaKey, remote.sequence),
                cloudOperationIds = setOf(remote.operationId),
                appliedAtEpochMillis = 300,
                domainMutation = { error("injected replacement failure") },
            )
        }

        assertEquals(listOf(local), store.pendingOperations())
        assertEquals(
            AppSyncOperationLifecycle.PendingLocal,
            store.allOutboxOperations().single().second,
        )
        assertFalse(store.isApplied(remote.operationId))
        assertTrue(store.causalContext().asStableMap().isEmpty())
    }

    @Test
    fun forcePullMaterializationFailureDoesNotClearExternalSettingsProjection() {
        val db = inMemoryDatabase()
        val store = activeStore(db)
        val settings = FakeSettingsStore().also { it.putString("theme", "dark") }
        db.appSyncOperationQueries.recordKnownSyncSettingKey("theme")
        db.appSyncOperationQueries.upsertSyncSettingValue(
            settingKey = "theme",
            type = "string",
            value_ = "dark",
            winnerOperationId = "existing",
            updatedAtEpochMillis = 1,
        )
        val adapter = SqlDelightSyncDomainStateAdapter(
            db = db,
            materializer = DatabaseSyncDomainMaterializer(db, settings),
            nowMillis = { 300 },
        )
        val invalid = remoteOperation()
        val reduction = OperationReducer().reduce(operations = listOf(invalid))

        assertFailsWith<IllegalArgumentException> {
            store.replaceWithVerifiedCloudState(
                result = reduction,
                coverage = SyncCausalContext().advance(invalid.replicaKey, invalid.sequence),
                cloudOperationIds = setOf(invalid.operationId),
                appliedAtEpochMillis = 300,
                domainMutation = {
                    adapter.adoptCheckpointWithinTransaction(it.entities.values)
                },
            )
        }

        assertEquals("dark", settings.getString("theme", "missing"))
        assertEquals(
            "dark",
            db.appSyncOperationQueries.getSyncSettingValue("theme")
                .executeAsOne()
                .settingValue,
        )
    }

    private fun activeStore(db: Database): SqlDelightAppSyncOperationStore =
        SqlDelightAppSyncOperationStore(db).also {
            it.initialize("generation")
            it.bindAccount(SyncAccountBinding("account"), AppSyncInstallationState.Active)
        }

    private fun appendSetting(store: SqlDelightAppSyncOperationStore): SyncOperation =
        store.appendLocalOperation(
            accountBinding = SyncAccountBinding("account"),
            domainId = SyncDomainId("settings"),
            entityId = SyncEntityId("theme"),
            entityGeneration = 1,
            kind = SyncOperationKind.Patch,
            fields = mapOf("value" to "dark"),
            causalContext = SyncCausalContext(),
            createdAtEpochMillis = 100,
            origin = SyncOperationOrigin.UserAction,
        )

    private fun remoteOperation(): SyncOperation {
        val device = SyncDeviceId("remote")
        val epoch = SyncDeviceEpoch("epoch")
        val sequence = SyncSequence(1)
        return SyncOperation(
            operationId = SyncOperation.idFor(device, epoch, sequence),
            deviceId = device,
            deviceEpoch = epoch,
            sequence = sequence,
            accountBinding = SyncAccountBinding("account"),
            domainId = SyncDomainId("settings"),
            entityId = SyncEntityId("theme"),
            kind = SyncOperationKind.Patch,
            fields = mapOf("value" to "light"),
            createdAtEpochMillis = 100,
            origin = SyncOperationOrigin.UserAction,
        )
    }

    private fun journalFor(
        operation: SyncOperation,
        observed: SyncCausalContext,
        acknowledgements: List<AppSyncCheckpointAcknowledgement>,
        heartbeatAtEpochMillis: Long = 250,
    ) = LoadedAppSyncJournal(
        remoteId = "1",
        fingerprint = "journal",
        payload = AppSyncJournalPayload(
            accountBinding = operation.accountBinding,
            deviceId = operation.deviceId,
            deviceEpoch = operation.deviceEpoch,
            writerNonce = SyncWriterNonce("writer"),
            firstSequence = operation.sequence.value,
            lastSequence = operation.sequence.value,
            operations = listOf(operation),
            observed = observed,
            checkpointAcknowledgements = acknowledgements,
            heartbeatAtEpochMillis = heartbeatAtEpochMillis,
        ),
    )

    private fun inMemoryDatabase(): Database {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        return Database(driver)
    }

    private class FakeSettingsStore : SettingsStore {
        private val values = mutableMapOf<String, Any>()
        override fun getInt(key: String, defaultValue: Int): Int = values[key] as? Int ?: defaultValue
        override fun putInt(key: String, value: Int) { values[key] = value }
        override fun getFloat(key: String, defaultValue: Float): Float =
            values[key] as? Float ?: defaultValue
        override fun putFloat(key: String, value: Float) { values[key] = value }
        override fun getString(key: String, defaultValue: String): String =
            values[key] as? String ?: defaultValue
        override fun putString(key: String, value: String) { values[key] = value }
        override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
            values[key] as? Boolean ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { values[key] = value }
        override fun remove(key: String) { values.remove(key) }
        override fun hasKey(key: String): Boolean = key in values
    }
}

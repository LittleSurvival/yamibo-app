package me.thenano.yamibo.yamibo_app.repository.appsync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.littlesurvival.dto.value.BlogClassId
import io.github.littlesurvival.dto.value.BlogId
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
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.ResolvedSyncEntity
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.SqlDelightSyncDomainStateAdapter
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.SyncDomainMaterializer
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncInstallationState
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncBootstrapRollbackSnapshot
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncOperationLifecycle
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncVerifiedCheckpoint
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncJournalRetirementIntent
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncJournalRetirementStage
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
import me.thenano.yamibo.yamibo_app.store.appsync.AppSyncRemoteBlogKind
import me.thenano.yamibo.yamibo_app.store.appsync.SqlDelightAppSyncRemoteBlogStore
import me.thenano.yamibo.yamibo_app.store.appsync.StoredAppSyncRemoteBlog
import me.thenano.yamibo.yamibo_app.store.appsync.LocalSyncOperationDraft
import me.thenano.yamibo.yamibo_app.store.settings.SettingsStore
import me.thenano.yamibo.yamibo_app.util.time.FixedScheduleInterval

class SqlDelightAppSyncOperationStoreTest {
    @Test
    fun bootstrapRollbackSnapshotSurvivesStoreRestartWithProvenance() {
        val db = inMemoryDatabase()
        val snapshot = AppSyncBootstrapRollbackSnapshot(
            accountBinding = SyncAccountBinding("account"),
            databaseGeneration = "generation",
            encodedSnapshot = "yamibo-app-sync:gzip-base64:1:payload",
            createdAtEpochMillis = 123,
        )
        SqlDelightAppSyncOperationStore(db).also {
            it.initialize("generation")
            it.saveBootstrapRollbackSnapshot(snapshot)
        }

        assertEquals(
            snapshot,
            SqlDelightAppSyncOperationStore(db).latestBootstrapRollbackSnapshot(),
        )
    }

    @Test
    fun replicaObservationAndRetirementIntentSurviveStoreRestart() {
        val db = inMemoryDatabase()
        val binding = SyncAccountBinding("account")
        val firstStore = activeStore(db)
        val first = firstStore.recordReplicaObservation(
            accountBinding = binding,
            replicaKey = "device:epoch",
            sourceBlogId = 42,
            fingerprint = "fingerprint",
            publishedThroughSequence = 8,
            observedAtEpochMillis = 100,
            maximumObservationGapMillis = 1_000,
        )
        val unchanged = firstStore.recordReplicaObservation(
            accountBinding = binding,
            replicaKey = "device:epoch",
            sourceBlogId = 42,
            fingerprint = "fingerprint",
            publishedThroughSequence = 8,
            observedAtEpochMillis = 200,
            maximumObservationGapMillis = 1_000,
        )
        assertEquals(first.firstObservedUnchangedAtEpochMillis, unchanged.firstObservedUnchangedAtEpochMillis)

        firstStore.saveRetirementIntent(
            AppSyncJournalRetirementIntent(
                accountBinding = binding,
                replicaKey = "device:epoch",
                sourceBlogId = 42,
                fingerprint = "fingerprint",
                publishedThroughSequence = 8,
                checkpointId = "checkpoint",
                checkpointFingerprint = "checkpoint-fingerprint",
                checkpointVectorHash = "vector",
                activeSetHash = "active",
                stage = AppSyncJournalRetirementStage.IntentRecorded,
                attempts = 0,
                lastResultCode = null,
                createdAtEpochMillis = 200,
                updatedAtEpochMillis = 200,
                completedAtEpochMillis = null,
            ),
        )

        val restarted = SqlDelightAppSyncOperationStore(db)
        assertEquals(unchanged, restarted.replicaObservations(binding).single())
        assertEquals(
            AppSyncJournalRetirementStage.IntentRecorded,
            restarted.retirementIntents(binding).single().stage,
        )
        assertEquals(setOf("checkpoint"), restarted.pinnedRetirementCheckpointIds())
        assertTrue(
            restarted.transitionRetirementIntent(
                binding,
                "device:epoch",
                AppSyncJournalRetirementStage.IntentRecorded,
                AppSyncJournalRetirementStage.IndexRetirementPublished,
                "INDEX_VERIFIED",
                300,
            ),
        )
        assertFalse(
            restarted.transitionRetirementIntent(
                binding,
                "device:epoch",
                AppSyncJournalRetirementStage.IntentRecorded,
                AppSyncJournalRetirementStage.Completed,
                "STALE",
                400,
            ),
        )
    }

    @Test
    fun replicaObservationResetsOnIdentityChangeOrClockAnomaly() {
        val store = activeStore(inMemoryDatabase())
        val binding = SyncAccountBinding("account")
        fun observe(fingerprint: String, at: Long) = store.recordReplicaObservation(
            binding,
            "device:epoch",
            42,
            fingerprint,
            8,
            at,
            maximumObservationGapMillis = 1_000,
        )

        observe("one", 100)
        assertEquals(200, observe("one", 200).lastObservedAtEpochMillis)
        assertEquals(300, observe("two", 300).firstObservedUnchangedAtEpochMillis)
        assertEquals(250, observe("two", 250).firstObservedUnchangedAtEpochMillis)
        assertEquals(2_000, observe("two", 2_000).firstObservedUnchangedAtEpochMillis)
    }

    @Test
    fun remoteBlogCacheClearPreservesAccountBoundClassId() {
        val db = inMemoryDatabase()
        val store = SqlDelightAppSyncRemoteBlogStore(db)
        val binding = SyncAccountBinding("account")
        val classId = BlogClassId(4568)
        store.saveClassId(binding, classId)
        store.save(
            StoredAppSyncRemoteBlog(
                remoteKey = "index",
                kind = AppSyncRemoteBlogKind.Index,
                blogId = BlogId(12),
                classId = classId,
                fingerprint = "fingerprint",
                validatedAtEpochMillis = 100,
                contentUpdatedAtEpochMillis = 100,
            ),
        )

        store.clear()

        assertNull(store.load("index"))
        assertEquals(classId, store.loadClassId(binding))
    }

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
    fun resolvedCheckpointWinnerRepairsMissingCausalCoverage() {
        val db = inMemoryDatabase()
        val store = activeStore(db)
        val remote = remoteOperation()
        val reduction = OperationReducer().reduce(operations = listOf(remote))
        val adapter = SqlDelightSyncDomainStateAdapter(
            db = db,
            materializer = object : SyncDomainMaterializer {
                override fun apply(entity: ResolvedSyncEntity) = Unit
                override fun reconcileProjections() = Unit
            },
            nowMillis = { 200 },
        )

        store.replaceWithVerifiedCloudState(
            result = reduction.copy(appliedOperations = emptyList()),
            coverage = SyncCausalContext(),
            cloudOperationIds = emptySet(),
            appliedAtEpochMillis = 200,
            domainMutation = { adapter.adoptCheckpointWithinTransaction(it.entities.values) },
        )

        assertEquals(remote.sequence.value, store.causalContext()[remote.replicaKey])
        assertFalse(store.isApplied(remote.operationId))
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
        val publishedJournal = journalFor(operation, coverage, listOf(exactAck))
        assertEquals(
            coverage,
            coordinator.compactIfSafe(listOf(publishedJournal)),
        )
        assertEquals(
            AppSyncOperationLifecycle.Compacted,
            store.allOutboxOperations().single().second,
        )
        assertNull(
            coordinator.compactIfSafe(
                listOf(
                    publishedJournal.copy(
                        payload = publishedJournal.payload.copy(operations = emptyList()),
                    ),
                ),
            ),
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
    fun schedulingPolicyDefaultsAndRoundTrips() {
        val store = SqlDelightAppSyncOperationStore(inMemoryDatabase())
        val initial = store.initialize("generation")

        assertEquals(AppSyncScheduleSettings(), initial.scheduleSettings)
        assertEquals(0L, initial.requestedTriggerGeneration)
        assertEquals(0L, initial.accountedTriggerGeneration)

        val changed = AppSyncScheduleSettings(
            syncOnAppStart = true,
            syncOnForegroundExit = true,
            periodicInterval = FixedScheduleInterval.Days2,
        )
        store.setScheduleSettings(changed)

        assertEquals(changed, store.installation()?.scheduleSettings)
    }

    @Test
    fun schedulingPolicyRejectsIntervalsOutsideAppSyncSubset() {
        val store = SqlDelightAppSyncOperationStore(inMemoryDatabase())
        store.initialize("generation")

        assertFailsWith<IllegalArgumentException> {
            store.setScheduleSettings(
                AppSyncScheduleSettings(
                    periodicInterval = FixedScheduleInterval.Hours24,
                ),
            )
        }
    }

    @Test
    fun everySupportedSchedulingIntervalRoundTripsAndCorruptionFallsBack() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        val store = SqlDelightAppSyncOperationStore(Database(driver))
        store.initialize("generation")

        AppSyncPeriodicIntervals.forEach { interval ->
            store.setScheduleSettings(AppSyncScheduleSettings(periodicInterval = interval))
            assertEquals(interval, store.installation()?.scheduleSettings?.periodicInterval)
        }

        driver.execute(
            null,
            "UPDATE AppSyncInstallation SET periodicIntervalKey = 'corrupt'",
            0,
        )
        assertEquals(
            FixedScheduleInterval.Hours6,
            store.installation()?.scheduleSettings?.periodicInterval,
        )
    }

    @Test
    fun automaticTriggerGenerationIsGatedAndConditionallyAccounted() {
        val store = SqlDelightAppSyncOperationStore(inMemoryDatabase())
        store.initialize("generation")
        store.setScheduleSettings(
            AppSyncScheduleSettings(
                syncOnAppStart = true,
                syncOnForegroundExit = false,
            ),
        )

        assertNull(store.requestAutomaticTrigger(AppSyncAutomaticTrigger.AppStartup))
        store.setAutomaticEnabled(true)
        assertNull(store.requestAutomaticTrigger(AppSyncAutomaticTrigger.ForegroundExit))

        assertEquals(1L, store.requestAutomaticTrigger(AppSyncAutomaticTrigger.AppStartup))
        assertEquals(2L, store.requestAutomaticTrigger(AppSyncAutomaticTrigger.AppStartup))
        store.accountAutomaticTrigger(1L)
        assertEquals(1L, store.installation()?.accountedTriggerGeneration)
        assertEquals(2L, store.installation()?.requestedTriggerGeneration)

        store.accountAutomaticTrigger(99L)
        assertEquals(2L, store.installation()?.accountedTriggerGeneration)
    }

    @Test
    fun forcePullReplacementDiscardsUnpublishedOperationsAndAdoptsCloudCoverage() {
        val db = inMemoryDatabase()
        val store = activeStore(db)
        val originalInstallation = requireNotNull(store.installation())
        store.updateState(AppSyncInstallationState.Quarantined)
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
        assertEquals(AppSyncInstallationState.Active, store.installation()?.state)
        val replacementInstallation = requireNotNull(store.installation())
        assertNotEquals(originalInstallation.deviceId, replacementInstallation.deviceId)
        assertNotEquals(originalInstallation.deviceEpoch, replacementInstallation.deviceEpoch)
        assertNotEquals(originalInstallation.writerNonce, replacementInstallation.writerNonce)
        assertEquals(1L, replacementInstallation.nextSequence)

        val afterPull = appendSetting(store)
        assertEquals(1L, afterPull.sequence.value)
        assertEquals(replacementInstallation.deviceId, afterPull.deviceId)
        assertEquals(replacementInstallation.deviceEpoch, afterPull.deviceEpoch)
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

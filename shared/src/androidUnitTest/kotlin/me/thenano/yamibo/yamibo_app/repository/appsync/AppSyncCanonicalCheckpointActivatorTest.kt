package me.thenano.yamibo.yamibo_app.repository.appsync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.*
import me.thenano.yamibo.yamibo_app.Database
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.*
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncOperationLifecycle
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncInstallationState
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncRecoveryPhase
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.*
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.*
import me.thenano.yamibo.yamibo_app.repository.appsync.schema.*
import me.thenano.yamibo.yamibo_app.store.appsync.*
import me.thenano.yamibo.yamibo_app.store.settings.SettingsStore

class AppSyncCanonicalCheckpointActivatorTest {
    @Test fun engineResumesCommittedNativeRecoveryWhenWritesAreDisabledAndDoesNotRepublish() = fixture {
        val source = append()
        val (recovery, id) = stageNativeJournal(source)
        val continuation = nativeContinuation(recovery)
        val cloud = AppSyncJournalLoadResult.Success(emptyList(), verifiedCanonicalCheckpoints = listOf(verified()))
        val result = assertIs<OperationSyncResult.Converged>(synchronize(cloud, continuation))
        assertEquals(1, result.acknowledgedLocalCount)
        assertEquals(AppSyncRecoveryPhase.Completed, recovery.session(id)?.phase)
        assertEquals(18, preferences.values["novelreadersettings.fontsize"])
        assertNotNull(state.read(account.value))
        assertNull(db.appSyncOperationQueries.getRunLease().executeAsOneOrNull())
        assertFalse(continuation.hasPending(account))
    }

    @Test fun nativeWriteGateNeedsExplicitResumeAndLegacyCloudCannotBypassIt() = fixture {
        val source = append()
        val (recovery, id) = stageNativeCheckpoint(committed = false)
        val continuation = nativeContinuation(recovery)
        val cloud = AppSyncJournalLoadResult.Success(emptyList(), verifiedCanonicalCheckpoints = listOf(verified()))
        assertIs<OperationSyncResult.PausedProvider>(synchronize(cloud, continuation))
        assertEquals(AppSyncRecoveryPhase.NeedsAttention, recovery.session(id)?.phase)
        assertEquals("native-compatibility", recovery.session(id)?.lastErrorCategory)
        assertEquals(0L, recovery.session(id)?.retryCount)
        assertNull(state.read(account.value))
        val reads = loadCalls
        assertIs<OperationSyncResult.PausedProvider>(synchronize(cloud, continuation))
        assertEquals(reads, loadCalls)
        recovery.resumeRetryExhaustedRecovery(account, now)
        assertEquals(AppSyncRecoveryPhase.CommittingIndex, recovery.session(id)?.phase)
        assertIs<OperationSyncResult.PausedProvider>(synchronize(AppSyncJournalLoadResult.Success(emptyList()), continuation))
        assertEquals("native-cloud-validation", recovery.session(id)?.lastErrorCategory)
        assertEquals(listOf(source), store.pendingOperations())
        assertNull(state.read(account.value))
        assertFalse(recovery.session(id)!!.indexCommitted)
    }

    @Test fun nativeCloudReadFailuresRespectDurableDeadlineAndExhaustionBeforeReadingAgain() = fixture {
        append()
        val (recovery, id) = stageNativeCheckpoint()
        val continuation = nativeContinuation(recovery)
        val failure = AppSyncJournalLoadResult.RetryableFailure("offline")
        repeat(3) { attempt ->
            assertIs<OperationSyncResult.RetryScheduled>(synchronize(failure, continuation))
            assertEquals((attempt + 1).toLong(), recovery.session(id)?.retryCount)
            val reads = loadCalls
            if (attempt < 2) {
                assertIs<OperationSyncResult.RetryScheduled>(synchronize(failure, continuation))
                assertEquals(reads, loadCalls)
                now = assertNotNull(recovery.session(id)?.nextRetryAtEpochMillis)
            }
        }
        assertEquals(AppSyncRecoveryPhase.NeedsAttention, recovery.session(id)?.phase)
        val reads = loadCalls
        assertTrue(forcedLoads.all { it })
        assertIs<OperationSyncResult.PausedProvider>(synchronize(failure, continuation))
        assertEquals(reads, loadCalls)
        assertNull(state.read(account.value))
        assertNull(db.appSyncOperationQueries.getRunLease().executeAsOneOrNull())
    }

    @Test fun nativeGateExpiringInsideCommitRemainsExplicitlyResumableWithoutChargingPayloadFailure() = fixture {
        append()
        val (recovery, id) = stageNativeCheckpoint(committed = false)
        var gateChecks = 0
        val continuation = nativeContinuation(recovery) { ++gateChecks <= 2 }
        val cloud = AppSyncJournalLoadResult.Success(emptyList(), verifiedCanonicalCheckpoints = listOf(verified()))
        assertIs<OperationSyncResult.PausedProvider>(synchronize(cloud, continuation))
        assertEquals(AppSyncRecoveryPhase.NeedsAttention, recovery.session(id)?.phase)
        assertEquals("native-compatibility", recovery.session(id)?.lastErrorCategory)
        assertEquals(0L, recovery.session(id)?.retryCount)
        recovery.resumeRetryExhaustedRecovery(account, now)
        assertEquals(AppSyncRecoveryPhase.CommittingIndex, recovery.session(id)?.phase)
        assertNull(recovery.session(id)?.lastErrorCategory)
        assertNull(state.read(account.value))
    }

    @Test fun unexpectedNativeReadExceptionAlsoPersistsOneFailureAndReleasesLease() = fixture {
        append()
        val (recovery, id) = stageNativeCheckpoint()
        val continuation = nativeContinuation(recovery)
        loadThrows = true
        val cloud = AppSyncJournalLoadResult.Success(emptyList())
        assertIs<OperationSyncResult.RetryScheduled>(synchronize(cloud, continuation))
        assertEquals(1L, recovery.session(id)?.retryCount)
        assertNotNull(recovery.session(id)?.nextRetryAtEpochMillis)
        assertNull(db.appSyncOperationQueries.getRunLease().executeAsOneOrNull())
        val reads = loadCalls
        assertIs<OperationSyncResult.RetryScheduled>(synchronize(cloud, continuation))
        assertEquals(reads, loadCalls)
        assertEquals(1L, recovery.session(id)?.retryCount)
    }

    @Test fun journalRecoveryMergesOtherDeviceStateAndWaitsForSettingsBeforeAcknowledgingOnlyFrozenSources() = fixture {
        val published = append()
        val (recovery, id) = stageNativeJournal(published)
        val latest = remoteCheckpoint(published)
        val cloud = AppSyncCanonicalCloudPlan.Ready(verified(latest), AppSyncCanonicalOperationBlock(account.value, emptyList()), emptyList())
        preferences.fail = true
        assertIs<AppSyncSegmentedJournalCommitResult.Retryable>(commitNative(recovery, id, cloud, 100))
        assertEquals(AppSyncRecoveryPhase.ActivatingLocal, recovery.session(id)?.phase)
        assertEquals(listOf(published), store.pendingOperations())
        assertEquals(latest.entities, state.read(account.value)?.entities)
        val later = append("22")
        preferences.fail = false
        val completed = assertIs<AppSyncSegmentedJournalCommitResult.Verified>(commitNative(
            SqlDelightAppSyncRecoveryStore(db), id, cloud, assertNotNull(recovery.session(id)?.nextRetryAtEpochMillis)))
        assertEquals(setOf(published.operationId.value), completed.acknowledgedOperationIds)
        assertEquals(AppSyncRecoveryPhase.Completed, recovery.session(id)?.phase)
        assertEquals(listOf(later), store.pendingOperations())
        assertEquals(AppSyncOperationLifecycle.Acknowledged, store.allOutboxOperations().single { it.first == published }.second)
        assertEquals(22, preferences.values["novelreadersettings.fontsize"])
        assertEquals(latest.coverage, store.verifiedCheckpoints().single().coverage.asStableMap())
        assertEquals(2L, state.read(account.value)?.coverage?.get(published.replicaKey.stableKey))
        preferences.values["novelreadersettings.fontsize"] = 30
        assertIs<AppSyncCanonicalActivationResult.Applied>(activator().activateJournalRecovery(recovery, id, cloud))
        assertEquals(30, preferences.values["novelreadersettings.fontsize"])
    }

    @Test fun conflictingJournalRecoveryEvidenceCannotWriteProjectionOrAcknowledge() = fixture {
        val published = append()
        val (recovery, id) = stageNativeJournal(published)
        val original = recovery.nativeJournalForActivation(id).document.block.operations.single()
        val conflicting = assertIs<AppSyncCanonicalOperationImport.Accepted>(AppSyncCanonicalOperationImporter().import(
            account.value, published.copy(fields = published.fields + ("value" to "30")))).operation
        assertNotEquals(original, conflicting)
        val cloud = AppSyncCanonicalCloudPlan.Ready(verified(), AppSyncCanonicalOperationBlock(account.value, listOf(conflicting)), emptyList())
        assertIs<AppSyncCanonicalActivationResult.NeedsAttention>(activator().activateJournalRecovery(recovery, id, cloud))
        assertNull(state.read(account.value))
        assertTrue(preferences.values.isEmpty())
        assertTrue(store.verifiedCheckpoints().isEmpty())
        assertEquals(listOf(published), store.pendingOperations())
        assertEquals(AppSyncRecoveryPhase.ActivatingLocal, recovery.session(id)?.phase)
    }

    @Test fun journalObservedHistoryMustBePresentBeforeAnyProjectionOrAcknowledgement() = fixture {
        val published = append()
        val (recovery, id) = stageNativeJournal(published, observed = mapOf("missing:epoch" to 7L))
        val cloud = AppSyncCanonicalCloudPlan.Ready(verified(), AppSyncCanonicalOperationBlock(account.value, emptyList()), emptyList())
        assertIs<AppSyncCanonicalActivationResult.NeedsAttention>(activator().activateJournalRecovery(recovery, id, cloud))
        assertNull(state.read(account.value))
        assertTrue(store.verifiedCheckpoints().isEmpty())
        assertEquals(listOf(published), store.pendingOperations())
        assertEquals(AppSyncRecoveryPhase.ActivatingLocal, recovery.session(id)?.phase)
    }

    @Test fun engineRecoveryHookRunsUnderLeaseBeforeGenericActivationAndReleasesOnReturn() = fixture {
        val pending = append()
        var calls = 0
        val cloud = AppSyncJournalLoadResult.Success(emptyList(), verifiedCanonicalCheckpoints = listOf(verified()))
        val expected = OperationSyncResult.RetryScheduled("native recovery continuation")
        assertEquals(expected, synchronize(cloud) { binding, _, plan ->
            calls++
            assertEquals(account, binding)
            assertEquals(checkpoint.checkpointId, plan.checkpoint.document.checkpointId)
            assertNotNull(db.appSyncOperationQueries.getRunLease().executeAsOneOrNull())
            assertNull(state.read(account.value))
            expected
        })
        assertEquals(1, calls)
        assertNull(db.appSyncOperationQueries.getRunLease().executeAsOneOrNull())
        assertNull(state.read(account.value))
        assertEquals(listOf(pending), store.pendingOperations())
    }

    @Test fun invalidCanonicalCloudCannotInvokeRecoveryHookAndNullHookKeepsReaderBehavior() = fixture {
        val pending = append()
        val cloud = AppSyncJournalLoadResult.Success(emptyList(), verifiedCanonicalCheckpoints = listOf(verified()))
        var calls = 0
        assertIs<OperationSyncResult.PausedProvider>(synchronize(cloud.copy(canonicalReadIssues = listOf("corrupt"))) { _, _, _ ->
            calls++; error("Invalid cloud must not reach recovery")
        })
        assertEquals(0, calls)
        assertNull(state.read(account.value))
        store.updateState(AppSyncInstallationState.Active)
        assertIs<OperationSyncResult.PausedProvider>(synchronize(cloud) { _, _, _ -> calls++; null })
        assertEquals(1, calls)
        assertNotNull(state.read(account.value))
        assertEquals(listOf(pending), store.pendingOperations())
    }
    @Test fun nativeCoordinatorRetriesCheckpointSettingsWithoutRepeatingRemotePublication() = fixture {
        val pending = append()
        val latest = remoteCheckpoint(pending)
        val cloud = AppSyncCanonicalCloudPlan.Ready(verified(latest), AppSyncCanonicalOperationBlock(account.value, emptyList()), emptyList())
        val (recovery, id) = stageNativeCheckpoint()
        var now = 100L
        val provider = object : AppSyncBlogProvider {
            override suspend fun fetchMyBlogs(blogClassId: io.github.littlesurvival.dto.value.BlogClassId?, page: Int): Nothing = error("No remote scan during activation")
            override suspend fun fetchBlog(blogId: io.github.littlesurvival.dto.value.BlogId): Nothing = error("No remote read during activation")
            override suspend fun submitBlog(request: AppSyncBlogWriteRequest): Nothing = error("No remote write during activation")
            override suspend fun deleteBlog(request: AppSyncBlogDeleteRequest): Nothing = error("No cleanup during activation")
        }
        val publisher = AppSyncV3SegmentPublisher(provider, recovery, { now })
        val committer = AppSyncV3IndexCommitter(provider, recovery, publisher, { now })
        fun run() = kotlinx.coroutines.runBlocking {
            AppSyncV3CommitCoordinator(committer, recovery, { now },
                AppSyncCanonicalCheckpointActivator(db, store, state, materializer, { now }), { true })
                .commit(id, "unused after verified index", checkpoint.checkpointId,
                    AppSyncBlogClassSelection.Existing(io.github.littlesurvival.dto.value.BlogClassId(7)),
                    io.github.littlesurvival.dto.value.FormHash("test"), cloud)
        }
        preferences.fail = true
        assertIs<AppSyncSegmentedJournalCommitResult.Retryable>(run())
        assertEquals(AppSyncRecoveryPhase.ActivatingLocal, recovery.session(id)?.phase)
        assertEquals(1L, recovery.session(id)?.retryCount)
        now = assertNotNull(recovery.session(id)?.nextRetryAtEpochMillis)
        preferences.fail = false
        val result = assertIs<AppSyncSegmentedJournalCommitResult.Verified>(run())
        assertTrue(result.acknowledgedOperationIds.isEmpty())
        assertEquals(AppSyncRecoveryPhase.Completed, recovery.session(id)?.phase)
        assertEquals(listOf(pending), store.pendingOperations())
        assertEquals(26, preferences.values["novelreadersettings.fontsize"])
        assertEquals(setOf(checkpoint.checkpointId, latest.checkpointId), store.verifiedCheckpoints().map { it.checkpointId }.toSet())
        assertEquals(latest.coverage, store.verifiedCheckpoints().single { it.checkpointId == latest.checkpointId }.coverage.asStableMap())
    }

    @Test fun staleRecoveryCannotReplaceAnAlreadyActivatedRemoteHeadWithoutLatestCloudEvidence() = fixture {
        val pending = append()
        val latest = remoteCheckpoint(pending)
        assertIs<AppSyncCanonicalActivationResult.Applied>(activator().activate(verified(latest)))
        val (recovery, id) = stageNativeCheckpoint()
        val before = state.read(account.value)
        val evidence = store.verifiedCheckpoints()
        assertIs<AppSyncCanonicalActivationResult.NeedsAttention>(activator().activateRecovery(recovery, id))
        assertEquals(before, state.read(account.value))
        assertEquals(evidence, store.verifiedCheckpoints())
        assertEquals(26, preferences.values["novelreadersettings.fontsize"])
        assertEquals(AppSyncRecoveryPhase.ActivatingLocal, recovery.session(id)?.phase)
        val cloud = AppSyncCanonicalCloudPlan.Ready(verified(latest), AppSyncCanonicalOperationBlock(account.value, emptyList()), emptyList())
        assertIs<AppSyncCanonicalActivationResult.Applied>(activator().activateRecovery(recovery, id, cloud))
        assertEquals(AppSyncRecoveryPhase.Completed, recovery.session(id)?.phase)
        assertEquals(before?.coverage, state.read(account.value)?.coverage)
        assertEquals(listOf(pending), store.pendingOperations())
    }
    @Test fun nativeRecoveryWaitsForPreferencesAndPreservesEditsAddedDuringRetry() = fixture {
        val pending = append()
        val (recovery, id) = stageNativeCheckpoint()
        preferences.fail = true
        val first = assertIs<AppSyncCanonicalActivationResult.Applied>(activator().activateRecovery(recovery, id))
        assertFalse(first.settingsReconciled)
        assertEquals(AppSyncRecoveryPhase.ActivatingLocal, recovery.session(id)?.phase)
        assertNotNull(state.read(account.value))
        assertEquals(listOf(pending), store.pendingOperations())
        val later = append("22")
        assertIs<AppSyncCanonicalLocalUpdate.Recorded>(state.recordLocalBatch(account.value, listOf(later)))
        preferences.fail = false
        val restarted = SqlDelightAppSyncRecoveryStore(db)
        assertTrue(assertIs<AppSyncCanonicalActivationResult.Applied>(activator().activateRecovery(restarted, id)).settingsReconciled)
        assertEquals(AppSyncRecoveryPhase.Completed, restarted.session(id)?.phase)
        assertEquals(22, preferences.values["novelreadersettings.fontsize"])
        assertEquals(listOf(pending, later), store.pendingOperations())
        assertEquals(checkpoint.coverage, store.verifiedCheckpoints().single().coverage.asStableMap())
        val head = db.appSyncCanonicalStateQueries.getState().executeAsOne()
        preferences.values["novelreadersettings.fontsize"] = 26
        assertIs<AppSyncCanonicalActivationResult.Applied>(activator().activateRecovery(restarted, id))
        assertEquals(26, preferences.values["novelreadersettings.fontsize"])
        val replayed = db.appSyncCanonicalStateQueries.getState().executeAsOne()
        assertEquals(head.checkpointId, replayed.checkpointId)
        assertEquals(head.canonicalSha256, replayed.canonicalSha256)
        assertContentEquals(head.canonicalPayload, replayed.canonicalPayload)
    }

    @Test fun nativeRecoveryRequiresCommittedIndexAndUnchangedInstallationBeforeProjection() = fixture {
        append()
        val (recovery, id) = stageNativeCheckpoint(committed = false)
        assertIs<AppSyncCanonicalActivationResult.NeedsAttention>(activator().activateRecovery(recovery, id))
        assertNull(state.read(account.value))
        assertTrue(store.verifiedCheckpoints().isEmpty())
        recovery.markNativeIndexCommitted(id, 400, assertNotNull(recovery.nativeIndexIntent(id)).body, 12)
        assertFailsWith<IllegalArgumentException> { recovery.completeNativeCheckpointActivation(id, 20) }
        store.rotateDeviceEpoch(account, AppSyncInstallationState.Active)
        assertIs<AppSyncCanonicalActivationResult.NeedsAttention>(activator().activateRecovery(recovery, id))
        assertNull(state.read(account.value))
        assertEquals(AppSyncRecoveryPhase.ActivatingLocal, recovery.session(id)?.phase)
    }

    @Test fun activationGuardFailureCannotWriteProjectionOrCheckpointEvidence() = fixture {
        val pending = append()
        assertIs<AppSyncCanonicalActivationResult.NeedsAttention>(activator().activate(verified(), beforeDatabaseActivation = {
            error("Recovery changed before transaction")
        }))
        assertNull(state.read(account.value))
        assertTrue(store.verifiedCheckpoints().isEmpty())
        assertEquals(listOf(pending), store.pendingOperations())
    }
    @Test fun preferenceReconciliationRetainsReferencedDeletionProofs() = fixture {
        val delete = AppSyncCanonicalOperation("remote-device", "remote-epoch", 1, 1,
            "novelreadersettings.fontsize", 1, SyncOperationKind.Delete, 15,
            SyncOperationOrigin.UserAction, "proof", emptyMap(), emptyMap())
        val head = checkpoint.copy(coverage = mapOf("remote-device:remote-epoch" to 1L),
            entities = listOf(AppSyncCanonicalProjection(1, delete.entityId, 1, tombstone = delete)),
            authorizations = listOf(AppSyncCanonicalDeleteProof("proof", 1, "settings", 1, 100)))
        preferences.values[delete.entityId] = 18
        state.replace(account.value, head)
        assertTrue(state.reconcileSettings(account.value))
        assertFalse(delete.entityId in preferences.values)
        assertEquals(0L, db.appSyncCanonicalStateQueries.getState().executeAsOne().settingsReconciliationPending)
    }

    @Test fun failedPreferenceReconciliationSurvivesLocalEditsAndResumesFromLatestHead() = fixture {
        append()
        preferences.fail = true
        assertFalse(assertIs<AppSyncCanonicalActivationResult.Applied>(activator().activate(verified())).settingsReconciled)
        assertEquals(1L, db.appSyncCanonicalStateQueries.getState().executeAsOne().settingsReconciliationPending)
        val edit = append("22")
        assertIs<AppSyncCanonicalLocalUpdate.Recorded>(state.recordLocalBatch(account.value, listOf(edit)))
        assertEquals(1L, db.appSyncCanonicalStateQueries.getState().executeAsOne().settingsReconciliationPending)
        preferences.fail = false
        val restarted = SqlDelightCanonicalCheckpointState(db, DatabaseSyncDomainMaterializer(db, preferences))
        assertTrue(restarted.reconcileSettings(account.value))
        assertEquals(22, preferences.values["novelreadersettings.fontsize"])
        assertEquals(0L, db.appSyncCanonicalStateQueries.getState().executeAsOne().settingsReconciliationPending)
        // Once reconciled, a later ordinary preference edit must not replay an old mirror.
        preferences.values["novelreadersettings.fontsize"] = 26
        assertTrue(restarted.reconcileSettings(account.value))
        assertEquals(26, preferences.values["novelreadersettings.fontsize"])
    }

    @Test fun canonicalSnapshotAuditUsesTypedFieldsAndRepairsOnlyActualChanges() = fixture {
        val pending = append()
        activator().activate(verified())
        val planner = LocalProjectionRepairPlanner()
        val draft = LocalSyncOperationDraft(pending.domainId, pending.entityId, kind = SyncOperationKind.Put,
            fields = pending.fields + mapOf("value" to "018", "cache" to "local-only"))
        assertTrue(planner.plan(listOf(draft), assertNotNull(state.read(account.value))).isEmpty())
        val edited = draft.copy(fields = draft.fields + ("value" to "22"))
        val repairs = planner.plan(listOf(edited), assertNotNull(state.read(account.value)))
        assertEquals(1, repairs.size)
        store.appendLocalOperations(account, repairs, store.causalContext(), 25, SyncOperationOrigin.Migration) {
            assertIs<AppSyncCanonicalLocalUpdate.Recorded>(state.recordLocalBatch(account.value, it))
        }
        assertTrue(planner.plan(listOf(edited), assertNotNull(state.read(account.value))).isEmpty())
        assertEquals(2, store.pendingOperations().size)
        assertTrue(db.appSyncOperationQueries.getResolvedEntities().executeAsList().isEmpty())
    }

    @Test fun canonicalSnapshotAuditRejectsInvalidEssentialValuesAndDoesNotInferMissingRowDeletes() = fixture {
        val pending = append()
        activator().activate(verified())
        val planner = LocalProjectionRepairPlanner()
        val head = assertNotNull(state.read(account.value))
        val invalid = LocalSyncOperationDraft(pending.domainId, pending.entityId, kind = SyncOperationKind.Put,
            fields = pending.fields + ("value" to "not-an-integer"))
        assertFailsWith<IllegalArgumentException> { planner.plan(listOf(invalid), head) }
        assertTrue(planner.plan(emptyList(), head).isEmpty())
        assertEquals(head, state.read(account.value))
        assertEquals(listOf(pending), store.pendingOperations())
    }

    @Test fun engineActivatesVerifiedCanonicalCloudAndPreservesPendingEdits() = fixture {
        val pending = append()
        val cloud = AppSyncJournalLoadResult.Success(emptyList(), verifiedCanonicalCheckpoints = listOf(verified()))
        val result = synchronize(cloud)
        assertIs<OperationSyncResult.PausedProvider>(result)
        assertTrue(result.reason.contains("Canonical state applied"))
        assertNotNull(state.read(account.value))
        assertEquals(18, preferences.values["novelreadersettings.fontsize"])
        assertEquals(listOf(pending), store.pendingOperations())
        assertTrue(store.verifiedCheckpoints().single().coverage.asStableMap().isEmpty())
    }

    @Test fun engineCannotFallBackToLegacyOrEmptyCloudAfterCanonicalActivation() = fixture {
        append()
        assertIs<AppSyncCanonicalActivationResult.Applied>(activator().activate(verified()))
        val before = state.read(account.value)
        val outbox = store.allOutboxOperations()
        val result = assertIs<OperationSyncResult.PausedProvider>(synchronize(AppSyncJournalLoadResult.Success(emptyList())))
        assertTrue(result.reason.contains("verified v3 cloud base"))
        assertEquals(before, state.read(account.value))
        assertEquals(outbox, store.allOutboxOperations())
    }

    @Test fun engineDoesNotActivateInvalidCloudOrReportFailedSettingsAsApplied() = fixture {
        val pending = append()
        val invalid = AppSyncJournalLoadResult.Success(emptyList(), verifiedCanonicalCheckpoints = listOf(verified()),
            canonicalReadIssues = listOf("corrupt journal"))
        assertTrue(assertIs<OperationSyncResult.PausedProvider>(synchronize(invalid)).reason.contains("validation"))
        assertNull(state.read(account.value))
        assertTrue(store.verifiedCheckpoints().isEmpty())
        store.updateState(AppSyncInstallationState.Active)
        preferences.fail = true
        val valid = invalid.copy(canonicalReadIssues = emptyList())
        assertTrue(assertIs<OperationSyncResult.PausedProvider>(synchronize(valid)).reason.contains("settings reconciliation"))
        assertNotNull(state.read(account.value))
        assertEquals(listOf(pending), store.pendingOperations())
        store.updateState(AppSyncInstallationState.Active)
        preferences.fail = false
        assertTrue(assertIs<OperationSyncResult.PausedProvider>(synchronize(valid)).reason.contains("Canonical state applied"))
        assertEquals(18, preferences.values["novelreadersettings.fontsize"])
    }

    @Test fun laterNativeJournalAndPendingEditsCommitTogetherWithoutExpandingRemoteCheckpointCoverage() = fixture {
        val pending = append()
        val device = SyncDeviceId("remote-device")
        val epoch = SyncDeviceEpoch("remote-epoch")
        val remote = pending.copy(deviceId = device, deviceEpoch = epoch,
            operationId = SyncOperation.idFor(device, epoch, pending.sequence),
            fields = pending.fields + ("value" to "26"), createdAtEpochMillis = 100)
        val imported = assertIs<AppSyncCanonicalOperationImport.Accepted>(AppSyncCanonicalOperationImporter().import(account.value, remote))
        val block = AppSyncCanonicalOperationBlock(account.value, listOf(imported.operation))
        assertIs<AppSyncCanonicalActivationResult.Applied>(activator().activate(verified(), block))
        assertEquals(26, preferences.values["novelreadersettings.fontsize"])
        val local = assertNotNull(state.read(account.value))
        assertEquals(mapOf(remote.replicaKey.stableKey to 1L, pending.replicaKey.stableKey to 1L), local.coverage)
        assertEquals(local.coverage, store.causalContext().asStableMap())
        assertTrue(store.isApplied(remote.operationId))
        assertEquals(listOf(pending), store.pendingOperations())
        assertTrue(store.verifiedCheckpoints().single().coverage.asStableMap().isEmpty())
        assertIs<AppSyncCanonicalActivationResult.Applied>(activator().activate(verified(), block))
        assertEquals(local, state.read(account.value))
    }

    private val account = SyncAccountBinding("account")
    private val checkpoint = AppSyncCanonicalCheckpoint("remote", account.value, 10, emptyMap(), emptyList())
    private fun verified(cp: AppSyncCanonicalCheckpoint = checkpoint): AppSyncVerifiedCanonicalCheckpoint {
        val fingerprint = AppSyncCanonicalCheckpointCodec().encode(cp).sha256().hex()
        val index = AppSyncIndexEnvelopeCodec().encode(AppSyncIndexPayload(account,
            checkpoints = listOf(AppSyncIndexCheckpointReference(cp.checkpointId, 123, fingerprint)), updatedAtEpochMillis = 11))
        return assertNotNull(AppSyncVerifiedCanonicalCheckpoint.verify(account.value, 123, index,
            AppSyncV3DocumentCodec().encodeCheckpoint(cp)))
    }

    private class Preferences : SettingsStore {
        var fail = false
        val values = mutableMapOf<String, Any>()
        private fun write(key: String, value: Any) { check(!fail); values[key] = value }
        override fun getInt(key: String, defaultValue: Int) = values[key] as? Int ?: defaultValue
        override fun putInt(key: String, value: Int) = write(key, value)
        override fun getFloat(key: String, defaultValue: Float) = values[key] as? Float ?: defaultValue
        override fun putFloat(key: String, value: Float) = write(key, value)
        override fun getString(key: String, defaultValue: String) = values[key] as? String ?: defaultValue
        override fun putString(key: String, value: String) = write(key, value)
        override fun getBoolean(key: String, defaultValue: Boolean) = values[key] as? Boolean ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) = write(key, value)
        override fun remove(key: String) { check(!fail); values.remove(key) }
        override fun hasKey(key: String) = key in values
    }

    private inner class Fixture(val db: Database) {
        var now = 20L
        var loadCalls = 0
        var loadThrows = false
        val forcedLoads = mutableListOf<Boolean>()
        val store = SqlDelightAppSyncOperationStore(db).also {
            it.initialize("database"); it.bindAccount(account, AppSyncInstallationState.Active)
        }
        val preferences = Preferences()
        val materializer = DatabaseSyncDomainMaterializer(db, preferences)
        val state = SqlDelightCanonicalCheckpointState(db, materializer)
        fun nativeContinuation(recovery: SqlDelightAppSyncRecoveryStore, canWrite: () -> Boolean = { false }): AppSyncNativeRecoveryContinuation {
            val provider = object : AppSyncBlogProvider {
                override suspend fun fetchMyBlogs(blogClassId: io.github.littlesurvival.dto.value.BlogClassId?, page: Int): Nothing = error("Unexpected native scan")
                override suspend fun fetchBlog(blogId: io.github.littlesurvival.dto.value.BlogId): Nothing = error("Unexpected native read")
                override suspend fun submitBlog(request: AppSyncBlogWriteRequest): Nothing = error("Unexpected native write")
                override suspend fun deleteBlog(request: AppSyncBlogDeleteRequest): Nothing = error("Unexpected native cleanup")
            }
            val blogs = SqlDelightAppSyncRemoteBlogStore(db)
            blogs.saveClassId(account, io.github.littlesurvival.dto.value.BlogClassId(7))
            return AppSyncNativeRecoveryContinuation(provider, store, recovery, blogs, activator(), { now }, canWrite)
        }
        fun commitNative(recovery: SqlDelightAppSyncRecoveryStore, id: String, cloud: AppSyncCanonicalCloudPlan.Ready,
            now: Long) = kotlinx.coroutines.runBlocking {
            val provider = object : AppSyncBlogProvider {
                override suspend fun fetchMyBlogs(blogClassId: io.github.littlesurvival.dto.value.BlogClassId?, page: Int): Nothing = error("No remote scan during activation")
                override suspend fun fetchBlog(blogId: io.github.littlesurvival.dto.value.BlogId): Nothing = error("No remote read during activation")
                override suspend fun submitBlog(request: AppSyncBlogWriteRequest): Nothing = error("No remote write during activation")
                override suspend fun deleteBlog(request: AppSyncBlogDeleteRequest): Nothing = error("No cleanup during activation")
            }
            val publisher = AppSyncV3SegmentPublisher(provider, recovery, { now })
            val committer = AppSyncV3IndexCommitter(provider, recovery, publisher, { now })
            AppSyncV3CommitCoordinator(committer, recovery, { now }, activator(), { true }).commit(id, "frozen", "identity",
                AppSyncBlogClassSelection.Existing(io.github.littlesurvival.dto.value.BlogClassId(7)),
                io.github.littlesurvival.dto.value.FormHash("test"), cloud)
        }
        fun stageNativeJournal(source: SyncOperation, observed: Map<String, Long> = emptyMap()): Pair<SqlDelightAppSyncRecoveryStore, String> {
            val recovery = SqlDelightAppSyncRecoveryStore(db)
            val session = recovery.createOrResumeSegmentedJournal(account, setOf(source.operationId.value), "journal-source", 1)
            val imported = assertIs<AppSyncCanonicalOperationImport.Accepted>(AppSyncCanonicalOperationImporter().import(account.value, source))
            val identity = source.replicaKey.stableKey
            val journal = AppSyncCanonicalJournal(AppSyncCanonicalOperationBlock(account.value, listOf(imported.operation)),
                source.deviceId.value, source.deviceEpoch.value, session.targetWriterNonce.value,
                source.sequence.value, source.sequence.value, observed, emptyList(), 1, 3, 3, "test", source.sequence.value)
            recovery.pinPayload(session.sessionId, "Journal", identity, 3) { AppSyncV3DocumentCodec().encodeJournal(identity, journal) }
            recovery.startSegmentedJournal(session.sessionId, 2)
            recovery.saveSegmentIntent(session.sessionId, 0, 1, "segment", null)
            recovery.markSegmentVerified(session.sessionId, 0, "segment", 200, 3)
            recovery.transition(session.sessionId, AppSyncRecoveryPhase.PublishingSegments, AppSyncRecoveryPhase.PublishingRoot, 4)
            recovery.pinNativeRootIntent(session.sessionId, "b".repeat(64))
            recovery.markRootVerified(session.sessionId, 300, "b".repeat(64), 5)
            val index = AppSyncIndexEnvelopeCodec().encode(AppSyncIndexPayload(account,
                journals = listOf(AppSyncIndexJournalReference(identity, 300,
                    AppSyncCanonicalJournalCodec().encode(journal).sha256().hex())), updatedAtEpochMillis = 6))
            recovery.pinNativeIndexIntent(session.sessionId, NativeRecoveryIndexIntent(index, null, null))
            recovery.markNativeIndexCommitted(session.sessionId, 400, index, 7)
            return recovery to session.sessionId
        }
        fun remoteCheckpoint(source: SyncOperation): AppSyncCanonicalCheckpoint {
            val device = SyncDeviceId("new-remote"); val epoch = SyncDeviceEpoch("remote-epoch")
            val remote = source.copy(deviceId = device, deviceEpoch = epoch,
                operationId = SyncOperation.idFor(device, epoch, source.sequence),
                fields = source.fields + ("value" to "26"), createdAtEpochMillis = 100)
            return assertIs<AppSyncCanonicalPendingMergeResult.Ready>(AppSyncCanonicalPendingMerge().prepare(
                checkpoint, listOf(remote), "latest-remote", 101)).checkpoint
        }
        fun stageNativeCheckpoint(committed: Boolean = true): Pair<SqlDelightAppSyncRecoveryStore, String> {
            val recovery = SqlDelightAppSyncRecoveryStore(db)
            val session = recovery.createOrResumeSegmentedCheckpoint(account, checkpoint.checkpointId, "source", 1)
            recovery.pinPayload(session.sessionId, "Checkpoint", checkpoint.checkpointId, 3) {
                AppSyncV3DocumentCodec().encodeCheckpoint(checkpoint)
            }
            recovery.startSegmentedJournal(session.sessionId, 2)
            recovery.saveSegmentIntent(session.sessionId, 0, 1, "segment", null)
            recovery.markSegmentVerified(session.sessionId, 0, "segment", 200, 3)
            recovery.transition(session.sessionId, AppSyncRecoveryPhase.PublishingSegments, AppSyncRecoveryPhase.PublishingRoot, 4)
            recovery.pinNativeRootIntent(session.sessionId, "a".repeat(64))
            recovery.markRootVerified(session.sessionId, 300, "a".repeat(64), 5)
            val index = AppSyncIndexEnvelopeCodec().encode(AppSyncIndexPayload(account,
                checkpoints = listOf(AppSyncIndexCheckpointReference(checkpoint.checkpointId, 300,
                    AppSyncCanonicalCheckpointCodec().encode(checkpoint).sha256().hex())), updatedAtEpochMillis = 6))
            recovery.pinNativeIndexIntent(session.sessionId, NativeRecoveryIndexIntent(index, null, null))
            if (committed) recovery.markNativeIndexCommitted(session.sessionId, 400, index, 7)
            return recovery to session.sessionId
        }
        fun synchronize(cloud: AppSyncJournalLoadResult, recovery: AppSyncCanonicalRecoveryContinuation? = null,
            onResume: suspend (SyncAccountBinding, io.github.littlesurvival.dto.value.FormHash, AppSyncCanonicalCloudPlan.Ready) -> OperationSyncResult? = { _, _, _ -> null }): OperationSyncResult = kotlinx.coroutines.runBlocking {
            val remote = object : AppSyncJournalRemote {
                override suspend fun loadJournals(accountBinding: SyncAccountBinding, forceDiscovery: Boolean): AppSyncJournalLoadResult {
                    loadCalls++; forcedLoads += forceDiscovery
                    check(!loadThrows) { "Unexpected load failure" }
                    return cloud
                }
                override suspend fun publishOwnJournal(payload: AppSyncJournalPayload, expectedFingerprint: String?,
                    formHash: io.github.littlesurvival.dto.value.FormHash): AppSyncJournalPublishResult =
                    error("Canonical reader must not publish a legacy journal")
            }
            val legacy = object : SyncDomainStateAdapter {
                override fun currentState(): Map<SyncEntityKey, ResolvedSyncEntity> = error("Legacy state read")
                override fun apply(result: OperationReductionResult) = error("Legacy state write")
            }
            OperationSyncEngine(store, remote, legacy, nowMillis = { now }, ownerId = { "canonical-reader-test" },
                activateCanonical = { activator().activate(it.checkpoint, it.canonicalOperations, it.legacyOperations) },
                hasCanonicalState = { db.appSyncCanonicalStateQueries.getState().executeAsOneOrNull() != null },
                canonicalRecovery = recovery ?: object : AppSyncCanonicalRecoveryContinuation {
                    override suspend fun resume(account: SyncAccountBinding, formHash: io.github.littlesurvival.dto.value.FormHash,
                        cloud: AppSyncCanonicalCloudPlan.Ready) = onResume(account, formHash, cloud)
                })
                .synchronize(account, io.github.littlesurvival.dto.value.FormHash("test"), detectEmptyCloud = true,
                    forceDiscovery = recovery?.hasPending(account) == true)
        }
        fun activator(operations: AppSyncOperationStore = store) =
            AppSyncCanonicalCheckpointActivator(db, operations, state, materializer, { now })
        fun append(value: String = "18") = store.appendLocalOperation(account,
            SyncDomainId("settings"), SyncEntityId("novelreadersettings.fontsize"), 1,
            SyncOperationKind.Put, mapOf("type" to "int", "value" to value), store.causalContext(),
            15, SyncOperationOrigin.UserAction)
    }
    private fun fixture(test: Fixture.() -> Unit) {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            Database.Schema.create(driver)
            Fixture(Database(driver)).test()
        }
    }

    @Test fun discardedAndSupersededSourcesCannotResurrectDuringActivation() {
        for (lifecycle in listOf(AppSyncOperationLifecycle.DiscardedByForcePull,
            AppSyncOperationLifecycle.DiscardedByRebootstrap, AppSyncOperationLifecycle.SupersededByRecovery)) fixture {
            val obsolete = append("invalid historical body")
            when (lifecycle) {
                AppSyncOperationLifecycle.DiscardedByForcePull -> {
                    store.replaceWithVerifiedCloudState(OperationReducer().reduce(operations = emptyList()),
                        SyncCausalContext(), emptySet(), 16) {}
                }
                AppSyncOperationLifecycle.SupersededByRecovery -> {
                    db.appSyncOperationQueries.markOperationsSupersededByRecovery(listOf(obsolete.operationId.value))
                    store.rotateDeviceEpoch(account, AppSyncInstallationState.Active)
                }
                else -> store.rotateDeviceEpoch(account, AppSyncInstallationState.Active)
            }
            val pending = append("22")
            val before = store.allOutboxOperations()
            assertEquals(lifecycle, before.first { it.first.operationId == obsolete.operationId }.second)
            val result = assertIs<AppSyncCanonicalActivationResult.Applied>(activator().activate(verified()))
            assertEquals(1, result.pendingOperationCount)
            assertEquals(22, preferences.values["novelreadersettings.fontsize"])
            val local = assertNotNull(state.read(account.value))
            assertEquals(mapOf(pending.replicaKey.stableKey to 1L), local.coverage)
            assertEquals(before, store.allOutboxOperations())
            assertEquals(listOf(pending), store.pendingOperations())
            assertFalse(store.isApplied(obsolete.operationId))
            assertTrue(store.isApplied(pending.operationId))
        }
    }

    @Test fun acknowledgedAndCompactedSourcesStillCompleteOlderCheckpointCoverage() {
        for (compacted in listOf(false, true)) fixture {
            val source = append()
            store.markAcknowledged(setOf(source.operationId), 16)
            if (compacted) store.markCompacted(setOf(source.operationId))
            val before = store.allOutboxOperations()
            val applied = assertIs<AppSyncCanonicalActivationResult.Applied>(activator().activate(verified()))
            assertEquals(0, applied.pendingOperationCount)
            assertEquals(18, preferences.values["novelreadersettings.fontsize"])
            assertEquals(mapOf(source.replicaKey.stableKey to 1L), state.read(account.value)?.coverage)
            assertEquals(before, store.allOutboxOperations())
            assertTrue(store.verifiedCheckpoints().single().coverage.asStableMap().isEmpty())
        }
    }

    @Test fun oldAccountAcknowledgedHistoryCannotEnterNewAccountActivation() = fixture {
        val historical = append()
        store.markAcknowledged(setOf(historical.operationId), 16)
        val other = SyncAccountBinding("other-account")
        store.completeBootstrap(other, OperationReducer().reduce(operations = emptyList()), SyncCausalContext(),
            emptySet(), 17, true, null) {}
        val pending = assertNotNull(recorder().record("settings", "novelreadersettings.fontsize", SyncOperationKind.Put,
            mapOf("type" to "int", "value" to "22")) {})
        val target = checkpoint.copy(accountBinding = other.value)
        val index = AppSyncIndexEnvelopeCodec().encode(AppSyncIndexPayload(other,
            checkpoints = listOf(AppSyncIndexCheckpointReference(target.checkpointId, 123,
                AppSyncCanonicalCheckpointCodec().encode(target).sha256().hex())), updatedAtEpochMillis = 18))
        val evidence = assertNotNull(AppSyncVerifiedCanonicalCheckpoint.verify(other.value, 123, index,
            AppSyncV3DocumentCodec().encodeCheckpoint(target)))
        val before = store.allOutboxOperations()
        val result = assertIs<AppSyncCanonicalActivationResult.Applied>(activator().activate(evidence))
        assertEquals(1, result.pendingOperationCount)
        assertEquals(22, preferences.values["novelreadersettings.fontsize"])
        assertEquals(mapOf(pending.replicaKey.stableKey to 1L), state.read(other.value)?.coverage)
        assertEquals(before, store.allOutboxOperations())
        assertFalse(store.isApplied(historical.operationId))
        assertEquals(AppSyncOperationLifecycle.Acknowledged,
            store.allOutboxOperations().first { it.first == historical }.second)
    }

    @Test fun pendingEditSurvivesAndRemoteCoverageNeverClaimsLocalOperations() = fixture {
        val pending = append()
        val outbox = store.allOutboxOperations()
        val result = assertIs<AppSyncCanonicalActivationResult.Applied>(activator().activate(verified()))
        assertEquals(1, result.pendingOperationCount)
        assertTrue(result.settingsReconciled)
        assertEquals(18, preferences.values["novelreadersettings.fontsize"])
        assertEquals(outbox, store.allOutboxOperations())
        assertTrue(store.isApplied(pending.operationId))
        val local = assertNotNull(state.read(account.value))
        assertTrue(local.checkpointId.startsWith("local:"))
        assertEquals(1L, local.coverage[pending.replicaKey.stableKey])
        val remote = store.verifiedCheckpoints().single()
        assertEquals("remote", remote.checkpointId)
        assertTrue(remote.coverage.asStableMap().isEmpty())
        assertEquals(verified().fingerprint, remote.payloadFingerprint)
        assertIs<AppSyncCanonicalActivationResult.Applied>(activator().activate(verified()))
        assertEquals(local, state.read(account.value))
        append("22")
        assertIs<AppSyncCanonicalActivationResult.Applied>(activator().activate(verified()))
        assertEquals(22, preferences.values["novelreadersettings.fontsize"])
        assertEquals(2, store.pendingOperations().size)
        assertNotEquals(local.checkpointId, state.read(account.value)?.checkpointId)
    }

    @Test fun failureAfterNestedAdoptionRollsBackStateReceiptsCoverageAndCheckpoint() = fixture {
        val pending = append()
        val outbox = store.allOutboxOperations()
        val beforeCoverage = store.causalContext()
        val failing = object : AppSyncOperationStore by store {
            override fun adoptCheckpoint(checkpointId: String, blogId: Long?, coverage: SyncCausalContext,
                payloadFingerprint: String, createdAtEpochMillis: Long, verifiedAtEpochMillis: Long,
                laterReduction: OperationReductionResult, domainMutation: (OperationReductionResult) -> Unit) {
                store.adoptCheckpoint(checkpointId, blogId, coverage, payloadFingerprint, createdAtEpochMillis,
                    verifiedAtEpochMillis, laterReduction, domainMutation)
                error("injected after nested transaction")
            }
        }
        assertIs<AppSyncCanonicalActivationResult.NeedsAttention>(activator(failing).activate(verified()))
        assertNull(state.read(account.value))
        assertTrue(db.appSyncOperationQueries.getSyncSettingValues().executeAsList().isEmpty())
        assertFalse(store.isApplied(pending.operationId))
        assertEquals(beforeCoverage, store.causalContext())
        assertEquals(outbox, store.allOutboxOperations())
        assertTrue(store.verifiedCheckpoints().isEmpty())
        assertTrue(preferences.values.isEmpty())
    }

    @Test fun preferenceFailureReportsCommittedStateAndRetryReconciles() = fixture {
        append()
        preferences.fail = true
        val first = assertIs<AppSyncCanonicalActivationResult.Applied>(activator().activate(verified()))
        assertFalse(first.settingsReconciled)
        val committed = assertNotNull(state.read(account.value))
        assertEquals(1, store.verifiedCheckpoints().size)
        preferences.fail = false
        assertTrue(assertIs<AppSyncCanonicalActivationResult.Applied>(activator().activate(verified())).settingsReconciled)
        assertEquals(committed, state.read(account.value))
        assertEquals(18, preferences.values["novelreadersettings.fontsize"])
        assertEquals(1, store.pendingOperations().size)
    }

    @Test fun unimportablePendingEditLeavesOriginalEvidenceUntouched() = fixture {
        append("not-an-int")
        val outbox = store.allOutboxOperations()
        assertIs<AppSyncCanonicalActivationResult.NeedsAttention>(activator().activate(verified()))
        assertEquals(outbox, store.allOutboxOperations())
        assertNull(state.read(account.value))
        assertTrue(store.verifiedCheckpoints().isEmpty())
    }

    @Test fun activationRejectsInstallationAccountMismatch() = fixture {
        store.bindAccount(SyncAccountBinding("other"), AppSyncInstallationState.Active)
        assertIs<AppSyncCanonicalActivationResult.NeedsAttention>(activator().activate(verified()))
        assertNull(db.appSyncCanonicalStateQueries.getState().executeAsOneOrNull())
        assertTrue(store.verifiedCheckpoints().isEmpty())
    }

    private fun Fixture.recorder() = AppSyncMutationRecorder(true, store,
        SqlDelightSyncDomainStateAdapter(db, materializer, nowMillis = { 30 }), { 30 }, state)

    @Test fun recorderUsesCanonicalValuesForNoOpsAndKeepsLegacyProvenanceEmpty() = fixture {
        append()
        activator().activate(verified())
        val recorder = recorder()
        val sequence = store.installation()?.nextSequence
        var callbacks = 0
        assertNull(recorder.record("settings", "novelreadersettings.fontsize", SyncOperationKind.Patch,
            mapOf("type" to "int", "value" to "018")) { callbacks++ })
        assertEquals(sequence, store.installation()?.nextSequence)
        assertEquals(1, callbacks)
        val operation = assertNotNull(recorder.record("settings", "novelreadersettings.fontsize", SyncOperationKind.Patch,
            mapOf("type" to "int", "value" to "22")) { callbacks++ })
        assertEquals(2, callbacks)
        assertEquals("22", state.read(account.value)?.entities?.single()?.values()?.values?.first { it.legacyValue() == "22" }?.legacyValue())
        assertEquals(operation.sequence.value, state.read(account.value)?.coverage?.get(operation.replicaKey.stableKey))
        assertTrue(db.appSyncOperationQueries.getResolvedEntities().executeAsList().isEmpty())
        assertEquals(2, store.pendingOperations().size)
        assertEquals(18, preferences.values["novelreadersettings.fontsize"])
    }

    @Test fun recorderBatchComparesAgainstEarlierCanonicalOperationsInSameCommand() = fixture {
        append()
        activator().activate(verified())
        fun draft(value: String) = LocalSyncOperationDraft(SyncDomainId("settings"),
            SyncEntityId("novelreadersettings.fontsize"), kind = SyncOperationKind.Patch,
            fields = mapOf("type" to "int", "value" to value))
        val created = recorder().recordBatch(listOf(draft("22"), draft("22"), draft("18"))) {}
        assertEquals(listOf("22", "18"), created.map { it.fields["value"] })
        assertEquals(listOf(2L, 3L), created.map { it.sequence.value })
        assertTrue(state.read(account.value)!!.entities.single().values().values.any { it.legacyValue() == "18" })
        assertEquals(3, store.pendingOperations().size)
    }

    @Test fun recorderImportFailureCommitsOriginalSourceAndLocalMutation() = fixture {
        append()
        activator().activate(verified())
        val previous = state.read(account.value)
        val created = assertNotNull(recorder().record("settings", "novelreadersettings.fontsize", SyncOperationKind.Patch,
            mapOf("type" to "int", "value" to "invalid")) { preferences.values["local-edit"] = "retained" })
        assertEquals("retained", preferences.values["local-edit"])
        assertEquals("invalid", store.pendingOperations().last().fields["value"])
        assertEquals(created, store.pendingOperations().last())
        assertEquals(previous, state.read(account.value))
        assertEquals(AppSyncInstallationState.Quarantined, store.installation()?.state)
    }

    @Test fun recorderOversizedNotePreservesUntruncatedSourceAndLocalEdit() = fixture {
        activator().activate(verified())
        val source = AppSyncSyntheticCorpus.create().journal.operations.first { it.domainId.value == "detail-note" }
        val content = "x".repeat(128 * 1024 + 1)
        val previous = state.read(account.value)
        val created = assertNotNull(recorder().record(source.domainId.value, source.entityId.value, SyncOperationKind.Put,
            source.fields + ("content" to content)) { preferences.values["local-note"] = content })
        assertEquals(content, preferences.values["local-note"])
        assertEquals(content, created.fields["content"])
        assertEquals(created, store.pendingOperations().single())
        assertEquals(previous, state.read(account.value))
        assertEquals(AppSyncInstallationState.Quarantined, store.installation()?.state)
    }

    @Test fun recorderCallbackFailureRollsBackOutboxAndCanonicalProvenance() = fixture {
        append()
        activator().activate(verified())
        val previous = state.read(account.value)
        val outbox = store.allOutboxOperations()
        val sequence = store.installation()?.nextSequence
        assertFailsWith<IllegalStateException> {
            recorder().record("settings", "novelreadersettings.fontsize", SyncOperationKind.Patch,
                mapOf("type" to "int", "value" to "22")) { error("local mutation failed") }
        }
        assertEquals(outbox, store.allOutboxOperations())
        assertEquals(previous, state.read(account.value))
        assertEquals(sequence, store.installation()?.nextSequence)
    }

    @Test fun failedCanonicalBatchAndLaterCommandsRetainCorrectiveEdits() = fixture {
        append()
        activator().activate(verified())
        val previous = state.read(account.value)
        fun draft(value: String) = LocalSyncOperationDraft(SyncDomainId("settings"),
            SyncEntityId("novelreadersettings.fontsize"), kind = SyncOperationKind.Patch,
            fields = mapOf("type" to "int", "value" to value))
        val recorder = recorder()
        val batch = recorder.recordBatch(listOf(draft("invalid"), draft("18"))) {}
        assertEquals(listOf("invalid", "18"), batch.map { it.fields["value"] })
        assertEquals(AppSyncInstallationState.Quarantined, store.installation()?.state)
        val correction = assertNotNull(recorder.record("settings", "novelreadersettings.fontsize", SyncOperationKind.Patch,
            mapOf("type" to "int", "value" to "18")) {})
        assertEquals(4L, correction.sequence.value)
        assertEquals(listOf("18", "invalid", "18", "18"), store.pendingOperations().map { it.fields["value"] })
        assertEquals(previous, state.read(account.value))
    }

    @Test fun quarantinedGenerationChangeDoesNotSuppressFollowingCorrection() = fixture {
        append()
        activator().activate(verified())
        val previous = state.read(account.value)
        val invalid = LocalSyncOperationDraft(SyncDomainId("settings"), SyncEntityId("novelreadersettings.fontsize"),
            entityGeneration = 2, kind = SyncOperationKind.Patch, fields = mapOf("type" to "int", "value" to "22"))
        val correction = invalid.copy(entityGeneration = 1, fields = mapOf("type" to "int", "value" to "18"))
        val operations = recorder().recordBatch(listOf(invalid, correction)) {}
        assertEquals(listOf(2L, 1L), operations.map { it.entityGeneration })
        assertEquals(listOf("22", "18"), operations.map { it.fields["value"] })
        assertEquals(3, store.pendingOperations().size)
        assertEquals(previous, state.read(account.value))
        assertEquals(AppSyncInstallationState.Quarantined, store.installation()?.state)
    }

    @Test fun canonicalRecorderImportsCompleteNineteenDomainCorpus() = fixture {
        activator().activate(verified())
        val sources = AppSyncSyntheticCorpus.create().journal.operations
        val created = recorder().recordCommand {
            sources.map { LocalSyncOperationDraft(it.domainId, it.entityId, it.entityGeneration, it.kind, it.fields,
                it.bulkDeleteAuthorizationId) }
        }
        assertEquals(AppSyncCanonicalSchema.domains.keys, created.map { it.domainId.value }.toSet())
        assertEquals(AppSyncInstallationState.Active, store.installation()?.state)
        val canonical = assertNotNull(state.read(account.value))
        assertEquals(AppSyncCanonicalSchema.domainsById.keys, canonical.entities.map { it.domainId }.toSet())
        assertEquals(created.size.toLong(), canonical.coverage[created.first().replicaKey.stableKey])
        assertEquals(created, store.pendingOperations())
        assertTrue(db.appSyncOperationQueries.getResolvedEntities().executeAsList().isEmpty())
        val merged = assertIs<AppSyncCanonicalPendingMergeResult.Ready>(AppSyncCanonicalPendingMerge().prepare(
            checkpoint, created, canonical.checkpointId, canonical.createdAtEpochMillis))
        assertEquals(canonical, merged.checkpoint)
    }

    @Test fun recorderGenerationReadsCanonicalTombstoneWithoutLegacyProjection() = fixture {
        append()
        activator().activate(verified())
        val recorder = recorder()
        recorder.record("settings", "novelreadersettings.fontsize", SyncOperationKind.Delete, emptyMap()) {}
        assertEquals(2L, recorder.currentGeneration("settings", "novelreadersettings.fontsize"))
        assertTrue(db.appSyncOperationQueries.getResolvedEntities().executeAsList().isEmpty())
    }

    @Test fun cloudResetPreparationKeepsCanonicalHeadUntilReplacementCommits() = fixture {
        append()
        activator().activate(verified())
        val previous = state.read(account.value)
        val sources = store.allOutboxOperations()
        store.prepareForCloudReset()
        assertNull(store.installation()?.accountBinding)
        assertEquals(previous, state.read(account.value))
        assertEquals(sources, store.allOutboxOperations())
        val legacy = SqlDelightSyncDomainStateAdapter(db, materializer, nowMillis = { 30 })
        store.completeBootstrap(account, OperationReducer().reduce(operations = emptyList()), SyncCausalContext(),
            emptySet(), 30, false, null) { legacy.adoptCheckpointWithinTransaction(it.entities.values) }
        assertNull(state.read(account.value))
        assertEquals(AppSyncInstallationState.Active, store.installation()?.state)
        assertTrue(db.appSyncOperationQueries.getSyncSettingValues().executeAsList().isEmpty())
    }

    @Test fun accountRebootstrapClearsSupersededCanonicalBindingAtomically() = fixture {
        append()
        activator().activate(verified())
        val other = SyncAccountBinding("another-account")
        val legacy = SqlDelightSyncDomainStateAdapter(db, materializer, nowMillis = { 30 })
        store.completeBootstrap(other, OperationReducer().reduce(operations = emptyList()), SyncCausalContext(),
            emptySet(), 30, true, null) { legacy.adoptCheckpointWithinTransaction(it.entities.values) }
        assertNull(state.read(other.value))
        assertEquals(other, store.installation()?.accountBinding)
        assertTrue(store.pendingOperations().isEmpty())
        assertEquals(AppSyncOperationLifecycle.DiscardedByRebootstrap, store.allOutboxOperations().single().second)
        assertNotNull(recorder().record("settings", "novelreadersettings.fontsize", SyncOperationKind.Put,
            mapOf("type" to "int", "value" to "22")) {})
        assertEquals(other, store.pendingOperations().single().accountBinding)
        assertEquals(1, db.appSyncOperationQueries.getResolvedEntities().executeAsList().size)
    }

    @Test fun forcePullReplacementClearsCanonicalHeadButOuterFailureRestoresIt() = fixture {
        append()
        activator().activate(verified())
        val previous = state.read(account.value)
        val before = store.allOutboxOperations()
        val installation = store.installation()
        val settings = db.appSyncOperationQueries.getSyncSettingValues().executeAsList()
        val legacy = SqlDelightSyncDomainStateAdapter(db, materializer, nowMillis = { 30 })
        fun replace() = store.replaceWithVerifiedCloudState(OperationReducer().reduce(operations = emptyList()),
            SyncCausalContext(), emptySet(), 30) { legacy.adoptCheckpointWithinTransaction(it.entities.values) }
        assertFailsWith<IllegalStateException> {
            db.transaction {
                replace()
                assertNull(state.read(account.value))
                error("injected after replacement")
            }
        }
        assertEquals(previous, state.read(account.value))
        assertEquals(before, store.allOutboxOperations())
        assertEquals(installation, store.installation())
        assertEquals(settings, db.appSyncOperationQueries.getSyncSettingValues().executeAsList())
        replace()
        assertNull(state.read(account.value))
        assertEquals(AppSyncOperationLifecycle.DiscardedByForcePull, store.allOutboxOperations().single().second)
    }

    @Test fun failedBootstrapAndWriterRotationRetainCanonicalEvidence() = fixture {
        append()
        activator().activate(verified())
        val previous = state.read(account.value)
        val before = store.installation()
        assertFailsWith<IllegalStateException> {
            store.completeBootstrap(account, OperationReducer().reduce(operations = emptyList()), SyncCausalContext(),
                emptySet(), 30, true, null) { error("materialization failed") }
        }
        assertEquals(previous, state.read(account.value))
        assertEquals(before, store.installation())
        store.rotateDeviceEpoch(account, AppSyncInstallationState.RebootstrapRequired)
        assertEquals(previous, state.read(account.value))
    }

    @Test fun localBatchRecordsProvenanceWithoutReplayingMaterializedValuesOrAcknowledgingSources() = fixture {
        append()
        assertIs<AppSyncCanonicalActivationResult.Applied>(activator().activate(verified()))
        val remote = store.verifiedCheckpoints()
        val materialized = db.appSyncOperationQueries.getSyncSettingValues().executeAsList()
        preferences.values["novelreadersettings.fontsize"] = 99
        val created = store.appendLocalCommand(account, store.causalContext(), 30, SyncOperationOrigin.UserAction,
            localMutation = { listOf(LocalSyncOperationDraft(SyncDomainId("settings"),
                SyncEntityId("novelreadersettings.fontsize"), kind = SyncOperationKind.Patch,
                fields = mapOf("type" to "int", "value" to "22"))) },
            afterOperationsCreated = {
                assertTrue(assertIs<AppSyncCanonicalLocalUpdate.Recorded>(state.recordLocalBatch(account.value, it)).changed)
            })
        val local = assertNotNull(state.read(account.value))
        assertTrue(local.entities.single().values().values.any { it.legacyValue() == "22" })
        assertEquals(2L, local.coverage[created.single().replicaKey.stableKey])
        assertEquals(materialized, db.appSyncOperationQueries.getSyncSettingValues().executeAsList())
        assertEquals(99, preferences.values["novelreadersettings.fontsize"])
        assertEquals(remote, store.verifiedCheckpoints())
        assertEquals(2, store.pendingOperations().size)
        assertFalse(assertIs<AppSyncCanonicalLocalUpdate.Recorded>(state.recordLocalBatch(account.value, created)).changed)
        assertEquals(local, state.read(account.value))
    }

    @Test fun localBatchAndOutboxRollbackTogetherAfterCanonicalWrite() = fixture {
        append()
        activator().activate(verified())
        val previous = state.read(account.value)
        val before = store.allOutboxOperations()
        val nextSequence = store.installation()?.nextSequence
        assertFailsWith<IllegalStateException> {
            store.appendLocalCommand(account, store.causalContext(), 30, SyncOperationOrigin.UserAction,
                localMutation = { listOf(LocalSyncOperationDraft(SyncDomainId("settings"),
                    SyncEntityId("novelreadersettings.fontsize"), kind = SyncOperationKind.Patch,
                    fields = mapOf("type" to "int", "value" to "24"))) },
                afterOperationsCreated = {
                    assertIs<AppSyncCanonicalLocalUpdate.Recorded>(state.recordLocalBatch(account.value, it))
                    error("injected after canonical write")
                })
        }
        assertEquals(previous, state.read(account.value))
        assertEquals(before, store.allOutboxOperations())
        assertEquals(nextSequence, store.installation()?.nextSequence)
    }

    @Test fun localImportFailureKeepsOriginalOutboxAndLastCanonicalState() = fixture {
        append()
        activator().activate(verified())
        val previous = state.read(account.value)
        val invalid = append("invalid")
        val sources = store.allOutboxOperations()
        val result = assertIs<AppSyncCanonicalLocalUpdate.NeedsAttention>(state.recordLocalBatch(account.value, listOf(invalid)))
        assertEquals(AppSyncPendingMergeFailure.ImportFailure, result.failure.reason)
        assertEquals(previous, state.read(account.value))
        assertEquals(sources, store.allOutboxOperations())
        assertEquals(2, store.pendingOperations().size)
    }

    @Test fun localRecordingRequiresActivationAndCorrectInstallationAccount() = fixture {
        val operation = append()
        assertIs<AppSyncCanonicalLocalUpdate.NotActivated>(state.recordLocalBatch(account.value, listOf(operation)))
        activator().activate(verified())
        val previous = state.read(account.value)
        store.bindAccount(SyncAccountBinding("other"), AppSyncInstallationState.Active)
        assertFailsWith<IllegalArgumentException> { state.recordLocalBatch(account.value, emptyList()) }
        assertEquals(previous, state.read(account.value))
    }

    @Test fun commandBatchingDoesNotChangeCanonicalIdentity() {
        fixture {
            activator().activate(verified())
            val first = append("18")
            val second = append("22")
            assertIs<AppSyncCanonicalLocalUpdate.Recorded>(state.recordLocalBatch(account.value, listOf(first, second)))
            val batched = assertNotNull(state.read(account.value))
            fixture {
                activator().activate(verified())
                assertIs<AppSyncCanonicalLocalUpdate.Recorded>(state.recordLocalBatch(account.value, listOf(first)))
                assertIs<AppSyncCanonicalLocalUpdate.Recorded>(state.recordLocalBatch(account.value, listOf(second)))
                assertEquals(batched, state.read(account.value))
            }
        }
    }

    @Test fun excludedLocalSequenceAdvancesCoverageWithoutCreatingAnEntity() = fixture {
        activator().activate(verified())
        val operation = store.appendLocalOperation(account, SyncDomainId("settings"), SyncEntityId("future.secret"), 1,
            SyncOperationKind.Put, mapOf("type" to "string", "value" to "private"), store.causalContext(),
            15, SyncOperationOrigin.UserAction)
        val result = assertIs<AppSyncCanonicalLocalUpdate.Recorded>(state.recordLocalBatch(account.value, listOf(operation)))
        assertEquals(1, result.excludedCount)
        val local = assertNotNull(state.read(account.value))
        assertEquals(1L, local.coverage[operation.replicaKey.stableKey])
        assertTrue(local.entities.isEmpty())
        assertEquals(listOf(operation), store.pendingOperations())
        assertTrue(store.verifiedCheckpoints().single().coverage.asStableMap().isEmpty())
    }
}

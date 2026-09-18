package me.thenano.yamibo.yamibo_app.repository.appsync

import kotlin.test.*
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.*
import me.thenano.yamibo.yamibo_app.repository.appsync.model.*
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.*
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.*
import me.thenano.yamibo.yamibo_app.repository.appsync.schema.*

class AppSyncCanonicalCloudPlannerTest {
    private val account = SyncAccountBinding("account")
    private val installation = AppSyncInstallation("db", account, SyncDeviceId("local"), SyncDeviceEpoch("epoch"),
        SyncWriterNonce("local-writer"), 1, AppSyncInstallationState.Active, null, null, null, false,
        AppSyncScheduleSettings(), 0, 0)
    private val planner = AppSyncCanonicalCloudPlanner()
    private val base = AppSyncCanonicalCheckpoint("base", account.value, 1, emptyMap(), emptyList())
    private val codec = AppSyncV3DocumentCodec()

    private fun verified(checkpoint: AppSyncCanonicalCheckpoint): AppSyncVerifiedCanonicalCheckpoint {
        val index = AppSyncIndexEnvelopeCodec().encode(AppSyncIndexPayload(account, checkpoints = listOf(
            AppSyncIndexCheckpointReference(checkpoint.checkpointId, 42, AppSyncCanonicalCheckpointCodec().encode(checkpoint).sha256().hex())),
            updatedAtEpochMillis = 1))
        return assertNotNull(AppSyncVerifiedCanonicalCheckpoint.verify(account.value, 42, index, codec.encodeCheckpoint(checkpoint)))
    }
    private fun cloud(checkpoints: List<AppSyncCanonicalCheckpoint> = listOf(base),
        canonical: List<LoadedAppSyncCanonicalDocument> = emptyList(), legacy: List<LoadedAppSyncJournal> = emptyList()): AppSyncJournalLoadResult.Success {
        val index = AppSyncIndexEnvelopeCodec().encode(AppSyncIndexPayload(account, checkpoints = checkpoints.mapIndexed { i, cp ->
            AppSyncIndexCheckpointReference(cp.checkpointId, 42 + i, AppSyncCanonicalCheckpointCodec().encode(cp).sha256().hex())
        }, updatedAtEpochMillis = 1))
        return AppSyncJournalLoadResult.Success(legacy, canonicalDocuments = canonical,
            verifiedCanonicalCheckpoints = checkpoints.mapIndexed { i, cp -> assertNotNull(
                AppSyncVerifiedCanonicalCheckpoint.verify(account.value, 42L + i, index, codec.encodeCheckpoint(cp))) })
    }
    private fun failure(cloud: AppSyncJournalLoadResult.Success) =
        assertIs<AppSyncCanonicalCloudPlan.NeedsAttention>(planner.prepare(account, installation, cloud))

    private fun operation(sequence: Long = 1): SyncOperation {
        val source = AppSyncSyntheticCorpus.create().journal.operations.first { it.domainId.value == "detail-note" }
        val device = SyncDeviceId("remote"); val epoch = SyncDeviceEpoch("epoch"); val seq = SyncSequence(sequence)
        return source.copy(accountBinding = account, deviceId = device, deviceEpoch = epoch, sequence = seq,
            operationId = SyncOperation.idFor(device, epoch, seq), causalContext = SyncCausalContext())
    }
    private fun native(operation: SyncOperation = operation(), nonce: String = "writer", published: Long = operation.sequence.value,
        remoteId: String = "10"): LoadedAppSyncCanonicalDocument {
        val imported = assertIs<AppSyncCanonicalOperationImport.Accepted>(AppSyncCanonicalOperationImporter().import(account.value, operation))
        val journal = AppSyncCanonicalJournal(AppSyncCanonicalOperationBlock(account.value, listOf(imported.operation)),
            operation.deviceId.value, operation.deviceEpoch.value, nonce, operation.sequence.value, operation.sequence.value,
            mapOf(operation.replicaKey.stableKey to operation.sequence.value), emptyList(), 1, 3, 3, "test", published)
        val text = codec.encodeJournal(operation.replicaKey.stableKey, journal)
        return LoadedAppSyncCanonicalDocument(remoteId, codec.discover(text, account.value, AppSyncV3PayloadKind.Journal))
    }

    private fun discovered(checkpoint: AppSyncCanonicalCheckpoint) = LoadedAppSyncCanonicalDocument("99",
        codec.discover(codec.encodeCheckpoint(checkpoint), account.value, AppSyncV3PayloadKind.Checkpoint))

    @Test fun unindexedCheckpointCannotHideConflictingIdentity() {
        assertIs<AppSyncCanonicalCloudPlan.Ready>(planner.prepare(account, installation,
            cloud(canonical = listOf(discovered(base)))))
        assertEquals(AppSyncCanonicalCloudFailure.CheckpointConflict,
            failure(cloud(canonical = listOf(discovered(base.copy(createdAtEpochMillis = 2))))).reason)
    }

    @Test fun unindexedCheckpointCoverageMustBeRepresentedWithoutGrantingActivationAuthority() {
        val later = assertIs<AppSyncCanonicalPendingMergeResult.Ready>(AppSyncCanonicalPendingMerge().prepare(
            base, listOf(operation()), "unindexed", 2)).checkpoint
        assertEquals(AppSyncCanonicalCloudFailure.CheckpointConflict,
            failure(cloud(canonical = listOf(discovered(later)))).reason)
        val ready = assertIs<AppSyncCanonicalCloudPlan.Ready>(planner.prepare(account, installation,
            cloud(canonical = listOf(discovered(later), native()))))
        assertEquals(base.checkpointId, ready.checkpoint.document.checkpointId)
        assertEquals(AppSyncCanonicalCloudFailure.NoIndexedCheckpoint,
            failure(cloud(emptyList(), listOf(discovered(later), native()))).reason)
    }

    @Test fun checkpointSelectionRequiresVectorDominanceWithoutSummingCounters() {
        val a = base.copy(checkpointId = "a", coverage = mapOf("a:e" to Long.MAX_VALUE, "b:e" to 1))
        val b = base.copy(checkpointId = "b", coverage = mapOf("a:e" to Long.MAX_VALUE - 1, "b:e" to 2))
        assertEquals(AppSyncCanonicalCloudFailure.CheckpointConflict, failure(cloud(listOf(a, b))).reason)
        val complete = a.copy(checkpointId = "complete", coverage = mapOf("a:e" to Long.MAX_VALUE, "b:e" to 2))
        val result = assertIs<AppSyncCanonicalCloudPlan.Ready>(planner.prepare(account, installation, cloud(listOf(a, b, complete))))
        assertEquals("complete", result.checkpoint.document.checkpointId)
    }

    @Test fun equalCoverageDivergentCheckpointContentIsRejected() {
        val original = assertIs<AppSyncCanonicalPendingMergeResult.Ready>(AppSyncCanonicalPendingMerge().prepare(
            base, listOf(operation()), "a", 1)).checkpoint
        val changed = assertIs<AppSyncCanonicalPendingMergeResult.Ready>(AppSyncCanonicalPendingMerge().prepare(
            base, listOf(operation().copy(fields = operation().fields + ("content" to "different"))), "b", 2)).checkpoint
        assertEquals(AppSyncCanonicalCloudFailure.CheckpointConflict, failure(cloud(listOf(original, changed))).reason)
        assertIs<AppSyncCanonicalCloudPlan.Ready>(planner.prepare(account, installation,
            cloud(listOf(original, original.copy(checkpointId = "duplicate", createdAtEpochMillis = 2)))))
    }

    @Test fun laterJournalCanCompleteIncomparableCheckpointBases() {
        val first = operation()
        val device = SyncDeviceId("remote-two")
        val second = first.copy(deviceId = device, operationId = SyncOperation.idFor(device, first.deviceEpoch, first.sequence),
            fields = first.fields + ("content" to "other device"))
        fun checkpoint(id: String, source: SyncOperation) = assertIs<AppSyncCanonicalPendingMergeResult.Ready>(
            AppSyncCanonicalPendingMerge().prepare(base, listOf(source), id, 1)).checkpoint
        val a = checkpoint("a", first)
        val b = checkpoint("b", second)
        val onlyB = cloud(listOf(a, b), listOf(native(second)))
        val result = assertIs<AppSyncCanonicalCloudPlan.Ready>(planner.prepare(account, installation, onlyB))
        assertEquals("a", result.checkpoint.document.checkpointId)
        assertIs<AppSyncCanonicalCloudPlan.Ready>(planner.prepare(account, installation,
            cloud(listOf(a, b), listOf(native(first), native(second, remoteId = "11")))))
        assertEquals(AppSyncCanonicalCloudFailure.CheckpointConflict,
            failure(cloud(listOf(a, b))).reason)
    }

    @Test fun nativeAndLegacyJournalCopiesMergeWithoutDroppingAnySequence() {
        val first = operation()
        val second = operation(2).copy(kind = SyncOperationKind.Patch, fields = mapOf("content" to "new"),
            causalContext = SyncCausalContext(mapOf(first.replicaKey.stableKey to 1)))
        val journal = AppSyncJournalPayload(account, first.deviceId, first.deviceEpoch, SyncWriterNonce("writer"),
            1, 1, listOf(first), SyncCausalContext(mapOf(first.replicaKey.stableKey to 1)), heartbeatAtEpochMillis = 1)
        val loaded = cloud(canonical = listOf(native(second), native(second, remoteId = "11")),
            legacy = listOf(LoadedAppSyncJournal("9", "legacy", journal))).copy(indexedReplicaKeys = setOf(first.replicaKey.stableKey))
        val result = assertIs<AppSyncCanonicalCloudPlan.Ready>(planner.prepare(account, installation, loaded))
        assertEquals(listOf(first), result.legacyOperations)
        assertEquals(1, result.canonicalOperations.operations.size)
        assertEquals(2L, result.canonicalOperations.operations.single().sequence)
    }

    @Test fun writerConflictsAndMissingPublishedSequencesAreNotPartialSuccess() {
        assertEquals(AppSyncCanonicalCloudFailure.WriterConflict,
            failure(cloud(canonical = listOf(native(), native(nonce = "other", remoteId = "11")))).reason)
        val own = installation.copy(deviceId = SyncDeviceId("remote"))
        assertEquals(AppSyncCanonicalCloudFailure.OwnWriterConflict,
            assertIs<AppSyncCanonicalCloudPlan.NeedsAttention>(planner.prepare(account, own, cloud(canonical = listOf(native())))).reason)
        assertEquals(AppSyncCanonicalCloudFailure.MissingPublishedCoverage,
            failure(cloud(canonical = listOf(native(published = 4)))).reason)
        val gap = failure(cloud(canonical = listOf(native(operation(2)))))
        assertEquals(AppSyncCanonicalCloudFailure.MergeFailure, gap.reason)
        assertEquals(AppSyncPendingMergeFailure.SequenceGap, gap.mergeFailure)
    }

    @Test fun missingIndexReferencesReadIssuesAndBudgetPreventPlanning() {
        assertEquals(AppSyncCanonicalCloudFailure.NoIndexedCheckpoint, failure(cloud(emptyList(), listOf(native()))).reason)
        assertEquals(AppSyncCanonicalCloudFailure.MissingIndexedJournal,
            failure(cloud().copy(indexedReplicaKeys = setOf("missing:epoch"))).reason)
        assertEquals(AppSyncCanonicalCloudFailure.ReadFailure,
            failure(cloud().copy(retirementDiscoveryIssues = listOf("Journal disappeared during discovery"))).reason)
        val document = native()
        assertEquals(AppSyncCanonicalCloudFailure.Budget, failure(cloud(canonical = List(1025) { document })).reason)
        assertEquals(AppSyncCanonicalCloudFailure.CheckpointConflict, failure(cloud().copy(
            verifiedCanonicalCheckpoints = listOf(verified(base), verified(base.copy(checkpointId = "other-index"))))).reason)
    }

    @Test fun mutableDocumentMetadataAndDuplicateArtifactIdentityAreRevalidated() {
        val coverage = mutableMapOf<String, Long>()
        val previouslyVerified = cloud(listOf(base.copy(coverage = coverage)))
        coverage["changed:epoch"] = 1
        // Body verification decodes its own document; mutating the original source map
        // must not alter the already verified checkpoint.
        assertIs<AppSyncCanonicalCloudPlan.Ready>(planner.prepare(account, installation, previouslyVerified))
        assertTrue(previouslyVerified.verifiedCanonicalCheckpoints.single().document.coverage.isEmpty())
        val loaded = native()
        val read = assertIs<AppSyncV3DocumentRead.Journal>(loaded.document)
        assertEquals(AppSyncCanonicalCloudFailure.InvalidDocument, failure(cloud(canonical = listOf(loaded.copy(
            document = read.copy(document = read.document.copy(heartbeatAtEpochMillis = 2)))))).reason)
        assertEquals(AppSyncCanonicalCloudFailure.InvalidDocument, failure(cloud(canonical = listOf(loaded,
            native(operation().copy(createdAtEpochMillis = 2))))).reason)
    }
}

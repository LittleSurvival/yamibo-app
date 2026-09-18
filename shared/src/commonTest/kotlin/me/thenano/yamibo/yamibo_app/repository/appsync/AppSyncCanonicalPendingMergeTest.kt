package me.thenano.yamibo.yamibo_app.repository.appsync

import kotlin.test.*
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.*
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.*
import me.thenano.yamibo.yamibo_app.repository.appsync.schema.*

class AppSyncCanonicalPendingMergeTest {
    private val corpus by lazy { AppSyncSyntheticCorpus.create() }
    private val account get() = corpus.journal.accountBinding.value
    private val planner = AppSyncCanonicalPendingMerge()
    private fun base() = AppSyncCanonicalCheckpoint("base", account, 1, emptyMap(), emptyList())
    private fun source(domain: String, seq: Long): SyncOperation {
        val device = SyncDeviceId("pending-device"); val epoch = SyncDeviceEpoch("pending-epoch")
        return corpus.journal.operations.first { it.domainId.value == domain }.copy(deviceId = device, deviceEpoch = epoch,
            sequence = SyncSequence(seq), operationId = SyncOperation.idFor(device, epoch, SyncSequence(seq)), causalContext = SyncCausalContext())
    }
    private fun ready(current: AppSyncCanonicalCheckpoint, sources: List<SyncOperation>) =
        assertIs<AppSyncCanonicalPendingMergeResult.Ready>(planner.prepare(current, sources, "next", 2))
    private fun failure(current: AppSyncCanonicalCheckpoint, sources: List<SyncOperation>) =
        assertIs<AppSyncCanonicalPendingMergeResult.NeedsAttention>(planner.prepare(current, sources, "next", 2))

    @Test fun excludedAndNoOpSequencesRemainCoveredWithoutPhantomEntities() {
        val put = source("detail-note", 1)
        val excluded = source("settings", 2).copy(entityId = SyncEntityId("future.secret.setting"))
        val noOp = source("favorite.item", 3).copy(kind = SyncOperationKind.Patch, fields = mapOf("coverUrl" to "https://example.test/cache"))
        val patch = source("detail-note", 4).copy(kind = SyncOperationKind.Patch, fields = mapOf("content" to "edited"),
            causalContext = SyncCausalContext(mapOf(put.replicaKey.stableKey to 3)))
        val sources = listOf(patch, excluded, put, noOp, put)
        val result = ready(base(), sources)
        assertEquals(mapOf(put.replicaKey.stableKey to 4L), result.checkpoint.coverage)
        assertEquals(1, result.excludedCount)
        assertEquals(1, result.noOpCount)
        assertEquals(4, result.representedSourceIds.size)
        assertEquals(1, result.checkpoint.entities.size)
        assertEquals(AppSyncCanonicalValue.Text("edited"), result.checkpoint.entities.single().values()[19])
        assertEquals(sources, listOf(patch, excluded, put, noOp, put))
        assertEquals(result.checkpoint.entities, ready(result.checkpoint, sources).checkpoint.entities)
    }

    @Test fun gapsFailuresAndCollisionsNeverReturnPartialCoverage() {
        val put = source("detail-note", 1)
        assertEquals(AppSyncPendingMergeFailure.SequenceGap, failure(base(), listOf(put, source("detail-note", 3))).reason)
        assertEquals(AppSyncPendingMergeFailure.IdentityCollision, failure(base(), listOf(put, put.copy(createdAtEpochMillis = 1))).reason)
        val oversized = source("detail-note", 2).copy(kind = SyncOperationKind.Patch, fields = mapOf("content" to "x".repeat(128 * 1024 + 1)))
        val result = failure(base(), listOf(put, oversized))
        assertEquals(AppSyncPendingMergeFailure.ImportFailure, result.reason)
        assertEquals(AppSyncCanonicalIssueReason.FieldBudget, result.importIssue?.fieldIssue?.reason)
        assertEquals(AppSyncPendingMergeFailure.AccountMismatch, failure(base(), listOf(put.copy(accountBinding = SyncAccountBinding("other")))).reason)
        assertEquals(AppSyncPendingMergeFailure.SourceBudget, failure(base(), List(100_001) { put }).reason)
    }

    @Test fun coveredWinningIdentityStillCannotChangeContent() {
        val put = source("detail-note", 1)
        val current = ready(base(), listOf(put)).checkpoint
        assertEquals(AppSyncPendingMergeFailure.IdentityCollision,
            failure(current, listOf(put.copy(fields = put.fields + ("content" to "tampered")))).reason)
        assertEquals(current.coverage, ready(current, listOf(put)).checkpoint.coverage)
    }

    @Test fun independentReplicaCoverageAdvancesOnlyItsContiguousPrefix() {
        val first = source("detail-note", 1)
        val original = corpus.journal.operations.first { it.domainId.value == "reading.time" }
        val second = original.copy(sequence = SyncSequence(1),
            operationId = SyncOperation.idFor(original.deviceId, original.deviceEpoch, SyncSequence(1)), causalContext = SyncCausalContext())
        val result = ready(base(), listOf(second, first))
        assertEquals(mapOf(first.replicaKey.stableKey to 1L, second.replicaKey.stableKey to 1L), result.checkpoint.coverage)
    }

    @Test fun emptyInstallationStillGuardsBulkDeleteAndRetainsOriginalProofCount() {
        val deletes = (1L..51L).map { sequence -> source("favorite.item", sequence).copy(
            entityId = SyncEntityId("ThreadNormal|$sequence|0"), kind = SyncOperationKind.Delete,
            fields = emptyMap(), origin = SyncOperationOrigin.UserAction) }
        assertEquals(AppSyncPendingMergeFailure.BulkDeleteAuthorization, failure(base(), deletes).reason)
        val authorized = deletes.map { it.copy(bulkDeleteAuthorizationId = "batch", fields = mapOf(
            AppSyncBulkDeleteProofFields.SCOPE to "selection", AppSyncBulkDeleteProofFields.COUNT to "100",
            AppSyncBulkDeleteProofFields.EXPIRES_AT to Long.MAX_VALUE.toString())) }
        val result = ready(base(), authorized)
        assertEquals(51, BulkDeleteGuard { null }.evaluate(authorized) { 51 }.quarantined.size)
        assertEquals(100L, result.checkpoint.authorizations.single().operationCount)
        assertEquals(51, result.checkpoint.entities.size)
        assertTrue(result.checkpoint.entities.all { it.fields.isEmpty() && it.tombstone?.fields?.isEmpty() == true })
        val changedProof = authorized.first().copy(fields = authorized.first().fields + (AppSyncBulkDeleteProofFields.SCOPE to "changed"))
        assertEquals(AppSyncPendingMergeFailure.IdentityCollision, failure(result.checkpoint, listOf(changedProof)).reason)
    }

    @Test fun representativeLegacyJournalProducesACompleteCanonicalCheckpoint() {
        val result = ready(base(), corpus.journal.operations)
        assertEquals(corpus.journal.observed.asStableMap(), result.checkpoint.coverage)
        assertEquals(AppSyncCanonicalSchema.domainsById.keys, result.checkpoint.entities.map { it.domainId }.toSet())
        assertEquals(corpus.journal.operations.size, result.representedSourceIds.size)
        val codec = AppSyncCanonicalCheckpointCodec()
        assertEquals(result.checkpoint, codec.decode(account, "next", codec.encode(result.checkpoint)))
    }
}

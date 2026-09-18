package me.thenano.yamibo.yamibo_app.repository.appsync

import kotlin.test.*
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.*
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.*
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.*
import me.thenano.yamibo.yamibo_app.repository.appsync.schema.*

class AppSyncCanonicalRecoveryPlannerTest {
    private val account = SyncAccountBinding("account")
    private val base = AppSyncCanonicalCheckpoint("base", account.value, 1, emptyMap(), emptyList())
    private val planner = AppSyncCanonicalRecoveryPlanner()
    private fun operation(device: String = "remote", sequence: Long = 1): SyncOperation {
        val source = AppSyncSyntheticCorpus.create().journal.operations.first { it.domainId.value == "detail-note" }
        val writer = SyncDeviceId(device); val epoch = SyncDeviceEpoch("epoch"); val seq = SyncSequence(sequence)
        return source.copy(accountBinding = account, deviceId = writer, deviceEpoch = epoch, sequence = seq,
            operationId = SyncOperation.idFor(writer, epoch, seq), causalContext = SyncCausalContext())
    }
    private fun checkpoint(id: String, vararg sources: SyncOperation) = assertIs<AppSyncCanonicalPendingMergeResult.Ready>(
        AppSyncCanonicalPendingMerge().prepare(base, sources.toList(), id, 2)).checkpoint
    private fun verified(cp: AppSyncCanonicalCheckpoint): AppSyncVerifiedCanonicalCheckpoint {
        val index = AppSyncIndexEnvelopeCodec().encode(AppSyncIndexPayload(SyncAccountBinding(cp.accountBinding),
            checkpoints = listOf(AppSyncIndexCheckpointReference(cp.checkpointId, 42,
                AppSyncCanonicalCheckpointCodec().encode(cp).sha256().hex())), updatedAtEpochMillis = 3))
        return assertNotNull(AppSyncVerifiedCanonicalCheckpoint.verify(cp.accountBinding, 42, index,
            AppSyncV3DocumentCodec().encodeCheckpoint(cp)))
    }
    private fun cloud(cp: AppSyncCanonicalCheckpoint, vararg journal: SyncOperation): AppSyncCanonicalCloudPlan.Ready {
        val imported = journal.map { assertIs<AppSyncCanonicalOperationImport.Accepted>(
            AppSyncCanonicalOperationImporter().import(account.value, it)).operation }
        return AppSyncCanonicalCloudPlan.Ready(verified(cp), AppSyncCanonicalOperationBlock(cp.accountBinding, imported), emptyList())
    }

    @Test fun newerCompactedCloudBaseWinsWithoutPretendingItsWinnersAreAContiguousJournal() {
        val first = operation(); val second = operation(sequence = 2).copy(fields = first.fields + ("content" to "latest"))
        val frozen = checkpoint("frozen", first)
        val latest = checkpoint("latest", first, second)
        val selected = assertIs<AppSyncCanonicalCloudPlan.Ready>(planner.prepare(verified(frozen), cloud(latest)))
        assertEquals(latest, selected.checkpoint.document)
        assertTrue(selected.canonicalOperations.operations.isEmpty())
        assertNotEquals(verified(frozen).indexFingerprint, selected.checkpoint.indexFingerprint)
    }

    @Test fun frozenPublicationCanBeNewerThanThePrePublicationCloudRead() {
        val frozen = checkpoint("frozen", operation())
        assertEquals(frozen, assertIs<AppSyncCanonicalCloudPlan.Ready>(planner.prepare(verified(frozen), cloud(base))).checkpoint.document)
    }

    @Test fun concurrentCheckpointsNeedRealJournalCoverageAndPreserveThatJournal() {
        val first = operation(); val second = operation("other")
        val frozen = checkpoint("frozen", first); val latest = checkpoint("latest", second)
        assertEquals(AppSyncCanonicalCloudFailure.MissingPublishedCoverage,
            assertIs<AppSyncCanonicalCloudPlan.NeedsAttention>(planner.prepare(verified(frozen), cloud(latest))).reason)
        val plan = cloud(latest, second)
        val result = assertIs<AppSyncCanonicalCloudPlan.Ready>(planner.prepare(verified(frozen), plan))
        assertEquals(frozen, result.checkpoint.document)
        assertEquals(plan.canonicalOperations, result.canonicalOperations)
    }

    @Test fun conflictingWinnerIdentityAndMissingWinnerAtEqualCoverageAreRejected() {
        val first = operation()
        val frozen = checkpoint("frozen", first)
        val mutated = checkpoint("latest", first.copy(fields = first.fields + ("content" to "forged")))
        assertEquals(AppSyncCanonicalCloudFailure.OperationCollision,
            assertIs<AppSyncCanonicalCloudPlan.NeedsAttention>(planner.prepare(verified(frozen), cloud(mutated))).reason)
        val lost = frozen.copy(checkpointId = "lost", entities = emptyList())
        assertEquals(AppSyncCanonicalCloudFailure.CheckpointConflict,
            assertIs<AppSyncCanonicalCloudPlan.NeedsAttention>(planner.prepare(verified(frozen), cloud(lost))).reason)
    }

    @Test fun crossAccountAndReusedCheckpointIdentityCannotEnterRecovery() {
        val frozen = checkpoint("frozen", operation())
        val other = base.copy(accountBinding = "other")
        assertEquals(AppSyncCanonicalCloudFailure.AccountMismatch,
            assertIs<AppSyncCanonicalCloudPlan.NeedsAttention>(planner.prepare(verified(frozen), cloud(other))).reason)
        assertEquals(AppSyncCanonicalCloudFailure.CheckpointConflict,
            assertIs<AppSyncCanonicalCloudPlan.NeedsAttention>(planner.prepare(verified(frozen), cloud(base.copy(checkpointId = "frozen")))).reason)
    }
}

package me.thenano.yamibo.yamibo_app.repository.appsync.engine

import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncVerifiedCanonicalCheckpoint
import me.thenano.yamibo.yamibo_app.repository.appsync.schema.*

/** Reconciles a durably published recovery checkpoint with a separately verified current
 * cloud plan. Their index versions may differ; neither checkpoint's coverage is invented
 * from its compacted winners. Journals must bridge any missing history.
 */
internal class AppSyncCanonicalRecoveryPlanner {
    fun prepare(frozen: AppSyncVerifiedCanonicalCheckpoint,
        cloud: AppSyncCanonicalCloudPlan.Ready): AppSyncCanonicalCloudPlan {
        fun attention(reason: AppSyncCanonicalCloudFailure) = AppSyncCanonicalCloudPlan.NeedsAttention(reason)
        val candidates = listOf(cloud.checkpoint, frozen)
        val account = frozen.document.accountBinding
        if (cloud.checkpoint.document.accountBinding != account || cloud.canonicalOperations.accountBinding != account ||
            cloud.legacyOperations.any { it.accountBinding.value != account })
            return attention(AppSyncCanonicalCloudFailure.AccountMismatch)
        val codec = AppSyncCanonicalCheckpointCodec()
        val identities = linkedMapOf<String, String>()
        val winners = linkedMapOf<Triple<String, String, Long>, AppSyncCanonicalOperation>()
        val proofs = linkedMapOf<String, AppSyncCanonicalDeleteProof>()
        var bytes = 0L
        for (candidate in candidates) {
            val encoded = try { codec.encode(candidate.document) }
                catch (_: Exception) { return attention(AppSyncCanonicalCloudFailure.InvalidDocument) }
            bytes += encoded.size
            if (bytes > 64L * 1024 * 1024) return attention(AppSyncCanonicalCloudFailure.Budget)
            if (encoded.sha256().hex() != candidate.fingerprint)
                return attention(AppSyncCanonicalCloudFailure.InvalidDocument)
            val old = identities.put(candidate.document.checkpointId, candidate.fingerprint)
            if (old != null && old != candidate.fingerprint)
                return attention(AppSyncCanonicalCloudFailure.CheckpointConflict)
            for (entity in candidate.document.entities) {
                for (operation in entity.fields.values + listOfNotNull(entity.relation, entity.tombstone)) {
                    val previous = winners.put(Triple(operation.deviceId, operation.deviceEpoch, operation.sequence), operation)
                    if (previous != null && previous != operation)
                        return attention(AppSyncCanonicalCloudFailure.OperationCollision)
                }
            }
            for (proof in candidate.document.authorizations) {
                val previous = proofs.put(proof.authorizationId, proof)
                if (previous != null && previous != proof) return attention(AppSyncCanonicalCloudFailure.ProofCollision)
            }
        }
        // Include the cloud journals' resulting coverage, not merely their checkpoint base.
        val cloudMerged = AppSyncCanonicalPendingMerge().prepare(cloud.checkpoint.document, cloud.legacyOperations,
            "comparison", 0, cloud.canonicalOperations)
        if (cloudMerged is AppSyncCanonicalPendingMergeResult.NeedsAttention)
            return AppSyncCanonicalCloudPlan.NeedsAttention(AppSyncCanonicalCloudFailure.MergeFailure, cloudMerged.reason)
        cloudMerged as AppSyncCanonicalPendingMergeResult.Ready
        val required = listOf(frozen.document.coverage, cloudMerged.checkpoint.coverage)
        var selected: AppSyncVerifiedCanonicalCheckpoint? = null
        var content: okio.ByteString? = null
        for (candidate in candidates) {
            val merged = AppSyncCanonicalPendingMerge().prepare(candidate.document, cloud.legacyOperations,
                "comparison", 0, cloud.canonicalOperations)
            if (merged is AppSyncCanonicalPendingMergeResult.NeedsAttention) {
                if (merged.reason == AppSyncPendingMergeFailure.IdentityCollision)
                    return AppSyncCanonicalCloudPlan.NeedsAttention(AppSyncCanonicalCloudFailure.MergeFailure, merged.reason)
                continue
            }
            merged as AppSyncCanonicalPendingMergeResult.Ready
            if (!required.all { coverage -> coverage.all { (replica, sequence) ->
                    (merged.checkpoint.coverage[replica] ?: 0L) >= sequence } }) continue
            val candidateContent = codec.encode(merged.checkpoint)
            if (content != null && content != candidateContent)
                return attention(AppSyncCanonicalCloudFailure.CheckpointConflict)
            if (selected == null) { selected = candidate; content = candidateContent }
        }
        return selected?.let { cloud.copy(checkpoint = it) }
            ?: attention(AppSyncCanonicalCloudFailure.MissingPublishedCoverage)
    }
}

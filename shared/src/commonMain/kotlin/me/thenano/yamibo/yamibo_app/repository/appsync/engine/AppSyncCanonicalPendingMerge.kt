package me.thenano.yamibo.yamibo_app.repository.appsync.engine

import me.thenano.yamibo.yamibo_app.repository.appsync.operation.*
import me.thenano.yamibo.yamibo_app.repository.appsync.schema.*

internal enum class AppSyncPendingMergeFailure {
    AccountMismatch, SourceBudget, IdentityCollision, SequenceGap, ImportFailure,
    BulkDeleteAuthorization, InvalidCanonicalState, QuarantinedOperation,
}

internal sealed interface AppSyncCanonicalPendingMergeResult {
    data class Ready(
        val checkpoint: AppSyncCanonicalCheckpoint,
        val representedSourceIds: Set<SyncOperationId>,
        val excludedCount: Int,
        val noOpCount: Int,
        val exclusions: List<AppSyncCanonicalIssue>,
        val conflicts: List<SyncConflictRecord>,
    ) : AppSyncCanonicalPendingMergeResult
    data class NeedsAttention(val reason: AppSyncPendingMergeFailure,
        val importIssue: AppSyncCanonicalOperationImport.NeedsAttention? = null) : AppSyncCanonicalPendingMergeResult
}

/** Pure preparation: source rows stay untouched until verified activation commits.
 * Coverage advances only through every source sequence, including explicit exclusions/no-ops.
 */
internal class AppSyncCanonicalPendingMerge(
    private val importer: AppSyncCanonicalOperationImporter = AppSyncCanonicalOperationImporter(),
    private val reducer: OperationReducer = OperationReducer(),
    private val bulkDeleteGuard: BulkDeleteGuard = BulkDeleteGuard { null },
) {
    fun prepare(current: AppSyncCanonicalCheckpoint, sources: List<SyncOperation>,
        checkpointId: String, createdAtEpochMillis: Long,
        canonicalBlock: AppSyncCanonicalOperationBlock? = null): AppSyncCanonicalPendingMergeResult {
        fun attention(reason: AppSyncPendingMergeFailure) = AppSyncCanonicalPendingMergeResult.NeedsAttention(reason)
        if (sources.size.toLong() + (canonicalBlock?.operations?.size ?: 0) > 100_000) return attention(AppSyncPendingMergeFailure.SourceBudget)
        if (canonicalBlock != null && canonicalBlock.accountBinding != current.accountBinding)
            return attention(AppSyncPendingMergeFailure.AccountMismatch)
        if (sources.any { it.accountBinding.value != current.accountBinding }) return attention(AppSyncPendingMergeFailure.AccountMismatch)
        val unique = linkedMapOf<SyncOperationId, SyncOperation>()
        sources.forEach { source ->
            val old = unique[source.operationId]
            if (old == null) unique[source.operationId] = source
            else if (old != source) {
                // Sanitized fallback intentionally removes cache fields and may replace raw
                // event evidence with its portable identity. Only exact canonical operation
                // and authorization equality can establish that these are the same source.
                val left = importer.import(current.accountBinding, old)
                val right = importer.import(current.accountBinding, source)
                if (left !is AppSyncCanonicalOperationImport.Accepted || right !is AppSyncCanonicalOperationImport.Accepted ||
                    left.operation != right.operation || left.proof != right.proof)
                    return attention(AppSyncPendingMergeFailure.IdentityCollision)
            }
        }
        val nativeImports = linkedMapOf<SyncOperationId, AppSyncCanonicalOperationImport.Accepted>()
        if (canonicalBlock != null) {
            try { AppSyncCanonicalOperationBlockCodec().encode(canonicalBlock) } catch (_: Exception) {
                return attention(AppSyncPendingMergeFailure.InvalidCanonicalState)
            }
            val proofs = canonicalBlock.authorizations.associateBy { it.authorizationId }
            canonicalBlock.operations.forEach { operation ->
                val proof = operation.authorizationId?.let(proofs::get)
                val view = operation.toLegacyOperationView(current.accountBinding, proof)
                val native = AppSyncCanonicalOperationImport.Accepted(operation, proof, emptyList())
                unique[view.operationId]?.let { legacy ->
                    val imported = importer.import(current.accountBinding, legacy)
                    if (imported !is AppSyncCanonicalOperationImport.Accepted ||
                        imported.operation != operation || imported.proof != proof)
                        return attention(AppSyncPendingMergeFailure.IdentityCollision)
                }
                nativeImports[view.operationId] = native
                if (view.operationId !in unique) unique[view.operationId] = view
            }
        }
        fun importSource(source: SyncOperation): AppSyncCanonicalOperationImport =
            nativeImports[source.operationId] ?: importer.import(current.accountBinding, source)
        try { AppSyncCanonicalCheckpointCodec().encode(current) } catch (_: Exception) {
            return attention(AppSyncPendingMergeFailure.InvalidCanonicalState)
        }
        val known = current.entities.flatMap { it.fields.values + listOfNotNull(it.relation, it.tombstone) }.associateBy {
            SyncOperation.idFor(SyncDeviceId(it.deviceId), SyncDeviceEpoch(it.deviceEpoch), SyncSequence(it.sequence))
        }
        val knownProofs = current.authorizations.associateBy { it.authorizationId }
        unique.values.forEach { source ->
            known[source.operationId]?.let { winner ->
                val imported = importSource(source)
                if (imported !is AppSyncCanonicalOperationImport.Accepted || imported.operation != winner ||
                    imported.proof != winner.authorizationId?.let(knownProofs::get))
                    return attention(AppSyncPendingMergeFailure.IdentityCollision)
            }
        }
        val uncovered = unique.values.filter { (current.coverage[it.replicaKey.stableKey] ?: 0) < it.sequence.value }
        val coverage = current.coverage.toMutableMap()
        uncovered.groupBy { it.replicaKey.stableKey }.forEach { (replica, operations) ->
            var previous = coverage[replica] ?: 0
            operations.sortedBy { it.sequence.value }.forEach { op ->
                if (previous == Long.MAX_VALUE || op.sequence.value != previous + 1) return attention(AppSyncPendingMergeFailure.SequenceGap)
                previous = op.sequence.value
            }
            coverage[replica] = previous
        }
        val canonical = mutableListOf<AppSyncCanonicalOperation>()
        val acceptedSources = mutableListOf<SyncOperation>()
        val proofs = linkedMapOf<String, AppSyncCanonicalDeleteProof>()
        val exclusions = mutableListOf<AppSyncCanonicalIssue>()
        var excluded = 0
        var noOp = 0
        for (source in uncovered) {
            when (val imported = importSource(source)) {
                is AppSyncCanonicalOperationImport.Accepted -> {
                    canonical += imported.operation
                    acceptedSources += source
                    exclusions += imported.exclusions
                    imported.proof?.let { proof ->
                        val old = proofs.put(proof.authorizationId, proof)
                        if (old != null && old != proof) return attention(AppSyncPendingMergeFailure.IdentityCollision)
                    }
                }
                is AppSyncCanonicalOperationImport.Excluded -> { excluded++; exclusions += imported.issue }
                is AppSyncCanonicalOperationImport.NoOp -> { noOp++; exclusions += imported.exclusions }
                is AppSyncCanonicalOperationImport.NeedsAttention -> return AppSyncCanonicalPendingMergeResult.NeedsAttention(
                    AppSyncPendingMergeFailure.ImportFailure, imported)
            }
        }
        val guarded = bulkDeleteGuard.evaluateCoveredSubset(acceptedSources) { domain ->
            val knownCount = current.entities.count { AppSyncCanonicalSchema.domainsById.getValue(it.domainId).name == domain.value &&
                it.tombstone == null && it.relation?.kind != SyncOperationKind.RelationRemove }
            // An empty/new installation must not disable the fractional bulk-delete guard.
            if (knownCount == 0) acceptedSources.count { it.domainId == domain && it.kind == SyncOperationKind.Delete } else knownCount
        }
        if (guarded.quarantined.isNotEmpty()) return attention(AppSyncPendingMergeFailure.BulkDeleteAuthorization)
        return try {
            val reduced = reducer.reduceCanonical(current, AppSyncCanonicalOperationBlock(current.accountBinding, canonical, proofs.values.toList()))
            if (reduced.quarantined.isNotEmpty()) return attention(AppSyncPendingMergeFailure.QuarantinedOperation)
            val candidate = AppSyncCanonicalCheckpoint(checkpointId, current.accountBinding, createdAtEpochMillis, coverage,
                reduced.entities, reduced.authorizations)
            AppSyncCanonicalCheckpointCodec().encode(candidate)
            AppSyncCanonicalPendingMergeResult.Ready(candidate, unique.keys.toSet(), excluded, noOp, exclusions, reduced.conflicts)
        } catch (_: Exception) { attention(AppSyncPendingMergeFailure.InvalidCanonicalState) }
    }
}

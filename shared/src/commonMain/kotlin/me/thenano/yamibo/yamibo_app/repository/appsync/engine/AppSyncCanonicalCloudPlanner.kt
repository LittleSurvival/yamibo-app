package me.thenano.yamibo.yamibo_app.repository.appsync.engine

import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncInstallation
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.*
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.*
import me.thenano.yamibo.yamibo_app.repository.appsync.schema.*

internal enum class AppSyncCanonicalCloudFailure {
    ReadFailure, Budget, AccountMismatch, NoIndexedCheckpoint, CheckpointConflict,
    MissingIndexedJournal, WriterConflict, OwnWriterConflict, InvalidDocument,
    OperationCollision, ProofCollision, MissingPublishedCoverage, MergeFailure,
}

internal sealed interface AppSyncCanonicalCloudPlan {
    data class Ready(val checkpoint: AppSyncVerifiedCanonicalCheckpoint,
        val canonicalOperations: AppSyncCanonicalOperationBlock,
        val legacyOperations: List<SyncOperation>,
        val nativeJournals: List<AppSyncV3DocumentRead.Journal> = emptyList(),
        val legacyJournals: List<AppSyncJournalPayload> = emptyList()) : AppSyncCanonicalCloudPlan
    data class NeedsAttention(val reason: AppSyncCanonicalCloudFailure,
        val mergeFailure: AppSyncPendingMergeFailure? = null) : AppSyncCanonicalCloudPlan
}

/** Validates the complete loaded cloud before activation. Pure: no receipts, acknowledgements,
 * writes or cleanup. Pending local edits must still be reread and merged in the activation
 * transaction. Vector dominance (not summed counters) selects a safe checkpoint base.
 */
internal class AppSyncCanonicalCloudPlanner {
    fun prepare(account: SyncAccountBinding, installation: AppSyncInstallation,
        cloud: AppSyncJournalLoadResult.Success): AppSyncCanonicalCloudPlan {
        fun attention(reason: AppSyncCanonicalCloudFailure) = AppSyncCanonicalCloudPlan.NeedsAttention(reason)
        if (installation.accountBinding != account) return attention(AppSyncCanonicalCloudFailure.AccountMismatch)
        if (cloud.canonicalReadIssues.isNotEmpty() || cloud.retirementDiscoveryIssues.isNotEmpty())
            return attention(AppSyncCanonicalCloudFailure.ReadFailure)
        if (cloud.canonicalDocuments.size + cloud.journals.size + cloud.checkpoints.size > 1024 ||
            cloud.verifiedCanonicalCheckpoints.size > 1024) return attention(AppSyncCanonicalCloudFailure.Budget)
        val checkpoints = cloud.verifiedCanonicalCheckpoints
        if (checkpoints.isEmpty()) return attention(AppSyncCanonicalCloudFailure.NoIndexedCheckpoint)
        if (checkpoints.any { it.document.accountBinding != account.value } ||
            cloud.checkpoints.any { it.envelope.payload.accountBinding != account } ||
            cloud.journals.any { it.payload.accountBinding != account }) return attention(AppSyncCanonicalCloudFailure.AccountMismatch)
        fun dominates(left: Map<String, Long>, right: Map<String, Long>) =
            right.all { (replica, sequence) -> (left[replica] ?: 0L) >= sequence }
        val allCoverage = checkpoints.map { it.document.coverage } + cloud.checkpoints.map { it.envelope.payload.coverage.asStableMap() } +
            cloud.canonicalDocuments.mapNotNull { (it.document as? AppSyncV3DocumentRead.Checkpoint)?.document?.coverage }
        if (checkpoints.map { it.indexFingerprint }.distinct().size != 1)
            return attention(AppSyncCanonicalCloudFailure.CheckpointConflict)
        val codec = AppSyncCanonicalCheckpointCodec()
        val checkpointFingerprints = linkedMapOf<String, String>()
        var verifiedBytes = 0L
        for (checkpoint in checkpoints) {
            val bytes = try { codec.encode(checkpoint.document) }
                catch (_: Exception) { return attention(AppSyncCanonicalCloudFailure.InvalidDocument) }
            if (bytes.sha256().hex() != checkpoint.fingerprint) return attention(AppSyncCanonicalCloudFailure.InvalidDocument)
            val previous = checkpointFingerprints.put(checkpoint.document.checkpointId, checkpoint.fingerprint)
            if (previous != null && previous != checkpoint.fingerprint) return attention(AppSyncCanonicalCloudFailure.CheckpointConflict)
            verifiedBytes += bytes.size
            if (verifiedBytes > 64L * 1024 * 1024) return attention(AppSyncCanonicalCloudFailure.Budget)
        }
        val discoveredBytes = cloud.canonicalDocuments.sumOf {
            when (val read = it.document) {
                is AppSyncV3DocumentRead.Checkpoint -> read.metadata.uncompressedLength.toLong().coerceAtLeast(0)
                is AppSyncV3DocumentRead.Journal -> read.metadata.uncompressedLength.toLong().coerceAtLeast(0)
                else -> 0L
            }
        }
        if (verifiedBytes + discoveredBytes > 64L * 1024 * 1024) return attention(AppSyncCanonicalCloudFailure.Budget)

        val owners = linkedMapOf<String, String>()
        val published = linkedMapOf<String, Long>()
        val ownReplica = "${installation.deviceId.value}:${installation.deviceEpoch.value}"
        fun observe(replica: String, nonce: String, through: Long): AppSyncCanonicalCloudFailure? {
            if (replica == ownReplica && nonce != installation.writerNonce.value) return AppSyncCanonicalCloudFailure.OwnWriterConflict
            val previous = owners.put(replica, nonce)
            if (previous != null && previous != nonce) return AppSyncCanonicalCloudFailure.WriterConflict
            published[replica] = maxOf(published[replica] ?: 0L, through)
            return null
        }
        val legacyCount = cloud.journals.sumOf { it.payload.operations.size.toLong() }
        val nativeCount = cloud.canonicalDocuments.sumOf {
            (it.document as? AppSyncV3DocumentRead.Journal)?.document?.block?.operations?.size?.toLong() ?: 0L
        }
        if (legacyCount + nativeCount > 100_000) return attention(AppSyncCanonicalCloudFailure.Budget)
        val legacy = cloud.journals.flatMap { it.payload.operations }
        for (loaded in cloud.journals) {
            val journal = loaded.payload
            if (journal.protocolWriteVersion > 3 || AppSyncJournalEnvelopeCodec().validatePayload(journal) != null)
                return attention(AppSyncCanonicalCloudFailure.InvalidDocument)
            val replica = "${journal.deviceId.value}:${journal.deviceEpoch.value}"
            observe(replica, journal.writerNonce.value, maxOf(journal.lastSequence,
                journal.publishedThroughSequence ?: 0L, journal.observed.asStableMap()[replica] ?: 0L))?.let { return attention(it) }
        }
        val operations = linkedMapOf<SyncOperationId, AppSyncCanonicalOperation>()
        val proofs = linkedMapOf<String, AppSyncCanonicalDeleteProof>()
        val artifactFingerprints = linkedMapOf<String, String>()
        for (loaded in cloud.canonicalDocuments) {
            val read = loaded.document
            val metadata = when (read) {
                is AppSyncV3DocumentRead.Journal -> read.metadata
                is AppSyncV3DocumentRead.Checkpoint -> read.metadata
                else -> return attention(AppSyncCanonicalCloudFailure.InvalidDocument)
            }
            if (metadata.accountBinding != account.value) return attention(AppSyncCanonicalCloudFailure.AccountMismatch)
            if (metadata.schemaVersion != 3 || metadata.codecVersion != 1 || metadata.compressorId != 1 ||
                (read is AppSyncV3DocumentRead.Journal && metadata.kind != AppSyncV3PayloadKind.Journal) ||
                (read is AppSyncV3DocumentRead.Checkpoint && (metadata.kind != AppSyncV3PayloadKind.Checkpoint ||
                    metadata.identity != read.document.checkpointId || read.document.accountBinding != account.value)))
                return attention(AppSyncCanonicalCloudFailure.InvalidDocument)
            val bytes = try {
                when (read) {
                    is AppSyncV3DocumentRead.Journal -> AppSyncCanonicalJournalCodec().encode(read.document)
                    is AppSyncV3DocumentRead.Checkpoint -> codec.encode(read.document)
                }
            } catch (_: Exception) { return attention(AppSyncCanonicalCloudFailure.InvalidDocument) }
            if (bytes.size != metadata.uncompressedLength || bytes.sha256().hex() != metadata.canonicalFingerprint)
                return attention(AppSyncCanonicalCloudFailure.InvalidDocument)
            val oldFingerprint = artifactFingerprints.put(loaded.remoteId, metadata.canonicalFingerprint)
            if (oldFingerprint != null && oldFingerprint != metadata.canonicalFingerprint)
                return attention(AppSyncCanonicalCloudFailure.InvalidDocument)
            if (read is AppSyncV3DocumentRead.Checkpoint) {
                val previous = checkpointFingerprints.put(read.document.checkpointId, metadata.canonicalFingerprint)
                if (previous != null && previous != metadata.canonicalFingerprint)
                    return attention(AppSyncCanonicalCloudFailure.CheckpointConflict)
                continue
            }
            if (read !is AppSyncV3DocumentRead.Journal) continue
            val journal = read.document
            val replica = "${journal.deviceId}:${journal.deviceEpoch}"
            if (journal.block.accountBinding != account.value || metadata.identity != replica)
                return attention(AppSyncCanonicalCloudFailure.AccountMismatch)
            observe(replica, journal.writerNonce, maxOf(journal.lastSequence,
                journal.publishedThroughSequence ?: 0L, journal.observed[replica] ?: 0L))?.let { return attention(it) }
            for (operation in journal.block.operations) {
                val id = SyncOperation.idFor(SyncDeviceId(operation.deviceId), SyncDeviceEpoch(operation.deviceEpoch), SyncSequence(operation.sequence))
                val old = operations.put(id, operation)
                if (old != null && old != operation) return attention(AppSyncCanonicalCloudFailure.OperationCollision)
            }
            for (proof in journal.block.authorizations) {
                val old = proofs.put(proof.authorizationId, proof)
                if (old != null && old != proof) return attention(AppSyncCanonicalCloudFailure.ProofCollision)
            }
        }
        if (!owners.keys.containsAll(cloud.indexedReplicaKeys)) return attention(AppSyncCanonicalCloudFailure.MissingIndexedJournal)
        val block = AppSyncCanonicalOperationBlock(account.value, operations.values.toList(), proofs.values.toList())
        var selected: AppSyncVerifiedCanonicalCheckpoint? = null
        var selectedContent: okio.ByteString? = null
        var coverageConflict = false
        var publishedGap = false
        var mergeFailure: AppSyncPendingMergeFailure? = null
        // Concurrent checkpoints can be completed by later journals. Try each base, require
        // complete coverage, and compare resulting content before choosing a deterministic base.
        for (candidate in checkpoints.sortedWith(compareByDescending<AppSyncVerifiedCanonicalCheckpoint> {
            it.document.createdAtEpochMillis }.thenByDescending { it.document.checkpointId })) {
            val merged = AppSyncCanonicalPendingMerge().prepare(candidate.document, legacy,
                candidate.document.checkpointId, candidate.document.createdAtEpochMillis, block)
            if (merged is AppSyncCanonicalPendingMergeResult.NeedsAttention) {
                if (merged.reason == AppSyncPendingMergeFailure.IdentityCollision)
                    return AppSyncCanonicalCloudPlan.NeedsAttention(AppSyncCanonicalCloudFailure.MergeFailure, merged.reason)
                mergeFailure = merged.reason
                continue
            }
            merged as AppSyncCanonicalPendingMergeResult.Ready
            if (!allCoverage.all { dominates(merged.checkpoint.coverage, it) }) { coverageConflict = true; continue }
            if (!dominates(merged.checkpoint.coverage, published)) { publishedGap = true; continue }
            val content = codec.encode(merged.checkpoint.copy(checkpointId = "comparison", createdAtEpochMillis = 0))
            if (selectedContent != null && content != selectedContent) return attention(AppSyncCanonicalCloudFailure.CheckpointConflict)
            if (selected == null) { selected = candidate; selectedContent = content }
        }
        if (selected == null) return when {
            publishedGap -> attention(AppSyncCanonicalCloudFailure.MissingPublishedCoverage)
            coverageConflict -> attention(AppSyncCanonicalCloudFailure.CheckpointConflict)
            else -> AppSyncCanonicalCloudPlan.NeedsAttention(AppSyncCanonicalCloudFailure.MergeFailure, mergeFailure)
        }
        return AppSyncCanonicalCloudPlan.Ready(selected, block, legacy,
            cloud.canonicalDocuments.mapNotNull { it.document as? AppSyncV3DocumentRead.Journal },
            cloud.journals.map { it.payload })
    }
}

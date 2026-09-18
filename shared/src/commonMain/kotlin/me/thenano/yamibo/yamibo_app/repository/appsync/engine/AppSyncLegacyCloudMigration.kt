package me.thenano.yamibo.yamibo_app.repository.appsync.engine

import me.thenano.yamibo.yamibo_app.repository.appsync.model.*
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.*
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.*
import me.thenano.yamibo.yamibo_app.repository.appsync.schema.*

internal enum class AppSyncLegacyCloudFailure {
    Discovery, Budget, Account, NativeCloud, Source, Writer, MissingJournal,
    CheckpointConflict, CloudCoverage, CloudMerge, Pending,
}
internal sealed interface AppSyncLegacyCloudMigrationResult {
    data class Ready(val checkpoint: AppSyncCanonicalCheckpoint,
        val source: AppSyncVerifiedLegacyCheckpoint,
        val representedPendingIds: Set<SyncOperationId>) : AppSyncLegacyCloudMigrationResult
    data class NeedsAttention(val reason: AppSyncLegacyCloudFailure) : AppSyncLegacyCloudMigrationResult
}

/** Pure first-publication preparation. The result is not canonical index evidence and must
 * not activate, acknowledge or clean anything until native publication and readback succeed.
 * Callers must obtain pending sources inside the transaction that freezes the publication.
 */
internal class AppSyncLegacyCloudMigration {
    fun prepare(account: SyncAccountBinding, installation: AppSyncInstallation,
        cloud: AppSyncJournalLoadResult.Success, pending: List<SyncOperation>,
        checkpointId: String, createdAtEpochMillis: Long): AppSyncLegacyCloudMigrationResult {
        fun fail(reason: AppSyncLegacyCloudFailure) = AppSyncLegacyCloudMigrationResult.NeedsAttention(reason)
        if (!cloud.authoritativeDiscovery || cloud.retirementDiscoveryIssues.isNotEmpty() || cloud.canonicalReadIssues.isNotEmpty())
            return fail(AppSyncLegacyCloudFailure.Discovery)
        if (cloud.requiresCanonicalProcessing) return fail(AppSyncLegacyCloudFailure.NativeCloud)
        if (installation.accountBinding != account || installation.state != AppSyncInstallationState.Active ||
            cloud.checkpoints.any { it.envelope.payload.accountBinding != account } ||
            cloud.journals.any { it.payload.accountBinding != account } ||
            cloud.verifiedLegacyCheckpoints.any { it.account != account.value }) return fail(AppSyncLegacyCloudFailure.Account)
        if (cloud.checkpoints.size + cloud.journals.size + cloud.verifiedLegacyCheckpoints.size > 1024 ||
            cloud.journals.sumOf { it.payload.operations.size.toLong() } + pending.size > 100_000)
            return fail(AppSyncLegacyCloudFailure.Budget)
        val sources = cloud.verifiedLegacyCheckpoints
        if (sources.isEmpty()) return fail(AppSyncLegacyCloudFailure.Source)
        if (sources.map { it.indexFingerprint }.distinct().size != 1) return fail(AppSyncLegacyCloudFailure.CheckpointConflict)
        val own = SyncReplicaKey(installation.deviceId, installation.deviceEpoch).stableKey
        val owners = linkedMapOf<String, SyncWriterNonce>()
        val required = linkedMapOf<String, Long>()
        fun requireCoverage(coverage: Map<String, Long>) = coverage.forEach { (key, value) ->
            required[key] = maxOf(required[key] ?: 0, value)
        }
        cloud.checkpoints.forEach { requireCoverage(it.envelope.payload.coverage.asStableMap()) }
        val fingerprints = linkedMapOf<String, String>()
        for (loaded in cloud.checkpoints) {
            val previous = fingerprints.put(loaded.envelope.payload.checkpointId, loaded.envelope.fingerprint)
            if (previous != null && previous != loaded.envelope.fingerprint) return fail(AppSyncLegacyCloudFailure.CheckpointConflict)
        }
        for (loaded in cloud.journals) {
            val journal = loaded.payload
            if (journal.protocolWriteVersion > 2 || AppSyncJournalEnvelopeCodec().validatePayload(journal) != null)
                return fail(AppSyncLegacyCloudFailure.Source)
            val key = SyncReplicaKey(journal.deviceId, journal.deviceEpoch).stableKey
            val previous = owners.put(key, journal.writerNonce)
            if ((previous != null && previous != journal.writerNonce) ||
                (key == own && journal.writerNonce != installation.writerNonce)) return fail(AppSyncLegacyCloudFailure.Writer)
            requireCoverage(journal.observed.asStableMap())
            requireCoverage(mapOf(key to maxOf(journal.lastSequence, journal.publishedThroughSequence ?: 0)))
            journal.checkpointAcknowledgements.forEach { requireCoverage(it.coverage.asStableMap()) }
            journal.operations.forEach { requireCoverage(it.causalContext.asStableMap()) }
        }
        if (!owners.keys.containsAll(cloud.indexedReplicaKeys)) return fail(AppSyncLegacyCloudFailure.MissingJournal)
        if ((required[own] ?: 0) >= installation.nextSequence) return fail(AppSyncLegacyCloudFailure.Writer)
        if (pending.any { it.accountBinding != account || it.replicaKey.stableKey != own || it.sequence.value >= installation.nextSequence })
            return fail(AppSyncLegacyCloudFailure.Pending)
        val operations = cloud.journals.flatMap { it.payload.operations }
        val codec = AppSyncCanonicalCheckpointCodec()
        var selected: AppSyncVerifiedLegacyCheckpoint? = null
        var selectedState: AppSyncCanonicalCheckpoint? = null
        var content: okio.ByteString? = null
        var bytes = 0L
        for (source in sources.sortedBy { it.blogId }) {
            val imported = AppSyncLegacyCheckpointMigration().prepare(source)
            if (imported !is AppSyncLegacyCheckpointMigrationResult.Ready) return fail(AppSyncLegacyCloudFailure.Source)
            if (fingerprints[imported.checkpoint.checkpointId] != source.fingerprint ||
                cloud.checkpoints.none { it.remoteId == source.blogId.toString() && it.envelope.fingerprint == source.fingerprint })
                return fail(AppSyncLegacyCloudFailure.Source)
            bytes += codec.encode(imported.checkpoint).size
            if (bytes > 64L * 1024 * 1024) return fail(AppSyncLegacyCloudFailure.Budget)
            val merged = AppSyncCanonicalPendingMerge().prepare(imported.checkpoint, operations, checkpointId, createdAtEpochMillis)
            if (merged is AppSyncCanonicalPendingMergeResult.NeedsAttention) {
                if (merged.reason == AppSyncPendingMergeFailure.IdentityCollision) return fail(AppSyncLegacyCloudFailure.CloudMerge)
                continue
            }
            merged as AppSyncCanonicalPendingMergeResult.Ready
            if (!required.all { (key, value) -> (merged.checkpoint.coverage[key] ?: 0) >= value }) continue
            val encoded = codec.encode(merged.checkpoint)
            if (content != null && content != encoded) return fail(AppSyncLegacyCloudFailure.CheckpointConflict)
            if (selected == null) { selected = source; selectedState = merged.checkpoint; content = encoded }
        }
        val base = selectedState ?: return fail(AppSyncLegacyCloudFailure.CloudCoverage)
        val withPending = AppSyncCanonicalPendingMerge().prepare(base, pending, checkpointId, createdAtEpochMillis)
        if (withPending !is AppSyncCanonicalPendingMergeResult.Ready) return fail(AppSyncLegacyCloudFailure.Pending)
        return AppSyncLegacyCloudMigrationResult.Ready(withPending.checkpoint, requireNotNull(selected), withPending.representedSourceIds)
    }
}

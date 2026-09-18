package me.thenano.yamibo.yamibo_app.repository.appsync.engine

import me.thenano.yamibo.yamibo_app.repository.appsync.operation.*
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncVerifiedLegacyCheckpoint
import me.thenano.yamibo.yamibo_app.repository.appsync.schema.*

internal enum class AppSyncLegacyCheckpointFailure { Source, History, Projection, Snapshot }
internal sealed interface AppSyncLegacyCheckpointMigrationResult {
    data class Ready(val checkpoint: AppSyncCanonicalCheckpoint, val sourceFingerprint: String,
        val sourceIndexFingerprint: String) : AppSyncLegacyCheckpointMigrationResult
    data class NeedsAttention(val reason: AppSyncLegacyCheckpointFailure) : AppSyncLegacyCheckpointMigrationResult
}

/** A migration candidate, not an index-verified canonical checkpoint. No writes or cleanup.
 * Both legacy representations must agree on all portable live entities and values.
 */
internal class AppSyncLegacyCheckpointMigration {
    fun prepare(verified: AppSyncVerifiedLegacyCheckpoint, history: List<SyncOperation> = emptyList()): AppSyncLegacyCheckpointMigrationResult {
        var failure = AppSyncLegacyCheckpointFailure.Source
        return try {
            val source = verified.read()
            val payload = source.payload
            val coverage = payload.coverage.asStableMap()
            val checkpoint = if (payload.resolvedEntities.isEmpty() && coverage.values.any { it > 0 }) {
                failure = AppSyncLegacyCheckpointFailure.History
                require(history.size <= 100_000 && history.all { it.accountBinding.value == verified.account })
                val covered = history.filter { payload.coverage.includes(it) }
                require(covered.all { operation -> operation.causalContext.asStableMap().all { (replica, sequence) ->
                    (coverage[replica] ?: 0) >= sequence
                } })
                // Reconstruct from real, contiguous source operations. No synthetic writer,
                // timestamps or provenance may be inferred from the snapshot's display data.
                val rebuilt = AppSyncCanonicalPendingMerge().prepare(
                    AppSyncCanonicalCheckpoint(payload.checkpointId, verified.account, payload.createdAtEpochMillis, emptyMap(), emptyList()),
                    covered, payload.checkpointId, payload.createdAtEpochMillis)
                require(rebuilt is AppSyncCanonicalPendingMergeResult.Ready)
                require(rebuilt.checkpoint.coverage.filterValues { it > 0 } == coverage.filterValues { it > 0 })
                rebuilt.checkpoint.copy(coverage = coverage)
            } else {
                failure = AppSyncLegacyCheckpointFailure.Projection
                val imported = AppSyncCanonicalProjectionImporter().prepare(verified.account, payload.checkpointId,
                    payload.createdAtEpochMillis, coverage, payload.resolvedEntities)
                require(imported is AppSyncProjectionImportResult.Ready)
                imported.checkpoint
            }
            failure = AppSyncLegacyCheckpointFailure.Projection
            val tombstones = checkpoint.entities.associate {
                Triple(it.domainId, it.entityId, it.generation) to it.tombstone
            }
            require(payload.tombstones.all { tombstone ->
                val domain = AppSyncCanonicalSchema.domains[tombstone.domainId.value]?.id
                val source = domain?.let { tombstones[Triple(it, tombstone.entityId.value, tombstone.entityGeneration)] }
                source != null && SyncOperation.idFor(SyncDeviceId(source.deviceId), SyncDeviceEpoch(source.deviceEpoch),
                    SyncSequence(source.sequence)) == tombstone.operationId
            })
            failure = AppSyncLegacyCheckpointFailure.Snapshot
            require(AppSyncCanonicalSnapshotValidation.matches(checkpoint, source.snapshot))
            AppSyncLegacyCheckpointMigrationResult.Ready(checkpoint, verified.fingerprint, verified.indexFingerprint)
        } catch (_: Exception) { AppSyncLegacyCheckpointMigrationResult.NeedsAttention(failure) }
    }
}

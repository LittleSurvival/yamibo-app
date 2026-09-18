package me.thenano.yamibo.yamibo_app.repository.appsync.engine

import me.thenano.yamibo.yamibo_app.repository.appsync.operation.*
import me.thenano.yamibo.yamibo_app.repository.appsync.schema.*

internal enum class AppSyncV2ProjectionExportFailure { InvalidCheckpoint, OperationExport, SemanticChange }
internal sealed interface AppSyncV2ProjectionExport {
    data class Ready(val entities: List<ResolvedSyncEntity>) : AppSyncV2ProjectionExport
    data class NeedsAttention(val reason: AppSyncV2ProjectionExportFailure) : AppSyncV2ProjectionExport
}

/** Reconstructs field provenance for sanitized checkpoint fallback, never a new operation
 * stream. Compacted winners are not a contiguous journal and cannot authorize publication.
 */
internal class AppSyncSanitizedV2ProjectionExporter {
    fun export(checkpoint: AppSyncCanonicalCheckpoint): AppSyncV2ProjectionExport {
        val codec = AppSyncCanonicalCheckpointCodec()
        val expected = try { codec.encode(checkpoint) }
        catch (_: Exception) { return attention(AppSyncV2ProjectionExportFailure.InvalidCheckpoint) }
        val winners = checkpoint.entities.flatMap { it.fields.values + listOfNotNull(it.relation, it.tombstone) }
            .distinctBy { Triple(it.deviceId, it.deviceEpoch, it.sequence) }
        val exported = AppSyncSanitizedV2OperationExporter().export(AppSyncCanonicalOperationBlock(
            checkpoint.accountBinding, winners, checkpoint.authorizations), allowPortableEventIdentity = true)
        if (exported !is AppSyncV2OperationExport.Ready) return attention(AppSyncV2ProjectionExportFailure.OperationExport)
        val sources = exported.operations.associateBy { Triple(it.deviceId.value, it.deviceEpoch.value, it.sequence.value) }
        fun source(operation: AppSyncCanonicalOperation): SyncOperation =
            sources.getValue(Triple(operation.deviceId, operation.deviceEpoch, operation.sequence))
        return try {
            val entities = checkpoint.entities.sortedWith(compareBy({ it.domainId }, { it.entityId }, { it.generation })).map { entity ->
                val domain = AppSyncCanonicalSchema.domainsById.getValue(entity.domainId)
                ResolvedSyncEntity(SyncEntityKey(SyncDomainId(domain.name), SyncEntityId(entity.entityId), entity.generation),
                    fields = entity.fields.entries.sortedBy { it.key }.associate { (id, winner) ->
                        val name = domain.fieldsById.getValue(id).name
                        val operation = source(winner)
                        name to ResolvedSyncField(operation.fields.getValue(name), operation)
                    }, relationPresent = entity.relation?.let { it.kind == SyncOperationKind.RelationAdd },
                    relationOperation = entity.relation?.let(::source), tombstone = entity.tombstone?.let(::source))
            }
            val imported = AppSyncCanonicalProjectionImporter().prepare(checkpoint.accountBinding, checkpoint.checkpointId,
                checkpoint.createdAtEpochMillis, checkpoint.coverage, entities)
            if (imported !is AppSyncProjectionImportResult.Ready || codec.encode(imported.checkpoint) != expected)
                attention(AppSyncV2ProjectionExportFailure.SemanticChange)
            else AppSyncV2ProjectionExport.Ready(entities)
        } catch (_: Exception) { attention(AppSyncV2ProjectionExportFailure.SemanticChange) }
    }

    private fun attention(reason: AppSyncV2ProjectionExportFailure) = AppSyncV2ProjectionExport.NeedsAttention(reason)
}

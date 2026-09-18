package me.thenano.yamibo.yamibo_app.repository.appsync.engine

import me.thenano.yamibo.yamibo_app.repository.appsync.operation.*
import me.thenano.yamibo.yamibo_app.repository.appsync.schema.*

internal enum class AppSyncProjectionImportFailure { Budget, Account, Coverage, Provenance, SourceImport, Projection }
internal sealed interface AppSyncProjectionImportResult {
    data class Ready(val checkpoint: AppSyncCanonicalCheckpoint, val excludedEntities: Int,
        val excludedFields: Int) : AppSyncProjectionImportResult
    data class NeedsAttention(val reason: AppSyncProjectionImportFailure) : AppSyncProjectionImportResult
}

/** Converts legacy field winners without replaying their full source bodies. This is pure
 * preparation, not remote checkpoint verification or authorization to delete legacy sources.
 */
internal class AppSyncCanonicalProjectionImporter {
    fun prepare(account: String, checkpointId: String, createdAt: Long, coverage: Map<String, Long>,
        entities: List<ResolvedSyncEntity>): AppSyncProjectionImportResult {
        var failure = AppSyncProjectionImportFailure.Budget
        return try {
            require(entities.size <= 100_000 && coverage.size <= 100_000)
            require(entities.sumOf { it.fields.size.toLong() + (if (it.relationOperation != null) 1 else 0) +
                (if (it.tombstone != null) 1 else 0) } <= 250_000)
            failure = AppSyncProjectionImportFailure.Provenance
            require(entities.map { it.key.domainId to it.key.entityId }.distinct().size == entities.size)
            val sources = linkedMapOf<SyncOperationId, SyncOperation>()
            val imports = linkedMapOf<SyncOperationId, AppSyncCanonicalOperationImport>()
            val proofs = linkedMapOf<String, AppSyncCanonicalDeleteProof>()
            val importer = AppSyncCanonicalOperationImporter()
            var bytes = 0L
            fun source(key: SyncEntityKey, operation: SyncOperation): AppSyncCanonicalOperationImport {
                failure = AppSyncProjectionImportFailure.Account
                require(operation.accountBinding.value == account)
                failure = AppSyncProjectionImportFailure.Coverage
                require((coverage[operation.replicaKey.stableKey] ?: 0) >= operation.sequence.value &&
                    operation.causalContext.asStableMap().all { (replica, sequence) -> (coverage[replica] ?: 0L) >= sequence })
                failure = AppSyncProjectionImportFailure.Provenance
                require(key == SyncEntityKey(operation.domainId, operation.entityId, operation.entityGeneration))
                require(operation.operationId == SyncOperation.idFor(operation.deviceId, operation.deviceEpoch, operation.sequence))
                val previous = sources.put(operation.operationId, operation)
                require(previous == null || previous == operation)
                imports[operation.operationId]?.let { return it }
                failure = AppSyncProjectionImportFailure.Budget
                require(sources.size <= 100_000)
                bytes += operation.fields.entries.sumOf { (name, value) -> name.encodeToByteArray().size.toLong() +
                    (value?.encodeToByteArray()?.size ?: 0) }
                require(bytes <= 64L * 1024 * 1024)
                val imported = importer.import(account, operation)
                failure = AppSyncProjectionImportFailure.SourceImport
                require(imported !is AppSyncCanonicalOperationImport.NeedsAttention)
                if (imported is AppSyncCanonicalOperationImport.Accepted) imported.proof?.let { proof ->
                    val old = proofs.put(proof.authorizationId, proof)
                    require(old == null || old == proof)
                }
                imports[operation.operationId] = imported
                return imported
            }
            var excludedEntities = 0
            var excludedFields = 0
            val projections = entities.mapNotNull { entity ->
                val winners = entity.fields.values.map { it.operation } + listOfNotNull(entity.relationOperation, entity.tombstone)
                winners.forEach { source(entity.key, it) }
                val domain = AppSyncCanonicalSchema.domains[entity.key.domainId.value]
                if (domain == null || (domain.id == 1 && entity.key.entityId.value.lowercase() !in AppSyncCanonicalSettings.entries)) {
                    excludedEntities++
                    return@mapNotNull null
                }
                failure = AppSyncProjectionImportFailure.Provenance
                require(entity.key.generation > 0)
                require((entity.relationOperation == null) == (entity.relationPresent == null))
                val relation = entity.relationOperation?.let {
                    require(it.kind in setOf(SyncOperationKind.RelationAdd, SyncOperationKind.RelationRemove))
                    require(entity.relationPresent == (it.kind == SyncOperationKind.RelationAdd))
                    (imports[it.operationId] as? AppSyncCanonicalOperationImport.Accepted)?.operation ?: error("Missing relation")
                }
                val tombstone = entity.tombstone?.let {
                    require(it.kind == SyncOperationKind.Delete && entity.fields.isEmpty() && relation == null)
                    (imports[it.operationId] as? AppSyncCanonicalOperationImport.Accepted)?.operation ?: error("Missing tombstone")
                }
                val fields = linkedMapOf<Int, AppSyncCanonicalOperation>()
                entity.fields.forEach { (name, field) ->
                    failure = AppSyncProjectionImportFailure.Provenance
                    require(name in field.operation.fields && equivalentAppSyncLegacyFieldValues(domain.name,
                        entity.key.entityId.value, name, field.value, field.operation.fields[name]))
                    val id = domain.fields[name]?.id
                    val operation = (imports[field.operation.operationId] as? AppSyncCanonicalOperationImport.Accepted)?.operation
                    if (id == null || operation == null || id !in operation.fields || relation?.kind == SyncOperationKind.RelationRemove)
                        excludedFields++
                    else fields[id] = operation
                }
                AppSyncCanonicalProjection(domain.id, AppSyncCanonicalEntityKeys.parse(domain.id, entity.key.entityId.value).legacyIdentity(),
                    entity.key.generation, fields, relation, tombstone)
            }
            failure = AppSyncProjectionImportFailure.Projection
            val usedProofs = projections.flatMap { it.fields.values + listOfNotNull(it.relation, it.tombstone) }
                .mapNotNull { it.authorizationId }.toSet()
            val checkpoint = AppSyncCanonicalCheckpoint(checkpointId, account, createdAt, coverage, projections,
                proofs.values.filter { it.authorizationId in usedProofs })
            val codec = AppSyncCanonicalCheckpointCodec()
            val canonical = codec.decode(account, checkpointId, codec.encode(checkpoint))
            val validated = OperationReducer().reduceCanonical(canonical, AppSyncCanonicalOperationBlock(account, emptyList()))
            require(validated.quarantined.isEmpty() && validated.entities == canonical.entities)
            AppSyncProjectionImportResult.Ready(canonical, excludedEntities, excludedFields)
        } catch (_: Exception) { AppSyncProjectionImportResult.NeedsAttention(failure) }
    }
}

package me.thenano.yamibo.yamibo_app.repository.appsync.engine

import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperationKind
import me.thenano.yamibo.yamibo_app.store.appsync.LocalSyncOperationDraft
import me.thenano.yamibo.yamibo_app.repository.appsync.schema.*

/**
 * Builds only live, idempotent repairs. A missing local row is deliberately not
 * interpreted as a deletion because absence has no reliable ownership signal.
 */
internal class LocalProjectionRepairPlanner {
    /** Compare portable typed values, never compatibility-only/derived snapshot fields. */
    fun plan(localDrafts: List<LocalSyncOperationDraft>, checkpoint: AppSyncCanonicalCheckpoint): List<LocalSyncOperationDraft> {
        val latest = checkpoint.entities.associateBy { it.domainId to it.entityId }
        return localDrafts.mapNotNull { local ->
            require(local.kind == SyncOperationKind.Put || local.kind == SyncOperationKind.RelationAdd) {
                "Local snapshot repair may only contain live entities and relations"
            }
            val normalized = AppSyncCanonicalNormalizer.normalize(local.domainId.value, local.entityId.value, local.fields)
            if (normalized is AppSyncCanonicalFieldsResult.Excluded) return@mapNotNull null
            require(normalized is AppSyncCanonicalFieldsResult.Accepted) { "Local canonical fields need attention" }
            val domain = AppSyncCanonicalSchema.domains.getValue(local.domainId.value)
            val identity = AppSyncCanonicalEntityKeys.parse(domain.id, local.entityId.value).legacyIdentity()
            val current = latest[domain.id to identity]
            val live = current != null && current.tombstone == null &&
                (local.kind != SyncOperationKind.RelationAdd || current.relation?.kind == SyncOperationKind.RelationAdd)
            if (live && normalized.fields.all { (field, value) -> current.fields[field]?.fields?.get(field) == value })
                return@mapNotNull null
            local.copy(entityGeneration = when {
                current == null -> local.entityGeneration
                current.tombstone != null || current.relation?.kind == SyncOperationKind.RelationRemove ->
                    nextGeneration(current.generation)
                else -> current.generation
            })
        }
    }

    private fun nextGeneration(generation: Long): Long {
        require(generation < Long.MAX_VALUE) { "Canonical generation is exhausted" }
        return generation + 1
    }

    fun plan(
        localDrafts: List<LocalSyncOperationDraft>,
        resolvedState: Map<SyncEntityKey, ResolvedSyncEntity>,
    ): List<LocalSyncOperationDraft> {
        val latest = resolvedState.values
            .groupBy { it.key.domainId to it.key.entityId }
            .mapValues { (_, entities) -> entities.maxBy { it.key.generation } }
        return localDrafts.mapNotNull { local ->
            val current = latest[local.domainId to local.entityId]
            if (matches(local, current)) return@mapNotNull null
            when (local.kind) {
                SyncOperationKind.RelationAdd -> local.copy(
                    entityGeneration = current?.key?.generation ?: local.entityGeneration,
                )
                SyncOperationKind.Put -> local.copy(
                    entityGeneration = when {
                        current == null -> local.entityGeneration
                        current.tombstone != null -> current.key.generation + 1L
                        else -> current.key.generation
                    },
                )
                else -> error("Local snapshot repair may only contain live entities and relations")
            }
        }
    }

    fun isRepresented(
        localDrafts: List<LocalSyncOperationDraft>,
        resolvedState: Map<SyncEntityKey, ResolvedSyncEntity>,
    ): Boolean {
        val latest = resolvedState.values
            .groupBy { it.key.domainId to it.key.entityId }
            .mapValues { (_, entities) -> entities.maxBy { it.key.generation } }
        return localDrafts.all { matches(it, latest[it.domainId to it.entityId]) }
    }

    private fun matches(
        local: LocalSyncOperationDraft,
        current: ResolvedSyncEntity?,
    ): Boolean {
        if (current == null || current.tombstone != null) return false
        if (local.kind == SyncOperationKind.RelationAdd && current.relationPresent != true) return false
        return local.fields.all { (field, value) -> current.fields[field]?.value == value }
    }
}

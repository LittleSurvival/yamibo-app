package me.thenano.yamibo.yamibo_app.repository.appsync.engine

import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperationKind
import me.thenano.yamibo.yamibo_app.store.appsync.LocalSyncOperationDraft

/**
 * Builds only live, idempotent repairs. A missing local row is deliberately not
 * interpreted as a deletion because absence has no reliable ownership signal.
 */
internal class LocalProjectionRepairPlanner {
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

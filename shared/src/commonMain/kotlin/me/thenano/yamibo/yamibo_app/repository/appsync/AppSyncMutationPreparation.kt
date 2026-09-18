package me.thenano.yamibo.yamibo_app.repository.appsync

import me.thenano.yamibo.yamibo_app.repository.appsync.domain.SyncDomainRegistry
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.OperationReducer
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.ResolvedSyncEntity
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.SyncEntityKey
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncDomainId
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncEntityId
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperation
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperationKind
import me.thenano.yamibo.yamibo_app.repository.appsync.schema.equivalentAppSyncLegacyFieldValues

/** One transaction-local preparation session. No persisted projection is modified here. */
internal class AppSyncMutationPreparation(
    private val loadEntity: (SyncDomainId, SyncEntityId) -> Map<SyncEntityKey, ResolvedSyncEntity>,
) {
    private val states = mutableMapOf<Pair<SyncDomainId, SyncEntityId>, Map<SyncEntityKey, ResolvedSyncEntity>>()
    private val registry = SyncDomainRegistry.Default
    private val reducer = OperationReducer(registry)

    fun prepareFields(operation: SyncOperation): Map<String, String?>? {
        val domain = operation.domainId.value
        val id = operation.entityId.value
        if (!AppSyncPortabilityPolicy.isEntityPortable(domain, id)) return null
        val portable = portableAppSyncFields(domain, id, operation.fields)
        if (operation.kind == SyncOperationKind.Patch && portable.isEmpty()) return null
        val target = operation.domainId to operation.entityId
        val state = states.getOrPut(target) { loadEntity(operation.domainId, operation.entityId) }
        val current = state.values.maxByOrNull { it.key.generation }
        val contract = registry.contractFor(operation.domainId)
        val fields = when (operation.kind) {
            SyncOperationKind.Delete, SyncOperationKind.RelationRemove -> {
                // Legacy relation identity and embedded authorization remain required by v2.
                val required = contract?.requiredFieldsByKind?.get(operation.kind).orEmpty() +
                    if (operation.bulkDeleteAuthorizationId != null) AppSyncLegacyFieldRegistry.proofFields else emptySet()
                portable.filterKeys { it in required }
            }
            SyncOperationKind.Patch -> {
                if (current == null || current.key.generation != operation.entityGeneration ||
                    current.tombstone != null || current.relationPresent == false ||
                    registry.validationFailure(operation.copy(fields = portable)) != null) {
                    portable
                } else {
                    val changed = portable.filter { (field, value) ->
                        !current.fields.containsKey(field) || !equivalentAppSyncLegacyFieldValues(
                            domain, id, field, current.fields.getValue(field).value, value)
                    }
                    if (changed.isEmpty()) return null
                    val required = contract?.requiredFieldsByKind?.get(SyncOperationKind.Patch).orEmpty()
                    portable.filterKeys { it in changed || it in required }
                }
            }
            else -> portable
        }
        // Use the exact future identity/context, not a map overlay: monotonic registers,
        // remove-wins, invalid operations and generation boundaries can all reject input values.
        states[target] = reducer.reduce(current = state, operations = listOf(operation.copy(fields = fields))).entities
        return fields
    }
}

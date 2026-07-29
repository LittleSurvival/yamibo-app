package me.thenano.yamibo.yamibo_app.repository.appsync.engine

import kotlinx.serialization.Serializable
import me.thenano.yamibo.yamibo_app.repository.appsync.domain.SyncConflictPolicy
import me.thenano.yamibo.yamibo_app.repository.appsync.domain.SyncDomainRegistry
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncCausalRelation
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncDomainId
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncEntityId
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperation
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperationId
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperationKind
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.compareCausally

@Serializable
internal data class SyncEntityKey(
    val domainId: SyncDomainId,
    val entityId: SyncEntityId,
    val generation: Long,
)

@Serializable
internal data class ResolvedSyncField(
    val value: String?,
    val operation: SyncOperation,
)

@Serializable
internal data class ResolvedSyncEntity(
    val key: SyncEntityKey,
    val fields: Map<String, ResolvedSyncField> = emptyMap(),
    val relationPresent: Boolean? = null,
    val relationOperation: SyncOperation? = null,
    val tombstone: SyncOperation? = null,
)

internal data class SyncConflictRecord(
    val entityKey: SyncEntityKey,
    val field: String?,
    val winnerOperationId: SyncOperationId,
    val loserOperationId: SyncOperationId,
    val policy: SyncConflictPolicy,
    val reason: String,
)

internal data class SyncQuarantinedOperation(
    val operation: SyncOperation,
    val reason: String,
)

internal data class OperationReductionResult(
    val entities: Map<SyncEntityKey, ResolvedSyncEntity>,
    val conflicts: List<SyncConflictRecord>,
    val quarantined: List<SyncQuarantinedOperation>,
    val appliedOperations: List<SyncOperation>,
) {
    val appliedOperationIds: Set<SyncOperationId>
        get() = appliedOperations.mapTo(linkedSetOf()) { it.operationId }
}

internal class OperationReducer(
    private val registry: SyncDomainRegistry = SyncDomainRegistry.Default,
) {
    fun reduce(
        current: Map<SyncEntityKey, ResolvedSyncEntity> = emptyMap(),
        operations: Iterable<SyncOperation>,
    ): OperationReductionResult {
        val entities = current.toMutableMap()
        val conflicts = mutableListOf<SyncConflictRecord>()
        val quarantined = mutableListOf<SyncQuarantinedOperation>()
        val applied = linkedMapOf<SyncOperationId, SyncOperation>()

        operations
            .distinctBy { it.operationId }
            .sortedBy { it.operationId }
            .forEach { operation ->
                val contract = registry.contractFor(operation.domainId)
                val validationFailure = registry.validationFailure(operation)
                if (contract == null || validationFailure != null) {
                    quarantined += SyncQuarantinedOperation(
                        operation,
                        validationFailure ?: "Missing domain contract",
                    )
                    return@forEach
                }
                val key = SyncEntityKey(
                    operation.domainId,
                    operation.entityId,
                    operation.entityGeneration,
                )
                val sameEntityKeys = entities.keys.filter {
                    it.domainId == operation.domainId && it.entityId == operation.entityId
                }
                val latestGeneration = sameEntityKeys.maxOfOrNull { it.generation }
                if (latestGeneration != null && operation.entityGeneration < latestGeneration) {
                    applied[operation.operationId] = operation
                    return@forEach
                }
                if (latestGeneration != null && operation.entityGeneration > latestGeneration) {
                    if (operation.kind != SyncOperationKind.Put) {
                        quarantined += SyncQuarantinedOperation(
                            operation,
                            "A new entity generation must begin with Put",
                        )
                        return@forEach
                    }
                    sameEntityKeys.forEach(entities::remove)
                }
                val existing = entities[key] ?: ResolvedSyncEntity(key)
                entities[key] = when (contract.conflictPolicy) {
                    SyncConflictPolicy.RemoveWinsRelation ->
                        reduceRelation(existing, operation, contract.conflictPolicy, conflicts)
                    SyncConflictPolicy.FieldRegister,
                    SyncConflictPolicy.MonotonicProgress,
                    SyncConflictPolicy.RemoveWinsEntity,
                    -> reduceEntity(
                        existing,
                        operation,
                        contract.conflictPolicy,
                        contract.monotonicNumericFields,
                        conflicts,
                    )
                }
                applied[operation.operationId] = operation
            }

        return OperationReductionResult(
            entities = entities,
            conflicts = conflicts,
            quarantined = quarantined,
            appliedOperations = applied.values.toList(),
        )
    }

    private fun reduceEntity(
        existing: ResolvedSyncEntity,
        incoming: SyncOperation,
        policy: SyncConflictPolicy,
        monotonicNumericFields: Set<String>,
        conflicts: MutableList<SyncConflictRecord>,
    ): ResolvedSyncEntity {
        if (incoming.kind == SyncOperationKind.Delete) {
            val newestLive = existing.fields.values.maxByOrNull { it.operation.operationId }?.operation
            val winner = choose(
                current = existing.tombstone ?: newestLive,
                incoming = incoming,
                removeWins = true,
            )
            if (winner === incoming) {
                recordConflictIfConcurrent(existing.key, null, incoming, existing.tombstone ?: newestLive, policy, conflicts)
                return existing.copy(fields = emptyMap(), tombstone = incoming)
            }
            recordConflictIfConcurrent(existing.key, null, winner, incoming, policy, conflicts)
            return existing
        }

        val tombstone = existing.tombstone
        if (tombstone != null) {
            val relation = compareCausally(tombstone, incoming)
            val explicitRecreation = incoming.entityGeneration > existing.key.generation
            if (!explicitRecreation || relation != SyncCausalRelation.Before) {
                recordConflictIfConcurrent(existing.key, null, tombstone, incoming, policy, conflicts)
                return existing
            }
        }

        val updatedFields = existing.fields.toMutableMap()
        incoming.fields.forEach { (field, value) ->
            val current = updatedFields[field]
            val winner = if (
                field in monotonicNumericFields &&
                current != null &&
                compareCausally(current.operation, incoming) == SyncCausalRelation.Concurrent
            ) {
                val currentValue = current.value?.toLongOrNull()
                val incomingValue = value?.toLongOrNull()
                when {
                    currentValue == null || incomingValue == null ->
                        choose(current.operation, incoming, removeWins = false)
                    incomingValue > currentValue -> incoming
                    incomingValue < currentValue -> current.operation
                    else -> choose(current.operation, incoming, removeWins = false)
                }
            } else {
                choose(current?.operation, incoming, removeWins = false)
            }
            if (winner === incoming) {
                recordConflictIfConcurrent(existing.key, field, incoming, current?.operation, policy, conflicts)
                updatedFields[field] = ResolvedSyncField(value, incoming)
            } else {
                recordConflictIfConcurrent(existing.key, field, winner, incoming, policy, conflicts)
            }
        }
        return existing.copy(fields = updatedFields, tombstone = null)
    }

    private fun reduceRelation(
        existing: ResolvedSyncEntity,
        incoming: SyncOperation,
        policy: SyncConflictPolicy,
        conflicts: MutableList<SyncConflictRecord>,
    ): ResolvedSyncEntity {
        val current = existing.relationOperation
        val incomingPresent = incoming.kind == SyncOperationKind.RelationAdd
        val winner = choose(
            current = current,
            incoming = incoming,
            removeWins = true,
        )
        return if (winner === incoming) {
            recordConflictIfConcurrent(existing.key, null, incoming, current, policy, conflicts)
            existing.copy(
                fields = existing.fields + incoming.fields.mapValues { (_, value) ->
                    ResolvedSyncField(value, incoming)
                },
                relationPresent = incomingPresent,
                relationOperation = incoming,
            )
        } else {
            recordConflictIfConcurrent(existing.key, null, winner, incoming, policy, conflicts)
            existing
        }
    }

    private fun choose(
        current: SyncOperation?,
        incoming: SyncOperation,
        removeWins: Boolean,
    ): SyncOperation {
        if (current == null) return incoming
        return when (compareCausally(current, incoming)) {
            SyncCausalRelation.Before -> incoming
            SyncCausalRelation.After,
            SyncCausalRelation.Same,
            -> current
            SyncCausalRelation.Concurrent -> {
                val currentRemoves = current.kind == SyncOperationKind.Delete ||
                    current.kind == SyncOperationKind.RelationRemove
                val incomingRemoves = incoming.kind == SyncOperationKind.Delete ||
                    incoming.kind == SyncOperationKind.RelationRemove
                when {
                    removeWins && currentRemoves != incomingRemoves ->
                        if (currentRemoves) current else incoming
                    incoming.operationId > current.operationId -> incoming
                    else -> current
                }
            }
        }
    }

    private fun recordConflictIfConcurrent(
        key: SyncEntityKey,
        field: String?,
        winner: SyncOperation,
        loser: SyncOperation?,
        policy: SyncConflictPolicy,
        destination: MutableList<SyncConflictRecord>,
    ) {
        if (loser == null || winner.operationId == loser.operationId) return
        if (compareCausally(winner, loser) != SyncCausalRelation.Concurrent) return
        destination += SyncConflictRecord(
            entityKey = key,
            field = field,
            winnerOperationId = winner.operationId,
            loserOperationId = loser.operationId,
            policy = policy,
            reason = "Concurrent operations resolved by $policy",
        )
    }
}

package me.thenano.yamibo.yamibo_app.repository.appsync.engine

import me.thenano.yamibo.yamibo_app.repository.appsync.operation.*
import me.thenano.yamibo.yamibo_app.repository.appsync.schema.*
import me.thenano.yamibo.yamibo_app.repository.appsync.domain.SyncDomainRegistry

internal data class AppSyncCanonicalQuarantine(val operation: AppSyncCanonicalOperation, val reason: String)
internal data class AppSyncCanonicalReductionResult(
    val entities: List<AppSyncCanonicalProjection>,
    val authorizations: List<AppSyncCanonicalDeleteProof>,
    val conflicts: List<SyncConflictRecord>,
    val quarantined: List<AppSyncCanonicalQuarantine>,
    val appliedOperations: List<AppSyncCanonicalOperation>,
)

/** Typed validation and provenance bridge, sharing the existing causal conflict rules.
 * No identity/cache/parent fields are synthesized, and coverage never advances here.
 */
internal fun reduceCanonicalUsingSharedRules(
    current: AppSyncCanonicalCheckpoint,
    incoming: AppSyncCanonicalOperationBlock,
    reduce: (Map<SyncEntityKey, ResolvedSyncEntity>, List<SyncOperation>) -> OperationReductionResult,
): AppSyncCanonicalReductionResult {
    require(current.accountBinding == incoming.accountBinding) { "Canonical reduction account mismatch" }
    AppSyncCanonicalCheckpointCodec().encode(current)
    AppSyncCanonicalOperationBlockCodec().encode(incoming)
    require(current.entities.map { it.domainId to it.entityId }.distinct().size == current.entities.size) {
        "Multiple canonical generations"
    }
    val canonical = linkedMapOf<SyncOperationId, AppSyncCanonicalOperation>()
    fun id(op: AppSyncCanonicalOperation) = SyncOperation.idFor(SyncDeviceId(op.deviceId), SyncDeviceEpoch(op.deviceEpoch), SyncSequence(op.sequence))
    fun remember(op: AppSyncCanonicalOperation) {
        val old = canonical.put(id(op), op)
        require(old == null || old == op) { "Canonical operation identity collision" }
    }
    current.entities.forEach { entity ->
        entity.fields.values.forEach(::remember)
        entity.relation?.let(::remember)
        entity.tombstone?.let(::remember)
    }
    incoming.operations.forEach(::remember)
    val proofs = linkedMapOf<String, AppSyncCanonicalDeleteProof>()
    (current.authorizations + incoming.authorizations).forEach { proof ->
        val old = proofs.put(proof.authorizationId, proof)
        require(old == null || old == proof) { "Canonical authorization identity collision" }
    }
    AppSyncCanonicalOperationBlockCodec().encode(AppSyncCanonicalOperationBlock(current.accountBinding,
        canonical.values.toList(), proofs.values.toList()))
    val shared = canonical.mapValues { (_, op) ->
        val schema = AppSyncCanonicalSchema.domainsById.getValue(op.domainId)
        SyncOperation(id(op), SyncDeviceId(op.deviceId), SyncDeviceEpoch(op.deviceEpoch), SyncSequence(op.sequence),
            SyncAccountBinding(current.accountBinding), SyncDomainId(schema.name), SyncEntityId(op.entityId), op.generation,
            op.kind, op.fields.mapKeys { schema.fieldsById.getValue(it.key).name }.mapValues { it.value.legacyValue() },
            SyncCausalContext(op.causalContext), op.createdAtEpochMillis, op.origin, op.authorizationId)
    }
    shared.values.forEach(::validateCanonicalMutation)
    val state = current.entities.associate { entity ->
        val schema = AppSyncCanonicalSchema.domainsById.getValue(entity.domainId)
        val key = SyncEntityKey(SyncDomainId(schema.name), SyncEntityId(entity.entityId), entity.generation)
        key to ResolvedSyncEntity(key, entity.fields.entries.associate { (field, winner) ->
            schema.fieldsById.getValue(field).name to ResolvedSyncField(winner.fields.getValue(field).legacyValue(), shared.getValue(id(winner)))
        }, entity.relation?.let { it.kind == SyncOperationKind.RelationAdd }, entity.relation?.let { shared.getValue(id(it)) },
            entity.tombstone?.let { shared.getValue(id(it)) })
    }
    val result = reduce(state, incoming.operations.map { shared.getValue(id(it)) })
    val entities = result.entities.values.map { entity ->
        val schema = AppSyncCanonicalSchema.domains.getValue(entity.key.domainId.value)
        AppSyncCanonicalProjection(schema.id, entity.key.entityId.value, entity.key.generation,
            entity.fields.entries.associate { (field, winner) -> schema.fields.getValue(field).id to canonical.getValue(winner.operation.operationId) },
            entity.relationOperation?.let { canonical.getValue(it.operationId) }, entity.tombstone?.let { canonical.getValue(it.operationId) })
    }.sortedWith(compareBy({ it.domainId }, { it.entityId }, { it.generation }))
    val usedProofs = entities.flatMap { it.fields.values + listOfNotNull(it.relation, it.tombstone) }
        .mapNotNull { it.authorizationId }.toSet()
    return AppSyncCanonicalReductionResult(entities, proofs.values.filter { it.authorizationId in usedProofs }.sortedBy { it.authorizationId },
        result.conflicts, result.quarantined.map { AppSyncCanonicalQuarantine(canonical.getValue(it.operation.operationId), it.reason) },
        result.appliedOperations.map { canonical.getValue(it.operationId) })
}

/** Reconstructed values exist only for legacy semantic checks, never in reducer provenance. */
private fun validateCanonicalMutation(op: SyncOperation) {
    val domain = AppSyncCanonicalSchema.domains.getValue(op.domainId.value)
    val contract = requireNotNull(SyncDomainRegistry.Default.contractFor(op.domainId))
    contract.allowedFieldsByKind[op.kind]?.let { allowed ->
        require(op.fields.keys.all { it in allowed }) { "Invalid canonical mutation fields" }
    }
    if (op.kind == SyncOperationKind.Put || op.kind == SyncOperationKind.RelationAdd) {
        val required = contract.requiredFieldsByKind[op.kind].orEmpty().filter { name ->
            domain.fields[name]?.classification == AppSyncFieldClass.Essential && name != "sourceDiscriminator"
        }
        require(op.fields.keys.containsAll(required)) { "Missing canonical mutation fields" }
    }
    // Legacy progress contracts have no Put field list. Canonical snapshots must still carry
    // their non-null essential values so a fresh device can materialize them.
    if (op.kind == SyncOperationKind.Put && domain.id in setOf(8, 9, 10, 14)) {
        require(domain.fields.values.filter { it.classification == AppSyncFieldClass.Essential && !it.nullable }
            .all { it.name in op.fields }) { "Missing canonical progress fields" }
    }
    val semanticValues = op.fields.toMutableMap()
    if (!(domain.id == 17 && op.kind == SyncOperationKind.Patch))
        semanticValues.putAll(AppSyncCanonicalEntityKeys.parse(domain.id, op.entityId.value).derivedFields())
    domain.fields.values.filter { op.kind == SyncOperationKind.Put && it.classification == AppSyncFieldClass.BoundedPresentation }.forEach {
        if (it.name !in semanticValues) semanticValues[it.name] = ""
    }
    if (domain.id == 17 && op.kind == SyncOperationKind.Put) {
        if ("sourceDiscriminator" !in semanticValues) semanticValues["sourceDiscriminator"] =
            defaultAppSyncEventDiscriminator(semanticValues["detailIds"])
        require(semanticValues["sourceDiscriminator"]?.startsWith("legacy-ambiguous|") != true) {
            "Unsupported canonical event evidence"
        }
    }
    // A minimal patch need not repeat an unchanged update timestamp. The semantic validator
    // still checks supplied timestamps; zero is a validation-only placeholder for absence.
    if (domain.id == 3 && op.kind == SyncOperationKind.Patch && "updatedAt" !in semanticValues) semanticValues["updatedAt"] = "0"
    if (domain.id in setOf(11, 12, 13) && op.kind == SyncOperationKind.Patch && "lastVisitTime" !in semanticValues)
        semanticValues["lastVisitTime"] = "0"
    require(contract.semanticValidator?.invoke(op.copy(fields = semanticValues)) == null) { "Invalid canonical mutation semantics" }
}

package me.thenano.yamibo.yamibo_app.repository.appsync

import me.thenano.yamibo.yamibo_app.repository.appsync.domain.SyncDomainRegistry
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.OperationReducer
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.*
import me.thenano.yamibo.yamibo_app.repository.appsync.schema.*

/** Command-local canonical comparison; compatibility fields remain only in the source outbox. */
internal class AppSyncCanonicalMutationPreparation(
    private var current: AppSyncCanonicalCheckpoint,
    private var preserveAll: Boolean = false,
) {
    fun prepareFields(source: SyncOperation): Map<String, String?>? {
        if (preserveAll) return source.fields
        fun retain(): Map<String, String?> { preserveAll = true; return source.fields }
        val imported = AppSyncCanonicalOperationImporter().import(current.accountBinding, source)
        if (imported is AppSyncCanonicalOperationImport.Excluded || imported is AppSyncCanonicalOperationImport.NoOp) return null
        // Preserve invalid/oversized essential source data. Recording surfaces the failure
        // after the ordinary local edit and outbox have been saved in the same transaction.
        if (imported !is AppSyncCanonicalOperationImport.Accepted) return retain()
        val operation = imported.operation
        val schema = AppSyncCanonicalSchema.domainsById.getValue(operation.domainId)
        val entity = current.entities.singleOrNull { it.domainId == operation.domainId && it.entityId == operation.entityId }
        val changed = if (source.kind == SyncOperationKind.Patch && entity != null &&
            entity.generation == operation.generation && entity.tombstone == null &&
            entity.relation?.kind != SyncOperationKind.RelationRemove) {
            operation.fields.filter { (field, value) -> entity.fields[field]?.fields?.get(field) != value }
        } else operation.fields
        if (source.kind == SyncOperationKind.Patch && changed.isEmpty()) return null
        val required = SyncDomainRegistry.Default.contractFor(source.domainId)?.requiredFieldsByKind?.get(source.kind).orEmpty() +
            if (source.bulkDeleteAuthorizationId != null) AppSyncLegacyFieldRegistry.proofFields else emptySet()
        val names = changed.keys.mapTo(hashSetOf()) { schema.fieldsById.getValue(it).name }
        val fields = source.fields.filterKeys { it in names || it in required }
        // Compare subsequent edits against the exact source that will enter the outbox,
        // including compatibility-required fields, not a different prospective operation.
        val prepared = AppSyncCanonicalOperationImporter().import(current.accountBinding, source.copy(fields = fields))
        if (prepared !is AppSyncCanonicalOperationImport.Accepted) return retain()
        val result = try {
            OperationReducer().reduceCanonical(current, AppSyncCanonicalOperationBlock(
                current.accountBinding, listOf(prepared.operation), listOfNotNull(prepared.proof)))
        } catch (_: Exception) {
            // Aggregate codec/semantic limits are handled by the durable recording path.
            return retain()
        }
        if (result.quarantined.isNotEmpty()) return retain()
        current = current.copy(entities = result.entities, authorizations = result.authorizations,
            coverage = current.coverage + (source.replicaKey.stableKey to maxOf(
                current.coverage[source.replicaKey.stableKey] ?: 0L, source.sequence.value)))
        return fields
    }
}

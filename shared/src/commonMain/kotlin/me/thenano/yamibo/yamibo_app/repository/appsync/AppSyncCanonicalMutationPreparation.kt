package me.thenano.yamibo.yamibo_app.repository.appsync

import me.thenano.yamibo.yamibo_app.repository.appsync.domain.SyncDomainRegistry
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.OperationReducer
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.*
import me.thenano.yamibo.yamibo_app.repository.appsync.schema.*

/** Command-local canonical comparison; compatibility fields remain only in the source outbox. */
internal class AppSyncCanonicalMutationPreparation(private var current: AppSyncCanonicalCheckpoint) {
    fun prepareFields(source: SyncOperation): Map<String, String?>? {
        val imported = AppSyncCanonicalOperationImporter().import(current.accountBinding, source)
        if (imported is AppSyncCanonicalOperationImport.Excluded || imported is AppSyncCanonicalOperationImport.NoOp) return null
        // Preserve invalid/oversized essential source data. Recording surfaces the failure
        // after the ordinary local edit and outbox have been saved in the same transaction.
        if (imported !is AppSyncCanonicalOperationImport.Accepted) return source.fields
        val operation = imported.operation
        val schema = AppSyncCanonicalSchema.domainsById.getValue(operation.domainId)
        val entity = current.entities.singleOrNull { it.domainId == operation.domainId && it.entityId == operation.entityId }
        val changed = if (source.kind == SyncOperationKind.Patch && entity != null &&
            entity.generation == operation.generation && entity.tombstone == null &&
            entity.relation?.kind != SyncOperationKind.RelationRemove) {
            operation.fields.filter { (field, value) -> entity.fields[field]?.fields?.get(field) != value }
        } else operation.fields
        if (source.kind == SyncOperationKind.Patch && changed.isEmpty()) return null
        val minimal = operation.copy(fields = changed)
        val result = try {
            OperationReducer().reduceCanonical(current, AppSyncCanonicalOperationBlock(
                current.accountBinding, listOf(minimal), listOfNotNull(imported.proof)))
        } catch (_: Exception) {
            // Aggregate codec/semantic limits are handled by the durable recording path.
            return source.fields
        }
        current = current.copy(entities = result.entities, authorizations = result.authorizations,
            coverage = current.coverage + (source.replicaKey.stableKey to maxOf(
                current.coverage[source.replicaKey.stableKey] ?: 0L, source.sequence.value)))
        val required = SyncDomainRegistry.Default.contractFor(source.domainId)?.requiredFieldsByKind?.get(source.kind).orEmpty() +
            if (source.bulkDeleteAuthorizationId != null) AppSyncLegacyFieldRegistry.proofFields else emptySet()
        val names = changed.keys.mapTo(hashSetOf()) { schema.fieldsById.getValue(it).name }
        return source.fields.filterKeys { it in names || it in required }
    }
}

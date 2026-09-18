package me.thenano.yamibo.yamibo_app.repository.appsync.schema

import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncBulkDeleteProofFields
import me.thenano.yamibo.yamibo_app.repository.appsync.domain.SyncDomainRegistry
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.*
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncLegacyReaderCompatibility

internal enum class AppSyncV2ExportFailure { InvalidCanonical, ReaderCompatibility, LegacyContract, SemanticChange }

internal sealed interface AppSyncV2OperationExport {
    data class Ready(val operations: List<SyncOperation>) : AppSyncV2OperationExport
    data class NeedsAttention(val reason: AppSyncV2ExportFailure) : AppSyncV2OperationExport
}

/** Converts verified canonical operations, never materialized cache values or fabricated history.
 * This does not authorize publication, acknowledge sources, or replace an indexed v3 root.
 */
internal class AppSyncSanitizedV2OperationExporter {
    fun export(block: AppSyncCanonicalOperationBlock): AppSyncV2OperationExport {
        try { AppSyncCanonicalOperationBlockCodec().encode(block) }
        catch (_: Exception) { return attention(AppSyncV2ExportFailure.InvalidCanonical) }
        val proofs = block.authorizations.associateBy { it.authorizationId }
        val output = ArrayList<SyncOperation>(block.operations.size)
        for (operation in block.operations) {
            val domain = AppSyncCanonicalSchema.domainsById.getValue(operation.domainId)
            val fields = operation.fields.mapKeys { domain.fieldsById.getValue(it.key).name }
                .mapValues { it.value.legacyValue() }.toMutableMap()
            if (operation.kind != SyncOperationKind.Delete) {
                val identity = AppSyncCanonicalEntityKeys.parse(domain.id, operation.entityId).derivedFields()
                val required = SyncDomainRegistry.Default.contractFor(SyncDomainId(domain.name))
                    ?.requiredFieldsByKind?.get(operation.kind).orEmpty()
                fields.putAll(if (operation.kind == SyncOperationKind.Patch) identity.filterKeys { it in required } else identity)
                // Legacy settings use the string preference API for enum values.
                if (domain.id == 1 && fields["type"] == "enum") fields["type"] = "string"
                if (domain.id == 17 && operation.kind == SyncOperationKind.Put && fields["sourceDiscriminator"] == null) {
                    fields["sourceDiscriminator"] = defaultAppSyncEventDiscriminator(fields["detailIds"])
                }
            }
            val proof = operation.authorizationId?.let(proofs::getValue)
            if (proof != null) {
                fields[AppSyncBulkDeleteProofFields.SCOPE] = proof.scope
                fields[AppSyncBulkDeleteProofFields.COUNT] = proof.operationCount.toString()
                fields[AppSyncBulkDeleteProofFields.EXPIRES_AT] = proof.expiresAtEpochMillis.toString()
            }
            val device = SyncDeviceId(operation.deviceId)
            val epoch = SyncDeviceEpoch(operation.deviceEpoch)
            val sequence = SyncSequence(operation.sequence)
            val source = SyncOperation(SyncOperation.idFor(device, epoch, sequence), device, epoch, sequence,
                SyncAccountBinding(block.accountBinding), SyncDomainId(domain.name), SyncEntityId(operation.entityId),
                operation.generation, operation.kind, fields, SyncCausalContext(operation.causalContext),
                operation.createdAtEpochMillis, operation.origin, operation.authorizationId)
            if (AppSyncLegacyReaderCompatibility.requiresV3(source))
                return attention(AppSyncV2ExportFailure.ReaderCompatibility)
            if (SyncDomainRegistry.Default.validationFailure(source) != null)
                return attention(AppSyncV2ExportFailure.LegacyContract)
            // Round-trip proves that adaptation neither adds portable values nor changes causality,
            // identity, deletion authority, or omitted field semantics. No partial batch escapes.
            val imported = AppSyncCanonicalOperationImporter().import(block.accountBinding, source)
            if (imported !is AppSyncCanonicalOperationImport.Accepted)
                return attention(AppSyncV2ExportFailure.LegacyContract)
            if (imported.operation != operation || imported.proof != proof)
                return attention(AppSyncV2ExportFailure.SemanticChange)
            output += source
        }
        return AppSyncV2OperationExport.Ready(output)
    }

    private fun attention(reason: AppSyncV2ExportFailure) = AppSyncV2OperationExport.NeedsAttention(reason)
}

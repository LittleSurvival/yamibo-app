package me.thenano.yamibo.yamibo_app.repository.appsync.schema

import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncBulkDeleteProofFields
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperation
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperationKind
import me.thenano.yamibo.yamibo_app.repository.appsync.domain.SyncDomainRegistry
import me.thenano.yamibo.yamibo_app.repository.backup.favoriteUpdateEventIdentity

internal enum class AppSyncCanonicalImportFailure {
    AccountMismatch, InvalidIdentity, IdentityFieldMismatch, InvalidContract, InvalidProof,
    NonReconstructibleEventIdentity, InvalidCanonicalOperation,
}

/** Never drops a source sequence silently. The caller must retain coverage for Excluded/NoOp. */
internal sealed interface AppSyncCanonicalOperationImport {
    data class Accepted(val operation: AppSyncCanonicalOperation, val proof: AppSyncCanonicalDeleteProof?,
        val exclusions: List<AppSyncCanonicalIssue>) : AppSyncCanonicalOperationImport
    data class Excluded(val issue: AppSyncCanonicalIssue) : AppSyncCanonicalOperationImport
    data class NoOp(val exclusions: List<AppSyncCanonicalIssue>) : AppSyncCanonicalOperationImport
    data class NeedsAttention(val failure: AppSyncCanonicalImportFailure? = null,
        val fieldIssue: AppSyncCanonicalIssue? = null) : AppSyncCanonicalOperationImport
}

/** Pure legacy import boundary. It never mutates source operations, outbox rows or projections. */
internal class AppSyncCanonicalOperationImporter {
    fun import(expectedAccount: String, source: SyncOperation): AppSyncCanonicalOperationImport {
        if (source.accountBinding.value != expectedAccount) return attention(AppSyncCanonicalImportFailure.AccountMismatch)
        val domain = AppSyncCanonicalSchema.domains[source.domainId.value] ?: return AppSyncCanonicalOperationImport.Excluded(
            AppSyncCanonicalIssue(null, null, AppSyncCanonicalIssueReason.UnknownDomain))
        if (domain.id == 1 && source.entityId.value.lowercase() !in AppSyncCanonicalSettings.entries) {
            return AppSyncCanonicalOperationImport.Excluded(AppSyncCanonicalIssue(domain.id, null, AppSyncCanonicalIssueReason.UnknownSetting))
        }
        val key = try { AppSyncCanonicalEntityKeys.parse(domain.id, source.entityId.value) } catch (_: Exception) {
            return attention(AppSyncCanonicalImportFailure.InvalidIdentity)
        }
        val destructive = source.kind == SyncOperationKind.Delete || source.kind == SyncOperationKind.RelationRemove
        if (!destructive) {
            val mismatch = key.derivedFields().any { (field, value) ->
                field in source.fields && !equivalentIdentity(domain.name, source.entityId.value, field, source.fields[field], value)
            }
            if (mismatch) return attention(AppSyncCanonicalImportFailure.IdentityFieldMismatch)
        }
        val contract = SyncDomainRegistry.Default.contractFor(source.domainId)
        if (contract == null || source.kind !in contract.allowedKinds) return attention(AppSyncCanonicalImportFailure.InvalidContract)
        // Legacy destructive bodies may contain stale data; only the authoritative key/proof survives.
        if (!destructive && contract.validate(source) != null) return attention(AppSyncCanonicalImportFailure.InvalidContract)
        if (domain.id == 17 && source.kind == SyncOperationKind.Put && !eventCanBeReconstructed(source)) {
            return attention(AppSyncCanonicalImportFailure.NonReconstructibleEventIdentity)
        }
        val proof = try { proof(source, domain.id) } catch (_: Exception) { return attention(AppSyncCanonicalImportFailure.InvalidProof) }
        val normalized = AppSyncCanonicalNormalizer.normalize(domain.name, source.entityId.value, if (destructive) emptyMap() else source.fields)
        val fields = when (normalized) {
            is AppSyncCanonicalFieldsResult.Excluded -> return AppSyncCanonicalOperationImport.Excluded(normalized.issue)
            is AppSyncCanonicalFieldsResult.NeedsAttention -> return AppSyncCanonicalOperationImport.NeedsAttention(fieldIssue = normalized.issue)
            is AppSyncCanonicalFieldsResult.Accepted -> normalized
        }
        if (source.kind == SyncOperationKind.Patch && fields.fields.isEmpty()) return AppSyncCanonicalOperationImport.NoOp(fields.exclusions)
        val operation = AppSyncCanonicalOperation(source.deviceId.value, source.deviceEpoch.value, source.sequence.value,
            domain.id, key.legacyIdentity(), source.entityGeneration, source.kind, source.createdAtEpochMillis, source.origin,
            source.bulkDeleteAuthorizationId, source.causalContext.asStableMap(), fields.fields)
        try {
            AppSyncCanonicalOperationBlockCodec().encode(AppSyncCanonicalOperationBlock(expectedAccount, listOf(operation), listOfNotNull(proof)))
        } catch (_: Exception) { return attention(AppSyncCanonicalImportFailure.InvalidCanonicalOperation) }
        return AppSyncCanonicalOperationImport.Accepted(operation, proof, fields.exclusions)
    }

    private fun proof(source: SyncOperation, domainId: Int): AppSyncCanonicalDeleteProof? {
        val keys = setOf(AppSyncBulkDeleteProofFields.SCOPE, AppSyncBulkDeleteProofFields.COUNT, AppSyncBulkDeleteProofFields.EXPIRES_AT)
        val id = source.bulkDeleteAuthorizationId
        if (id == null) {
            require(source.fields.keys.none { it in keys })
            return null
        }
        require(source.kind == SyncOperationKind.Delete)
        val scope = requireNotNull(source.fields[AppSyncBulkDeleteProofFields.SCOPE])
        val count = requireNotNull(source.fields[AppSyncBulkDeleteProofFields.COUNT]?.toLongOrNull())
        val expiry = requireNotNull(source.fields[AppSyncBulkDeleteProofFields.EXPIRES_AT]?.toLongOrNull())
        require(count > 0 && source.createdAtEpochMillis <= expiry)
        return AppSyncCanonicalDeleteProof(id, domainId, scope, count, expiry)
    }

    private fun eventCanBeReconstructed(source: SyncOperation): Boolean = try {
        val fields = source.fields
        val details = requireNotNull(fields["detailIds"]).split(',').filter(String::isNotBlank).map(String::toLong).distinct().sorted()
        // Default ambiguous discriminators embed refetchable title text. A digest representation
        // is still required before these can be migrated without retaining that duplication.
        val discriminator = fields["sourceDiscriminator"]
        if (discriminator.isNullOrBlank() || discriminator.startsWith("legacy-ambiguous|")) false
        else favoriteUpdateEventIdentity(requireNotNull(fields["targetType"]), requireNotNull(fields["targetId"]).toLong(),
            requireNotNull(fields["authorId"]).toLong(), requireNotNull(fields["mode"]), details,
            requireNotNull(fields["ambiguous"]).toBooleanStrict(), requireNotNull(fields["detectedAt"]).toLong(),
            requireNotNull(fields["summary"]), fields["title"].orEmpty(), discriminator).syncId == source.entityId.value
    } catch (_: Exception) { false }

    private fun equivalentIdentity(domain: String, entity: String, field: String, actual: String?, expected: String?): Boolean {
        // Enum preferences use the string settings-store API, but their declared canonical type is enum.
        if (domain == "settings" && field == "type" && expected == "enum" && actual == "string") return true
        return equivalentAppSyncLegacyFieldValues(domain, entity, field, actual, expected)
    }
    private fun attention(failure: AppSyncCanonicalImportFailure) = AppSyncCanonicalOperationImport.NeedsAttention(failure)
}

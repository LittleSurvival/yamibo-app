package me.thenano.yamibo.yamibo_app.repository.appsync.engine

import me.thenano.yamibo.yamibo_app.repository.appsync.operation.*
import me.thenano.yamibo.yamibo_app.repository.appsync.schema.*

/** Ephemeral view for existing receipt/causal bookkeeping and the bulk-delete guard.
 * This is not a v2 publication adapter: canonical minimal fields need not satisfy old readers.
 * Shared proof is expanded only for the guard; canonical persistence retains one proof tuple.
 */
internal fun AppSyncCanonicalOperation.toLegacyOperationView(account: String,
    proof: AppSyncCanonicalDeleteProof? = null): SyncOperation {
    val domain = AppSyncCanonicalSchema.domainsById.getValue(domainId)
    val body = fields.mapKeys { domain.fieldsById.getValue(it.key).name }
        .mapValues { it.value.legacyValue() }.toMutableMap()
    if (proof != null) {
        require(authorizationId == proof.authorizationId && domainId == proof.domainId)
        body[AppSyncBulkDeleteProofFields.SCOPE] = proof.scope
        body[AppSyncBulkDeleteProofFields.COUNT] = proof.operationCount.toString()
        body[AppSyncBulkDeleteProofFields.EXPIRES_AT] = proof.expiresAtEpochMillis.toString()
    }
    val device = SyncDeviceId(deviceId)
    val epoch = SyncDeviceEpoch(deviceEpoch)
    val sequence = SyncSequence(sequence)
    return SyncOperation(SyncOperation.idFor(device, epoch, sequence), device, epoch, sequence,
        SyncAccountBinding(account), SyncDomainId(domain.name), SyncEntityId(entityId), generation,
        kind, body, SyncCausalContext(causalContext), createdAtEpochMillis, origin, authorizationId)
}

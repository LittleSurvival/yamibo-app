package me.thenano.yamibo.yamibo_app.repository.appsync.remote

import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperation
import me.thenano.yamibo.yamibo_app.repository.backup.PORTABLE_LEGACY_EVENT_PREFIX
import me.thenano.yamibo.yamibo_app.repository.backup.CloudBackupPayloadCodec

/** Legacy wire framing does not make v3-only event evidence readable by older clients.
 * Readers retain support; ordinary legacy publication is rejected. The dedicated sanitized
 * fallback adapter retains portable evidence only behind the compatible reader-3 cohort gate.
 */
internal object AppSyncLegacyReaderCompatibility {
    const val REASON = "Portable event identity requires v3 reader compatibility"
    fun requiresV3(operation: SyncOperation): Boolean = operation.domainId.value == "favorite.update-event" &&
        operation.fields["sourceDiscriminator"]?.startsWith(PORTABLE_LEGACY_EVENT_PREFIX) == true
    fun requiresV3(payload: AppSyncJournalPayload): Boolean = payload.operations.any(::requiresV3)
    fun requiresV3(payload: AppSyncCheckpointPayload,
        snapshot: me.thenano.yamibo.yamibo_app.repository.backup.YamiboBackupFile? = null): Boolean {
        if (payload.resolvedEntities.any { entity ->
            entity.fields.values.any { requiresV3(it.operation) } ||
                listOfNotNull(entity.tombstone, entity.relationOperation).any(::requiresV3)
        }) return true
        return (snapshot ?: CloudBackupPayloadCodec().decode(payload.encodedSnapshot).getOrThrow()).favoriteUpdates.events.any {
            it.sourceDiscriminator.startsWith(PORTABLE_LEGACY_EVENT_PREFIX)
        }
    }
}

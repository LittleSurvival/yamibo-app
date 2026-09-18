package me.thenano.yamibo.yamibo_app.repository.appsync.engine

import me.thenano.yamibo.yamibo_app.repository.appsync.operation.*
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.*
import me.thenano.yamibo.yamibo_app.repository.appsync.schema.*
import me.thenano.yamibo.yamibo_app.repository.appsync.withPortableAppSyncPayloads
import me.thenano.yamibo.yamibo_app.repository.backup.YamiboBackupFile

internal enum class AppSyncV2CheckpointFailure { Compatibility, Projection, Snapshot, Envelope }
internal sealed interface AppSyncV2CheckpointPreparation {
    data class Ready(val payload: AppSyncCheckpointPayload, val envelope: String, val fingerprint: String) : AppSyncV2CheckpointPreparation
    data class NeedsAttention(val reason: AppSyncV2CheckpointFailure) : AppSyncV2CheckpointPreparation
}

/** Snapshot values must match canonical winners; the snapshot never supplies their provenance.
 * Pure preparation does not authorize cloud writes, source acknowledgement, or cleanup.
 */
internal class AppSyncSanitizedV2CheckpointPreparation(private val canWrite: () -> Boolean = { false }) {
    fun prepare(checkpoint: AppSyncCanonicalCheckpoint, snapshot: YamiboBackupFile): AppSyncV2CheckpointPreparation {
        if (!canWrite()) return fail(AppSyncV2CheckpointFailure.Compatibility)
        val result = encodeFrozen(checkpoint, snapshot)
        return if (canWrite()) result else fail(AppSyncV2CheckpointFailure.Compatibility)
    }

    internal fun encodeFrozen(checkpoint: AppSyncCanonicalCheckpoint, snapshot: YamiboBackupFile): AppSyncV2CheckpointPreparation {
        val exported = AppSyncSanitizedV2ProjectionExporter().export(checkpoint)
        if (exported !is AppSyncV2ProjectionExport.Ready) return fail(AppSyncV2CheckpointFailure.Projection)
        return try {
            val source = snapshot.withPortableAppSyncPayloads()
            if (!AppSyncCanonicalSnapshotValidation.matches(checkpoint, source)) return fail(AppSyncV2CheckpointFailure.Snapshot)
            val events = checkpoint.entities.filter { it.domainId == 17 && it.tombstone == null }.associateBy { it.entityId }
            val portable = source.copy(favoriteUpdates = source.favoriteUpdates.copy(events = source.favoriteUpdates.events.map { event ->
                val values = AppSyncCanonicalMaterializedFields.restore(events.getValue(event.syncId))
                event.copy(forumName = null, latestPostTitle = null, title = values["title"].orEmpty(),
                    sourceDiscriminator = values.getValue("sourceDiscriminator")!!)
            }))
            val codec = AppSyncCheckpointEnvelopeCodec()
            val payload = codec.createPayload(checkpoint.checkpointId, SyncAccountBinding(checkpoint.accountBinding),
                SyncCausalContext(checkpoint.coverage), portable, exported.entities, exported.entities.mapNotNull { entity ->
                    entity.tombstone?.let { AppSyncCheckpointTombstone(entity.key.domainId, entity.key.entityId,
                        entity.key.generation, it.operationId) }
                }, checkpoint.createdAtEpochMillis)
            val envelope = codec.encodeSanitizedFallback(payload)
            val decoded = (codec.validate(envelope) as? AppSyncCheckpointValidation.Valid)?.envelope
                ?: return fail(AppSyncV2CheckpointFailure.Envelope)
            if (decoded.payload != payload || !AppSyncCanonicalSnapshotValidation.matches(checkpoint, decoded.snapshot))
                return fail(AppSyncV2CheckpointFailure.Envelope)
            val imported = AppSyncCanonicalProjectionImporter().prepare(checkpoint.accountBinding, checkpoint.checkpointId,
                checkpoint.createdAtEpochMillis, checkpoint.coverage, decoded.payload.resolvedEntities)
            val canonical = AppSyncCanonicalCheckpointCodec()
            if (imported !is AppSyncProjectionImportResult.Ready || canonical.encode(imported.checkpoint) != canonical.encode(checkpoint))
                return fail(AppSyncV2CheckpointFailure.Envelope)
            AppSyncV2CheckpointPreparation.Ready(payload, envelope, decoded.fingerprint)
        } catch (_: Exception) { fail(AppSyncV2CheckpointFailure.Envelope) }
    }

    private fun fail(reason: AppSyncV2CheckpointFailure) = AppSyncV2CheckpointPreparation.NeedsAttention(reason)
}

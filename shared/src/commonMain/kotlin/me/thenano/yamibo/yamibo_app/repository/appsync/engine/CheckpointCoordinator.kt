package me.thenano.yamibo.yamibo_app.repository.appsync.engine

import io.github.littlesurvival.dto.value.FormHash
import me.thenano.yamibo.yamibo_app.repository.appsync.domain.stableAppSyncFingerprint
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncOperationLifecycle
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncVerifiedCheckpoint
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncAccountBinding
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncCheckpointEnvelopeCodec
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncCheckpointTombstone
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncCheckpointValidation
import me.thenano.yamibo.yamibo_app.repository.backup.YamiboBackupFile
import me.thenano.yamibo.yamibo_app.store.appsync.AppSyncOperationStore

internal sealed interface CheckpointCreationResult {
    data object NotNeeded : CheckpointCreationResult
    data class Verified(val checkpointId: String) : CheckpointCreationResult
    data class RetryableFailure(val reason: String) : CheckpointCreationResult
    data class Paused(val reason: String) : CheckpointCreationResult
    data class StoragePressure(val reason: String) : CheckpointCreationResult
}

internal class CheckpointCoordinator(
    private val store: AppSyncOperationStore,
    private val remote: AppSyncJournalRemote,
    private val domainState: SyncDomainStateAdapter,
    private val snapshot: () -> YamiboBackupFile,
    private val nowMillis: () -> Long,
    private val minimumAcknowledgedOperations: Int = 64,
    private val maximumRetainedCheckpoints: Int = 3,
    private val codec: AppSyncCheckpointEnvelopeCodec = AppSyncCheckpointEnvelopeCodec(),
) {
    suspend fun createIfNeeded(
        accountBinding: SyncAccountBinding,
        formHash: FormHash,
    ): CheckpointCreationResult {
        if (store.pendingOperations().isNotEmpty()) return CheckpointCreationResult.NotNeeded
        retentionFailure(
            remote.enforceCheckpointRetention(
                accountBinding,
                formHash,
                maximumRetainedCheckpoints,
                store.pinnedRetirementCheckpointIds(),
            ),
        )?.let { return it }
        val acknowledged = store.allOutboxOperations().filter {
            it.second == AppSyncOperationLifecycle.Acknowledged
        }
        if (acknowledged.size < minimumAcknowledgedOperations) {
            return CheckpointCreationResult.NotNeeded
        }
        val coverage = store.causalContext()
        val entities = domainState.currentState().values
        val checkpointId = deterministicCheckpointId(coverage.asStableMap(), entities)
        if (store.verifiedCheckpoints().any { it.checkpointId == checkpointId }) {
            return CheckpointCreationResult.NotNeeded
        }
        val createdAt = nowMillis()
        val payload = codec.createPayload(
            checkpointId = checkpointId,
            accountBinding = accountBinding,
            coverage = coverage,
            snapshot = snapshot(),
            resolvedEntities = entities,
            tombstones = entities.mapNotNull { entity ->
                entity.tombstone?.let {
                    AppSyncCheckpointTombstone(
                        domainId = entity.key.domainId,
                        entityId = entity.key.entityId,
                        entityGeneration = entity.key.generation,
                        operationId = it.operationId,
                    )
                }
            },
            createdAtEpochMillis = createdAt,
        )
        val expectedEnvelope = codec.validate(codec.encode(payload))
            as AppSyncCheckpointValidation.Valid
        return when (val published = remote.publishCheckpoint(payload, formHash)) {
            is AppSyncCheckpointPublishResult.Verified -> {
                val checkpoint = published.checkpoint
                val reloaded = checkpoint.envelope
                if (
                    reloaded.payload.checkpointId != payload.checkpointId ||
                    reloaded.payload.accountBinding != payload.accountBinding ||
                    reloaded.payload.coverage != payload.coverage ||
                    reloaded.fingerprint != expectedEnvelope.envelope.fingerprint
                ) {
                    return CheckpointCreationResult.RetryableFailure(
                        "Authoritative checkpoint reload did not match the published payload",
                    )
                }
                store.saveVerifiedCheckpoint(
                    AppSyncVerifiedCheckpoint(
                        checkpointId = checkpointId,
                        blogId = checkpoint.remoteId.toLongOrNull(),
                        coverage = coverage,
                        payloadFingerprint = checkpoint.envelope.fingerprint,
                        createdAtEpochMillis = createdAt,
                        verifiedAtEpochMillis = nowMillis(),
                    ),
                )
                retentionFailure(
                    remote.enforceCheckpointRetention(
                        accountBinding,
                        formHash,
                        maximumRetainedCheckpoints,
                        store.pinnedRetirementCheckpointIds(),
                    ),
                )?.let { return it }
                CheckpointCreationResult.Verified(checkpointId)
            }
            is AppSyncCheckpointPublishResult.Unknown ->
                CheckpointCreationResult.RetryableFailure(published.reason)
            AppSyncCheckpointPublishResult.FormExpired ->
                CheckpointCreationResult.Paused("Cached FormHash expired")
            is AppSyncCheckpointPublishResult.StoragePressure ->
                CheckpointCreationResult.StoragePressure(
                    "Checkpoint requires ${published.encodedChars} chars; " +
                        "safe provider limit is ${published.limitChars}",
                )
            is AppSyncCheckpointPublishResult.TerminalFailure ->
                CheckpointCreationResult.Paused(published.reason)
        }
    }

    private fun retentionFailure(
        result: AppSyncCheckpointRetentionResult,
    ): CheckpointCreationResult? = when (result) {
        AppSyncCheckpointRetentionResult.NotNeeded -> null
        is AppSyncCheckpointRetentionResult.Verified -> {
            store.retainVerifiedCheckpoints(result.retainedCheckpointIds)
            null
        }
        is AppSyncCheckpointRetentionResult.StoragePressure -> {
            store.retainVerifiedCheckpoints(result.retainedCheckpointIds)
            CheckpointCreationResult.StoragePressure(result.reason)
        }
        AppSyncCheckpointRetentionResult.FormExpired ->
            CheckpointCreationResult.Paused("Cached FormHash expired during checkpoint retention")
        is AppSyncCheckpointRetentionResult.RetryableFailure ->
            CheckpointCreationResult.RetryableFailure(result.reason)
        is AppSyncCheckpointRetentionResult.TerminalFailure ->
            CheckpointCreationResult.Paused(result.reason)
    }

    private fun deterministicCheckpointId(
        coverage: Map<String, Long>,
        entities: Collection<ResolvedSyncEntity>,
    ): String {
        val material = buildString {
            coverage.forEach { (key, sequence) -> append(key).append('=').append(sequence).append(';') }
            entities.sortedWith(
                compareBy(
                    { it.key.domainId.value },
                    { it.key.entityId.value },
                    { it.key.generation },
                ),
            ).forEach {
                append(it.key.domainId.value)
                append('|').append(it.key.entityId.value)
                append('|').append(it.key.generation)
                append('|').append(it.fields.values.map { field -> field.operation.operationId.value }.sorted())
                append('|').append(it.tombstone?.operationId?.value)
                append(';')
            }
        }
        return "cp-${stableAppSyncFingerprint(material).take(24)}"
    }
}

package me.thenano.yamibo.yamibo_app.repository.appsync.model

import kotlinx.serialization.Serializable
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncAccountBinding
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncDeviceEpoch
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncDeviceId
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncWriterNonce
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncCausalContext
import me.thenano.yamibo.yamibo_app.repository.appsync.AppSyncScheduleSettings

@Serializable
enum class AppSyncInstallationState {
    Unbound,
    Bootstrapping,
    Active,
    PausedAuth,
    PausedProvider,
    Quarantined,
    RebootstrapRequired,
    ;

    companion object
}

@Serializable
enum class AppSyncOperationLifecycle {
    PendingLocal,
    PublishedUnverified,
    Acknowledged,
    AppliedRemote,
    Quarantined,
    Compacted,
    DiscardedByForcePull,
    DiscardedByRebootstrap,
    ;

    companion object
}

internal data class AppSyncInstallation(
    val databaseGeneration: String,
    val accountBinding: SyncAccountBinding?,
    val deviceId: SyncDeviceId,
    val deviceEpoch: SyncDeviceEpoch,
    val writerNonce: SyncWriterNonce,
    val nextSequence: Long,
    val state: AppSyncInstallationState,
    val lastVerifiedHeartbeatAt: Long?,
    val journalBlogId: Long?,
    val lastFullDiscoveryAt: Long?,
    val automaticEnabled: Boolean,
    val scheduleSettings: AppSyncScheduleSettings,
    val requestedTriggerGeneration: Long,
    val accountedTriggerGeneration: Long,
)

internal data class AppSyncRunLease(
    val ownerId: String,
    val acquiredAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
)

internal data class AppSyncBulkDeleteAuthorization(
    val authorizationId: String,
    val domainId: String,
    val scopeKey: String,
    val operationCount: Long,
    val expiresAtEpochMillis: Long,
    val consumedAtEpochMillis: Long?,
)

internal data class AppSyncVerifiedCheckpoint(
    val checkpointId: String,
    val blogId: Long?,
    val coverage: SyncCausalContext,
    val payloadFingerprint: String,
    val createdAtEpochMillis: Long,
    val verifiedAtEpochMillis: Long,
)

internal data class AppSyncBootstrapRollbackSnapshot(
    val accountBinding: SyncAccountBinding,
    val databaseGeneration: String,
    val encodedSnapshot: String,
    val createdAtEpochMillis: Long,
)

internal data class AppSyncReplicaObservation(
    val accountBinding: SyncAccountBinding,
    val replicaKey: String,
    val sourceBlogId: Long,
    val fingerprint: String,
    val publishedThroughSequence: Long,
    val firstObservedUnchangedAtEpochMillis: Long,
    val lastObservedAtEpochMillis: Long,
)

internal enum class AppSyncJournalRetirementStage {
    IntentRecorded,
    IndexRetirementPublished,
    DeleteRequested,
    Completed,
    Blocked,
    Absorbed,
}

internal data class AppSyncJournalRetirementIntent(
    val accountBinding: SyncAccountBinding,
    val replicaKey: String,
    val sourceBlogId: Long,
    val fingerprint: String,
    val publishedThroughSequence: Long,
    val checkpointId: String,
    val checkpointFingerprint: String,
    val checkpointVectorHash: String,
    val activeSetHash: String,
    val stage: AppSyncJournalRetirementStage,
    val attempts: Long,
    val lastResultCode: String?,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val completedAtEpochMillis: Long?,
)

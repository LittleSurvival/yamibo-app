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
    SupersededByRecovery,
    ;

    companion object
}

internal enum class AppSyncRecoveryPhase {
    Classifying,
    Staging,
    PublishingSegments,
    PublishingRoot,
    CommittingIndex,
    ActivatingLocal,
    Cleaning,
    Completed,
    NeedsAttention,
    ;

    companion object
}

internal enum class AppSyncRecoveryMode {
    LegacyShadow,
    SegmentedJournal,
    ;

    companion object
}

internal data class AppSyncRecoverySession(
    val sessionId: String,
    val accountBinding: SyncAccountBinding,
    val mode: AppSyncRecoveryMode,
    val sourceDeviceId: SyncDeviceId,
    val sourceDeviceEpoch: SyncDeviceEpoch,
    val targetDeviceId: SyncDeviceId,
    val targetDeviceEpoch: SyncDeviceEpoch,
    val targetWriterNonce: SyncWriterNonce,
    val targetFirstSequence: Long,
    val generationId: String,
    val sourceOperationIds: Set<String>,
    val replacementFingerprint: String,
    val phase: AppSyncRecoveryPhase,
    val retryCount: Long,
    val nextRetryAtEpochMillis: Long?,
    val lastErrorCategory: String?,
    val blockingDomain: String?,
    val redactedBlockingEntity: String?,
    val rootBlogId: Long?,
    val rootFingerprint: String?,
    val indexCommitted: Boolean,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val completedAtEpochMillis: Long?,
    val acknowledgedSourceOperationIds: Set<String> = emptySet(),
)

internal data class AppSyncRecoverySegmentWrite(
    val sessionId: String,
    val segmentIndex: Int,
    val segmentCount: Int,
    val expectedFingerprint: String,
    val nextBlogId: Long?,
    val blogId: Long?,
    val verifiedFingerprint: String?,
    val verifiedAtEpochMillis: Long?,
)

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

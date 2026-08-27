package me.thenano.yamibo.yamibo_app.repository.appsync.remote

import io.github.littlesurvival.dto.value.FormHash
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncRecoveryPhase
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncRecoveryMode
import me.thenano.yamibo.yamibo_app.store.appsync.SqlDelightAppSyncRecoveryStore

internal sealed interface AppSyncSegmentedCheckpointCommitResult {
    data class Verified(val rootBlogId: Long) : AppSyncSegmentedCheckpointCommitResult
    data object FormExpired : AppSyncSegmentedCheckpointCommitResult
    data class Retryable(val reason: String) : AppSyncSegmentedCheckpointCommitResult
    data class Conflict(val reason: String) : AppSyncSegmentedCheckpointCommitResult
    data class Terminal(val reason: String) : AppSyncSegmentedCheckpointCommitResult
}

/** Commits a Checkpoint chain without exposing it to compaction before Index verification. */
internal class AppSyncSegmentedCheckpointCommitCoordinator(
    private val publisher: AppSyncSegmentPublisher,
    private val indexCommitter: AppSyncSegmentIndexCommitter,
    private val recoveryStore: SqlDelightAppSyncRecoveryStore,
    private val nowMillis: () -> Long,
) {
    suspend fun commit(
        sessionId: String,
        checkpointId: String,
        canonicalEnvelope: String,
        classSelection: AppSyncBlogClassSelection,
        formHash: FormHash,
    ): AppSyncSegmentedCheckpointCommitResult {
        var session = recoveryStore.session(sessionId)
            ?: return AppSyncSegmentedCheckpointCommitResult.Terminal("Recovery session is missing")
        require(session.mode == AppSyncRecoveryMode.SegmentedCheckpoint)
        if (session.phase == AppSyncRecoveryPhase.Completed) {
            return AppSyncSegmentedCheckpointCommitResult.Verified(
                session.rootBlogId
                    ?: return AppSyncSegmentedCheckpointCommitResult.Terminal(
                        "Completed checkpoint root is missing",
                    ),
            )
        }
        if (session.phase != AppSyncRecoveryPhase.ActivatingLocal) {
            when (val published = publisher.publish(
                sessionId = sessionId,
                canonicalEnvelope = canonicalEnvelope,
                kind = AppSyncSegmentPayloadKind.Checkpoint,
                identity = checkpointId,
                classSelection = classSelection,
                formHash = formHash,
            )) {
                is AppSyncSegmentPublishResult.ReadyToCommitIndex -> Unit
                AppSyncSegmentPublishResult.FormExpired ->
                    return AppSyncSegmentedCheckpointCommitResult.FormExpired
                is AppSyncSegmentPublishResult.Retryable ->
                    return AppSyncSegmentedCheckpointCommitResult.Retryable(published.reason)
                is AppSyncSegmentPublishResult.Terminal ->
                    return AppSyncSegmentedCheckpointCommitResult.Terminal(published.reason)
            }
            when (val committed = indexCommitter.commitCheckpointRoot(
                sessionId, checkpointId, classSelection, formHash,
            )) {
                AppSyncSegmentIndexCommitResult.Verified -> Unit
                AppSyncSegmentIndexCommitResult.FormExpired ->
                    return AppSyncSegmentedCheckpointCommitResult.FormExpired
                is AppSyncSegmentIndexCommitResult.Retryable ->
                    return AppSyncSegmentedCheckpointCommitResult.Retryable(committed.reason)
                is AppSyncSegmentIndexCommitResult.Conflict ->
                    return AppSyncSegmentedCheckpointCommitResult.Conflict(committed.reason)
                is AppSyncSegmentIndexCommitResult.Terminal ->
                    return AppSyncSegmentedCheckpointCommitResult.Terminal(committed.reason)
            }
            session = recoveryStore.session(sessionId)
                ?: return AppSyncSegmentedCheckpointCommitResult.Terminal(
                    "Recovery session disappeared",
                )
        }
        if (session.phase != AppSyncRecoveryPhase.ActivatingLocal || !session.indexCommitted) {
            return AppSyncSegmentedCheckpointCommitResult.Retryable(
                "Verified Index commit has not reached local activation",
            )
        }
        recoveryStore.activateCommittedSession(sessionId, nowMillis())
        val completed = recoveryStore.session(sessionId)
            ?: return AppSyncSegmentedCheckpointCommitResult.Terminal(
                "Activated recovery session disappeared",
            )
        return AppSyncSegmentedCheckpointCommitResult.Verified(requireNotNull(completed.rootBlogId))
    }
}

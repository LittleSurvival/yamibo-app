package me.thenano.yamibo.yamibo_app.repository.appsync.remote

import io.github.littlesurvival.dto.value.FormHash
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncRecoveryPhase
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncRecoveryMode
import me.thenano.yamibo.yamibo_app.store.appsync.SqlDelightAppSyncRecoveryStore

internal sealed interface AppSyncSegmentedJournalCommitResult {
    data class Verified(
        val rootBlogId: Long,
        val acknowledgedOperationIds: Set<String>,
    ) : AppSyncSegmentedJournalCommitResult

    data object FormExpired : AppSyncSegmentedJournalCommitResult
    data class Retryable(val reason: String) : AppSyncSegmentedJournalCommitResult
    data class Conflict(val reason: String) : AppSyncSegmentedJournalCommitResult
    data class Terminal(val reason: String) : AppSyncSegmentedJournalCommitResult
}

/** Runs the v2 commit protocol without acknowledging any operation before verified activation. */
internal class AppSyncSegmentedJournalCommitCoordinator(
    private val publisher: AppSyncSegmentPublisher,
    private val indexCommitter: AppSyncSegmentIndexCommitter,
    private val recoveryStore: SqlDelightAppSyncRecoveryStore,
    private val nowMillis: () -> Long,
) {
    suspend fun commit(
        sessionId: String,
        canonicalEnvelope: String,
        identity: String,
        classSelection: AppSyncBlogClassSelection,
        formHash: FormHash,
    ): AppSyncSegmentedJournalCommitResult {
        var session = recoveryStore.session(sessionId)
            ?: return AppSyncSegmentedJournalCommitResult.Terminal("Recovery session is missing")
        if (session.phase == AppSyncRecoveryPhase.Completed) {
            val rootBlogId = session.rootBlogId
                ?: return AppSyncSegmentedJournalCommitResult.Terminal("Completed recovery root is missing")
            return AppSyncSegmentedJournalCommitResult.Verified(
                rootBlogId,
                acknowledgedOperationIds(session),
            )
        }
        if (session.phase != AppSyncRecoveryPhase.ActivatingLocal) {
            when (
                val published = publisher.publish(
                    sessionId,
                    canonicalEnvelope,
                    AppSyncSegmentPayloadKind.Journal,
                    identity,
                    classSelection,
                    formHash,
                )
            ) {
                is AppSyncSegmentPublishResult.ReadyToCommitIndex -> Unit
                AppSyncSegmentPublishResult.FormExpired ->
                    return AppSyncSegmentedJournalCommitResult.FormExpired
                is AppSyncSegmentPublishResult.Retryable ->
                    return AppSyncSegmentedJournalCommitResult.Retryable(published.reason)
                is AppSyncSegmentPublishResult.Terminal ->
                    return AppSyncSegmentedJournalCommitResult.Terminal(published.reason)
            }
            when (val committed = indexCommitter.commitJournalRoot(sessionId, classSelection, formHash)) {
                AppSyncSegmentIndexCommitResult.Verified -> Unit
                AppSyncSegmentIndexCommitResult.FormExpired ->
                    return AppSyncSegmentedJournalCommitResult.FormExpired
                is AppSyncSegmentIndexCommitResult.Retryable ->
                    return AppSyncSegmentedJournalCommitResult.Retryable(committed.reason)
                is AppSyncSegmentIndexCommitResult.Conflict ->
                    return AppSyncSegmentedJournalCommitResult.Conflict(committed.reason)
                is AppSyncSegmentIndexCommitResult.Terminal ->
                    return AppSyncSegmentedJournalCommitResult.Terminal(committed.reason)
            }
            session = recoveryStore.session(sessionId)
                ?: return AppSyncSegmentedJournalCommitResult.Terminal("Recovery session disappeared")
        }
        if (session.phase != AppSyncRecoveryPhase.ActivatingLocal || !session.indexCommitted) {
            return AppSyncSegmentedJournalCommitResult.Retryable(
                "Verified Index commit has not reached local activation",
            )
        }
        val acknowledged = acknowledgedOperationIds(session)
        recoveryStore.activateCommittedSession(sessionId, nowMillis())
        val completed = recoveryStore.session(sessionId)
            ?: return AppSyncSegmentedJournalCommitResult.Terminal("Activated recovery session disappeared")
        return AppSyncSegmentedJournalCommitResult.Verified(
            rootBlogId = requireNotNull(completed.rootBlogId),
            acknowledgedOperationIds = acknowledged,
        )
    }

    private fun acknowledgedOperationIds(
        session: me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncRecoverySession,
    ): Set<String> = when (session.mode) {
        AppSyncRecoveryMode.LegacyShadow -> recoveryStore.shadowOperations(session.sessionId)
            .mapTo(linkedSetOf()) { it.operationId.value }
        AppSyncRecoveryMode.SegmentedJournal -> session.sourceOperationIds
    }
}

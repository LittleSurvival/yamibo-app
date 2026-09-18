package me.thenano.yamibo.yamibo_app.repository.appsync.remote

import io.github.littlesurvival.dto.value.FormHash
import kotlinx.coroutines.CancellationException
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncCanonicalActivationResult
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncCanonicalCheckpointActivator
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncCanonicalCloudPlan
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncRecoveryAttempts
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncRecoveryFailureCategory
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncRecoveryMode
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncRecoveryPhase
import me.thenano.yamibo.yamibo_app.store.appsync.SqlDelightAppSyncRecoveryStore

/** One native recovery run under the caller's lease. Retry deadlines are durable, but the
 * caller must enqueue executable work before displaying a scheduled/waiting state.
 */
internal class AppSyncV3CommitCoordinator(
    private val committer: AppSyncV3IndexCommitter,
    private val recovery: SqlDelightAppSyncRecoveryStore,
    private val nowMillis: () -> Long,
    private val checkpointActivator: AppSyncCanonicalCheckpointActivator? = null,
    private val canRun: suspend () -> Boolean = { false },
) {
    suspend fun commit(sessionId: String, envelope: String, identity: String,
        selection: AppSyncBlogClassSelection.Existing, formHash: FormHash,
        cloud: AppSyncCanonicalCloudPlan.Ready? = null): AppSyncSegmentedJournalCommitResult {
        if (!canRun()) return AppSyncSegmentedJournalCommitResult.Terminal("Native recovery is disabled")
        val session = recovery.session(sessionId) ?: return AppSyncSegmentedJournalCommitResult.Terminal("Native session is missing")
        if (recovery.payloadTransportVersion(sessionId)?.let { it != 3L } == true)
            return AppSyncSegmentedJournalCommitResult.Terminal("Frozen recovery payload requires another protocol")
        if (session.mode == AppSyncRecoveryMode.LegacyShadow) return AppSyncSegmentedJournalCommitResult.Terminal("Native recovery cannot run legacy shadow mode")
        if (session.phase == AppSyncRecoveryPhase.NeedsAttention) return AppSyncSegmentedJournalCommitResult.Terminal("Native recovery requires attention")
        if ((session.nextRetryAtEpochMillis ?: 0) > nowMillis())
            return AppSyncSegmentedJournalCommitResult.Retryable("Persisted retry deadline has not arrived")
        val result = try {
            advance(sessionId, envelope, identity, selection, formHash, cloud)
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: IllegalArgumentException) { AppSyncSegmentedJournalCommitResult.Conflict("Native recovery evidence changed")
        } catch (_: Exception) { AppSyncSegmentedJournalCommitResult.Retryable("Native recovery interrupted") }
        val category = when (result) {
            is AppSyncSegmentedJournalCommitResult.Retryable -> AppSyncRecoveryFailureCategory.AmbiguousWrite
            is AppSyncSegmentedJournalCommitResult.Conflict -> AppSyncRecoveryFailureCategory.IndexConflict
            is AppSyncSegmentedJournalCommitResult.Terminal -> AppSyncRecoveryFailureCategory.PolicyViolation
            else -> null
        }
        if (category != null && AppSyncRecoveryAttempts(recovery, nowMillis).recordFailure(sessionId, category))
            return AppSyncSegmentedJournalCommitResult.Terminal("Native recovery requires attention")
        return result
    }

    private suspend fun advance(sessionId: String, envelope: String, identity: String,
        selection: AppSyncBlogClassSelection.Existing, formHash: FormHash,
        cloud: AppSyncCanonicalCloudPlan.Ready?): AppSyncSegmentedJournalCommitResult {
        var session = requireNotNull(recovery.session(sessionId))
        val checkpoint = session.mode == AppSyncRecoveryMode.SegmentedCheckpoint
        if (checkpoint && checkpointActivator == null)
            return AppSyncSegmentedJournalCommitResult.Terminal("Native checkpoint activation is not configured")
        if (session.phase in setOf(AppSyncRecoveryPhase.Classifying, AppSyncRecoveryPhase.Staging)) {
            recovery.startSegmentedJournal(sessionId, nowMillis())
            session = requireNotNull(recovery.session(sessionId))
        }
        if (session.phase !in setOf(AppSyncRecoveryPhase.ActivatingLocal, AppSyncRecoveryPhase.Completed)) {
            when (val result = committer.commit(sessionId, envelope,
                if (checkpoint) AppSyncV3PayloadKind.Checkpoint else AppSyncV3PayloadKind.Journal, identity, selection, formHash)) {
                AppSyncSegmentIndexCommitResult.Verified -> Unit
                AppSyncSegmentIndexCommitResult.FormExpired -> return AppSyncSegmentedJournalCommitResult.FormExpired
                is AppSyncSegmentIndexCommitResult.Retryable -> return AppSyncSegmentedJournalCommitResult.Retryable(result.reason)
                is AppSyncSegmentIndexCommitResult.Conflict -> return AppSyncSegmentedJournalCommitResult.Conflict(result.reason)
                is AppSyncSegmentIndexCommitResult.Terminal -> return AppSyncSegmentedJournalCommitResult.Terminal(result.reason)
            }
        }
        require(recovery.usesNativeTransport(sessionId))
        if (checkpoint) {
            when (val result = requireNotNull(checkpointActivator).activateRecovery(recovery, sessionId, cloud)) {
                is AppSyncCanonicalActivationResult.Applied -> if (!result.settingsReconciled)
                    return AppSyncSegmentedJournalCommitResult.Retryable("Native settings reconciliation is pending")
                is AppSyncCanonicalActivationResult.NeedsAttention ->
                    return AppSyncSegmentedJournalCommitResult.Conflict(result.reason)
            }
        } else recovery.activateCommittedSession(sessionId, nowMillis())
        session = requireNotNull(recovery.session(sessionId))
        require(session.phase == AppSyncRecoveryPhase.Completed && session.indexCommitted)
        return AppSyncSegmentedJournalCommitResult.Verified(requireNotNull(session.rootBlogId),
            if (checkpoint) emptySet() else session.sourceOperationIds)
    }
}

package me.thenano.yamibo.yamibo_app.repository.appsync.remote

import io.github.littlesurvival.dto.value.FormHash
import kotlinx.coroutines.CancellationException
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.*
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncRecoveryMode
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncRecoveryPhase
import me.thenano.yamibo.yamibo_app.store.appsync.SqlDelightAppSyncRecoveryStore

/** Advances immutable fallback publication under the caller's engine lease. A retry deadline
 * is not an enqueued worker; the service must persist and enqueue executable continuation.
 */
internal class AppSyncSanitizedV2CommitCoordinator(
    private val committer: AppSyncSanitizedV2IndexCommitter,
    private val recovery: SqlDelightAppSyncRecoveryStore,
    private val activator: AppSyncCanonicalCheckpointActivator,
    private val nowMillis: () -> Long,
    private val canWrite: suspend () -> Boolean = { false },
) {
    suspend fun commit(sessionId: String, selection: AppSyncBlogClassSelection.Existing,
        formHash: FormHash, cloud: AppSyncCanonicalCloudPlan.Ready): AppSyncSegmentedJournalCommitResult {
        val session = recovery.session(sessionId)
            ?: return AppSyncSegmentedJournalCommitResult.Terminal("Fallback session is missing")
        if (session.mode != AppSyncRecoveryMode.SegmentedJournal || !recovery.usesNativeTransport(sessionId) ||
            !recovery.usesSanitizedV2Transport(sessionId))
            return AppSyncSegmentedJournalCommitResult.Terminal("Frozen recovery requires another protocol")
        if (session.phase == AppSyncRecoveryPhase.NeedsAttention)
            return AppSyncSegmentedJournalCommitResult.Terminal("Fallback recovery requires attention")
        if (!session.indexCommitted && !canWrite())
            return AppSyncSegmentedJournalCommitResult.Terminal("Fallback publication is disabled")
        if ((session.nextRetryAtEpochMillis ?: 0L) > nowMillis())
            return AppSyncSegmentedJournalCommitResult.Retryable("Persisted retry deadline has not arrived")
        val result = try {
            advance(sessionId, selection, formHash, cloud)
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: IllegalArgumentException) { AppSyncSegmentedJournalCommitResult.Conflict("Fallback recovery evidence changed")
        } catch (_: Exception) { AppSyncSegmentedJournalCommitResult.Retryable("Fallback recovery interrupted") }
        if (result is AppSyncSegmentedJournalCommitResult.Terminal && !canWrite()) return result
        val category = when (result) {
            is AppSyncSegmentedJournalCommitResult.Retryable -> AppSyncRecoveryFailureCategory.AmbiguousWrite
            is AppSyncSegmentedJournalCommitResult.Conflict -> AppSyncRecoveryFailureCategory.IndexConflict
            is AppSyncSegmentedJournalCommitResult.Terminal -> AppSyncRecoveryFailureCategory.PolicyViolation
            else -> null
        }
        if (category != null && AppSyncRecoveryAttempts(recovery, nowMillis).recordFailure(sessionId, category))
            return AppSyncSegmentedJournalCommitResult.Terminal("Fallback recovery requires attention")
        return result
    }

    private suspend fun advance(sessionId: String, selection: AppSyncBlogClassSelection.Existing,
        formHash: FormHash, cloud: AppSyncCanonicalCloudPlan.Ready): AppSyncSegmentedJournalCommitResult {
        var session = requireNotNull(recovery.session(sessionId))
        require(cloud.checkpoint.document.accountBinding == session.accountBinding.value &&
            cloud.canonicalOperations.accountBinding == session.accountBinding.value)
        if (session.phase in setOf(AppSyncRecoveryPhase.Classifying, AppSyncRecoveryPhase.Staging)) {
            recovery.startSegmentedJournal(sessionId, nowMillis())
            session = requireNotNull(recovery.session(sessionId))
        }
        if (session.phase !in setOf(AppSyncRecoveryPhase.ActivatingLocal, AppSyncRecoveryPhase.Completed)) {
            when (val result = committer.commit(sessionId, selection, formHash, cloud.checkpoint)) {
                AppSyncSegmentIndexCommitResult.Verified -> Unit
                AppSyncSegmentIndexCommitResult.FormExpired -> return AppSyncSegmentedJournalCommitResult.FormExpired
                is AppSyncSegmentIndexCommitResult.Retryable -> return AppSyncSegmentedJournalCommitResult.Retryable(result.reason)
                is AppSyncSegmentIndexCommitResult.Conflict -> return AppSyncSegmentedJournalCommitResult.Conflict(result.reason)
                is AppSyncSegmentIndexCommitResult.Terminal -> return AppSyncSegmentedJournalCommitResult.Terminal(result.reason)
            }
        }
        when (val result = activator.activateJournalRecovery(recovery, sessionId, cloud)) {
            is AppSyncCanonicalActivationResult.Applied -> if (!result.settingsReconciled)
                return AppSyncSegmentedJournalCommitResult.Retryable("Fallback settings reconciliation is pending")
            is AppSyncCanonicalActivationResult.NeedsAttention ->
                return AppSyncSegmentedJournalCommitResult.Conflict(result.reason)
        }
        session = requireNotNull(recovery.session(sessionId))
        require(session.phase == AppSyncRecoveryPhase.Completed && session.indexCommitted)
        return AppSyncSegmentedJournalCommitResult.Verified(requireNotNull(session.rootBlogId), session.sourceOperationIds)
    }
}

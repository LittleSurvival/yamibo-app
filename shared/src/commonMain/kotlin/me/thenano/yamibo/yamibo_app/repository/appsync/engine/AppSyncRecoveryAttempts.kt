package me.thenano.yamibo.yamibo_app.repository.appsync.engine

import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncRecoveryPhase
import me.thenano.yamibo.yamibo_app.store.appsync.SqlDelightAppSyncRecoveryStore

/** The attempt budget follows immutable content, not process lifetime or worker invocation. */
internal class AppSyncRecoveryAttempts(
    private val store: SqlDelightAppSyncRecoveryStore,
    private val nowMillis: () -> Long,
    private val policy: AppSyncRecoveryRetryPolicy = AppSyncRecoveryRetryPolicy(),
) {
    fun recordFailure(sessionId: String, category: AppSyncRecoveryFailureCategory): Boolean {
        val session = requireNotNull(store.session(sessionId))
        if (session.phase == AppSyncRecoveryPhase.NeedsAttention) return true
        val pending = if (session.phase == AppSyncRecoveryPhase.PublishingSegments) {
            store.segmentWrites(sessionId).filter {
                it.blogId == null || it.verifiedFingerprint != it.expectedFingerprint
            }.maxByOrNull { it.segmentIndex }
        } else null
        val decision = policy.decide(
            session = session,
            category = category,
            payloadFingerprint = pending?.expectedFingerprint ?: session.replacementFingerprint,
            nowEpochMillis = nowMillis(),
            segmentedStrategy = true,
            retryTarget = pending?.let { "segment:${it.segmentIndex}" } ?: session.phase.name,
        )
        when (decision) {
            is AppSyncRecoveryRetryDecision.RetryAt -> store.transition(
                sessionId, session.phase, session.phase, nowMillis(),
                retryCount = decision.retryCount,
                retryIdentity = decision.retryIdentity,
                nextRetryAtEpochMillis = decision.atEpochMillis,
                lastErrorCategory = category.name,
            )
            is AppSyncRecoveryRetryDecision.NeedsAttention -> store.transition(
                sessionId, session.phase, AppSyncRecoveryPhase.NeedsAttention, nowMillis(),
                retryCount = decision.failureCount,
                retryIdentity = decision.retryIdentity,
                lastErrorCategory = decision.reason,
            )
            is AppSyncRecoveryRetryDecision.SwitchToSegmentation -> error("Already segmented")
        }
        return decision is AppSyncRecoveryRetryDecision.NeedsAttention
    }
}

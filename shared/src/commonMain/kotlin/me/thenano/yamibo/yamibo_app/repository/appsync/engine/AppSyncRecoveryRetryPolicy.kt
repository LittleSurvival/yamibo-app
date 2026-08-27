package me.thenano.yamibo.yamibo_app.repository.appsync.engine

import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncRecoverySession

internal enum class AppSyncRecoveryFailureCategory {
    Network,
    Timeout,
    ProviderMaintenance,
    AmbiguousWrite,
    IndexConflict,
    SingleBodyOversized,
    PolicyViolation,
    UnsupportedTotalSize,
}

internal sealed interface AppSyncRecoveryRetryDecision {
    data class RetryAt(
        val retryCount: Long,
        val atEpochMillis: Long,
        val retryIdentity: String,
    ) : AppSyncRecoveryRetryDecision

    data class SwitchToSegmentation(val retryIdentity: String) : AppSyncRecoveryRetryDecision
    data class NeedsAttention(val reason: String, val retryIdentity: String) : AppSyncRecoveryRetryDecision
}

internal class AppSyncRecoveryRetryPolicy(
    private val maximumRetries: Long = 5,
    private val baseDelayMillis: Long = 30_000,
    private val maximumDelayMillis: Long = 6 * 60 * 60 * 1_000L,
) {
    init {
        require(maximumRetries >= 0)
        require(baseDelayMillis > 0)
        require(maximumDelayMillis >= baseDelayMillis)
    }

    fun decide(
        session: AppSyncRecoverySession,
        category: AppSyncRecoveryFailureCategory,
        payloadFingerprint: String,
        nowEpochMillis: Long,
        encodedChars: Int? = null,
        targetChars: Int? = null,
        segmentedStrategy: Boolean,
    ): AppSyncRecoveryRetryDecision {
        require(payloadFingerprint.isNotBlank())
        val retryIdentity = listOf(
            session.phase.name,
            session.generationId,
            payloadFingerprint,
        ).joinToString(":")
        if (
            category == AppSyncRecoveryFailureCategory.SingleBodyOversized ||
            (!segmentedStrategy && encodedChars != null && targetChars != null && encodedChars > targetChars)
        ) {
            return AppSyncRecoveryRetryDecision.SwitchToSegmentation(retryIdentity)
        }
        if (category in setOf(
                AppSyncRecoveryFailureCategory.PolicyViolation,
                AppSyncRecoveryFailureCategory.UnsupportedTotalSize,
            )
        ) {
            return AppSyncRecoveryRetryDecision.NeedsAttention(category.name, retryIdentity)
        }
        val nextRetry = session.retryCount + 1
        if (nextRetry > maximumRetries) {
            return AppSyncRecoveryRetryDecision.NeedsAttention("retry-exhausted", retryIdentity)
        }
        val exponent = (nextRetry - 1).coerceAtMost(30).toInt()
        val delay = (baseDelayMillis * (1L shl exponent)).coerceAtMost(maximumDelayMillis)
        return AppSyncRecoveryRetryDecision.RetryAt(
            retryCount = nextRetry,
            atEpochMillis = nowEpochMillis + delay,
            retryIdentity = retryIdentity,
        )
    }
}

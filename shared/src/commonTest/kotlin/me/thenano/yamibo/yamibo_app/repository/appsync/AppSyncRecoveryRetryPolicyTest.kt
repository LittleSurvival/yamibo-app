package me.thenano.yamibo.yamibo_app.repository.appsync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncRecoveryFailureCategory
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncRecoveryRetryDecision
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncRecoveryRetryPolicy
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncRecoveryPhase
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncRecoveryMode
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncRecoverySession
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncAccountBinding
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncDeviceEpoch
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncDeviceId
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncWriterNonce

class AppSyncRecoveryRetryPolicyTest {
    @Test
    fun knownOversizedSingleBodySwitchesStrategyWithoutRetryingIdenticalContent() {
        val decision = AppSyncRecoveryRetryPolicy().decide(
            session(), AppSyncRecoveryFailureCategory.SingleBodyOversized,
            payloadFingerprint = "payload", nowEpochMillis = 1_000,
            encodedChars = 50_001, targetChars = 42_000, segmentedStrategy = false,
        )
        assertIs<AppSyncRecoveryRetryDecision.SwitchToSegmentation>(decision)
        assertTrue(decision.retryIdentity.contains("payload"))
    }

    @Test
    fun transientFailuresUseBoundedExponentialBackoffAndStableContentIdentity() {
        val policy = AppSyncRecoveryRetryPolicy(
            maximumRetries = 3, baseDelayMillis = 100, maximumDelayMillis = 250,
        )
        val first = assertIs<AppSyncRecoveryRetryDecision.RetryAt>(
            policy.decide(
                session(retryCount = 0), AppSyncRecoveryFailureCategory.Network,
                "payload", 1_000, segmentedStrategy = true,
            ),
        )
        val third = assertIs<AppSyncRecoveryRetryDecision.RetryAt>(
            policy.decide(
                session(retryCount = 2), AppSyncRecoveryFailureCategory.Timeout,
                "payload", 1_000, segmentedStrategy = true,
            ),
        )
        assertEquals(1_100, first.atEpochMillis)
        assertEquals(1_250, third.atEpochMillis)
        assertEquals(first.retryIdentity, third.retryIdentity)
        assertIs<AppSyncRecoveryRetryDecision.NeedsAttention>(
            policy.decide(
                session(retryCount = 3), AppSyncRecoveryFailureCategory.Network,
                "payload", 1_000, segmentedStrategy = true,
            ),
        )
    }

    @Test
    fun permanentPolicyAndTotalSizeFailuresNeverScheduleAutomaticRetry() {
        listOf(
            AppSyncRecoveryFailureCategory.PolicyViolation,
            AppSyncRecoveryFailureCategory.UnsupportedTotalSize,
        ).forEach { category ->
            assertIs<AppSyncRecoveryRetryDecision.NeedsAttention>(
                AppSyncRecoveryRetryPolicy().decide(
                    session(), category, "payload", 1_000, segmentedStrategy = true,
                ),
            )
        }
    }

    private fun session(retryCount: Long = 0) = AppSyncRecoverySession(
        sessionId = "session",
        accountBinding = SyncAccountBinding("account"),
        mode = AppSyncRecoveryMode.LegacyShadow,
        sourceDeviceId = SyncDeviceId("source"),
        sourceDeviceEpoch = SyncDeviceEpoch("source-epoch"),
        targetDeviceId = SyncDeviceId("target"),
        targetDeviceEpoch = SyncDeviceEpoch("target-epoch"),
        targetWriterNonce = SyncWriterNonce("nonce"),
        targetFirstSequence = 1,
        generationId = "generation",
        sourceOperationIds = setOf("operation"),
        replacementFingerprint = "replacement",
        phase = AppSyncRecoveryPhase.PublishingSegments,
        retryCount = retryCount,
        nextRetryAtEpochMillis = null,
        lastErrorCategory = null,
        blockingDomain = null,
        redactedBlockingEntity = null,
        rootBlogId = null,
        rootFingerprint = null,
        indexCommitted = false,
        createdAtEpochMillis = 1,
        updatedAtEpochMillis = 1,
        completedAtEpochMillis = null,
    )
}

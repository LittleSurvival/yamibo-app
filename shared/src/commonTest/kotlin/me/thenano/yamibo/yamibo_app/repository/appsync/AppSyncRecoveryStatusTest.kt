package me.thenano.yamibo.yamibo_app.repository.appsync

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncRecoveryMode
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncRecoveryPhase
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncRecoverySegmentWrite
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncRecoverySession
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncAccountBinding
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncDeviceEpoch
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncDeviceId
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncWriterNonce

class AppSyncRecoveryStatusTest {
    @Test
    fun everyDurableRecoveryPhaseHasAnExplicitPublicServicePhase() {
        val expected = mapOf(
            AppSyncRecoveryPhase.Classifying to AppSyncServicePhase.RecoveryClassifying,
            AppSyncRecoveryPhase.Staging to AppSyncServicePhase.RecoveryStaging,
            AppSyncRecoveryPhase.PublishingSegments to AppSyncServicePhase.RecoveryUploadingSegments,
            AppSyncRecoveryPhase.PublishingRoot to AppSyncServicePhase.RecoveryPublishingRoot,
            AppSyncRecoveryPhase.CommittingIndex to AppSyncServicePhase.RecoveryCommittingIndex,
            AppSyncRecoveryPhase.ActivatingLocal to AppSyncServicePhase.RecoveryActivatingLocal,
            AppSyncRecoveryPhase.Cleaning to AppSyncServicePhase.RecoveryCleaning,
            AppSyncRecoveryPhase.Completed to AppSyncServicePhase.RecoveryCleaning,
            AppSyncRecoveryPhase.NeedsAttention to AppSyncServicePhase.RecoveryNeedsAttention,
        )
        assertEquals(
            expected,
            AppSyncRecoveryPhase.entries.associateWith {
                it.toPublicRecoveryPhase().toServicePhase()
            },
        )
    }

    @Test
    fun automaticSchedulerKeepsRetryDemandUntilRecoveryCompletesOrNeedsAttention() {
        listOf(
            AppSyncServicePhase.RecoveryClassifying,
            AppSyncServicePhase.RecoveryStaging,
            AppSyncServicePhase.RecoveryUploadingSegments,
            AppSyncServicePhase.RecoveryPublishingRoot,
            AppSyncServicePhase.RecoveryCommittingIndex,
            AppSyncServicePhase.RecoveryActivatingLocal,
            AppSyncServicePhase.RecoveryCleaning,
        ).forEach { assertFalse(it.isDurableAutomaticTriggerOutcome()) }
        assertTrue(AppSyncServicePhase.RecoveryNeedsAttention.isDurableAutomaticTriggerOutcome())
        assertTrue(AppSyncServicePhase.Active.isDurableAutomaticTriggerOutcome())
    }

    @Test
    fun durableRecoveryResumeSkipsRepeatedSeedUntilCommitOrAttention() {
        AppSyncRecoveryPhase.entries.forEach { phase ->
            assertEquals(
                phase !in setOf(AppSyncRecoveryPhase.Completed, AppSyncRecoveryPhase.NeedsAttention),
                phase.requiresPayloadResume(),
                "Unexpected seed/resume policy for $phase",
            )
        }
        assertEquals(
            AppSyncRecoveryRunPolicy(
                auditLocalProjection = false,
                detectEmptyCloud = false,
            ),
            appSyncRecoveryRunPolicy(
                resumesCapacityRecovery = true,
                requestedProjectionAudit = true,
                requestedEmptyCloudDetection = true,
            ),
        )
    }

    @Test
    fun publicProgressCountsOnlyAuthoritativelyVerifiedSegments() {
        val session = AppSyncRecoverySession(
            sessionId = "session",
            accountBinding = SyncAccountBinding("account"),
            mode = AppSyncRecoveryMode.SegmentedCheckpoint,
            sourceDeviceId = SyncDeviceId("source-device"),
            sourceDeviceEpoch = SyncDeviceEpoch("source-epoch"),
            targetDeviceId = SyncDeviceId("target-device"),
            targetDeviceEpoch = SyncDeviceEpoch("target-epoch"),
            targetWriterNonce = SyncWriterNonce("writer-nonce"),
            targetFirstSequence = 1L,
            generationId = "generation",
            sourceOperationIds = setOf("one", "two"),
            replacementFingerprint = "payload-fingerprint",
            phase = AppSyncRecoveryPhase.PublishingSegments,
            retryCount = 2L,
            nextRetryAtEpochMillis = 99L,
            lastErrorCategory = "NETWORK",
            blockingDomain = null,
            redactedBlockingEntity = null,
            rootBlogId = null,
            rootFingerprint = null,
            indexCommitted = false,
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 2L,
            completedAtEpochMillis = null,
            encodedChars = 90_000,
            targetBudgetChars = 42_000,
        )
        val progress = appSyncRecoveryStatus(
            session,
            listOf(
                AppSyncRecoverySegmentWrite("session", 0, 2, "a", 2L, 1L, "a", 3L),
                AppSyncRecoverySegmentWrite("session", 1, 2, "b", null, null, null, null),
            ),
        )
        assertEquals(1, progress.verifiedSegmentCount)
        assertEquals(2, progress.totalSegmentCount)
        assertEquals(2, progress.pendingOperationCount)
        assertEquals(90_000, progress.encodedChars)
    }

    @Test
    fun reliabilityEvidenceContainsOnlyBoundedRecoveryMetadata() {
        val sensitive = "private-note-body-and-cache-payload"
        val status = AppSyncServiceStatus(
            phase = AppSyncServicePhase.RecoveryUploadingSegments,
            automaticEnabled = true,
            pendingOperationCount = 7,
            lastVerifiedAtEpochMillis = null,
            message = sensitive,
            recoveryStatus = AppSyncRecoveryStatus(
                phase = AppSyncRecoveryPublicPhase.UploadingSegments,
                encodedChars = 90_000,
                targetBudgetChars = 42_000,
                verifiedSegmentCount = 1,
                totalSegmentCount = 3,
                pendingOperationCount = 7,
                retryCount = 2,
                retryCategory = "NETWORK",
                nextRetryAtEpochMillis = 123L,
                blockingDomain = "detail-note",
                redactedBlockingEntity = sensitive,
                payloadFingerprint = "fingerprint123",
            ),
        )

        val evidence = assertNotNull(redactedRecoveryEvidenceLine(status, orphanCount = 4))
        assertTrue(evidence.contains("fingerprint=fingerprint123"))
        assertTrue(evidence.contains("encodedChars=90000"))
        assertTrue(evidence.contains("segments=1/3"))
        assertTrue(evidence.contains("retryCategory=NETWORK"))
        assertTrue(evidence.contains("orphanCount=4"))
        assertFalse(evidence.contains(sensitive))
        assertFalse(evidence.contains("detail-note"))
    }
}

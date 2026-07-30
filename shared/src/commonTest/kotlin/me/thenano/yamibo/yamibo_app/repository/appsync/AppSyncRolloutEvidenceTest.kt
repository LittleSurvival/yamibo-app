package me.thenano.yamibo.yamibo_app.repository.appsync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import me.thenano.yamibo.yamibo_app.repository.appsync.rollout.AppSyncRolloutEvidenceCodec
import me.thenano.yamibo.yamibo_app.repository.appsync.rollout.AppSyncRolloutEvidenceReport
import me.thenano.yamibo.yamibo_app.repository.appsync.rollout.RolloutDemandEvidence
import me.thenano.yamibo.yamibo_app.repository.appsync.rollout.RolloutDemandOutcome
import me.thenano.yamibo.yamibo_app.repository.appsync.rollout.RolloutExclusionReason
import me.thenano.yamibo.yamibo_app.repository.appsync.rollout.RolloutFixedPointObservation

class AppSyncRolloutEvidenceTest {
    @Test
    fun oneHundredEligibleFixedPointsPassWithoutCountingRetriesTwice() {
        val report = AppSyncRolloutEvidenceReport.create(
            generatedAtEpochMillis = 1_000,
            demands = List(100) { index ->
                convergedDemand(
                    index = index,
                    attempts = if (index % 10 == 0) 2 else 1,
                )
            } + RolloutDemandEvidence(
                demandId = "excluded-auth",
                eligible = false,
                outcome = RolloutDemandOutcome.Excluded,
                attempts = 1,
                coveredDomains = emptySet(),
                fixedPoint = null,
                exclusionReason = RolloutExclusionReason.AuthenticationUnavailable,
            ),
        )

        assertEquals(100, report.eligibleDemandCount)
        assertEquals(100, report.convergedDemandCount)
        assertEquals(10, report.retryCount)
        assertEquals(1, report.exclusionCount)
        assertEquals(10_000, report.convergenceBasisPoints)
        assertTrue(report.passesRetirementGate)
    }

    @Test
    fun lossOrMoreThanOnePercentFailureBlocksRetirement() {
        val lost = AppSyncRolloutEvidenceReport.create(
            generatedAtEpochMillis = 1_000,
            demands = List(100) { index ->
                convergedDemand(index).let {
                    if (index == 0) {
                        it.copy(
                            fixedPoint = it.fixedPoint?.copy(
                                acknowledgedOperationLossCount = 1,
                            ),
                        )
                    } else {
                        it
                    }
                }
            },
        )
        val belowTarget = AppSyncRolloutEvidenceReport.create(
            generatedAtEpochMillis = 1_000,
            demands = List(100) { index ->
                if (index < 2) {
                    convergedDemand(index).copy(
                        outcome = RolloutDemandOutcome.Failed,
                        fixedPoint = null,
                    )
                } else {
                    convergedDemand(index)
                }
            },
        )

        assertEquals(1, lost.acknowledgedOperationLossCount)
        assertFalse(lost.passesRetirementGate)
        assertEquals(9_800, belowTarget.convergenceBasisPoints)
        assertFalse(belowTarget.passesRetirementGate)
    }

    @Test
    fun encodedEvidenceContainsOnlyBoundedOperationalFields() {
        val encoded = AppSyncRolloutEvidenceCodec.encode(
            AppSyncRolloutEvidenceReport.create(
                generatedAtEpochMillis = 1_000,
                demands = listOf(convergedDemand(0)),
            ),
        )

        assertTrue("\"demandId\"" in encoded)
        assertTrue("\"coveredDomains\"" in encoded)
        listOf("cookie", "formHash", "accountId", "blogPayload", "rawContent").forEach {
            assertFalse(it in encoded)
        }
    }

    private fun convergedDemand(
        index: Int,
        attempts: Int = 1,
    ) = RolloutDemandEvidence(
        demandId = "demand-$index",
        eligible = true,
        outcome = RolloutDemandOutcome.Converged,
        attempts = attempts,
        coveredDomains = setOf(
            "settings",
            "favorite.item",
            "rss.search-subscription",
            "reading.thread",
            "favorite.update-event",
            "favorite.update-fid-filter",
            "favorite.update-category-filter",
        ),
        fixedPoint = RolloutFixedPointObservation(
            pendingNonQuarantinedCount = 0,
            fetchedValidUnappliedCount = 0,
            projectionMismatchCount = 0,
            acknowledgedOperationLossCount = 0,
        ),
    )
}

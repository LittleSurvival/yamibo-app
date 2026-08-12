package me.thenano.yamibo.yamibo_app.repository.appsync

import kotlin.test.Test
import kotlin.test.assertEquals

class AppSyncReliabilityClassificationTest {
    @Test
    fun leaseContentionAndTransientPhasesRemainAutomaticRetries() {
        listOf(
            AppSyncServicePhase.RetryPending,
            AppSyncServicePhase.Running,
            AppSyncServicePhase.BootstrapRequired,
        ).forEach { phase ->
            assertEquals("RETRY", reliabilityOutcomeFor(phase))
        }
    }

    @Test
    fun onlyTerminalDataOrProviderStatesRequireManualIntervention() {
        listOf(
            AppSyncServicePhase.PausedProvider,
            AppSyncServicePhase.Quarantined,
        ).forEach { phase ->
            assertEquals("MANUAL_INTERVENTION", reliabilityOutcomeFor(phase))
        }
    }

    @Test
    fun retryContinuesTheSameEligibleDemand() {
        val continued = nextReliabilityDemand(
            trigger = "background_workmanager",
            nowEpochMillis = 9_000,
            deviceEpoch = "epoch-new",
            pending = PendingReliabilityDemand(
                runId = "run-original",
                startedAtEpochMillis = 1_000,
                retryCount = 2,
            ),
        )

        assertEquals("run-original", continued.runId)
        assertEquals(1_000, continued.startedAtEpochMillis)
        assertEquals(3, continued.retryCount)
        assertEquals("background_workmanager", continued.trigger)
    }

    @Test
    fun terminalDemandStartsANewReliabilityIdentity() {
        val created = nextReliabilityDemand(
            trigger = "manual",
            nowEpochMillis = 9_000,
            deviceEpoch = "epoch-a",
            pending = null,
        )

        assertEquals(9_000, created.startedAtEpochMillis)
        assertEquals(0, created.retryCount)
        assertEquals("manual", created.trigger)
        assertEquals(true, created.runId.startsWith("run-"))
    }
}

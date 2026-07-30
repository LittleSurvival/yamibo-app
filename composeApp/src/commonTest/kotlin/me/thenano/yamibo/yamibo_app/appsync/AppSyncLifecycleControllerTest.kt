package me.thenano.yamibo.yamibo_app.appsync

import kotlin.test.Test
import kotlin.test.assertEquals
import me.thenano.yamibo.yamibo_app.repository.appsync.AppSyncAutomaticTrigger
import me.thenano.yamibo.yamibo_app.repository.appsync.AppSyncScheduleSettings
import me.thenano.yamibo.yamibo_app.repository.appsync.AppSyncServicePhase
import me.thenano.yamibo.yamibo_app.repository.appsync.AppSyncServiceStatus
import me.thenano.yamibo.yamibo_app.util.time.FixedScheduleInterval

class AppSyncLifecycleControllerTest {
    @Test
    fun reconcileUsesPersistedPolicyAndHandsPendingWorkToPlatform() {
        val scheduler = RecordingScheduler()
        val controller = AppSyncLifecycleController(
            statusProvider = {
                status(
                    automaticEnabled = true,
                    pendingTriggerGeneration = 3,
                    interval = FixedScheduleInterval.Days2,
                )
            },
            triggerRequest = { null },
            scheduler = scheduler,
        )

        controller.reconcileRegistration()

        assertEquals(listOf(true to FixedScheduleInterval.Days2), scheduler.registrations)
        assertEquals(1, scheduler.immediateRuns)
    }

    @Test
    fun lifecycleHandoffRunsOnlyWhenDurableTriggerWasAccepted() {
        val scheduler = RecordingScheduler()
        val accepted = mutableListOf<AppSyncAutomaticTrigger>()
        val controller = AppSyncLifecycleController(
            statusProvider = { status() },
            triggerRequest = {
                accepted += it
                if (it == AppSyncAutomaticTrigger.AppStartup) 1 else null
            },
            scheduler = scheduler,
        )

        controller.onForegroundSessionStarted()
        controller.onForegroundSessionExited()

        assertEquals(
            listOf(
                AppSyncAutomaticTrigger.AppStartup,
                AppSyncAutomaticTrigger.ForegroundExit,
            ),
            accepted,
        )
        assertEquals(1, scheduler.immediateRuns)
    }

    private fun status(
        automaticEnabled: Boolean = false,
        pendingTriggerGeneration: Long? = null,
        interval: FixedScheduleInterval = FixedScheduleInterval.Hours6,
    ) = AppSyncServiceStatus(
        phase = AppSyncServicePhase.Active,
        automaticEnabled = automaticEnabled,
        pendingOperationCount = 0,
        lastVerifiedAtEpochMillis = null,
        message = "",
        scheduleSettings = AppSyncScheduleSettings(periodicInterval = interval),
        pendingTriggerGeneration = pendingTriggerGeneration,
    )

    private class RecordingScheduler : AppSyncBackgroundScheduler {
        val registrations = mutableListOf<Pair<Boolean, FixedScheduleInterval>>()
        var immediateRuns = 0

        override fun setEnabled(enabled: Boolean, interval: FixedScheduleInterval) {
            registrations += enabled to interval
        }

        override fun runNow() {
            immediateRuns += 1
        }
    }
}

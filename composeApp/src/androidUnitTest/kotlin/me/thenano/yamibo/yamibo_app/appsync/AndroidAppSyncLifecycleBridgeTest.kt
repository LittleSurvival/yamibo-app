package me.thenano.yamibo.yamibo_app.appsync

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import me.thenano.yamibo.yamibo_app.repository.appsync.AppSyncAutomaticTrigger
import me.thenano.yamibo.yamibo_app.repository.appsync.AppSyncServicePhase
import me.thenano.yamibo.yamibo_app.repository.appsync.AppSyncServiceStatus
import me.thenano.yamibo.yamibo_app.util.time.FixedScheduleInterval

class AndroidAppSyncLifecycleBridgeTest {
    @BeforeTest
    fun setUp() = AndroidAppSyncLifecycleBridge.resetForTest()

    @AfterTest
    fun tearDown() = AndroidAppSyncLifecycleBridge.resetForTest()

    @Test
    fun configurationRecreationDoesNotCreateExitOrDuplicateStartup() {
        val triggers = mutableListOf<AppSyncAutomaticTrigger>()
        val scheduler = RecordingScheduler()
        val controller = AppSyncLifecycleController(
            statusProvider = { status() },
            triggerRequest = {
                triggers += it
                triggers.size.toLong()
            },
            scheduler = scheduler,
        )

        AndroidAppSyncLifecycleBridge.onActivityStarted()
        AndroidAppSyncLifecycleBridge.attach(controller)
        AndroidAppSyncLifecycleBridge.onActivityStopped(changingConfigurations = true)
        AndroidAppSyncLifecycleBridge.detach(controller)
        AndroidAppSyncLifecycleBridge.attach(controller)
        AndroidAppSyncLifecycleBridge.onActivityStarted()

        assertEquals(listOf(AppSyncAutomaticTrigger.AppStartup), triggers)

        AndroidAppSyncLifecycleBridge.onActivityStopped(changingConfigurations = false)
        AndroidAppSyncLifecycleBridge.onActivityStarted()

        assertEquals(
            listOf(
                AppSyncAutomaticTrigger.AppStartup,
                AppSyncAutomaticTrigger.ForegroundExit,
                AppSyncAutomaticTrigger.AppStartup,
            ),
            triggers,
        )
        assertEquals(3, scheduler.immediateRuns)
    }

    private fun status() = AppSyncServiceStatus(
        phase = AppSyncServicePhase.Active,
        automaticEnabled = true,
        pendingOperationCount = 0,
        lastVerifiedAtEpochMillis = null,
        message = "",
    )

    private class RecordingScheduler : AppSyncBackgroundScheduler {
        var immediateRuns = 0

        override fun setEnabled(enabled: Boolean, interval: FixedScheduleInterval) = Unit

        override fun runNow() {
            immediateRuns += 1
        }
    }
}

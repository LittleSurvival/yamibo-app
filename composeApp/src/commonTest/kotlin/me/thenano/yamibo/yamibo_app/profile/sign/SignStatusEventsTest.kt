package me.thenano.yamibo.yamibo_app.profile.sign

import io.github.littlesurvival.core.YamiboResult
import me.thenano.yamibo.yamibo_app.event.AppEvent
import me.thenano.yamibo.yamibo_app.event.events.SignStatusChangedEvent
import me.thenano.yamibo.yamibo_app.repository.SignRepository
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class SignStatusEventsTest {
    @Test
    fun notificationSuppressesLateLaunchCheckAndExistingDialog() = runBlocking {
        val state = LaunchSignReminderState()
        state.onStatusChecked(signedToday = false)
        assertTrue(state.visible)
        val lateResult = CompletableDeferred<Boolean>()
        val check = launch(start = CoroutineStart.UNDISPATCHED) {
            state.onStatusChecked(lateResult.await())
        }
        state.suppress()
        assertFalse(state.visible)
        lateResult.complete(false)
        check.join()
        assertFalse(state.visible)
        assertTrue(state.suppressed)
    }

    @Test
    fun coldNotificationLaunchAndRestorationStaySuppressed() {
        val state = LaunchSignReminderState(suppressed = true)
        state.onStatusChecked(signedToday = false)
        assertFalse(state.visible)
        val restored = LaunchSignReminderState(suppressed = state.suppressed)
        restored.onStatusChecked(signedToday = false)
        assertFalse(restored.visible)
        val regularLaunch = LaunchSignReminderState()
        regularLaunch.onStatusChecked(signedToday = false)
        assertTrue(regularLaunch.visible)
    }

    @Test
    fun confirmedSuccessfulOutcomesEmitSignStatusChange() {
        SignRepository.ActionStatus.entries.forEach { status ->
            assertTrue(
                shouldEmitSignStatusChanged(
                    YamiboResult.Success(actionResult(status, signedToday = true)),
                ),
            )
        }
    }

    @Test
    fun unsuccessfulOrUnconfirmedOutcomesDoNotEmitSignStatusChange() {
        assertFalse(
            shouldEmitSignStatusChanged(
                YamiboResult.Success(
                    actionResult(SignRepository.ActionStatus.SUCCESS, signedToday = false),
                ),
            ),
        )
        assertFalse(shouldEmitSignStatusChanged(YamiboResult.Failure("network")))
        assertFalse(shouldEmitSignStatusChanged(YamiboResult.Maintenance))
        assertFalse(shouldEmitSignStatusChanged(YamiboResult.NotLoggedIn))
        assertFalse(shouldEmitSignStatusChanged(YamiboResult.NoPermission("denied")))
    }

    @Test
    fun onlySignStatusEventDismissesReminder() {
        assertTrue(shouldDismissSignReminderFor(SignStatusChangedEvent))
        assertFalse(shouldDismissSignReminderFor(UnrelatedEvent))
    }

    private fun actionResult(
        status: SignRepository.ActionStatus,
        signedToday: Boolean,
    ) = SignRepository.ActionResult(
        status = status,
        message = "result",
        repairCount = 0,
        pageInfo = SignRepository.SignPageInfo(
            currentDateText = null,
            monthLabel = null,
            notice = null,
            calendarDays = emptyList(),
            repairOptions = emptyList(),
            myActivity = emptyList(),
            statistics = emptyList(),
            extraSections = emptyList(),
            signActionUrl = null,
            repairActionPrefix = null,
            hasSignedToday = signedToday,
            lastSignDateKey = null,
        ),
    )
}

private object UnrelatedEvent : AppEvent

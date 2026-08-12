package me.thenano.yamibo.yamibo_app.profile.sign

import io.github.littlesurvival.core.YamiboResult
import me.thenano.yamibo.yamibo_app.event.AppEvent
import me.thenano.yamibo.yamibo_app.event.events.SignStatusChangedEvent
import me.thenano.yamibo.yamibo_app.repository.SignRepository
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SignStatusEventsTest {
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

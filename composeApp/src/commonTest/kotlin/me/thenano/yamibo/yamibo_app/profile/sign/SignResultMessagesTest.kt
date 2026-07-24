package me.thenano.yamibo.yamibo_app.profile.sign

import io.github.littlesurvival.core.YamiboResult
import me.thenano.yamibo.yamibo_app.repository.SignRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SignResultMessagesTest {
    private val text = SignResultText(
        noPermissionActionMessage = "manual sign required",
        localize = { "localized:$it" },
    )

    @Test
    fun signActionFeedbackMessageKeepsCustomActionText() {
        assertEquals("signed", YamiboResult.Success(actionResult("signed")).signActionFeedbackMessage(text))
        assertEquals("manual sign required", YamiboResult.NoPermission("denied").signActionFeedbackMessage(text))
        assertEquals("localized:network", YamiboResult.Failure("network").signActionFeedbackMessage(text))
        assertEquals("localized:${YamiboResult.NotLoggedIn.message()}", YamiboResult.NotLoggedIn.signActionFeedbackMessage(text))
        assertEquals("localized:${YamiboResult.Maintenance.message()}", YamiboResult.Maintenance.signActionFeedbackMessage(text))
    }

    @Test
    fun signInfoErrorMessagePreservesRawFailureReasons() {
        assertNull(YamiboResult.Success(signPageInfo()).signInfoErrorMessage(text))
        assertEquals("denied", YamiboResult.NoPermission("denied").signInfoErrorMessage(text))
        assertEquals("network", YamiboResult.Failure("network").signInfoErrorMessage(text))
        assertEquals("localized:${YamiboResult.NotLoggedIn.message()}", YamiboResult.NotLoggedIn.signInfoErrorMessage(text))
        assertEquals("localized:${YamiboResult.Maintenance.message()}", YamiboResult.Maintenance.signInfoErrorMessage(text))
    }

    private fun actionResult(message: String) = SignRepository.ActionResult(
        status = SignRepository.ActionStatus.SUCCESS,
        message = message,
        repairCount = 0,
        pageInfo = signPageInfo(),
    )

    private fun signPageInfo() = SignRepository.SignPageInfo(
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
        hasSignedToday = false,
        lastSignDateKey = null,
    )
}

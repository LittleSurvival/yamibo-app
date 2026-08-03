package me.thenano.yamibo.yamibo_app.profile

import io.github.littlesurvival.core.YamiboResult
import me.thenano.yamibo.yamibo_app.event.AppEvent
import me.thenano.yamibo.yamibo_app.event.events.LoginSuccessEvent
import me.thenano.yamibo.yamibo_app.event.events.SignStatusChangedEvent
import me.thenano.yamibo.yamibo_app.repository.SignRepository
import kotlin.test.Test
import kotlin.test.assertEquals

class PassiveSignButtonStateTest {
    @Test
    fun signedRecordShowsSignedWithoutFetching() {
        val repository = TrackingSignRepository(knownSignedToday = true)

        assertEquals(PassiveSignButtonState.Signed, resolvePassiveSignButtonState(repository))
        assertEquals(0, repository.fetchCount)
    }

    @Test
    fun unsignedOrMissingRecordShowsAvailableWithoutFetching() {
        listOf(false, null).forEach { knownSignedToday ->
            val repository = TrackingSignRepository(knownSignedToday)

            assertEquals(PassiveSignButtonState.Available, resolvePassiveSignButtonState(repository))
            assertEquals(0, repository.fetchCount)
        }
    }

    @Test
    fun signAndLoginEventsRefreshThePassiveState() {
        assertEquals(true, isProfileSignRefreshEvent(LoginSuccessEvent))
        assertEquals(true, isProfileSignRefreshEvent(SignStatusChangedEvent))
        assertEquals(false, isProfileSignRefreshEvent(UnrelatedEvent))
    }
}

private object UnrelatedEvent : AppEvent

private class TrackingSignRepository(
    private val knownSignedToday: Boolean?,
) : SignRepository {
    var fetchCount: Int = 0
        private set

    override suspend fun fetchPageInfo(): YamiboResult<SignRepository.SignPageInfo> {
        fetchCount += 1
        return YamiboResult.Failure("Unexpected passive fetch")
    }

    override suspend fun runAutoSign(allowRepair: Boolean): YamiboResult<SignRepository.ActionResult> =
        YamiboResult.Failure("Not used")

    override suspend fun getTodayRecord(): SignRepository.DailyRecord? = null
    override fun getKnownSignedToday(): Boolean? = knownSignedToday
    override suspend fun isSignedToday(): Boolean = knownSignedToday == true
    override suspend fun markTodaySigned(message: String?) = Unit
    override fun getCachedPageInfo(): SignRepository.SignPageInfo? = null
    override fun cacheObservedHtml(html: String): SignRepository.SignPageInfo? = null
}

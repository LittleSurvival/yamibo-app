package me.thenano.yamibo.yamibo_app.notification

import io.github.littlesurvival.core.YamiboResult
import io.github.littlesurvival.dto.page.HomePage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import me.thenano.yamibo.yamibo_app.repository.notification.MessageNotificationChecker
import me.thenano.yamibo.yamibo_app.repository.notification.MessageNotificationGateway

class AndroidRealtimeMessageNotificationCheckTest {
    @Test
    fun realtimeHasNoPollingQuotaAndStillChecksAuthoritativeUnread() = runBlocking {
        val fixture = Fixture()
        repeat(8) { assertEquals(MessageNotificationChecker.Result.Delivered, fixture.check()) }
        assertEquals(8, fixture.fetchCount)
        assertEquals(8, fixture.gateway.showCount)
    }

    @Test
    fun realtimeHonorsMuteAndDoesNotPostWithoutUnread() = runBlocking {
        val fixture = Fixture()
        fixture.muted = true
        assertEquals(MessageNotificationChecker.Result.MutedToday, fixture.check())
        fixture.muted = false
        fixture.homeResult = YamiboResult.Success(homePage(false))
        assertEquals(MessageNotificationChecker.Result.NoNewMessage, fixture.check())
        assertEquals(0, fixture.gateway.showCount)
    }

    @Test
    fun missingAccountAndDisabledAvoidHomepageAndDelivery() = runBlocking {
        val fixture = Fixture()
        fixture.enabled = false
        assertEquals(MessageNotificationChecker.Result.Disabled, fixture.check())
        fixture.enabled = true
        fixture.userId = null
        assertEquals(MessageNotificationChecker.Result.MissingAccount, fixture.check())
        assertEquals(0, fixture.fetchCount)
        assertEquals(0, fixture.gateway.showCount)
    }

    @Test
    fun failedFetchOrDeliveryCannotAcknowledgeEvent() = runBlocking {
        val fixture = Fixture()
        fixture.homeResult = YamiboResult.Failure("offline")
        assertFalse(fixture.check().isTerminalMessageNotificationCheck())
        fixture.homeResult = YamiboResult.NotLoggedIn
        assertFalse(fixture.check().isTerminalMessageNotificationCheck())
        fixture.homeResult = YamiboResult.Maintenance
        assertFalse(fixture.check().isTerminalMessageNotificationCheck())
        fixture.homeResult = YamiboResult.Success(homePage(true))
        fixture.gateway.canShow = false
        assertFalse(fixture.check().isTerminalMessageNotificationCheck())
        fixture.gateway.canShow = true
        assertTrue(fixture.check().isTerminalMessageNotificationCheck())
    }

    @Test
    fun cancellationFromUnreadFetchIsNotTreatedAsAProcessedEvent() = runBlocking {
        val fixture = Fixture()
        fixture.cancelFetch = true
        assertFailsWith<CancellationException> { fixture.check() }
        assertEquals(0, fixture.gateway.showCount)
    }

    private class Fixture {
        var enabled = true
        var userId: Int? = 11
        var muted = false
        var fetchCount = 0
        var cancelFetch = false
        var homeResult: YamiboResult<HomePage> = YamiboResult.Success(homePage(true))
        val gateway = FakeGateway()
        suspend fun check() = checkRealtimeMessageNotification(
            enabled = enabled,
            userId = userId,
            fetchHomePage = {
                fetchCount += 1
                if (cancelFetch) throw CancellationException("cancelled")
                homeResult
            },
            isMutedToday = { muted },
            notificationGateway = gateway,
        )
    }

    private class FakeGateway : MessageNotificationGateway {
        var canShow = true
        var showCount = 0
        override suspend fun showMessageNotification(): Boolean {
            showCount += 1
            return canShow
        }
        override suspend fun dismissMessageNotification() = Unit
    }

    companion object {
        private fun homePage(hasNewMessage: Boolean) = HomePage(
            swiperImages = emptyList(),
            categories = emptyList(),
            hasNewMessage = hasNewMessage,
        )
    }
}

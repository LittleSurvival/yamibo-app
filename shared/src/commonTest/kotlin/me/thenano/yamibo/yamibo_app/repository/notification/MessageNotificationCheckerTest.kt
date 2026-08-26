package me.thenano.yamibo.yamibo_app.repository.notification

import io.github.littlesurvival.core.YamiboResult
import io.github.littlesurvival.dto.page.HomePage
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import me.thenano.yamibo.yamibo_app.repository.settings.AppSettingsRepository
import me.thenano.yamibo.yamibo_app.repository.settings.MessageNotificationDailyLimit
import me.thenano.yamibo.yamibo_app.store.settings.SettingsStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessageNotificationCheckerTest {
    @Test
    fun positiveHomepageHonorsQuotaAndResetsOnNextDate() = runBlocking {
        val fixture = Fixture()

        assertEquals(MessageNotificationChecker.Result.Delivered, fixture.checker.check())
        assertEquals(MessageNotificationChecker.Result.Delivered, fixture.checker.check())
        assertEquals(MessageNotificationChecker.Result.DailyLimitReached, fixture.checker.check())
        assertEquals(2, fixture.gateway.showCount)

        fixture.today = "2026-08-28"
        assertEquals(MessageNotificationChecker.Result.Delivered, fixture.checker.check())
        assertEquals(3, fixture.gateway.showCount)
    }

    @Test
    fun falseAndFailedHomepageDoNotConsumeQuota() = runBlocking {
        val fixture = Fixture()
        fixture.homeResult = YamiboResult.Success(homePage(hasNewMessage = false))
        assertEquals(MessageNotificationChecker.Result.NoNewMessage, fixture.checker.check())
        fixture.homeResult = YamiboResult.Failure("offline")
        assertEquals(MessageNotificationChecker.Result.FetchFailed(retryable = true), fixture.checker.check())
        fixture.homeResult = YamiboResult.Maintenance
        assertEquals(MessageNotificationChecker.Result.FetchFailed(retryable = false), fixture.checker.check())
        fixture.homeResult = YamiboResult.Success(homePage(hasNewMessage = true))
        assertEquals(MessageNotificationChecker.Result.Delivered, fixture.checker.check())
        assertEquals(1, fixture.gateway.showCount)
    }

    @Test
    fun disabledAndMissingAccountAvoidHomepageFetch() = runBlocking {
        val fixture = Fixture()
        fixture.settings.messageNotificationEnabled.setValue(false)
        assertEquals(MessageNotificationChecker.Result.Disabled, fixture.checker.check())
        assertEquals(0, fixture.fetchCount)

        fixture.settings.messageNotificationEnabled.setValue(true)
        fixture.userId = null
        assertEquals(MessageNotificationChecker.Result.MissingAccount, fixture.checker.check())
        assertEquals(0, fixture.fetchCount)
    }

    @Test
    fun unlimitedModeAllowsEveryPositiveCheck() = runBlocking {
        val fixture = Fixture()
        fixture.settings.messageNotificationDailyLimit.setValue(MessageNotificationDailyLimit.UNLIMITED)

        repeat(8) {
            assertEquals(MessageNotificationChecker.Result.Delivered, fixture.checker.check())
        }
        assertEquals(8, fixture.gateway.showCount)
    }

    @Test
    fun muteIsPerAccountAndExpiresOnNextDate() = runBlocking {
        val fixture = Fixture()

        assertTrue(fixture.checker.muteToday())
        assertEquals(1, fixture.gateway.dismissCount)
        assertEquals(MessageNotificationChecker.Result.MutedToday, fixture.checker.check())

        fixture.userId = 22
        assertEquals(MessageNotificationChecker.Result.Delivered, fixture.checker.check())
        fixture.userId = 11
        fixture.today = "2026-08-28"
        assertEquals(MessageNotificationChecker.Result.Delivered, fixture.checker.check())
    }

    @Test
    fun failedPostingDoesNotConsumeQuota() = runBlocking {
        val fixture = Fixture()
        fixture.gateway.canShow = false

        repeat(3) {
            assertEquals(MessageNotificationChecker.Result.DeliveryUnavailable, fixture.checker.check())
        }
        fixture.gateway.canShow = true
        assertEquals(MessageNotificationChecker.Result.Delivered, fixture.checker.check())
        assertEquals(4, fixture.gateway.showCount)
    }

    @Test
    fun concurrentChecksAreSerializedAcrossPostingAndAccounting() = runBlocking {
        val fixture = Fixture()
        fixture.settings.messageNotificationDailyLimit.setValue(MessageNotificationDailyLimit.ONCE)
        fixture.gateway.delayMillis = 20

        val results = List(4) { async { fixture.checker.check() } }.awaitAll()

        assertEquals(1, results.count { it == MessageNotificationChecker.Result.Delivered })
        assertEquals(3, results.count { it == MessageNotificationChecker.Result.DailyLimitReached })
        assertEquals(1, fixture.gateway.showCount)
    }

    @Test
    fun muteWithoutAccountStillDismissesVisibleNotification() = runBlocking {
        val fixture = Fixture()
        fixture.userId = null

        assertFalse(fixture.checker.muteToday())
        assertEquals(1, fixture.gateway.dismissCount)
    }

    private class Fixture {
        val store = MemorySettingsStore()
        val settings = AppSettingsRepository(store)
        val gateway = FakeGateway()
        var today = "2026-08-27"
        var userId: Int? = 11
        var fetchCount = 0
        var homeResult: YamiboResult<HomePage> = YamiboResult.Success(homePage(hasNewMessage = true))
        val checker = MessageNotificationChecker(
            settings = settings,
            deliveryStateStore = MessageNotificationDeliveryStateStore(store),
            currentUserId = { userId },
            fetchHomePage = {
                fetchCount += 1
                homeResult
            },
            notificationGateway = gateway,
            currentDate = { today },
        )
    }

    private class FakeGateway : MessageNotificationGateway {
        var canShow = true
        var showCount = 0
        var dismissCount = 0
        var delayMillis = 0L

        override suspend fun showMessageNotification(): Boolean {
            showCount += 1
            if (delayMillis > 0) delay(delayMillis)
            return canShow
        }

        override suspend fun dismissMessageNotification() {
            dismissCount += 1
        }
    }
}

private fun homePage(hasNewMessage: Boolean): HomePage = HomePage(
    swiperImages = emptyList(),
    categories = emptyList(),
    hasNewMessage = hasNewMessage,
)

private class MemorySettingsStore : SettingsStore {
    private val values = mutableMapOf<String, String>()

    override fun getInt(key: String, defaultValue: Int): Int = values[key]?.toIntOrNull() ?: defaultValue
    override fun putInt(key: String, value: Int) { values[key] = value.toString() }
    override fun getFloat(key: String, defaultValue: Float): Float = values[key]?.toFloatOrNull() ?: defaultValue
    override fun putFloat(key: String, value: Float) { values[key] = value.toString() }
    override fun getString(key: String, defaultValue: String): String = values[key] ?: defaultValue
    override fun putString(key: String, value: String) { values[key] = value }
    override fun getBoolean(key: String, defaultValue: Boolean): Boolean = values[key]?.toBooleanStrictOrNull() ?: defaultValue
    override fun putBoolean(key: String, value: Boolean) { values[key] = value.toString() }
    override fun remove(key: String) { values.remove(key) }
    override fun hasKey(key: String): Boolean = key in values
}

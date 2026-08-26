package me.thenano.yamibo.yamibo_app.repository.notification

import io.github.littlesurvival.core.YamiboResult
import io.github.littlesurvival.dto.page.HomePage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.thenano.yamibo.yamibo_app.repository.settings.AppSettingsRepository
import me.thenano.yamibo.yamibo_app.util.time.currentLocalDateKey

class MessageNotificationChecker(
    private val settings: AppSettingsRepository,
    private val deliveryStateStore: MessageNotificationDeliveryStateStore,
    private val currentUserId: () -> Int?,
    private val fetchHomePage: suspend () -> YamiboResult<HomePage>,
    private val notificationGateway: MessageNotificationGateway,
    private val currentDate: () -> String = ::currentLocalDateKey,
) {
    sealed interface Result {
        data object Disabled : Result
        data object MissingAccount : Result
        data object NoNewMessage : Result
        data object MutedToday : Result
        data object DailyLimitReached : Result
        data object Delivered : Result
        data object DeliveryUnavailable : Result
        data class FetchFailed(val retryable: Boolean) : Result
    }

    private val mutex = Mutex()

    suspend fun check(): Result = mutex.withLock {
        if (!settings.messageNotificationEnabled.getValue()) return@withLock Result.Disabled
        val userId = currentUserId() ?: return@withLock Result.MissingAccount
        val homePage = when (val result = fetchHomePage()) {
            is YamiboResult.Success -> result.value
            is YamiboResult.Failure -> return@withLock Result.FetchFailed(retryable = true)
            is YamiboResult.WafChallenge,
            YamiboResult.Maintenance,
            YamiboResult.NotLoggedIn,
            is YamiboResult.NoPermission,
            -> return@withLock Result.FetchFailed(retryable = false)
        }
        if (!homePage.hasNewMessage) return@withLock Result.NoNewMessage

        val today = currentDate()
        val dailyState = deliveryStateStore.stateFor(userId, today)
        if (dailyState.muted) return@withLock Result.MutedToday
        val limit = settings.messageNotificationDailyLimit.getValue().maxPerDay
        if (limit != null && dailyState.deliveredCount >= limit) {
            return@withLock Result.DailyLimitReached
        }
        if (!notificationGateway.showMessageNotification()) {
            return@withLock Result.DeliveryUnavailable
        }
        deliveryStateStore.recordDelivery(userId, today)
        Result.Delivered
    }

    suspend fun muteToday(): Boolean = mutex.withLock {
        val userId = currentUserId()
        if (userId != null) deliveryStateStore.muteToday(userId, currentDate())
        notificationGateway.dismissMessageNotification()
        userId != null
    }
}

interface MessageNotificationGateway {
    suspend fun showMessageNotification(): Boolean

    suspend fun dismissMessageNotification()
}

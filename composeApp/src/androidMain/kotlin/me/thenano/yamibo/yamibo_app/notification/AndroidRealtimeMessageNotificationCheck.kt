package me.thenano.yamibo.yamibo_app.notification

import io.github.littlesurvival.core.YamiboResult
import io.github.littlesurvival.dto.page.HomePage
import me.thenano.yamibo.yamibo_app.repository.notification.MessageNotificationChecker
import me.thenano.yamibo.yamibo_app.repository.notification.MessageNotificationGateway

/**
 * Realtime signals ignore the polling quota and do not consume it. Their account-scoped mute,
 * permission gate and authoritative homepage check still apply. The runtime owns serialization.
 */
internal suspend fun checkRealtimeMessageNotification(
    enabled: Boolean,
    userId: Int?,
    fetchHomePage: suspend () -> YamiboResult<HomePage>,
    isMutedToday: () -> Boolean,
    notificationGateway: MessageNotificationGateway,
): MessageNotificationChecker.Result {
    if (!enabled) return MessageNotificationChecker.Result.Disabled
    if (userId == null) return MessageNotificationChecker.Result.MissingAccount
    val homePage = when (val result = fetchHomePage()) {
        is YamiboResult.Success -> result.value
        is YamiboResult.Failure -> return MessageNotificationChecker.Result.FetchFailed(retryable = true)
        is YamiboResult.WafChallenge,
        YamiboResult.Maintenance,
        YamiboResult.NotLoggedIn,
        is YamiboResult.NoPermission,
        -> return MessageNotificationChecker.Result.FetchFailed(retryable = false)
    }
    if (!homePage.hasNewMessage) return MessageNotificationChecker.Result.NoNewMessage
    if (isMutedToday()) return MessageNotificationChecker.Result.MutedToday
    return if (notificationGateway.showMessageNotification()) {
        MessageNotificationChecker.Result.Delivered
    } else {
        MessageNotificationChecker.Result.DeliveryUnavailable
    }
}

internal fun MessageNotificationChecker.Result.isTerminalMessageNotificationCheck(): Boolean = when (this) {
    is MessageNotificationChecker.Result.FetchFailed,
    MessageNotificationChecker.Result.DeliveryUnavailable,
    MessageNotificationChecker.Result.MissingAccount,
    -> false
    MessageNotificationChecker.Result.Disabled,
    MessageNotificationChecker.Result.NoNewMessage,
    MessageNotificationChecker.Result.MutedToday,
    MessageNotificationChecker.Result.DailyLimitReached,
    MessageNotificationChecker.Result.Delivered,
    -> true
}

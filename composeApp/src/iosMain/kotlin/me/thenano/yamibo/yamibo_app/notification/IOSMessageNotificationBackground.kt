@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package me.thenano.yamibo.yamibo_app.notification

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import me.thenano.yamibo.yamibo_app.i18n.i18n
import me.thenano.yamibo.yamibo_app.network.IOSYamiboClientProvider
import me.thenano.yamibo.yamibo_app.repository.notification.MessageNotificationChecker
import me.thenano.yamibo.yamibo_app.repository.notification.MessageNotificationDeliveryStateStore
import me.thenano.yamibo.yamibo_app.repository.notification.MessageNotificationGateway
import me.thenano.yamibo.yamibo_app.repository.settings.AppSettingsRepository
import me.thenano.yamibo.yamibo_app.store.IOSCookieStore
import me.thenano.yamibo.yamibo_app.store.IOSUserStore
import me.thenano.yamibo.yamibo_app.store.settings.IOSSettingsStore
import me.thenano.yamibo.yamibo_app.util.time.FixedScheduleInterval
import platform.BackgroundTasks.BGAppRefreshTaskRequest
import platform.BackgroundTasks.BGTaskScheduler
import platform.Foundation.NSDate
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusProvisional
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNUserNotificationCenter
import kotlin.coroutines.resume

const val MESSAGE_NOTIFICATION_BACKGROUND_TASK_IDENTIFIER =
    "me.thenano.yamibo.yamibo-app.message-notification"
const val MESSAGE_NOTIFICATION_CATEGORY_IDENTIFIER =
    "me.thenano.yamibo.yamibo-app.message-notification.category"
const val MESSAGE_NOTIFICATION_OPEN_ACTION_IDENTIFIER =
    "me.thenano.yamibo.yamibo-app.message-notification.open"
const val MESSAGE_NOTIFICATION_MUTE_ACTION_IDENTIFIER =
    "me.thenano.yamibo.yamibo-app.message-notification.mute-today"
private const val MESSAGE_NOTIFICATION_REQUEST_IDENTIFIER =
    "me.thenano.yamibo.yamibo-app.message-notification.current"

class IOSMessageNotificationScheduler {
    fun setEnabled(enabled: Boolean, interval: FixedScheduleInterval) {
        BGTaskScheduler.sharedScheduler.cancelTaskRequestWithIdentifier(
            MESSAGE_NOTIFICATION_BACKGROUND_TASK_IDENTIFIER,
        )
        if (!enabled) return
        val request = BGAppRefreshTaskRequest(MESSAGE_NOTIFICATION_BACKGROUND_TASK_IDENTIFIER).apply {
            earliestBeginDate = NSDate(
                timeIntervalSinceReferenceDate =
                    NSDate().timeIntervalSinceReferenceDate + interval.duration.inWholeSeconds.toDouble(),
            )
        }
        BGTaskScheduler.sharedScheduler.submitTaskRequest(request, null)
    }
}

private var activeMessageNotificationJob: Job? = null

fun runMessageNotificationBackground(completion: (Boolean) -> Unit) {
    activeMessageNotificationJob?.cancel()
    activeMessageNotificationJob = CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
        val settings = AppSettingsRepository(IOSSettingsStore())
        val result = runCatching { createIOSMessageNotificationChecker(settings).check() }.getOrNull()
        if (settings.messageNotificationEnabled.getValue()) {
            IOSMessageNotificationScheduler().setEnabled(
                enabled = true,
                interval = settings.messageNotificationInterval.getValue(),
            )
        }
        completion(
            result != null &&
                (result !is MessageNotificationChecker.Result.FetchFailed || !result.retryable),
        )
        activeMessageNotificationJob = null
    }
}

fun cancelMessageNotificationBackground() {
    activeMessageNotificationJob?.cancel()
    activeMessageNotificationJob = null
    val settings = AppSettingsRepository(IOSSettingsStore())
    if (settings.messageNotificationEnabled.getValue()) {
        IOSMessageNotificationScheduler().setEnabled(
            enabled = true,
            interval = settings.messageNotificationInterval.getValue(),
        )
    }
}

fun openMessageCenterFromIOSNotification() {
    requestOpenMessageCenterFromNotification()
}

fun muteMessageNotificationsToday(completion: (Boolean) -> Unit) {
    CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
        completion(createIOSMessageNotificationActionChecker().muteToday())
    }
}

private fun createIOSMessageNotificationActionChecker(): MessageNotificationChecker {
    val rawSettings = IOSSettingsStore()
    val userStore = IOSUserStore()
    return MessageNotificationChecker(
        settings = AppSettingsRepository(rawSettings),
        deliveryStateStore = MessageNotificationDeliveryStateStore(rawSettings),
        currentUserId = { userStore.load()?.uid?.value },
        fetchHomePage = { error("Message fetch is not used by notification actions") },
        notificationGateway = IOSMessageNotificationGateway(),
    )
}

private suspend fun createIOSMessageNotificationChecker(
    settings: AppSettingsRepository = AppSettingsRepository(IOSSettingsStore()),
): MessageNotificationChecker {
    val rawSettings = IOSSettingsStore()
    val cookieStore = IOSCookieStore()
    val userStore = IOSUserStore()
    val client = IOSYamiboClientProvider.getForBackground(cookieStore)
    return MessageNotificationChecker(
        settings = settings,
        deliveryStateStore = MessageNotificationDeliveryStateStore(rawSettings),
        currentUserId = { userStore.load()?.uid?.value },
        fetchHomePage = { client.fetchHomePage() },
        notificationGateway = IOSMessageNotificationGateway(),
    )
}

private class IOSMessageNotificationGateway : MessageNotificationGateway {
    override suspend fun showMessageNotification(): Boolean = suspendCancellableCoroutine { continuation ->
        val center = UNUserNotificationCenter.currentNotificationCenter()
        center.getNotificationSettingsWithCompletionHandler { settings ->
            if (
                settings?.authorizationStatus != UNAuthorizationStatusAuthorized &&
                settings?.authorizationStatus != UNAuthorizationStatusProvisional
            ) {
                if (continuation.isActive) continuation.resume(false)
                return@getNotificationSettingsWithCompletionHandler
            }
            val content = UNMutableNotificationContent().apply {
                setTitle(i18n("新消息通知"))
                setBody("您有新的通知，快來看看吧 !")
                setCategoryIdentifier(MESSAGE_NOTIFICATION_CATEGORY_IDENTIFIER)
            }
            center.addNotificationRequest(
                UNNotificationRequest.requestWithIdentifier(
                    identifier = MESSAGE_NOTIFICATION_REQUEST_IDENTIFIER,
                    content = content,
                    trigger = null,
                ),
            ) { error ->
                if (continuation.isActive) continuation.resume(error == null)
            }
        }
    }

    override suspend fun dismissMessageNotification() {
        val center = UNUserNotificationCenter.currentNotificationCenter()
        center.removeDeliveredNotificationsWithIdentifiers(listOf(MESSAGE_NOTIFICATION_REQUEST_IDENTIFIER))
        center.removePendingNotificationRequestsWithIdentifiers(listOf(MESSAGE_NOTIFICATION_REQUEST_IDENTIFIER))
    }
}

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package me.thenano.yamibo.yamibo_app.profile.settings.access

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusDenied
import platform.UserNotifications.UNAuthorizationStatusProvisional
import platform.UserNotifications.UNUserNotificationCenter
import kotlin.coroutines.resume

class IOSBackgroundAccessRepository : BackgroundAccessRepository {
    private val _state = MutableStateFlow(buildState(notificationGranted = null, notificationDenied = false))
    override val state: StateFlow<BackgroundAccessRepository.SetupState> = _state

    override suspend fun refresh() {
        val status = suspendCancellableCoroutine { continuation ->
            UNUserNotificationCenter.currentNotificationCenter()
                .getNotificationSettingsWithCompletionHandler { settings ->
                    if (continuation.isActive) continuation.resume(settings?.authorizationStatus)
                }
        }
        _state.value = buildState(
            notificationGranted = status == UNAuthorizationStatusAuthorized ||
                status == UNAuthorizationStatusProvisional,
            notificationDenied = status == UNAuthorizationStatusDenied,
        )
    }

    override fun runAction(action: BackgroundAccessRepository.SetupAction) {
        if (
            action == BackgroundAccessRepository.SetupAction.OpenNotificationSettings ||
            action == BackgroundAccessRepository.SetupAction.OpenAppSettings
        ) {
            NSURL.URLWithString(UIApplicationOpenSettingsURLString)?.let {
                UIApplication.sharedApplication.openURL(it)
            }
        }
    }

    private fun buildState(
        notificationGranted: Boolean?,
        notificationDenied: Boolean,
    ): BackgroundAccessRepository.SetupState = BackgroundAccessRepository.SetupState(
        summary = when (notificationGranted) {
            true -> text("通知權限已允許；iOS 會依系統排程執行背景檢查。")
            false -> text("通知權限尚未允許，系統無法顯示新消息通知。")
            null -> text("正在檢查 iOS 通知權限。")
        },
        items = listOf(
            BackgroundAccessRepository.SetupItem(
                title = text("通知權限"),
                subtitle = text("允許 App 在發現首頁消息紅點時顯示通知。"),
                status = if (notificationGranted == true) {
                    BackgroundAccessRepository.SetupStatus.Granted
                } else {
                    BackgroundAccessRepository.SetupStatus.Required
                },
                actionLabel = if (notificationGranted == true) null else {
                    text(if (notificationDenied) "開啟系統設定" else "允許通知")
                },
                action = if (notificationGranted == true) null else {
                    if (notificationDenied) {
                        BackgroundAccessRepository.SetupAction.OpenNotificationSettings
                    } else {
                        BackgroundAccessRepository.SetupAction.RequestNotificationPermission
                    }
                },
            ),
            BackgroundAccessRepository.SetupItem(
                title = text("背景刷新由系統安排"),
                subtitle = text("iOS 不保證精確的檢查時間；低耗電模式、使用習慣與系統資源都可能延後刷新。"),
                status = BackgroundAccessRepository.SetupStatus.Info,
            ),
        ),
        platformNote = text("iOS 的背景刷新屬於 best-effort；選擇的週期是最早可執行時間，不是準時保證。"),
    )

    private companion object {
        fun text(source: String, vararg args: Any?): BackgroundAccessRepository.I18nText =
            BackgroundAccessRepository.I18nText(source = source, args = args.toList())
    }
}

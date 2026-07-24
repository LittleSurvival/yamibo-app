package me.thenano.yamibo.yamibo_app.profile.settings.access

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class AndroidBackgroundAccessRepository(
    context: Context,
) : BackgroundAccessRepository {
    private val appContext = context.applicationContext
    private val _state = MutableStateFlow(buildState())

    override val state: StateFlow<BackgroundAccessRepository.SetupState> = _state

    override suspend fun refresh() {
        _state.value = buildState()
    }

    @SuppressLint("QueryPermissionsNeeded", "BatteryLife")
    @RequiresApi(Build.VERSION_CODES.O)
    override fun runAction(action: BackgroundAccessRepository.SetupAction) {
        val intent = when (action) {
            BackgroundAccessRepository.SetupAction.RequestNotificationPermission -> return
            BackgroundAccessRepository.SetupAction.OpenNotificationSettings -> {
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, appContext.packageName)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            }
            BackgroundAccessRepository.SetupAction.OpenBatteryOptimizationSettings -> {
                val requestIntent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = "package:${appContext.packageName}".toUri()
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                if (requestIntent.resolveActivity(appContext.packageManager) != null) {
                    requestIntent
                } else {
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                }
            }
            BackgroundAccessRepository.SetupAction.OpenAppSettings -> {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = "package:${appContext.packageName}".toUri()
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            }
            BackgroundAccessRepository.SetupAction.OpenDontKillMyApp -> {
                Intent(Intent.ACTION_VIEW, "https://dontkillmyapp.com/".toUri()).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            }
        }
        appContext.startActivity(intent)
    }

    private fun buildState(): BackgroundAccessRepository.SetupState {
        val notificationPermissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val notificationsEnabled = NotificationManagerCompat.from(appContext).areNotificationsEnabled()
        val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        val batteryOptimizationIgnored =
            powerManager.isIgnoringBatteryOptimizations(appContext.packageName)

        val notificationItem = when {
            !notificationPermissionGranted -> {
                BackgroundAccessRepository.SetupItem(
                    title = text("通知權限"),
                    subtitle = text("背景同步必須能在通知欄顯示進度。這項可直接在 App 內授予。"),
                    status = BackgroundAccessRepository.SetupStatus.Required,
                    actionLabel = text("授予"),
                    action = BackgroundAccessRepository.SetupAction.RequestNotificationPermission,
                )
            }
            !notificationsEnabled -> {
                BackgroundAccessRepository.SetupItem(
                    title = text("App 通知開關"),
                    subtitle = text("系統目前已關閉這個 App 的通知。請重新打開，不然同步仍然不會顯示在通知欄。"),
                    status = BackgroundAccessRepository.SetupStatus.Required,
                    actionLabel = text("前往"),
                    action = BackgroundAccessRepository.SetupAction.OpenNotificationSettings,
                )
            }
            else -> {
                BackgroundAccessRepository.SetupItem(
                    title = text("通知權限"),
                    subtitle = text("通知欄可正常顯示背景同步進度與完成結果。"),
                    status = BackgroundAccessRepository.SetupStatus.Granted,
                )
            }
        }

        val batteryItem = if (!batteryOptimizationIgnored) {
            BackgroundAccessRepository.SetupItem(
                title = text("電池最佳化"),
                subtitle = text("部分裝置會因電池最佳化提早中止背景網路。建議將本 App 加入忽略最佳化名單。"),
                status = BackgroundAccessRepository.SetupStatus.Recommended,
                actionLabel = text("前往"),
                action = BackgroundAccessRepository.SetupAction.OpenBatteryOptimizationSettings,
            )
        } else {
            BackgroundAccessRepository.SetupItem(
                title = text("電池最佳化"),
                subtitle = text("系統目前不會因電池最佳化優先中止這個 App 的背景同步。"),
                status = BackgroundAccessRepository.SetupStatus.Granted,
            )
        }

        val appSettingsItem = BackgroundAccessRepository.SetupItem(
            title = text("App 系統設定"),
            subtitle = text("若裝置廠商額外限制背景執行，可從這裡進入系統 App 設定再調整。"),
            status = BackgroundAccessRepository.SetupStatus.Info,
            actionLabel = text("前往"),
            action = BackgroundAccessRepository.SetupAction.OpenAppSettings,
        )
        val dontKillMyAppItem = BackgroundAccessRepository.SetupItem(
            title = text("廠商背景限制說明"),
            subtitle = text("部分品牌會額外限制背景同步。若你已開通知與電池最佳化，但任務仍常被中止，請查看對應機型說明。"),
            status = BackgroundAccessRepository.SetupStatus.Info,
            actionLabel = text("查看"),
            action = BackgroundAccessRepository.SetupAction.OpenDontKillMyApp,
        )

        val requiredMissingCount = listOf(notificationItem).count {
            it.status == BackgroundAccessRepository.SetupStatus.Required
        }
        val recommendedCount = listOf(batteryItem).count {
            it.status == BackgroundAccessRepository.SetupStatus.Recommended
        }
        val summary = when {
            requiredMissingCount > 0 -> text("目前缺少 {} 項必要權限。處理完後，背景同步通知才能正常顯示。", requiredMissingCount)
            recommendedCount > 0 -> text("必要權限已具備，但還有 {} 項建議設定，可降低背景同步被系統中止的機率。", recommendedCount)
            else -> text("目前背景同步所需的主要存取都已就緒。")
        }

        return BackgroundAccessRepository.SetupState(
            summary = summary,
            items = listOf(notificationItem, batteryItem, appSettingsItem, dontKillMyAppItem),
            platformNote = text("Android 的背景同步依賴前景通知。開始同步後，只要通知已成功出現，再縮小 App 也能持續執行。"),
        )
    }

    private fun text(source: String, vararg args: Any?): BackgroundAccessRepository.I18nText =
        BackgroundAccessRepository.I18nText(source = source, args = args.toList())
}

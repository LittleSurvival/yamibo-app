package me.thenano.yamibo.yamibo_app.profile.settings.backup

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import me.thenano.yamibo.yamibo_app.Logger
import me.thenano.yamibo.yamibo_app.i18n.i18n
import me.thenano.yamibo.yamibo_app.repository.settings.AppSettingsRepository
import me.thenano.yamibo.yamibo_app.store.settings.AndroidSettingsStore
import me.thenano.yamibo.yamibo_app.util.time.currentTimeMillis

class PanCloudBackupWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val notifications = AndroidBackupNotificationRepository(applicationContext)
        setForeground(createForegroundInfo(notifications, i18n("正在建立備份")))
        return try {
            val settings = AppSettingsRepository(AndroidSettingsStore(applicationContext))
            if (!settings.panCloudAutoBackupEnabled.getValue()) {
                return Result.success()
            }
            val components = AndroidPanCloudBackupSupport.createComponents(applicationContext)
            val restored = components.accountRepository.restoreSession()
            if (restored.isFailure || !components.accountRepository.status.loggedIn) {
                // 登录态失效（如 refresh_token 过期），不重试，等待用户重新登录。
                notifications.showFailed(i18n("網盤操作失敗"))
                return Result.failure()
            }
            val file = components.backupRepository.createBackup(automatic = true).getOrThrow()
            components.backupRepository.cleanupAutoBackups(settings.backupMaxAutoFiles.getValue()).getOrThrow()
            settings.backupLastAutoBackupAt.setValue(currentTimeMillis().toString())
            notifications.showCompleted("${file.name}，${file.bytes} bytes")
            Result.success()
        } catch (throwable: Throwable) {
            Logger.e("PanCloudBackupWorker", "Cloud backup failed", throwable)
            notifications.showFailed(throwable.message ?: i18n("建立備份時發生錯誤"))
            Result.retry()
        }
    }

    private fun createForegroundInfo(
        notifications: AndroidBackupNotificationRepository,
        text: String,
    ): ForegroundInfo {
        val notification = notifications.buildProgressNotification(text)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                AndroidBackupNotificationRepository.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(AndroidBackupNotificationRepository.NOTIFICATION_ID, notification)
        }
    }
}

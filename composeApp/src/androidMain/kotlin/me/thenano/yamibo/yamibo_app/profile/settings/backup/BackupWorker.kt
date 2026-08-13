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

class BackupWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val notifications = AndroidBackupNotificationRepository(applicationContext)
        setForeground(createForegroundInfo(notifications, i18n("正在建立備份")))
        return try {
            val settings = AppSettingsRepository(AndroidSettingsStore(applicationContext))
            if (settings.backupFolderUri.getValue().isBlank()) {
                notifications.showFailed(i18n("尚未選擇備份資料夾"))
                return Result.failure()
            }
            val repository = AndroidBackupSupport.createRepository(applicationContext)
            val file = repository.createBackup(automatic = true).getOrThrow()
            repository.cleanupAutoBackups(settings.backupMaxAutoFiles.getValue()).getOrThrow()

            var cloudName: String? = null
            if (settings.backupToCloudEnabled.getValue()) {
                val components = AndroidPanCloudBackupSupport.createComponents(applicationContext)
                val restored = components.accountRepository.restoreSession()
                if (restored.isSuccess && components.accountRepository.status.loggedIn) {
                    val cloudFile = components.backupRepository.createBackup(automatic = true).getOrThrow()
                    components.backupRepository.cleanupAutoBackups(settings.backupMaxAutoFiles.getValue()).getOrThrow()
                    cloudName = cloudFile.name
                }
            }

            settings.backupLastAutoBackupAt.setValue(currentTimeMillis().toString())
            val summary = if (cloudName != null) "${file.name} + $cloudName" else "${file.name}，${file.bytes} bytes"
            notifications.showCompleted(summary)
            Result.success()
        } catch (throwable: Throwable) {
            Logger.e("BackupWorker", "Automatic backup failed", throwable)
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

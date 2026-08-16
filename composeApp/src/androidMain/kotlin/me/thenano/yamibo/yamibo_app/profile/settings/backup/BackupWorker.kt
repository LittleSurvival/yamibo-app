package me.thenano.yamibo.yamibo_app.profile.settings.backup

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import me.thenano.yamibo.yamibo_app.Logger
import me.thenano.yamibo.yamibo_app.i18n.i18n
import me.thenano.yamibo.yamibo_app.repository.BackupRepository
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
            val maxAutoFiles = settings.backupMaxAutoFiles.getValue()
            val cloudEnabled = settings.backupToCloudEnabled.getValue()

            var cloudComponents: PanCloudBackupComponents? = null
            var cloudLoggedIn = false
            if (cloudEnabled) {
                val components = AndroidPanCloudBackupSupport.createComponents(applicationContext)
                cloudComponents = components
                cloudLoggedIn = components.accountRepository.restoreSession()
                    .fold(
                        onSuccess = { components.accountRepository.status.loggedIn },
                        onFailure = { false },
                    )
            }

            val file = when (resolveAutomaticBackupTarget(cloudEnabled, cloudLoggedIn)) {
                AutomaticBackupTarget.LOCAL -> {
                    if (settings.backupFolderUri.getValue().isBlank()) {
                        notifications.showFailed(i18n("尚未選擇備份資料夾"))
                        return Result.failure()
                    }
                    createAutomaticBackupAndCleanup(
                        repository = AndroidBackupSupport.createRepository(applicationContext),
                        maxAutoFiles = maxAutoFiles,
                    )
                }
                AutomaticBackupTarget.CLOUD -> createAutomaticBackupAndCleanup(
                    repository = checkNotNull(cloudComponents).backupRepository,
                    maxAutoFiles = maxAutoFiles,
                )
                AutomaticBackupTarget.CLOUD_UNAVAILABLE -> {
                    notifications.showFailed(i18n("網盤未登入，無法建立定期備份"))
                    return Result.failure()
                }
            }

            settings.backupLastAutoBackupAt.setValue(currentTimeMillis().toString())
            notifications.showCompleted(automaticBackupSummary(file))
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

internal enum class AutomaticBackupTarget {
    LOCAL,
    CLOUD,
    CLOUD_UNAVAILABLE,
}

internal fun resolveAutomaticBackupTarget(
    backupToCloudEnabled: Boolean,
    cloudLoggedIn: Boolean,
): AutomaticBackupTarget = when {
    backupToCloudEnabled && cloudLoggedIn -> AutomaticBackupTarget.CLOUD
    backupToCloudEnabled -> AutomaticBackupTarget.CLOUD_UNAVAILABLE
    else -> AutomaticBackupTarget.LOCAL
}

internal fun automaticBackupSummary(file: BackupRepository.BackupFileInfo): String =
    "${file.name}，${file.bytes} bytes"

internal suspend fun createAutomaticBackupAndCleanup(
    repository: BackupRepository,
    maxAutoFiles: Int,
): BackupRepository.BackupFileInfo {
    val file = repository.createBackup(automatic = true).getOrThrow()
    repository.cleanupAutoBackups(maxAutoFiles).getOrThrow()
    return file
}

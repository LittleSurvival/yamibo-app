package me.thenano.yamibo.yamibo_app.favorite.sync

import me.thenano.yamibo.yamibo_app.i18n.i18n

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.StateFlow
import me.thenano.yamibo.yamibo_app.Logger
import me.thenano.yamibo.yamibo_app.repository.BackgroundTaskRepository

class AndroidBackgroundTaskRepository(
    context: Context,
) : BackgroundTaskRepository {
    private val appContext = context.applicationContext

    override val runningFavoriteSyncRunIds: StateFlow<Set<String>> =
        FavoriteSyncForegroundService.runningFavoriteSyncRunIds

    override suspend fun startFavoriteSync(runId: String): BackgroundTaskRepository.StartResult {
        if (!AndroidAppForegroundTracker.isForeground()) {
            return BackgroundTaskRepository.StartResult.Rejected(i18n("請保持 App 在前景後再開始同步。"))
        }
        if (isNotificationAccessMissing()) {
            return BackgroundTaskRepository.StartResult.Rejected(
                reason = i18n("請先允許通知權限，背景同步才會顯示在通知欄。"),
                requiresBackgroundAccessSetup = true,
            )
        }

        return try {
            val intent = FavoriteSyncForegroundService.createStartIntent(appContext, runId)
            ContextCompat.startForegroundService(appContext, intent)
            BackgroundTaskRepository.StartResult.Started
        } catch (error: Throwable) {
            Logger.e("AndroidBackgroundTaskRepository", "Failed to start favorite sync runId=$runId", error)
            BackgroundTaskRepository.StartResult.Rejected(i18n("目前無法啟動背景同步，請在 App 前景時重試。"))
        }
    }

    override suspend fun cancelFavoriteSync(runId: String) {
        val intent = FavoriteSyncForegroundService.createCancelIntent(appContext, runId)
        ContextCompat.startForegroundService(appContext, intent)
    }

    private fun isNotificationAccessMissing(): Boolean {
        val runtimePermissionMissing =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        return runtimePermissionMissing || !NotificationManagerCompat.from(appContext).areNotificationsEnabled()
    }
}


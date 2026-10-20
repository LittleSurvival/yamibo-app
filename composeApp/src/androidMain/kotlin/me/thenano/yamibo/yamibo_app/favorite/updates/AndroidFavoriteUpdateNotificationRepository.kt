package me.thenano.yamibo.yamibo_app.favorite.updates

import me.thenano.yamibo.yamibo_app.i18n.i18n


import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import me.thenano.yamibo.yamibo_app.MainActivity
import me.thenano.yamibo.yamibo_app.Logger
import me.thenano.yamibo.yamibo_app.notification.AndroidNotificationMetadata
import me.thenano.yamibo.yamibo_app.repository.FavoriteUpdateRepository.RunSnapshot

internal class AndroidFavoriteUpdateNotificationRepository(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val notificationManager = NotificationManagerCompat.from(appContext)

    init {
        ensureChannel()
    }

    fun buildProgressNotification(snapshot: RunSnapshot): Notification {
        val total = snapshot.totalCount.coerceAtLeast(0)
        val processed = (snapshot.completedCount + snapshot.skippedCount + snapshot.failedCount).coerceAtMost(total)
        val progress = if (total > 0) ((processed * 100f) / total).toInt().coerceIn(0, 100) else 0
        return NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(AndroidNotificationMetadata.SMALL_ICON_RES_ID)
            .setContentTitle(i18n("收藏更新"))
            .setContentText(snapshot.currentItem?.takeIf { it.isNotBlank() } ?: i18n("正在檢查收藏更新"))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, progress, total == 0)
            .setContentIntent(createOpenAppPendingIntent())
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, i18n("取消"), FavoriteUpdateCancelReceiver.createPendingIntent(appContext, snapshot.runId))
            .build()
    }

    fun showCompleted(snapshot: RunSnapshot) {
        post(
            NOTIFICATION_ID,
            NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(AndroidNotificationMetadata.SMALL_ICON_RES_ID)
                .setContentTitle(i18n("收藏更新完成"))
                .setContentText(if (snapshot.detectedCount > 0) i18n("偵測到 {} 個更新", snapshot.detectedCount) else i18n("沒有偵測到更新"))
                .setAutoCancel(true)
                .setOnlyAlertOnce(false)
                .setContentIntent(createOpenAppPendingIntent())
                .setProgress(0, 0, false)
                .build(),
        )
    }

    fun showFailed(title: String, text: String) {
        post(
            NOTIFICATION_ID,
            NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(AndroidNotificationMetadata.SMALL_ICON_RES_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setOnlyAlertOnce(false)
                .setContentIntent(createOpenAppPendingIntent())
                .setProgress(0, 0, false)
                .build(),
        )
    }

    fun showProgress(snapshot: RunSnapshot) {
        post(NOTIFICATION_ID, buildProgressNotification(snapshot))
    }

    fun showWaiting(runId: String) {
        post(
            NOTIFICATION_ID,
            NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(AndroidNotificationMetadata.SMALL_ICON_RES_ID)
                .setContentTitle(i18n("收藏更新"))
                .setContentText(i18n("已儲存進度，等待系統繼續檢查"))
                .setOnlyAlertOnce(true)
                .setContentIntent(createOpenAppPendingIntent())
                .addAction(0, i18n("取消"), FavoriteUpdateCancelReceiver.createPendingIntent(appContext, runId))
                .build(),
        )
    }

    fun dismiss() {
        notificationManager.cancel(NOTIFICATION_ID)
    }

    @Suppress("MissingPermission")
    private fun post(id: Int, notification: Notification) {
        // Notifications are optional presentation. A revoked permission must not abort the
        // repository work or escape from the progress collector as an uncaught exception.
        try {
            notificationManager.notify(id, notification)
        } catch (error: RuntimeException) {
            Logger.e("FavoriteUpdateNotification", "Unable to post update notification", error)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                i18n("收藏更新"),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = i18n("顯示收藏更新檢查進度與結果")
            },
        )
    }

    private fun createOpenAppPendingIntent(): PendingIntent {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            appContext,
            1002,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val CHANNEL_ID = "favorite_update_channel"
        const val NOTIFICATION_ID = 228120
    }
}


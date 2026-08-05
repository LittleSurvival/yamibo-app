package me.thenano.yamibo.yamibo_app.favorite.sync

import me.thenano.yamibo.yamibo_app.i18n.i18n

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import me.thenano.yamibo.yamibo_app.MainActivity
import me.thenano.yamibo.yamibo_app.notification.AndroidNotificationMetadata
import me.thenano.yamibo.yamibo_app.repository.SystemNotificationRepository

class AndroidSystemNotificationRepository(
    context: Context,
) : SystemNotificationRepository {
    private val appContext = context.applicationContext
    private val notificationManager = NotificationManagerCompat.from(appContext)

    init {
        ensureChannel()
    }

    fun buildProgressNotification(model: SystemNotificationRepository.ProgressNotificationModel): Notification {
        return NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(AndroidNotificationMetadata.SMALL_ICON_RES_ID)
            .setContentTitle(model.title)
            .setContentText(model.text)
            .setOnlyAlertOnce(true)
            .setOngoing(model.ongoing)
            .setProgress(100, model.progress.coerceIn(0, 100), model.indeterminate)
            .setContentIntent(createOpenAppPendingIntent())
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .apply {
                if (model.canCancel && model.runId != null) {
                    val runId = model.runId ?: return@apply
                    addAction(
                        0,
                        "Cancel",
                        FavoriteSyncCancelReceiver.createPendingIntent(appContext, runId),
                    )
                }
            }
            .build()
    }

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    override suspend fun showProgress(model: SystemNotificationRepository.ProgressNotificationModel) {
        notificationManager.notify(model.notificationId, buildProgressNotification(model))
    }

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    override suspend fun showCompleted(notificationId: Int, title: String, text: String) {
        notificationManager.notify(
            notificationId,
            NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(AndroidNotificationMetadata.SMALL_ICON_RES_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(createOpenAppPendingIntent())
                .setProgress(0, 0, false)
                .build(),
        )
    }

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    override suspend fun showFailed(notificationId: Int, title: String, text: String) {
        notificationManager.notify(
            notificationId,
            NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(AndroidNotificationMetadata.SMALL_ICON_RES_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(createOpenAppPendingIntent())
                .setProgress(0, 0, false)
                .build(),
        )
    }

    override suspend fun dismiss(notificationId: Int) {
        notificationManager.cancel(notificationId)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            i18n("收藏同步"),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = i18n("顯示百合會收藏同步進度")
        }
        manager.createNotificationChannel(channel)
    }

    private fun createOpenAppPendingIntent(): PendingIntent {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            appContext,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val CHANNEL_ID = "favorite_sync_channel"
    }
}


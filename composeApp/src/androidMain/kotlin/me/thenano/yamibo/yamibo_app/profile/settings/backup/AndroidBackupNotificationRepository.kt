package me.thenano.yamibo.yamibo_app.profile.settings.backup

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import me.thenano.yamibo.yamibo_app.Logger
import me.thenano.yamibo.yamibo_app.MainActivity
import me.thenano.yamibo.yamibo_app.notification.AndroidNotificationMetadata

internal class AndroidBackupNotificationRepository(context: Context) {
    private val appContext = context.applicationContext
    private val notificationManager = NotificationManagerCompat.from(appContext)

    init {
        ensureChannel()
    }

    fun buildProgressNotification(text: String): Notification =
        NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(AndroidNotificationMetadata.SMALL_ICON_RES_ID)
            .setContentTitle("設定與收藏備份")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, 50, true)
            .setContentIntent(createOpenAppPendingIntent())
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    @SuppressLint("MissingPermission")
    fun showCompleted(text: String) {
        runCatching {
            notificationManager.notify(
                NOTIFICATION_ID,
                NotificationCompat.Builder(appContext, CHANNEL_ID)
                    .setSmallIcon(AndroidNotificationMetadata.SMALL_ICON_RES_ID)
                    .setContentTitle("設定與收藏備份完成")
                    .setContentText(text)
                    .setAutoCancel(true)
                    .setContentIntent(createOpenAppPendingIntent())
                    .build(),
            )
        }.onFailure { Logger.w(TAG, "Failed to show completed backup notification", it) }
    }

    @SuppressLint("MissingPermission")
    fun showFailed(text: String) {
        runCatching {
            notificationManager.notify(
                NOTIFICATION_ID,
                NotificationCompat.Builder(appContext, CHANNEL_ID)
                    .setSmallIcon(AndroidNotificationMetadata.SMALL_ICON_RES_ID)
                    .setContentTitle("設定與收藏備份失敗")
                    .setContentText(text)
                    .setAutoCancel(true)
                    .setContentIntent(createOpenAppPendingIntent())
                    .build(),
            )
        }.onFailure { Logger.w(TAG, "Failed to show failed backup notification", it) }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "設定與收藏備份", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun createOpenAppPendingIntent(): PendingIntent {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            appContext,
            1031,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val TAG = "AndroidBackupNotification"
        const val CHANNEL_ID = "yamibo_backup_channel"
        const val NOTIFICATION_ID = 231001
    }
}

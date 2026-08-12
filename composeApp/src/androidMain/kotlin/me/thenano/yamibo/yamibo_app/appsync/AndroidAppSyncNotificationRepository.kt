package me.thenano.yamibo.yamibo_app.appsync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import me.thenano.yamibo.yamibo_app.MainActivity
import me.thenano.yamibo.yamibo_app.i18n.i18n
import me.thenano.yamibo.yamibo_app.notification.AndroidNotificationMetadata

internal class AndroidAppSyncNotificationRepository(context: Context) {
    private val appContext = context.applicationContext
    private val notificationManager = NotificationManagerCompat.from(appContext)

    init {
        ensureChannel()
    }

    fun showQuarantined() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        notificationManager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(AndroidNotificationMetadata.SMALL_ICON_RES_ID)
                .setContentTitle(i18n("雲端同步需要檢查"))
                .setContentText(i18n("背景同步已停止，請開啟雲端同步頁面檢查資料"))
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(createOpenAppPendingIntent())
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build(),
        )
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                i18n("雲端同步警告"),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = i18n("顯示需要使用者處理的雲端同步問題")
            },
        )
    }

    private fun createOpenAppPendingIntent(): PendingIntent {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            appContext,
            NOTIFICATION_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private companion object {
        const val CHANNEL_ID = "app_sync_attention_channel"
        const val NOTIFICATION_ID = 228140
        const val NOTIFICATION_REQUEST_CODE = 1040
    }
}

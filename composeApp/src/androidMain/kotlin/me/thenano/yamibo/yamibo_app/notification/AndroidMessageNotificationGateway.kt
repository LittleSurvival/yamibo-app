package me.thenano.yamibo.yamibo_app.notification

import android.Manifest
import android.app.Notification
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
import me.thenano.yamibo.yamibo_app.Logger
import me.thenano.yamibo.yamibo_app.MainActivity
import me.thenano.yamibo.yamibo_app.i18n.i18n
import me.thenano.yamibo.yamibo_app.repository.notification.MessageNotificationGateway

internal class AndroidMessageNotificationGateway(
    context: Context,
    private val userId: Int? = null,
    private val postingFence: AndroidMessageNotificationPostingFence = AndroidMessageNotificationPostingFence(),
    private val canPost: () -> Boolean = { true },
) : MessageNotificationGateway {
    private val appContext = context.applicationContext
    private val manager = NotificationManagerCompat.from(appContext)

    override suspend fun showMessageNotification(): Boolean {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return false
        if (!manager.areNotificationsEnabled()) return false

        ensureChannel(appContext)
        val openPendingIntent = PendingIntent.getActivity(
            appContext,
            REQUEST_CODE_OPEN,
            Intent(appContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(MainActivity.EXTRA_FROM_MESSAGE_NOTIFICATION, true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val mutePendingIntent = PendingIntent.getBroadcast(
            appContext,
            REQUEST_CODE_MUTE,
            Intent(appContext, MessageNotificationActionReceiver::class.java).apply {
                action = MessageNotificationActionReceiver.ACTION_MUTE_TODAY
                userId?.let { putExtra(MessageNotificationActionReceiver.EXTRA_USER_ID, it) }
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(AndroidNotificationMetadata.SMALL_ICON_RES_ID)
            .setContentTitle(i18n("新消息通知"))
            .setContentText("您有新的通知，快來看看吧 !")
            .setAutoCancel(true)
            .setContentIntent(openPendingIntent)
            .addAction(0, "查看通知", openPendingIntent)
            .addAction(0, "不再提醒（僅限今日）", mutePendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()
        return try {
            postingFence.postIfAllowed(canPost) { manager.notify(NOTIFICATION_ID, notification) }
        } catch (error: SecurityException) {
            Logger.e("MessageNotification", "Notification permission was revoked", error)
            false
        }
    }

    override suspend fun dismissMessageNotification() {
        dismissActiveMessageNotification(appContext)
    }

    companion object {
        const val CHANNEL_ID = "message_notification_channel"
        const val NOTIFICATION_ID = 228150
        private const val REQUEST_CODE_OPEN = 1050
        private const val REQUEST_CODE_MUTE = 1051

        /** Also called before Firebase can display its default notification channel. */
        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            (context.applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        i18n("新消息通知"),
                        NotificationManager.IMPORTANCE_DEFAULT,
                    ).apply { lockscreenVisibility = Notification.VISIBILITY_PRIVATE },
                )
        }
    }
}

internal fun dismissActiveMessageNotification(context: Context) {
    val appContext = context.applicationContext
    val manager = NotificationManagerCompat.from(appContext)
    manager.cancel(AndroidMessageNotificationGateway.NOTIFICATION_ID)
    // Firebase auto-posts use generated tags/IDs, unlike the fixed local notification ID.
    val systemManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
    systemManager?.activeNotifications?.filter {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            it.notification.channelId == AndroidMessageNotificationGateway.CHANNEL_ID
        } else {
            // API 24–25 have no channels. Match SDK tags or the server's generic UUID-tagged payload.
            isLegacyFirebaseMessageNotification(
                it.tag,
                it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
                it.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            )
        }
    }?.forEach { notification -> manager.cancel(notification.tag, notification.id) }
}

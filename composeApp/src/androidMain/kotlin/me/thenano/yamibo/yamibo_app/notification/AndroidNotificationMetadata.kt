package me.thenano.yamibo.yamibo_app.notification

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import me.thenano.yamibo.yamibo_app.R

internal object AndroidNotificationMetadata {
    const val SMALL_ICON_RES_ID: Int = R.drawable.ic_stat_yamibo
    const val SIGN_REMINDER_CHANNEL_ID = "yamibo_sign_reminder_channel"
    const val SIGN_REMINDER_NOTIFICATION_ID = 240619
}

internal class SignReminderNotificationController(
    private val cancelNotification: (Int) -> Unit,
) {
    fun dismissActiveReminder() {
        cancelNotification(AndroidNotificationMetadata.SIGN_REMINDER_NOTIFICATION_ID)
    }
}

internal fun dismissActiveSignReminder(context: Context) {
    val manager = NotificationManagerCompat.from(context.applicationContext)
    SignReminderNotificationController(manager::cancel).dismissActiveReminder()
}

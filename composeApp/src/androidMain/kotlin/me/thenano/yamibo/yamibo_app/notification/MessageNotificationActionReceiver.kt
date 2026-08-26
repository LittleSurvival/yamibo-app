package me.thenano.yamibo.yamibo_app.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

internal class MessageNotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_MUTE_TODAY) return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                AndroidMessageNotificationRuntime.createChecker(context).muteToday()
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_MUTE_TODAY =
            "me.thenano.yamibo.yamibo_app.action.MUTE_MESSAGE_NOTIFICATIONS_TODAY"
    }
}

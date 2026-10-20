package me.thenano.yamibo.yamibo_app.favorite.updates

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class FavoriteUpdateCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val runId = intent.getStringExtra(EXTRA_RUN_ID) ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                AndroidFavoriteUpdateSupport.createRepository(context).interruptRun(runId)
                WorkManager.getInstance(context.applicationContext)
                    .cancelUniqueWork(AndroidFavoriteUpdateScheduler.UNIQUE_MANUAL_WORK)
                AndroidFavoriteUpdateNotificationRepository(context).dismiss()
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val ACTION_CANCEL = "me.thenano.yamibo.favorite.update.CANCEL"
        private const val EXTRA_RUN_ID = "run_id"

        fun createPendingIntent(context: Context, runId: String): PendingIntent {
            val intent = Intent(context, FavoriteUpdateCancelReceiver::class.java).apply {
                action = ACTION_CANCEL
                putExtra(EXTRA_RUN_ID, runId)
            }
            return PendingIntent.getBroadcast(
                context,
                runId.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}

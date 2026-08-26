package me.thenano.yamibo.yamibo_app.notification

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import me.thenano.yamibo.yamibo_app.util.time.FixedScheduleInterval
import kotlin.time.toJavaDuration

internal class AndroidMessageNotificationScheduler(context: Context) {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    fun setEnabled(enabled: Boolean, interval: FixedScheduleInterval) {
        if (!enabled) {
            workManager.cancelUniqueWork(UNIQUE_PERIODIC_WORK)
            return
        }
        val request = PeriodicWorkRequestBuilder<MessageNotificationWorker>(interval.duration.toJavaDuration())
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .addTag(WORK_TAG)
            .build()
        workManager.enqueueUniquePeriodicWork(
            UNIQUE_PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    companion object {
        const val WORK_TAG = "message-notification-check"
        const val UNIQUE_PERIODIC_WORK = "message-notification-check-periodic"
    }
}

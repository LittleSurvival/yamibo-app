package me.thenano.yamibo.yamibo_app.appsync

import android.content.Context
import androidx.work.Constraints
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

class AndroidAppSyncBackgroundScheduler(context: Context) : AppSyncBackgroundScheduler {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    override suspend fun setEnabled(enabled: Boolean) {
        if (!enabled) {
            workManager.cancelUniqueWork(PERIODIC_WORK)
            return
        }
        val request = PeriodicWorkRequestBuilder<AppSyncWorker>(6.hours.toJavaDuration())
            .setConstraints(constraints())
            .addTag(WORK_TAG)
            .build()
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    override suspend fun runNow() {
        val request = OneTimeWorkRequestBuilder<AppSyncWorker>()
            .setConstraints(constraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30.seconds.toJavaDuration())
            .addTag(WORK_TAG)
            .build()
        workManager.enqueueUniqueWork(MANUAL_WORK, ExistingWorkPolicy.KEEP, request)
    }

    private fun constraints() = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true)
        .build()

    private companion object {
        const val WORK_TAG = "yamibo-app-sync"
        const val PERIODIC_WORK = "yamibo-app-sync-periodic"
        const val MANUAL_WORK = "yamibo-app-sync-manual"
    }
}

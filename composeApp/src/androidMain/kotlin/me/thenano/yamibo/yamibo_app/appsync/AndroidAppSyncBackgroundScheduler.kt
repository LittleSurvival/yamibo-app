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
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.thenano.yamibo.yamibo_app.util.time.currentTimeMillis
import me.thenano.yamibo.yamibo_app.util.time.FixedScheduleInterval

class AndroidAppSyncBackgroundScheduler(context: Context) : AppSyncBackgroundScheduler {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    override fun setEnabled(enabled: Boolean, interval: FixedScheduleInterval) {
        if (!enabled) {
            workManager.cancelUniqueWork(PERIODIC_WORK)
            workManager.cancelUniqueWork(LIFECYCLE_WORK)
            return
        }
        val request = PeriodicWorkRequestBuilder<AppSyncWorker>(
            interval.duration.inWholeMilliseconds,
            TimeUnit.MILLISECONDS,
        )
            .setConstraints(constraints())
            .addTag(WORK_TAG)
            .build()
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    override fun runNow() {
        val request = OneTimeWorkRequestBuilder<AppSyncWorker>()
            .setConstraints(constraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(WORK_TAG)
            .build()
        workManager.enqueueUniqueWork(LIFECYCLE_WORK, ExistingWorkPolicy.KEEP, request)
    }

    override val ownsManualExecution: Boolean = true

    override suspend fun runManual() {
        val request = OneTimeWorkRequestBuilder<AppSyncWorker>()
            .setConstraints(constraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(WORK_TAG)
            .addTag(MANUAL_WORK_TAG)
            .build()
        withContext(Dispatchers.IO) {
            workManager.enqueueUniqueWork(MANUAL_WORK, ExistingWorkPolicy.KEEP, request).result.get()
        }
    }

    suspend fun continueRecovery(nextRetryAtEpochMillis: Long?) {
        val delay = ((nextRetryAtEpochMillis ?: (currentTimeMillis() + 30_000)) - currentTimeMillis())
            .coerceIn(0, 6 * 60 * 60 * 1_000L)
        val request = OneTimeWorkRequestBuilder<AppSyncWorker>()
            .setConstraints(constraints())
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(WORK_TAG)
            .addTag(MANUAL_WORK_TAG)
            .build()
        withContext(Dispatchers.IO) {
            workManager.enqueueUniqueWork(MANUAL_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
                .result.get()
        }
    }

    private fun constraints() = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true)
        .build()

    private companion object {
        const val WORK_TAG = "yamibo-app-sync"
        const val PERIODIC_WORK = "yamibo-app-sync-periodic"
        const val LIFECYCLE_WORK = "yamibo-app-sync-lifecycle"
        const val MANUAL_WORK = "yamibo-app-sync-manual-recovery"
        const val MANUAL_WORK_TAG = "yamibo-app-sync-manual"
    }
}

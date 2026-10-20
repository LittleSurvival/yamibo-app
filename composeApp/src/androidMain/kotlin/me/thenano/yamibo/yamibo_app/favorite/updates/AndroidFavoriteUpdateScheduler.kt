package me.thenano.yamibo.yamibo_app.favorite.updates

import android.content.Context
import androidx.work.Constraints
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.workDataOf
import me.thenano.yamibo.yamibo_app.repository.settings.FavoriteUpdateInterval
import kotlin.time.toJavaDuration
import java.util.concurrent.TimeUnit

class AndroidFavoriteUpdateScheduler(
    context: Context,
) : FavoriteUpdateScheduler {
    private val appContext = context.applicationContext
    private val workManager = WorkManager.getInstance(appContext)

    override suspend fun startFavoriteUpdate(runId: String): FavoriteUpdateScheduler.StartResult {
        val request = OneTimeWorkRequestBuilder<FavoriteUpdateWorker>()
            .setInputData(workDataOf(FavoriteUpdateWorker.KEY_RUN_ID to runId))
            .setConstraints(defaultConstraints())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .addTag(WORK_TAG)
            .build()
        workManager.enqueueUniqueWork(UNIQUE_MANUAL_WORK, ExistingWorkPolicy.REPLACE, request).await()
        return FavoriteUpdateScheduler.StartResult.Started
    }

    override suspend fun cancelFavoriteUpdate(runId: String) {
        workManager.cancelUniqueWork(UNIQUE_MANUAL_WORK).await()
        AndroidFavoriteUpdateNotificationRepository(appContext).dismiss()
    }

    override suspend fun schedulePeriodicFavoriteUpdate(interval: FavoriteUpdateInterval) {
        val duration = interval.fixedInterval?.duration
        if (interval.smart || duration == null) {
            workManager.cancelUniqueWork(UNIQUE_PERIODIC_WORK)
            return
        }
        val request = PeriodicWorkRequestBuilder<FavoriteUpdatePeriodicWorker>(duration.toJavaDuration())
            .setConstraints(defaultConstraints())
            .addTag(WORK_TAG)
            .build()
        workManager.enqueueUniquePeriodicWork(
            UNIQUE_PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    internal suspend fun enqueueAutomaticUpdate() {
        val request = OneTimeWorkRequestBuilder<FavoriteUpdateWorker>()
            .setConstraints(defaultConstraints())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .addTag(WORK_TAG)
            .build()
        // Manual and periodic triggers share one queue and notification. A periodic tick
        // must not replace an active run or one waiting for its next chunk.
        workManager.enqueueUniqueWork(UNIQUE_MANUAL_WORK, ExistingWorkPolicy.KEEP, request).await()
    }

    private fun defaultConstraints(): Constraints =
        Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

    companion object {
        const val WORK_TAG = "favorite-update"
        const val UNIQUE_MANUAL_WORK = "favorite-update-manual"
        private const val UNIQUE_PERIODIC_WORK = "favorite-update-periodic"
    }
}

package me.thenano.yamibo.yamibo_app.favorite.updates

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import me.thenano.yamibo.yamibo_app.repository.settings.FavoriteUpdateInterval
import kotlin.time.toJavaDuration

class AndroidFavoriteUpdateScheduler(
    context: Context,
) : FavoriteUpdateScheduler {
    private val appContext = context.applicationContext
    private val workManager = WorkManager.getInstance(appContext)

    override suspend fun startFavoriteUpdate(runId: String): FavoriteUpdateScheduler.StartResult {
        val request = OneTimeWorkRequestBuilder<FavoriteUpdateWorker>()
            .setInputData(workDataOf(FavoriteUpdateWorker.KEY_RUN_ID to runId))
            .setConstraints(defaultConstraints())
            .addTag(WORK_TAG)
            .build()
        workManager.enqueueUniqueWork(UNIQUE_MANUAL_WORK, ExistingWorkPolicy.REPLACE, request)
        return FavoriteUpdateScheduler.StartResult.Started
    }

    override suspend fun cancelFavoriteUpdate(runId: String) {
        workManager.cancelAllWorkByTag(WORK_TAG)
    }

    override suspend fun schedulePeriodicFavoriteUpdate(interval: FavoriteUpdateInterval) {
        val duration = interval.fixedInterval?.duration
        if (interval.smart || duration == null) {
            workManager.cancelUniqueWork(UNIQUE_PERIODIC_WORK)
            return
        }
        val request = PeriodicWorkRequestBuilder<FavoriteUpdateWorker>(duration.toJavaDuration())
            .setConstraints(defaultConstraints())
            .addTag(WORK_TAG)
            .build()
        workManager.enqueueUniquePeriodicWork(
            UNIQUE_PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
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

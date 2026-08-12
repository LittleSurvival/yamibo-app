package me.thenano.yamibo.yamibo_app.profile.settings.backup

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import me.thenano.yamibo.yamibo_app.repository.settings.BackupInterval
import kotlin.time.toJavaDuration

class AndroidBackupScheduler(context: Context) : BackupScheduler {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    override suspend fun schedule(interval: BackupInterval) {
        val duration = interval.fixedInterval?.duration
        if (duration == null) {
            workManager.cancelUniqueWork(UNIQUE_PERIODIC_WORK)
            return
        }
        val request = PeriodicWorkRequestBuilder<BackupWorker>(duration.toJavaDuration())
            .setConstraints(defaultConstraints())
            .addTag(WORK_TAG)
            .build()
        workManager.enqueueUniquePeriodicWork(
            UNIQUE_PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    override suspend fun runNow() {
        val request = OneTimeWorkRequestBuilder<BackupWorker>()
            .setConstraints(defaultConstraints())
            .addTag(WORK_TAG)
            .build()
        workManager.enqueueUniqueWork(UNIQUE_MANUAL_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    override suspend fun cancel() {
        workManager.cancelAllWorkByTag(WORK_TAG)
    }

    private fun defaultConstraints(): Constraints =
        Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

    companion object {
        const val WORK_TAG = "yamibo-backup"
        private const val UNIQUE_MANUAL_WORK = "yamibo-backup-manual"
        private const val UNIQUE_PERIODIC_WORK = "yamibo-backup-periodic"
    }
}

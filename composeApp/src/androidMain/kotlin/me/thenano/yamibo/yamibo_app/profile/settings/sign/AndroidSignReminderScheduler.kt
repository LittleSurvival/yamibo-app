package me.thenano.yamibo.yamibo_app.profile.settings.sign

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import me.thenano.yamibo.yamibo_app.notification.dismissActiveSignReminder
import me.thenano.yamibo.yamibo_app.repository.settings.SignReminderFrequency
import java.util.concurrent.TimeUnit

import androidx.work.workDataOf

class AndroidSignReminderScheduler(context: Context) : SignReminderScheduler {
    private val appContext = context.applicationContext
    private val workManager = WorkManager.getInstance(appContext)

    override suspend fun schedule(frequency: SignReminderFrequency) {
        val minutes = getIntervalMinutes(frequency)
        if (minutes == null) {
            workManager.cancelUniqueWork(UNIQUE_PERIODIC_WORK)
            return
        }
        val request = PeriodicWorkRequestBuilder<SignReminderWorker>(minutes, TimeUnit.MINUTES)
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
        val request = androidx.work.OneTimeWorkRequestBuilder<SignReminderWorker>()
            .setInputData(workDataOf(SignReminderWorker.KEY_IS_TEST to true))
            .addTag(WORK_TAG)
            .build()
        workManager.enqueueUniqueWork(
            UNIQUE_MANUAL_WORK,
            androidx.work.ExistingWorkPolicy.REPLACE,
            request
        )
    }

    override suspend fun cancel() {
        workManager.cancelAllWorkByTag(WORK_TAG)
    }

    override suspend fun dismissActiveReminder() {
        dismissActiveSignReminder(appContext)
    }

    private fun getIntervalMinutes(frequency: SignReminderFrequency): Long? {
        return when (frequency) {
            SignReminderFrequency.OFF -> null
            else -> (24L / frequency.timesPerDay.toFloat() * 60L).toLong()
        }
    }

    private fun defaultConstraints(): Constraints =
        Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

    companion object {
        const val WORK_TAG = "yamibo-sign-reminder"
        const val UNIQUE_PERIODIC_WORK = "yamibo-sign-reminder-periodic"
        const val UNIQUE_MANUAL_WORK = "yamibo-sign-reminder-manual"
    }
}

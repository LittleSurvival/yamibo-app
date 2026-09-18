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
import androidx.work.WorkInfo
import androidx.work.workDataOf
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import me.thenano.yamibo.yamibo_app.util.time.currentTimeMillis
import me.thenano.yamibo.yamibo_app.util.time.FixedScheduleInterval
import me.thenano.yamibo.yamibo_app.repository.appsync.AppSyncService

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

    suspend fun continueRecovery(service: AppSyncService): Unit = withContext(Dispatchers.IO) {
        var evidence = service.prepareRecoveryWork(UUID.randomUUID().toString()) ?: return@withContext
        var known = workManager.getWorkInfoById(UUID.fromString(evidence.requestId)).get()
        if (known?.state?.isFinished == true) {
            service.retireRecoveryWork(evidence.requestId)
            evidence = service.prepareRecoveryWork(UUID.randomUUID().toString()) ?: return@withContext
            known = workManager.getWorkInfoById(UUID.fromString(evidence.requestId)).get()
        }
        if (known != null) {
            if (known.state == WorkInfo.State.RUNNING) service.beginRecoveryWork(evidence.requestId)
            else service.confirmRecoveryWorkEnqueued(evidence.requestId)
            return@withContext
        }
        val delay = (evidence.notBeforeEpochMillis - currentTimeMillis()).coerceAtLeast(0)
        val request = OneTimeWorkRequestBuilder<AppSyncWorker>()
            .setId(UUID.fromString(evidence.requestId))
            .setInputData(workDataOf(RECOVERY_REQUEST_ID to evidence.requestId))
            .setConstraints(constraints())
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(WORK_TAG)
            .addTag(MANUAL_WORK_TAG)
            .build()
        try {
            workManager.enqueueUniqueWork(MANUAL_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
                .result.get()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            // Another callback can enqueue the same persisted UUID while this call waits.
            // Only an actual nonterminal WorkManager row reconciles that ambiguity.
            val reconciled = workManager.getWorkInfoById(request.id).get()
            if (reconciled == null || reconciled.state.isFinished) throw error
        }
        val confirmed = workManager.getWorkInfoById(request.id).get()
        check(confirmed != null) { "Recovery work was not durably enqueued" }
        if (confirmed.state.isFinished) return@withContext
        if (confirmed.state == WorkInfo.State.RUNNING) service.beginRecoveryWork(evidence.requestId)
        else service.confirmRecoveryWorkEnqueued(evidence.requestId)
        return@withContext
    }

    override suspend fun reconcileRecoveryWork(service: AppSyncService): Unit = withContext(Dispatchers.IO) {
        val evidence = service.recoveryWorkRequest() ?: return@withContext
        val info = workManager.getWorkInfoById(UUID.fromString(evidence.requestId)).get()
        when {
            info == null -> if (evidence.enqueuedAtEpochMillis != null) service.retireRecoveryWork(evidence.requestId) else Unit
            info.state.isFinished -> service.retireRecoveryWork(evidence.requestId)
            info.state == WorkInfo.State.RUNNING -> service.beginRecoveryWork(evidence.requestId)
            else -> service.confirmRecoveryWorkEnqueued(evidence.requestId)
        }
        return@withContext
    }

    private fun constraints() = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true)
        .build()

    companion object {
        internal const val RECOVERY_REQUEST_ID = "appsync-recovery-request-id"
        private const val WORK_TAG = "yamibo-app-sync"
        private const val PERIODIC_WORK = "yamibo-app-sync-periodic"
        private const val LIFECYCLE_WORK = "yamibo-app-sync-lifecycle"
        private const val MANUAL_WORK = "yamibo-app-sync-manual-recovery"
        private const val MANUAL_WORK_TAG = "yamibo-app-sync-manual"
    }
}

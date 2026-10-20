package me.thenano.yamibo.yamibo_app.favorite.updates

import me.thenano.yamibo.yamibo_app.i18n.i18n

import android.content.Context
import android.os.SystemClock
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import me.thenano.yamibo.yamibo_app.Logger
import me.thenano.yamibo.yamibo_app.repository.FavoriteUpdateRepository

class FavoriteUpdateWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repository = AndroidFavoriteUpdateSupport.createRepository(applicationContext)
        val notifications = AndroidFavoriteUpdateNotificationRepository(applicationContext)
        // WorkRequest IDs survive retries and process death. Periodic scheduling delegates to
        // a fresh one-time request, so each scheduled run also gets its own durable identity.
        val requestedRunId = inputData.getString(KEY_RUN_ID)
        val runId = requestedRunId ?: "favorite-update-work-$id"
        return try {
            if (repository.getRunSnapshot(runId) == null) {
                if (requestedRunId != null) return Result.failure()
                repository.startRun(runId)
            }
            when (runFavoriteUpdateAttempt(
                repository = repository,
                runId = runId,
                nowMillis = SystemClock::elapsedRealtime,
                onProgress = notifications::showProgress,
            )) {
                FavoriteUpdateAttemptResult.COMPLETED -> {
                    repository.getRunSnapshot(runId)?.let(notifications::showCompleted)
                    Result.success()
                }
                FavoriteUpdateAttemptResult.RETRY -> {
                    notifications.showWaiting(runId)
                    Result.retry()
                }
                FavoriteUpdateAttemptResult.STOPPED -> {
                    val snapshot = repository.getRunSnapshot(runId)
                    if (snapshot?.status == FavoriteUpdateRepository.RunStatus.INTERRUPTED ||
                        snapshot?.status == FavoriteUpdateRepository.RunStatus.FAILED
                    ) {
                        notifications.showFailed(i18n("收藏更新中斷"), i18n("更新檢查已中斷，請開啟 App 繼續"))
                    } else {
                        notifications.dismiss()
                    }
                    Result.success()
                }
            }
        } catch (error: CancellationException) {
            // Constraints and OS stops are rescheduled by WorkManager. Do not poison their
            // checkpoint with INTERRUPTED; explicit user stops update the repository separately.
            notifications.dismiss()
            throw error
        } catch (error: Exception) {
            Logger.e("FavoriteUpdateWorker", "Favorite update failed runId=$runId", error)
            val message = i18n("更新檢查已中斷，請開啟 App 繼續")
            repository.markRunInterrupted(runId, message)
            notifications.showFailed(i18n("收藏更新中斷"), message)
            Result.failure()
        }
    }

    companion object {
        const val KEY_RUN_ID = "run_id"
    }
}

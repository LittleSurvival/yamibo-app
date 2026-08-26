package me.thenano.yamibo.yamibo_app.notification

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import me.thenano.yamibo.yamibo_app.Logger
import me.thenano.yamibo.yamibo_app.repository.notification.MessageNotificationChecker

internal class MessageNotificationWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        when (val checkResult = AndroidMessageNotificationRuntime.createChecker(applicationContext).check()) {
            is MessageNotificationChecker.Result.FetchFailed -> {
                if (shouldRetryMessageCheck(checkResult, runAttemptCount)) {
                    Result.retry()
                } else {
                    Result.success()
                }
            }
            else -> Result.success()
        }
    } catch (error: Throwable) {
        Logger.e(TAG, "Periodic message check failed", error)
        if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
    }

    companion object {
        const val TAG = "MessageNotificationWorker"
        const val MAX_RETRIES = 3
    }
}

internal fun shouldRetryMessageCheck(
    result: MessageNotificationChecker.Result.FetchFailed,
    runAttemptCount: Int,
): Boolean = result.retryable && runAttemptCount < MessageNotificationWorker.MAX_RETRIES

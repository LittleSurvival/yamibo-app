package me.thenano.yamibo.yamibo_app.notification.bridge

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.littlesurvival.YamiboRoute
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import me.thenano.yamibo.yamibo_app.BuildConfig
import me.thenano.yamibo.yamibo_app.notification.AndroidMessageNotificationRuntime
import me.thenano.yamibo.yamibo_app.repository.notification.MessageNotificationChecker
import me.thenano.yamibo.yamibo_app.repository.settings.AppSettingsRepository
import me.thenano.yamibo.yamibo_app.store.AndroidCookieStore
import me.thenano.yamibo.yamibo_app.store.AndroidUserStore
import me.thenano.yamibo.yamibo_app.store.settings.AndroidSettingsStore

/** Survives a cold FCM service process. Data is only an invalidation ID, never private message content. */
internal class NotificationSignalWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val eventId = inputData.getString(EVENT_ID) ?: return Result.success()
        val context = applicationContext
        if (!AppSettingsRepository(AndroidSettingsStore(context)).messageNotificationEnabled.getValue()) return Result.success()
        val uid = AndroidUserStore(context).load()?.uid?.value ?: return Result.success()
        val cookie = AndroidCookieStore(context).load()?.takeIf { it.isNotBlank() } ?: return Result.success()
        val origin = trustedNotificationOrigin(BuildConfig.NOTIFICATION_ORIGIN, YamiboRoute.Domain.build()) ?: return Result.success()
        val api = NotificationBridgeClient(origin)
        return try {
            val session = api.exchange(cookie, uid)
            try {
                when (val result = AndroidMessageNotificationRuntime.check(context, session.uid, eventId)) {
                    is MessageNotificationChecker.Result.FetchFailed -> if (result.retryable && runAttemptCount < 3) Result.retry() else Result.success()
                    else -> Result.success()
                }
            } finally {
                try { api.revoke(session) } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { }
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: NotificationApiException) {
            if (error.status in setOf(401, 403) || runAttemptCount >= 3) Result.success() else Result.retry()
        } catch (_: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        } finally {
            api.close()
        }
    }

    companion object {
        private const val EVENT_ID = "event_id"
        fun enqueue(context: Context, eventId: String) {
            val key = MessageDigest.getInstance("SHA-256").digest(eventId.toByteArray()).joinToString("") { "%02x".format(it) }
            val request = OneTimeWorkRequestBuilder<NotificationSignalWorker>()
                .setInputData(workDataOf(EVENT_ID to eventId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag("notification-bridge-signal")
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork("notification-signal-$key", ExistingWorkPolicy.KEEP, request)
        }
    }
}

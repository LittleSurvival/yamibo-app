package me.thenano.yamibo.yamibo_app.favorite.updates

import me.thenano.yamibo.yamibo_app.i18n.i18n

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import me.thenano.yamibo.yamibo_app.Logger
import me.thenano.yamibo.yamibo_app.repository.FavoriteUpdateRepository

class FavoriteUpdateWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repository = AndroidFavoriteUpdateSupport.createRepository(applicationContext)
        val notifications = AndroidFavoriteUpdateNotificationRepository(applicationContext)
        val runId = inputData.getString(KEY_RUN_ID) ?: repository.startRun()
        return supervisorScope {
            val notificationJob = launch {
                var lastForegroundUpdateAt = 0L
                repository.state.collect { state ->
                    val snapshot = state.snapshotOrNull() ?: return@collect
                    if (snapshot.runId != runId) return@collect
                    if (state is FavoriteUpdateRepository.RunState.Running) {
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastForegroundUpdateAt < FOREGROUND_UPDATE_INTERVAL_MS) {
                            return@collect
                        }
                        lastForegroundUpdateAt = now
                        setForeground(createForegroundInfo(notifications, snapshot))
                    }
                }
            }
            try {
                repository.getRunSnapshot(runId)?.let {
                    setForeground(createForegroundInfo(notifications, it))
                }
                repository.runUpdate(runId)
                repository.getRunSnapshot(runId)?.let { snapshot ->
                    if (snapshot.status == FavoriteUpdateRepository.RunStatus.COMPLETED) {
                        @Suppress("MissingPermission")
                        notifications.showCompleted(snapshot)
                    }
                }
                Result.success()
            } catch (error: CancellationException) {
                repository.markRunInterrupted(runId, i18n("更新檢查已中斷"))
                throw error
            } catch (throwable: Throwable) {
                Logger.e("FavoriteUpdateWorker", "Favorite update failed runId=$runId", throwable)
                repository.markRunInterrupted(runId, throwable.message ?: i18n("更新檢查被系統中斷"))
                @Suppress("MissingPermission")
                notifications.showFailed(i18n("收藏更新中斷"), throwable.message ?: i18n("更新檢查被系統中斷"))
                Result.retry()
            } finally {
                notificationJob.cancel()
            }
        }
    }

    private fun createForegroundInfo(
        notifications: AndroidFavoriteUpdateNotificationRepository,
        snapshot: FavoriteUpdateRepository.RunSnapshot,
    ): ForegroundInfo {
        val notification = notifications.buildProgressNotification(snapshot)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                AndroidFavoriteUpdateNotificationRepository.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(AndroidFavoriteUpdateNotificationRepository.NOTIFICATION_ID, notification)
        }
    }

    private fun FavoriteUpdateRepository.RunState.snapshotOrNull(): FavoriteUpdateRepository.RunSnapshot? =
        when (this) {
            FavoriteUpdateRepository.RunState.Idle -> null
            is FavoriteUpdateRepository.RunState.Running -> snapshot
            is FavoriteUpdateRepository.RunState.Interrupted -> snapshot
            is FavoriteUpdateRepository.RunState.Failed -> snapshot
            is FavoriteUpdateRepository.RunState.Completed -> snapshot
        }

    companion object {
        const val KEY_RUN_ID = "run_id"
        private const val FOREGROUND_UPDATE_INTERVAL_MS = 2_000L
    }
}

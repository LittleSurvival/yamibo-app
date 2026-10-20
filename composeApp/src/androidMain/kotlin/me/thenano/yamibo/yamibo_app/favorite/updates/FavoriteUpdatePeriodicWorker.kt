package me.thenano.yamibo.yamibo_app.favorite.updates

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/** Each periodic tick gets a new one-time request whose ID stays stable across chunk retries. */
class FavoriteUpdatePeriodicWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        AndroidFavoriteUpdateScheduler(applicationContext).enqueueAutomaticUpdate()
        return Result.success()
    }
}

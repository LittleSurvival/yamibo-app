package me.thenano.yamibo.yamibo_app.favorite.updates

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.await
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.thenano.yamibo.yamibo_app.Database
import me.thenano.yamibo.yamibo_app.db.DatabaseFactory

/**
 * Deterministic emulator regression fixture. Requires a separate .favoriteqa application ID,
 * so the fixture cannot alter a normal debug/release installation or contact the forum.
 */
class FavoriteUpdateQaReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!context.packageName.endsWith(".favoriteqa") ||
            !(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone"))
        ) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repository = AndroidFavoriteUpdateSupport.createRepository(context)
                val scheduler = AndroidFavoriteUpdateScheduler(context)
                when (intent.getStringExtra("command")) {
                    "seed" -> {
                        val driver = DatabaseFactory(context).createDriver()
                        try {
                            val db = Database(driver)
                            check(db.localFavoriteItemQueries.getAll().executeAsList().isEmpty()) {
                                "Fixture requires an empty dedicated QA installation"
                            }
                            val count = intent.getIntExtra("count", 32).coerceIn(1, 2_100)
                            db.transaction {
                                repeat(count) { index ->
                                    // Missing local RSS subscriptions fail immediately without network.
                                    db.localFavoriteItemQueries.insertFavoriteItem(
                                        "RssSearch", 9_000_000L + index, "QA RSS $index",
                                        null, null, null, null, 0, index.toLong(), index.toLong(),
                                    )
                                }
                            }
                            Log.i(TAG, "seeded count=$count")
                        } finally {
                            driver.close()
                        }
                    }
                    "manual" -> scheduler.startFavoriteUpdate(repository.startRun())
                    "automatic" -> scheduler.enqueueAutomaticUpdate()
                    "periodic" -> {
                        val request = OneTimeWorkRequestBuilder<FavoriteUpdatePeriodicWorker>()
                            .setInitialDelay(30, TimeUnit.SECONDS)
                            .build()
                        WorkManager.getInstance(context).enqueue(request).await()
                        Log.i(TAG, "periodic-trigger=${request.id}")
                    }
                    "cancel" -> repository.getLatestSnapshot()?.let {
                        FavoriteUpdateCancelReceiver.createPendingIntent(context, it.runId).send()
                    }
                    "resume" -> repository.resumeInterruptedRun()?.let { scheduler.startFavoriteUpdate(it) }
                    "status" -> {
                        val snapshot = repository.getLatestSnapshot()
                        val work = WorkManager.getInstance(context)
                            .getWorkInfosForUniqueWork(AndroidFavoriteUpdateScheduler.UNIQUE_MANUAL_WORK).get()
                        Log.i(TAG, "snapshot=${snapshot?.copy(warningMessage = null)}")
                        work.forEach {
                            Log.i(TAG, "work=${it.id} state=${it.state} attempts=${it.runAttemptCount}")
                        }
                    }
                }
                Log.i(TAG, "command=${intent.getStringExtra("command")} done")
            } catch (error: Exception) {
                Log.e(TAG, "fixture failed", error)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "FavoriteUpdateQA"
    }
}

package me.thenano.yamibo.yamibo_app.favorite.sync

import me.thenano.yamibo.yamibo_app.i18n.i18n

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import me.thenano.yamibo.yamibo_app.Logger
import me.thenano.yamibo.yamibo_app.repository.FavoriteSyncRepository
import me.thenano.yamibo.yamibo_app.repository.SystemNotificationRepository
import kotlin.math.absoluteValue

class FavoriteSyncForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val activeRuns = linkedMapOf<String, ActiveRun>()
    private lateinit var favoriteSyncRepository: FavoriteSyncRepository
    private lateinit var notificationRepository: AndroidSystemNotificationRepository

    override fun onCreate() {
        super.onCreate()
        favoriteSyncRepository = AndroidFavoriteSyncSupport.createRepository(this)
        notificationRepository = AndroidSystemNotificationRepository(this)
    }

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val runId = intent?.getStringExtra(EXTRA_RUN_ID)
        when (intent?.action) {
            ACTION_START -> if (runId != null) startRun(runId)
            ACTION_CANCEL -> if (runId != null) cancelRun(runId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        runningRunIdsState.value = emptySet()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    private fun startRun(runId: String) {
        if (activeRuns.containsKey(runId)) return

        val notificationId = notificationIdFor(runId)
        val initialModel = SystemNotificationRepository.ProgressNotificationModel(
            notificationId = notificationId,
            title = i18n("同步百合會收藏"),
            text = i18n("準備同步任務"),
            progress = 3,
            indeterminate = false,
            ongoing = true,
            canCancel = true,
            runId = runId,
        )
        try {
            startForeground(notificationId, notificationRepository.buildProgressNotification(initialModel))
        } catch (throwable: Throwable) {
            Logger.e(TAG, "Failed to enter foreground for favorite sync runId=$runId", throwable)
            scope.launch {
                favoriteSyncRepository.markRunInterrupted(runId, i18n("系統目前不允許啟動背景同步，請在 App 前景時重試。"))
            }
            stopSelf()
            return
        }
        runningRunIdsState.value += runId

        val stateJob = scope.launch(start = CoroutineStart.LAZY) {
            favoriteSyncRepository.observeRun(runId).collectLatest { state ->
                val snapshot = state.snapshotOrNull() ?: return@collectLatest
                notificationRepository.showProgress(snapshot.toNotificationModel(state))
                when (state) {
                    is FavoriteSyncRepository.FavoriteSyncState.Completed -> {
                        notificationRepository.showCompleted(notificationId, i18n("同步完成"), i18n("百合會收藏已同步到本地"))
                        finishRun(runId, keepNotification = true)
                    }
                    is FavoriteSyncRepository.FavoriteSyncState.Failed -> {
                        notificationRepository.showFailed(notificationId, i18n("同步失敗"), snapshot.errorMessage ?: i18n("同步過程發生錯誤"))
                        finishRun(runId, keepNotification = true)
                    }
                    is FavoriteSyncRepository.FavoriteSyncState.Interrupted -> {
                        notificationRepository.showFailed(notificationId, i18n("同步已中斷"), snapshot.errorMessage ?: i18n("同步已取消"))
                        finishRun(runId, keepNotification = true)
                    }
                    else -> Unit
                }
            }
        }
        val workJob = scope.launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
            favoriteSyncRepository.runImport(runId)
        }
        activeRuns[runId] = ActiveRun(notificationId, stateJob, workJob)
        stateJob.start()
        workJob.start()
    }

    private fun cancelRun(runId: String) {
        val activeRun = activeRuns[runId] ?: run {
            if (activeRuns.isEmpty()) stopSelf()
            return
        }
        scope.launch {
            favoriteSyncRepository.interruptRun(runId)
            activeRun.workJob.cancelAndJoin()
            if (activeRuns.containsKey(runId)) {
                finishRun(runId, keepNotification = true)
            }
        }
    }

    private fun finishRun(runId: String, keepNotification: Boolean) {
        val activeRun = activeRuns.remove(runId) ?: return
        activeRun.stateJob.cancel()
        activeRun.workJob.cancel()
        runningRunIdsState.value -= runId

        if (activeRuns.isEmpty()) {
            if (keepNotification) {
                stopForeground(STOP_FOREGROUND_DETACH)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
        }
    }

    private data class ActiveRun(
        val notificationId: Int,
        val stateJob: Job,
        val workJob: Job,
    )

    companion object {
        private const val TAG = "FavoriteSyncService"
        private const val ACTION_START = "me.thenano.yamibo.yamibo_app.favorite.sync.START"
        private const val ACTION_CANCEL = "me.thenano.yamibo.yamibo_app.favorite.sync.CANCEL"
        private const val EXTRA_RUN_ID = "run_id"
        private val runningRunIdsState = MutableStateFlow<Set<String>>(emptySet())
        val runningFavoriteSyncRunIds: StateFlow<Set<String>> = runningRunIdsState.asStateFlow()

        fun createStartIntent(context: Context, runId: String): Intent {
            return Intent(context, FavoriteSyncForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RUN_ID, runId)
            }
        }

        fun createCancelIntent(context: Context, runId: String): Intent {
            return Intent(context, FavoriteSyncForegroundService::class.java).apply {
                action = ACTION_CANCEL
                putExtra(EXTRA_RUN_ID, runId)
            }
        }

        fun notificationIdFor(runId: String): Int = 10_000 + runId.hashCode().absoluteValue
    }
}

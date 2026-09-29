package me.thenano.yamibo.yamibo_app.desktop

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import me.thenano.yamibo.yamibo_app.appsync.AppSyncBackgroundScheduler
import me.thenano.yamibo.yamibo_app.appsync.shouldNotifyBackgroundQuarantine
import me.thenano.yamibo.yamibo_app.favorite.updates.FavoriteUpdateScheduler
import me.thenano.yamibo.yamibo_app.profile.settings.backup.BackupScheduler
import me.thenano.yamibo.yamibo_app.profile.settings.sign.SignReminderScheduler
import me.thenano.yamibo.yamibo_app.repository.*
import me.thenano.yamibo.yamibo_app.repository.appsync.AppSyncService
import me.thenano.yamibo.yamibo_app.repository.appsync.isDurableAutomaticTriggerOutcome
import me.thenano.yamibo.yamibo_app.repository.settings.*
import me.thenano.yamibo.yamibo_app.store.settings.SettingsStore
import me.thenano.yamibo.yamibo_app.util.time.FixedScheduleInterval
import me.thenano.yamibo.yamibo_app.util.time.currentTimeMillis

internal class DesktopBackgroundServices(
    scope: CoroutineScope,
    rawSettings: SettingsStore,
    private val settings: AppSettingsRepository,
    private val auth: AuthRepository,
    private val sync: FavoriteSyncRepository,
    private val updates: FavoriteUpdateRepository,
    private val backup: BackupRepository,
    private val sign: SignRepository,
    private val appSync: AppSyncService,
    private val notify: (String, String) -> Unit,
) {
    private val updateMutex = Mutex()
    private val backupMutex = Mutex()
    val jobs = DesktopJobs(scope, rawSettings) { notify("背景工作未完成", "請開啟程式檢查網路、登入及儲存狀態：$it") }
    val favoriteSync = object : BackgroundTaskRepository {
        override val runningFavoriteSyncRunIds = jobs.running.map { keys ->
            keys.filter { it.startsWith("favorite-sync:") }.map { it.removePrefix("favorite-sync:") }.toSet()
        }.stateIn(scope, SharingStarted.Eagerly, emptySet())
        override suspend fun startFavoriteSync(runId: String): BackgroundTaskRepository.StartResult {
            jobs.start("favorite-sync:$runId") { sync.runImport(runId) }
            return BackgroundTaskRepository.StartResult.Started
        }
        override suspend fun cancelFavoriteSync(runId: String) {
            jobs.cancel("favorite-sync:$runId")
            sync.interruptRun(runId)
        }
    }
    private suspend fun update(runId: String) = updateMutex.withLock {
        if (updates.getRunSnapshot(runId)?.status != FavoriteUpdateRepository.RunStatus.RUNNING) return@withLock
        try {
            updates.runUpdate(runId)
            when (updates.getRunSnapshot(runId)?.status) {
                FavoriteUpdateRepository.RunStatus.COMPLETED -> notify("收藏更新", "收藏更新檢查已完成")
                FavoriteUpdateRepository.RunStatus.INTERRUPTED -> notify("收藏更新未完成", "請開啟收藏更新頁檢查登入與網路狀態")
                else -> Unit
            }
        }
        catch (cancelled: CancellationException) {
            withContext(NonCancellable) { updates.markRunInterrupted(runId, "更新檢查已中斷") }
            throw cancelled
        }
        catch (error: Exception) { updates.markRunInterrupted(runId, "更新檢查未完成"); throw error }
    }
    val favoriteUpdates = object : FavoriteUpdateScheduler {
        override suspend fun startFavoriteUpdate(runId: String): FavoriteUpdateScheduler.StartResult {
            jobs.start("favorite-update:$runId") { update(runId) }
            return FavoriteUpdateScheduler.StartResult.Started
        }
        override suspend fun cancelFavoriteUpdate(runId: String) {
            jobs.cancel("favorite-update:$runId")
            updates.interruptRun(runId)
        }
        override suspend fun schedulePeriodicFavoriteUpdate(interval: FavoriteUpdateInterval) {
            jobs.schedule("favorite-update-periodic", interval.fixedInterval?.duration?.inWholeMilliseconds) {
                if (auth.isLoggedIn()) {
                    update(updates.startRun())
                }
            }
        }
    }
    private suspend fun createBackup() = backupMutex.withLock {
        val file = backup.createBackup(automatic = true).getOrThrow()
        backup.cleanupAutoBackups(settings.backupMaxAutoFiles.getValue()).getOrThrow()
        settings.backupLastAutoBackupAt.setValue(currentTimeMillis().toString())
        notify("備份完成", file.name)
    }
    val backups = object : BackupScheduler {
        override suspend fun schedule(interval: BackupInterval) {
            jobs.schedule("backup-periodic", interval.fixedInterval?.duration?.inWholeMilliseconds) { createBackup() }
        }
        override suspend fun runNow() { jobs.start("backup-manual") { createBackup() } }
        override suspend fun cancel() { jobs.cancel("backup-periodic"); jobs.cancel("backup-manual") }
    }
    val signReminders = object : SignReminderScheduler {
        override suspend fun schedule(frequency: SignReminderFrequency) {
            val millis = frequency.timesPerDay.takeIf { it > 0 }?.let { 86_400_000L / it }
            jobs.schedule("sign-reminder", millis) {
                if (auth.isLoggedIn() && sign.getKnownSignedToday() != true) notify("百合會每日簽到", "今天尚未完成簽到，請開啟程式完成簽到")
            }
        }
        override suspend fun runNow() { notify("百合會每日簽到", "簽到提醒測試") }
        override suspend fun cancel() { jobs.cancel("sign-reminder") }
        // Tray notifications are transient and expire under OS control; there is no retained reminder.
        override suspend fun dismissActiveReminder() = Unit
    }
    private suspend fun synchronize() {
        val pending = appSync.pendingAutomaticTriggerGeneration()
        val previous = appSync.currentStatus().phase
        val phase = appSync.synchronizeNow(trigger = "desktop_background").phase
        if (shouldNotifyBackgroundQuarantine(previous, phase)) notify("同步需要處理", "請開啟同步設定檢查狀態")
        if (pending != null && phase.isDurableAutomaticTriggerOutcome()) appSync.accountAutomaticTrigger(pending)
    }
    val appSyncScheduler = object : AppSyncBackgroundScheduler {
        override fun setEnabled(enabled: Boolean, interval: FixedScheduleInterval) {
            jobs.schedule("app-sync-periodic", interval.duration.inWholeMilliseconds.takeIf { enabled }) { synchronize() }
        }
        override fun runNow() { jobs.start("app-sync-trigger") { synchronize() } }
    }
}

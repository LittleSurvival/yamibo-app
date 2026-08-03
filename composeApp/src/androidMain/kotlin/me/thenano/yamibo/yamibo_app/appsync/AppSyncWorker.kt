package me.thenano.yamibo.yamibo_app.appsync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.littlesurvival.YamiboClient
import me.thenano.yamibo.yamibo_app.Database
import me.thenano.yamibo.yamibo_app.Logger
import me.thenano.yamibo.yamibo_app.db.DatabaseFactory
import me.thenano.yamibo.yamibo_app.repository.AndroidAuthRepository
import me.thenano.yamibo.yamibo_app.repository.appsync.AppSyncService
import me.thenano.yamibo.yamibo_app.repository.appsync.AppSyncServicePhase
import me.thenano.yamibo.yamibo_app.repository.appsync.isDurableAutomaticTriggerOutcome
import me.thenano.yamibo.yamibo_app.repository.backup.BackupRepositoryImpl
import me.thenano.yamibo.yamibo_app.repository.settings.AppSettingsRepository
import me.thenano.yamibo.yamibo_app.repository.settings.MangaReaderSettingsRepository
import me.thenano.yamibo.yamibo_app.repository.settings.NovelReaderSettingsRepository
import me.thenano.yamibo.yamibo_app.repository.AndroidBackupStorageProvider
import me.thenano.yamibo.yamibo_app.AppVersion
import me.thenano.yamibo.yamibo_app.store.AndroidCookieStore
import me.thenano.yamibo.yamibo_app.store.AndroidUserStore
import me.thenano.yamibo.yamibo_app.store.settings.AndroidSettingsStore

class AppSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        if (runAttemptCount >= MAX_RETRY_ATTEMPTS) return Result.failure()
        val client = YamiboClient(timeoutMillis = 60_000L)
        val auth = AndroidAuthRepository(
            AndroidCookieStore(applicationContext),
            AndroidUserStore(applicationContext),
            client,
        )
        val db = Database(DatabaseFactory(applicationContext).createDriver())
        val rawSettings = AndroidSettingsStore(applicationContext)
        val service = AppSyncService(
            db = db,
            settingsStore = rawSettings,
            authRepository = auth,
        )
        val settings = service.operationRecordingSettingsStore(db, rawSettings)
        val appSettings = AppSettingsRepository(settings)
        val novelSettings = NovelReaderSettingsRepository(settings)
        val mangaSettings = MangaReaderSettingsRepository(settings)
        service.registerSyncableSettings(listOf(appSettings, novelSettings, mangaSettings))
        service.registerLocalSnapshotSource(
            BackupRepositoryImpl(
                db = db,
                settingsStore = settings,
                settingsRegistries = listOf(appSettings, novelSettings, mangaSettings),
                storageProvider = AndroidBackupStorageProvider(applicationContext, appSettings),
                appVersionCode = AppVersion.VersionCode.toInt(),
            ),
        )
        val pendingGeneration = service.pendingAutomaticTriggerGeneration()
        val previousPhase = service.currentStatus().phase
        val phase = service.synchronizeNow(trigger = "background_workmanager").phase
        if (shouldNotifyBackgroundQuarantine(previousPhase, phase)) {
            AndroidAppSyncNotificationRepository(applicationContext).showQuarantined()
        }
        if (pendingGeneration != null && phase.isDurableAutomaticTriggerOutcome()) {
            service.accountAutomaticTrigger(pendingGeneration)
        }
        when (phase) {
            AppSyncServicePhase.Active -> {
                if (service.pendingAutomaticTriggerGeneration() != null) Result.retry()
                else Result.success()
            }
            AppSyncServicePhase.PausedAuth,
            AppSyncServicePhase.Quarantined,
            AppSyncServicePhase.Disabled,
            -> Result.failure()
            AppSyncServicePhase.BootstrapRequired,
            AppSyncServicePhase.Running,
            AppSyncServicePhase.PausedProvider,
            AppSyncServicePhase.RetryPending,
            -> Result.retry()
        }
    } catch (error: Throwable) {
        Logger.e("AppSyncWorker", "Background AppSync failed", error)
        Result.retry()
    }

    private companion object {
        const val MAX_RETRY_ATTEMPTS = 5
    }
}

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package me.thenano.yamibo.yamibo_app.appsync

import io.github.littlesurvival.YamiboClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import me.thenano.yamibo.yamibo_app.AppVersion
import me.thenano.yamibo.yamibo_app.Database
import me.thenano.yamibo.yamibo_app.db.DatabaseFactory
import me.thenano.yamibo.yamibo_app.repository.IOSAuthRepository
import me.thenano.yamibo.yamibo_app.repository.IOSBackupStorageProvider
import me.thenano.yamibo.yamibo_app.repository.appsync.AppSyncService
import me.thenano.yamibo.yamibo_app.repository.appsync.AppSyncServicePhase
import me.thenano.yamibo.yamibo_app.repository.backup.BackupRepositoryImpl
import me.thenano.yamibo.yamibo_app.repository.settings.AppSettingsRepository
import me.thenano.yamibo.yamibo_app.repository.settings.MangaReaderSettingsRepository
import me.thenano.yamibo.yamibo_app.repository.settings.NovelReaderSettingsRepository
import me.thenano.yamibo.yamibo_app.store.IOSCookieStore
import me.thenano.yamibo.yamibo_app.store.IOSForumFavoriteStore
import me.thenano.yamibo.yamibo_app.store.IOSUserStore
import me.thenano.yamibo.yamibo_app.store.settings.IOSSettingsStore
import platform.BackgroundTasks.BGProcessingTaskRequest
import platform.BackgroundTasks.BGTaskScheduler
import platform.Foundation.NSDate

const val APP_SYNC_BACKGROUND_TASK_IDENTIFIER =
    "me.thenano.yamibo.yamibo-app.app-sync"

class IOSAppSyncBackgroundScheduler : AppSyncBackgroundScheduler {
    override suspend fun setEnabled(enabled: Boolean) {
        if (!enabled) {
            BGTaskScheduler.sharedScheduler.cancelTaskRequestWithIdentifier(
                APP_SYNC_BACKGROUND_TASK_IDENTIFIER,
            )
            return
        }
        BGTaskScheduler.sharedScheduler.cancelTaskRequestWithIdentifier(
            APP_SYNC_BACKGROUND_TASK_IDENTIFIER,
        )
        submit(earliestBeginSeconds = PERIODIC_EARLIEST_BEGIN_SECONDS)
    }

    override suspend fun runNow() {
        BGTaskScheduler.sharedScheduler.cancelTaskRequestWithIdentifier(
            APP_SYNC_BACKGROUND_TASK_IDENTIFIER,
        )
        submit(earliestBeginSeconds = MANUAL_EARLIEST_BEGIN_SECONDS)
    }

    private fun submit(earliestBeginSeconds: Double) {
        val request = BGProcessingTaskRequest(APP_SYNC_BACKGROUND_TASK_IDENTIFIER).apply {
            requiresNetworkConnectivity = true
            requiresExternalPower = false
            earliestBeginDate = NSDate(
                timeIntervalSinceReferenceDate =
                    NSDate().timeIntervalSinceReferenceDate + earliestBeginSeconds,
            )
        }
        BGTaskScheduler.sharedScheduler.submitTaskRequest(request, null)
    }

    private companion object {
        const val MANUAL_EARLIEST_BEGIN_SECONDS = 1.0
        const val PERIODIC_EARLIEST_BEGIN_SECONDS = 6.0 * 60.0 * 60.0
    }
}

fun runAppSyncBackground(completion: (Boolean) -> Unit) {
    CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
        val outcome = runCatching { runAppSyncOnce() }.getOrNull()
        if (outcome?.automaticEnabled == true) {
            IOSAppSyncBackgroundScheduler().setEnabled(true)
        }
        completion(outcome?.success == true)
    }
}

private data class IOSAppSyncRunOutcome(
    val success: Boolean,
    val automaticEnabled: Boolean,
)

private suspend fun runAppSyncOnce(): IOSAppSyncRunOutcome {
    val client = YamiboClient(timeoutMillis = 60_000L)
    val rawSettings = IOSSettingsStore()
    val auth = IOSAuthRepository(
        IOSCookieStore(),
        IOSUserStore(),
        client,
        IOSForumFavoriteStore(),
    )
    val db = Database(DatabaseFactory().createDriver())
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
            storageProvider = IOSBackupStorageProvider(appSettings),
            appVersionCode = AppVersion.VersionCode.toInt(),
        ),
    )
    val status = service.synchronizeNow(trigger = "background_bgtask")
    return IOSAppSyncRunOutcome(
        success = status.phase == AppSyncServicePhase.Active,
        automaticEnabled = status.automaticEnabled,
    )
}

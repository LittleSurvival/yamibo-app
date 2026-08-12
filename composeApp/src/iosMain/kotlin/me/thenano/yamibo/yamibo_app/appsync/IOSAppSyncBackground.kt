@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package me.thenano.yamibo.yamibo_app.appsync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import me.thenano.yamibo.yamibo_app.AppVersion
import me.thenano.yamibo.yamibo_app.Database
import me.thenano.yamibo.yamibo_app.db.DatabaseFactory
import me.thenano.yamibo.yamibo_app.repository.IOSAuthRepository
import me.thenano.yamibo.yamibo_app.network.IOSYamiboClientProvider
import me.thenano.yamibo.yamibo_app.repository.IOSBackupStorageProvider
import me.thenano.yamibo.yamibo_app.repository.appsync.AppSyncService
import me.thenano.yamibo.yamibo_app.repository.appsync.AppSyncServicePhase
import me.thenano.yamibo.yamibo_app.repository.appsync.isDurableAutomaticTriggerOutcome
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
import me.thenano.yamibo.yamibo_app.util.time.FixedScheduleInterval
import me.thenano.yamibo.yamibo_app.i18n.i18n
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusProvisional
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNUserNotificationCenter

const val APP_SYNC_BACKGROUND_TASK_IDENTIFIER =
    "me.thenano.yamibo.yamibo-app.app-sync"

class IOSAppSyncBackgroundScheduler : AppSyncBackgroundScheduler {
    override fun setEnabled(enabled: Boolean, interval: FixedScheduleInterval) {
        if (!enabled) {
            BGTaskScheduler.sharedScheduler.cancelTaskRequestWithIdentifier(
                APP_SYNC_BACKGROUND_TASK_IDENTIFIER,
            )
            return
        }
        BGTaskScheduler.sharedScheduler.cancelTaskRequestWithIdentifier(
            APP_SYNC_BACKGROUND_TASK_IDENTIFIER,
        )
        submit(earliestBeginSeconds = interval.duration.inWholeSeconds.toDouble())
    }

    override fun runNow() {
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
    }
}

private var activeBackgroundJob: Job? = null

fun runAppSyncBackground(completion: (Boolean) -> Unit) {
    activeBackgroundJob?.cancel()
    activeBackgroundJob = CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
        val outcome = runCatching { runAppSyncOnce() }.getOrNull()
        if (outcome?.automaticEnabled == true) {
            IOSAppSyncBackgroundScheduler().setEnabled(true, outcome.periodicInterval)
        }
        completion(outcome?.success == true)
        activeBackgroundJob = null
    }
}

fun cancelAppSyncBackground() {
    activeBackgroundJob?.cancel()
    activeBackgroundJob = null
}

private data class IOSAppSyncRunOutcome(
    val success: Boolean,
    val automaticEnabled: Boolean,
    val periodicInterval: FixedScheduleInterval,
)

private suspend fun runAppSyncOnce(): IOSAppSyncRunOutcome {
    val cookieStore = IOSCookieStore()
    val client = IOSYamiboClientProvider.getForBackground(cookieStore)
    val rawSettings = IOSSettingsStore()
    val auth = IOSAuthRepository(
        cookieStore,
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
    service.currentStatus().let { initial ->
        if (initial.automaticEnabled) {
            IOSAppSyncBackgroundScheduler().setEnabled(
                enabled = true,
                interval = initial.scheduleSettings.periodicInterval,
            )
        }
    }
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
    val pendingGeneration = service.pendingAutomaticTriggerGeneration()
    val previousPhase = service.currentStatus().phase
    val status = service.synchronizeNow(trigger = "background_bgtask")
    if (shouldNotifyBackgroundQuarantine(previousPhase, status.phase)) {
        showIOSAppSyncQuarantineNotification()
    }
    if (pendingGeneration != null && status.phase.isDurableAutomaticTriggerOutcome()) {
        service.accountAutomaticTrigger(pendingGeneration)
    }
    return IOSAppSyncRunOutcome(
        success = status.phase == AppSyncServicePhase.Active &&
            service.pendingAutomaticTriggerGeneration() == null,
        automaticEnabled = status.automaticEnabled,
        periodicInterval = status.scheduleSettings.periodicInterval,
    )
}

private fun showIOSAppSyncQuarantineNotification() {
    val center = UNUserNotificationCenter.currentNotificationCenter()
    center.getNotificationSettingsWithCompletionHandler { settings ->
        if (
            settings?.authorizationStatus != UNAuthorizationStatusAuthorized &&
            settings?.authorizationStatus != UNAuthorizationStatusProvisional
        ) {
            return@getNotificationSettingsWithCompletionHandler
        }
        val content = UNMutableNotificationContent().apply {
            setTitle(i18n("雲端同步需要檢查"))
            setBody(i18n("背景同步已停止，請開啟雲端同步頁面檢查資料"))
        }
        center.addNotificationRequest(
            UNNotificationRequest.requestWithIdentifier(
                identifier = APP_SYNC_QUARANTINE_NOTIFICATION_IDENTIFIER,
                content = content,
                trigger = null,
            ),
            withCompletionHandler = null,
        )
    }
}

private const val APP_SYNC_QUARANTINE_NOTIFICATION_IDENTIFIER =
    "me.thenano.yamibo.yamibo-app.app-sync.quarantined"

private var lifecycleController: AppSyncLifecycleController? = null
private var sceneActive = false
private var sceneGeneration = 0L
private var deliveredSceneGeneration = 0L

fun attachIOSAppSyncLifecycle(controller: AppSyncLifecycleController) {
    lifecycleController = controller
    controller.reconcileRegistration()
    deliverIOSStartupIfNeeded()
}

fun detachIOSAppSyncLifecycle(controller: AppSyncLifecycleController) {
    if (lifecycleController === controller) lifecycleController = null
}

fun appSyncSceneDidBecomeActive() {
    if (!sceneActive) {
        sceneActive = true
        sceneGeneration += 1
    }
    deliverIOSStartupIfNeeded()
}

fun appSyncSceneDidEnterBackground(completion: (Boolean) -> Unit) {
    if (!sceneActive) {
        completion(false)
        return
    }
    sceneActive = false
    if (lifecycleController?.onForegroundSessionExited() == true) {
        runAppSyncBackground(completion)
    } else {
        completion(false)
    }
}

private fun deliverIOSStartupIfNeeded() {
    val current = lifecycleController ?: return
    if (!sceneActive || deliveredSceneGeneration == sceneGeneration) return
    deliveredSceneGeneration = sceneGeneration
    current.onForegroundSessionStarted()
}

@file:Suppress("FunctionName", "unused")

package me.thenano.yamibo.yamibo_app

import me.thenano.yamibo.yamibo_app.profile.settings.access.DesktopBackgroundAccess

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import io.github.littlesurvival.YamiboClient
import io.github.littlesurvival.waf.YamiboWafChallengeHost
import me.thenano.yamibo.yamibo_app.desktop.*
import me.thenano.yamibo.yamibo_app.store.*
import me.thenano.yamibo.yamibo_app.store.settings.DesktopSettingsStore
import me.thenano.yamibo.yamibo_app.repository.backup.DesktopBackupStorageProvider
import me.thenano.yamibo.yamibo_app.repository.font.DesktopFontPlatform
import me.thenano.yamibo.yamibo_app.repository.download.DesktopDownloadStorageProvider
import me.thenano.yamibo.yamibo_app.repository.chapterstate.ChapterStateRepositoryImpl
import me.thenano.yamibo.yamibo_app.repository.sign.SignRepositoryImpl
import me.thenano.yamibo.yamibo_app.store.sign.DatabaseSignStatusStore
import me.thenano.yamibo.yamibo_app.repository.notification.*
import me.thenano.yamibo.yamibo_app.webview.DesktopWafBrowser
import io.github.littlesurvival.core.YamiboResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import me.thenano.yamibo.yamibo_app.db.DatabaseFactory
import me.thenano.yamibo.yamibo_app.favorite.sync.FavoriteSyncRunner
import me.thenano.yamibo.yamibo_app.favorite.updates.FavoriteUpdateRunner
import me.thenano.yamibo.yamibo_app.feedback.AppFeedbackController
import me.thenano.yamibo.yamibo_app.i18n.i18n
import me.thenano.yamibo.yamibo_app.navigation.LocalNavigator
import me.thenano.yamibo.yamibo_app.navigation.rememberRestorableNavigator
import me.thenano.yamibo.yamibo_app.repository.*
import me.thenano.yamibo.yamibo_app.repository.backup.BackupRepositoryImpl
import me.thenano.yamibo.yamibo_app.repository.appsync.AppSyncService
import me.thenano.yamibo.yamibo_app.repository.chineseconversion.createChineseConversionRepository
import me.thenano.yamibo.yamibo_app.repository.download.DownloadImageFetcher
import me.thenano.yamibo.yamibo_app.repository.download.DownloadRepositoryImpl
import me.thenano.yamibo.yamibo_app.repository.download.DesktopDownloadRepository
import me.thenano.yamibo.yamibo_app.repository.favorite.FavoriteShareRepositoryImpl
import me.thenano.yamibo.yamibo_app.repository.favorite.FavoriteSyncRepositoryImpl
import me.thenano.yamibo.yamibo_app.repository.contentcover.ContentCoverRepositoryImpl
import me.thenano.yamibo.yamibo_app.repository.font.DefaultFontRepository
import me.thenano.yamibo.yamibo_app.repository.appupdate.DefaultAppUpdateRepository
import me.thenano.yamibo.yamibo_app.repository.inapplinknavigation.DefaultInAppLinkNavigationRepository
import me.thenano.yamibo.yamibo_app.repository.settings.AppSettingsRepository
import me.thenano.yamibo.yamibo_app.repository.settings.MangaReaderSettingsRepository
import me.thenano.yamibo.yamibo_app.repository.settings.NovelReaderSettingsRepository
import me.thenano.yamibo.yamibo_app.repository.settings.SettingsImageReaderModeOverrideRepository
import me.thenano.yamibo.yamibo_app.repository.userspace.BlogRepositoryImpl
import me.thenano.yamibo.yamibo_app.repository.userspace.UserSpaceRepositoryImpl
import me.thenano.yamibo.yamibo_app.task.AppTaskManager
import me.thenano.yamibo.yamibo_app.util.state
import me.thenano.yamibo.yamibo_app.confirmation.AppConfirmationController
import me.thenano.yamibo.yamibo_app.appsync.AppSyncLifecycleController

@Composable
internal fun DesktopAppContent(
    appCoroutineScope: CoroutineScope,
    foreground: Boolean,
    windowVisible: Boolean,
    trayAvailable: Boolean,
    notify: (String, String) -> Unit,
    messageGateway: MessageNotificationGateway,
    rawSettingsStore: DesktopSettingsStore,
) {
    /** Navigator Logic */
    val navigator = rememberRestorableNavigator()
    val appFeedbackController = remember { AppFeedbackController() }
    val appConfirmationController = remember(appCoroutineScope) {
        AppConfirmationController(appCoroutineScope)
    }
    val appTaskManager = remember(appCoroutineScope, appFeedbackController) {
        AppTaskManager(appCoroutineScope, appFeedbackController)
    }
    DisposableEffect(appCoroutineScope, appFeedbackController, appConfirmationController) {
        onDispose {
            appConfirmationController.close()
            appFeedbackController.close()
            appCoroutineScope.cancel()
        }
    }

    /** Store Logic */
    val secrets = remember { DesktopSecretStore() }
    val cookieStore = remember { DesktopCookieStore(secrets) }
    val userStore = remember { DesktopUserStore(secrets) }
    val forumFavoriteStore = remember { DesktopForumFavoriteStore(rawSettingsStore) }

    /** Repository Logic */
    val yamiboClient = remember { YamiboClient(timeoutMillis = 60_000).apply { setCookie(cookieStore.load().orEmpty()) } }
    DisposableEffect(yamiboClient) { onDispose { yamiboClient.close() } }
    val authRepository = remember { DesktopAuthRepository(cookieStore, userStore, yamiboClient, forumFavoriteStore) }

    val dbFactory = remember { DatabaseFactory() }
    val driver = remember { dbFactory.createDriver() }
    val appDatabase = remember { Database(driver) }
    DisposableEffect(driver) { onDispose { driver.close() } }
    val appSyncService = remember {
        AppSyncService(
            db = appDatabase,
            settingsStore = rawSettingsStore,
            authRepository = authRepository,
        )
    }
    val settingsStore = remember {
        appSyncService.operationRecordingSettingsStore(appDatabase, rawSettingsStore)
    }
    val appSettingsRepository = remember { AppSettingsRepository(settingsStore) }
    val novelReaderSettingsRepository = remember { NovelReaderSettingsRepository(settingsStore) }
    val mangaReaderSettingsRepository = remember { MangaReaderSettingsRepository(settingsStore) }
    val imageReaderModeOverrideRepository = remember { SettingsImageReaderModeOverrideRepository(settingsStore) }
    remember(appSyncService, appSettingsRepository, novelReaderSettingsRepository, mangaReaderSettingsRepository) {
        appSyncService.registerSyncableSettings(
            listOf(appSettingsRepository, novelReaderSettingsRepository, mangaReaderSettingsRepository),
        )
    }
    val fontRepository = remember {
        DefaultFontRepository(
            settingsStore = settingsStore,
            appSettingsRepository = appSettingsRepository,
            novelReaderSettingsRepository = novelReaderSettingsRepository,
            platform = DesktopFontPlatform(),
        )
    }
    val diskCacheFactory = remember {
        me.thenano.yamibo.yamibo_app.core.cache.DiskCacheFactory(dbFactory,
            cacheDirPath = DesktopDirectories.ensureDataRoot().resolve("cache").toString())
    }

    DisposableEffect(diskCacheFactory) {
        onDispose {
            // Cache maintenance is IO-only; join it before closing its SQLite driver.
            kotlinx.coroutines.runBlocking { diskCacheFactory.close() }
        }
    }
    val forumRepository = remember {
        DefaultForumRepository(cookieStore, yamiboClient, diskCacheFactory, forumFavoriteStore)
    }
    val threadRepository = remember { DefaultThreadRepository(cookieStore, yamiboClient, diskCacheFactory) }
    val userSpaceRepository = remember { UserSpaceRepositoryImpl(cookieStore, yamiboClient, diskCacheFactory) }
    val blogRepository = remember { BlogRepositoryImpl(cookieStore, yamiboClient, diskCacheFactory) }
    val chineseConversionRepository = remember { createChineseConversionRepository() }
    val tagRepository = remember { DefaultTagRepository(cookieStore, yamiboClient, diskCacheFactory) }
    val favoriteRepository = remember { appSyncService.favoriteStoreRepository(appDatabase) }
    val detailNoteRepository = remember { appSyncService.detailNoteRepository(appDatabase) }
    val bookMarkRepository = remember { appSyncService.bookMarkRepository(appDatabase) }
    val remoteFavoriteRepository = remember { DefaultFavoriteRepository(cookieStore, yamiboClient) }
    val favoriteSyncRepository = remember {
        FavoriteSyncRepositoryImpl(
            db = appDatabase,
            authRepository = authRepository,
            favoriteRepository = remoteFavoriteRepository,
            localFavoriteRepository = favoriteRepository,
            threadRepository = threadRepository,
        )
    }
    val rssSearchSubscriptionRepository = remember {
        appSyncService.rssSearchSubscriptionRepository(
            db = appDatabase,
            authRepository = authRepository,
            forumRepository = forumRepository,
        )
    }
    val favoriteShareRepository = remember {
        FavoriteShareRepositoryImpl(
            favoriteRepository = favoriteRepository,
            rssRepository = rssSearchSubscriptionRepository,
        )
    }
    val favoriteUpdateRepository = remember {
        appSyncService.favoriteUpdateRepository(
            db = appDatabase,
            localFavoriteRepository = favoriteRepository,
            threadRepository = threadRepository,
            tagRepository = tagRepository,
            rssSearchSubscriptionRepository = rssSearchSubscriptionRepository,
        )
    }
    val backupStorageProvider = remember { DesktopBackupStorageProvider(appSettingsRepository) }
    val backupRepository = remember {
        BackupRepositoryImpl(
            db = appDatabase,
            settingsStore = settingsStore,
            settingsRegistries = listOf(appSettingsRepository, novelReaderSettingsRepository, mangaReaderSettingsRepository),
            storageProvider = backupStorageProvider,
            appVersionCode = AppVersion.VersionCode.toInt(),
        )
    }
    remember(appSyncService, backupRepository) {
        appSyncService.registerLocalSnapshotSource(backupRepository)
    }
    val downloadImageFetcher = remember { DownloadImageFetcher { cookieStore.load().orEmpty() } }
    DisposableEffect(downloadImageFetcher) { onDispose { downloadImageFetcher.close() } }
    val downloadRepository = remember {
        val queueFailureReported = java.util.concurrent.atomic.AtomicBoolean(false)
        DesktopDownloadRepository(appSettingsRepository.backupFolderUri.stateFlow,
            CoroutineScope(appCoroutineScope.coroutineContext + Dispatchers.Default)) { folder, downloadScope ->
            DownloadRepositoryImpl(
                threadRepository = threadRepository,
                tagRepository = tagRepository,
                rssRepository = rssSearchSubscriptionRepository,
                storageProvider = DesktopDownloadStorageProvider(appSettingsRepository, folder) {
                    javax.swing.SwingUtilities.invokeLater {
                        javax.swing.JOptionPane.showMessageDialog(null,
                            "部分離線內容暫時無法使用。請檢查下載資料夾是否仍可存取或重新下載損壞內容；原有檔案未被刪除。",
                            "百合會論壇", javax.swing.JOptionPane.WARNING_MESSAGE)
                    }
                },
                imageFetcher = downloadImageFetcher,
                scope = downloadScope,
                onBackgroundFailure = {
                    if (queueFailureReported.compareAndSet(false, true)) {
                        javax.swing.SwingUtilities.invokeLater {
                            javax.swing.JOptionPane.showMessageDialog(null,
                                "下載佇列無法讀取或儲存，原有佇列未被重設。請檢查資料夾權限、磁碟空間或佇列檔案，修復後重新啟動程式。",
                                "百合會論壇", javax.swing.JOptionPane.WARNING_MESSAGE)
                        }
                    }
                },
            )
        }
    }
    androidx.compose.runtime.LaunchedEffect(backupRepository) {
        diskCacheFactory.backupStorageUsageProvider = { backupRepository.getBackupStorageBytes() }
    }
    val backgroundAccessRepository = remember(trayAvailable) { DesktopBackgroundAccess { trayAvailable } }
    val novelCacheRepository = remember { DefaultNovelThreadCacheRepository(diskCacheFactory) }
    val inAppLinkNavigationRepository = remember {
        DefaultInAppLinkNavigationRepository(threadRepository, novelCacheRepository)
    }
    val readHistoryRepository = remember {
        appSyncService.readHistoryRepository(DefaultReadHistoryRepository(appDatabase))
    }
    val chapterStateRepository = remember { ChapterStateRepositoryImpl(appDatabase) }
    val contentCoverRepository = remember {
        ContentCoverRepositoryImpl(appDatabase)
    }
    val signRepository = remember {
        SignRepositoryImpl(
            signStatusStore = DatabaseSignStatusStore(appDatabase),
            authRepository = authRepository,
            appSettingsRepository = appSettingsRepository,
            yamiboClient = yamiboClient,
        )
    }
    val themeRepository = remember { DefaultThemeRepository() }
    val appUpdateRepository = remember {
        DefaultAppUpdateRepository(
            appSettingsRepository = appSettingsRepository,
            platform = DesktopAppUpdatePlatform(),
        )
    }
    val background = remember {
        DesktopBackgroundServices(appCoroutineScope, rawSettingsStore, appSettingsRepository,
            authRepository, favoriteSyncRepository, favoriteUpdateRepository, backupRepository,
            signRepository, appSyncService, notify)
    }
    val backupScheduler = background.backups
    val signReminderScheduler = background.signReminders
    val appSyncBackgroundScheduler = background.appSyncScheduler
    val appSyncLifecycleController = remember { AppSyncLifecycleController(appSyncService, appSyncBackgroundScheduler) }
    LaunchedEffect(windowVisible) {
        appSyncLifecycleController.reconcileRegistration()
        if (windowVisible) appSyncLifecycleController.onForegroundSessionStarted()
        else appSyncLifecycleController.onForegroundSessionExited()
    }
    val messageChecker = remember {
        MessageNotificationChecker(appSettingsRepository, MessageNotificationDeliveryStateStore(rawSettingsStore),
            { authRepository.currentUser()?.uid?.value }, forumRepository::fetchHomePage, messageGateway)
    }
    val backgroundTaskRepository = background.favoriteSync
    val favoriteSyncRunner = remember {
        FavoriteSyncRunner(
            scope = CoroutineScope(appCoroutineScope.coroutineContext + Dispatchers.Default),
            repository = favoriteSyncRepository,
            backgroundTaskRepository = backgroundTaskRepository,
            prepareRemoteAccess = {
                when (val result = remoteFavoriteRepository.fetchFavorites()) {
                    is YamiboResult.Success -> null
                    else -> i18n(result.message())
                }
            },
        )
    }
    val favoriteUpdateScheduler = background.favoriteUpdates
    val favoriteUpdateRunner = remember {
        FavoriteUpdateRunner(
            scope = CoroutineScope(appCoroutineScope.coroutineContext + Dispatchers.Default),
            repository = favoriteUpdateRepository,
            scheduler = favoriteUpdateScheduler,
            prepareRemoteAccess = {
                when (val result = remoteFavoriteRepository.fetchFavorites()) {
                    is YamiboResult.Success -> null
                    else -> i18n(result.message())
                }
            },
        )
    }


    /** Provide Repositories */
    CompositionLocalProvider(
        LocalAppCoroutineScope provides appCoroutineScope,
        LocalAppFeedbackController provides appFeedbackController,
        LocalAppConfirmationController provides appConfirmationController,
        LocalAppTaskManager provides appTaskManager,
        LocalNavigator provides navigator,
        LocalAuthRepository provides authRepository,
        LocalAppSyncService provides appSyncService,
        LocalAppSyncBackgroundScheduler provides appSyncBackgroundScheduler,
        LocalAppUpdateRepository provides appUpdateRepository,
        LocalForumRepository provides forumRepository,
        LocalThreadRepository provides threadRepository,
        LocalInAppLinkNavigationRepository provides inAppLinkNavigationRepository,
        LocalUserSpaceRepository provides userSpaceRepository,
        LocalBlogRepository provides blogRepository,
        LocalBackupRepository provides backupRepository,
        LocalBackupScheduler provides backupScheduler,
        LocalDownloadRepository provides downloadRepository,
        LocalChineseConversionRepository provides chineseConversionRepository,
        LocalDetailNoteRepository provides detailNoteRepository,
        LocalBookMarkRepository provides bookMarkRepository,
        LocalFavoriteRepository provides favoriteRepository,
        LocalFavoriteShareRepository provides favoriteShareRepository,
        LocalRemoteFavoriteRepository provides remoteFavoriteRepository,
        LocalFavoriteSyncRepository provides favoriteSyncRepository,
        LocalFavoriteSyncRunner provides favoriteSyncRunner,
        LocalFavoriteUpdateRepository provides favoriteUpdateRepository,
        LocalFavoriteUpdateRunner provides favoriteUpdateRunner,
        LocalRssSearchSubscriptionRepository provides rssSearchSubscriptionRepository,
        LocalFontRepository provides fontRepository,
        LocalBackgroundAccessRepository provides backgroundAccessRepository,
        LocalNovelThreadCacheRepository provides novelCacheRepository,
        LocalReadHistoryRepository provides readHistoryRepository,
        LocalChapterStateRepository provides chapterStateRepository,
        LocalContentCoverRepository provides contentCoverRepository,
        LocalSignRepository provides signRepository,
        LocalThemeRepository provides themeRepository,
        LocalTagRepository provides tagRepository,
        LocalAppSettingsRepository provides appSettingsRepository,
        LocalDiskCacheFactory provides diskCacheFactory,
        LocalNovelReaderSettingsRepository provides novelReaderSettingsRepository,
        LocalMangaReaderSettingsRepository provides mangaReaderSettingsRepository,
        LocalImageReaderModeOverrideRepository provides imageReaderModeOverrideRepository,
        LocalSignReminderScheduler provides signReminderScheduler,
    ) {
        val messageNotificationEnabled = appSettingsRepository.messageNotificationEnabled.state()
        val messageNotificationInterval = appSettingsRepository.messageNotificationInterval.state()
        val favoriteUpdateInterval = appSettingsRepository.favoriteUpdateInterval.state()
        val backupInterval = appSettingsRepository.backupInterval.state()
        val signReminderFrequency = appSettingsRepository.signInReminderFrequency.state()
        LaunchedEffect(favoriteUpdateInterval) { favoriteUpdateRunner.schedulePeriodicUpdate(favoriteUpdateInterval) }
        LaunchedEffect(backupInterval) { backupScheduler.schedule(backupInterval) }
        LaunchedEffect(signReminderFrequency) { signReminderScheduler.schedule(signReminderFrequency) }

        androidx.compose.runtime.LaunchedEffect(Unit) {
            if (appSettingsRepository.clearCacheOnAppLaunch.getValue()) {
                diskCacheFactory.clearAllCache()
            }
        }
        androidx.compose.runtime.LaunchedEffect(messageNotificationEnabled, messageNotificationInterval) {
            background.jobs.schedule("message-check",
                messageNotificationInterval.duration.inWholeMilliseconds.takeIf { messageNotificationEnabled }) {
                messageChecker.check()
            }
        }
        Box(Modifier.fillMaxSize()) {
            App()
            YamiboWafChallengeHost(yamiboClient, foreground, Modifier.fillMaxSize()) { session, modifier ->
                DesktopWafBrowser(session, modifier)
            }
        }
    }
}

@file:Suppress("FunctionName", "unused")

package me.thenano.yamibo.yamibo_app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController
import io.github.littlesurvival.core.YamiboResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import me.thenano.yamibo.yamibo_app.db.DatabaseFactory
import me.thenano.yamibo.yamibo_app.favorite.sync.FavoriteSyncRunner
import me.thenano.yamibo.yamibo_app.favorite.sync.IOSBackgroundTaskRepository
import me.thenano.yamibo.yamibo_app.favorite.updates.FavoriteUpdateRunner
import me.thenano.yamibo.yamibo_app.favorite.updates.IOSFavoriteUpdateScheduler
import me.thenano.yamibo.yamibo_app.feedback.AppFeedbackController
import me.thenano.yamibo.yamibo_app.i18n.i18n
import me.thenano.yamibo.yamibo_app.navigation.LocalNavigator
import me.thenano.yamibo.yamibo_app.navigation.rememberRestorableNavigator
import me.thenano.yamibo.yamibo_app.network.IOSYamiboClientProvider
import me.thenano.yamibo.yamibo_app.profile.settings.access.IOSBackgroundAccessRepository
import me.thenano.yamibo.yamibo_app.profile.settings.backup.IOSBackupScheduler
import me.thenano.yamibo.yamibo_app.profile.settings.sign.IOSSignReminderScheduler
import me.thenano.yamibo.yamibo_app.repository.*
import me.thenano.yamibo.yamibo_app.repository.localnovel.IOSLocalNovelRepository
import me.thenano.yamibo.yamibo_app.repository.localnovel.PlatformFileOperations
import me.thenano.yamibo.yamibo_app.repository.backup.BackupRepositoryImpl
import me.thenano.yamibo.yamibo_app.repository.appsync.AppSyncService
import me.thenano.yamibo.yamibo_app.repository.chineseconversion.createChineseConversionRepository
import me.thenano.yamibo.yamibo_app.repository.download.DownloadImageFetcher
import me.thenano.yamibo.yamibo_app.repository.download.DownloadRepositoryImpl
import me.thenano.yamibo.yamibo_app.repository.download.IOSDownloadStorageProvider
import me.thenano.yamibo.yamibo_app.repository.favorite.FavoriteShareRepositoryImpl
import me.thenano.yamibo.yamibo_app.repository.favorite.FavoriteSyncRepositoryImpl
import me.thenano.yamibo.yamibo_app.repository.contentcover.ContentCoverRepositoryImpl
import me.thenano.yamibo.yamibo_app.repository.font.DefaultFontRepository
import me.thenano.yamibo.yamibo_app.repository.font.IOSFontPlatform
import me.thenano.yamibo.yamibo_app.repository.appupdate.DefaultAppUpdateRepository
import me.thenano.yamibo.yamibo_app.repository.inapplinknavigation.DefaultInAppLinkNavigationRepository
import me.thenano.yamibo.yamibo_app.repository.settings.AppSettingsRepository
import me.thenano.yamibo.yamibo_app.repository.settings.MangaReaderSettingsRepository
import me.thenano.yamibo.yamibo_app.repository.settings.NovelReaderSettingsRepository
import me.thenano.yamibo.yamibo_app.repository.settings.SettingsImageReaderModeOverrideRepository
import me.thenano.yamibo.yamibo_app.repository.userspace.BlogRepositoryImpl
import me.thenano.yamibo.yamibo_app.repository.userspace.UserSpaceRepositoryImpl
import me.thenano.yamibo.yamibo_app.store.IOSCookieStore
import me.thenano.yamibo.yamibo_app.store.IOSForumFavoriteStore
import me.thenano.yamibo.yamibo_app.store.IOSUserStore
import me.thenano.yamibo.yamibo_app.store.settings.IOSSettingsStore
import me.thenano.yamibo.yamibo_app.update.IOSAppUpdatePlatform
import me.thenano.yamibo.yamibo_app.task.AppTaskManager
import me.thenano.yamibo.yamibo_app.confirmation.AppConfirmationController
import me.thenano.yamibo.yamibo_app.appsync.IOSAppSyncBackgroundScheduler
import me.thenano.yamibo.yamibo_app.appsync.AppSyncLifecycleController
import me.thenano.yamibo.yamibo_app.appsync.attachIOSAppSyncLifecycle
import me.thenano.yamibo.yamibo_app.appsync.detachIOSAppSyncLifecycle

fun MainViewController() = ComposeUIViewController {
    /** Navigator Logic */
    val navigator = rememberRestorableNavigator()
    val appCoroutineScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main) }
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
    val cookieStore = remember { IOSCookieStore() }
    val userStore = remember { IOSUserStore() }
    val forumFavoriteStore = remember { IOSForumFavoriteStore() }
    val rawSettingsStore = remember { IOSSettingsStore() }

    /** Repository Logic */
    val yamiboClient = remember { IOSYamiboClientProvider.get(cookieStore) }
    val authRepository = remember { IOSAuthRepository(cookieStore, userStore, yamiboClient, forumFavoriteStore) }

    val dbFactory = remember { DatabaseFactory() }
    val appDatabase = remember { Database(dbFactory.createDriver()) }
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
            platform = IOSFontPlatform(),
        )
    }
    val diskCacheFactory = remember { 
        val paths = platform.Foundation.NSSearchPathForDirectoriesInDomains(
            platform.Foundation.NSCachesDirectory, 
            platform.Foundation.NSUserDomainMask, 
            true
        )
        val cacheDir = paths.first() as String
        me.thenano.yamibo.yamibo_app.core.cache.DiskCacheFactory(dbFactory, cacheDirPath = cacheDir) 
    }

    val forumRepository = remember {
        IOSForumRepository(cookieStore, yamiboClient, diskCacheFactory, forumFavoriteStore)
    }
    val threadRepository = remember { IOSThreadRepository(cookieStore, yamiboClient, diskCacheFactory) }
    val userSpaceRepository = remember { UserSpaceRepositoryImpl(cookieStore, yamiboClient, diskCacheFactory) }
    val blogRepository = remember { BlogRepositoryImpl(cookieStore, yamiboClient, diskCacheFactory) }
    val chineseConversionRepository = remember { createChineseConversionRepository() }
    val tagRepository = remember { IOSTagRepository(cookieStore, yamiboClient, diskCacheFactory) }
    val favoriteRepository = remember { appSyncService.favoriteStoreRepository(appDatabase) }
    val detailNoteRepository = remember { appSyncService.detailNoteRepository(appDatabase) }
    val bookMarkRepository = remember { appSyncService.bookMarkRepository(appDatabase) }
    val remoteFavoriteRepository = remember { IOSFavoriteRepository(cookieStore, yamiboClient) }
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
    val backgroundTaskRepository = remember { IOSBackgroundTaskRepository(favoriteSyncRepository) }
    val favoriteSyncRunner = remember {
        FavoriteSyncRunner(
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
    val favoriteUpdateScheduler = remember { IOSFavoriteUpdateScheduler(favoriteUpdateRepository) }
    val favoriteUpdateRunner = remember {
        FavoriteUpdateRunner(
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
    val backupStorageProvider = remember { IOSBackupStorageProvider(appSettingsRepository) }
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
    val downloadRepository = remember {
        DownloadRepositoryImpl(
            threadRepository = threadRepository,
            tagRepository = tagRepository,
            rssRepository = rssSearchSubscriptionRepository,
            storageProvider = IOSDownloadStorageProvider(appSettingsRepository),
            imageFetcher = DownloadImageFetcher { cookieStore.load().orEmpty() },
        )
    }
    val backupScheduler = remember { IOSBackupScheduler() }
    val appSyncBackgroundScheduler = remember { IOSAppSyncBackgroundScheduler() }
    val appSyncLifecycleController = remember(appSyncService, appSyncBackgroundScheduler) {
        AppSyncLifecycleController(appSyncService, appSyncBackgroundScheduler)
    }
    DisposableEffect(appSyncLifecycleController) {
        attachIOSAppSyncLifecycle(appSyncLifecycleController)
        onDispose { detachIOSAppSyncLifecycle(appSyncLifecycleController) }
    }
    androidx.compose.runtime.LaunchedEffect(backupRepository) {
        diskCacheFactory.backupStorageUsageProvider = { backupRepository.getBackupStorageBytes() }
    }
    val backgroundAccessRepository = remember { IOSBackgroundAccessRepository() }
    val novelCacheRepository = remember { IOSNovelThreadCacheRepository(diskCacheFactory) }
    val inAppLinkNavigationRepository = remember {
        DefaultInAppLinkNavigationRepository(threadRepository, novelCacheRepository)
    }
    val readHistoryRepository = remember {
        appSyncService.readHistoryRepository(IOSReadHistoryRepository(appDatabase))
    }
    val chapterStateRepository = remember { IOSLocalChapterStateRepository(dbFactory) }
    val contentCoverRepository = remember {
        ContentCoverRepositoryImpl(Database(dbFactory.createDriver()))
    }
    val signRepository = remember {
        IOSSignRepository(
            dbFactory = dbFactory,
            authRepository = authRepository,
            appSettingsRepository = appSettingsRepository,
            yamiboClient = yamiboClient,
        )
    }
    val themeRepository = remember { IOSThemeRepository() }
    val localNovelRepository = remember { IOSLocalNovelRepository(dbFactory) }
    val platformFileOps = remember { PlatformFileOperations() }
    val signReminderScheduler = remember { IOSSignReminderScheduler() }
    val appUpdateRepository = remember {
        DefaultAppUpdateRepository(
            appSettingsRepository = appSettingsRepository,
            platform = IOSAppUpdatePlatform(),
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
        LocalLocalNovelRepository provides localNovelRepository,
        LocalPlatformFileOperations provides platformFileOps,
        LocalTagRepository provides tagRepository,
        LocalAppSettingsRepository provides appSettingsRepository,
        LocalDiskCacheFactory provides diskCacheFactory,
        LocalNovelReaderSettingsRepository provides novelReaderSettingsRepository,
        LocalMangaReaderSettingsRepository provides mangaReaderSettingsRepository,
        LocalImageReaderModeOverrideRepository provides imageReaderModeOverrideRepository,
        LocalSignReminderScheduler provides signReminderScheduler,
    ) {
        androidx.compose.runtime.LaunchedEffect(Unit) {
            if (appSettingsRepository.clearCacheOnAppLaunch.getValue()) {
                diskCacheFactory.clearAllCache()
            }
        }
        YamiboWafRecoveryRoot(yamiboClient) {
            App()
        }
    }
}

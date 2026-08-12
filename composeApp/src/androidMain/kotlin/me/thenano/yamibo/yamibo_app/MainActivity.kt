package me.thenano.yamibo.yamibo_app

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import io.github.littlesurvival.core.YamiboResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import me.thenano.yamibo.yamibo_app.core.cache.DiskCacheFactory
import me.thenano.yamibo.yamibo_app.db.DatabaseFactory
import me.thenano.yamibo.yamibo_app.download.AndroidDownloadBackgroundController
import me.thenano.yamibo.yamibo_app.download.AndroidDownloadRuntime
import me.thenano.yamibo.yamibo_app.favorite.sync.AndroidAppForegroundTracker
import me.thenano.yamibo.yamibo_app.favorite.sync.AndroidBackgroundTaskRepository
import me.thenano.yamibo.yamibo_app.favorite.sync.FavoriteSyncRunner
import me.thenano.yamibo.yamibo_app.favorite.updates.AndroidFavoriteUpdateScheduler
import me.thenano.yamibo.yamibo_app.favorite.updates.FavoriteUpdateRunner
import me.thenano.yamibo.yamibo_app.i18n.i18n
import me.thenano.yamibo.yamibo_app.confirmation.AppConfirmationController
import me.thenano.yamibo.yamibo_app.feedback.AppFeedbackController
import me.thenano.yamibo.yamibo_app.navigation.LocalNavigator
import me.thenano.yamibo.yamibo_app.navigation.rememberRestorableNavigator
import me.thenano.yamibo.yamibo_app.network.AndroidYamiboClientProvider
import me.thenano.yamibo.yamibo_app.notification.dismissActiveSignReminder
import me.thenano.yamibo.yamibo_app.profile.settings.access.AndroidBackgroundAccessRepository
import me.thenano.yamibo.yamibo_app.profile.settings.backup.AndroidBackupScheduler
import me.thenano.yamibo.yamibo_app.profile.settings.sign.AndroidSignReminderScheduler
import me.thenano.yamibo.yamibo_app.repository.*
import me.thenano.yamibo.yamibo_app.repository.localnovel.AndroidLocalNovelRepository
import me.thenano.yamibo.yamibo_app.repository.localnovel.PlatformFileOperations
import me.thenano.yamibo.yamibo_app.repository.appupdate.DefaultAppUpdateRepository
import me.thenano.yamibo.yamibo_app.repository.backup.BackupRepositoryImpl
import me.thenano.yamibo.yamibo_app.repository.appsync.AppSyncService
import me.thenano.yamibo.yamibo_app.appsync.AndroidAppSyncBackgroundScheduler
import me.thenano.yamibo.yamibo_app.appsync.AndroidAppSyncLifecycleBridge
import me.thenano.yamibo.yamibo_app.appsync.AppSyncLifecycleController
import me.thenano.yamibo.yamibo_app.repository.chineseconversion.createChineseConversionRepository
import me.thenano.yamibo.yamibo_app.repository.contentcover.ContentCoverRepositoryImpl
import me.thenano.yamibo.yamibo_app.repository.download.AndroidDownloadStorageProvider
import me.thenano.yamibo.yamibo_app.repository.download.DownloadImageFetcher
import me.thenano.yamibo.yamibo_app.repository.download.DownloadRepositoryImpl
import me.thenano.yamibo.yamibo_app.repository.favorite.FavoriteShareRepositoryImpl
import me.thenano.yamibo.yamibo_app.repository.favorite.FavoriteSyncRepositoryImpl
import me.thenano.yamibo.yamibo_app.repository.favorite.FavoriteUpdateRepositoryImpl
import me.thenano.yamibo.yamibo_app.repository.font.AndroidFontPlatform
import me.thenano.yamibo.yamibo_app.repository.font.DefaultFontRepository
import me.thenano.yamibo.yamibo_app.repository.inapplinknavigation.DefaultInAppLinkNavigationRepository
import me.thenano.yamibo.yamibo_app.repository.rss.RssSearchSubscriptionRepositoryImpl
import me.thenano.yamibo.yamibo_app.repository.settings.AppSettingsRepository
import me.thenano.yamibo.yamibo_app.repository.settings.MangaReaderSettingsRepository
import me.thenano.yamibo.yamibo_app.repository.settings.NovelReaderSettingsRepository
import me.thenano.yamibo.yamibo_app.repository.settings.SettingsImageReaderModeOverrideRepository
import me.thenano.yamibo.yamibo_app.repository.userspace.BlogRepositoryImpl
import me.thenano.yamibo.yamibo_app.repository.userspace.UserSpaceRepositoryImpl
import me.thenano.yamibo.yamibo_app.store.AndroidCookieStore
import me.thenano.yamibo.yamibo_app.store.AndroidForumFavoriteStore
import me.thenano.yamibo.yamibo_app.store.AndroidUserStore
import me.thenano.yamibo.yamibo_app.store.settings.AndroidSettingsStore
import me.thenano.yamibo.yamibo_app.update.AndroidAppUpdatePlatform
import me.thenano.yamibo.yamibo_app.util.state
import me.thenano.yamibo.yamibo_app.task.AppTaskManager
import kotlin.time.Duration.Companion.milliseconds

class MainActivity : ComponentActivity() {
    var lastBackTime = 0L

    override fun onStart() {
        super.onStart()
        AndroidAppForegroundTracker.markForeground(true)
        AndroidAppSyncLifecycleBridge.onActivityStarted()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: android.content.Intent?) {
        if (intent?.getBooleanExtra(EXTRA_FROM_NOTIFICATION_SIGN_IN, false) == true) {
            dismissActiveSignReminder(this)
            showSignWebViewTrigger.value = true
            intent.putExtra(EXTRA_FROM_NOTIFICATION_SIGN_IN, false)
        }
    }

    override fun onStop() {
        AndroidAppSyncLifecycleBridge.onActivityStopped(isChangingConfigurations)
        AndroidAppForegroundTracker.markForeground(false)
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        handleIntent(intent)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val context = LocalContext.current
            val notificationPermissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission(),
            ) { }

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
            DisposableEffect(navigator) {
                val callback = onBackPressedDispatcher.addCallback(this@MainActivity) {
                    val exitInterval = 2000L // 2 seconds
                    if (navigator.dispatchBack()) return@addCallback

                    val now = System.currentTimeMillis()
                    if (now - lastBackTime < exitInterval) {
                        Logger.i(
                            "AndroidBackHandler",
                            "Double Tapped(Interval=${now - lastBackTime}) , Exit."
                        )
                        finish()
                    } else {
                        lastBackTime = now
                        Toast.makeText(this@MainActivity, i18n("再按一次退出應用"), Toast.LENGTH_SHORT).show()
                    }
                }
                onDispose { callback.remove() }
            }
            /** Store Logic */
            val cookieStore = remember { AndroidCookieStore(context) }
            val userStore = remember { AndroidUserStore(context) }
            val forumFavoriteStore = remember { AndroidForumFavoriteStore(context) }
            val rawSettingsStore = remember { AndroidSettingsStore(context) }

            /** Repository Logic */
            val yamiboClient = remember { AndroidYamiboClientProvider.get(context) }
            val authRepository = remember {
                AndroidAuthRepository(cookieStore, userStore, yamiboClient, forumFavoriteStore)
            }
            val dbFactory = remember { DatabaseFactory(context) }
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
            DisposableEffect(
                appSyncService,
                appSettingsRepository,
                novelReaderSettingsRepository,
                mangaReaderSettingsRepository,
            ) {
                appSyncService.registerSyncableSettings(
                    listOf(appSettingsRepository, novelReaderSettingsRepository, mangaReaderSettingsRepository),
                )
                onDispose { }
            }
            @SuppressLint("RememberReturnType")
            val fontRepository = remember {
                DefaultFontRepository(
                    settingsStore = settingsStore,
                    appSettingsRepository = appSettingsRepository,
                    novelReaderSettingsRepository = novelReaderSettingsRepository,
                    platform = AndroidFontPlatform(context),
                )
            }
            val diskCacheFactory = remember {
                DiskCacheFactory(
                    dbFactory,
                    cacheDirPath = context.cacheDir.absolutePath
                )
            }
            
            val forumRepository = remember {
                AndroidForumRepository(cookieStore, yamiboClient, diskCacheFactory, forumFavoriteStore)
            }
            val threadRepository = remember { AndroidThreadRepository(cookieStore, yamiboClient, diskCacheFactory) }
            val userSpaceRepository = remember { UserSpaceRepositoryImpl(cookieStore, yamiboClient, diskCacheFactory) }
            val blogRepository = remember { BlogRepositoryImpl(cookieStore, yamiboClient, diskCacheFactory) }
            val chineseConversionRepository = remember { createChineseConversionRepository() }
            val tagRepository = remember { AndroidTagRepository(cookieStore, yamiboClient, diskCacheFactory) }
            val favoriteRepository = remember { appSyncService.favoriteStoreRepository(appDatabase) }
            val detailNoteRepository = remember { appSyncService.detailNoteRepository(appDatabase) }
            val bookMarkRepository = remember { appSyncService.bookMarkRepository(appDatabase) }
            val chapterStateRepository = remember { AndroidLocalChapterStateRepository(dbFactory) }
            val remoteFavoriteRepository = remember { AndroidFavoriteRepository(cookieStore, yamiboClient) }
            val favoriteSyncDatabase = appDatabase
            val favoriteSyncRepository = remember {
                FavoriteSyncRepositoryImpl(
                    db = favoriteSyncDatabase,
                    authRepository = authRepository,
                    favoriteRepository = remoteFavoriteRepository,
                    localFavoriteRepository = favoriteRepository,
                    threadRepository = threadRepository,
                )
            }
            val rssSearchSubscriptionRepository = remember {
                appSyncService.rssSearchSubscriptionRepository(
                    db = favoriteSyncDatabase,
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
                    db = favoriteSyncDatabase,
                    localFavoriteRepository = favoriteRepository,
                    threadRepository = threadRepository,
                    tagRepository = tagRepository,
                    rssSearchSubscriptionRepository = rssSearchSubscriptionRepository,
                )
            }
            val backgroundTaskRepository = remember { AndroidBackgroundTaskRepository(context) }
            @SuppressLint("RememberReturnType")
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
            val favoriteUpdateScheduler = remember { AndroidFavoriteUpdateScheduler(context) }
            @SuppressLint("RememberReturnType")
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
            val backupStorageProvider = remember { AndroidBackupStorageProvider(context, appSettingsRepository) }
            val backupRepository = remember {
                BackupRepositoryImpl(
                    db = favoriteSyncDatabase,
                    settingsStore = settingsStore,
                    settingsRegistries = listOf(appSettingsRepository, novelReaderSettingsRepository, mangaReaderSettingsRepository),
                    storageProvider = backupStorageProvider,
                    appVersionCode = AppVersion.VersionCode.toInt(),
                )
            }
            DisposableEffect(appSyncService, backupRepository) {
                appSyncService.registerLocalSnapshotSource(backupRepository)
                onDispose { }
            }
            val appSyncBackgroundScheduler = remember {
                AndroidAppSyncBackgroundScheduler(context)
            }
            @SuppressLint("RememberReturnType")
            val appSyncLifecycleController = remember(
                appSyncService,
                appSyncBackgroundScheduler,
            ) {
                AppSyncLifecycleController(appSyncService, appSyncBackgroundScheduler)
            }
            DisposableEffect(appSyncLifecycleController) {
                AndroidAppSyncLifecycleBridge.attach(appSyncLifecycleController)
                onDispose {
                    AndroidAppSyncLifecycleBridge.detach(appSyncLifecycleController)
                }
            }
            val downloadRepository = remember {
                DownloadRepositoryImpl(
                    threadRepository = threadRepository,
                    tagRepository = tagRepository,
                    rssRepository = rssSearchSubscriptionRepository,
                    storageProvider = AndroidDownloadStorageProvider(context, appSettingsRepository),
                    imageFetcher = DownloadImageFetcher { cookieStore.load().orEmpty() },
                    backgroundController = AndroidDownloadBackgroundController(context),
                ).also { AndroidDownloadRuntime.repository = it }
            }
            DisposableEffect(downloadRepository) {
                AndroidDownloadRuntime.repository = downloadRepository
                onDispose {
                    if (AndroidDownloadRuntime.repository === downloadRepository) {
                        AndroidDownloadRuntime.repository = null
                    }
                }
            }
            val backupScheduler = remember { AndroidBackupScheduler(context) }
            val signReminderScheduler = remember { AndroidSignReminderScheduler(context) }
            LaunchedEffect(backupRepository) {
                diskCacheFactory.backupStorageUsageProvider = { backupRepository.getBackupStorageBytes() }
            }
            val backgroundAccessRepository = remember { AndroidBackgroundAccessRepository(context) }
            val novelCacheRepository = remember { AndroidNovelThreadCacheRepository(diskCacheFactory) }
            val inAppLinkNavigationRepository = remember {
                DefaultInAppLinkNavigationRepository(threadRepository, novelCacheRepository)
            }
            val readHistoryRepository = remember {
                appSyncService.readHistoryRepository(AndroidReadHistoryRepository(appDatabase))
            }
            val contentCoverRepository = remember {
                ContentCoverRepositoryImpl(Database(dbFactory.createDriver()))
            }
            val signRepository = remember {
                AndroidSignRepository(
                    dbFactory = dbFactory,
                    authRepository = authRepository,
                    appSettingsRepository = appSettingsRepository,
                    yamiboClient = yamiboClient,
                )
            }
            val themeRepository = remember { AndroidThemeRepository() }
            val localNovelRepository = remember { AndroidLocalNovelRepository(dbFactory) }
            val platformFileOps = remember { PlatformFileOperations(context) }
            val appUpdateRepository = remember {
                DefaultAppUpdateRepository(
                    appSettingsRepository = appSettingsRepository,
                    platform = AndroidAppUpdatePlatform(context),
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
                LocalChapterStateRepository provides chapterStateRepository,
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
                val favoriteUpdateInterval = appSettingsRepository.favoriteUpdateInterval.state()
                val backupInterval = appSettingsRepository.backupInterval.state()
                val signReminderFrequency = appSettingsRepository.signInReminderFrequency.state()
                
                LaunchedEffect(Unit) {
                    if (appSettingsRepository.clearCacheOnAppLaunch.getValue()) {
                        delay(1_200.milliseconds)
                        diskCacheFactory.clearAllCache()
                    }
                }
                LaunchedEffect(favoriteUpdateInterval) {
                    delay(1_200.milliseconds)
                    favoriteUpdateRunner.schedulePeriodicUpdate(favoriteUpdateInterval)
                }
                LaunchedEffect(backupInterval) {
                    delay(1_200.milliseconds)
                    backupScheduler.schedule(backupInterval)
                }
                LaunchedEffect(signReminderFrequency) {
                    delay(1_200.milliseconds)
                    signReminderScheduler.schedule(signReminderFrequency)
                }
                LaunchedEffect(Unit) {
                    delay(1_200.milliseconds)
                    if (
                        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.POST_NOTIFICATIONS,
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }

                YamiboWafRecoveryRoot(yamiboClient) {
                    App()
                }
            }
        }
    }

    companion object {
        const val EXTRA_FROM_NOTIFICATION_SIGN_IN = "extra_from_notification_sign_in"
    }
}

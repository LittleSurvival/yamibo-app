package me.thenano.yamibo.yamibo_app

import androidx.compose.runtime.compositionLocalOf
import kotlinx.coroutines.CoroutineScope
import me.thenano.yamibo.yamibo_app.confirmation.AppConfirmationController
import me.thenano.yamibo.yamibo_app.feedback.AppFeedbackController
import me.thenano.yamibo.yamibo_app.task.AppTaskManager
import me.thenano.yamibo.yamibo_app.core.cache.DiskCacheFactory
import me.thenano.yamibo.yamibo_app.repository.AuthRepository
import me.thenano.yamibo.yamibo_app.repository.appsync.AppSyncService
import me.thenano.yamibo.yamibo_app.appsync.AppSyncBackgroundScheduler
import me.thenano.yamibo.yamibo_app.repository.AppUpdateRepository
import me.thenano.yamibo.yamibo_app.repository.BlogRepository
import me.thenano.yamibo.yamibo_app.repository.BackupRepository
import me.thenano.yamibo.yamibo_app.repository.ChineseConversionRepository
import me.thenano.yamibo.yamibo_app.repository.ContentCoverRepository
import me.thenano.yamibo.yamibo_app.repository.DetailNoteRepository
import me.thenano.yamibo.yamibo_app.repository.DownloadRepository
import me.thenano.yamibo.yamibo_app.repository.FavoriteRepository
import me.thenano.yamibo.yamibo_app.repository.FavoriteShareRepository
import me.thenano.yamibo.yamibo_app.repository.FavoriteSyncRepository
import me.thenano.yamibo.yamibo_app.repository.FavoriteUpdateRepository
import me.thenano.yamibo.yamibo_app.repository.FontRepository
import me.thenano.yamibo.yamibo_app.repository.ForumRepository
import me.thenano.yamibo.yamibo_app.repository.InAppLinkNavigationRepository
import me.thenano.yamibo.yamibo_app.repository.BookMarkRepository as BookMarkRepositoryType
import me.thenano.yamibo.yamibo_app.repository.ChapterStateRepository as ChapterStateRepositoryType
import me.thenano.yamibo.yamibo_app.repository.FavoriteStoreRepository as FavoriteStoreRepositoryType
import me.thenano.yamibo.yamibo_app.repository.NovelPrePostCommentsCacheRepository
import me.thenano.yamibo.yamibo_app.repository.ReadHistoryRepository
import me.thenano.yamibo.yamibo_app.repository.RssSearchSubscriptionRepository
import me.thenano.yamibo.yamibo_app.repository.SignRepository
import me.thenano.yamibo.yamibo_app.repository.TagRepository
import me.thenano.yamibo.yamibo_app.repository.ThemeRepository
import me.thenano.yamibo.yamibo_app.repository.ThreadRepository
import me.thenano.yamibo.yamibo_app.repository.UserSpaceRepository
import me.thenano.yamibo.yamibo_app.profile.settings.access.BackgroundAccessRepository
import me.thenano.yamibo.yamibo_app.repository.settings.AppSettingsRepository
import me.thenano.yamibo.yamibo_app.repository.settings.ImageReaderModeOverrideRepository
import me.thenano.yamibo.yamibo_app.repository.settings.MangaReaderSettingsRepository
import me.thenano.yamibo.yamibo_app.repository.settings.NovelReaderSettingsRepository
import me.thenano.yamibo.yamibo_app.favorite.sync.FavoriteSyncRunner
import me.thenano.yamibo.yamibo_app.favorite.updates.FavoriteUpdateRunner
import me.thenano.yamibo.yamibo_app.profile.settings.backup.BackupScheduler
import me.thenano.yamibo.yamibo_app.profile.settings.sign.SignReminderScheduler
import me.thenano.yamibo.yamibo_app.repository.LocalNovelRepository
import me.thenano.yamibo.yamibo_app.repository.localnovel.PlatformFileOperations

val LocalAppCoroutineScope =
    compositionLocalOf<CoroutineScope> { error("LocalAppCoroutineScope not provided") }

val LocalAppFeedbackController =
    compositionLocalOf<AppFeedbackController> { error("LocalAppFeedbackController not provided") }

val LocalAppConfirmationController =
    compositionLocalOf<AppConfirmationController> { error("LocalAppConfirmationController not provided") }

val LocalAppTaskManager =
    compositionLocalOf<AppTaskManager> { error("LocalAppTaskManager not provided") }

val LocalAuthRepository =
    compositionLocalOf<AuthRepository> { error("LocalAuthRepository not provided") }

val LocalAppSyncService =
    compositionLocalOf<AppSyncService?> { null }

val LocalAppSyncBackgroundScheduler =
    compositionLocalOf<AppSyncBackgroundScheduler?> { null }

val LocalAppUpdateRepository =
    compositionLocalOf<AppUpdateRepository> { error("LocalAppUpdateRepository not provided") }

val LocalForumRepository =
    compositionLocalOf<ForumRepository> { error("LocalForumRepository not provided") }

val LocalThreadRepository =
    compositionLocalOf<ThreadRepository> { error("LocalThreadRepository not provided") }

val LocalInAppLinkNavigationRepository =
    compositionLocalOf<InAppLinkNavigationRepository> { error("LocalInAppLinkNavigationRepository not provided") }

val LocalUserSpaceRepository =
    compositionLocalOf<UserSpaceRepository> { error("LocalUserSpaceRepository not provided") }

val LocalBlogRepository =
    compositionLocalOf<BlogRepository> { error("LocalBlogRepository not provided") }

val LocalBackupRepository =
    compositionLocalOf<BackupRepository> { error("LocalBackupRepository not provided") }

val LocalBackupScheduler =
    compositionLocalOf<BackupScheduler> { error("LocalBackupScheduler not provided") }

val LocalDownloadRepository =
    compositionLocalOf<DownloadRepository> { error("LocalDownloadRepository not provided") }

val LocalSignReminderScheduler =
    compositionLocalOf<SignReminderScheduler> { error("LocalSignReminderScheduler not provided") }

val LocalChineseConversionRepository =
    compositionLocalOf<ChineseConversionRepository> { error("LocalChineseConversionRepository not provided") }

val LocalContentCoverRepository =
    compositionLocalOf<ContentCoverRepository> { error("LocalContentCoverRepository not provided") }

val LocalDetailNoteRepository =
    compositionLocalOf<DetailNoteRepository> { error("LocalDetailNoteRepository not provided") }

val LocalBookMarkRepository =
    compositionLocalOf<BookMarkRepositoryType> { error("LocalBookMarkRepository not provided") }

val LocalChapterStateRepository =
    compositionLocalOf<ChapterStateRepositoryType> { error("LocalChapterStateRepository not provided") }

val LocalFavoriteRepository =
    compositionLocalOf<FavoriteStoreRepositoryType> { error("LocalFavoriteRepository not provided") }

val LocalFavoriteShareRepository =
    compositionLocalOf<FavoriteShareRepository> { error("LocalFavoriteShareRepository not provided") }

val LocalRemoteFavoriteRepository =
    compositionLocalOf<FavoriteRepository> { error("LocalRemoteFavoriteRepository not provided") }

val LocalFavoriteSyncRepository =
    compositionLocalOf<FavoriteSyncRepository> { error("LocalFavoriteSyncRepository not provided") }

val LocalFavoriteSyncRunner =
    compositionLocalOf<FavoriteSyncRunner> { error("LocalFavoriteSyncRunner not provided") }

val LocalFavoriteUpdateRepository =
    compositionLocalOf<FavoriteUpdateRepository> { error("LocalFavoriteUpdateRepository not provided") }

val LocalFavoriteUpdateRunner =
    compositionLocalOf<FavoriteUpdateRunner> { error("LocalFavoriteUpdateRunner not provided") }

val LocalRssSearchSubscriptionRepository =
    compositionLocalOf<RssSearchSubscriptionRepository> { error("LocalRssSearchSubscriptionRepository not provided") }

val LocalFontRepository =
    compositionLocalOf<FontRepository> { error("LocalFontRepository not provided") }

val LocalBackgroundAccessRepository =
    compositionLocalOf<BackgroundAccessRepository> { error("LocalBackgroundAccessRepository not provided") }

val LocalThemeRepository =
    compositionLocalOf<ThemeRepository> { error("LocalThemeRepository not provided") }

val LocalNovelThreadCacheRepository =
    compositionLocalOf<NovelPrePostCommentsCacheRepository> { error("LocalNovelThreadCacheRepository not provided") }

val LocalReadHistoryRepository =
    compositionLocalOf<ReadHistoryRepository> { error("LocalReadHistoryRepository not provided") }

val LocalSignRepository =
    compositionLocalOf<SignRepository> { error("LocalSignRepository not provided") }

val LocalTagRepository =
    compositionLocalOf<TagRepository> { error("LocalTagRepository not provided") }

val LocalAppSettingsRepository =
    compositionLocalOf<AppSettingsRepository> { error("LocalAppSettingsRepository not provided") }

val LocalNovelReaderSettingsRepository =
    compositionLocalOf<NovelReaderSettingsRepository> { error("LocalNovelReaderSettingsRepository not provided") }

val LocalMangaReaderSettingsRepository =
    compositionLocalOf<MangaReaderSettingsRepository> { error("LocalMangaReaderSettingsRepository not provided") }

val LocalImageReaderModeOverrideRepository =
    compositionLocalOf<ImageReaderModeOverrideRepository> { error("LocalImageReaderModeOverrideRepository not provided") }

val LocalDiskCacheFactory =
    compositionLocalOf<DiskCacheFactory> { error("LocalDiskCacheFactory not provided") }

val LocalLocalNovelRepository =
    compositionLocalOf<LocalNovelRepository> { error("LocalLocalNovelRepository not provided") }

val LocalPlatformFileOperations =
    compositionLocalOf<PlatformFileOperations> { error("LocalPlatformFileOperations not provided") }

package me.thenano.yamibo.yamibo_app.favorite.updates

import android.content.Context
import me.thenano.yamibo.yamibo_app.Database
import me.thenano.yamibo.yamibo_app.core.cache.DiskCacheFactory
import me.thenano.yamibo.yamibo_app.db.DatabaseFactory
import me.thenano.yamibo.yamibo_app.network.AndroidYamiboClientProvider
import me.thenano.yamibo.yamibo_app.repository.AndroidAuthRepository
import me.thenano.yamibo.yamibo_app.repository.AndroidForumRepository
import me.thenano.yamibo.yamibo_app.repository.AndroidTagRepository
import me.thenano.yamibo.yamibo_app.repository.AndroidThreadRepository
import me.thenano.yamibo.yamibo_app.repository.FavoriteUpdateRepository
import me.thenano.yamibo.yamibo_app.repository.appsync.AppSyncService
import me.thenano.yamibo.yamibo_app.repository.rss.RssSearchSubscriptionRepositoryImpl
import me.thenano.yamibo.yamibo_app.store.AndroidCookieStore
import me.thenano.yamibo.yamibo_app.store.AndroidForumFavoriteStore
import me.thenano.yamibo.yamibo_app.store.AndroidUserStore
import me.thenano.yamibo.yamibo_app.store.settings.AndroidSettingsStore

internal object AndroidFavoriteUpdateSupport {
    fun createRepository(context: Context): FavoriteUpdateRepository {
        val appContext = context.applicationContext
        val dbFactory = DatabaseFactory(appContext)
        val cookieStore = AndroidCookieStore(appContext)
        val userStore = AndroidUserStore(appContext)
        val forumFavoriteStore = AndroidForumFavoriteStore(appContext)
        val yamiboClient = AndroidYamiboClientProvider.get(appContext)
        val diskCacheFactory = DiskCacheFactory(
            dbFactory = dbFactory,
            cacheDirPath = appContext.cacheDir.absolutePath,
        )
        val db = Database(dbFactory.createDriver())
        val authRepository = AndroidAuthRepository(cookieStore, userStore, yamiboClient, forumFavoriteStore)
        val appSyncService = AppSyncService(
            db = db,
            settingsStore = AndroidSettingsStore(appContext),
            authRepository = authRepository,
        )
        val forumRepository = AndroidForumRepository(
            cookieStore,
            yamiboClient,
            diskCacheFactory,
            forumFavoriteStore,
        )
        return appSyncService.favoriteUpdateRepository(
            db = db,
            localFavoriteRepository = appSyncService.favoriteStoreRepository(db),
            threadRepository = AndroidThreadRepository(cookieStore, yamiboClient, diskCacheFactory),
            tagRepository = AndroidTagRepository(cookieStore, yamiboClient, diskCacheFactory),
            rssSearchSubscriptionRepository = appSyncService.rssSearchSubscriptionRepository(
                db = db,
                authRepository = authRepository,
                forumRepository = forumRepository,
            ),
        )
    }
}

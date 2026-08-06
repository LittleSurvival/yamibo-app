package me.thenano.yamibo.yamibo_app.favorite.sync

import android.content.Context
import io.github.littlesurvival.YamiboClient
import me.thenano.yamibo.yamibo_app.Database
import me.thenano.yamibo.yamibo_app.core.cache.DiskCacheFactory
import me.thenano.yamibo.yamibo_app.db.DatabaseFactory
import me.thenano.yamibo.yamibo_app.repository.AndroidAuthRepository
import me.thenano.yamibo.yamibo_app.repository.AndroidFavoriteRepository
import me.thenano.yamibo.yamibo_app.repository.AndroidThreadRepository
import me.thenano.yamibo.yamibo_app.repository.FavoriteSyncRepository
import me.thenano.yamibo.yamibo_app.repository.appsync.AppSyncService
import me.thenano.yamibo.yamibo_app.repository.favorite.FavoriteSyncRepositoryImpl
import me.thenano.yamibo.yamibo_app.store.AndroidCookieStore
import me.thenano.yamibo.yamibo_app.store.AndroidUserStore
import me.thenano.yamibo.yamibo_app.store.settings.AndroidSettingsStore

internal object AndroidFavoriteSyncSupport {
    fun createRepository(context: Context): FavoriteSyncRepository {
        val appContext = context.applicationContext
        val cookieStore = AndroidCookieStore(appContext)
        val userStore = AndroidUserStore(appContext)
        val yamiboClient = YamiboClient(timeoutMillis = 60_000L)
        val authRepository = AndroidAuthRepository(cookieStore, userStore, yamiboClient)
        val dbFactory = DatabaseFactory(appContext)
        val diskCacheFactory = DiskCacheFactory(
            dbFactory = dbFactory,
            cacheDirPath = appContext.cacheDir.absolutePath,
        )
        val db = Database(dbFactory.createDriver())
        val appSyncService = AppSyncService(
            db = db,
            settingsStore = AndroidSettingsStore(appContext),
            authRepository = authRepository,
        )
        return FavoriteSyncRepositoryImpl(
            db = db,
            authRepository = authRepository,
            favoriteRepository = AndroidFavoriteRepository(cookieStore, yamiboClient),
            localFavoriteRepository = appSyncService.favoriteStoreRepository(db),
            threadRepository = AndroidThreadRepository(cookieStore, yamiboClient, diskCacheFactory),
        )
    }
}

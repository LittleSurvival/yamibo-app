package me.thenano.yamibo.yamibo_app.repository

import me.thenano.yamibo.yamibo_app.util.auth.setNativeCookies

import io.github.littlesurvival.YamiboClient
import io.github.littlesurvival.core.YamiboResult
import io.github.littlesurvival.dto.page.FavoritePage
import io.github.littlesurvival.dto.page.FavoriteType
import io.github.littlesurvival.dto.value.FavoriteId
import io.github.littlesurvival.dto.value.FormHash
import io.github.littlesurvival.dto.value.UserId
import me.thenano.yamibo.yamibo_app.store.auth.CookieStore

class IOSFavoriteRepository(
    private val cookieStore: CookieStore,
    private val yamiboClient: YamiboClient
) : FavoriteRepository {
    override suspend fun fetchFavorites(
        userId: UserId?,
        type: FavoriteType,
        page: Int
    ): YamiboResult<FavoritePage> {
        yamiboClient.setNativeCookies(cookieStore.load() ?: "")
        return yamiboClient.fetchFavoritePage(userId, type, page)
    }

    override suspend fun removeFavorite(
        favoriteId: FavoriteId,
        formHash: FormHash,
    ): YamiboResult<String> {
        yamiboClient.setNativeCookies(cookieStore.load() ?: "")
        return yamiboClient.fetchRemoveFavorite(favoriteId, formHash)
    }
}

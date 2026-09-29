package me.thenano.yamibo.yamibo_app.repository

import io.github.littlesurvival.YamiboClient
import io.github.littlesurvival.core.YamiboResult
import io.github.littlesurvival.dto.page.FavoritePage
import io.github.littlesurvival.dto.page.FavoriteType
import io.github.littlesurvival.dto.value.FavoriteId
import io.github.littlesurvival.dto.value.FormHash
import io.github.littlesurvival.dto.value.UserId
import me.thenano.yamibo.yamibo_app.store.auth.CookieStore

class DefaultFavoriteRepository(
    private val cookieStore: CookieStore,
    private val yamiboClient: YamiboClient
) : FavoriteRepository {
    override suspend fun fetchFavorites(
        userId: UserId?,
        type: FavoriteType,
        page: Int
    ): YamiboResult<FavoritePage> {
        yamiboClient.setCookie(cookieStore.load() ?: "")
        return yamiboClient.fetchFavoritePage(userId, type, page)
    }

    override suspend fun removeFavorite(
        favoriteId: FavoriteId,
        formHash: FormHash,
    ): YamiboResult<String> {
        yamiboClient.setCookie(cookieStore.load() ?: "")
        return yamiboClient.fetchRemoveFavorite(favoriteId, formHash)
    }
}

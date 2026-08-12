package me.thenano.yamibo.yamibo_app.repository.forum

import io.github.littlesurvival.core.YamiboResult
import io.github.littlesurvival.dto.page.FavoritePage
import io.github.littlesurvival.dto.page.HomePage
import io.github.littlesurvival.dto.value.FavoriteId
import io.github.littlesurvival.dto.value.ForumId
import io.github.littlesurvival.parse.util.ParseUtils
import me.thenano.yamibo.yamibo_app.store.forum.ForumFavoriteStore
import me.thenano.yamibo.yamibo_app.i18n.i18n

class ForumFavoriteSynchronizer(
    private val store: ForumFavoriteStore,
    private val fetchPage: suspend (Int) -> YamiboResult<FavoritePage>,
) {
    suspend fun applyHomePage(homePage: HomePage) {
        val forumIds = homePage.categories
            .firstOrNull { it.title == FAVORITE_FORUM_CATEGORY_TITLE }
            ?.forums
            .orEmpty()
            .mapTo(linkedSetOf()) { it.fid }
        store.replaceMembership(forumIds)
    }

    suspend fun removeFavorite(
        forumId: ForumId,
        remove: suspend (FavoriteId) -> YamiboResult<String>,
    ): YamiboResult<String> {
        var favoriteId = store.favorites.value[forumId]
        if (favoriteId == null) {
            when (val refresh = refreshFavoriteId(forumId)) {
                is YamiboResult.Success -> favoriteId = refresh.value
                else -> return YamiboResult.Failure(
                    i18n("無法取得這個看板的收藏識別碼，請重新整理後再試。"),
                )
            }
        }
        favoriteId ?: return YamiboResult.Failure(
            i18n("無法取得這個看板的收藏識別碼，請重新整理後再試。"),
        )

        return when (val result = remove(favoriteId)) {
            is YamiboResult.Success -> {
                store.remove(forumId)
                result
            }
            is YamiboResult.Failure -> repairIdAfterRemoveFailure(forumId, result)
            is YamiboResult.NotLoggedIn,
            is YamiboResult.NoPermission,
            is YamiboResult.WafChallenge,
            is YamiboResult.Maintenance -> result
        }
    }

    private suspend fun refreshFavoriteId(forumId: ForumId): YamiboResult<FavoriteId?> {
        if (forumId !in store.favorites.value) return YamiboResult.Success(null)
        var page = 1
        while (true) {
            when (val result = fetchPage(page)) {
                is YamiboResult.Success -> {
                    val favoriteId = result.value.items.firstNotNullOfOrNull { item ->
                        item.favId.takeIf { ParseUtils.extractFid(item.url) == forumId }
                    }
                    if (favoriteId != null) {
                        store.enrichFavoriteIds(mapOf(forumId to favoriteId))
                        return YamiboResult.Success(favoriteId)
                    }
                    val pageNav = result.value.pageNav
                    val totalPages = pageNav?.totalPages ?: page
                    val hasNextPage = page < totalPages || pageNav?.nextUrl != null
                    if (!hasNextPage) {
                        return YamiboResult.Success(null)
                    }
                    page += 1
                }
                is YamiboResult.NotLoggedIn,
                is YamiboResult.NoPermission,
                is YamiboResult.Maintenance,
                is YamiboResult.WafChallenge,
                is YamiboResult.Failure -> return result
            }
        }
    }

    private suspend fun repairIdAfterRemoveFailure(
        forumId: ForumId,
        failure: YamiboResult.Failure,
    ): YamiboResult<String> {
        val repairMessage = when (val refresh = refreshFavoriteId(forumId)) {
            is YamiboResult.Success -> if (refresh.value != null) {
                i18n("解除收藏失敗，已重新整理收藏識別碼，請再試一次。")
            } else {
                i18n("解除收藏失敗，重新整理後仍無法取得收藏識別碼。")
            }
            else -> i18n("解除收藏失敗，且收藏識別碼重新整理失敗，請稍後再試。")
        }
        return YamiboResult.Failure(
            reason = "${failure.reason}\n$repairMessage",
            exception = failure.exception,
        )
    }

    companion object {
        const val FAVORITE_FORUM_CATEGORY_TITLE = "我收藏的版块"
    }
}

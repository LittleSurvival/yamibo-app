package me.thenano.yamibo.yamibo_app.repository

import io.github.littlesurvival.YamiboClient
import io.github.littlesurvival.core.YamiboResult
import io.github.littlesurvival.dto.page.FilterType
import io.github.littlesurvival.dto.page.AddFavoriteResult
import io.github.littlesurvival.dto.page.FavoriteType
import io.github.littlesurvival.dto.page.ForumPage
import io.github.littlesurvival.dto.page.HomePage
import io.github.littlesurvival.dto.page.OrderType
import io.github.littlesurvival.dto.page.SearchPage
import io.github.littlesurvival.dto.value.FormHash
import io.github.littlesurvival.dto.value.ForumId
import io.github.littlesurvival.dto.value.SearchId
import me.thenano.yamibo.yamibo_app.store.auth.CookieStore
import me.thenano.yamibo.yamibo_app.repository.forum.ForumFavoriteSynchronizer
import me.thenano.yamibo.yamibo_app.store.forum.ForumFavoriteStore
import kotlin.time.Duration.Companion.hours

import me.thenano.yamibo.yamibo_app.core.cache.DiskCacheFactory

class IOSForumRepository(
    private val cookieStore: CookieStore,
    private val yamiboClient: YamiboClient,
    diskCacheFactory: DiskCacheFactory,
    private val forumFavoriteStore: ForumFavoriteStore,
) : ForumRepository {

    private val homeCache = diskCacheFactory.create<HomePage>("home_page", maxSize = 1, expiration = 12.hours)
    private val forumCache = diskCacheFactory.create<ForumPage>("forum_page", maxSize = 60, expiration = 2.hours)
    private val favoriteSynchronizer = ForumFavoriteSynchronizer(forumFavoriteStore) { page ->
        yamiboClient.fetchFavoritePage(type = FavoriteType.Forum, page = page)
    }

    override val favoriteForums = forumFavoriteStore.favorites

    companion object {
        private const val HOME_CACHE_KEY = "main"
    }

    override suspend fun fetchHomePage(): YamiboResult<HomePage> {
        yamiboClient.setCookie(cookieStore.load() ?: "")
        val result = yamiboClient.fetchHomePage()

        if (result is YamiboResult.Success) {
            homeCache.set(HOME_CACHE_KEY, result.value)
            favoriteSynchronizer.applyHomePage(result.value)
        }
        return result
    }

    override suspend fun fetchForum(
        fid: ForumId,
        page: Int,
        filterType: FilterType?,
        orderType: OrderType?,
    ): YamiboResult<ForumPage> {
        yamiboClient.setCookie(cookieStore.load() ?: "")
        val result = yamiboClient.fetchForumById(fid, filterType, orderType, page)

        if (result is YamiboResult.Success) {
            forumCache.set(forumCacheKey(fid, page, filterType, orderType), result.value)
        }
        return result
    }

    override suspend fun fetchSearch(
        query: String,
        forumId: ForumId?,
        formHash: FormHash
    ): YamiboResult<SearchPage> {
        yamiboClient.setCookie(cookieStore.load() ?: "")
        return yamiboClient.fetchSearch(query, forumId, formHash)
    }

    override suspend fun fetchSearchById(
        query: String,
        searchId: SearchId,
        page: Int
    ): YamiboResult<SearchPage> {
        yamiboClient.setCookie(cookieStore.load() ?: "")
        return yamiboClient.fetchSearchById(query, searchId, page)
    }

    override suspend fun addFavorite(forumId: ForumId, formHash: FormHash): YamiboResult<AddFavoriteResult> {
        yamiboClient.setCookie(cookieStore.load() ?: "")
        val result = yamiboClient.fetchAddFavorite(forumId, formHash)
        if (result is YamiboResult.Success) {
            forumFavoriteStore.upsert(forumId, result.value.favId)
        }
        return result
    }

    override suspend fun removeFavorite(forumId: ForumId, formHash: FormHash): YamiboResult<String> {
        yamiboClient.setCookie(cookieStore.load() ?: "")
        return favoriteSynchronizer.removeFavorite(forumId) { favoriteId ->
            yamiboClient.fetchRemoveFavorite(favoriteId, formHash)
        }
    }

    override fun getCachedHomePage(): HomePage? = homeCache.get(HOME_CACHE_KEY)

    override fun getCachedForumPage(
        fid: ForumId,
        page: Int,
        filterType: FilterType?,
        orderType: OrderType?,
    ): ForumPage? =
        forumCache.get(forumCacheKey(fid, page, filterType, orderType))

    override fun setCachedForumPage(
        fid: ForumId,
        page: Int,
        forumPage: ForumPage,
        filterType: FilterType?,
        orderType: OrderType?,
    ) {
        forumCache.set(forumCacheKey(fid, page, filterType, orderType), forumPage)
    }

    override fun clearCachedForum(fid: ForumId) {
        forumCache.removeByPrefix(ForumRepository.ForumCacheKey.keyPrefix(fid.value))
    }

    private fun forumCacheKey(
        fid: ForumId,
        page: Int,
        filterType: FilterType?,
        orderType: OrderType?,
    ): String = ForumRepository.ForumCacheKey(
        fid = fid.value,
        page = page,
        filterTypeId = filterType?.id?.value,
        orderFilter = orderType?.filter,
        orderBy = orderType?.orderBy,
    ).toCacheKey()
}

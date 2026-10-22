package me.thenano.yamibo.yamibo_app.repository

import io.github.littlesurvival.core.YamiboResult
import io.github.littlesurvival.dto.page.FilterType
import io.github.littlesurvival.dto.page.AddFavoriteResult
import io.github.littlesurvival.dto.page.ForumPage
import io.github.littlesurvival.dto.page.HomePage
import io.github.littlesurvival.dto.page.OrderType
import io.github.littlesurvival.dto.page.SearchPage
import io.github.littlesurvival.dto.value.FormHash
import io.github.littlesurvival.dto.value.FavoriteId
import io.github.littlesurvival.dto.value.ForumId
import io.github.littlesurvival.dto.value.SearchId
import kotlinx.coroutines.flow.StateFlow

interface ForumRepository {
    val favoriteForums: StateFlow<Map<ForumId, FavoriteId?>>

    data class ForumCacheKey(
        val fid: Int,
        val page: Int,
        val filterTypeId: Int?,
        val orderFilter: String?,
        val orderBy: String?,
    ) {
        fun toCacheKey(): String = listOf(
            fid.toString(),
            page.toString(),
            filterTypeId?.toString() ?: "all",
            orderFilter ?: "default",
            orderBy ?: "default",
        ).joinToString("_")

        companion object {
            fun keyPrefix(fid: Int): String = "${fid}_"
        }
    }

    suspend fun fetchHomePage(): YamiboResult<HomePage>
    suspend fun fetchForum(
        fid: ForumId,
        page: Int = 1,
        filterType: FilterType? = null,
        orderType: OrderType? = null,
    ): YamiboResult<ForumPage>
    suspend fun fetchSearch(
        query: String,
        forumId: ForumId? = null,
        formHash: FormHash
    ): YamiboResult<SearchPage>

    suspend fun fetchSearchById(
        query: String,
        searchId: SearchId,
        page: Int = 1
    ): YamiboResult<SearchPage>

    suspend fun addFavorite(forumId: ForumId, formHash: FormHash): YamiboResult<AddFavoriteResult>
    suspend fun removeFavorite(forumId: ForumId, formHash: FormHash): YamiboResult<String>

    fun getCachedHomePage(): HomePage?
    fun getCachedForumPage(
        fid: ForumId,
        page: Int = 1,
        filterType: FilterType? = null,
        orderType: OrderType? = null,
    ): ForumPage?
    fun setCachedForumPage(
        fid: ForumId,
        page: Int,
        forumPage: ForumPage,
        filterType: FilterType? = null,
        orderType: OrderType? = null,
    )
    fun clearCachedForum(fid: ForumId)
}

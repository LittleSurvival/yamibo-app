package me.thenano.yamibo.yamibo_app.repository.forum

import io.github.littlesurvival.core.YamiboResult
import io.github.littlesurvival.dto.model.ForumSummary
import io.github.littlesurvival.dto.page.FavoriteItem
import io.github.littlesurvival.dto.page.FavoritePage
import io.github.littlesurvival.dto.page.FavoriteType
import io.github.littlesurvival.dto.page.ForumCategory
import io.github.littlesurvival.dto.page.HomePage
import io.github.littlesurvival.dto.value.FavoriteId
import io.github.littlesurvival.dto.value.ForumId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import me.thenano.yamibo.yamibo_app.store.forum.ForumFavoriteStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ForumFavoriteSynchronizerTest {
    @Test
    fun exactHomeCategoryDefinesMembershipWithoutFetchingFavoritePages() = runBlocking {
        val store = InMemoryForumFavoriteStore(mapOf(ForumId(10) to FavoriteId(100)))
        var pageFetches = 0
        val synchronizer = ForumFavoriteSynchronizer(store) {
            pageFetches += 1
            error("Home membership must not fetch favorite pages")
        }

        synchronizer.applyHomePage(homePage("我收藏的版块", listOf(ForumId(10), ForumId(20))))

        assertEquals(
            mapOf(ForumId(10) to FavoriteId(100), ForumId(20) to null),
            store.favorites.value,
        )
        assertEquals(0, pageFetches)
    }

    @Test
    fun similarCategoryTitleDoesNotCountAsFavoriteMembership() = runBlocking {
        val store = InMemoryForumFavoriteStore(mapOf(ForumId(10) to FavoriteId(100)))
        val synchronizer = ForumFavoriteSynchronizer(store) { error("No page should be fetched") }

        synchronizer.applyHomePage(homePage("我的收藏版块", listOf(ForumId(10))))

        assertTrue(store.favorites.value.isEmpty())
    }

    @Test
    fun missingFavoriteCategoryClearsMembership() = runBlocking {
        val store = InMemoryForumFavoriteStore(mapOf(ForumId(10) to FavoriteId(100)))
        val synchronizer = ForumFavoriteSynchronizer(store) { error("No page should be fetched") }

        synchronizer.applyHomePage(HomePage(swiperImages = emptyList(), categories = emptyList()))

        assertTrue(store.favorites.value.isEmpty())
    }

    @Test
    fun homeMembershipDoesNotTriggerIdEnrichment() = runBlocking {
        val store = InMemoryForumFavoriteStore(mapOf(ForumId(10) to FavoriteId(100)))
        val synchronizer = ForumFavoriteSynchronizer(store) { YamiboResult.Failure("offline") }

        synchronizer.applyHomePage(homePage("我收藏的版块", listOf(ForumId(10), ForumId(20))))

        assertEquals(FavoriteId(100), store.favorites.value[ForumId(10)])
        assertTrue(ForumId(20) in store.favorites.value)
        assertNull(store.favorites.value[ForumId(20)])
    }

    @Test
    fun missingIdIsEnrichedOnlyWhenRemoving() = runBlocking {
        val forumId = ForumId(20)
        val refreshedId = FavoriteId(202)
        val store = InMemoryForumFavoriteStore(mapOf(forumId to null))
        var pageFetches = 0
        var removeCalls = 0
        val synchronizer = ForumFavoriteSynchronizer(store) {
            pageFetches += 1
            YamiboResult.Success(
                favoritePage(FavoriteItem("B", "forum.php?mod=forumdisplay&fid=20", refreshedId)),
            )
        }

        val result = synchronizer.removeFavorite(forumId) { favoriteId ->
            removeCalls += 1
            assertEquals(refreshedId, favoriteId)
            YamiboResult.Success("removed")
        }

        assertIs<YamiboResult.Success<String>>(result)
        assertEquals(1, pageFetches)
        assertEquals(1, removeCalls)
        assertTrue(store.favorites.value.isEmpty())
    }

    @Test
    fun failedRemoveRefreshesIdWithoutRetryingAndKeepsMembership() = runBlocking {
        val forumId = ForumId(20)
        val store = InMemoryForumFavoriteStore(mapOf(forumId to FavoriteId(201)))
        var pageFetches = 0
        var removeCalls = 0
        val synchronizer = ForumFavoriteSynchronizer(store) {
            pageFetches += 1
            YamiboResult.Success(
                favoritePage(FavoriteItem("B", "forum.php?mod=forumdisplay&fid=20", FavoriteId(202))),
            )
        }

        val result = synchronizer.removeFavorite(forumId) {
            removeCalls += 1
            YamiboResult.Failure("stale favorite id")
        }

        val failure = assertIs<YamiboResult.Failure>(result)
        assertTrue(failure.reason.contains("已重新整理收藏識別碼"))
        assertEquals(1, pageFetches)
        assertEquals(1, removeCalls)
        assertEquals(FavoriteId(202), store.favorites.value[forumId])
    }

    private fun homePage(title: String, forumIds: List<ForumId>): HomePage = HomePage(
        swiperImages = emptyList(),
        categories = listOf(
            ForumCategory(
                title = title,
                forums = forumIds.map { forumId ->
                    ForumSummary(
                        fid = forumId,
                        name = "Forum ${forumId.value}",
                        url = "forum.php?mod=forumdisplay&fid=${forumId.value}",
                    )
                },
            ),
        ),
    )

    private fun favoritePage(vararg items: FavoriteItem): FavoritePage = FavoritePage(
        type = FavoriteType.Forum,
        items = items.toList(),
    )
}

private class InMemoryForumFavoriteStore(
    initial: Map<ForumId, FavoriteId?> = emptyMap(),
) : ForumFavoriteStore {
    private val state = MutableStateFlow(initial)
    override val favorites: StateFlow<Map<ForumId, FavoriteId?>> = state

    override suspend fun replaceMembership(forumIds: Set<ForumId>) {
        state.value = forumIds.associateWith { state.value[it] }
    }

    override suspend fun enrichFavoriteIds(favoriteIds: Map<ForumId, FavoriteId>) {
        state.value = state.value.mapValues { (forumId, oldId) -> favoriteIds[forumId] ?: oldId }
    }

    override suspend fun upsert(forumId: ForumId, favoriteId: FavoriteId?) {
        state.value += forumId to favoriteId
    }

    override suspend fun remove(forumId: ForumId) {
        state.value -= forumId
    }

    override suspend fun clear() {
        state.value = emptyMap()
    }
}

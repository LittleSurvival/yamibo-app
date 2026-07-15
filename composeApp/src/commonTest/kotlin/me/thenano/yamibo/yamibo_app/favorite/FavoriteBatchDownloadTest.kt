package me.thenano.yamibo.yamibo_app.favorite

import io.github.littlesurvival.core.YamiboResult
import io.github.littlesurvival.dto.model.ThreadSummary
import io.github.littlesurvival.dto.page.SearchPage
import io.github.littlesurvival.dto.page.TagPage
import io.github.littlesurvival.dto.page.ThreadPage
import io.github.littlesurvival.dto.value.ForumId
import io.github.littlesurvival.dto.value.TagId
import io.github.littlesurvival.dto.value.ThreadId
import io.github.littlesurvival.dto.value.UserId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import me.thenano.yamibo.yamibo_app.repository.DownloadRepository
import me.thenano.yamibo.yamibo_app.repository.FavoriteStoreRepository
import me.thenano.yamibo.yamibo_app.repository.RssSearchSubscriptionRepository
import me.thenano.yamibo.yamibo_app.repository.download.DownloadQueueEntry
import me.thenano.yamibo.yamibo_app.repository.download.DownloadQueueSummary
import me.thenano.yamibo.yamibo_app.repository.download.DownloadStatus
import me.thenano.yamibo.yamibo_app.repository.download.DownloadTaskKey
import me.thenano.yamibo.yamibo_app.repository.download.DownloadedContentGroup
import me.thenano.yamibo.yamibo_app.repository.download.DownloadedContentSummary
import me.thenano.yamibo.yamibo_app.repository.download.RssMangaChapterDownloadKey
import me.thenano.yamibo.yamibo_app.repository.download.RssMangaChapterManifest
import me.thenano.yamibo.yamibo_app.repository.download.TagMangaChapterDownloadKey
import me.thenano.yamibo.yamibo_app.repository.download.TagMangaChapterManifest
import me.thenano.yamibo.yamibo_app.repository.download.ThreadPageDownloadKey
import me.thenano.yamibo.yamibo_app.repository.download.ThreadPageDownloadManifest
import kotlin.test.Test
import kotlin.test.assertEquals

class FavoriteBatchDownloadTest {
    @Test
    fun expandsCollectionsAndDeduplicatesByItemAndTarget() {
        val normal = favoriteItem(1, FavoriteStoreRepository.FavoriteTargetType.ThreadNormal, 100)
        val sameItem = normal.copy()
        val sameTargetDifferentItem = favoriteItem(2, FavoriteStoreRepository.FavoriteTargetType.ThreadNormal, 100)
        val novel = favoriteItem(3, FavoriteStoreRepository.FavoriteTargetType.ThreadNovel, 100, UserId(9))
        val content = FavoriteStoreRepository.FavoriteCategoryContent(
            directItems = listOf(normal, sameTargetDifferentItem),
            collections = listOf(collection(7, sameItem, novel)),
        )

        val scope = buildFavoriteBatchDownloadScope(
            content = content,
            selectedItemIds = setOf(normal.id, sameTargetDifferentItem.id),
            selectedCollectionIds = setOf(7),
        )

        assertEquals(2, scope.directItemCount)
        assertEquals(1, scope.selectedCollectionCount)
        assertEquals(2, scope.expandedCollectionItemCount)
        assertEquals(listOf(normal.id, novel.id), scope.items.map { it.id })
    }

    @Test
    fun countsAndFiltersByDownloadType() {
        val scope = FavoriteBatchDownloadScope(
            directItemCount = 4,
            selectedCollectionCount = 0,
            expandedCollectionItemCount = 0,
            items = listOf(
                favoriteItem(1, FavoriteStoreRepository.FavoriteTargetType.ThreadNormal, 10),
                favoriteItem(2, FavoriteStoreRepository.FavoriteTargetType.ThreadNovel, 20),
                favoriteItem(3, FavoriteStoreRepository.FavoriteTargetType.TagManga, 30),
                favoriteItem(4, FavoriteStoreRepository.FavoriteTargetType.RssSearch, 40),
            ),
        )

        assertEquals(1, scope.countByType()[FavoriteBatchDownloadType.NormalThread])
        assertEquals(
            listOf(2L, 4L),
            scope.itemsForTypes(setOf(FavoriteBatchDownloadType.NovelThread, FavoriteBatchDownloadType.RssSearch)).map { it.id },
        )
    }

    @Test
    fun enqueueMapsSupportedFavoritesToAllDownloadApis() = runBlocking {
        val downloads = FakeDownloadRepository()
        val rss = FakeRssRepository(rssSummary(id = 40, query = "keyword"))

        val result = enqueueFavoriteBatchDownloads(
            downloadRepository = downloads,
            rssRepository = rss,
            items = listOf(
                favoriteItem(1, FavoriteStoreRepository.FavoriteTargetType.ThreadNormal, 10),
                favoriteItem(2, FavoriteStoreRepository.FavoriteTargetType.ThreadNovel, 20, UserId(3)),
                favoriteItem(3, FavoriteStoreRepository.FavoriteTargetType.TagManga, 30),
                favoriteItem(4, FavoriteStoreRepository.FavoriteTargetType.RssSearch, 40),
            ),
            mode = FavoriteBatchDownloadMode.All,
        )

        assertEquals(FavoriteBatchDownloadResult(requested = 4, queued = 4, skipped = 0, failed = 0, unsupported = 0), result)
        assertEquals(
            listOf("thread:10:null", "thread:20:3", "tag:30", "rss:40:keyword"),
            downloads.calls,
        )
    }

    @Test
    fun enqueueExceptLastPageModeOnlyChangesThreadFavorites() = runBlocking {
        val downloads = FakeDownloadRepository()
        val rss = FakeRssRepository(rssSummary(id = 40, query = "keyword"))

        val result = enqueueFavoriteBatchDownloads(
            downloadRepository = downloads,
            rssRepository = rss,
            items = listOf(
                favoriteItem(1, FavoriteStoreRepository.FavoriteTargetType.ThreadNormal, 10),
                favoriteItem(2, FavoriteStoreRepository.FavoriteTargetType.ThreadNovel, 20, UserId(3)),
                favoriteItem(3, FavoriteStoreRepository.FavoriteTargetType.TagManga, 30),
                favoriteItem(4, FavoriteStoreRepository.FavoriteTargetType.RssSearch, 40),
            ),
            mode = FavoriteBatchDownloadMode.ExceptLastPage,
        )

        assertEquals(FavoriteBatchDownloadResult(requested = 4, queued = 4, skipped = 0, failed = 0, unsupported = 0), result)
        assertEquals(
            listOf("threadExceptLast:10:null", "threadExceptLast:20:3", "tag:30", "rss:40:keyword"),
            downloads.calls,
        )
    }

    @Test
    fun exceptLastPageAvailabilityRequiresSelectedThreadTypes() {
        val scope = FavoriteBatchDownloadScope(
            directItemCount = 4,
            selectedCollectionCount = 0,
            expandedCollectionItemCount = 0,
            items = listOf(
                favoriteItem(1, FavoriteStoreRepository.FavoriteTargetType.ThreadNormal, 10),
                favoriteItem(2, FavoriteStoreRepository.FavoriteTargetType.TagManga, 30),
                favoriteItem(3, FavoriteStoreRepository.FavoriteTargetType.RssSearch, 40),
            ),
        )

        assertEquals(
            true,
            scope.supportsExceptLastPageDownload(setOf(FavoriteBatchDownloadType.NormalThread, FavoriteBatchDownloadType.TagManga)),
        )
        assertEquals(false, scope.supportsExceptLastPageDownload(setOf(FavoriteBatchDownloadType.TagManga, FavoriteBatchDownloadType.RssSearch)))
        assertEquals(
            FavoriteBatchDownloadMode.All,
            FavoriteBatchDownloadMode.ExceptLastPage.coerceFor(
                scope,
                setOf(FavoriteBatchDownloadType.TagManga, FavoriteBatchDownloadType.RssSearch),
            ),
        )
    }

    @Test
    fun enqueueReportsMissingRssSubscriptionAsUnsupported() = runBlocking {
        val result = enqueueFavoriteBatchDownloads(
            downloadRepository = FakeDownloadRepository(),
            rssRepository = FakeRssRepository(),
            items = listOf(favoriteItem(1, FavoriteStoreRepository.FavoriteTargetType.RssSearch, 99)),
        )

        assertEquals(0, result.queued)
        assertEquals(1, result.unsupported)
    }

    @Test
    fun enqueueAggregatesPartialFailures() = runBlocking {
        val result = enqueueFavoriteBatchDownloads(
            downloadRepository = FakeDownloadRepository(failedThreadIds = setOf(10)),
            rssRepository = FakeRssRepository(),
            items = listOf(
                favoriteItem(1, FavoriteStoreRepository.FavoriteTargetType.ThreadNormal, 10),
                favoriteItem(2, FavoriteStoreRepository.FavoriteTargetType.TagManga, 30),
            ),
        )

        assertEquals(FavoriteBatchDownloadResult(requested = 2, queued = 1, skipped = 0, failed = 1, unsupported = 0), result)
    }

    private fun favoriteItem(
        id: Long,
        targetType: FavoriteStoreRepository.FavoriteTargetType,
        targetId: Long,
        authorId: UserId? = null,
    ) = FavoriteStoreRepository.FavoriteItem(
        id = id,
        targetType = targetType,
        targetId = targetId,
        title = "title-$id",
        coverUrl = null,
        lastUpdatedTime = null,
        forumId = null,
        forumName = null,
        authorId = authorId,
        createdAt = id,
        lastFavoriteStatusUpdateAt = id,
    )

    private fun collection(id: Long, vararg items: FavoriteStoreRepository.FavoriteItem) =
        FavoriteStoreRepository.FavoriteCollectionWithItems(
            collection = FavoriteStoreRepository.FavoriteCollection(
                id = id,
                categoryId = 1,
                name = "collection",
                colorKey = "brown",
                sortOrder = 0,
                createdAt = 0,
                updatedAt = 0,
            ),
            items = items.toList(),
        )

    private fun rssSummary(id: Long, query: String) = RssSearchSubscriptionRepository.SubscriptionSummary(
        id = id,
        title = "RSS title",
        query = query,
        forumId = null,
        forumName = null,
        enabled = true,
        createdAt = 0,
        updatedAt = 0,
        lastRefreshStartedAt = null,
        lastRefreshFinishedAt = null,
        lastRefreshStatus = null,
        lastRefreshMessage = null,
        lastTotalCount = 0,
        unreadCount = 0,
    )
}

private class FakeDownloadRepository(
    private val failedThreadIds: Set<Long> = emptySet(),
) : DownloadRepository {
    val calls = mutableListOf<String>()
    override val queue: StateFlow<List<DownloadQueueEntry>> = MutableStateFlow(emptyList())
    override suspend fun isStorageReady() = true
    override suspend fun getSummary() = DownloadQueueSummary()
    override suspend fun getDownloadedContentSummary() = DownloadedContentSummary()
    override suspend fun getDownloadedContentGroups(): List<DownloadedContentGroup> = emptyList()
    override suspend fun getStatus(key: ThreadPageDownloadKey) = DownloadStatus.NotDownloaded
    override suspend fun getStatus(key: TagMangaChapterDownloadKey) = DownloadStatus.NotDownloaded
    override suspend fun getStatus(key: RssMangaChapterDownloadKey) = DownloadStatus.NotDownloaded
    override suspend fun getDownloadedPage(key: ThreadPageDownloadKey): ThreadPage? = null
    override suspend fun getManifest(key: ThreadPageDownloadKey): ThreadPageDownloadManifest? = null
    override suspend fun getTagMangaChapterImages(key: TagMangaChapterDownloadKey): List<String>? = null
    override suspend fun getTagMangaManifest(key: TagMangaChapterDownloadKey): TagMangaChapterManifest? = null
    override suspend fun getRssMangaChapterImages(key: RssMangaChapterDownloadKey): List<String>? = null
    override suspend fun getRssMangaManifest(key: RssMangaChapterDownloadKey): RssMangaChapterManifest? = null
    override suspend fun enqueuePage(tid: ThreadId, title: String, authorId: UserId?, page: Int) = Result.success(Unit)
    override suspend fun enqueueThread(tid: ThreadId, title: String, authorId: UserId?): Result<Unit> {
        calls += "thread:${tid.value}:${authorId?.value}"
        if (tid.value.toLong() in failedThreadIds) return Result.failure(IllegalStateException("failed"))
        return Result.success(Unit)
    }
    override suspend fun enqueueThreadExceptLastPage(tid: ThreadId, title: String, authorId: UserId?): Result<Unit> {
        calls += "threadExceptLast:${tid.value}:${authorId?.value}"
        if (tid.value.toLong() in failedThreadIds) return Result.failure(IllegalStateException("failed"))
        return Result.success(Unit)
    }
    override suspend fun enqueueTagMangaChapter(tagId: TagId, tagName: String, thread: ThreadSummary, tagPage: Int) = Result.success(Unit)
    override suspend fun enqueueTagMangaCurrentPage(tagId: TagId, tagName: String, threads: List<ThreadSummary>, tagPage: Int) = Result.success(Unit)
    override suspend fun enqueueTagMangaAllPages(tagId: TagId, tagName: String): Result<Unit> {
        calls += "tag:${tagId.value}"
        return Result.success(Unit)
    }
    override suspend fun enqueueRssMangaChapter(subscriptionId: Long, title: String, query: String, thread: ThreadSummary, page: Int) = Result.success(Unit)
    override suspend fun enqueueRssMangaCurrentPage(subscriptionId: Long, title: String, query: String, threads: List<ThreadSummary>, page: Int) = Result.success(Unit)
    override suspend fun enqueueRssMangaAllPages(subscriptionId: Long, title: String, query: String): Result<Unit> {
        calls += "rss:$subscriptionId:$query"
        return Result.success(Unit)
    }
    override suspend fun refreshPage(tid: ThreadId, title: String, authorId: UserId?, page: Int): YamiboResult<ThreadPage> = YamiboResult.Failure("unused")
    override suspend fun refreshTagMangaChapter(key: TagMangaChapterDownloadKey): YamiboResult<List<String>> = YamiboResult.Failure("unused")
    override suspend fun refreshRssMangaChapter(key: RssMangaChapterDownloadKey): YamiboResult<List<String>> = YamiboResult.Failure("unused")
    override suspend fun markThreadUpdateAvailable(tid: ThreadId, authorId: UserId?) = Unit
    override suspend fun clearPage(key: ThreadPageDownloadKey) = Result.success(Unit)
    override suspend fun clearThread(key: ThreadPageDownloadKey) = Result.success(Unit)
    override suspend fun clearTagMangaChapter(key: TagMangaChapterDownloadKey) = Result.success(Unit)
    override suspend fun clearTagManga(tagId: TagId) = Result.success(Unit)
    override suspend fun clearRssMangaChapter(key: RssMangaChapterDownloadKey) = Result.success(Unit)
    override suspend fun clearRssManga(subscriptionId: Long) = Result.success(Unit)
    override suspend fun retry(key: DownloadTaskKey) = Result.success(Unit)
    override fun pauseAll() = Unit
    override fun resumeAll() = Unit
}

private class FakeRssRepository(
    private vararg val summaries: RssSearchSubscriptionRepository.SubscriptionSummary,
) : RssSearchSubscriptionRepository {
    override val subscriptions: StateFlow<List<RssSearchSubscriptionRepository.SubscriptionSummary>> =
        MutableStateFlow(summaries.toList())
    override val unreadCount: StateFlow<Int> = MutableStateFlow(0)
    override suspend fun createFromSearch(title: String, query: String, forumId: ForumId?, forumName: String?, searchPage: SearchPage): YamiboResult<Long> =
        YamiboResult.Failure("unused")
    override suspend fun ensureSubscription(query: String, forumId: ForumId?, forumName: String?): YamiboResult<Long> =
        YamiboResult.Failure("unused")
    override suspend fun findBySearch(query: String, forumId: ForumId?): RssSearchSubscriptionRepository.SubscriptionSummary? = null
    override suspend fun refresh(subscriptionId: Long): YamiboResult<RssSearchSubscriptionRepository.RefreshSummary> = YamiboResult.Failure("unused")
    override suspend fun refreshAllEnabled(): List<YamiboResult<RssSearchSubscriptionRepository.RefreshSummary>> = emptyList()
    override suspend fun getSubscription(subscriptionId: Long): RssSearchSubscriptionRepository.SubscriptionSummary? =
        summaries.firstOrNull { it.id == subscriptionId }
    override suspend fun getCatalogPage(subscriptionId: Long, page: Int, pageSize: Int): RssSearchSubscriptionRepository.CatalogPage? = null
    override suspend fun getCachedCatalogPage(subscriptionId: Long, page: Int, pageSize: Int): RssSearchSubscriptionRepository.CatalogPage? = null
    override suspend fun markRead(subscriptionId: Long, threadId: Long) = Unit
    override suspend fun markUnread(subscriptionId: Long, threadId: Long) = Unit
    override suspend fun rename(subscriptionId: Long, title: String) = Unit
    override suspend fun setEnabled(subscriptionId: Long, enabled: Boolean) = Unit
    override suspend fun delete(subscriptionId: Long) = Unit
}

package me.thenano.yamibo.yamibo_app.repository

import io.github.littlesurvival.dto.value.ForumId
import io.github.littlesurvival.dto.value.PostId
import io.github.littlesurvival.dto.value.TagId
import io.github.littlesurvival.dto.value.ThreadId
import io.github.littlesurvival.dto.value.UserId
import me.thenano.yamibo.yamibo_app.Database
import me.thenano.yamibo.yamibo_app.i18n.i18n
import me.thenano.yamibo.yamibo_app.db.DatabaseFactory
import me.thenano.yamibo.yamibo_app.util.time.currentTimeMillis
import me.thenano.yamibo.yamibo_app.repository.rss.rssSearchSubscriptionSyncId
import me.thenano.yamibo.yamibo_app.MangaTagReadingHistory
import me.thenano.yamibo.yamibo_app.ReadingHistory
import me.thenano.yamibo.yamibo_app.RssCatalogReadingHistory
import me.thenano.yamibo.yamibo_app.RssSearchReadingHistory
import me.thenano.yamibo.yamibo_app.TagCatalogReadingHistory

class IOSReadHistoryRepository(
    private val db: Database,
) : ReadHistoryRepository, SynchronousReaderPersistence {
    constructor(dbFactory: DatabaseFactory) : this(Database(dbFactory.createDriver()))
    private companion object {
        const val COMBINED_HISTORY_BATCH_SIZE = 500L
        const val MAX_HISTORY_ITEMS = 2000L
    }

    private val queries = db.readingHistoryQueries
    private val readingTimeQueries = db.readingTimeStatQueries

    override suspend fun savePosition(history: ReadHistoryRepository.ThreadReadingHistory) {
        savePositionSynchronously(history)
    }

    override fun savePositionSynchronously(history: ReadHistoryRepository.ThreadReadingHistory) {
        queries.upsert(
            threadId = history.threadId.value.toLong(),
            threadType = history.threadType.name,
            threadName = history.threadName,
            threadCover = history.threadCover,
            forumName = history.forumName,
            forumId = history.forumId?.value?.toLong(),
            authorId = history.authorId?.value?.toLong() ?: 0L,
            page = history.page.toLong(),
            postId = history.postId.value.toLong(),
            postTitle = history.postTitle,
            anchorPostId = history.anchorPostId,
            anchorPostRatio = history.anchorPostRatio?.toDouble(),
            anchorBlockId = history.anchorBlockId,
            anchorBlockType = history.anchorBlockType,
            anchorBlockRatio = history.anchorBlockRatio?.toDouble(),
            globalScrollY = history.globalScrollY?.toLong(),
            viewportHeight = history.viewportHeight?.toLong(),
            firstVisibleItemIndex = history.firstVisibleItemIndex?.toLong(),
            firstVisibleItemOffset = history.firstVisibleItemOffset?.toLong(),
            historyOrigin = history.historyOrigin.name,
            lastVisitTime = history.lastVisitTime,
            lastUpdatedTime = history.lastUpdatedTime
        )
        queries.trimToLatest(MAX_HISTORY_ITEMS)
    }

    override suspend fun getPosition(
        tid: ThreadId,
        threadType: ReadHistoryRepository.ThreadEntryType,
        authorId: UserId?
    ): ReadHistoryRepository.ThreadReadingHistory? {
        return queries.getByThreadKey(
            threadId = tid.value.toLong(),
            threadType = threadType.name,
            authorId = authorId?.value?.toLong() ?: 0L
        ).executeAsOneOrNull()?.toHistory()
    }

    override suspend fun getHistoryPage(
        page: Int,
        pageSize: Int
    ): List<ReadHistoryRepository.ThreadReadingHistory> {
        val offset = (page - 1).coerceAtLeast(0) * pageSize
        return queries.getPage(pageSize.toLong(), offset.toLong())
            .executeAsList()
            .map { it.toHistory() }
    }

    override suspend fun getHistoryCount(): Long {
        return queries.countAll().executeAsOne()
    }

    override suspend fun deleteHistory(
        tid: ThreadId,
        threadType: ReadHistoryRepository.ThreadEntryType,
        authorId: UserId?
    ) {
        getPosition(tid, threadType, authorId)?.let(::deleteHistorySynchronously)
    }

    override fun deleteHistorySynchronously(history: ReadHistoryRepository.ThreadReadingHistory) {
        queries.deleteByThreadKey(
            threadId = history.threadId.value.toLong(),
            threadType = history.threadType.name,
            authorId = history.authorId?.value?.toLong() ?: 0L
        )
    }

    override suspend fun deleteAll() {
        deleteAllThreadHistorySynchronously()
    }

    override fun deleteAllThreadHistorySynchronously() {
        queries.deleteAll()
    }

    override suspend fun searchHistory(
        query: String,
        page: Int,
        pageSize: Int
    ): List<ReadHistoryRepository.ThreadReadingHistory> {
        val offset = (page - 1).coerceAtLeast(0) * pageSize
        return queries.searchByName(query, pageSize.toLong(), offset.toLong())
            .executeAsList()
            .map { it.toHistory() }
    }

    override suspend fun searchHistoryCount(query: String): Long {
        return queries.countByName(query).executeAsOne()
    }

    override suspend fun deleteHistoryBatch(items: List<ReadHistoryRepository.ThreadReadingHistory>) {
        deleteHistoryBatchSynchronously(items)
    }

    override fun deleteHistoryBatchSynchronously(items: List<ReadHistoryRepository.ThreadReadingHistory>) {
        if (items.isEmpty()) return
        db.transaction {
            items.forEach { item ->
                queries.deleteByThreadKey(
                    threadId = item.threadId.value.toLong(),
                    threadType = item.threadType.name,
                    authorId = item.authorId?.value?.toLong() ?: 0L
                )
            }
        }
    }

    private fun ReadingHistory.toHistory(): ReadHistoryRepository.ThreadReadingHistory {
        return ReadHistoryRepository.ThreadReadingHistory(
            threadType = ReadHistoryRepository.ThreadEntryType.fromStorage(threadType),
            threadName = threadName,
            threadId = ThreadId(threadId.toInt()),
            threadCover = threadCover,
            lastUpdatedTime = lastUpdatedTime,
            forumName = forumName,
            forumId = forumId?.toInt()?.let { ForumId(it) },
            authorId = authorId.takeIf { it != 0L }?.toInt()?.let { UserId(it) },
            page = page.toInt(),
            postId = PostId(postId.toInt()),
            postTitle = postTitle,
            anchorPostId = anchorPostId,
            anchorPostRatio = anchorPostRatio?.toFloat(),
            anchorBlockId = anchorBlockId,
            anchorBlockType = anchorBlockType,
            anchorBlockRatio = anchorBlockRatio?.toFloat(),
            globalScrollY = globalScrollY?.toInt(),
            viewportHeight = viewportHeight?.toInt(),
            firstVisibleItemIndex = firstVisibleItemIndex?.toInt(),
            firstVisibleItemOffset = firstVisibleItemOffset?.toInt(),
            historyOrigin = ReadHistoryRepository.ThreadHistoryOrigin.fromStorage(historyOrigin),
            lastVisitTime = lastVisitTime
        )
    }

    private val imageQueries = db.imageReadingHistoryQueries

    override suspend fun saveImagePosition(history: ReadHistoryRepository.ImageReadingHistory) {
        saveImagePositionSynchronously(history)
    }

    override fun saveImagePositionSynchronously(history: ReadHistoryRepository.ImageReadingHistory) {
        imageQueries.upsert(
            postId = history.postId.value.toLong(),
            threadId = history.threadId.value.toLong(),
            pageIndex = history.pageIndex.toLong(),
            totalPages = history.totalPages.toLong(),
            firstVisibleItemIndex = history.firstVisibleItemIndex?.toLong(),
            firstVisibleItemOffset = history.firstVisibleItemOffset?.toLong(),
            lastVisitTime = history.lastVisitTime
        )
        imageQueries.trimToLatest(MAX_HISTORY_ITEMS)
    }

    override suspend fun getImagePosition(postId: PostId): ReadHistoryRepository.ImageReadingHistory? {
        return imageQueries.getByPostId(postId.value.toLong()).executeAsOneOrNull()?.let {
            ReadHistoryRepository.ImageReadingHistory(
                postId = PostId(it.postId.toInt()),
                threadId = ThreadId(it.threadId.toInt()),
                pageIndex = it.pageIndex.toInt(),
                totalPages = it.totalPages.toInt(),
                firstVisibleItemIndex = it.firstVisibleItemIndex?.toInt(),
                firstVisibleItemOffset = it.firstVisibleItemOffset?.toInt(),
                lastVisitTime = it.lastVisitTime
            )
        }
    }

    private val mangaTagQueries = db.mangaTagReadingHistoryQueries
    private val tagCatalogQueries = db.tagCatalogReadingHistoryQueries

    override suspend fun saveTagMangaReaderModeHistory(history: ReadHistoryRepository.TagMangaReadingHistory) {
        saveTagMangaReaderModeHistorySynchronously(history)
    }

    override fun saveTagMangaReaderModeHistorySynchronously(
        history: ReadHistoryRepository.TagMangaReadingHistory,
    ) {
        mangaTagQueries.upsert(
            tagId = history.tagId.value.toLong(),
            tagName = history.tagName,
            tagPage = history.tagPage.toLong(),
            threadId = history.threadId.value.toLong(),
            threadTitle = history.threadTitle,
            threadImagePageIndex = history.threadImagePageIndex.toLong(),
            threadImageTotalPages = history.threadImageTotalPages.toLong(),
            firstVisibleItemIndex = history.firstVisibleItemIndex?.toLong(),
            firstVisibleItemOffset = history.firstVisibleItemOffset?.toLong(),
            lastVisitTime = history.lastVisitTime,
            coverUrl = history.coverUrl
        )
        mangaTagQueries.trimToLatest(MAX_HISTORY_ITEMS)
    }

    override suspend fun getTagMangaReaderModeHistoryPosition(tagId: TagId): ReadHistoryRepository.TagMangaReadingHistory? {
        val history = mangaTagQueries.getByTagId(tagId.value.toLong()).executeAsOneOrNull()
        return history?.let {
            ReadHistoryRepository.TagMangaReadingHistory(
                tagId = TagId(it.tagId.toInt()),
                tagName = it.tagName,
                tagPage = it.tagPage.toInt(),
                threadId = ThreadId(it.threadId.toInt()),
                threadTitle = it.threadTitle,
                threadImagePageIndex = it.threadImagePageIndex.toInt(),
                threadImageTotalPages = it.threadImageTotalPages.toInt(),
                firstVisibleItemIndex = it.firstVisibleItemIndex?.toInt(),
                firstVisibleItemOffset = it.firstVisibleItemOffset?.toInt(),
                lastVisitTime = it.lastVisitTime,
                coverUrl = it.coverUrl
            )
        }
    }

    override suspend fun deleteMangaTagHistory(tagId: TagId) {
        deleteMangaTagHistorySynchronously(tagId)
    }

    override fun deleteMangaTagHistorySynchronously(tagId: TagId) {
        mangaTagQueries.deleteByTagId(tagId.value.toLong())
    }

    override suspend fun saveTagCatalogThreadHistory(history: ReadHistoryRepository.TagCatalogReadingHistory) {
        saveTagCatalogThreadHistorySynchronously(history)
    }

    override fun saveTagCatalogThreadHistorySynchronously(
        history: ReadHistoryRepository.TagCatalogReadingHistory,
    ) {
        tagCatalogQueries.upsert(
            tagId = history.tagId.value.toLong(),
            tagName = history.tagName,
            tagPage = history.tagPage.toLong(),
            threadId = history.threadId.value.toLong(),
            threadTitle = history.threadTitle,
            threadPage = history.threadPage.toLong(),
            postId = history.postId.value.toLong(),
            postTitle = history.postTitle,
            authorId = history.authorId?.value?.toLong(),
            anchorPostId = history.anchorPostId,
            anchorPostRatio = history.anchorPostRatio?.toDouble(),
            anchorBlockId = history.anchorBlockId,
            anchorBlockType = history.anchorBlockType,
            anchorBlockRatio = history.anchorBlockRatio?.toDouble(),
            viewportHeight = history.viewportHeight?.toLong(),
            firstVisibleItemIndex = history.firstVisibleItemIndex?.toLong(),
            firstVisibleItemOffset = history.firstVisibleItemOffset?.toLong(),
            lastVisitTime = history.lastVisitTime,
            coverUrl = history.coverUrl,
        )
        tagCatalogQueries.trimToLatest(MAX_HISTORY_ITEMS)
    }

    override suspend fun getTagCatalogThreadHistoryPosition(tagId: TagId): ReadHistoryRepository.TagCatalogReadingHistory? {
        return tagCatalogQueries.getByTagId(tagId.value.toLong()).executeAsOneOrNull()?.toHistory()
    }

    override suspend fun deleteTagCatalogThreadHistory(tagId: TagId) {
        deleteTagCatalogThreadHistorySynchronously(tagId)
    }

    override fun deleteTagCatalogThreadHistorySynchronously(tagId: TagId) {
        tagCatalogQueries.getByTagId(tagId.value.toLong()).executeAsOneOrNull()?.let { history ->
            queries.deleteByThreadOrigin(
                threadId = history.threadId,
                threadType = ReadHistoryRepository.ThreadEntryType.Normal.name,
                authorId = history.authorId ?: 0L,
                historyOrigin = ReadHistoryRepository.ThreadHistoryOrigin.TagCatalog.name,
            )
        }
        tagCatalogQueries.deleteByTagId(tagId.value.toLong())
    }

    private val rssSearchQueries = db.rssSearchReadingHistoryQueries
    private val rssCatalogQueries = db.rssCatalogReadingHistoryQueries

    override suspend fun saveRssSearchReaderModeHistory(history: ReadHistoryRepository.RssSearchReadingHistory) {
        saveRssSearchReaderModeHistorySynchronously(history)
    }

    override fun saveRssSearchReaderModeHistorySynchronously(
        history: ReadHistoryRepository.RssSearchReadingHistory,
    ) {
        rssSearchQueries.upsert(
            subscriptionId = history.subscriptionId,
            subscriptionTitle = history.subscriptionTitle,
            subscriptionQuery = history.subscriptionQuery,
            subscriptionPage = history.subscriptionPage.toLong(),
            threadId = history.threadId.value.toLong(),
            threadTitle = history.threadTitle,
            threadImagePageIndex = history.threadImagePageIndex.toLong(),
            threadImageTotalPages = history.threadImageTotalPages.toLong(),
            firstVisibleItemIndex = history.firstVisibleItemIndex?.toLong(),
            firstVisibleItemOffset = history.firstVisibleItemOffset?.toLong(),
            lastVisitTime = history.lastVisitTime,
            coverUrl = history.coverUrl,
        )
        rssSearchQueries.trimToLatest(MAX_HISTORY_ITEMS)
    }

    override suspend fun getRssSearchReaderModeHistoryPosition(subscriptionId: Long): ReadHistoryRepository.RssSearchReadingHistory? {
        return rssSearchQueries.getBySubscriptionId(subscriptionId).executeAsOneOrNull()?.toHistory()
    }

    override suspend fun deleteRssSearchHistory(subscriptionId: Long) {
        deleteRssSearchHistorySynchronously(subscriptionId)
    }

    override fun deleteRssSearchHistorySynchronously(subscriptionId: Long) {
        rssSearchQueries.deleteBySubscriptionId(subscriptionId)
    }

    override suspend fun saveRssCatalogThreadHistory(history: ReadHistoryRepository.RssCatalogReadingHistory) {
        saveRssCatalogThreadHistorySynchronously(history)
    }

    override fun saveRssCatalogThreadHistorySynchronously(
        history: ReadHistoryRepository.RssCatalogReadingHistory,
    ) {
        rssCatalogQueries.upsert(
            subscriptionId = history.subscriptionId,
            subscriptionTitle = history.subscriptionTitle,
            subscriptionQuery = history.subscriptionQuery,
            subscriptionPage = history.subscriptionPage.toLong(),
            threadId = history.threadId.value.toLong(),
            threadTitle = history.threadTitle,
            threadPage = history.threadPage.toLong(),
            postId = history.postId.value.toLong(),
            postTitle = history.postTitle,
            authorId = history.authorId?.value?.toLong(),
            anchorPostId = history.anchorPostId,
            anchorPostRatio = history.anchorPostRatio?.toDouble(),
            anchorBlockId = history.anchorBlockId,
            anchorBlockType = history.anchorBlockType,
            anchorBlockRatio = history.anchorBlockRatio?.toDouble(),
            viewportHeight = history.viewportHeight?.toLong(),
            firstVisibleItemIndex = history.firstVisibleItemIndex?.toLong(),
            firstVisibleItemOffset = history.firstVisibleItemOffset?.toLong(),
            lastVisitTime = history.lastVisitTime,
            coverUrl = history.coverUrl,
        )
        rssCatalogQueries.trimToLatest(MAX_HISTORY_ITEMS)
    }

    override suspend fun getRssCatalogThreadHistoryPosition(subscriptionId: Long): ReadHistoryRepository.RssCatalogReadingHistory? {
        return rssCatalogQueries.getBySubscriptionId(subscriptionId).executeAsOneOrNull()?.toHistory()
    }

    override suspend fun deleteRssCatalogThreadHistory(subscriptionId: Long) {
        deleteRssCatalogThreadHistorySynchronously(subscriptionId)
    }

    override fun deleteRssCatalogThreadHistorySynchronously(subscriptionId: Long) {
        rssCatalogQueries.getBySubscriptionId(subscriptionId).executeAsOneOrNull()?.let { history ->
            queries.deleteByThreadOrigin(
                threadId = history.threadId,
                threadType = ReadHistoryRepository.ThreadEntryType.Normal.name,
                authorId = history.authorId ?: 0L,
                historyOrigin = ReadHistoryRepository.ThreadHistoryOrigin.RssCatalog.name,
            )
        }
        rssCatalogQueries.deleteBySubscriptionId(subscriptionId)
    }

    private fun RssSearchReadingHistory.toHistory(): ReadHistoryRepository.RssSearchReadingHistory {
        return ReadHistoryRepository.RssSearchReadingHistory(
            subscriptionId = subscriptionId,
            subscriptionTitle = subscriptionTitle,
            subscriptionQuery = subscriptionQuery,
            subscriptionPage = subscriptionPage.toInt(),
            threadId = ThreadId(threadId.toInt()),
            threadTitle = threadTitle,
            threadImagePageIndex = threadImagePageIndex.toInt(),
            threadImageTotalPages = threadImageTotalPages.toInt(),
            firstVisibleItemIndex = firstVisibleItemIndex?.toInt(),
            firstVisibleItemOffset = firstVisibleItemOffset?.toInt(),
            lastVisitTime = lastVisitTime,
            coverUrl = coverUrl,
        )
    }

    private fun RssCatalogReadingHistory.toHistory(): ReadHistoryRepository.RssCatalogReadingHistory {
        return ReadHistoryRepository.RssCatalogReadingHistory(
            subscriptionId = subscriptionId,
            subscriptionTitle = subscriptionTitle,
            subscriptionQuery = subscriptionQuery,
            subscriptionPage = subscriptionPage.toInt(),
            threadId = ThreadId(threadId.toInt()),
            threadTitle = threadTitle,
            threadPage = threadPage.toInt(),
            postId = PostId(postId.toInt()),
            postTitle = postTitle,
            authorId = authorId?.toInt()?.let { UserId(it) },
            anchorPostId = anchorPostId.takeIf { it != 0L } ?: postId,
            anchorPostRatio = anchorPostRatio?.toFloat(),
            anchorBlockId = anchorBlockId,
            anchorBlockType = anchorBlockType,
            anchorBlockRatio = anchorBlockRatio?.toFloat(),
            viewportHeight = viewportHeight?.toInt(),
            firstVisibleItemIndex = firstVisibleItemIndex?.toInt(),
            firstVisibleItemOffset = firstVisibleItemOffset?.toInt(),
            lastVisitTime = lastVisitTime,
            coverUrl = coverUrl,
        )
    }

    private fun MangaTagReadingHistory.toHistory(): ReadHistoryRepository.TagMangaReadingHistory {
        return ReadHistoryRepository.TagMangaReadingHistory(
            tagId = TagId(tagId.toInt()),
            tagName = tagName,
            tagPage = tagPage.toInt(),
            threadId = ThreadId(threadId.toInt()),
            threadTitle = threadTitle,
            threadImagePageIndex = threadImagePageIndex.toInt(),
            threadImageTotalPages = threadImageTotalPages.toInt(),
            firstVisibleItemIndex = firstVisibleItemIndex?.toInt(),
            firstVisibleItemOffset = firstVisibleItemOffset?.toInt(),
            lastVisitTime = lastVisitTime,
            coverUrl = coverUrl
        )
    }

    private fun TagCatalogReadingHistory.toHistory(): ReadHistoryRepository.TagCatalogReadingHistory {
        return ReadHistoryRepository.TagCatalogReadingHistory(
            tagId = TagId(tagId.toInt()),
            tagName = tagName,
            tagPage = tagPage.toInt(),
            threadId = ThreadId(threadId.toInt()),
            threadTitle = threadTitle,
            threadPage = threadPage.toInt(),
            postId = PostId(postId.toInt()),
            postTitle = postTitle,
            authorId = authorId?.toInt()?.let { UserId(it) },
            anchorPostId = anchorPostId.takeIf { it != 0L } ?: postId,
            anchorPostRatio = anchorPostRatio?.toFloat(),
            anchorBlockId = anchorBlockId,
            anchorBlockType = anchorBlockType,
            anchorBlockRatio = anchorBlockRatio?.toFloat(),
            viewportHeight = viewportHeight?.toInt(),
            firstVisibleItemIndex = firstVisibleItemIndex?.toInt(),
            firstVisibleItemOffset = firstVisibleItemOffset?.toInt(),
            lastVisitTime = lastVisitTime,
            coverUrl = coverUrl,
        )
    }

    override suspend fun getCombinedHistoryPage(
        page: Int,
        pageSize: Int
    ): List<ReadHistoryRepository.AnyReadingHistory> {
        val offset = (page - 1).coerceAtLeast(0) * pageSize
        val threads = loadAllThreadHistory()
        val tags = loadLatestTagCatalogHistory()
        val rss = loadLatestRssCatalogHistory()
        return (threads + tags + rss)
            .sortedByDescending { it.lastVisitTime }
            .drop(offset)
            .take(pageSize)
    }

    override suspend fun getCombinedHistoryCount(): Long {
        return queries.countAll().executeAsOne() +
            loadLatestTagCatalogHistory().size.toLong() +
            loadLatestRssCatalogHistory().size.toLong()
    }

    override suspend fun getCombinedHistoryPageByFilter(
        filter: ReadHistoryRepository.HistoryFilter,
        page: Int,
        pageSize: Int
    ): List<ReadHistoryRepository.AnyReadingHistory> {
        val offset = (page - 1).coerceAtLeast(0) * pageSize
        return when (filter) {
            ReadHistoryRepository.HistoryFilter.All -> getCombinedHistoryPage(page, pageSize)
            is ReadHistoryRepository.HistoryFilter.Forum -> queries.getPageByForumId(
                filter.forumId.value.toLong(),
                pageSize.toLong(),
                offset.toLong(),
            ).executeAsList().map { it.toHistory() }
            ReadHistoryRepository.HistoryFilter.Tag -> loadLatestTagCatalogHistory()
                .drop(offset)
                .take(pageSize)
            ReadHistoryRepository.HistoryFilter.Rss -> loadLatestRssCatalogHistory()
                .drop(offset)
                .take(pageSize)
        }
    }

    override suspend fun getCombinedHistoryCountByFilter(filter: ReadHistoryRepository.HistoryFilter): Long {
        return when (filter) {
            ReadHistoryRepository.HistoryFilter.All -> getCombinedHistoryCount()
            is ReadHistoryRepository.HistoryFilter.Forum -> queries.countByForumId(filter.forumId.value.toLong()).executeAsOne()
            ReadHistoryRepository.HistoryFilter.Tag -> loadLatestTagCatalogHistory().size.toLong()
            ReadHistoryRepository.HistoryFilter.Rss -> loadLatestRssCatalogHistory().size.toLong()
        }
    }

    override suspend fun getCombinedHistoryFilterCounts(): List<ReadHistoryRepository.HistoryFilterCount> {
        val forumCounts = queries.getForumCounts().executeAsList().map { row ->
            val forumId = row.forumId
            ReadHistoryRepository.HistoryFilterCount(
                filter = ReadHistoryRepository.HistoryFilter.Forum(ForumId(forumId.toInt())),
                label = row.forumName?.takeIf { it.isNotBlank() } ?: "Forum $forumId",
                count = row.count,
            )
        }
        val tagCount = loadLatestTagCatalogHistory().size.toLong()
        val rssCount = loadLatestRssCatalogHistory().size.toLong()
        return buildList {
            add(
                ReadHistoryRepository.HistoryFilterCount(
                    filter = ReadHistoryRepository.HistoryFilter.All,
                    label = i18n("全部"),
                    count = getCombinedHistoryCount(),
                )
            )
            addAll(forumCounts)
            if (tagCount > 0L) {
                add(
                    ReadHistoryRepository.HistoryFilterCount(
                        filter = ReadHistoryRepository.HistoryFilter.Tag,
                        label = "Tag",
                        count = tagCount,
                    )
                )
            }
            if (rssCount > 0L) {
                add(
                    ReadHistoryRepository.HistoryFilterCount(
                        filter = ReadHistoryRepository.HistoryFilter.Rss,
                        label = "RSS",
                        count = rssCount,
                    )
                )
            }
        }
    }

    override suspend fun searchCombinedHistory(
        query: String,
        page: Int,
        pageSize: Int
    ): List<ReadHistoryRepository.AnyReadingHistory> {
        val offset = (page - 1).coerceAtLeast(0) * pageSize
        val threads = loadAllThreadHistory(query)
        val tags = loadLatestTagCatalogHistory(query)
        val rss = loadLatestRssCatalogHistory(query)
        return (threads + tags + rss)
            .sortedByDescending { it.lastVisitTime }
            .drop(offset)
            .take(pageSize)
    }

    override suspend fun searchCombinedHistoryCount(query: String): Long {
        return queries.countByName(query).executeAsOne() +
            loadLatestTagCatalogHistory(query).size.toLong() +
            loadLatestRssCatalogHistory(query).size.toLong()
    }

    override suspend fun deleteCombinedHistoryBatch(items: List<ReadHistoryRepository.AnyReadingHistory>) {
        deleteCombinedHistoryBatchSynchronously(items)
    }

    override fun deleteCombinedHistoryBatchSynchronously(items: List<ReadHistoryRepository.AnyReadingHistory>) {
        val mangaTagIds = items.filterIsInstance<ReadHistoryRepository.TagMangaReadingHistory>()
            .map { it.tagId.value.toLong() }.distinct()
        val tagCatalogIds = items.filterIsInstance<ReadHistoryRepository.TagCatalogReadingHistory>()
            .map { it.tagId.value.toLong() }.distinct()
        val rssSearchIds = items.filterIsInstance<ReadHistoryRepository.RssSearchReadingHistory>()
            .map { it.subscriptionId }.distinct()
        val rssCatalogIds = items.filterIsInstance<ReadHistoryRepository.RssCatalogReadingHistory>()
            .map { it.subscriptionId }.distinct()

        items.filterIsInstance<ReadHistoryRepository.ThreadReadingHistory>().forEach { item ->
            queries.deleteByThreadKey(
                threadId = item.threadId.value.toLong(),
                threadType = item.threadType.name,
                authorId = item.authorId?.value?.toLong() ?: 0L
            )
        }
        items.filterIsInstance<ReadHistoryRepository.ImageReadingHistory>().forEach {
            imageQueries.deleteByPostId(it.postId.value.toLong())
        }
        if (mangaTagIds.isNotEmpty()) mangaTagQueries.deleteByTagIds(mangaTagIds)
        if (tagCatalogIds.isNotEmpty()) tagCatalogQueries.deleteByTagIds(tagCatalogIds)
        if (rssSearchIds.isNotEmpty()) rssSearchQueries.deleteBySubscriptionIds(rssSearchIds)
        if (rssCatalogIds.isNotEmpty()) rssCatalogQueries.deleteBySubscriptionIds(rssCatalogIds)
    }

    override suspend fun deleteAllCombinedHistory() {
        deleteAllCombinedHistorySynchronously()
    }

    override fun deleteAllCombinedHistorySynchronously() {
        queries.deleteAll()
        imageQueries.deleteAll()
        mangaTagQueries.deleteAll()
        tagCatalogQueries.deleteAll()
        rssSearchQueries.deleteAll()
        rssCatalogQueries.deleteAll()
    }

    override suspend fun getAllImageHistoryForSync() = imageQueries.getAll().executeAsList().map {
        ReadHistoryRepository.ImageReadingHistory(
            postId = PostId(it.postId.toInt()),
            threadId = ThreadId(it.threadId.toInt()),
            pageIndex = it.pageIndex.toInt(),
            totalPages = it.totalPages.toInt(),
            firstVisibleItemIndex = it.firstVisibleItemIndex?.toInt(),
            firstVisibleItemOffset = it.firstVisibleItemOffset?.toInt(),
            lastVisitTime = it.lastVisitTime,
        )
    }

    override suspend fun getAllTagMangaHistoryForSync() =
        mangaTagQueries.getAll().executeAsList().map { it.toHistory() }

    override suspend fun getAllTagCatalogHistoryForSync() =
        tagCatalogQueries.getAll().executeAsList().map { it.toHistory() }

    override suspend fun getAllRssSearchHistoryForSync() =
        rssSearchQueries.getAll().executeAsList().map { it.toHistory() }

    override suspend fun getAllRssCatalogHistoryForSync() =
        rssCatalogQueries.getAll().executeAsList().map { it.toHistory() }

    override suspend fun getRssSubscriptionSyncId(subscriptionId: Long): String? =
        db.rssSearchSubscriptionQueries.getById(subscriptionId).executeAsOneOrNull()?.let {
            rssSearchSubscriptionSyncId(it.query, it.forumId)
        }

    override suspend fun recordReadingDuration(dateKey: String, durationMillis: Long) {
        recordReadingDurationSynchronously(dateKey, durationMillis)
    }

    override fun recordReadingDurationSynchronously(dateKey: String, durationMillis: Long) {
        if (durationMillis <= 0L) return
        readingTimeQueries.addDuration(dateKey, durationMillis, currentTimeMillis())
    }

    override suspend fun getReadingDurationDays(
        startDateKey: String,
        endDateKey: String,
    ): List<ReadHistoryRepository.ReadingDurationDay> {
        return readingTimeQueries.getRange(startDateKey, endDateKey)
            .executeAsList()
            .map {
                ReadHistoryRepository.ReadingDurationDay(
                    dateKey = it.dateKey,
                    durationMillis = it.durationMillis,
                )
            }
    }

    override suspend fun getReadingDurationTotal(startDateKey: String, endDateKey: String): Long {
        return readingTimeQueries.getTotalDuration(startDateKey, endDateKey).executeAsOne()
    }

    private fun loadAllThreadHistory(query: String? = null): List<ReadHistoryRepository.ThreadReadingHistory> {
        val histories = mutableListOf<ReadHistoryRepository.ThreadReadingHistory>()
        var offset = 0L

        while (true) {
            val batch = if (query.isNullOrBlank()) {
                queries.getPage(COMBINED_HISTORY_BATCH_SIZE, offset).executeAsList()
            } else {
                queries.searchByName(query, COMBINED_HISTORY_BATCH_SIZE, offset).executeAsList()
            }

            if (batch.isEmpty()) break
            histories += batch.map { it.toHistory() }
            if (batch.size < COMBINED_HISTORY_BATCH_SIZE.toInt()) break
            offset += batch.size
        }

        return histories
    }

    private fun loadAllTagHistory(query: String? = null): List<ReadHistoryRepository.TagMangaReadingHistory> {
        val histories = mutableListOf<ReadHistoryRepository.TagMangaReadingHistory>()
        var offset = 0L

        while (true) {
            val batch = if (query.isNullOrBlank()) {
                mangaTagQueries.getPage(COMBINED_HISTORY_BATCH_SIZE, offset).executeAsList()
            } else {
                mangaTagQueries.searchByName(query, query, COMBINED_HISTORY_BATCH_SIZE, offset).executeAsList()
            }

            if (batch.isEmpty()) break
            histories += batch.map { it.toHistory() }
            if (batch.size < COMBINED_HISTORY_BATCH_SIZE.toInt()) break
            offset += batch.size
        }

        return histories
    }

    private fun loadAllTagCatalogHistory(query: String? = null): List<ReadHistoryRepository.TagCatalogReadingHistory> {
        val histories = mutableListOf<ReadHistoryRepository.TagCatalogReadingHistory>()
        var offset = 0L

        while (true) {
            val batch = if (query.isNullOrBlank()) {
                tagCatalogQueries.getPage(COMBINED_HISTORY_BATCH_SIZE, offset).executeAsList()
            } else {
                tagCatalogQueries.searchByName(query, query, COMBINED_HISTORY_BATCH_SIZE, offset).executeAsList()
            }

            if (batch.isEmpty()) break
            histories += batch.map { it.toHistory() }
            if (batch.size < COMBINED_HISTORY_BATCH_SIZE.toInt()) break
            offset += batch.size
        }

        return histories
    }

    private fun loadLatestTagCatalogHistory(query: String? = null): List<ReadHistoryRepository.AnyReadingHistory> {
        return (loadAllTagHistory(query) + loadAllTagCatalogHistory(query)).latestByCatalogKey()
    }

    private fun loadAllRssSearchHistory(query: String? = null): List<ReadHistoryRepository.RssSearchReadingHistory> {
        val histories = mutableListOf<ReadHistoryRepository.RssSearchReadingHistory>()
        var offset = 0L

        while (true) {
            val batch = if (query.isNullOrBlank()) {
                rssSearchQueries.getPage(COMBINED_HISTORY_BATCH_SIZE, offset).executeAsList()
            } else {
                rssSearchQueries.searchByName(query, query, query, COMBINED_HISTORY_BATCH_SIZE, offset).executeAsList()
            }

            if (batch.isEmpty()) break
            histories += batch.map { it.toHistory() }
            if (batch.size < COMBINED_HISTORY_BATCH_SIZE.toInt()) break
            offset += batch.size
        }

        return histories
    }

    private fun loadAllRssCatalogHistory(query: String? = null): List<ReadHistoryRepository.RssCatalogReadingHistory> {
        val histories = mutableListOf<ReadHistoryRepository.RssCatalogReadingHistory>()
        var offset = 0L

        while (true) {
            val batch = if (query.isNullOrBlank()) {
                rssCatalogQueries.getPage(COMBINED_HISTORY_BATCH_SIZE, offset).executeAsList()
            } else {
                rssCatalogQueries.searchByName(query, query, query, COMBINED_HISTORY_BATCH_SIZE, offset).executeAsList()
            }

            if (batch.isEmpty()) break
            histories += batch.map { it.toHistory() }
            if (batch.size < COMBINED_HISTORY_BATCH_SIZE.toInt()) break
            offset += batch.size
        }

        return histories
    }

    private fun loadLatestRssCatalogHistory(query: String? = null): List<ReadHistoryRepository.AnyReadingHistory> {
        return (loadAllRssSearchHistory(query) + loadAllRssCatalogHistory(query)).latestByCatalogKey()
    }

    private fun List<ReadHistoryRepository.AnyReadingHistory>.latestByCatalogKey(): List<ReadHistoryRepository.AnyReadingHistory> {
        return groupBy { history ->
            when (history) {
                is ReadHistoryRepository.TagMangaReadingHistory -> "tag:${history.tagId.value}"
                is ReadHistoryRepository.TagCatalogReadingHistory -> "tag:${history.tagId.value}"
                is ReadHistoryRepository.RssSearchReadingHistory -> "rss:${history.subscriptionId}"
                is ReadHistoryRepository.RssCatalogReadingHistory -> "rss:${history.subscriptionId}"
                else -> "other:${history.lastVisitTime}"
            }
        }.values
            .mapNotNull { it.maxByOrNull(ReadHistoryRepository.AnyReadingHistory::lastVisitTime) }
            .sortedByDescending { it.lastVisitTime }
    }
}

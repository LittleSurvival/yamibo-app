package me.thenano.yamibo.yamibo_app.repository

import io.github.littlesurvival.dto.value.ForumId
import io.github.littlesurvival.dto.value.PostId
import io.github.littlesurvival.dto.value.TagId
import io.github.littlesurvival.dto.value.ThreadId
import io.github.littlesurvival.dto.value.UserId

/**
 * Repository for tracking reading history and precise scroll positions.
 *
 * Uses content-anchor positioning for accurate reading position restoration.
 */
interface ReadHistoryRepository {

    /** Common wrapper/interface for merging multiple types of reading history */
    sealed interface AnyReadingHistory {
        val lastVisitTime: Long
    }

    sealed interface HistoryFilter {
        data object All : HistoryFilter
        data class Forum(val forumId: ForumId) : HistoryFilter
        data object Tag : HistoryFilter
        data object Rss : HistoryFilter
    }

    data class HistoryFilterCount(
        val filter: HistoryFilter,
        val label: String,
        val count: Long,
    )

    data class ReadingDurationDay(
        val dateKey: String,
        val durationMillis: Long,
    )

    enum class ThreadEntryType {
        Normal,
        Novel;

        companion object {
            fun fromStorage(value: String?): ThreadEntryType {
                return entries.firstOrNull { it.name == value } ?: Normal
            }
        }
    }

    enum class ThreadHistoryOrigin {
        Direct,
        TagCatalog,
        RssCatalog;

        companion object {
            fun fromStorage(value: String?): ThreadHistoryOrigin {
                return entries.firstOrNull { it.name == value } ?: Direct
            }
        }
    }

    /** Full reading history entry with anchor-based positioning data */
    data class ThreadReadingHistory(
        val threadType: ThreadEntryType,
        val threadName: String,
        val threadId: ThreadId,
        val threadCover: String?,
        val lastUpdatedTime: Long?,
        val forumName: String?,
        val forumId: ForumId?,
        val authorId: UserId?,
        val page: Int,
        val postId: PostId,
        val postTitle: String,

        val anchorPostId: Long,
        val anchorPostRatio: Float? = null,

        val anchorBlockId: String? = null,
        val anchorBlockType: String? = null,
        val anchorBlockRatio: Float? = null,

        val globalScrollY: Int? = null,
        val viewportHeight: Int? = null,
        val firstVisibleItemIndex: Int? = null,
        val firstVisibleItemOffset: Int? = null,
        val historyOrigin: ThreadHistoryOrigin = ThreadHistoryOrigin.Direct,

        override val lastVisitTime: Long,
    ) : AnyReadingHistory

    /** Save or update a reading position */
    suspend fun savePosition(history: ThreadReadingHistory)

    /** Get saved position for a given thread */
    suspend fun getPosition(
        tid: ThreadId,
        threadType: ThreadEntryType,
        authorId: UserId? = null,
    ): ThreadReadingHistory?

    /** Get a page of history entries (newest first) */
    suspend fun getHistoryPage(page: Int, pageSize: Int = 20): List<ThreadReadingHistory>

    /** Total number of history entries */
    suspend fun getHistoryCount(): Long

    /** Delete a single history entry */
    suspend fun deleteHistory(
        tid: ThreadId,
        threadType: ThreadEntryType,
        authorId: UserId? = null,
    )

    /** Delete all history entries */
    suspend fun deleteAll()

    /** Search history entries by thread name (newest first) */
    suspend fun searchHistory(query: String, page: Int, pageSize: Int = 20): List<ThreadReadingHistory>

    /** Count search results */
    suspend fun searchHistoryCount(query: String): Long

    /** Delete multiple history entries by thread IDs */
    suspend fun deleteHistoryBatch(items: List<ThreadReadingHistory>)

    // Combined History Queries

    /** Get a combined page of history entries mixing Thread and TagManga (newest first) */
    suspend fun getCombinedHistoryPage(page: Int, pageSize: Int = 20): List<AnyReadingHistory>

    /** Total number of combined history entries */
    suspend fun getCombinedHistoryCount(): Long

    suspend fun getCombinedHistoryPageByFilter(
        filter: HistoryFilter,
        page: Int,
        pageSize: Int = 20,
    ): List<AnyReadingHistory>

    suspend fun getCombinedHistoryCountByFilter(filter: HistoryFilter): Long

    suspend fun getCombinedHistoryFilterCounts(): List<HistoryFilterCount>

    suspend fun getCombinedHistoryPageByFilters(
        filters: Set<HistoryFilter>,
        page: Int,
        pageSize: Int = 20,
    ): List<AnyReadingHistory> {
        val normalized = filters.filterNot { it == HistoryFilter.All }.toSet()
        if (normalized.isEmpty()) return getCombinedHistoryPage(page, pageSize)
        val offset = (page - 1).coerceAtLeast(0) * pageSize
        val items = mutableListOf<AnyReadingHistory>()
        for (filter in normalized) {
            val count = getCombinedHistoryCountByFilter(filter).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            if (count > 0) {
                items += getCombinedHistoryPageByFilter(filter, page = 1, pageSize = count)
            }
        }
        return items
            .sortedByDescending { it.lastVisitTime }
            .drop(offset)
            .take(pageSize)
    }

    suspend fun getCombinedHistoryCountByFilters(filters: Set<HistoryFilter>): Long {
        val normalized = filters.filterNot { it == HistoryFilter.All }.toSet()
        if (normalized.isEmpty()) return getCombinedHistoryCount()
        var total = 0L
        for (filter in normalized) {
            total += getCombinedHistoryCountByFilter(filter)
        }
        return total
    }

    /** Search combined history entries by title/tag (newest first) */
    suspend fun searchCombinedHistory(query: String, page: Int, pageSize: Int = 20): List<AnyReadingHistory>

    /** Count combined search results */
    suspend fun searchCombinedHistoryCount(query: String): Long

    /** Batch delete combined history entries (based on their type-specific IDs) */
    suspend fun deleteCombinedHistoryBatch(items: List<AnyReadingHistory>)

    /** Delete all history entries across all types */
    suspend fun deleteAllCombinedHistory()

    suspend fun recordReadingDuration(dateKey: String, durationMillis: Long)

    suspend fun getReadingDurationDays(startDateKey: String, endDateKey: String): List<ReadingDurationDay>

    suspend fun getReadingDurationTotal(startDateKey: String, endDateKey: String): Long

    /** Full reading history entry specifically for image posts (manga forum) */
    data class ImageReadingHistory(
        val postId: PostId,
        val threadId: ThreadId,
        val pageIndex: Int,
        val totalPages: Int,
        val firstVisibleItemIndex: Int? = null,
        val firstVisibleItemOffset: Int? = null,
        override val lastVisitTime: Long
    ) : AnyReadingHistory

    /** Save or update a position for an image reading session */
    suspend fun saveImagePosition(history: ImageReadingHistory)

    /** Get saved position for a given post ID */
    suspend fun getImagePosition(postId: PostId): ImageReadingHistory?

    /** Reading history entry for manga tag reading mode (keyed by tagId) */
    data class TagMangaReadingHistory(
        val tagId: TagId,
        val tagName: String,
        val tagPage: Int,
        val threadId: ThreadId,
        val threadTitle: String,
        val threadImagePageIndex: Int,
        val threadImageTotalPages: Int,
        val firstVisibleItemIndex: Int? = null,
        val firstVisibleItemOffset: Int? = null,
        override val lastVisitTime: Long,
        val coverUrl: String? = null
    ) : AnyReadingHistory

    /** Non-manga catalog reading history for a tag detail thread entry. */
    data class TagCatalogReadingHistory(
        val tagId: TagId,
        val tagName: String,
        val tagPage: Int,
        val threadId: ThreadId,
        val threadTitle: String,
        val threadPage: Int,
        val postId: PostId,
        val postTitle: String,
        val authorId: UserId? = null,
        val anchorPostId: Long = postId.value.toLong(),
        val anchorPostRatio: Float? = null,
        val anchorBlockId: String? = null,
        val anchorBlockType: String? = null,
        val anchorBlockRatio: Float? = null,
        val viewportHeight: Int? = null,
        val firstVisibleItemIndex: Int? = null,
        val firstVisibleItemOffset: Int? = null,
        override val lastVisitTime: Long,
        val coverUrl: String? = null,
    ) : AnyReadingHistory

    /** Save or update manga tag reading position */
    suspend fun saveTagMangaReaderModeHistory(history: TagMangaReadingHistory)

    /** Get saved manga tag position for a given tag ID */
    suspend fun getTagMangaReaderModeHistoryPosition(tagId: TagId): TagMangaReadingHistory?

    /** Delete manga tag reading history */
    suspend fun deleteMangaTagHistory(tagId: TagId)

    suspend fun saveTagCatalogThreadHistory(history: TagCatalogReadingHistory)

    suspend fun getTagCatalogThreadHistoryPosition(tagId: TagId): TagCatalogReadingHistory?

    suspend fun deleteTagCatalogThreadHistory(tagId: TagId)

    /** Reading history entry for RSS search catalog reading mode (keyed by subscriptionId) */
    data class RssSearchReadingHistory(
        val subscriptionId: Long,
        val subscriptionTitle: String,
        val subscriptionQuery: String,
        val subscriptionPage: Int,
        val threadId: ThreadId,
        val threadTitle: String,
        val threadImagePageIndex: Int,
        val threadImageTotalPages: Int,
        val firstVisibleItemIndex: Int? = null,
        val firstVisibleItemOffset: Int? = null,
        override val lastVisitTime: Long,
        val coverUrl: String? = null,
    ) : AnyReadingHistory

    data class RssCatalogReadingHistory(
        val subscriptionId: Long,
        val subscriptionTitle: String,
        val subscriptionQuery: String,
        val subscriptionPage: Int,
        val threadId: ThreadId,
        val threadTitle: String,
        val threadPage: Int,
        val postId: PostId,
        val postTitle: String,
        val authorId: UserId? = null,
        val anchorPostId: Long = postId.value.toLong(),
        val anchorPostRatio: Float? = null,
        val anchorBlockId: String? = null,
        val anchorBlockType: String? = null,
        val anchorBlockRatio: Float? = null,
        val viewportHeight: Int? = null,
        val firstVisibleItemIndex: Int? = null,
        val firstVisibleItemOffset: Int? = null,
        override val lastVisitTime: Long,
        val coverUrl: String? = null,
    ) : AnyReadingHistory

    suspend fun saveRssSearchReaderModeHistory(history: RssSearchReadingHistory)

    suspend fun getRssSearchReaderModeHistoryPosition(subscriptionId: Long): RssSearchReadingHistory?

    suspend fun deleteRssSearchHistory(subscriptionId: Long)

    suspend fun saveRssCatalogThreadHistory(history: RssCatalogReadingHistory)

    suspend fun getRssCatalogThreadHistoryPosition(subscriptionId: Long): RssCatalogReadingHistory?

    suspend fun deleteRssCatalogThreadHistory(subscriptionId: Long)

    /** Complete backing-table snapshots used to build atomic AppSync delete batches. */
    suspend fun getAllImageHistoryForSync(): List<ImageReadingHistory> = emptyList()
    suspend fun getAllTagMangaHistoryForSync(): List<TagMangaReadingHistory> = emptyList()
    suspend fun getAllTagCatalogHistoryForSync(): List<TagCatalogReadingHistory> = emptyList()
    suspend fun getAllRssSearchHistoryForSync(): List<RssSearchReadingHistory> = emptyList()
    suspend fun getAllRssCatalogHistoryForSync(): List<RssCatalogReadingHistory> = emptyList()

    /** Resolves a device-local RSS row id to the subscription's cloud identity. */
    suspend fun getRssSubscriptionSyncId(subscriptionId: Long): String? = null
}

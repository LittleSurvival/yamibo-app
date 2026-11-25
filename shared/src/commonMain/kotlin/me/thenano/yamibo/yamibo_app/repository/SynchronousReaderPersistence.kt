package me.thenano.yamibo.yamibo_app.repository

import me.thenano.yamibo.yamibo_app.Database

/**
 * SQLDelight mutation entry points used inside the AppSync transaction.
 *
 * Public callers continue to use [ReadHistoryRepository]'s suspend API. These methods exist so
 * the operation recorder can keep the local row and its AppSync operation atomic without bridging
 * a suspend call with runBlocking.
 */
internal interface SynchronousReaderPersistence {
    fun threadHistoryForDeletion(tid: io.github.littlesurvival.dto.value.ThreadId,
        type: ReadHistoryRepository.ThreadEntryType, author: io.github.littlesurvival.dto.value.UserId?): List<ReadHistoryRepository.ThreadReadingHistory>
    fun allThreadHistoryForDeletion(): List<ReadHistoryRepository.ThreadReadingHistory>
    /** Returns the previous visit time, including when a stale write was ignored. */
    fun savePositionSynchronously(history: ReadHistoryRepository.ThreadReadingHistory): Long?
    fun deleteHistorySynchronously(history: ReadHistoryRepository.ThreadReadingHistory)
    fun deleteHistoryBatchSynchronously(items: List<ReadHistoryRepository.ThreadReadingHistory>)
    fun deleteAllThreadHistorySynchronously()
    /** Returns the previous visit time from the same transaction as the position write. */
    fun saveImagePositionSynchronously(history: ReadHistoryRepository.ImageReadingHistory): Long?
    fun saveTagMangaReaderModeHistorySynchronously(history: ReadHistoryRepository.TagMangaReadingHistory)
    fun deleteMangaTagHistorySynchronously(tagId: io.github.littlesurvival.dto.value.TagId)

    fun saveTagCatalogThreadHistorySynchronously(
        history: ReadHistoryRepository.TagCatalogReadingHistory,
    )
    fun deleteTagCatalogThreadHistorySynchronously(tagId: io.github.littlesurvival.dto.value.TagId)

    fun saveRssSearchReaderModeHistorySynchronously(
        history: ReadHistoryRepository.RssSearchReadingHistory,
    )
    fun deleteRssSearchHistorySynchronously(subscriptionId: Long)

    fun saveRssCatalogThreadHistorySynchronously(
        history: ReadHistoryRepository.RssCatalogReadingHistory,
    )
    fun deleteRssCatalogThreadHistorySynchronously(subscriptionId: Long)
    fun deleteCombinedHistoryBatchSynchronously(items: List<ReadHistoryRepository.AnyReadingHistory>)
    fun deleteAllCombinedHistorySynchronously()
    fun recordReadingDurationSynchronously(dateKey: String, durationMillis: Long)
}

// Legacy writes may reuse the same millisecond as a precise position. Never attach that older detail to the new row.
internal fun Database.storeTextPositionDetail(tid: Long, type: String, scope: Long, origin: String,
    at: Long, author: Long?, source: String?, occurrence: Long?) {
    if (author == null) readingHistoryQueries.clearTextAnchorAt(tid, type, scope, origin, at)
    else readingHistoryQueries.saveTextAnchor(tid, type, scope, origin, at, author, source, occurrence)
}

internal fun Database.storeImagePositionDetail(pid: Long, at: Long, floor: Long?, source: String?,
    occurrence: Long?, page: Long?, author: Long?) {
    if (floor != null && source != null && occurrence != null && page != null) {
        imageReadingHistoryQueries.saveAnchor(pid, at, floor, source, occurrence, page, author)
    } else {
        imageReadingHistoryQueries.clearAnchorAt(pid, at)
    }
}

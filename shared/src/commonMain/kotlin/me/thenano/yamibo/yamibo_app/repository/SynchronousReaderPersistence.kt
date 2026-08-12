package me.thenano.yamibo.yamibo_app.repository

/**
 * SQLDelight mutation entry points used inside the AppSync transaction.
 *
 * Public callers continue to use [ReadHistoryRepository]'s suspend API. These methods exist so
 * the operation recorder can keep the local row and its AppSync operation atomic without bridging
 * a suspend call with runBlocking.
 */
internal interface SynchronousReaderPersistence {
    fun savePositionSynchronously(history: ReadHistoryRepository.ThreadReadingHistory)
    fun deleteHistorySynchronously(history: ReadHistoryRepository.ThreadReadingHistory)
    fun deleteHistoryBatchSynchronously(items: List<ReadHistoryRepository.ThreadReadingHistory>)
    fun deleteAllThreadHistorySynchronously()
    fun saveImagePositionSynchronously(history: ReadHistoryRepository.ImageReadingHistory)
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

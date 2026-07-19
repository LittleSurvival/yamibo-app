package me.thenano.yamibo.yamibo_app.history.components

import me.thenano.yamibo.yamibo_app.i18n.i18n

import me.thenano.yamibo.yamibo_app.repository.ReadHistoryRepository
import me.thenano.yamibo.yamibo_app.repository.ReadHistoryRepository.ThreadReadingHistory
import me.thenano.yamibo.yamibo_app.util.time.currentTimeMillis
import me.thenano.yamibo.yamibo_app.util.time.formatDate
import me.thenano.yamibo.yamibo_app.util.time.formatTime as sharedFormatTime

internal fun itemKey(history: ReadHistoryRepository.AnyReadingHistory): String {
    return when (history) {
        is ThreadReadingHistory -> "thread_${history.threadType}_${history.threadId.value}_${history.authorId?.value ?: 0}"
        is ReadHistoryRepository.TagMangaReadingHistory -> "tag_${history.tagId.value}_${history.threadId.value}"
        is ReadHistoryRepository.TagCatalogReadingHistory -> "tag_catalog_${history.tagId.value}_${history.threadId.value}"
        is ReadHistoryRepository.RssSearchReadingHistory -> "rss_${history.subscriptionId}_${history.threadId.value}"
        is ReadHistoryRepository.RssCatalogReadingHistory -> "rss_catalog_${history.subscriptionId}_${history.threadId.value}"
        else -> "history_${history.lastVisitTime}"
    }
}

internal fun groupByDate(
    items: List<ReadHistoryRepository.AnyReadingHistory>,
): List<Pair<String, List<ReadHistoryRepository.AnyReadingHistory>>> {
    val now = currentTimeMillis()
    val oneDayMs = 24 * 60 * 60 * 1000L
    val grouped = mutableMapOf<String, MutableList<ReadHistoryRepository.AnyReadingHistory>>()
    for (item in items) {
        val diffMs = now - item.lastVisitTime
        val label = when {
            diffMs < oneDayMs -> i18n("今天")
            diffMs < 2 * oneDayMs -> i18n("昨天")
            diffMs < 3 * oneDayMs -> i18n("前天")
            diffMs < 7 * oneDayMs -> i18n("{}天前", (diffMs / oneDayMs).toInt())
            else -> formatDate(item.lastVisitTime)
        }
        grouped.getOrPut(label) { mutableListOf() }.add(item)
    }
    return grouped.toList()
}

internal fun formatTime(timestamp: Long): String = sharedFormatTime(timestamp)


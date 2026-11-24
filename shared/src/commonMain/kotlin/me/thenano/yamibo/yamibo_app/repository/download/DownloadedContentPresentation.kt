package me.thenano.yamibo.yamibo_app.repository.download

const val DOWNLOADED_CONTENT_FILTER_ALL = "all"
const val DOWNLOADED_CONTENT_FILTER_TAG_MANGA = "tag_manga"
const val DOWNLOADED_CONTENT_FILTER_UNKNOWN_FORUM = "forum_unknown"

enum class DownloadedContentSortMode {
    TotalSize,
    DownloadedAt,
}

data class DownloadedContentFilterOption(
    val key: String,
    val label: String,
    val count: Int,
)

/** Progress updates do not change the downloaded library. */
fun downloadedContentRevision(entries: List<DownloadQueueEntry>): Map<DownloadTaskKey, Long> =
    entries.asSequence()
        .filter { it.status == DownloadStatus.Downloaded || it.status == DownloadStatus.UpdateAvailable }
        .associate { it.key to it.updatedAt }

val DownloadedContentItem.sourceKeys: List<DownloadTaskKey> get() = listOf(key) + otherKeys

private fun uniqueImages(items: List<DownloadedContentItem>): Pair<Int, Long> {
    val shared = mutableMapOf<String, Long>()
    var count = 0
    var bytes = 0L
    for (item in items) {
        shared.putAll(item.sharedImages)
        count += item.imageCount - item.sharedImages.size
        bytes += item.imageBytes - item.sharedImages.values.sum()
    }
    return (shared.size + count) to (shared.values.sum() + bytes)
}

internal fun mergeDownloadedThreads(groups: List<DownloadedContentGroup>): List<DownloadedContentGroup> {
    return groups.flatMap { group -> group.items.map { group to it } }
        .groupBy { (_, item) -> item.key.pageKey.tid }.map { (tid, entries) ->
            val header = entries.firstOrNull { it.first.type == DownloadedContentGroupType.Thread }?.first ?: entries.first().first
            val items = entries.map { it.second }.groupBy { item ->
                // Image-only legacy items retain their source-specific reader until migration.
                item.key.pageKey to item.key.takeIf { item.needsMigration && it !is ThreadPageDownloadKey }
            }.values.map { copies ->
                val first = copies.minWith(compareBy<DownloadedContentItem> { it.missingImageCount }
                    .thenBy { it.key !is ThreadPageDownloadKey }.thenByDescending { it.downloadedAt })
                val (count, bytes) = uniqueImages(copies)
                first.copy(otherKeys = copies.map { it.key } - first.key, sortPage = first.key.pageKey.page,
                    detail = "第 ${first.key.pageKey.page} 頁 · " + (first.key.pageKey.authorId?.let { "作者 $it" } ?: "全部作者"),
                    sharedImages = copies.flatMap { it.sharedImages.entries }.associate { it.toPair() },
                    needsMigration = copies.any { it.needsMigration }, imageCount = count, imageBytes = bytes)
            }.sortedWith(compareBy<DownloadedContentItem> { it.sortPage }.thenBy { it.key.stableId })
            val (count, bytes) = uniqueImages(items)
            val latest = items.maxBy { it.downloadedAt }
            header.copy(id = "tid_$tid", title = latest.title, items = items, itemCount = items.size,
                imageCount = count, imageBytes = bytes, latestDownloadedAt = latest.downloadedAt)
        }
}

/** Reuse the inventory already loaded for the list instead of scanning storage again. */
fun summarizeDownloadedContent(groups: List<DownloadedContentGroup>): DownloadedContentSummary {
    val threads = groups.filter { it.type == DownloadedContentGroupType.Thread }
    val tags = groups.filter { it.type == DownloadedContentGroupType.TagManga }
    val rss = groups.filter { it.type == DownloadedContentGroupType.RssManga }
    val items = groups.flatMap { it.items }
    val claims = items.flatMap { it.sourceKeys }.distinct()
    val (uniqueCount, uniqueBytes) = uniqueImages(items)
    return DownloadedContentSummary(
        totalItems = groups.size,
        threadPages = claims.count { it is ThreadPageDownloadKey },
        tagMangaChapters = claims.count { it is TagMangaChapterDownloadKey },
        rssMangaChapters = claims.count { it is RssMangaChapterDownloadKey },
        imageCount = uniqueCount,
        imageBytes = uniqueBytes,
        threadImageBytes = threads.sumOf { it.imageBytes },
        tagMangaImageBytes = tags.sumOf { it.imageBytes },
        rssMangaImageBytes = rss.sumOf { it.imageBytes },
    )
}

fun buildDownloadedContentFilterOptions(groups: List<DownloadedContentGroup>): List<DownloadedContentFilterOption> =
    groups
        .groupBy { it.normalizedFilterKey() to it.normalizedFilterLabel() }
        .map { (filter, grouped) ->
            DownloadedContentFilterOption(
                key = filter.first,
                label = filter.second,
                count = grouped.sumOf { it.itemCount },
            )
        }
        .sortedWith(
            compareBy<DownloadedContentFilterOption> { option ->
                when (option.key) {
                    DOWNLOADED_CONTENT_FILTER_TAG_MANGA -> 2
                    DOWNLOADED_CONTENT_FILTER_UNKNOWN_FORUM -> 1
                    else -> 0
                }
            }.thenBy { it.label }
        )

fun filterAndSortDownloadedContentGroups(
    groups: List<DownloadedContentGroup>,
    selectedFilterKeys: Set<String>,
    sortMode: DownloadedContentSortMode,
    descending: Boolean,
): List<DownloadedContentGroup> {
    val activeKeys = selectedFilterKeys - DOWNLOADED_CONTENT_FILTER_ALL
    val filtered = if (activeKeys.isEmpty()) {
        groups
    } else {
        groups.filter { it.normalizedFilterKey() in activeKeys }
    }
    val comparator = when (sortMode) {
        DownloadedContentSortMode.TotalSize -> compareBy<DownloadedContentGroup> { it.imageBytes }
        DownloadedContentSortMode.DownloadedAt -> compareBy { it.effectiveLatestDownloadedAt() }
    }.thenBy { it.title }
    return if (descending) filtered.sortedWith(comparator.reversed()) else filtered.sortedWith(comparator)
}

private fun DownloadedContentGroup.normalizedFilterKey(): String =
    filterKey.ifBlank {
        when (type) {
            DownloadedContentGroupType.TagManga -> DOWNLOADED_CONTENT_FILTER_TAG_MANGA
            DownloadedContentGroupType.RssManga -> DOWNLOADED_CONTENT_FILTER_TAG_MANGA
            DownloadedContentGroupType.Thread -> DOWNLOADED_CONTENT_FILTER_UNKNOWN_FORUM
        }
    }

private fun DownloadedContentGroup.normalizedFilterLabel(): String =
    filterLabel.ifBlank {
        when (type) {
            DownloadedContentGroupType.TagManga -> "標籤漫畫"
            DownloadedContentGroupType.RssManga -> "RSS 漫畫"
            DownloadedContentGroupType.Thread -> "未知板塊"
        }
    }

private fun DownloadedContentGroup.effectiveLatestDownloadedAt(): Long =
    latestDownloadedAt.takeIf { it > 0L } ?: items.maxOfOrNull { it.downloadedAt } ?: 0L

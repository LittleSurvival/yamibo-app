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

/** Reuse the inventory already loaded for the list instead of scanning storage again. */
fun summarizeDownloadedContent(groups: List<DownloadedContentGroup>): DownloadedContentSummary {
    val threads = groups.filter { it.type == DownloadedContentGroupType.Thread }
    val tags = groups.filter { it.type == DownloadedContentGroupType.TagManga }
    val rss = groups.filter { it.type == DownloadedContentGroupType.RssManga }
    return DownloadedContentSummary(
        totalItems = groups.sumOf { it.itemCount },
        threadPages = threads.sumOf { it.itemCount },
        tagMangaChapters = tags.sumOf { it.itemCount },
        rssMangaChapters = rss.sumOf { it.itemCount },
        imageCount = groups.sumOf { it.imageCount },
        imageBytes = groups.sumOf { it.imageBytes },
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

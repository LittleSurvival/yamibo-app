package me.thenano.yamibo.yamibo_app.repository.download

import kotlinx.serialization.Serializable

@Serializable
sealed interface DownloadTaskKey {
    val stableId: String
}

@Serializable
data class ThreadPageDownloadKey(
    val tid: Int,
    val page: Int,
    val authorId: Int? = null,
) : DownloadTaskKey {
    override val stableId: String
        get() = "thread_${tid}_page_${page}_author_${authorId ?: "all"}"

    val threadPrefix: String
        get() = "thread_${tid}_"
}

@Serializable
data class TagMangaChapterDownloadKey(
    val tagId: Int,
    val tid: Int,
    val authorId: Int? = null,
) : DownloadTaskKey {
    override val stableId: String
        get() = "tag_manga_${tagId}_chapter_${tid}_author_${authorId ?: "all"}"

    val tagStableId: String
        get() = "tag_manga_$tagId"

    val chapterStableId: String
        get() = "chapter_${tid}_author_${authorId ?: "all"}"
}

@Serializable
data class RssMangaChapterDownloadKey(
    val subscriptionId: Long,
    val tid: Int,
    val authorId: Int? = null,
) : DownloadTaskKey {
    override val stableId: String
        get() = "rss_${subscriptionId}_chapter_${tid}_author_${authorId ?: "all"}"

    val rssStableId: String
        get() = "rss_$subscriptionId"

    val chapterStableId: String
        get() = "chapter_${tid}_author_${authorId ?: "all"}"
}

enum class DownloadStatus {
    NotDownloaded,
    Queued,
    Downloading,
    Downloaded,
    Failed,
    Paused,
    UpdateAvailable,
}

enum class DownloadStage {
    Preparing,
    FetchingContent,
    DownloadingText,
    DownloadingImages,
    Saving,
}

enum class DownloadPageKind {
    Normal,
    LastAtDownloadTime,
}

@Serializable
data class DownloadedImage(
    val sourceUrl: String,
    val fileName: String,
    val bytes: Long,
)

@Serializable
data class ThreadPageDownloadManifest(
    val key: ThreadPageDownloadKey,
    val title: String,
    val downloadedAt: Long,
    val sourceTotalPages: Int,
    val pageKind: DownloadPageKind,
    val forumId: Int? = null,
    val forumName: String? = null,
    val images: List<DownloadedImage> = emptyList(),
)

@Serializable
data class TagMangaChapterManifest(
    val key: TagMangaChapterDownloadKey,
    val tagName: String,
    val tid: Int = key.tid,
    val title: String,
    val authorId: Int? = key.authorId,
    val tagPage: Int,
    val imageCount: Int,
    val downloadedAt: Long,
    val sourceUpdatedAt: Long? = null,
    val images: List<DownloadedImage> = emptyList(),
)

@Serializable
data class RssMangaChapterManifest(
    val key: RssMangaChapterDownloadKey,
    val subscriptionTitle: String,
    val subscriptionQuery: String,
    val tid: Int = key.tid,
    val title: String,
    val authorId: Int? = key.authorId,
    val subscriptionPage: Int,
    val imageCount: Int,
    val downloadedAt: Long,
    val sourceUpdatedAt: Long? = null,
    val images: List<DownloadedImage> = emptyList(),
)

@Serializable
data class DownloadQueueEntry(
    val key: DownloadTaskKey,
    val title: String,
    val status: DownloadStatus,
    val progressCurrent: Int = 0,
    val progressTotal: Int = 0,
    val stage: DownloadStage? = null,
    val message: String? = null,
    val updatedAt: Long = 0L,
    // Legacy queues have only updatedAt; use it once when decoding older entries.
    val queuedAt: Long = updatedAt,
)

data class DownloadQueueSummary(
    val queued: Int = 0,
    val downloading: Int = 0,
    val downloaded: Int = 0,
    val failed: Int = 0,
    val updateAvailable: Int = 0,
) {
    val active: Int get() = queued + downloading
}

data class DownloadedContentSummary(
    val totalItems: Int = 0,
    val threadPages: Int = 0,
    val tagMangaChapters: Int = 0,
    val rssMangaChapters: Int = 0,
    val imageCount: Int = 0,
    val imageBytes: Long = 0L,
    val threadImageBytes: Long = 0L,
    val tagMangaImageBytes: Long = 0L,
    val rssMangaImageBytes: Long = 0L,
)

data class DownloadedContentGroup(
    val id: String,
    val title: String,
    val type: DownloadedContentGroupType,
    val itemCount: Int,
    val imageCount: Int,
    val imageBytes: Long,
    val items: List<DownloadedContentItem>,
    val filterKey: String = "",
    val filterLabel: String = "",
    val latestDownloadedAt: Long = 0L,
)

enum class DownloadedContentGroupType {
    Thread,
    TagManga,
    RssManga,
}

data class DownloadedContentItem(
    val key: DownloadTaskKey,
    val title: String,
    val detail: String,
    val downloadedAt: Long,
    val imageCount: Int,
    val imageBytes: Long,
)

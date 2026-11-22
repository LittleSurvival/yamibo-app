package me.thenano.yamibo.yamibo_app.repository.download

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.encodeUtf8

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

internal val DownloadTaskKey.parentDirectory: String? get() = when (this) {
    is ThreadPageDownloadKey -> null
    is TagMangaChapterDownloadKey -> tagStableId
    is RssMangaChapterDownloadKey -> rssStableId
}

internal val DownloadTaskKey.itemDirectory: String get() = when (this) {
    is ThreadPageDownloadKey -> stableId
    is TagMangaChapterDownloadKey -> chapterStableId
    is RssMangaChapterDownloadKey -> chapterStableId
}

/** Read-time image reference carried through the readers' URL-based navigation. */
@Serializable
data class MissingThreadImage(
    val key: DownloadTaskKey,
    val storageId: String,
    val sourceUrl: String,
) {
    internal fun readerUri(): String = PREFIX + Json.encodeToString(this).encodeUtf8().base64Url()

    companion object {
        private const val PREFIX = "yamibo-missing://"

        fun isReaderUri(url: String): Boolean = url.startsWith(PREFIX)

        fun fromReaderUri(url: String): MissingThreadImage? {
            if (!isReaderUri(url)) return null
            val payload = url.removePrefix(PREFIX).decodeBase64()?.utf8() ?: return null
            return runCatching { Json.decodeFromString<MissingThreadImage>(payload) }.getOrElse {
                // Older saved reader URLs encode a concrete thread key without a type discriminator.
                runCatching { Json.decodeFromString<LegacyThreadImage>(payload) }
                    .getOrNull()?.let { MissingThreadImage(it.key, it.storageId, it.sourceUrl) }
            }
        }
    }
}

@Serializable
private data class LegacyThreadImage(val key: ThreadPageDownloadKey, val storageId: String, val sourceUrl: String)

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
    val packed: Boolean = false,
)

/** The archive generation changes only when the physical image pack is replaced. */
sealed interface DownloadManifest {
    val owner: Int?
    val missingImageCount: Int
    val key: DownloadTaskKey
    val images: List<DownloadedImage>
    val archiveId: String?
}

internal fun DownloadManifest.orderedSources(): List<String> {
    val order = when (this) {
        is TagMangaChapterManifest -> sourceOrder
        is RssMangaChapterManifest -> sourceOrder
        is ThreadPageDownloadManifest -> emptyList()
    }
    return order.ifEmpty { images.map { it.sourceUrl } }
}

@Serializable
internal data class ArchivedImage(val storageId: String, val key: DownloadTaskKey, val fileName: String, val generation: String, val sourceId: String) {
    fun uri() = PREFIX + Json.encodeToString(this).encodeUtf8().base64Url()

    companion object {
        const val PREFIX = "yamibo-image://"
        fun parse(uri: String): ArchivedImage? {
            if (!uri.startsWith(PREFIX)) return null
            return runCatching { Json.decodeFromString<ArchivedImage>(requireNotNull(uri.removePrefix(PREFIX).decodeBase64()).utf8()) }
                .getOrNull()?.takeIf { it.fileName.isSafeImageFileName() }
        }
    }
}

@Serializable
data class ThreadPageDownloadManifest(
    override val key: ThreadPageDownloadKey,
    val title: String,
    val downloadedAt: Long,
    val sourceTotalPages: Int,
    val pageKind: DownloadPageKind,
    val forumId: Int? = null,
    val forumName: String? = null,
    override val images: List<DownloadedImage> = emptyList(),
    override val archiveId: String? = null,
    override val owner: Int? = null,
    override val missingImageCount: Int = 0,
) : DownloadManifest

@Serializable
data class TagMangaChapterManifest(
    override val key: TagMangaChapterDownloadKey,
    val tagName: String,
    val tid: Int = key.tid,
    val title: String,
    val authorId: Int? = key.authorId,
    val tagPage: Int,
    val imageCount: Int,
    val downloadedAt: Long,
    val sourceUpdatedAt: Long? = null,
    override val images: List<DownloadedImage> = emptyList(),
    override val archiveId: String? = null,
    val sourceOrder: List<String> = emptyList(),
    override val owner: Int? = null,
    override val missingImageCount: Int = 0,
) : DownloadManifest

@Serializable
data class RssMangaChapterManifest(
    override val key: RssMangaChapterDownloadKey,
    val subscriptionTitle: String,
    val subscriptionQuery: String,
    val tid: Int = key.tid,
    val title: String,
    val authorId: Int? = key.authorId,
    val subscriptionPage: Int,
    val imageCount: Int,
    val downloadedAt: Long,
    val sourceUpdatedAt: Long? = null,
    override val images: List<DownloadedImage> = emptyList(),
    override val archiveId: String? = null,
    val sourceOrder: List<String> = emptyList(),
    override val owner: Int? = null,
    override val missingImageCount: Int = 0,
) : DownloadManifest

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
    val owner: Int? = null,
    val refreshSource: Boolean = false,
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

@Serializable
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

@Serializable
data class DownloadedContentItem(
    val sortPage: Int = 0,
    val needsMigration: Boolean = false,
    val key: DownloadTaskKey,
    val title: String,
    val detail: String,
    val downloadedAt: Long,
    val imageCount: Int,
    val imageBytes: Long,
    val missingImageCount: Int = 0,
    val owner: Int? = null,
)

package me.thenano.yamibo.yamibo_app.repository.backup

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

internal const val CURRENT_BACKUP_SCHEMA_VERSION = 1

@Serializable
internal data class YamiboBackupFile(
    val schemaVersion: Int = CURRENT_BACKUP_SCHEMA_VERSION,
    val appVersionCode: Int,
    val createdAt: Long,
    val favorites: BackupFavorites = BackupFavorites(),
    val settings: List<BackupSetting> = emptyList(),
    val notes: List<BackupDetailNote> = emptyList(),
    val bookmarks: List<BackupBookMark> = emptyList(),
    val readingState: BackupReadingState = BackupReadingState(),
)

@Serializable
internal data class BackupFavorites(
    val categories: List<BackupFavoriteCategory> = emptyList(),
    val collections: List<BackupFavoriteCollection> = emptyList(),
    val items: List<BackupFavoriteItem> = emptyList(),
    val itemCategories: List<BackupFavoriteItemCategory> = emptyList(),
    val itemCollections: List<BackupFavoriteItemCollection> = emptyList(),
)

@Serializable
internal data class BackupFavoriteCategory(
    val localId: Long,
    val syncId: String? = null,
    val name: String,
    val sortOrder: Long,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
internal data class BackupFavoriteCollection(
    val localId: Long,
    val syncId: String? = null,
    val categoryLocalId: Long,
    val name: String,
    val colorKey: String,
    val sortOrder: Long,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
internal data class BackupFavoriteItem(
    val localId: Long,
    val targetType: String,
    val targetId: Long,
    val title: String,
    val coverUrl: String?,
    val lastUpdatedTime: Long?,
    val forumId: Long?,
    val forumName: String?,
    val authorId: Long,
    val createdAt: Long,
    val lastFavoriteStatusUpdateAt: Long,
)

@Serializable
internal data class BackupFavoriteItemCategory(
    val itemLocalId: Long,
    val categoryLocalId: Long,
    val createdAt: Long,
)

@Serializable
internal data class BackupFavoriteItemCollection(
    val itemLocalId: Long,
    val collectionLocalId: Long,
    val createdAt: Long,
)

@Serializable
internal data class BackupSetting(
    val key: String,
    val type: BackupSettingType,
    val value: String,
)

@Serializable
internal enum class BackupSettingType {
    @SerialName("int") Int,
    @SerialName("float") Float,
    @SerialName("bool") Bool,
    @SerialName("string") String,
    @SerialName("enum") Enum,
}

@Serializable
internal data class BackupDetailNote(
    val targetType: String,
    val targetId: Long,
    val authorId: Long,
    val content: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
internal data class BackupBookMark(
    val targetType: String,
    val parentId: Long,
    val targetId: Long,
    val title: String,
    val bookmarked: Boolean,
    val read: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
internal data class BackupReadingState(
    val threadHistory: List<BackupThreadReadingHistory> = emptyList(),
    val imageHistory: List<BackupImageReadingHistory> = emptyList(),
    val tagMangaHistory: List<BackupTagMangaReadingHistory> = emptyList(),
    val readingTimeStats: List<BackupReadingTimeStat> = emptyList(),
)

@Serializable
internal data class BackupThreadReadingHistory(
    val threadId: Long,
    val threadType: String,
    val threadName: String,
    val threadCover: String?,
    val forumName: String?,
    val forumId: Long?,
    val authorId: Long,
    val page: Long,
    val postId: Long,
    val postTitle: String,
    val anchorPostId: Long,
    val anchorPostRatio: Double?,
    val anchorBlockId: String?,
    val anchorBlockType: String?,
    val anchorBlockRatio: Double?,
    val globalScrollY: Long?,
    val viewportHeight: Long?,
    val firstVisibleItemIndex: Long?,
    val firstVisibleItemOffset: Long?,
    val historyOrigin: String = "Direct",
    val lastVisitTime: Long,
    val lastUpdatedTime: Long?,
)

@Serializable
internal data class BackupImageReadingHistory(
    val postId: Long,
    val threadId: Long,
    val pageIndex: Long,
    val totalPages: Long,
    val firstVisibleItemIndex: Long?,
    val firstVisibleItemOffset: Long?,
    val lastVisitTime: Long,
)

@Serializable
internal data class BackupTagMangaReadingHistory(
    val tagId: Long,
    val tagName: String,
    val tagPage: Long,
    val threadId: Long,
    val threadTitle: String,
    val threadImagePageIndex: Long,
    val threadImageTotalPages: Long,
    val firstVisibleItemIndex: Long?,
    val firstVisibleItemOffset: Long?,
    val lastVisitTime: Long,
    val coverUrl: String?,
)

@Serializable
internal data class BackupReadingTimeStat(
    val dateKey: String,
    val durationMillis: Long,
    val updatedAt: Long,
)

package me.thenano.yamibo.yamibo_app.repository.appsync.model

import kotlinx.serialization.Serializable

@Serializable
data class AppSyncFavorites(
    val categories: List<AppSyncFavoriteCategory> = emptyList(),
    val collections: List<AppSyncFavoriteCollection> = emptyList(),
    val items: List<AppSyncFavoriteItem> = emptyList(),
    val itemCategoryRefs: List<AppSyncFavoriteItemCategoryRef> = emptyList(),
    val itemCollectionRefs: List<AppSyncFavoriteItemCollectionRef> = emptyList(),
)

@Serializable
data class AppSyncFavoriteCategory(
    val snapshotId: Long,
    val name: String,
    val sortOrder: Long,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
data class AppSyncFavoriteCollection(
    val snapshotId: Long,
    val categorySnapshotId: Long,
    val name: String,
    val colorKey: String,
    val sortOrder: Long,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
data class AppSyncFavoriteItem(
    val snapshotId: Long,
    val targetType: String,
    val targetId: Long,
    val title: String,
    val coverUrl: String? = null,
    val lastUpdatedTime: Long? = null,
    val forumId: Long? = null,
    val forumName: String? = null,
    val authorId: Long = 0,
    val createdAt: Long,
    val lastFavoriteStatusUpdateAt: Long,
)

@Serializable
data class AppSyncFavoriteItemCategoryRef(
    val itemSnapshotId: Long,
    val categorySnapshotId: Long,
    val createdAt: Long,
)

@Serializable
data class AppSyncFavoriteItemCollectionRef(
    val itemSnapshotId: Long,
    val collectionSnapshotId: Long,
    val createdAt: Long,
)

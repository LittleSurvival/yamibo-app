package me.thenano.yamibo.yamibo_app.repository

import io.github.littlesurvival.dto.value.ForumId
import io.github.littlesurvival.dto.value.TagId
import io.github.littlesurvival.dto.value.ThreadId
import io.github.littlesurvival.dto.value.UserId
import kotlinx.coroutines.flow.StateFlow

interface FavoriteStoreRepository {
    val favoriteItemRevision: StateFlow<Long>

    enum class FavoriteTargetType {
        ThreadNormal,
        ThreadNovel,
        TagManga,
        RssSearch;

        companion object {
            fun fromStorage(value: String?): FavoriteTargetType {
                return entries.firstOrNull { it.name == value } ?: ThreadNormal
            }
        }
    }

    data class FavoriteCategory(
        val id: Long,
        val name: String,
        val sortOrder: Long,
        val createdAt: Long,
        val updatedAt: Long,
    )

    data class FavoriteCollection(
        val id: Long,
        val categoryId: Long,
        val name: String,
        val colorKey: String,
        val sortOrder: Long,
        val createdAt: Long,
        val updatedAt: Long,
    )

    data class FavoriteCollectionOption(
        val id: Long,
        val categoryId: Long,
        val categoryName: String,
        val collectionName: String,
        val colorKey: String,
    )

    data class FavoriteCategoryDeletePreview(
        val categoryId: Long,
        val categoryName: String,
        val directItemCount: Int,
        val collectionCount: Int,
        val collectionItemCount: Int,
        val totalDistinctItemCount: Int,
        val isDefaultCategory: Boolean,
    )

    data class FavoriteCategoryContent(
        val directItems: List<FavoriteItem>,
        val collections: List<FavoriteCollectionWithItems>,
    )

    data class FavoriteItem(
        val id: Long,
        val targetType: FavoriteTargetType,
        val targetId: Long,
        val title: String,
        val coverUrl: String?,
        val lastUpdatedTime: Long?,
        val forumId: ForumId?,
        val forumName: String?,
        val authorId: UserId?,
        val createdAt: Long,
        val lastFavoriteStatusUpdateAt: Long,
    )

    data class FavoriteCollectionWithItems(
        val collection: FavoriteCollection,
        val items: List<FavoriteItem>,
    )

    suspend fun ensureDefaults()
    suspend fun getDefaultCategory(): FavoriteCategory
    suspend fun getCategories(): List<FavoriteCategory>
    suspend fun getAllFavoriteItems(): List<FavoriteItem>
    suspend fun getCollections(categoryId: Long): List<FavoriteCollection>
    suspend fun getAllCollections(): List<FavoriteCollection>
    suspend fun getCategoryContent(categoryId: Long): FavoriteCategoryContent
    suspend fun getCollectionsWithItems(categoryId: Long): List<FavoriteCollectionWithItems>
    suspend fun getCollectionOptions(): List<FavoriteCollectionOption>
    suspend fun getCategoryIdsForItem(itemId: Long): Set<Long>
    suspend fun getContainingCategoryIdsForItem(itemId: Long): Set<Long>
    suspend fun getCollectionIdsForItem(itemId: Long): Set<Long>
    suspend fun getFavoritePaths(itemId: Long): List<String>

    suspend fun createCategory(name: String): FavoriteCategory
    suspend fun updateCategory(categoryId: Long, name: String)
    suspend fun getCategoryDeletePreview(categoryId: Long): FavoriteCategoryDeletePreview?
    suspend fun deleteCategory(categoryId: Long, moveItemsToDefault: Boolean = true)
    suspend fun moveCategory(categoryId: Long, moveUp: Boolean)
    suspend fun moveCategoryToIndex(categoryId: Long, targetIndex: Int)

    suspend fun createCollection(categoryId: Long, name: String, colorKey: String): FavoriteCollection
    suspend fun updateCollection(collectionId: Long, name: String, colorKey: String)
    suspend fun deleteCollection(collectionId: Long)
    suspend fun moveCollection(collectionId: Long, moveUp: Boolean)
    suspend fun moveCollectionToIndex(collectionId: Long, targetIndex: Int)

    suspend fun addNormalThreadFavorite(
        tid: ThreadId,
        title: String,
        coverUrl: String?,
        lastUpdatedTime: Long?,
        forumId: ForumId?,
        forumName: String?,
        categoryIds: List<Long> = emptyList(),
        collectionIds: List<Long> = emptyList(),
    )

    suspend fun addNovelThreadFavorite(
        tid: ThreadId,
        title: String,
        authorId: UserId?,
        coverUrl: String?,
        lastUpdatedTime: Long?,
        forumId: ForumId?,
        forumName: String?,
        categoryIds: List<Long> = emptyList(),
        collectionIds: List<Long> = emptyList(),
    )

    suspend fun addTagMangaFavorite(
        tagId: TagId,
        tagName: String,
        coverUrl: String?,
        categoryIds: List<Long> = emptyList(),
        collectionIds: List<Long> = emptyList(),
    )

    suspend fun addRssSearchFavorite(
        subscriptionId: Long,
        title: String,
        coverUrl: String?,
        lastUpdatedTime: Long? = null,
        categoryIds: List<Long> = emptyList(),
        collectionIds: List<Long> = emptyList(),
    )

    suspend fun isThreadFavorited(
        tid: ThreadId,
        threadType: ReadHistoryRepository.ThreadEntryType,
        authorId: UserId? = null,
    ): Boolean

    suspend fun isTagFavorited(tagId: TagId): Boolean
    suspend fun getFavoriteItem(
        targetType: FavoriteTargetType,
        targetId: Long,
        authorId: UserId? = null,
    ): FavoriteItem?
    suspend fun setItemLocations(itemId: Long, categoryIds: Set<Long>, collectionIds: Set<Long>)
    suspend fun setItemsLocations(
        itemIds: Set<Long>,
        categoryIds: Set<Long>,
        collectionIds: Set<Long>,
    )
    suspend fun addItemsToLocations(
        itemIds: Set<Long>,
        categoryIds: Set<Long> = emptySet(),
        collectionIds: Set<Long> = emptySet(),
    )
    suspend fun setItemCollections(itemId: Long, collectionIds: Set<Long>)
    suspend fun addItemsToCollections(itemIds: Set<Long>, collectionIds: Set<Long>)
    suspend fun removeItemsFromCategory(itemIds: Set<Long>, categoryId: Long)
    suspend fun removeItemsFromCollections(itemIds: Set<Long>, collectionIds: Set<Long>)
    suspend fun deleteFavoriteItems(itemIds: Set<Long>)

    companion object {
        const val DEFAULT_CATEGORY_NAME = "\u9810\u8a2d"
        const val DEFAULT_COLLECTION_COLOR = "brown"
    }
}

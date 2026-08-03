package me.thenano.yamibo.yamibo_app.repository.favorite

import io.github.littlesurvival.dto.value.ForumId
import io.github.littlesurvival.dto.value.TagId
import io.github.littlesurvival.dto.value.ThreadId
import io.github.littlesurvival.dto.value.UserId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import me.thenano.yamibo.yamibo_app.Database
import me.thenano.yamibo.yamibo_app.repository.FavoriteStoreRepository
import me.thenano.yamibo.yamibo_app.repository.FavoriteStoreRepository.Companion.DEFAULT_CATEGORY_NAME
import me.thenano.yamibo.yamibo_app.repository.FavoriteStoreRepository.FavoriteCategory
import me.thenano.yamibo.yamibo_app.repository.FavoriteStoreRepository.FavoriteCategoryContent
import me.thenano.yamibo.yamibo_app.repository.FavoriteStoreRepository.FavoriteCategoryDeletePreview
import me.thenano.yamibo.yamibo_app.repository.FavoriteStoreRepository.FavoriteCollection
import me.thenano.yamibo.yamibo_app.repository.FavoriteStoreRepository.FavoriteCollectionOption
import me.thenano.yamibo.yamibo_app.repository.FavoriteStoreRepository.FavoriteCollectionWithItems
import me.thenano.yamibo.yamibo_app.repository.FavoriteStoreRepository.FavoriteItem
import me.thenano.yamibo.yamibo_app.repository.FavoriteStoreRepository.FavoriteTargetType
import me.thenano.yamibo.yamibo_app.i18n.i18n
import me.thenano.yamibo.yamibo_app.repository.ReadHistoryRepository
import me.thenano.yamibo.yamibo_app.util.time.currentTimeMillis
import me.thenano.yamibo.yamibo_app.repository.contentcover.normalizeCoverUrl
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncIdentityGenerator
import me.thenano.yamibo.yamibo_app.repository.appsync.AppSyncMutationRecorder
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncDomainId
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncEntityId
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperationKind
import me.thenano.yamibo.yamibo_app.store.appsync.LocalSyncOperationDraft
import me.thenano.yamibo.yamibo_app.LocalFavoriteCategory
import me.thenano.yamibo.yamibo_app.LocalFavoriteCollection
import me.thenano.yamibo.yamibo_app.LocalFavoriteItem

class FavoriteStoreRepositoryImpl internal constructor(
    private val db: Database,
    private val mutationRecorder: AppSyncMutationRecorder? = null,
) : FavoriteStoreRepository {
    private val favoriteItemRevisionState = MutableStateFlow(0L)
    override val favoriteItemRevision: StateFlow<Long> = favoriteItemRevisionState.asStateFlow()
    private val categoryQueries = db.localFavoriteCategoryQueries
    private val collectionQueries = db.localFavoriteCollectionQueries
    private val itemQueries = db.localFavoriteItemQueries
    private val itemCategoryCrossRefQueries = db.localFavoriteItemCategoryCrossRefQueries
    private val crossRefQueries = db.localFavoriteItemCollectionCrossRefQueries
    private val contentCoverQueries = db.contentCoverQueries
    private val rssSubscriptionQueries = db.rssSearchSubscriptionQueries
    private val rssResultQueries = db.rssSearchSubscriptionResultQueries

    override suspend fun ensureDefaults() {
        if (categoryQueries.getDefaultByName(DEFAULT_CATEGORY_NAME).executeAsOneOrNull() == null) {
            createCategory(DEFAULT_CATEGORY_NAME)
        }
    }

    override suspend fun getDefaultCategory(): FavoriteCategory {
        ensureDefaults()
        return categoryQueries.getDefaultByName(DEFAULT_CATEGORY_NAME).executeAsOne().toModel()
    }

    override suspend fun getCategories(): List<FavoriteCategory> {
        return categoryQueries.getAll()
            .executeAsList()
            .map { it.toModel() }
    }

    override suspend fun getAllFavoriteItems(): List<FavoriteItem> {
        return itemQueries.getAll()
            .executeAsList()
            .map { it.toModel() }
    }

    override suspend fun getCollections(categoryId: Long): List<FavoriteCollection> {
        return collectionQueries.getByCategoryId(categoryId)
            .executeAsList()
            .map { it.toModel() }
    }

    override suspend fun getAllCollections(): List<FavoriteCollection> {
        return collectionQueries.getAll()
            .executeAsList()
            .map { it.toModel() }
    }

    override suspend fun getCategoryContent(categoryId: Long): FavoriteCategoryContent {
        return FavoriteCategoryContent(
            directItems = itemQueries.getByCategoryId(categoryId)
                .executeAsList()
                .map { it.toModel() },
            collections = getCollectionsWithItems(categoryId)
        )
    }

    override suspend fun getCollectionsWithItems(categoryId: Long): List<FavoriteCollectionWithItems> {
        return getCollections(categoryId).map { collection ->
            FavoriteCollectionWithItems(
                collection = collection,
                items = itemQueries.getByCollectionId(collection.id)
                    .executeAsList()
                    .map { it.toModel() }
            )
        }
    }

    override suspend fun getCollectionOptions(): List<FavoriteCollectionOption> {
        val categories = getCategories().associateBy { it.id }
        return getAllCollections().mapNotNull { collection ->
            val category = categories[collection.categoryId] ?: return@mapNotNull null
            FavoriteCollectionOption(
                id = collection.id,
                categoryId = collection.categoryId,
                categoryName = category.name,
                collectionName = collection.name,
                colorKey = collection.colorKey
            )
        }
    }

    override suspend fun getCategoryIdsForItem(itemId: Long): Set<Long> {
        return itemCategoryCrossRefQueries.getCategoryIdsByItemId(itemId)
            .executeAsList()
            .toSet()
    }

    override suspend fun getCollectionIdsForItem(itemId: Long): Set<Long> {
        return crossRefQueries.getCollectionIdsByItemId(itemId)
            .executeAsList()
            .toSet()
    }

    override suspend fun getFavoritePaths(itemId: Long): List<String> {
        val categories = getCategories().associateBy { it.id }
        val collections = getAllCollections().associateBy { it.id }

        val categoryPaths = getCategoryIdsForItem(itemId)
            .mapNotNull { categoryId -> categories[categoryId]?.name }

        val collectionPaths = getCollectionIdsForItem(itemId)
            .mapNotNull { collectionId ->
                val collection = collections[collectionId] ?: return@mapNotNull null
                val category = categories[collection.categoryId] ?: return@mapNotNull null
                "${category.name}/${collection.name}"
            }

        return (categoryPaths + collectionPaths).distinct()
    }

    override suspend fun createCategory(name: String): FavoriteCategory {
        val normalizedName = validateFavoriteName(name = name)
        val now = currentTimeMillis()
        val nextOrder = (categoryQueries.getMaxSortOrder().executeAsOneOrNull()?.MAX ?: -1L) + 1L
        return mutateSyncable {
            categoryQueries.insertCategory(
                name = normalizedName,
                sortOrder = nextOrder,
                createdAt = now,
                updatedAt = now
            )
            categoryQueries.getFirstByName(normalizedName).executeAsOne().also {
                categoryQueries.setSyncId(SyncIdentityGenerator.stableEntityId().value, it.id)
            }.let {
                categoryQueries.getById(it.id).executeAsOne().toModel()
            }
        }
    }

    override suspend fun updateCategory(categoryId: Long, name: String) {
        if (isDefaultCategory(categoryId)) return
        val normalizedName = validateFavoriteName(name = name, excludeCategoryId = categoryId)
        mutateSyncable {
            categoryQueries.updateCategoryName(normalizedName, currentTimeMillis(), categoryId)
        }
    }

    override suspend fun getCategoryDeletePreview(categoryId: Long): FavoriteCategoryDeletePreview? {
        val category = categoryQueries.getById(categoryId).executeAsOneOrNull() ?: return null
        val collections = collectionQueries.getByCategoryId(categoryId).executeAsList()
        val directItemIds = itemCategoryCrossRefQueries.getItemIdsByCategoryId(categoryId).executeAsList()
        val collectionItemIds = collections.flatMap { collection ->
            crossRefQueries.getItemIdsByCollectionId(collection.id).executeAsList()
        }
        return FavoriteCategoryDeletePreview(
            categoryId = category.id,
            categoryName = category.name,
            directItemCount = directItemIds.distinct().size,
            collectionCount = collections.size,
            collectionItemCount = collectionItemIds.distinct().size,
            totalDistinctItemCount = (directItemIds + collectionItemIds).distinct().size,
            isDefaultCategory = category.name == DEFAULT_CATEGORY_NAME,
        )
    }

    override suspend fun deleteCategory(categoryId: Long, moveItemsToDefault: Boolean) {
        if (isDefaultCategory(categoryId)) return
        val categories = getCategories()
        if (categories.size <= 1) return

        val defaultCategoryId = getDefaultCategory().id
        val collectionIds = collectionQueries.getByCategoryId(categoryId)
            .executeAsList()
            .map { it.id }
        val impactedItemIds = (
            itemCategoryCrossRefQueries.getItemIdsByCategoryId(categoryId).executeAsList() +
                collectionIds
                    .flatMap { collectionId ->
                        crossRefQueries.getItemIdsByCollectionId(collectionId).executeAsList()
                    }
            ).distinct()

        mutateSyncable {
            itemCategoryCrossRefQueries.deleteByCategoryId(categoryId)
            collectionIds.forEach { collectionId ->
                crossRefQueries.deleteByCollectionId(collectionId)
                collectionQueries.deleteById(collectionId)
            }
            categoryQueries.deleteById(categoryId)

            if (moveItemsToDefault) {
                val now = currentTimeMillis()
                impactedItemIds.forEach { itemId ->
                    itemCategoryCrossRefQueries.insertCrossRef(itemId, defaultCategoryId, now)
                }
            }
            cleanupOrphanItems(impactedItemIds)
        }
        ensureDefaults()
    }

    override suspend fun moveCategory(categoryId: Long, moveUp: Boolean) {
        val categories = getCategories()
        val index = categories.indexOfFirst { it.id == categoryId }
        if (index == -1) return

        val targetIndex = if (moveUp) index - 1 else index + 1
        if (targetIndex !in categories.indices) return

        mutateSyncable {
            swapCategoryOrder(categories[index], categories[targetIndex])
        }
    }

    override suspend fun moveCategoryToIndex(categoryId: Long, targetIndex: Int) {
        val categories = getCategories().toMutableList()
        val currentIndex = categories.indexOfFirst { it.id == categoryId }
        if (currentIndex == -1) return

        val clampedTargetIndex = targetIndex.coerceIn(0, categories.lastIndex)
        if (currentIndex == clampedTargetIndex) return

        val moved = categories.removeAt(currentIndex)
        categories.add(clampedTargetIndex, moved)
        val now = currentTimeMillis()
        mutateSyncable {
            categories.forEachIndexed { index, category ->
                categoryQueries.updateCategoryOrder(index.toLong(), now, category.id)
            }
        }
    }

    override suspend fun createCollection(
        categoryId: Long,
        name: String,
        colorKey: String
    ): FavoriteCollection {
        val normalizedName = validateFavoriteName(name = name)
        val now = currentTimeMillis()
        val nextOrder = (collectionQueries.getMaxSortOrderByCategoryId(categoryId).executeAsOneOrNull()?.MAX ?: -1L) + 1L
        return mutateSyncable {
            collectionQueries.insertCollection(
                categoryId = categoryId,
                name = normalizedName,
                colorKey = colorKey,
                sortOrder = nextOrder,
                createdAt = now,
                updatedAt = now
            )
            collectionQueries.getLatestByCategoryId(categoryId).executeAsOne().also {
                collectionQueries.setSyncId(SyncIdentityGenerator.stableEntityId().value, it.id)
            }.let {
                collectionQueries.getById(it.id).executeAsOne().toModel()
            }
        }
    }

    override suspend fun updateCollection(collectionId: Long, name: String, colorKey: String) {
        val normalizedName = validateFavoriteName(name = name, excludeCollectionId = collectionId)
        mutateSyncable {
            collectionQueries.updateCollection(
                name = normalizedName,
                colorKey = colorKey,
                updatedAt = currentTimeMillis(),
                id = collectionId
            )
        }
    }

    override suspend fun deleteCollection(collectionId: Long) {
        val impactedItemIds = crossRefQueries.getItemIdsByCollectionId(collectionId).executeAsList()
        mutateSyncable {
            crossRefQueries.deleteByCollectionId(collectionId)
            collectionQueries.deleteById(collectionId)
            cleanupOrphanItems(impactedItemIds)
        }
    }

    override suspend fun moveCollection(collectionId: Long, moveUp: Boolean) {
        val collection = collectionQueries.getById(collectionId).executeAsOneOrNull() ?: return
        val collections = getCollections(collection.categoryId)
        val index = collections.indexOfFirst { it.id == collectionId }
        if (index == -1) return

        val targetIndex = if (moveUp) index - 1 else index + 1
        if (targetIndex !in collections.indices) return

        mutateSyncable {
            swapCollectionOrder(collections[index], collections[targetIndex])
        }
    }

    override suspend fun moveCollectionToIndex(collectionId: Long, targetIndex: Int) {
        val collection = collectionQueries.getById(collectionId).executeAsOneOrNull() ?: return
        val collections = getCollections(collection.categoryId).toMutableList()
        val currentIndex = collections.indexOfFirst { it.id == collectionId }
        if (currentIndex == -1) return

        val clampedTargetIndex = targetIndex.coerceIn(0, collections.lastIndex)
        if (currentIndex == clampedTargetIndex) return

        val moved = collections.removeAt(currentIndex)
        collections.add(clampedTargetIndex, moved)
        val now = currentTimeMillis()
        mutateSyncable {
            collections.forEachIndexed { index, item ->
                collectionQueries.updateCollectionOrder(index.toLong(), now, item.id)
            }
        }
    }

    override suspend fun addNormalThreadFavorite(
        tid: ThreadId,
        title: String,
        coverUrl: String?,
        lastUpdatedTime: Long?,
        forumId: ForumId?,
        forumName: String?,
        categoryIds: List<Long>,
        collectionIds: List<Long>
    ) {
        ensureDefaults()
        mutateSyncable {
            addThreadFavorite(
                targetType = FavoriteTargetType.ThreadNormal,
                tid = tid,
                title = title,
                authorId = null,
                coverUrl = coverUrl,
                lastUpdatedTime = lastUpdatedTime,
                forumId = forumId,
                forumName = forumName,
                categoryIds = categoryIds,
                collectionIds = collectionIds
            )
        }
        notifyFavoriteItemsChanged()
    }

    override suspend fun addNovelThreadFavorite(
        tid: ThreadId,
        title: String,
        authorId: UserId?,
        coverUrl: String?,
        lastUpdatedTime: Long?,
        forumId: ForumId?,
        forumName: String?,
        categoryIds: List<Long>,
        collectionIds: List<Long>
    ) {
        ensureDefaults()
        mutateSyncable {
            addThreadFavorite(
                targetType = FavoriteTargetType.ThreadNovel,
                tid = tid,
                title = title,
                authorId = authorId,
                coverUrl = coverUrl,
                lastUpdatedTime = lastUpdatedTime,
                forumId = forumId,
                forumName = forumName,
                categoryIds = categoryIds,
                collectionIds = collectionIds
            )
        }
        notifyFavoriteItemsChanged()
    }

    override suspend fun addTagMangaFavorite(
        tagId: TagId,
        tagName: String,
        coverUrl: String?,
        categoryIds: List<Long>,
        collectionIds: List<Long>
    ) {
        ensureDefaults()
        mutateSyncable {
            val now = currentTimeMillis()
            val normalizedCollections = collectionIds.distinct()
            val normalizedCategories = normalizeCategoryIds(categoryIds, normalizedCollections)
            val existing = itemQueries.findByTarget(
                targetType = FavoriteTargetType.TagManga.name,
                targetId = tagId.value.toLong(),
                authorId = 0L
            ).executeAsOneOrNull()
            upsertCanonicalCover(FavoriteTargetType.TagManga, tagId.value.toLong(), coverUrl, now)

            val itemId = if (existing != null) {
                val effectiveCoverUrl = coverUrl ?: existing.coverUrl
                if (existing.title != tagName || existing.coverUrl != effectiveCoverUrl) {
                    itemQueries.updateFavoriteItem(
                        title = tagName,
                        coverUrl = effectiveCoverUrl,
                        lastUpdatedTime = null,
                        forumId = null,
                        forumName = null,
                        authorId = 0L,
                        lastFavoriteStatusUpdateAt = now,
                        id = existing.id
                    )
                }
                existing.id
            } else {
                itemQueries.insertFavoriteItem(
                    targetType = FavoriteTargetType.TagManga.name,
                    targetId = tagId.value.toLong(),
                    title = tagName,
                    coverUrl = coverUrl,
                    lastUpdatedTime = null,
                    forumId = null,
                    forumName = null,
                    authorId = 0L,
                    createdAt = now,
                    lastFavoriteStatusUpdateAt = now
                )
                itemQueries.findByTarget(
                    targetType = FavoriteTargetType.TagManga.name,
                    targetId = tagId.value.toLong(),
                    authorId = 0L
                ).executeAsOne().id
            }
            mergeCategories(itemId, normalizedCategories)
            mergeCollections(itemId, normalizedCollections)
        }
        notifyFavoriteItemsChanged()
    }

    override suspend fun addRssSearchFavorite(
        subscriptionId: Long,
        title: String,
        coverUrl: String?,
        lastUpdatedTime: Long?,
        categoryIds: List<Long>,
        collectionIds: List<Long>,
    ) {
        ensureDefaults()
        mutateSyncable {
            addCatalogLikeFavorite(
                targetType = FavoriteTargetType.RssSearch,
                targetId = subscriptionId,
                title = title,
                coverUrl = coverUrl,
                lastUpdatedTime = lastUpdatedTime,
                categoryIds = categoryIds,
                collectionIds = collectionIds,
            )
        }
        notifyFavoriteItemsChanged()
    }

    override suspend fun isThreadFavorited(
        tid: ThreadId,
        threadType: ReadHistoryRepository.ThreadEntryType,
        authorId: UserId?
    ): Boolean {
        val targetType = if (threadType == ReadHistoryRepository.ThreadEntryType.Novel) {
            FavoriteTargetType.ThreadNovel
        } else {
            FavoriteTargetType.ThreadNormal
        }
        return itemQueries.findByTarget(
            targetType = targetType.name,
            targetId = tid.value.toLong(),
            authorId = authorId?.value?.toLong() ?: 0L
        ).executeAsOneOrNull() != null
    }

    override suspend fun isTagFavorited(tagId: TagId): Boolean {
        return itemQueries.findByTarget(
            targetType = FavoriteTargetType.TagManga.name,
            targetId = tagId.value.toLong(),
            authorId = 0L
        ).executeAsOneOrNull() != null
    }

    override suspend fun getFavoriteItem(
        targetType: FavoriteTargetType,
        targetId: Long,
        authorId: UserId?
    ): FavoriteItem? {
        return itemQueries.findByTarget(
            targetType = targetType.name,
            targetId = targetId,
            authorId = authorId?.value?.toLong() ?: 0L
        ).executeAsOneOrNull()?.toModel()
    }

    override suspend fun setItemLocations(
        itemId: Long,
        categoryIds: Set<Long>,
        collectionIds: Set<Long>
    ) {
        val normalizedCategories = categoryIds.distinct().toSet()
        val normalizedCollections = collectionIds.distinct().toSet()
        val now = currentTimeMillis()

        mutateSyncable {
            replaceItemLocations(itemId, normalizedCategories, normalizedCollections, now)
            cleanupOrphanItems(listOf(itemId))
        }
    }

    override suspend fun setItemsLocations(
        itemIds: Set<Long>,
        categoryIds: Set<Long>,
        collectionIds: Set<Long>
    ) {
        if (itemIds.isEmpty()) return
        val normalizedCategories = categoryIds.distinct().toSet()
        val normalizedCollections = collectionIds.distinct().toSet()
        val now = currentTimeMillis()

        mutateSyncable {
            itemIds.forEach { itemId ->
                replaceItemLocations(itemId, normalizedCategories, normalizedCollections, now)
            }
            cleanupOrphanItems(itemIds.toList())
        }
    }

    override suspend fun addItemsToLocations(
        itemIds: Set<Long>,
        categoryIds: Set<Long>,
        collectionIds: Set<Long>
    ) {
        if (itemIds.isEmpty()) return
        val normalizedCategories = categoryIds.distinct().toSet()
        val normalizedCollections = collectionIds.distinct().toSet()
        val now = currentTimeMillis()

        mutateSyncable {
            itemIds.forEach { itemId ->
                appendItemLocations(itemId, normalizedCategories, normalizedCollections, now)
            }
        }
    }

    override suspend fun setItemCollections(itemId: Long, collectionIds: Set<Long>) {
        setItemLocations(itemId, getCategoryIdsForItem(itemId), collectionIds)
    }

    override suspend fun addItemsToCollections(itemIds: Set<Long>, collectionIds: Set<Long>) {
        addItemsToLocations(itemIds, collectionIds = collectionIds)
    }

    override suspend fun removeItemsFromCategory(itemIds: Set<Long>, categoryId: Long) {
        if (itemIds.isEmpty()) return
        val now = currentTimeMillis()
        mutateSyncable {
            itemIds.forEach { itemId ->
                itemCategoryCrossRefQueries.deleteByItemIdAndCategoryId(itemId, categoryId)
                itemQueries.markFavoriteStatusUpdated(now, itemId)
            }
            cleanupOrphanItems(itemIds.toList())
        }
    }

    override suspend fun removeItemsFromCollections(itemIds: Set<Long>, collectionIds: Set<Long>) {
        if (itemIds.isEmpty() || collectionIds.isEmpty()) return
        val now = currentTimeMillis()
        mutateSyncable {
            itemIds.forEach { itemId ->
                collectionIds.forEach { collectionId ->
                    crossRefQueries.deleteByItemIdAndCollectionId(itemId, collectionId)
                }
                itemQueries.markFavoriteStatusUpdated(now, itemId)
            }
            cleanupOrphanItems(itemIds.toList())
        }
    }

    override suspend fun deleteFavoriteItems(itemIds: Set<Long>) {
        if (itemIds.isEmpty()) return

        mutateSyncable {
            itemIds.forEach { itemId ->
                itemCategoryCrossRefQueries.deleteByItemId(itemId)
                crossRefQueries.deleteByItemId(itemId)
                deleteFavoriteItemById(itemId)
            }
        }
        notifyFavoriteItemsChanged()
    }

    private fun notifyFavoriteItemsChanged() {
        favoriteItemRevisionState.update { it + 1L }
    }

    private fun addThreadFavorite(
        targetType: FavoriteTargetType,
        tid: ThreadId,
        title: String,
        authorId: UserId?,
        coverUrl: String?,
        lastUpdatedTime: Long?,
        forumId: ForumId?,
        forumName: String?,
        categoryIds: List<Long>,
        collectionIds: List<Long>
    ) {
        val now = currentTimeMillis()
        val normalizedCollections = collectionIds.distinct()
        val normalizedCategories = normalizeCategoryIds(categoryIds, normalizedCollections)
        val storedAuthorId = authorId?.value?.toLong() ?: 0L
        val existing = itemQueries.findByTarget(
            targetType = targetType.name,
            targetId = tid.value.toLong(),
            authorId = storedAuthorId
        ).executeAsOneOrNull()
        val effectiveLastUpdatedTime = lastUpdatedTime ?: existing?.lastUpdatedTime
        val effectiveCoverUrl = coverUrl ?: existing?.coverUrl
        upsertCanonicalCover(targetType, tid.value.toLong(), effectiveCoverUrl, now)

        val itemId = if (existing != null) {
            if (
                existing.title != title ||
                existing.coverUrl != effectiveCoverUrl ||
                existing.lastUpdatedTime != effectiveLastUpdatedTime ||
                existing.forumId != forumId?.value?.toLong() ||
                existing.forumName != forumName ||
                existing.authorId != storedAuthorId
            ) {
                itemQueries.updateFavoriteItem(
                    title = title,
                    coverUrl = effectiveCoverUrl,
                    lastUpdatedTime = effectiveLastUpdatedTime,
                    forumId = forumId?.value?.toLong(),
                    forumName = forumName,
                    authorId = storedAuthorId,
                    lastFavoriteStatusUpdateAt = now,
                    id = existing.id
                )
            }
            existing.id
        } else {
            itemQueries.insertFavoriteItem(
                targetType = targetType.name,
                targetId = tid.value.toLong(),
                title = title,
                coverUrl = effectiveCoverUrl,
                lastUpdatedTime = effectiveLastUpdatedTime,
                forumId = forumId?.value?.toLong(),
                forumName = forumName,
                authorId = storedAuthorId,
                createdAt = now,
                lastFavoriteStatusUpdateAt = now
            )
            itemQueries.findByTarget(
                targetType = targetType.name,
                targetId = tid.value.toLong(),
                authorId = storedAuthorId
            ).executeAsOne().id
        }

        mergeCategories(itemId, normalizedCategories)
        mergeCollections(itemId, normalizedCollections)
    }

    private fun addCatalogLikeFavorite(
        targetType: FavoriteTargetType,
        targetId: Long,
        title: String,
        coverUrl: String?,
        lastUpdatedTime: Long?,
        categoryIds: List<Long>,
        collectionIds: List<Long>,
    ) {
        val now = currentTimeMillis()
        val normalizedCollections = collectionIds.distinct()
        val normalizedCategories = normalizeCategoryIds(categoryIds, normalizedCollections)
        val existing = itemQueries.findByTarget(
            targetType = targetType.name,
            targetId = targetId,
            authorId = 0L
        ).executeAsOneOrNull()
        upsertCanonicalCover(targetType, targetId, coverUrl, now)

        val itemId = if (existing != null) {
            val effectiveCoverUrl = coverUrl ?: existing.coverUrl
            val effectiveLastUpdatedTime = lastUpdatedTime ?: existing.lastUpdatedTime
            if (
                existing.title != title ||
                existing.coverUrl != effectiveCoverUrl ||
                existing.lastUpdatedTime != effectiveLastUpdatedTime
            ) {
                itemQueries.updateFavoriteItem(
                    title = title,
                    coverUrl = effectiveCoverUrl,
                    lastUpdatedTime = effectiveLastUpdatedTime,
                    forumId = null,
                    forumName = null,
                    authorId = 0L,
                    lastFavoriteStatusUpdateAt = now,
                    id = existing.id
                )
            }
            existing.id
        } else {
            itemQueries.insertFavoriteItem(
                targetType = targetType.name,
                targetId = targetId,
                title = title,
                coverUrl = coverUrl,
                lastUpdatedTime = lastUpdatedTime,
                forumId = null,
                forumName = null,
                authorId = 0L,
                createdAt = now,
                lastFavoriteStatusUpdateAt = now
            )
            itemQueries.findByTarget(
                targetType = targetType.name,
                targetId = targetId,
                authorId = 0L
            ).executeAsOne().id
        }

        mergeCategories(itemId, normalizedCategories)
        mergeCollections(itemId, normalizedCollections)
    }

    private fun normalizeCategoryIds(
        categoryIds: List<Long>,
        collectionIds: List<Long>
    ): List<Long> {
        if (categoryIds.isNotEmpty()) return categoryIds.distinct()
        if (collectionIds.isNotEmpty()) return emptyList()
        return listOf(categoryQueries.getFirstCategory().executeAsOne().id)
    }

    private fun mergeCategories(itemId: Long, targetCategoryIds: List<Long>) {
        val existing = itemCategoryCrossRefQueries.getCategoryIdsByItemId(itemId).executeAsList().toMutableSet()
        val now = currentTimeMillis()
        targetCategoryIds.forEach { categoryId ->
            if (existing.add(categoryId)) {
                itemCategoryCrossRefQueries.insertCrossRef(itemId, categoryId, now)
            }
        }
    }

    private fun mergeCollections(itemId: Long, targetCollectionIds: List<Long>) {
        val existing = crossRefQueries.getCollectionIdsByItemId(itemId).executeAsList().toMutableSet()
        val now = currentTimeMillis()
        targetCollectionIds.forEach { collectionId ->
            if (existing.add(collectionId)) {
                crossRefQueries.insertCrossRef(itemId, collectionId, now)
            }
        }
    }

    private fun replaceItemLocations(
        itemId: Long,
        targetCategoryIds: Set<Long>,
        targetCollectionIds: Set<Long>,
        updatedAt: Long,
    ) {
        val existingCategories = itemCategoryCrossRefQueries.getCategoryIdsByItemId(itemId).executeAsList().toSet()
        val existingCollections = crossRefQueries.getCollectionIdsByItemId(itemId).executeAsList().toSet()

        val removedCategories = existingCategories.filterNot(targetCategoryIds::contains)
        removedCategories.forEach { categoryId ->
                itemCategoryCrossRefQueries.deleteByItemIdAndCategoryId(itemId, categoryId)
            }

        val addedCategories = targetCategoryIds.filterNot(existingCategories::contains)
        addedCategories.forEach { categoryId ->
                itemCategoryCrossRefQueries.insertCrossRef(itemId, categoryId, updatedAt)
            }

        val removedCollections = existingCollections.filterNot(targetCollectionIds::contains)
        removedCollections.forEach { collectionId ->
                crossRefQueries.deleteByItemIdAndCollectionId(itemId, collectionId)
            }

        val addedCollections = targetCollectionIds.filterNot(existingCollections::contains)
        addedCollections.forEach { collectionId ->
                crossRefQueries.insertCrossRef(itemId, collectionId, updatedAt)
            }

        if (
            removedCategories.isNotEmpty() ||
            addedCategories.isNotEmpty() ||
            removedCollections.isNotEmpty() ||
            addedCollections.isNotEmpty()
        ) {
            itemQueries.markFavoriteStatusUpdated(updatedAt, itemId)
        }
    }

    private fun appendItemLocations(
        itemId: Long,
        targetCategoryIds: Set<Long>,
        targetCollectionIds: Set<Long>,
        updatedAt: Long,
    ) {
        val existingCategories = itemCategoryCrossRefQueries.getCategoryIdsByItemId(itemId).executeAsList().toMutableSet()
        val existingCollections = crossRefQueries.getCollectionIdsByItemId(itemId).executeAsList().toMutableSet()

        var changed = false
        targetCategoryIds.forEach { categoryId ->
            if (existingCategories.add(categoryId)) {
                itemCategoryCrossRefQueries.insertCrossRef(itemId, categoryId, updatedAt)
                changed = true
            }
        }

        targetCollectionIds.forEach { collectionId ->
            if (existingCollections.add(collectionId)) {
                crossRefQueries.insertCrossRef(itemId, collectionId, updatedAt)
                changed = true
            }
        }

        if (changed) {
            itemQueries.markFavoriteStatusUpdated(updatedAt, itemId)
        }
    }

    private fun cleanupOrphanItems(itemIds: List<Long>) {
        itemIds.distinct().forEach { itemId ->
            if (
                crossRefQueries.countByItemId(itemId).executeAsOne() == 0L &&
                itemCategoryCrossRefQueries.countByItemId(itemId).executeAsOne() == 0L
            ) {
                deleteFavoriteItemById(itemId)
            }
        }
    }

    private fun deleteFavoriteItemById(itemId: Long) {
        val item = itemQueries.getById(itemId).executeAsOneOrNull()
        if (item?.targetType == FavoriteTargetType.RssSearch.name) {
            rssResultQueries.deleteBySubscription(item.targetId)
            rssSubscriptionQueries.deleteById(item.targetId)
        }
        itemQueries.deleteById(itemId)
    }

    private suspend fun validateFavoriteName(
        name: String,
        excludeCategoryId: Long? = null,
        excludeCollectionId: Long? = null,
    ): String {
        val normalizedName = name.trim()
        require(normalizedName.isNotBlank()) { i18n("名稱不能為空白") }

        val targetKey = normalizedName.lowercase()
        val categoryConflict = getCategories().firstOrNull {
            it.id != excludeCategoryId && it.name.trim().lowercase() == targetKey
        }
        if (categoryConflict != null) {
            throw IllegalArgumentException(i18n("名稱「{}」已被類別使用", normalizedName))
        }

        val collectionConflict = getAllCollections().firstOrNull {
            it.id != excludeCollectionId && it.name.trim().lowercase() == targetKey
        }
        if (collectionConflict != null) {
            throw IllegalArgumentException(i18n("名稱「{}」已被集合使用", normalizedName))
        }

        return normalizedName
    }

    private fun isDefaultCategory(categoryId: Long): Boolean {
        return categoryQueries.getById(categoryId).executeAsOneOrNull()?.name == DEFAULT_CATEGORY_NAME
    }

    private fun upsertCanonicalCover(
        targetType: FavoriteTargetType,
        targetId: Long,
        coverUrl: String?,
        updatedAt: Long,
    ) {
        val normalized = coverUrl?.let(::normalizeCoverUrl) ?: return
        val existing = contentCoverQueries.getByTarget(targetType.name, targetId).executeAsOneOrNull()
        contentCoverQueries.upsert(
            targetType = targetType.name,
            targetId = targetId,
            automaticCoverUrl = normalized,
            manualCoverUrl = existing?.manualCoverUrl,
            dynamicEnabled = existing?.dynamicEnabled ?: 1L,
            updatedAt = updatedAt,
        )
    }

    private fun swapCategoryOrder(
        first: FavoriteCategory,
        second: FavoriteCategory
    ) {
        val now = currentTimeMillis()
        categoryQueries.updateCategoryOrder(second.sortOrder, now, first.id)
        categoryQueries.updateCategoryOrder(first.sortOrder, now, second.id)
    }

    private fun swapCollectionOrder(
        first: FavoriteCollection,
        second: FavoriteCollection
    ) {
        val now = currentTimeMillis()
        collectionQueries.updateCollectionOrder(second.sortOrder, now, first.id)
        collectionQueries.updateCollectionOrder(first.sortOrder, now, second.id)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> mutateSyncable(mutation: () -> T): T {
        val recorder = mutationRecorder ?: return db.transactionWithResult { mutation() }
        var completed = false
        var result: Any? = null
        recorder.recordCommand {
            val before = captureSyncProjection()
            result = mutation()
            completed = true
            val after = captureSyncProjection()
            projectionDiff(before, after)
        }
        check(completed) { "Favorite mutation did not complete" }
        return result as T
    }

    private fun captureSyncProjection(): Map<ProjectedEntityKey, ProjectedEntity> {
        val categories = categoryQueries.getAll().executeAsList()
        val categorySyncIds = categories.mapNotNull { category ->
            category.syncId?.let { category.id to it }
        }.toMap()
        val collections = collectionQueries.getAll().executeAsList()
        val collectionSyncIds = collections.mapNotNull { collection ->
            collection.syncId?.let { collection.id to it }
        }.toMap()
        val items = itemQueries.getAll().executeAsList()
        val itemKeys = items.associate { item ->
            item.id to favoriteItemEntityId(item.targetType, item.targetId, item.authorId)
        }
        val result = linkedMapOf<ProjectedEntityKey, ProjectedEntity>()

        categories.forEach { category ->
            val syncId = category.syncId ?: return@forEach
            result.putEntity(
                domain = "favorite.category",
                entityId = syncId,
                fields = mapOf(
                    "name" to category.name,
                    "sortOrder" to category.sortOrder.toString(),
                    "createdAt" to category.createdAt.toString(),
                    "updatedAt" to category.updatedAt.toString(),
                ),
            )
        }
        collections.forEach { collection ->
            val syncId = collection.syncId ?: return@forEach
            val categorySyncId = categorySyncIds[collection.categoryId] ?: return@forEach
            result.putEntity(
                domain = "favorite.collection",
                entityId = syncId,
                fields = mapOf(
                    "categorySyncId" to categorySyncId,
                    "name" to collection.name,
                    "colorKey" to collection.colorKey,
                    "sortOrder" to collection.sortOrder.toString(),
                    "createdAt" to collection.createdAt.toString(),
                    "updatedAt" to collection.updatedAt.toString(),
                ),
            )
        }
        items.forEach { item ->
            result.putEntity(
                domain = "favorite.item",
                entityId = favoriteItemEntityId(item.targetType, item.targetId, item.authorId),
                fields = mapOf(
                    "targetType" to item.targetType,
                    "targetId" to item.targetId.toString(),
                    "authorId" to item.authorId.toString(),
                    "title" to item.title,
                    "coverUrl" to item.coverUrl,
                    "lastUpdatedTime" to item.lastUpdatedTime?.toString(),
                    "forumId" to item.forumId?.toString(),
                    "forumName" to item.forumName,
                    "createdAt" to item.createdAt.toString(),
                    "lastFavoriteStatusUpdateAt" to item.lastFavoriteStatusUpdateAt.toString(),
                ),
            )
        }
        itemCategoryCrossRefQueries.getAll().executeAsList().forEach { relation ->
            val itemKey = itemKeys[relation.itemId] ?: return@forEach
            val categorySyncId = categorySyncIds[relation.categoryId] ?: return@forEach
            result.putEntity(
                domain = "favorite.item-category",
                entityId = "$itemKey|$categorySyncId",
                fields = itemIdentityFields(itemKey) + mapOf(
                    "categorySyncId" to categorySyncId,
                    "createdAt" to relation.createdAt.toString(),
                ),
                relation = true,
            )
        }
        crossRefQueries.getAll().executeAsList().forEach { relation ->
            val itemKey = itemKeys[relation.itemId] ?: return@forEach
            val collectionSyncId = collectionSyncIds[relation.collectionId] ?: return@forEach
            result.putEntity(
                domain = "favorite.item-collection",
                entityId = "$itemKey|$collectionSyncId",
                fields = itemIdentityFields(itemKey) + mapOf(
                    "collectionSyncId" to collectionSyncId,
                    "createdAt" to relation.createdAt.toString(),
                ),
                relation = true,
            )
        }
        return result
    }

    private fun projectionDiff(
        before: Map<ProjectedEntityKey, ProjectedEntity>,
        after: Map<ProjectedEntityKey, ProjectedEntity>,
    ): List<LocalSyncOperationDraft> {
        val keys = (before.keys + after.keys).sortedWith(
            compareBy<ProjectedEntityKey>({ FAVORITE_DOMAIN_ORDER[it.domain] ?: Int.MAX_VALUE }, { it.entityId }),
        )
        return keys.mapNotNull { key ->
            val old = before[key]
            val new = after[key]
            when {
                old == null && new != null -> new.toDraft(
                    if (new.relation) SyncOperationKind.RelationAdd else SyncOperationKind.Put,
                )
                old != null && new == null -> old.toDraft(
                    if (old.relation) SyncOperationKind.RelationRemove else SyncOperationKind.Delete,
                )
                old != null && new != null && old.fields != new.fields -> {
                    if (new.relation) null else {
                        val changedFields = new.fields.filter { (field, value) -> old.fields[field] != value }
                        new.copy(fields = changedFields).toDraft(SyncOperationKind.Patch)
                    }
                }
                else -> null
            }
        }
    }

    private fun MutableMap<ProjectedEntityKey, ProjectedEntity>.putEntity(
        domain: String,
        entityId: String,
        fields: Map<String, String?>,
        relation: Boolean = false,
    ) {
        val key = ProjectedEntityKey(domain, entityId)
        put(key, ProjectedEntity(key, fields, relation))
    }

    private fun ProjectedEntity.toDraft(kind: SyncOperationKind) = LocalSyncOperationDraft(
        domainId = SyncDomainId(key.domain),
        entityId = SyncEntityId(key.entityId),
        kind = kind,
        fields = fields,
    )

    private fun favoriteItemEntityId(targetType: String, targetId: Long, authorId: Long): String =
        "$targetType|$targetId|$authorId"

    private fun itemIdentityFields(itemKey: String): Map<String, String?> {
        val parts = itemKey.split('|')
        require(parts.size == 3) { "Invalid favorite item key" }
        return mapOf(
            "targetType" to parts[0],
            "targetId" to parts[1],
            "authorId" to parts[2],
        )
    }

    private fun LocalFavoriteCategory.toModel(): FavoriteCategory {
        return FavoriteCategory(
            id = id,
            name = name,
            sortOrder = sortOrder,
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }

    private fun LocalFavoriteCollection.toModel(): FavoriteCollection {
        return FavoriteCollection(
            id = id,
            categoryId = categoryId,
            name = name,
            colorKey = colorKey,
            sortOrder = sortOrder,
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }

    private fun LocalFavoriteItem.toModel(): FavoriteItem {
        return FavoriteItem(
            id = id,
            targetType = FavoriteTargetType.fromStorage(targetType),
            targetId = targetId,
            title = title,
            coverUrl = coverUrl,
            lastUpdatedTime = lastUpdatedTime,
            forumId = forumId?.toInt()?.let(::ForumId),
            forumName = forumName,
            authorId = authorId.takeIf { it != 0L }?.toInt()?.let(::UserId),
            createdAt = createdAt,
            lastFavoriteStatusUpdateAt = lastFavoriteStatusUpdateAt
        )
    }

    private data class ProjectedEntityKey(
        val domain: String,
        val entityId: String,
    )

    private data class ProjectedEntity(
        val key: ProjectedEntityKey,
        val fields: Map<String, String?>,
        val relation: Boolean,
    )

    private companion object {
        val FAVORITE_DOMAIN_ORDER = mapOf(
            "favorite.category" to 10,
            "favorite.collection" to 20,
            "favorite.item" to 30,
            "favorite.item-category" to 40,
            "favorite.item-collection" to 50,
        )
    }
}

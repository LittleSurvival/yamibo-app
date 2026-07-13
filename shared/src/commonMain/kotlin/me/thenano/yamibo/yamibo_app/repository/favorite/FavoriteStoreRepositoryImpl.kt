package me.thenano.yamibo.yamibo_app.repository.favorite

import io.github.littlesurvival.dto.value.ForumId
import io.github.littlesurvival.dto.value.TagId
import io.github.littlesurvival.dto.value.ThreadId
import io.github.littlesurvival.dto.value.UserId
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
import me.thenano.yamibo.yamiboapp.LocalFavoriteCategory
import me.thenano.yamibo.yamiboapp.LocalFavoriteCollection
import me.thenano.yamibo.yamiboapp.LocalFavoriteItem

class FavoriteStoreRepositoryImpl(
    private val db: Database
) : FavoriteStoreRepository {
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
        categoryQueries.insertCategory(
            name = normalizedName,
            sortOrder = nextOrder,
            createdAt = now,
            updatedAt = now
        )
        return categoryQueries.getFirstByName(normalizedName).executeAsOne().toModel()
    }

    override suspend fun updateCategory(categoryId: Long, name: String) {
        if (isDefaultCategory(categoryId)) return
        val normalizedName = validateFavoriteName(name = name, excludeCategoryId = categoryId)
        categoryQueries.updateCategoryName(normalizedName, currentTimeMillis(), categoryId)
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

        db.transaction {
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
        }

        cleanupOrphanItems(impactedItemIds)
        ensureDefaults()
    }

    override suspend fun moveCategory(categoryId: Long, moveUp: Boolean) {
        val categories = getCategories()
        val index = categories.indexOfFirst { it.id == categoryId }
        if (index == -1) return

        val targetIndex = if (moveUp) index - 1 else index + 1
        if (targetIndex !in categories.indices) return

        swapCategoryOrder(categories[index], categories[targetIndex])
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
        db.transaction {
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
        collectionQueries.insertCollection(
            categoryId = categoryId,
            name = normalizedName,
            colorKey = colorKey,
            sortOrder = nextOrder,
            createdAt = now,
            updatedAt = now
        )
        return collectionQueries.getLatestByCategoryId(categoryId).executeAsOne().toModel()
    }

    override suspend fun updateCollection(collectionId: Long, name: String, colorKey: String) {
        val normalizedName = validateFavoriteName(name = name, excludeCollectionId = collectionId)
        collectionQueries.updateCollection(
            name = normalizedName,
            colorKey = colorKey,
            updatedAt = currentTimeMillis(),
            id = collectionId
        )
    }

    override suspend fun deleteCollection(collectionId: Long) {
        val impactedItemIds = crossRefQueries.getItemIdsByCollectionId(collectionId).executeAsList()
        db.transaction {
            crossRefQueries.deleteByCollectionId(collectionId)
            collectionQueries.deleteById(collectionId)
        }
        cleanupOrphanItems(impactedItemIds)
    }

    override suspend fun moveCollection(collectionId: Long, moveUp: Boolean) {
        val collection = collectionQueries.getById(collectionId).executeAsOneOrNull() ?: return
        val collections = getCollections(collection.categoryId)
        val index = collections.indexOfFirst { it.id == collectionId }
        if (index == -1) return

        val targetIndex = if (moveUp) index - 1 else index + 1
        if (targetIndex !in collections.indices) return

        swapCollectionOrder(collections[index], collections[targetIndex])
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
        db.transaction {
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

    override suspend fun addTagMangaFavorite(
        tagId: TagId,
        tagName: String,
        coverUrl: String?,
        categoryIds: List<Long>,
        collectionIds: List<Long>
    ) {
        ensureDefaults()
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
            itemQueries.updateFavoriteItem(
                title = tagName,
                coverUrl = coverUrl ?: existing.coverUrl,
                lastUpdatedTime = null,
                forumId = null,
                forumName = null,
                authorId = 0L,
                lastFavoriteStatusUpdateAt = now,
                id = existing.id
            )
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

    override suspend fun addRssSearchFavorite(
        subscriptionId: Long,
        title: String,
        coverUrl: String?,
        lastUpdatedTime: Long?,
        categoryIds: List<Long>,
        collectionIds: List<Long>,
    ) {
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

        db.transaction {
            replaceItemLocations(itemId, normalizedCategories, normalizedCollections, now)
        }

        cleanupOrphanItems(listOf(itemId))
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

        db.transaction {
            itemIds.forEach { itemId ->
                replaceItemLocations(itemId, normalizedCategories, normalizedCollections, now)
            }
        }
        cleanupOrphanItems(itemIds.toList())
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

        db.transaction {
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
        db.transaction {
            itemIds.forEach { itemId ->
                itemCategoryCrossRefQueries.deleteByItemIdAndCategoryId(itemId, categoryId)
                itemQueries.markFavoriteStatusUpdated(now, itemId)
            }
        }
        cleanupOrphanItems(itemIds.toList())
    }

    override suspend fun removeItemsFromCollections(itemIds: Set<Long>, collectionIds: Set<Long>) {
        if (itemIds.isEmpty() || collectionIds.isEmpty()) return
        val now = currentTimeMillis()
        db.transaction {
            itemIds.forEach { itemId ->
                collectionIds.forEach { collectionId ->
                    crossRefQueries.deleteByItemIdAndCollectionId(itemId, collectionId)
                }
                itemQueries.markFavoriteStatusUpdated(now, itemId)
            }
        }
        cleanupOrphanItems(itemIds.toList())
    }

    override suspend fun deleteFavoriteItems(itemIds: Set<Long>) {
        if (itemIds.isEmpty()) return

        db.transaction {
            itemIds.forEach { itemId ->
                itemCategoryCrossRefQueries.deleteByItemId(itemId)
                crossRefQueries.deleteByItemId(itemId)
                deleteFavoriteItemById(itemId)
            }
        }
    }

    private suspend fun addThreadFavorite(
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
        ensureDefaults()
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

    private suspend fun addCatalogLikeFavorite(
        targetType: FavoriteTargetType,
        targetId: Long,
        title: String,
        coverUrl: String?,
        lastUpdatedTime: Long?,
        categoryIds: List<Long>,
        collectionIds: List<Long>,
    ) {
        ensureDefaults()
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
            itemQueries.updateFavoriteItem(
                title = title,
                coverUrl = coverUrl ?: existing.coverUrl,
                lastUpdatedTime = lastUpdatedTime ?: existing.lastUpdatedTime,
                forumId = null,
                forumName = null,
                authorId = 0L,
                lastFavoriteStatusUpdateAt = now,
                id = existing.id
            )
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

    private suspend fun normalizeCategoryIds(
        categoryIds: List<Long>,
        collectionIds: List<Long>
    ): List<Long> {
        if (categoryIds.isNotEmpty()) return categoryIds.distinct()
        if (collectionIds.isNotEmpty()) return emptyList()
        ensureDefaults()
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

        existingCategories
            .filterNot(targetCategoryIds::contains)
            .forEach { categoryId ->
                itemCategoryCrossRefQueries.deleteByItemIdAndCategoryId(itemId, categoryId)
            }

        targetCategoryIds
            .filterNot(existingCategories::contains)
            .forEach { categoryId ->
                itemCategoryCrossRefQueries.insertCrossRef(itemId, categoryId, updatedAt)
            }

        existingCollections
            .filterNot(targetCollectionIds::contains)
            .forEach { collectionId ->
                crossRefQueries.deleteByItemIdAndCollectionId(itemId, collectionId)
            }

        targetCollectionIds
            .filterNot(existingCollections::contains)
            .forEach { collectionId ->
                crossRefQueries.insertCrossRef(itemId, collectionId, updatedAt)
            }

        itemQueries.markFavoriteStatusUpdated(updatedAt, itemId)
    }

    private fun appendItemLocations(
        itemId: Long,
        targetCategoryIds: Set<Long>,
        targetCollectionIds: Set<Long>,
        updatedAt: Long,
    ) {
        val existingCategories = itemCategoryCrossRefQueries.getCategoryIdsByItemId(itemId).executeAsList().toMutableSet()
        val existingCollections = crossRefQueries.getCollectionIdsByItemId(itemId).executeAsList().toMutableSet()

        targetCategoryIds.forEach { categoryId ->
            if (existingCategories.add(categoryId)) {
                itemCategoryCrossRefQueries.insertCrossRef(itemId, categoryId, updatedAt)
            }
        }

        targetCollectionIds.forEach { collectionId ->
            if (existingCollections.add(collectionId)) {
                crossRefQueries.insertCrossRef(itemId, collectionId, updatedAt)
            }
        }

        itemQueries.markFavoriteStatusUpdated(updatedAt, itemId)
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
}

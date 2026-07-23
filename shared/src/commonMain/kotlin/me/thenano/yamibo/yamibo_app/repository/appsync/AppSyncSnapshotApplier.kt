package me.thenano.yamibo.yamibo_app.repository.appsync

import me.thenano.yamibo.yamibo_app.Database
import me.thenano.yamibo.yamibo_app.repository.appsync.model.*

class AppSyncSnapshotApplier(
    private val db: Database,
    private val settingsPolicy: AppSyncSettingsPolicy,
) {
    fun apply(
        snapshot: AppSyncEnvelope,
        mode: AppSyncApplyMode,
    ): AppSyncResult<AppSyncImportSummary> {
        val settingsPreparation = settingsPolicy.prepare(
            imported = snapshot.settings,
            overwrite = mode == AppSyncApplyMode.Overwrite,
        )
        return try {
            db.transaction {
                if (mode == AppSyncApplyMode.Overwrite) clearInScopeData()
                applyHistory(snapshot.readingHistory, mode)
                applyFavorites(snapshot.favorites, mode)
            }
            applySettings(snapshot, settingsPreparation)
        } catch (error: Throwable) {
            AppSyncResult.Failure(
                AppSyncError.Apply("Unable to apply app-sync snapshot: ${error.message ?: error::class.simpleName}"),
            )
        }
    }

    private fun applySettings(
        snapshot: AppSyncEnvelope,
        preparation: AppSyncSettingsPolicy.Preparation,
    ): AppSyncResult<AppSyncImportSummary> {
        val applied = mutableListOf<AppSyncSettingsPolicy.PreparedSetting>()
        preparation.settings.forEach { setting ->
            val settingApplied = runCatching { settingsPolicy.apply(setting) }.getOrDefault(false)
            if (!settingApplied) {
                var rollbackIncomplete = false
                applied.asReversed().forEach {
                    if (!runCatching { settingsPolicy.rollback(it) }.getOrDefault(false)) {
                        rollbackIncomplete = true
                    }
                }
                return AppSyncResult.Failure(
                    AppSyncError.Apply(
                        message = "SQL data applied, but setting ${setting.next.key} failed",
                        settingsRollbackIncomplete = rollbackIncomplete,
                    ),
                )
            }
            applied += setting
        }
        return AppSyncResult.Success(
            AppSyncImportSummary(
                readingHistoryApplied = snapshot.readingHistory.totalCount(),
                favoritesApplied = snapshot.favorites.items.size,
                settingsApplied = applied.size,
                settingsSkipped = preparation.skipped,
                warnings = preparation.warnings,
            ),
        )
    }

    private fun clearInScopeData() {
        db.localFavoriteItemCategoryCrossRefQueries.deleteAll()
        db.localFavoriteItemCollectionCrossRefQueries.deleteAll()
        db.localFavoriteCollectionQueries.deleteAll()
        db.localFavoriteCategoryQueries.deleteAll()
        db.localFavoriteItemQueries.deleteAll()
        db.readingHistoryQueries.deleteAll()
        db.imageReadingHistoryQueries.deleteAll()
        db.mangaTagReadingHistoryQueries.deleteAll()
        db.tagCatalogReadingHistoryQueries.deleteAll()
        db.rssSearchReadingHistoryQueries.deleteAll()
        db.rssCatalogReadingHistoryQueries.deleteAll()
        db.readingTimeStatQueries.deleteAll()
    }

    private fun applyHistory(
        history: AppSyncReadingHistory,
        mode: AppSyncApplyMode,
    ) {
        val merging = mode == AppSyncApplyMode.Merge
        val threadTimes = if (merging) {
            db.readingHistoryQueries.getAll().executeAsList().associate {
                threadKey(it.threadId, it.threadType, it.authorId, it.historyOrigin) to it.lastVisitTime
            }
        } else emptyMap()
        history.thread.forEach {
            val existingTime = threadTimes[threadKey(it.threadId, it.threadType, it.authorId, it.historyOrigin)]
            if (existingTime == null || it.lastVisitTime >= existingTime) {
                db.readingHistoryQueries.upsert(
                    it.threadId,
                    it.threadType,
                    it.threadName,
                    it.threadCover,
                    it.forumName,
                    it.forumId,
                    it.authorId,
                    it.page,
                    it.postId,
                    it.postTitle,
                    it.anchorPostId,
                    it.anchorPostRatio,
                    it.anchorBlockId,
                    it.anchorBlockType,
                    it.anchorBlockRatio,
                    it.globalScrollY,
                    it.viewportHeight,
                    it.firstVisibleItemIndex,
                    it.firstVisibleItemOffset,
                    it.historyOrigin,
                    it.lastVisitTime,
                    it.lastUpdatedTime,
                )
            }
        }

        val imageTimes = if (merging) {
            db.imageReadingHistoryQueries.getAll().executeAsList().associate { it.postId to it.lastVisitTime }
        } else emptyMap()
        history.image.forEach {
            if (imageTimes[it.postId]?.let { existing -> it.lastVisitTime >= existing } != false) {
                db.imageReadingHistoryQueries.upsert(
                    it.postId,
                    it.threadId,
                    it.pageIndex,
                    it.totalPages,
                    it.firstVisibleItemIndex,
                    it.firstVisibleItemOffset,
                    it.lastVisitTime,
                )
            }
        }

        val tagMangaTimes = if (merging) {
            db.mangaTagReadingHistoryQueries.getAll().executeAsList().associate { it.tagId to it.lastVisitTime }
        } else emptyMap()
        history.tagManga.forEach {
            if (tagMangaTimes[it.tagId]?.let { existing -> it.lastVisitTime >= existing } != false) {
                db.mangaTagReadingHistoryQueries.upsert(
                    it.tagId,
                    it.tagName,
                    it.tagPage,
                    it.threadId,
                    it.threadTitle,
                    it.threadImagePageIndex,
                    it.threadImageTotalPages,
                    it.firstVisibleItemIndex,
                    it.firstVisibleItemOffset,
                    it.lastVisitTime,
                    it.coverUrl,
                )
            }
        }

        val tagCatalogTimes = if (merging) {
            db.tagCatalogReadingHistoryQueries.getAll().executeAsList().associate { it.tagId to it.lastVisitTime }
        } else emptyMap()
        history.tagCatalog.forEach {
            if (tagCatalogTimes[it.tagId]?.let { existing -> it.lastVisitTime >= existing } != false) {
                db.tagCatalogReadingHistoryQueries.upsert(
                    it.tagId,
                    it.tagName,
                    it.tagPage,
                    it.threadId,
                    it.threadTitle,
                    it.threadPage,
                    it.postId,
                    it.postTitle,
                    it.authorId,
                    it.anchorPostId,
                    it.anchorPostRatio,
                    it.anchorBlockId,
                    it.anchorBlockType,
                    it.anchorBlockRatio,
                    it.viewportHeight,
                    it.firstVisibleItemIndex,
                    it.firstVisibleItemOffset,
                    it.lastVisitTime,
                    it.coverUrl,
                )
            }
        }

        val rssSearchTimes = if (merging) {
            db.rssSearchReadingHistoryQueries.getAll().executeAsList().associate { it.subscriptionId to it.lastVisitTime }
        } else emptyMap()
        history.rssSearch.forEach {
            if (rssSearchTimes[it.subscriptionId]?.let { existing -> it.lastVisitTime >= existing } != false) {
                db.rssSearchReadingHistoryQueries.upsert(
                    it.subscriptionId,
                    it.subscriptionTitle,
                    it.subscriptionQuery,
                    it.subscriptionPage,
                    it.threadId,
                    it.threadTitle,
                    it.threadImagePageIndex,
                    it.threadImageTotalPages,
                    it.firstVisibleItemIndex,
                    it.firstVisibleItemOffset,
                    it.lastVisitTime,
                    it.coverUrl,
                )
            }
        }

        val rssCatalogTimes = if (merging) {
            db.rssCatalogReadingHistoryQueries.getAll().executeAsList().associate { it.subscriptionId to it.lastVisitTime }
        } else emptyMap()
        history.rssCatalog.forEach {
            if (rssCatalogTimes[it.subscriptionId]?.let { existing -> it.lastVisitTime >= existing } != false) {
                db.rssCatalogReadingHistoryQueries.upsert(
                    it.subscriptionId,
                    it.subscriptionTitle,
                    it.subscriptionQuery,
                    it.subscriptionPage,
                    it.threadId,
                    it.threadTitle,
                    it.threadPage,
                    it.postId,
                    it.postTitle,
                    it.authorId,
                    it.anchorPostId,
                    it.anchorPostRatio,
                    it.anchorBlockId,
                    it.anchorBlockType,
                    it.anchorBlockRatio,
                    it.viewportHeight,
                    it.firstVisibleItemIndex,
                    it.firstVisibleItemOffset,
                    it.lastVisitTime,
                    it.coverUrl,
                )
            }
        }

        val readingTimeUpdates = if (merging) {
            db.readingTimeStatQueries.getAll().executeAsList().associate { it.dateKey to it.updatedAt }
        } else emptyMap()
        history.readingTimeStats.forEach {
            if (readingTimeUpdates[it.dateKey]?.let { existing -> it.updatedAt >= existing } != false) {
                db.readingTimeStatQueries.upsert(it.dateKey, it.durationMillis, it.updatedAt)
            }
        }
    }

    private fun applyFavorites(
        favorites: AppSyncFavorites,
        mode: AppSyncApplyMode,
    ) {
        val merging = mode == AppSyncApplyMode.Merge
        val categoryIdMap = mutableMapOf<Long, Long>()
        val collectionIdMap = mutableMapOf<Long, Long>()
        val itemIdMap = mutableMapOf<Long, Long>()

        favorites.categories.sortedWith(compareBy({ it.sortOrder }, { it.snapshotId })).forEach { category ->
            val existing = if (merging) {
                db.localFavoriteCategoryQueries.getAll().executeAsList()
                    .firstOrNull { it.name.trim().equals(category.name.trim(), ignoreCase = true) }
            } else null
            val targetId = existing?.id ?: run {
                db.localFavoriteCategoryQueries.insertCategory(
                    category.name,
                    category.sortOrder,
                    category.createdAt,
                    category.updatedAt,
                )
                db.localFavoriteCategoryQueries.getFirstByName(category.name).executeAsOne().id
            }
            if (existing != null && category.updatedAt >= existing.updatedAt) {
                db.localFavoriteCategoryQueries.updateCategoryName(category.name, category.updatedAt, targetId)
                db.localFavoriteCategoryQueries.updateCategoryOrder(category.sortOrder, category.updatedAt, targetId)
            }
            categoryIdMap[category.snapshotId] = targetId
        }

        favorites.collections.sortedWith(compareBy({ it.sortOrder }, { it.snapshotId })).forEach { collection ->
            val categoryId = requireNotNull(categoryIdMap[collection.categorySnapshotId])
            val existing = if (merging) {
                db.localFavoriteCollectionQueries.getByCategoryId(categoryId).executeAsList()
                    .firstOrNull { it.name.trim().equals(collection.name.trim(), ignoreCase = true) }
            } else null
            val targetId = existing?.id ?: run {
                db.localFavoriteCollectionQueries.insertCollection(
                    categoryId,
                    collection.name,
                    collection.colorKey,
                    collection.sortOrder,
                    collection.createdAt,
                    collection.updatedAt,
                )
                db.localFavoriteCollectionQueries.getLatestByCategoryId(categoryId).executeAsOne().id
            }
            if (existing != null && collection.updatedAt >= existing.updatedAt) {
                db.localFavoriteCollectionQueries.updateCollection(
                    collection.name,
                    collection.colorKey,
                    collection.updatedAt,
                    targetId,
                )
                db.localFavoriteCollectionQueries.updateCollectionOrder(
                    collection.sortOrder,
                    collection.updatedAt,
                    targetId,
                )
            }
            collectionIdMap[collection.snapshotId] = targetId
        }

        favorites.items.sortedBy { it.snapshotId }.forEach { item ->
            val existing = if (merging) {
                db.localFavoriteItemQueries.findByTarget(item.targetType, item.targetId, item.authorId).executeAsOneOrNull()
            } else null
            val targetId = existing?.id ?: run {
                db.localFavoriteItemQueries.insertFavoriteItem(
                    item.targetType,
                    item.targetId,
                    item.title,
                    item.coverUrl,
                    item.lastUpdatedTime,
                    item.forumId,
                    item.forumName,
                    item.authorId,
                    item.createdAt,
                    item.lastFavoriteStatusUpdateAt,
                )
                db.localFavoriteItemQueries.findByTarget(item.targetType, item.targetId, item.authorId).executeAsOne().id
            }
            if (existing != null && item.lastFavoriteStatusUpdateAt >= existing.lastFavoriteStatusUpdateAt) {
                db.localFavoriteItemQueries.updateFavoriteItem(
                    item.title,
                    item.coverUrl,
                    item.lastUpdatedTime,
                    item.forumId,
                    item.forumName,
                    item.authorId,
                    item.lastFavoriteStatusUpdateAt,
                    targetId,
                )
            }
            itemIdMap[item.snapshotId] = targetId
        }

        favorites.itemCategoryRefs.forEach {
            db.localFavoriteItemCategoryCrossRefQueries.insertCrossRef(
                requireNotNull(itemIdMap[it.itemSnapshotId]),
                requireNotNull(categoryIdMap[it.categorySnapshotId]),
                it.createdAt,
            )
        }
        favorites.itemCollectionRefs.forEach {
            db.localFavoriteItemCollectionCrossRefQueries.insertCrossRef(
                requireNotNull(itemIdMap[it.itemSnapshotId]),
                requireNotNull(collectionIdMap[it.collectionSnapshotId]),
                it.createdAt,
            )
        }
    }

    private fun threadKey(threadId: Long, threadType: String, authorId: Long, origin: String): String =
        "$threadId|$threadType|$authorId|$origin"

    private fun AppSyncReadingHistory.totalCount(): Int =
        thread.size + image.size + tagManga.size + tagCatalog.size +
            rssSearch.size + rssCatalog.size + readingTimeStats.size
}

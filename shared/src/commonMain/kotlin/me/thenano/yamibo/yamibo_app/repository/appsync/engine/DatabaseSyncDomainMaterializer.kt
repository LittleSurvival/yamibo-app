package me.thenano.yamibo.yamibo_app.repository.appsync.engine

import me.thenano.yamibo.yamibo_app.Database
import me.thenano.yamibo.yamibo_app.repository.appsync.isAppSyncLocalOnlySetting
import me.thenano.yamibo.yamibo_app.repository.rss.rssSearchSubscriptionSyncId
import me.thenano.yamibo.yamibo_app.store.settings.SettingsStore

internal class DatabaseSyncDomainMaterializer(
    private val db: Database,
    private val settingsStore: SettingsStore,
) : SyncDomainMaterializer {
    override fun apply(entity: ResolvedSyncEntity) {
        when (entity.key.domainId.value) {
            "settings" -> applySetting(entity)
            "favorite.category" -> applyFavoriteCategory(entity)
            "favorite.collection" -> applyFavoriteCollection(entity)
            "favorite.item" -> applyFavoriteItem(entity)
            "rss.search-subscription" -> applyRssSearchSubscription(entity)
            "favorite.item-category" -> applyItemCategory(entity)
            "favorite.item-collection" -> applyItemCollection(entity)
            "favorite.update-event" -> applyFavoriteUpdateEvent(entity)
            "favorite.update-fid-filter" -> applyFavoriteUpdateFidChoice(entity)
            "favorite.update-category-filter" -> applyFavoriteUpdateCategoryChoice(entity)
            "detail-note" -> applyDetailNote(entity)
            "bookmark" -> applyBookmark(entity)
            "reading.thread" -> applyThreadHistory(entity)
            "reading.image" -> applyImageHistory(entity)
            "reading.tag-manga" -> applyTagHistory(entity)
            "reading.tag-catalog" -> applyTagCatalogHistory(entity)
            "reading.rss-search" -> applyRssSearchHistory(entity)
            "reading.rss-catalog" -> applyRssCatalogHistory(entity)
            "reading.time" -> applyReadingTime(entity)
            else -> error("No materializer for ${entity.key.domainId.value}")
        }
    }

    override fun reconcileProjections() {
        db.appSyncOperationQueries.getKnownSyncSettingKeys()
            .executeAsList()
            .filterNot(::isAppSyncLocalOnlySetting)
            .forEach(settingsStore::remove)
        db.appSyncOperationQueries.getSyncSettingValues().executeAsList()
            .filterNot { isAppSyncLocalOnlySetting(it.settingKey) }
            .forEach { setting ->
            when (setting.type) {
                "int" -> setting.settingValue?.toIntOrNull()?.let {
                    settingsStore.putInt(setting.settingKey, it)
                }
                "float" -> setting.settingValue?.toFloatOrNull()?.let {
                    settingsStore.putFloat(setting.settingKey, it)
                }
                "bool" -> setting.settingValue?.toBooleanStrictOrNull()?.let {
                    settingsStore.putBoolean(setting.settingKey, it)
                }
                "string", "enum" -> setting.settingValue?.let {
                    settingsStore.putString(setting.settingKey, it)
                }
            }
        }
        db.favoriteUpdateFidChoiceQueries.getAll().executeAsList().forEach { choice ->
            db.favoriteUpdateFidFilterQueries.setEnabled(
                choice.enabled,
                choice.updatedAt,
                choice.fid,
            )
        }
        db.favoriteUpdateCategoryChoiceQueries.getAll().executeAsList().forEach { choice ->
            val category = db.localFavoriteCategoryQueries.getBySyncId(choice.categorySyncId)
                .executeAsOneOrNull()
                ?: return@forEach
            db.favoriteUpdateCategoryFilterQueries.setEnabled(
                choice.enabled,
                choice.updatedAt,
                category.id,
            )
        }
    }

    override fun clearSyncableData() {
        db.appSyncOperationQueries.getSyncSettingValues()
            .executeAsList()
            .forEach { db.appSyncOperationQueries.recordKnownSyncSettingKey(it.settingKey) }
        db.localFavoriteItemCategoryCrossRefQueries.deleteAll()
        db.localFavoriteItemCollectionCrossRefQueries.deleteAll()
        db.localFavoriteCollectionQueries.deleteAll()
        db.localFavoriteCategoryQueries.deleteAll()
        db.localFavoriteItemQueries.deleteAll()
        clearRssSearchSubscriptions()
        db.detailNoteQueries.deleteAll()
        db.localBookMarkQueries.deleteAll()
        db.readingHistoryQueries.deleteAll()
        db.imageReadingHistoryQueries.deleteAll()
        db.mangaTagReadingHistoryQueries.deleteAll()
        db.tagCatalogReadingHistoryQueries.deleteAll()
        db.rssSearchReadingHistoryQueries.deleteAll()
        db.rssCatalogReadingHistoryQueries.deleteAll()
        db.readingTimeStatQueries.deleteAll()
        val now = 0L
        db.favoriteUpdateFidFilterQueries.getAll().executeAsList().forEach {
            db.favoriteUpdateFidFilterQueries.setEnabled(1, now, it.fid)
        }
        db.favoriteUpdateCategoryFilterQueries.getAll().executeAsList().forEach {
            db.favoriteUpdateCategoryFilterQueries.setEnabled(1, now, it.categoryId)
        }
        db.favoriteUpdateEventQueries.deleteAll()
        db.favoriteUpdateFidChoiceQueries.deleteAll()
        db.favoriteUpdateCategoryChoiceQueries.deleteAll()
        db.appSyncOperationQueries.clearSyncSettingValues()
    }

    private fun applySetting(entity: ResolvedSyncEntity) {
        if (isAppSyncLocalOnlySetting(entity.key.entityId.value)) return
        db.appSyncOperationQueries.recordKnownSyncSettingKey(entity.key.entityId.value)
        if (entity.tombstone != null) {
            db.appSyncOperationQueries.deleteSyncSettingValue(entity.key.entityId.value)
            return
        }
        val winner = entity.fields.values.maxByOrNull { it.operation.operationId } ?: return
        val fields = entity.values()
        db.appSyncOperationQueries.upsertSyncSettingValue(
            settingKey = entity.key.entityId.value,
            type = fields.require("type"),
            value_ = fields["value"],
            winnerOperationId = winner.operation.operationId.value,
            updatedAtEpochMillis = winner.operation.createdAtEpochMillis,
        )
    }

    private fun applyFavoriteCategory(entity: ResolvedSyncEntity) {
        val queries = db.localFavoriteCategoryQueries
        val existing = queries.getBySyncId(entity.key.entityId.value).executeAsOneOrNull()
        if (entity.tombstone != null) {
            existing?.let {
                db.localFavoriteItemCategoryCrossRefQueries.deleteByCategoryId(it.id)
                queries.deleteById(it.id)
            }
            return
        }
        val fields = entity.values()
        val now = fields.long("updatedAt")
        val id = existing?.id ?: run {
            queries.insertCategory(
                fields.require("name"),
                fields.long("sortOrder"),
                fields.long("createdAt"),
                now,
            )
            queries.getFirstByName(fields.require("name")).executeAsOne().id.also {
                queries.setSyncId(entity.key.entityId.value, it)
            }
        }
        queries.updateCategoryName(fields.require("name"), now, id)
        queries.updateCategoryOrder(fields.long("sortOrder"), now, id)
    }

    private fun applyFavoriteCollection(entity: ResolvedSyncEntity) {
        val queries = db.localFavoriteCollectionQueries
        val existing = queries.getBySyncId(entity.key.entityId.value).executeAsOneOrNull()
        if (entity.tombstone != null) {
            existing?.let {
                db.localFavoriteItemCollectionCrossRefQueries.deleteByCollectionId(it.id)
                queries.deleteById(it.id)
            }
            return
        }
        val fields = entity.values()
        val category = db.localFavoriteCategoryQueries
            .getBySyncId(fields.require("categorySyncId"))
            .executeAsOneOrNull()
            ?: error("Favorite collection references an unknown category")
        val now = fields.long("updatedAt")
        val id = existing?.id ?: run {
            queries.insertCollection(
                category.id,
                fields.require("name"),
                fields.require("colorKey"),
                fields.long("sortOrder"),
                fields.long("createdAt"),
                now,
            )
            queries.getLatestByCategoryId(category.id).executeAsOne().id.also {
                queries.setSyncId(entity.key.entityId.value, it)
            }
        }
        queries.updateCollection(fields.require("name"), fields.require("colorKey"), now, id)
        queries.updateCollectionOrder(fields.long("sortOrder"), now, id)
    }

    private fun applyFavoriteItem(entity: ResolvedSyncEntity) {
        val queries = db.localFavoriteItemQueries
        val fields = entity.values()
        val identity = entity.key.entityId.value.parts(3)
        val targetType = fields["targetType"] ?: identity[0]
        val targetId = fields["targetId"]?.toLongOrNull() ?: identity[1].toLong()
        val authorId = fields["authorId"]?.toLongOrNull() ?: identity[2].toLong()
        val existing = queries.findByTarget(targetType, targetId, authorId).executeAsOneOrNull()
        if (entity.tombstone != null) {
            existing?.let {
                db.localFavoriteItemCategoryCrossRefQueries.deleteByItemId(it.id)
                db.localFavoriteItemCollectionCrossRefQueries.deleteByItemId(it.id)
                queries.deleteById(it.id)
            }
            return
        }
        val statusAt = fields.long("lastFavoriteStatusUpdateAt")
        if (existing == null) {
            queries.insertFavoriteItem(
                targetType,
                targetId,
                fields.require("title"),
                fields["coverUrl"],
                fields["lastUpdatedTime"]?.toLongOrNull(),
                fields["forumId"]?.toLongOrNull(),
                fields["forumName"],
                authorId,
                fields.long("createdAt"),
                statusAt,
            )
        } else {
            queries.updateFavoriteItem(
                fields.require("title"),
                fields["coverUrl"],
                fields["lastUpdatedTime"]?.toLongOrNull(),
                fields["forumId"]?.toLongOrNull(),
                fields["forumName"],
                authorId,
                statusAt,
                existing.id,
            )
        }
    }

    private fun applyRssSearchSubscription(entity: ResolvedSyncEntity) {
        val queries = db.rssSearchSubscriptionQueries
        val existing = queries.getAll().executeAsList().firstOrNull {
            rssSearchSubscriptionSyncId(it.query, it.forumId) == entity.key.entityId.value
        }
        if (entity.tombstone != null) {
            existing?.let {
                db.rssSearchPageCacheQueries.deleteBySubscription(it.id)
                db.rssSearchSubscriptionResultQueries.deleteBySubscription(it.id)
                queries.deleteById(it.id)
            }
            return
        }
        val fields = entity.values()
        val updatedAt = fields.long("updatedAt")
        if (existing == null) {
            queries.insertSubscription(
                title = fields.require("title"),
                query = fields.require("query"),
                forumId = fields["forumId"]?.toLongOrNull(),
                forumName = fields["forumName"],
                enabled = fields.boolLong("enabled"),
                createdAt = fields.long("createdAt"),
                updatedAt = updatedAt,
                lastRefreshStartedAt = null,
                lastRefreshFinishedAt = null,
                lastRefreshStatus = null,
                lastRefreshMessage = null,
                lastSearchId = null,
                lastTotalCount = 0,
            )
        } else {
            queries.rename(fields.require("title"), updatedAt, existing.id)
            queries.setEnabled(fields.boolLong("enabled"), updatedAt, existing.id)
        }
    }

    private fun clearRssSearchSubscriptions() {
        db.rssSearchSubscriptionQueries.getAll().executeAsList().forEach {
            db.rssSearchPageCacheQueries.deleteBySubscription(it.id)
            db.rssSearchSubscriptionResultQueries.deleteBySubscription(it.id)
            db.rssSearchSubscriptionQueries.deleteById(it.id)
        }
    }

    private fun applyItemCategory(entity: ResolvedSyncEntity) {
        val fields = entity.values()
        val item = favoriteItem(fields) ?: return
        val category = db.localFavoriteCategoryQueries
            .getBySyncId(fields.require("categorySyncId"))
            .executeAsOneOrNull()
            ?: return
        if (entity.relationPresent == true) {
            db.localFavoriteItemCategoryCrossRefQueries.insertCrossRef(
                item.id,
                category.id,
                fields["createdAt"]?.toLongOrNull() ?: entity.relationOperation?.createdAtEpochMillis ?: 0,
            )
        } else {
            db.localFavoriteItemCategoryCrossRefQueries.deleteByItemIdAndCategoryId(item.id, category.id)
        }
    }

    private fun applyItemCollection(entity: ResolvedSyncEntity) {
        val fields = entity.values()
        val item = favoriteItem(fields) ?: return
        val collection = db.localFavoriteCollectionQueries
            .getBySyncId(fields.require("collectionSyncId"))
            .executeAsOneOrNull()
            ?: return
        if (entity.relationPresent == true) {
            db.localFavoriteItemCollectionCrossRefQueries.insertCrossRef(
                item.id,
                collection.id,
                fields["createdAt"]?.toLongOrNull() ?: entity.relationOperation?.createdAtEpochMillis ?: 0,
            )
        } else {
            db.localFavoriteItemCollectionCrossRefQueries
                .deleteByItemIdAndCollectionId(item.id, collection.id)
        }
    }

    private fun applyDetailNote(entity: ResolvedSyncEntity) {
        if (entity.tombstone != null) {
            val identity = entity.key.entityId.value.parts(3)
            db.detailNoteQueries.deleteByTarget(identity[0], identity[1].toLong(), identity[2].toLong())
            return
        }
        val fields = entity.values()
        db.detailNoteQueries.upsert(
            fields.require("targetType"),
            fields.long("targetId"),
            fields.long("authorId"),
            fields.require("content"),
            fields.long("createdAt"),
            fields.long("updatedAt"),
        )
    }

    private fun applyBookmark(entity: ResolvedSyncEntity) {
        if (entity.tombstone != null) {
            val identity = entity.key.entityId.value.parts(3)
            db.localBookMarkQueries.deleteByTarget(identity[0], identity[1].toLong(), identity[2].toLong())
            return
        }
        val fields = entity.values()
        db.localBookMarkQueries.upsert(
            fields.require("targetType"),
            fields.long("parentId"),
            fields.long("targetId"),
            fields.require("title"),
            fields.boolLong("bookmarked"),
            fields.boolLong("read"),
            fields.long("createdAt"),
            fields.long("updatedAt"),
        )
    }

    private fun applyThreadHistory(entity: ResolvedSyncEntity) {
        if (entity.tombstone != null) {
            val identity = entity.key.entityId.value.parts(4)
            db.readingHistoryQueries.deleteByThreadOrigin(
                identity[0].toLong(),
                identity[1],
                identity[2].toLong(),
                identity[3],
            )
            return
        }
        val fields = entity.values()
        db.readingHistoryQueries.upsert(
            threadId = fields.long("threadId"),
            threadType = fields.require("threadType"),
            threadName = fields.require("threadName"),
            threadCover = fields["threadCover"],
            forumName = fields["forumName"],
            forumId = fields["forumId"]?.toLongOrNull(),
            authorId = fields.long("authorId"),
            page = fields.long("page"),
            postId = fields.long("postId"),
            postTitle = fields.require("postTitle"),
            anchorPostId = fields.long("anchorPostId"),
            anchorPostRatio = fields["anchorPostRatio"]?.toDoubleOrNull(),
            anchorBlockId = fields["anchorBlockId"],
            anchorBlockType = fields["anchorBlockType"],
            anchorBlockRatio = fields["anchorBlockRatio"]?.toDoubleOrNull(),
            globalScrollY = fields["globalScrollY"]?.toLongOrNull(),
            viewportHeight = fields["viewportHeight"]?.toLongOrNull(),
            firstVisibleItemIndex = fields["firstVisibleItemIndex"]?.toLongOrNull(),
            firstVisibleItemOffset = fields["firstVisibleItemOffset"]?.toLongOrNull(),
            historyOrigin = fields.require("historyOrigin"),
            lastVisitTime = fields.long("lastVisitTime"),
            lastUpdatedTime = fields["lastUpdatedTime"]?.toLongOrNull(),
        )
    }

    private fun applyImageHistory(entity: ResolvedSyncEntity) {
        if (entity.tombstone != null) {
            db.imageReadingHistoryQueries.deleteByPostId(entity.key.entityId.value.toLong())
            return
        }
        val fields = entity.values()
        db.imageReadingHistoryQueries.upsert(
            fields.long("postId"),
            fields.long("threadId"),
            fields.long("pageIndex"),
            fields.long("totalPages"),
            fields["firstVisibleItemIndex"]?.toLongOrNull(),
            fields["firstVisibleItemOffset"]?.toLongOrNull(),
            fields.long("lastVisitTime"),
        )
    }

    private fun applyTagHistory(entity: ResolvedSyncEntity) {
        if (entity.tombstone != null) {
            db.mangaTagReadingHistoryQueries.deleteByTagId(entity.key.entityId.value.toLong())
            return
        }
        val fields = entity.values()
        db.mangaTagReadingHistoryQueries.upsert(
            fields.long("tagId"),
            fields.require("tagName"),
            fields.long("tagPage"),
            fields.long("threadId"),
            fields.require("threadTitle"),
            fields.long("threadImagePageIndex"),
            fields.long("threadImageTotalPages"),
            fields["firstVisibleItemIndex"]?.toLongOrNull(),
            fields["firstVisibleItemOffset"]?.toLongOrNull(),
            fields.long("lastVisitTime"),
            fields["coverUrl"],
        )
    }

    private fun applyTagCatalogHistory(entity: ResolvedSyncEntity) {
        if (entity.tombstone != null) {
            db.tagCatalogReadingHistoryQueries.deleteByTagId(entity.key.entityId.value.toLong())
            return
        }
        val fields = entity.values()
        db.tagCatalogReadingHistoryQueries.upsert(
            fields.long("tagId"),
            fields.require("tagName"),
            fields.long("tagPage"),
            fields.long("threadId"),
            fields.require("threadTitle"),
            fields.long("threadPage"),
            fields.long("postId"),
            fields.require("postTitle"),
            fields["authorId"]?.toLongOrNull(),
            fields.long("anchorPostId"),
            fields["anchorPostRatio"]?.toDoubleOrNull(),
            fields["anchorBlockId"],
            fields["anchorBlockType"],
            fields["anchorBlockRatio"]?.toDoubleOrNull(),
            fields["viewportHeight"]?.toLongOrNull(),
            fields["firstVisibleItemIndex"]?.toLongOrNull(),
            fields["firstVisibleItemOffset"]?.toLongOrNull(),
            fields.long("lastVisitTime"),
            fields["coverUrl"],
        )
    }

    private fun applyRssSearchHistory(entity: ResolvedSyncEntity) {
        val subscription = rssSubscription(entity.key.entityId.value)
        if (entity.tombstone != null) {
            subscription?.let {
                db.rssSearchReadingHistoryQueries.deleteBySubscriptionId(it.id)
            }
            return
        }
        val parent = requireNotNull(subscription) {
            "RSS search history references an unknown subscription"
        }
        val fields = entity.values()
        db.rssSearchReadingHistoryQueries.upsert(
            parent.id,
            fields.require("subscriptionTitle"),
            fields.require("subscriptionQuery"),
            fields.long("subscriptionPage"),
            fields.long("threadId"),
            fields.require("threadTitle"),
            fields.long("threadImagePageIndex"),
            fields.long("threadImageTotalPages"),
            fields["firstVisibleItemIndex"]?.toLongOrNull(),
            fields["firstVisibleItemOffset"]?.toLongOrNull(),
            fields.long("lastVisitTime"),
            fields["coverUrl"],
        )
    }

    private fun applyRssCatalogHistory(entity: ResolvedSyncEntity) {
        val subscription = rssSubscription(entity.key.entityId.value)
        if (entity.tombstone != null) {
            subscription?.let {
                db.rssCatalogReadingHistoryQueries.deleteBySubscriptionId(it.id)
            }
            return
        }
        val parent = requireNotNull(subscription) {
            "RSS catalog history references an unknown subscription"
        }
        val fields = entity.values()
        db.rssCatalogReadingHistoryQueries.upsert(
            parent.id,
            fields.require("subscriptionTitle"),
            fields.require("subscriptionQuery"),
            fields.long("subscriptionPage"),
            fields.long("threadId"),
            fields.require("threadTitle"),
            fields.long("threadPage"),
            fields.long("postId"),
            fields.require("postTitle"),
            fields["authorId"]?.toLongOrNull(),
            fields.long("anchorPostId"),
            fields["anchorPostRatio"]?.toDoubleOrNull(),
            fields["anchorBlockId"],
            fields["anchorBlockType"],
            fields["anchorBlockRatio"]?.toDoubleOrNull(),
            fields["viewportHeight"]?.toLongOrNull(),
            fields["firstVisibleItemIndex"]?.toLongOrNull(),
            fields["firstVisibleItemOffset"]?.toLongOrNull(),
            fields.long("lastVisitTime"),
            fields["coverUrl"],
        )
    }

    private fun rssSubscription(syncId: String) =
        db.rssSearchSubscriptionQueries.getAll().executeAsList().firstOrNull {
            rssSearchSubscriptionSyncId(
                it.query,
                it.forumId,
            ) == syncId
        }

    private fun applyReadingTime(entity: ResolvedSyncEntity) {
        val fields = entity.values()
        val dateKey = fields["dateKey"] ?: entity.key.entityId.value
        if (entity.tombstone != null) {
            db.readingTimeStatQueries.deleteByDateKey(dateKey)
        } else {
            db.readingTimeStatQueries.upsert(
                dateKey,
                fields.long("durationMillis"),
                fields.long("updatedAt"),
            )
        }
    }

    private fun applyFavoriteUpdateEvent(entity: ResolvedSyncEntity) {
        val queries = db.favoriteUpdateEventQueries
        val syncId = entity.key.entityId.value
        if (entity.tombstone != null) {
            queries.deleteBySyncId(syncId)
            return
        }
        val fields = entity.values()
        val readAt = fields["readAt"]?.toLongOrNull()
        val dismissedAt = fields["dismissedAt"]?.toLongOrNull()
        queries.upsertBySyncId(
            targetType = fields.require("targetType"),
            targetId = fields.long("targetId"),
            authorId = fields.long("authorId"),
            fid = fields["fid"]?.toLongOrNull(),
            forumName = fields["forumName"],
            title = fields.require("title"),
            latestPostTitle = fields["latestPostTitle"],
            mode = fields.require("mode"),
            summary = fields.require("summary"),
            detailIds = fields.require("detailIds"),
            coverUrl = fields["coverUrl"],
            detectedAt = fields.long("detectedAt"),
            readAt = readAt,
            dismissedAt = dismissedAt,
            ambiguous = fields.boolLong("ambiguous"),
            syncId = syncId,
            sourceFingerprint = fields.require("sourceFingerprint"),
            sourceDiscriminator = fields.require("sourceDiscriminator"),
        )
        queries.updateBySyncId(
            fid = fields["fid"]?.toLongOrNull(),
            forumName = fields["forumName"],
            title = fields.require("title"),
            latestPostTitle = fields["latestPostTitle"],
            summary = fields.require("summary"),
            coverUrl = fields["coverUrl"],
            readAt = readAt,
            dismissedAt = dismissedAt,
            syncId = syncId,
        )
    }

    private fun applyFavoriteUpdateFidChoice(entity: ResolvedSyncEntity) {
        val fields = entity.values()
        val winner = entity.fields["enabled"]?.operation
        db.favoriteUpdateFidChoiceQueries.upsertChoice(
            fid = fields.long("fid"),
            enabled = fields.boolLong("enabled"),
            winnerOperationId = winner?.operationId?.value,
            updatedAt = winner?.createdAtEpochMillis ?: 0L,
        )
    }

    private fun applyFavoriteUpdateCategoryChoice(entity: ResolvedSyncEntity) {
        val fields = entity.values()
        val winner = entity.fields["enabled"]?.operation
        db.favoriteUpdateCategoryChoiceQueries.upsertChoice(
            categorySyncId = fields.require("categorySyncId"),
            enabled = fields.boolLong("enabled"),
            winnerOperationId = winner?.operationId?.value,
            updatedAt = winner?.createdAtEpochMillis ?: 0L,
        )
    }

    private fun favoriteItem(fields: Map<String, String?>) =
        db.localFavoriteItemQueries.findByTarget(
            fields.require("targetType"),
            fields.long("targetId"),
            fields.long("authorId"),
        ).executeAsOneOrNull()

    private fun ResolvedSyncEntity.values(): Map<String, String?> =
        fields.mapValues { it.value.value }

    private fun Map<String, String?>.require(key: String): String =
        requireNotNull(this[key]) { "Missing materialized field: $key" }

    private fun Map<String, String?>.long(key: String): Long =
        require(key).toLong()

    private fun Map<String, String?>.boolLong(key: String): Long =
        if (require(key).toBooleanStrict()) 1L else 0L

    private fun String.parts(expectedSize: Int): List<String> =
        split('|').also {
            require(it.size == expectedSize && it.none(String::isBlank)) {
                "Invalid materialized entity identity"
            }
        }
}

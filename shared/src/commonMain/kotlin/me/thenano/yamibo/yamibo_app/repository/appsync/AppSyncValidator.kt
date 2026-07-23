package me.thenano.yamibo.yamibo_app.repository.appsync

import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncEnvelope
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncFormat

class AppSyncValidator {
    fun validate(snapshot: AppSyncEnvelope): List<String> = buildList {
        if (snapshot.schemaVersion != AppSyncFormat.CURRENT_SCHEMA_VERSION) {
            add("schemaVersion must be ${AppSyncFormat.CURRENT_SCHEMA_VERSION}")
        }
        if (snapshot.exportedAtEpochMillis < 0) add("exportedAtEpochMillis must be non-negative")
        validateHistory(snapshot, this)
        validateFavorites(snapshot, this)
        if (snapshot.settings.size > AppSyncFormat.MAX_SETTINGS) {
            add("settings exceeds ${AppSyncFormat.MAX_SETTINGS} items")
        }
        val duplicateSettings = snapshot.settings.groupingBy { it.key }.eachCount().filterValues { it > 1 }.keys
        if (duplicateSettings.isNotEmpty()) add("settings contains duplicate keys: ${duplicateSettings.sorted()}")
    }

    private fun validateHistory(snapshot: AppSyncEnvelope, errors: MutableList<String>) {
        val history = snapshot.readingHistory
        listOf(
            "thread" to history.thread.size,
            "image" to history.image.size,
            "tagManga" to history.tagManga.size,
            "tagCatalog" to history.tagCatalog.size,
            "rssSearch" to history.rssSearch.size,
            "rssCatalog" to history.rssCatalog.size,
            "readingTimeStats" to history.readingTimeStats.size,
        ).filter { it.second > AppSyncFormat.MAX_HISTORY_ITEMS_PER_SECTION }
            .forEach { errors += "${it.first} exceeds ${AppSyncFormat.MAX_HISTORY_ITEMS_PER_SECTION} items" }

        history.thread.forEachIndexed { index, item ->
            if (item.threadId <= 0) errors += "thread[$index].threadId must be positive"
            if (item.page < 1) errors += "thread[$index].page must be positive"
            if (item.lastVisitTime < 0) errors += "thread[$index].lastVisitTime must be non-negative"
            if (item.threadType !in VALID_THREAD_TYPES) errors += "thread[$index].threadType is invalid"
            if (item.historyOrigin !in VALID_HISTORY_ORIGINS) errors += "thread[$index].historyOrigin is invalid"
            if (item.anchorPostRatio?.isFinite() == false) errors += "thread[$index].anchorPostRatio must be finite"
            if (item.anchorBlockRatio?.isFinite() == false) errors += "thread[$index].anchorBlockRatio must be finite"
        }
        history.image.forEachIndexed { index, item ->
            if (item.postId <= 0 || item.threadId <= 0) errors += "image[$index] identifiers must be positive"
            if (item.pageIndex < 0 || item.totalPages < 0) errors += "image[$index] positions must be non-negative"
        }
        history.tagManga.forEachIndexed { index, item ->
            if (item.tagId <= 0 || item.threadId <= 0) errors += "tagManga[$index] identifiers must be positive"
        }
        history.tagCatalog.forEachIndexed { index, item ->
            if (item.tagId <= 0 || item.threadId <= 0) errors += "tagCatalog[$index] identifiers must be positive"
            if (item.anchorPostRatio?.isFinite() == false || item.anchorBlockRatio?.isFinite() == false) {
                errors += "tagCatalog[$index] ratios must be finite"
            }
        }
        history.rssSearch.forEachIndexed { index, item ->
            if (item.subscriptionId <= 0 || item.threadId <= 0) errors += "rssSearch[$index] identifiers must be positive"
        }
        history.rssCatalog.forEachIndexed { index, item ->
            if (item.subscriptionId <= 0 || item.threadId <= 0) errors += "rssCatalog[$index] identifiers must be positive"
            if (item.anchorPostRatio?.isFinite() == false || item.anchorBlockRatio?.isFinite() == false) {
                errors += "rssCatalog[$index] ratios must be finite"
            }
        }
        history.readingTimeStats.forEachIndexed { index, item ->
            if (item.dateKey.isBlank()) errors += "readingTimeStats[$index].dateKey must not be blank"
            if (item.durationMillis < 0) errors += "readingTimeStats[$index].durationMillis must be non-negative"
        }
    }

    private fun validateFavorites(snapshot: AppSyncEnvelope, errors: MutableList<String>) {
        val favorites = snapshot.favorites
        listOf(
            "categories" to favorites.categories.size,
            "collections" to favorites.collections.size,
            "items" to favorites.items.size,
            "itemCategoryRefs" to favorites.itemCategoryRefs.size,
            "itemCollectionRefs" to favorites.itemCollectionRefs.size,
        ).filter { it.second > AppSyncFormat.MAX_FAVORITE_ENTITIES_PER_SECTION }
            .forEach { errors += "${it.first} exceeds ${AppSyncFormat.MAX_FAVORITE_ENTITIES_PER_SECTION} items" }

        val categoryIds = favorites.categories.map { it.snapshotId }
        val collectionIds = favorites.collections.map { it.snapshotId }
        val itemIds = favorites.items.map { it.snapshotId }
        if (categoryIds.toSet().size != categoryIds.size) errors += "favorite category snapshot IDs must be unique"
        if (collectionIds.toSet().size != collectionIds.size) errors += "favorite collection snapshot IDs must be unique"
        if (itemIds.toSet().size != itemIds.size) errors += "favorite item snapshot IDs must be unique"
        if (categoryIds.any { it <= 0 } || collectionIds.any { it <= 0 } || itemIds.any { it <= 0 }) {
            errors += "favorite snapshot IDs must be positive"
        }

        val categorySet = categoryIds.toSet()
        val collectionSet = collectionIds.toSet()
        val itemSet = itemIds.toSet()
        favorites.collections.filter { it.categorySnapshotId !in categorySet }.forEach {
            errors += "collection ${it.snapshotId} references missing category ${it.categorySnapshotId}"
        }
        favorites.itemCategoryRefs.filter {
            it.itemSnapshotId !in itemSet || it.categorySnapshotId !in categorySet
        }.forEach {
            errors += "item-category reference ${it.itemSnapshotId}:${it.categorySnapshotId} is dangling"
        }
        favorites.itemCollectionRefs.filter {
            it.itemSnapshotId !in itemSet || it.collectionSnapshotId !in collectionSet
        }.forEach {
            errors += "item-collection reference ${it.itemSnapshotId}:${it.collectionSnapshotId} is dangling"
        }
        favorites.items.forEachIndexed { index, item ->
            if (item.targetId <= 0) errors += "favorite item[$index].targetId must be positive"
            if (item.targetType !in VALID_FAVORITE_TYPES) errors += "favorite item[$index].targetType is invalid"
        }
    }

    private companion object {
        val VALID_THREAD_TYPES = setOf("Normal", "Novel")
        val VALID_HISTORY_ORIGINS = setOf("Direct", "TagCatalog", "RssCatalog")
        val VALID_FAVORITE_TYPES = setOf("ThreadNormal", "ThreadNovel", "TagManga", "RssSearch")
    }
}

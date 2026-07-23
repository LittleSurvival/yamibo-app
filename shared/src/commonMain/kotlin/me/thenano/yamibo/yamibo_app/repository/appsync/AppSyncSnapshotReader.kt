package me.thenano.yamibo.yamibo_app.repository.appsync

import me.thenano.yamibo.yamibo_app.Database
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncEnvelope
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncFavoriteCategory
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncFavoriteCollection
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncFavoriteItem
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncFavoriteItemCategoryRef
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncFavoriteItemCollectionRef
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncFavorites
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncFormat
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncImageHistory
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncReadingHistory
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncReadingTimeStat
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncRssCatalogHistory
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncRssSearchHistory
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncTagCatalogHistory
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncTagMangaHistory
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncThreadHistory
import me.thenano.yamibo.yamibo_app.util.time.currentTimeMillis

class AppSyncSnapshotReader(
    private val db: Database,
    private val settingsPolicy: AppSyncSettingsPolicy,
    private val appVersionCode: Int,
    private val nowMillis: () -> Long = ::currentTimeMillis,
) {
    fun read(): AppSyncEnvelope = AppSyncEnvelope(
        schemaVersion = AppSyncFormat.CURRENT_SCHEMA_VERSION,
        exportedAtEpochMillis = nowMillis(),
        sourceAppVersionCode = appVersionCode,
        readingHistory = readHistory(),
        favorites = readFavorites(),
        settings = settingsPolicy.export(),
    )

    private fun readHistory(): AppSyncReadingHistory = AppSyncReadingHistory(
        thread = db.readingHistoryQueries.getAll().executeAsList().map {
            AppSyncThreadHistory(
                threadId = it.threadId,
                threadType = it.threadType,
                threadName = it.threadName,
                threadCover = it.threadCover,
                forumName = it.forumName,
                forumId = it.forumId,
                authorId = it.authorId,
                page = it.page,
                postId = it.postId,
                postTitle = it.postTitle,
                anchorPostId = it.anchorPostId,
                anchorPostRatio = it.anchorPostRatio,
                anchorBlockId = it.anchorBlockId,
                anchorBlockType = it.anchorBlockType,
                anchorBlockRatio = it.anchorBlockRatio,
                globalScrollY = it.globalScrollY,
                viewportHeight = it.viewportHeight,
                firstVisibleItemIndex = it.firstVisibleItemIndex,
                firstVisibleItemOffset = it.firstVisibleItemOffset,
                historyOrigin = it.historyOrigin,
                lastVisitTime = it.lastVisitTime,
                lastUpdatedTime = it.lastUpdatedTime,
            )
        },
        image = db.imageReadingHistoryQueries.getAll().executeAsList().map {
            AppSyncImageHistory(
                it.postId,
                it.threadId,
                it.pageIndex,
                it.totalPages,
                it.firstVisibleItemIndex,
                it.firstVisibleItemOffset,
                it.lastVisitTime,
            )
        },
        tagManga = db.mangaTagReadingHistoryQueries.getAll().executeAsList().map {
            AppSyncTagMangaHistory(
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
        },
        tagCatalog = db.tagCatalogReadingHistoryQueries.getAll().executeAsList().map {
            AppSyncTagCatalogHistory(
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
        },
        rssSearch = db.rssSearchReadingHistoryQueries.getAll().executeAsList().map {
            AppSyncRssSearchHistory(
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
        },
        rssCatalog = db.rssCatalogReadingHistoryQueries.getAll().executeAsList().map {
            AppSyncRssCatalogHistory(
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
        },
        readingTimeStats = db.readingTimeStatQueries.getAll().executeAsList().map {
            AppSyncReadingTimeStat(it.dateKey, it.durationMillis, it.updatedAt)
        },
    )

    private fun readFavorites(): AppSyncFavorites = AppSyncFavorites(
        categories = db.localFavoriteCategoryQueries.getAll().executeAsList().map {
            AppSyncFavoriteCategory(it.id, it.name, it.sortOrder, it.createdAt, it.updatedAt)
        },
        collections = db.localFavoriteCollectionQueries.getAll().executeAsList().map {
            AppSyncFavoriteCollection(it.id, it.categoryId, it.name, it.colorKey, it.sortOrder, it.createdAt, it.updatedAt)
        },
        items = db.localFavoriteItemQueries.getAll().executeAsList().map {
            AppSyncFavoriteItem(
                snapshotId = it.id,
                targetType = it.targetType,
                targetId = it.targetId,
                title = it.title,
                coverUrl = it.coverUrl,
                lastUpdatedTime = it.lastUpdatedTime,
                forumId = it.forumId,
                forumName = it.forumName,
                authorId = it.authorId,
                createdAt = it.createdAt,
                lastFavoriteStatusUpdateAt = it.lastFavoriteStatusUpdateAt,
            )
        },
        itemCategoryRefs = db.localFavoriteItemCategoryCrossRefQueries.getAll().executeAsList().map {
            AppSyncFavoriteItemCategoryRef(it.itemId, it.categoryId, it.createdAt)
        },
        itemCollectionRefs = db.localFavoriteItemCollectionCrossRefQueries.getAll().executeAsList().map {
            AppSyncFavoriteItemCollectionRef(it.itemId, it.collectionId, it.createdAt)
        },
    )
}

package me.thenano.yamibo.yamibo_app.repository.appsync.engine

import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncDomainId
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncEntityId
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperationKind
import me.thenano.yamibo.yamibo_app.repository.appsync.isAppSyncLocalOnlySetting
import me.thenano.yamibo.yamibo_app.repository.backup.YamiboBackupFile
import me.thenano.yamibo.yamibo_app.store.appsync.LocalSyncOperationDraft

internal data class BackupSnapshotMigrationPlan(
    val drafts: List<LocalSyncOperationDraft>,
    val skippedOrphanRssHistoryCount: Int,
)

internal class BackupSnapshotMigrationPlanner {
    fun plan(snapshot: YamiboBackupFile): List<LocalSyncOperationDraft> =
        planWithDiagnostics(snapshot).drafts

    fun planWithDiagnostics(snapshot: YamiboBackupFile): BackupSnapshotMigrationPlan {
        val categories = snapshot.favorites.categories.associateBy { it.localId }
        val categorySyncIds = categories.mapValues { (_, category) ->
            requireNotNull(category.syncId) { "Favorite category is missing a stable sync id" }
        }
        val collections = snapshot.favorites.collections.associateBy { it.localId }
        val collectionSyncIds = collections.mapValues { (_, collection) ->
            requireNotNull(collection.syncId) { "Favorite collection is missing a stable sync id" }
        }
        val items = snapshot.favorites.items.associateBy { it.localId }
        val rssSubscriptionSyncIds = snapshot.favorites.rssSubscriptions
            .associate { it.localId to it.syncId }
        var skippedOrphanRssHistoryCount = 0
        val drafts = buildList {
            snapshot.settings.filterNot { isAppSyncLocalOnlySetting(it.key) }.forEach {
                add(put("settings", it.key, mapOf("type" to it.type.serialName(), "value" to it.value)))
            }
            snapshot.favorites.categories.forEach {
                add(
                    put(
                        "favorite.category",
                        requireNotNull(it.syncId),
                        mapOf(
                            "name" to it.name,
                            "sortOrder" to it.sortOrder.toString(),
                            "createdAt" to it.createdAt.toString(),
                            "updatedAt" to it.updatedAt.toString(),
                        ),
                    ),
                )
            }
            snapshot.favorites.collections.forEach {
                add(
                    put(
                        "favorite.collection",
                        requireNotNull(it.syncId),
                        mapOf(
                            "categorySyncId" to requireNotNull(categorySyncIds[it.categoryLocalId]),
                            "name" to it.name,
                            "colorKey" to it.colorKey,
                            "sortOrder" to it.sortOrder.toString(),
                            "createdAt" to it.createdAt.toString(),
                            "updatedAt" to it.updatedAt.toString(),
                        ),
                    ),
                )
            }
            snapshot.favorites.items.forEach {
                add(put("favorite.item", it.entityId(), it.fields()))
            }
            snapshot.favorites.rssSubscriptions.forEach {
                add(
                    put(
                        "rss.search-subscription",
                        it.syncId,
                        mapOf(
                            "title" to it.title,
                            "query" to it.query,
                            "forumId" to it.forumId?.toString(),
                            "forumName" to it.forumName,
                            "enabled" to it.enabled.toString(),
                            "createdAt" to it.createdAt.toString(),
                            "updatedAt" to it.updatedAt.toString(),
                        ),
                    ),
                )
            }
            snapshot.favorites.itemCategories.forEach {
                val item = requireNotNull(items[it.itemLocalId])
                val categorySyncId = requireNotNull(categorySyncIds[it.categoryLocalId])
                add(
                    relation(
                        "favorite.item-category",
                        "${item.entityId()}|$categorySyncId",
                        item.identityFields() + mapOf(
                            "categorySyncId" to categorySyncId,
                            "createdAt" to it.createdAt.toString(),
                        ),
                    ),
                )
            }
            snapshot.favorites.itemCollections.forEach {
                val item = requireNotNull(items[it.itemLocalId])
                val collectionSyncId = requireNotNull(collectionSyncIds[it.collectionLocalId])
                add(
                    relation(
                        "favorite.item-collection",
                        "${item.entityId()}|$collectionSyncId",
                        item.identityFields() + mapOf(
                            "collectionSyncId" to collectionSyncId,
                            "createdAt" to it.createdAt.toString(),
                        ),
                    ),
                )
            }
            snapshot.notes.forEach {
                val entityId = "${it.targetType}|${it.targetId}|${it.authorId}"
                add(
                    put(
                        "detail-note",
                        entityId,
                        mapOf(
                            "targetType" to it.targetType,
                            "targetId" to it.targetId.toString(),
                            "authorId" to it.authorId.toString(),
                            "content" to it.content,
                            "createdAt" to it.createdAt.toString(),
                            "updatedAt" to it.updatedAt.toString(),
                        ),
                    ),
                )
            }
            snapshot.bookmarks.forEach {
                add(
                    put(
                        "bookmark",
                        "${it.targetType}|${it.parentId}|${it.targetId}",
                        mapOf(
                            "targetType" to it.targetType,
                            "parentId" to it.parentId.toString(),
                            "targetId" to it.targetId.toString(),
                            "title" to it.title,
                            "bookmarked" to it.bookmarked.toString(),
                            "read" to it.read.toString(),
                            "createdAt" to it.createdAt.toString(),
                            "updatedAt" to it.updatedAt.toString(),
                        ),
                    ),
                )
            }
            snapshot.readingState.threadHistory.forEach {
                add(
                    put(
                        "reading.thread",
                        "${it.threadId}|${it.threadType}|${it.authorId}|${it.historyOrigin}",
                        mapOf(
                            "threadId" to it.threadId.toString(),
                            "threadType" to it.threadType,
                            "authorId" to it.authorId.toString(),
                            "historyOrigin" to it.historyOrigin,
                            "threadName" to it.threadName,
                            "threadCover" to it.threadCover,
                            "forumName" to it.forumName,
                            "forumId" to it.forumId?.toString(),
                            "page" to it.page.toString(),
                            "postId" to it.postId.toString(),
                            "postTitle" to it.postTitle,
                            "anchorPostId" to it.anchorPostId.toString(),
                            "anchorPostRatio" to it.anchorPostRatio?.toString(),
                            "anchorBlockId" to it.anchorBlockId,
                            "anchorBlockType" to it.anchorBlockType,
                            "anchorBlockRatio" to it.anchorBlockRatio?.toString(),
                            "globalScrollY" to it.globalScrollY?.toString(),
                            "viewportHeight" to it.viewportHeight?.toString(),
                            "firstVisibleItemIndex" to it.firstVisibleItemIndex?.toString(),
                            "firstVisibleItemOffset" to it.firstVisibleItemOffset?.toString(),
                            "lastVisitTime" to it.lastVisitTime.toString(),
                            "lastUpdatedTime" to it.lastUpdatedTime?.toString(),
                        ),
                    ),
                )
            }
            snapshot.readingState.imageHistory.forEach {
                add(
                    put(
                        "reading.image",
                        it.postId.toString(),
                        mapOf(
                            "postId" to it.postId.toString(),
                            "threadId" to it.threadId.toString(),
                            "pageIndex" to it.pageIndex.toString(),
                            "totalPages" to it.totalPages.toString(),
                            "firstVisibleItemIndex" to it.firstVisibleItemIndex?.toString(),
                            "firstVisibleItemOffset" to it.firstVisibleItemOffset?.toString(),
                            "lastVisitTime" to it.lastVisitTime.toString(),
                        ),
                    ),
                )
            }
            snapshot.readingState.tagMangaHistory.forEach {
                add(
                    put(
                        "reading.tag-manga",
                        it.tagId.toString(),
                        mapOf(
                            "tagId" to it.tagId.toString(),
                            "tagName" to it.tagName,
                            "tagPage" to it.tagPage.toString(),
                            "threadId" to it.threadId.toString(),
                            "threadTitle" to it.threadTitle,
                            "threadImagePageIndex" to it.threadImagePageIndex.toString(),
                            "threadImageTotalPages" to it.threadImageTotalPages.toString(),
                            "firstVisibleItemIndex" to it.firstVisibleItemIndex?.toString(),
                            "firstVisibleItemOffset" to it.firstVisibleItemOffset?.toString(),
                            "lastVisitTime" to it.lastVisitTime.toString(),
                            "coverUrl" to it.coverUrl,
                        ),
                    ),
                )
            }
            snapshot.readingState.tagCatalogHistory.forEach {
                add(
                    put(
                        "reading.tag-catalog",
                        it.tagId.toString(),
                        mapOf(
                            "tagId" to it.tagId.toString(),
                            "tagName" to it.tagName,
                            "tagPage" to it.tagPage.toString(),
                            "threadId" to it.threadId.toString(),
                            "threadTitle" to it.threadTitle,
                            "threadPage" to it.threadPage.toString(),
                            "postId" to it.postId.toString(),
                            "postTitle" to it.postTitle,
                            "authorId" to it.authorId?.toString(),
                            "anchorPostId" to it.anchorPostId.toString(),
                            "anchorPostRatio" to it.anchorPostRatio?.toString(),
                            "anchorBlockId" to it.anchorBlockId,
                            "anchorBlockType" to it.anchorBlockType,
                            "anchorBlockRatio" to it.anchorBlockRatio?.toString(),
                            "viewportHeight" to it.viewportHeight?.toString(),
                            "firstVisibleItemIndex" to it.firstVisibleItemIndex?.toString(),
                            "firstVisibleItemOffset" to it.firstVisibleItemOffset?.toString(),
                            "lastVisitTime" to it.lastVisitTime.toString(),
                            "coverUrl" to it.coverUrl,
                        ),
                    ),
                )
            }
            snapshot.readingState.rssSearchHistory.forEach {
                val syncId = rssSubscriptionSyncIds[it.subscriptionId] ?: run {
                    skippedOrphanRssHistoryCount++
                    return@forEach
                }
                add(
                    put(
                        "reading.rss-search",
                        syncId,
                        mapOf(
                            "subscriptionSyncId" to syncId,
                            "subscriptionTitle" to it.subscriptionTitle,
                            "subscriptionQuery" to it.subscriptionQuery,
                            "subscriptionPage" to it.subscriptionPage.toString(),
                            "threadId" to it.threadId.toString(),
                            "threadTitle" to it.threadTitle,
                            "threadImagePageIndex" to it.threadImagePageIndex.toString(),
                            "threadImageTotalPages" to it.threadImageTotalPages.toString(),
                            "firstVisibleItemIndex" to it.firstVisibleItemIndex?.toString(),
                            "firstVisibleItemOffset" to it.firstVisibleItemOffset?.toString(),
                            "lastVisitTime" to it.lastVisitTime.toString(),
                            "coverUrl" to it.coverUrl,
                        ),
                    ),
                )
            }
            snapshot.readingState.rssCatalogHistory.forEach {
                val syncId = rssSubscriptionSyncIds[it.subscriptionId] ?: run {
                    skippedOrphanRssHistoryCount++
                    return@forEach
                }
                add(
                    put(
                        "reading.rss-catalog",
                        syncId,
                        mapOf(
                            "subscriptionSyncId" to syncId,
                            "subscriptionTitle" to it.subscriptionTitle,
                            "subscriptionQuery" to it.subscriptionQuery,
                            "subscriptionPage" to it.subscriptionPage.toString(),
                            "threadId" to it.threadId.toString(),
                            "threadTitle" to it.threadTitle,
                            "threadPage" to it.threadPage.toString(),
                            "postId" to it.postId.toString(),
                            "postTitle" to it.postTitle,
                            "authorId" to it.authorId?.toString(),
                            "anchorPostId" to it.anchorPostId.toString(),
                            "anchorPostRatio" to it.anchorPostRatio?.toString(),
                            "anchorBlockId" to it.anchorBlockId,
                            "anchorBlockType" to it.anchorBlockType,
                            "anchorBlockRatio" to it.anchorBlockRatio?.toString(),
                            "viewportHeight" to it.viewportHeight?.toString(),
                            "firstVisibleItemIndex" to it.firstVisibleItemIndex?.toString(),
                            "firstVisibleItemOffset" to it.firstVisibleItemOffset?.toString(),
                            "lastVisitTime" to it.lastVisitTime.toString(),
                            "coverUrl" to it.coverUrl,
                        ),
                    ),
                )
            }
            snapshot.readingState.readingTimeStats.forEach {
                add(
                    put(
                        "reading.time",
                        it.dateKey,
                        mapOf(
                            "dateKey" to it.dateKey,
                            "durationMillis" to it.durationMillis.toString(),
                            "updatedAt" to it.updatedAt.toString(),
                        ),
                    ),
                )
            }
            snapshot.favoriteUpdates.events.forEach {
                add(
                    put(
                        "favorite.update-event",
                        it.syncId,
                        mapOf(
                            "targetType" to it.targetType,
                            "targetId" to it.targetId.toString(),
                            "authorId" to (it.authorId ?: 0L).toString(),
                            "fid" to it.fid?.toString(),
                            "forumName" to it.forumName,
                            "title" to it.title,
                            "latestPostTitle" to it.latestPostTitle,
                            "mode" to it.mode,
                            "summary" to it.summary,
                            "detailIds" to it.detailIds.distinct().sorted().joinToString(","),
                            "coverUrl" to it.coverUrl,
                            "detectedAt" to it.detectedAt.toString(),
                            "readAt" to it.readAt?.toString(),
                            "dismissedAt" to it.dismissedAt?.toString(),
                            "ambiguous" to it.ambiguous.toString(),
                            "sourceFingerprint" to it.sourceFingerprint,
                            "sourceDiscriminator" to it.sourceDiscriminator,
                        ),
                    ),
                )
            }
            snapshot.favoriteUpdates.fidFilters.forEach {
                add(
                    put(
                        "favorite.update-fid-filter",
                        "fid:${it.fid}",
                        mapOf("fid" to it.fid.toString(), "enabled" to it.enabled.toString()),
                    ),
                )
            }
            snapshot.favoriteUpdates.categoryFilters.forEach {
                add(
                    put(
                        "favorite.update-category-filter",
                        "category:${it.categorySyncId}",
                        mapOf(
                            "categorySyncId" to it.categorySyncId,
                            "enabled" to it.enabled.toString(),
                        ),
                    ),
                )
            }
        }
        return BackupSnapshotMigrationPlan(drafts, skippedOrphanRssHistoryCount)
    }

    private fun me.thenano.yamibo.yamibo_app.repository.backup.BackupFavoriteItem.entityId() =
        "$targetType|$targetId|$authorId"

    private fun me.thenano.yamibo.yamibo_app.repository.backup.BackupFavoriteItem.identityFields() =
        mapOf(
            "targetType" to targetType,
            "targetId" to targetId.toString(),
            "authorId" to authorId.toString(),
        )

    private fun me.thenano.yamibo.yamibo_app.repository.backup.BackupFavoriteItem.fields() =
        identityFields() + mapOf(
            "title" to title,
            "coverUrl" to coverUrl,
            "lastUpdatedTime" to lastUpdatedTime?.toString(),
            "forumId" to forumId?.toString(),
            "forumName" to forumName,
            "createdAt" to createdAt.toString(),
            "lastFavoriteStatusUpdateAt" to lastFavoriteStatusUpdateAt.toString(),
        )

    private fun me.thenano.yamibo.yamibo_app.repository.backup.BackupSettingType.serialName() =
        when (this) {
            me.thenano.yamibo.yamibo_app.repository.backup.BackupSettingType.Int -> "int"
            me.thenano.yamibo.yamibo_app.repository.backup.BackupSettingType.Float -> "float"
            me.thenano.yamibo.yamibo_app.repository.backup.BackupSettingType.Bool -> "bool"
            me.thenano.yamibo.yamibo_app.repository.backup.BackupSettingType.String -> "string"
            me.thenano.yamibo.yamibo_app.repository.backup.BackupSettingType.Enum -> "enum"
        }

    private fun put(domain: String, entityId: String, fields: Map<String, String?>) =
        LocalSyncOperationDraft(
            domainId = SyncDomainId(domain),
            entityId = SyncEntityId(entityId),
            kind = SyncOperationKind.Put,
            fields = fields,
        )

    private fun relation(domain: String, entityId: String, fields: Map<String, String?>) =
        LocalSyncOperationDraft(
            domainId = SyncDomainId(domain),
            entityId = SyncEntityId(entityId),
            kind = SyncOperationKind.RelationAdd,
            fields = fields,
        )
}

package me.thenano.yamibo.yamibo_app.repository.appsync

import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncBulkDeleteProofFields

/**
 * Explicit v1/v2 field inventory. No prefix/wildcard grants permission to new fields or domains.
 * Identity and parent labels remain here until compatible reconstruction adapters exist; this is
 * not the compact v3 schema. Covers are declared separately as excluded cache fields.
 */
internal object AppSyncLegacyFieldRegistry {
    val fieldsByDomain: Map<String, Set<String>> = buildMap {
        fun declare(domain: String, fields: String) {
            val names = fields.split(' ').filter(String::isNotEmpty)
            check(names.distinct().size == names.size)
            check(put(domain, names.toSet()) == null)
        }
        declare("settings", "type value")
        declare("favorite.item", "targetType targetId authorId title createdAt " +
            "lastFavoriteStatusUpdateAt lastUpdatedTime forumId forumName")
        declare("favorite.category", "name sortOrder createdAt updatedAt")
        declare("favorite.collection", "categorySyncId name colorKey sortOrder createdAt updatedAt")
        declare("rss.search-subscription", "title query forumId forumName enabled createdAt updatedAt")
        declare("detail-note", "targetType targetId authorId content createdAt updatedAt")
        declare("bookmark", "targetType parentId targetId title bookmarked read createdAt updatedAt")
        declare("reading.thread", "threadId threadType authorId historyOrigin threadName " +
            "lastUpdatedTime forumName forumId page postId postTitle anchorPostId anchorPostRatio " +
            "anchorBlockId anchorBlockType anchorBlockRatio globalScrollY viewportHeight " +
            "firstVisibleItemIndex firstVisibleItemOffset lastVisitTime")
        declare("reading.image", "postId threadId pageIndex totalPages firstVisibleItemIndex " +
            "firstVisibleItemOffset lastVisitTime")
        declare("reading.tag-manga", "tagId tagName tagPage threadId threadTitle threadImagePageIndex " +
            "threadImageTotalPages firstVisibleItemIndex firstVisibleItemOffset lastVisitTime")
        declare("reading.tag-catalog", "tagId tagName tagPage threadId threadTitle threadPage postId " +
            "postTitle authorId anchorPostId anchorPostRatio anchorBlockId anchorBlockType " +
            "anchorBlockRatio viewportHeight firstVisibleItemIndex firstVisibleItemOffset lastVisitTime")
        declare("reading.rss-search", "subscriptionSyncId subscriptionTitle subscriptionQuery " +
            "subscriptionPage threadId threadTitle threadImagePageIndex threadImageTotalPages " +
            "firstVisibleItemIndex firstVisibleItemOffset lastVisitTime")
        declare("reading.rss-catalog", "subscriptionSyncId subscriptionTitle subscriptionQuery " +
            "subscriptionPage threadId threadTitle threadPage postId postTitle authorId anchorPostId " +
            "anchorPostRatio anchorBlockId anchorBlockType anchorBlockRatio viewportHeight " +
            "firstVisibleItemIndex firstVisibleItemOffset lastVisitTime")
        declare("reading.time", "dateKey durationMillis updatedAt")
        declare("favorite.item-category", "targetType targetId authorId categorySyncId createdAt")
        declare("favorite.item-collection", "targetType targetId authorId collectionSyncId createdAt")
        declare("favorite.update-event", "targetType targetId authorId fid forumName title latestPostTitle " +
            "mode summary detailIds detectedAt ambiguous sourceFingerprint sourceDiscriminator readAt dismissedAt")
        declare("favorite.update-fid-filter", "fid enabled")
        declare("favorite.update-category-filter", "categorySyncId enabled")
    }

    // V2 embeds authorization in delete fields. Never drop proof before the batch-metadata adapter.
    val proofFields: Set<String> = setOf(
        AppSyncBulkDeleteProofFields.SCOPE,
        AppSyncBulkDeleteProofFields.COUNT,
        AppSyncBulkDeleteProofFields.EXPIRES_AT,
    )

    fun permits(domain: String, field: String): Boolean =
        fieldsByDomain[domain]?.let { field in it || field in proofFields } == true
}

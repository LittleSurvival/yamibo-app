package me.thenano.yamibo.yamibo_app.repository.appsync.schema

import me.thenano.yamibo.yamibo_app.repository.appsync.AppSyncLegacyFieldRegistry
import me.thenano.yamibo.yamibo_app.repository.appsync.AppSyncPortabilityPolicy

internal enum class AppSyncFieldClass {
    Essential, BoundedPresentation, Derived, ParentJoinable, Cache, DeviceLocal,
}

internal data class AppSyncCanonicalField(
    val id: Int,
    val name: String,
    val type: AppSyncValueType,
    val nullable: Boolean,
    val classification: AppSyncFieldClass,
    val maxUtf8Bytes: Int,
    val allowMarkup: Boolean = false,
) {
    val portable: Boolean get() = classification == AppSyncFieldClass.Essential ||
        classification == AppSyncFieldClass.BoundedPresentation
}

internal data class AppSyncCanonicalDomain(
    val id: Int,
    val name: String,
    val fields: Map<String, AppSyncCanonicalField>,
) {
    val fieldsById = fields.values.associateBy { it.id }
}

/**
 * Stable identifiers for the canonical v3 model. The legacy inventory supplies membership only;
 * every member must have an explicit typed declaration here. No hash, sort order or enum ordinal
 * is ever used to allocate a wire ID. Removed IDs must remain reserved.
 */
internal object AppSyncCanonicalSchema {
    const val VERSION = 3
    const val TITLE_BYTES = 512
    const val ENTITY_BYTES = 256 * 1024

    private val domainIds = mapOf(
        "settings" to 1, "favorite.item" to 2, "rss.search-subscription" to 3,
        "favorite.category" to 4, "favorite.collection" to 5, "detail-note" to 6,
        "bookmark" to 7, "reading.thread" to 8, "reading.image" to 9,
        "reading.tag-manga" to 10, "reading.tag-catalog" to 11, "reading.rss-search" to 12,
        "reading.rss-catalog" to 13, "reading.time" to 14, "favorite.item-category" to 15,
        "favorite.item-collection" to 16, "favorite.update-event" to 17,
        "favorite.update-fid-filter" to 18, "favorite.update-category-filter" to 19,
    )

    private data class Scalar(val id: Int, val name: String, val type: AppSyncValueType)
    private val scalars = listOf(
        Scalar(1, "type", AppSyncValueType.Enum), Scalar(2, "value", AppSyncValueType.Text),
        Scalar(3, "targetType", AppSyncValueType.Enum), Scalar(4, "targetId", AppSyncValueType.Integer),
        Scalar(5, "authorId", AppSyncValueType.Integer), Scalar(6, "title", AppSyncValueType.Text),
        Scalar(7, "createdAt", AppSyncValueType.Integer), Scalar(8, "updatedAt", AppSyncValueType.Integer),
        Scalar(9, "lastFavoriteStatusUpdateAt", AppSyncValueType.Integer),
        Scalar(10, "lastUpdatedTime", AppSyncValueType.Integer), Scalar(11, "forumId", AppSyncValueType.Integer),
        Scalar(12, "forumName", AppSyncValueType.Text), Scalar(13, "name", AppSyncValueType.Text),
        Scalar(14, "sortOrder", AppSyncValueType.Integer), Scalar(15, "categorySyncId", AppSyncValueType.Identifier),
        Scalar(16, "colorKey", AppSyncValueType.Enum), Scalar(17, "query", AppSyncValueType.Text),
        Scalar(18, "enabled", AppSyncValueType.Boolean), Scalar(19, "content", AppSyncValueType.Text),
        Scalar(20, "parentId", AppSyncValueType.Integer), Scalar(21, "bookmarked", AppSyncValueType.Boolean),
        Scalar(22, "read", AppSyncValueType.Boolean), Scalar(23, "threadId", AppSyncValueType.Integer),
        Scalar(24, "threadType", AppSyncValueType.Enum), Scalar(25, "historyOrigin", AppSyncValueType.Enum),
        Scalar(26, "threadName", AppSyncValueType.Text), Scalar(27, "page", AppSyncValueType.Integer),
        Scalar(28, "postId", AppSyncValueType.Integer), Scalar(29, "postTitle", AppSyncValueType.Text),
        Scalar(30, "anchorPostId", AppSyncValueType.Integer), Scalar(31, "anchorPostRatio", AppSyncValueType.Decimal),
        Scalar(32, "anchorBlockId", AppSyncValueType.Identifier), Scalar(33, "anchorBlockType", AppSyncValueType.Enum),
        Scalar(34, "anchorBlockRatio", AppSyncValueType.Decimal), Scalar(35, "globalScrollY", AppSyncValueType.Integer),
        Scalar(36, "viewportHeight", AppSyncValueType.Integer), Scalar(37, "firstVisibleItemIndex", AppSyncValueType.Integer),
        Scalar(38, "firstVisibleItemOffset", AppSyncValueType.Integer), Scalar(39, "lastVisitTime", AppSyncValueType.Integer),
        Scalar(40, "pageIndex", AppSyncValueType.Integer), Scalar(41, "totalPages", AppSyncValueType.Integer),
        Scalar(42, "tagId", AppSyncValueType.Integer), Scalar(43, "tagName", AppSyncValueType.Text),
        Scalar(44, "tagPage", AppSyncValueType.Integer), Scalar(45, "threadTitle", AppSyncValueType.Text),
        Scalar(46, "threadImagePageIndex", AppSyncValueType.Integer), Scalar(47, "threadImageTotalPages", AppSyncValueType.Integer),
        Scalar(48, "threadPage", AppSyncValueType.Integer), Scalar(49, "subscriptionSyncId", AppSyncValueType.Identifier),
        Scalar(50, "subscriptionTitle", AppSyncValueType.Text), Scalar(51, "subscriptionQuery", AppSyncValueType.Text),
        Scalar(52, "subscriptionPage", AppSyncValueType.Integer), Scalar(53, "dateKey", AppSyncValueType.Identifier),
        Scalar(54, "durationMillis", AppSyncValueType.Integer), Scalar(55, "collectionSyncId", AppSyncValueType.Identifier),
        Scalar(56, "fid", AppSyncValueType.Integer), Scalar(57, "latestPostTitle", AppSyncValueType.Text),
        Scalar(58, "mode", AppSyncValueType.Enum), Scalar(59, "summary", AppSyncValueType.Text),
        Scalar(60, "detailIds", AppSyncValueType.Text), Scalar(61, "detectedAt", AppSyncValueType.Integer),
        Scalar(62, "ambiguous", AppSyncValueType.Boolean), Scalar(63, "sourceFingerprint", AppSyncValueType.Identifier),
        Scalar(64, "sourceDiscriminator", AppSyncValueType.Text), Scalar(65, "readAt", AppSyncValueType.Integer),
        Scalar(66, "dismissedAt", AppSyncValueType.Integer), Scalar(67, "coverUrl", AppSyncValueType.Text),
        Scalar(68, "threadCover", AppSyncValueType.Text),
        Scalar(69, "appsyncBulkDeleteScope", AppSyncValueType.Identifier),
        Scalar(70, "appsyncBulkDeleteCount", AppSyncValueType.Integer),
        Scalar(71, "appsyncBulkDeleteExpiresAt", AppSyncValueType.Integer),
    ).associateBy { it.name }

    private val derived = mapOf(
        "settings" to setOf("type"),
        "favorite.item" to setOf("targetType", "targetId", "authorId"),
        "detail-note" to setOf("targetType", "targetId", "authorId"),
        "bookmark" to setOf("targetType", "parentId", "targetId"),
        "reading.thread" to setOf("threadId", "threadType", "authorId", "historyOrigin"),
        "reading.image" to setOf("postId"),
        "reading.tag-manga" to setOf("tagId"), "reading.tag-catalog" to setOf("tagId"),
        "reading.rss-search" to setOf("subscriptionSyncId"), "reading.rss-catalog" to setOf("subscriptionSyncId"),
        "reading.time" to setOf("dateKey"),
        "favorite.item-category" to setOf("targetType", "targetId", "authorId", "categorySyncId"),
        "favorite.item-collection" to setOf("targetType", "targetId", "authorId", "collectionSyncId"),
        "favorite.update-event" to setOf("sourceFingerprint"),
        "favorite.update-fid-filter" to setOf("fid"),
        "favorite.update-category-filter" to setOf("categorySyncId"),
    )
    private val displayTitle = mapOf(
        "favorite.item" to "title", "bookmark" to "title", "reading.thread" to "threadName",
        "reading.tag-manga" to "tagName", "reading.tag-catalog" to "tagName",
        "favorite.update-event" to "title",
    )
    private val nullable = setOf("forumId", "forumName", "lastUpdatedTime", "anchorPostRatio",
        "anchorBlockId", "anchorBlockType", "anchorBlockRatio", "globalScrollY", "viewportHeight",
        "firstVisibleItemIndex", "firstVisibleItemOffset", "fid", "latestPostTitle", "readAt", "dismissedAt",
        "coverUrl", "threadCover")

    val domains: Map<String, AppSyncCanonicalDomain> = domainIds.mapValues { (domain, id) ->
        val names = AppSyncLegacyFieldRegistry.fieldsByDomain.getValue(domain) +
            AppSyncPortabilityPolicy.fieldDeclarations.filter { it.domain == domain }.map { it.field } +
            AppSyncLegacyFieldRegistry.proofFields
        val fields = names.associateWith { name ->
            val scalar = scalars.getValue(name)
            val classification = when {
                name in AppSyncLegacyFieldRegistry.proofFields -> AppSyncFieldClass.Derived // batch metadata
                name in derived[domain].orEmpty() -> AppSyncFieldClass.Derived
                name == "coverUrl" || name == "threadCover" -> AppSyncFieldClass.Cache
                name == "subscriptionTitle" || name == "subscriptionQuery" -> AppSyncFieldClass.ParentJoinable
                name in setOf("forumName", "postTitle", "latestPostTitle", "threadTitle") -> AppSyncFieldClass.Cache
                name == displayTitle[domain] -> AppSyncFieldClass.BoundedPresentation
                else -> AppSyncFieldClass.Essential
            }
            AppSyncCanonicalField(scalar.id, name, scalar.type,
                nullable = name in nullable || (name == "authorId" && domain in setOf("reading.tag-catalog", "reading.rss-catalog")),
                classification = classification,
                maxUtf8Bytes = when {
                    classification == AppSyncFieldClass.BoundedPresentation -> TITLE_BYTES
                    domain == "detail-note" && name == "content" -> 128 * 1024
                    name == "value" -> 64 * 1024
                    scalar.type in setOf(AppSyncValueType.Integer, AppSyncValueType.Decimal, AppSyncValueType.Boolean) -> 64
                    scalar.type == AppSyncValueType.Identifier || scalar.type == AppSyncValueType.Enum -> 1024
                    else -> 32 * 1024
                },
                allowMarkup = domain == "detail-note" && name == "content",
            )
        }
        AppSyncCanonicalDomain(id, domain, fields)
    }
    val domainsById = domains.values.associateBy { it.id }

    init {
        check(domainIds.keys == AppSyncLegacyFieldRegistry.fieldsByDomain.keys)
        check(domainsById.size == domains.size)
        check(scalars.values.map { it.id }.distinct().size == scalars.size)
        check(domains.values.all { it.fieldsById.size == it.fields.size })
        check(domains.values.all { domain -> domain.fields.values.count {
            it.classification == AppSyncFieldClass.BoundedPresentation
        } <= 1 })
    }
}

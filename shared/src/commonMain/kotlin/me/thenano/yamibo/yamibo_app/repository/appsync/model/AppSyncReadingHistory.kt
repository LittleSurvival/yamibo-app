package me.thenano.yamibo.yamibo_app.repository.appsync.model

import kotlinx.serialization.Serializable

@Serializable
data class AppSyncReadingHistory(
    val thread: List<AppSyncThreadHistory> = emptyList(),
    val image: List<AppSyncImageHistory> = emptyList(),
    val tagManga: List<AppSyncTagMangaHistory> = emptyList(),
    val tagCatalog: List<AppSyncTagCatalogHistory> = emptyList(),
    val rssSearch: List<AppSyncRssSearchHistory> = emptyList(),
    val rssCatalog: List<AppSyncRssCatalogHistory> = emptyList(),
    val readingTimeStats: List<AppSyncReadingTimeStat> = emptyList(),
)

@Serializable
data class AppSyncThreadHistory(
    val threadId: Long,
    val threadType: String,
    val threadName: String,
    val threadCover: String? = null,
    val forumName: String? = null,
    val forumId: Long? = null,
    val authorId: Long = 0,
    val page: Long = 1,
    val postId: Long = 0,
    val postTitle: String = "",
    val anchorPostId: Long = 0,
    val anchorPostRatio: Double? = null,
    val anchorBlockId: String? = null,
    val anchorBlockType: String? = null,
    val anchorBlockRatio: Double? = null,
    val globalScrollY: Long? = null,
    val viewportHeight: Long? = null,
    val firstVisibleItemIndex: Long? = null,
    val firstVisibleItemOffset: Long? = null,
    val historyOrigin: String = "Direct",
    val lastVisitTime: Long,
    val lastUpdatedTime: Long? = null,
)

@Serializable
data class AppSyncImageHistory(
    val postId: Long,
    val threadId: Long,
    val pageIndex: Long,
    val totalPages: Long,
    val firstVisibleItemIndex: Long? = null,
    val firstVisibleItemOffset: Long? = null,
    val lastVisitTime: Long,
)

@Serializable
data class AppSyncTagMangaHistory(
    val tagId: Long,
    val tagName: String,
    val tagPage: Long,
    val threadId: Long,
    val threadTitle: String,
    val threadImagePageIndex: Long,
    val threadImageTotalPages: Long,
    val firstVisibleItemIndex: Long? = null,
    val firstVisibleItemOffset: Long? = null,
    val lastVisitTime: Long,
    val coverUrl: String? = null,
)

@Serializable
data class AppSyncTagCatalogHistory(
    val tagId: Long,
    val tagName: String,
    val tagPage: Long,
    val threadId: Long,
    val threadTitle: String,
    val threadPage: Long,
    val postId: Long,
    val postTitle: String,
    val authorId: Long? = null,
    val anchorPostId: Long = 0,
    val anchorPostRatio: Double? = null,
    val anchorBlockId: String? = null,
    val anchorBlockType: String? = null,
    val anchorBlockRatio: Double? = null,
    val viewportHeight: Long? = null,
    val firstVisibleItemIndex: Long? = null,
    val firstVisibleItemOffset: Long? = null,
    val lastVisitTime: Long,
    val coverUrl: String? = null,
)

@Serializable
data class AppSyncRssSearchHistory(
    val subscriptionId: Long,
    val subscriptionTitle: String,
    val subscriptionQuery: String,
    val subscriptionPage: Long,
    val threadId: Long,
    val threadTitle: String,
    val threadImagePageIndex: Long,
    val threadImageTotalPages: Long,
    val firstVisibleItemIndex: Long? = null,
    val firstVisibleItemOffset: Long? = null,
    val lastVisitTime: Long,
    val coverUrl: String? = null,
)

@Serializable
data class AppSyncRssCatalogHistory(
    val subscriptionId: Long,
    val subscriptionTitle: String,
    val subscriptionQuery: String,
    val subscriptionPage: Long,
    val threadId: Long,
    val threadTitle: String,
    val threadPage: Long,
    val postId: Long,
    val postTitle: String,
    val authorId: Long? = null,
    val anchorPostId: Long = 0,
    val anchorPostRatio: Double? = null,
    val anchorBlockId: String? = null,
    val anchorBlockType: String? = null,
    val anchorBlockRatio: Double? = null,
    val viewportHeight: Long? = null,
    val firstVisibleItemIndex: Long? = null,
    val firstVisibleItemOffset: Long? = null,
    val lastVisitTime: Long,
    val coverUrl: String? = null,
)

@Serializable
data class AppSyncReadingTimeStat(
    val dateKey: String,
    val durationMillis: Long,
    val updatedAt: Long,
)

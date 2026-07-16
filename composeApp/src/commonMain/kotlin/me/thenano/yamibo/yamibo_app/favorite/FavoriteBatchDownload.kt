package me.thenano.yamibo.yamibo_app.favorite

import io.github.littlesurvival.dto.value.TagId
import io.github.littlesurvival.dto.value.ThreadId
import me.thenano.yamibo.yamibo_app.repository.DownloadRepository
import me.thenano.yamibo.yamibo_app.repository.FavoriteStoreRepository.FavoriteCategoryContent
import me.thenano.yamibo.yamibo_app.repository.FavoriteStoreRepository.FavoriteItem
import me.thenano.yamibo.yamibo_app.repository.FavoriteStoreRepository.FavoriteTargetType
import me.thenano.yamibo.yamibo_app.repository.RssSearchSubscriptionRepository

internal enum class FavoriteBatchDownloadType {
    NovelThread,
    NormalThread,
    TagManga,
    RssSearch,
}

internal enum class FavoriteBatchDownloadMode {
    All,
    ExceptLastPage,
}

internal data class FavoriteBatchDownloadScope(
    val directItemCount: Int,
    val selectedCollectionCount: Int,
    val expandedCollectionItemCount: Int,
    val items: List<FavoriteItem>,
) {
    val totalItemCount: Int get() = items.size
}

internal data class FavoriteBatchDownloadResult(
    val requested: Int,
    val queued: Int,
    val skipped: Int,
    val failed: Int,
    val unsupported: Int,
)

internal class FavoriteBatchDownloadSubmissionGate {
    private var submitting = false
    val isSubmitting: Boolean get() = submitting

    fun tryStart(): Boolean {
        if (submitting) return false
        submitting = true
        return true
    }

    fun finish() {
        submitting = false
    }
}

private data class FavoriteBatchTargetKey(
    val targetType: FavoriteTargetType,
    val targetId: Long,
    val authorId: Int?,
)

internal fun buildFavoriteBatchDownloadScope(
    content: FavoriteCategoryContent,
    selectedItemIds: Set<Long>,
    selectedCollectionIds: Set<Long>,
): FavoriteBatchDownloadScope {
    val allItems = content.directItems + content.collections.flatMap { it.items }
    val directSelectedItems = allItems.filter { it.id in selectedItemIds }
    val selectedCollections = content.collections.filter { it.collection.id in selectedCollectionIds }
    val expandedItems = selectedCollections.flatMap { it.items }
    val dedupedItems = (directSelectedItems + expandedItems)
        .distinctBy { it.id }
        .distinctBy { FavoriteBatchTargetKey(it.targetType, it.targetId, it.authorId?.value) }

    return FavoriteBatchDownloadScope(
        directItemCount = directSelectedItems.distinctBy { it.id }.size,
        selectedCollectionCount = selectedCollections.size,
        expandedCollectionItemCount = expandedItems.distinctBy { it.id }.size,
        items = dedupedItems,
    )
}

internal fun FavoriteItem.batchDownloadType(): FavoriteBatchDownloadType = when (targetType) {
    FavoriteTargetType.ThreadNovel -> FavoriteBatchDownloadType.NovelThread
    FavoriteTargetType.ThreadNormal -> FavoriteBatchDownloadType.NormalThread
    FavoriteTargetType.TagManga -> FavoriteBatchDownloadType.TagManga
    FavoriteTargetType.RssSearch -> FavoriteBatchDownloadType.RssSearch
}

internal fun FavoriteBatchDownloadScope.countByType(): Map<FavoriteBatchDownloadType, Int> =
    FavoriteBatchDownloadType.entries.associateWith { type ->
        items.count { it.batchDownloadType() == type }
    }

internal fun FavoriteBatchDownloadScope.itemsForTypes(types: Set<FavoriteBatchDownloadType>): List<FavoriteItem> =
    items.filter { it.batchDownloadType() in types }

internal fun FavoriteBatchDownloadType.supportsExceptLastPageDownload(): Boolean =
    this == FavoriteBatchDownloadType.NovelThread || this == FavoriteBatchDownloadType.NormalThread

internal fun FavoriteBatchDownloadScope.supportsExceptLastPageDownload(types: Set<FavoriteBatchDownloadType>): Boolean =
    items.any { item -> item.batchDownloadType() in types && item.batchDownloadType().supportsExceptLastPageDownload() }

internal fun FavoriteBatchDownloadMode.coerceFor(
    scope: FavoriteBatchDownloadScope,
    types: Set<FavoriteBatchDownloadType>,
): FavoriteBatchDownloadMode =
    if (this == FavoriteBatchDownloadMode.ExceptLastPage && !scope.supportsExceptLastPageDownload(types)) {
        FavoriteBatchDownloadMode.All
    } else {
        this
    }

internal suspend fun enqueueFavoriteBatchDownloads(
    downloadRepository: DownloadRepository,
    rssRepository: RssSearchSubscriptionRepository,
    items: List<FavoriteItem>,
    mode: FavoriteBatchDownloadMode = FavoriteBatchDownloadMode.All,
): FavoriteBatchDownloadResult {
    var queued = 0
    var skipped = 0
    var failed = 0
    var unsupported = 0

    for (item in items) {
        val result = when (item.targetType) {
            FavoriteTargetType.ThreadNormal,
            FavoriteTargetType.ThreadNovel -> {
                val threadId = ThreadId(item.targetId.toInt())
                if (mode == FavoriteBatchDownloadMode.ExceptLastPage) {
                    downloadRepository.enqueueThreadExceptLastPage(
                        tid = threadId,
                        title = item.title,
                        authorId = item.authorId,
                    )
                } else {
                    downloadRepository.enqueueThread(
                        tid = threadId,
                        title = item.title,
                        authorId = item.authorId,
                    )
                }
            }
            FavoriteTargetType.TagManga -> downloadRepository.enqueueTagMangaAllPages(
                tagId = TagId(item.targetId.toInt()),
                tagName = item.title,
            )
            FavoriteTargetType.RssSearch -> {
                val subscription = rssRepository.getSubscription(item.targetId)
                if (subscription == null) {
                    unsupported += 1
                    continue
                }
                downloadRepository.enqueueRssMangaAllPages(
                    subscriptionId = item.targetId,
                    title = subscription.title.ifBlank { item.title },
                    query = subscription.query,
                )
            }
        }

        if (result.isSuccess) {
            queued += 1
        } else {
            failed += 1
        }
    }

    return FavoriteBatchDownloadResult(
        requested = items.size,
        queued = queued,
        skipped = skipped,
        failed = failed,
        unsupported = unsupported,
    )
}

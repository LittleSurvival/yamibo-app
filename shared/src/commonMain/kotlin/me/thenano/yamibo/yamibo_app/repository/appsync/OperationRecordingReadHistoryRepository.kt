package me.thenano.yamibo.yamibo_app.repository.appsync

import kotlinx.coroutines.runBlocking
import me.thenano.yamibo.yamibo_app.repository.ReadHistoryRepository
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncDomainId
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncEntityId
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperationKind
import me.thenano.yamibo.yamibo_app.store.appsync.LocalSyncOperationDraft
import me.thenano.yamibo.yamibo_app.util.time.currentTimeMillis

internal class OperationRecordingReadHistoryRepository(
    private val delegate: ReadHistoryRepository,
    private val recorder: AppSyncMutationRecorder,
) : ReadHistoryRepository by delegate {
    override suspend fun savePosition(history: ReadHistoryRepository.ThreadReadingHistory) {
        val existing = delegate.getPosition(history.threadId, history.threadType, history.authorId)
        record(
            "reading.thread",
            threadEntityId(history),
            if (existing == null) SyncOperationKind.Put else SyncOperationKind.Patch,
            history.fields(),
        ) {
            delegate.savePosition(history)
        }
    }

    override suspend fun deleteHistory(
        tid: io.github.littlesurvival.dto.value.ThreadId,
        threadType: ReadHistoryRepository.ThreadEntryType,
        authorId: io.github.littlesurvival.dto.value.UserId?,
    ) {
        val existing = delegate.getPosition(tid, threadType, authorId) ?: return
        record("reading.thread", threadEntityId(existing), SyncOperationKind.Delete, existing.fields()) {
            delegate.deleteHistory(tid, threadType, authorId)
        }
    }

    override suspend fun deleteHistoryBatch(items: List<ReadHistoryRepository.ThreadReadingHistory>) {
        recordAuthorizedDeleteBatch(items.distinctBy(::threadEntityId).map {
            draft("reading.thread", threadEntityId(it), SyncOperationKind.Delete, it.fields())
        }, "reading-history:selected") {
            delegate.deleteHistoryBatch(items)
        }
    }

    override suspend fun deleteAll() {
        val items = loadAllThreadHistory()
        recordAuthorizedDeleteBatch(items.map {
            draft("reading.thread", threadEntityId(it), SyncOperationKind.Delete, it.fields())
        }, "reading-history:all-thread") {
            delegate.deleteAll()
        }
    }

    override suspend fun saveImagePosition(history: ReadHistoryRepository.ImageReadingHistory) {
        val existing = delegate.getImagePosition(history.postId)
        record(
            "reading.image",
            history.postId.value.toString(),
            if (existing == null) SyncOperationKind.Put else SyncOperationKind.Patch,
            history.fields(),
        ) {
            delegate.saveImagePosition(history)
        }
    }

    override suspend fun saveTagMangaReaderModeHistory(
        history: ReadHistoryRepository.TagMangaReadingHistory,
    ) {
        val existing = delegate.getTagMangaReaderModeHistoryPosition(history.tagId)
        record(
            "reading.tag-manga",
            history.tagId.value.toString(),
            if (existing == null) SyncOperationKind.Put else SyncOperationKind.Patch,
            history.fields(),
        ) {
            delegate.saveTagMangaReaderModeHistory(history)
        }
    }

    override suspend fun deleteMangaTagHistory(tagId: io.github.littlesurvival.dto.value.TagId) {
        val existing = delegate.getTagMangaReaderModeHistoryPosition(tagId) ?: return
        record(
            "reading.tag-manga",
            tagId.value.toString(),
            SyncOperationKind.Delete,
            existing.fields(),
        ) {
            delegate.deleteMangaTagHistory(tagId)
        }
    }

    override suspend fun saveTagCatalogThreadHistory(
        history: ReadHistoryRepository.TagCatalogReadingHistory,
    ) {
        val existing = delegate.getTagCatalogThreadHistoryPosition(history.tagId)
        record(
            "reading.tag-catalog",
            history.tagId.value.toString(),
            if (existing == null) SyncOperationKind.Put else SyncOperationKind.Patch,
            history.fields(),
        ) {
            delegate.saveTagCatalogThreadHistory(history)
        }
    }

    override suspend fun deleteTagCatalogThreadHistory(
        tagId: io.github.littlesurvival.dto.value.TagId,
    ) {
        val existing = delegate.getTagCatalogThreadHistoryPosition(tagId) ?: return
        record(
            "reading.tag-catalog",
            tagId.value.toString(),
            SyncOperationKind.Delete,
            existing.fields(),
        ) {
            delegate.deleteTagCatalogThreadHistory(tagId)
        }
    }

    override suspend fun saveRssSearchReaderModeHistory(
        history: ReadHistoryRepository.RssSearchReadingHistory,
    ) {
        val syncId = delegate.getRssSubscriptionSyncId(history.subscriptionId)
        if (syncId == null) {
            delegate.saveRssSearchReaderModeHistory(history)
            return
        }
        val existing = delegate.getRssSearchReaderModeHistoryPosition(history.subscriptionId)
        record(
            "reading.rss-search",
            syncId,
            if (existing == null) SyncOperationKind.Put else SyncOperationKind.Patch,
            history.fields(syncId),
        ) {
            delegate.saveRssSearchReaderModeHistory(history)
        }
    }

    override suspend fun deleteRssSearchHistory(subscriptionId: Long) {
        val existing = delegate.getRssSearchReaderModeHistoryPosition(subscriptionId) ?: return
        val syncId = delegate.getRssSubscriptionSyncId(subscriptionId)
        if (syncId == null) {
            delegate.deleteRssSearchHistory(subscriptionId)
            return
        }
        record("reading.rss-search", syncId, SyncOperationKind.Delete, existing.fields(syncId)) {
            delegate.deleteRssSearchHistory(subscriptionId)
        }
    }

    override suspend fun saveRssCatalogThreadHistory(
        history: ReadHistoryRepository.RssCatalogReadingHistory,
    ) {
        val syncId = delegate.getRssSubscriptionSyncId(history.subscriptionId)
        if (syncId == null) {
            delegate.saveRssCatalogThreadHistory(history)
            return
        }
        val existing = delegate.getRssCatalogThreadHistoryPosition(history.subscriptionId)
        record(
            "reading.rss-catalog",
            syncId,
            if (existing == null) SyncOperationKind.Put else SyncOperationKind.Patch,
            history.fields(syncId),
        ) {
            delegate.saveRssCatalogThreadHistory(history)
        }
    }

    override suspend fun deleteRssCatalogThreadHistory(subscriptionId: Long) {
        val existing = delegate.getRssCatalogThreadHistoryPosition(subscriptionId) ?: return
        val syncId = delegate.getRssSubscriptionSyncId(subscriptionId)
        if (syncId == null) {
            delegate.deleteRssCatalogThreadHistory(subscriptionId)
            return
        }
        record("reading.rss-catalog", syncId, SyncOperationKind.Delete, existing.fields(syncId)) {
            delegate.deleteRssCatalogThreadHistory(subscriptionId)
        }
    }

    override suspend fun deleteCombinedHistoryBatch(items: List<ReadHistoryRepository.AnyReadingHistory>) {
        val drafts = items.mapNotNull { item ->
            when (item) {
                is ReadHistoryRepository.ThreadReadingHistory ->
                    draft("reading.thread", threadEntityId(item), SyncOperationKind.Delete, item.fields())
                is ReadHistoryRepository.TagMangaReadingHistory ->
                    draft(
                        "reading.tag-manga",
                        item.tagId.value.toString(),
                        SyncOperationKind.Delete,
                        item.fields(),
                    )
                is ReadHistoryRepository.ImageReadingHistory ->
                    draft(
                        "reading.image",
                        item.postId.value.toString(),
                        SyncOperationKind.Delete,
                        item.fields(),
                    )
                is ReadHistoryRepository.TagCatalogReadingHistory ->
                    draft(
                        "reading.tag-catalog",
                        item.tagId.value.toString(),
                        SyncOperationKind.Delete,
                        item.fields(),
                    )
                is ReadHistoryRepository.RssSearchReadingHistory ->
                    delegate.getRssSubscriptionSyncId(item.subscriptionId)?.let { syncId ->
                        draft("reading.rss-search", syncId, SyncOperationKind.Delete, item.fields(syncId))
                    }
                is ReadHistoryRepository.RssCatalogReadingHistory ->
                    delegate.getRssSubscriptionSyncId(item.subscriptionId)?.let { syncId ->
                        draft("reading.rss-catalog", syncId, SyncOperationKind.Delete, item.fields(syncId))
                    }
            }
        }.distinctBy { "${it.domainId.value}|${it.entityId.value}" }
        recordAuthorizedDeleteBatch(drafts, "reading-history:selected-combined") {
            delegate.deleteCombinedHistoryBatch(items)
        }
    }

    override suspend fun deleteAllCombinedHistory() {
        val threads = loadAllThreadHistory()
        val images = delegate.getAllImageHistoryForSync()
        val mangaTags = delegate.getAllTagMangaHistoryForSync()
        val tagCatalogs = delegate.getAllTagCatalogHistoryForSync()
        val rssSearch = delegate.getAllRssSearchHistoryForSync()
        val rssCatalog = delegate.getAllRssCatalogHistoryForSync()
        val drafts = threads.map {
            draft("reading.thread", threadEntityId(it), SyncOperationKind.Delete, it.fields())
        } + images.map {
            draft("reading.image", it.postId.value.toString(), SyncOperationKind.Delete, it.fields())
        } + mangaTags.map {
            draft(
                "reading.tag-manga",
                it.tagId.value.toString(),
                SyncOperationKind.Delete,
                it.fields(),
            )
        } + tagCatalogs.map {
            draft(
                "reading.tag-catalog",
                it.tagId.value.toString(),
                SyncOperationKind.Delete,
                it.fields(),
            )
        } + rssSearch.mapNotNull {
            delegate.getRssSubscriptionSyncId(it.subscriptionId)?.let { syncId ->
                draft("reading.rss-search", syncId, SyncOperationKind.Delete, it.fields(syncId))
            }
        } + rssCatalog.mapNotNull {
            delegate.getRssSubscriptionSyncId(it.subscriptionId)?.let { syncId ->
                draft("reading.rss-catalog", syncId, SyncOperationKind.Delete, it.fields(syncId))
            }
        }
        recordAuthorizedDeleteBatch(drafts, "reading-history:all-combined") {
            delegate.deleteAllCombinedHistory()
        }
    }

    override suspend fun recordReadingDuration(dateKey: String, durationMillis: Long) {
        if (durationMillis <= 0) return
        val current = delegate.getReadingDurationTotal(dateKey, dateKey)
        val next = current + durationMillis
        record(
            "reading.time",
            dateKey,
            if (current == 0L) SyncOperationKind.Put else SyncOperationKind.Patch,
            mapOf(
                "dateKey" to dateKey,
                "durationMillis" to next.toString(),
                "updatedAt" to currentTimeMillis().toString(),
            ),
        ) {
            delegate.recordReadingDuration(dateKey, durationMillis)
        }
    }

    private suspend fun loadAllThreadHistory(): List<ReadHistoryRepository.ThreadReadingHistory> {
        val count = delegate.getHistoryCount().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return if (count == 0) emptyList() else delegate.getHistoryPage(1, count)
    }

    private fun record(
        domain: String,
        entityId: String,
        kind: SyncOperationKind,
        fields: Map<String, String?>,
        mutation: suspend () -> Unit,
    ) {
        recorder.record(
            domain = domain,
            entityId = entityId,
            kind = kind,
            fields = fields,
            entityGeneration = recorder.currentGeneration(domain, entityId),
        ) {
            runBlocking { mutation() }
        }
    }
    private fun recordAuthorizedDeleteBatch(
        drafts: List<LocalSyncOperationDraft>,
        scopeKey: String,
        mutation: suspend () -> Unit,
    ) {
        if (drafts.isEmpty()) {
            runBlocking { mutation() }
        } else {
            recorder.recordAuthorizedDeleteBatch(drafts, scopeKey) {
                runBlocking { mutation() }
            }
        }
    }

    private fun draft(
        domain: String,
        entityId: String,
        kind: SyncOperationKind,
        fields: Map<String, String?>,
    ) = LocalSyncOperationDraft(
        domainId = SyncDomainId(domain),
        entityId = SyncEntityId(entityId),
        entityGeneration = recorder.currentGeneration(domain, entityId),
        kind = kind,
        fields = fields,
    )

    private fun threadEntityId(history: ReadHistoryRepository.ThreadReadingHistory): String =
        "${history.threadId.value}|${history.threadType.name}|" +
            "${history.authorId?.value ?: 0}|${history.historyOrigin.name}"

    private fun ReadHistoryRepository.ThreadReadingHistory.fields() = mapOf(
        "threadId" to threadId.value.toString(),
        "threadType" to threadType.name,
        "authorId" to (authorId?.value ?: 0).toString(),
        "historyOrigin" to historyOrigin.name,
        "threadName" to threadName,
        "threadCover" to threadCover,
        "lastUpdatedTime" to lastUpdatedTime?.toString(),
        "forumName" to forumName,
        "forumId" to forumId?.value?.toString(),
        "page" to page.toString(),
        "postId" to postId.value.toString(),
        "postTitle" to postTitle,
        "anchorPostId" to anchorPostId.toString(),
        "anchorPostRatio" to anchorPostRatio?.toString(),
        "anchorBlockId" to anchorBlockId,
        "anchorBlockType" to anchorBlockType,
        "anchorBlockRatio" to anchorBlockRatio?.toString(),
        "globalScrollY" to globalScrollY?.toString(),
        "viewportHeight" to viewportHeight?.toString(),
        "firstVisibleItemIndex" to firstVisibleItemIndex?.toString(),
        "firstVisibleItemOffset" to firstVisibleItemOffset?.toString(),
        "lastVisitTime" to lastVisitTime.toString(),
    )

    private fun ReadHistoryRepository.ImageReadingHistory.fields() = mapOf(
        "postId" to postId.value.toString(),
        "threadId" to threadId.value.toString(),
        "pageIndex" to pageIndex.toString(),
        "totalPages" to totalPages.toString(),
        "firstVisibleItemIndex" to firstVisibleItemIndex?.toString(),
        "firstVisibleItemOffset" to firstVisibleItemOffset?.toString(),
        "lastVisitTime" to lastVisitTime.toString(),
    )

    private fun ReadHistoryRepository.TagMangaReadingHistory.fields() = mapOf(
        "tagId" to tagId.value.toString(),
        "tagName" to tagName,
        "tagPage" to tagPage.toString(),
        "threadId" to threadId.value.toString(),
        "threadTitle" to threadTitle,
        "threadImagePageIndex" to threadImagePageIndex.toString(),
        "threadImageTotalPages" to threadImageTotalPages.toString(),
        "firstVisibleItemIndex" to firstVisibleItemIndex?.toString(),
        "firstVisibleItemOffset" to firstVisibleItemOffset?.toString(),
        "lastVisitTime" to lastVisitTime.toString(),
        "coverUrl" to coverUrl,
    )

    private fun ReadHistoryRepository.TagCatalogReadingHistory.fields() = mapOf(
        "tagId" to tagId.value.toString(),
        "tagName" to tagName,
        "tagPage" to tagPage.toString(),
        "threadId" to threadId.value.toString(),
        "threadTitle" to threadTitle,
        "threadPage" to threadPage.toString(),
        "postId" to postId.value.toString(),
        "postTitle" to postTitle,
        "authorId" to authorId?.value?.toString(),
        "anchorPostId" to anchorPostId.toString(),
        "anchorPostRatio" to anchorPostRatio?.toString(),
        "anchorBlockId" to anchorBlockId,
        "anchorBlockType" to anchorBlockType,
        "anchorBlockRatio" to anchorBlockRatio?.toString(),
        "viewportHeight" to viewportHeight?.toString(),
        "firstVisibleItemIndex" to firstVisibleItemIndex?.toString(),
        "firstVisibleItemOffset" to firstVisibleItemOffset?.toString(),
        "lastVisitTime" to lastVisitTime.toString(),
        "coverUrl" to coverUrl,
    )

    private fun ReadHistoryRepository.RssSearchReadingHistory.fields(syncId: String) = mapOf(
        "subscriptionSyncId" to syncId,
        "subscriptionTitle" to subscriptionTitle,
        "subscriptionQuery" to subscriptionQuery,
        "subscriptionPage" to subscriptionPage.toString(),
        "threadId" to threadId.value.toString(),
        "threadTitle" to threadTitle,
        "threadImagePageIndex" to threadImagePageIndex.toString(),
        "threadImageTotalPages" to threadImageTotalPages.toString(),
        "firstVisibleItemIndex" to firstVisibleItemIndex?.toString(),
        "firstVisibleItemOffset" to firstVisibleItemOffset?.toString(),
        "lastVisitTime" to lastVisitTime.toString(),
        "coverUrl" to coverUrl,
    )

    private fun ReadHistoryRepository.RssCatalogReadingHistory.fields(syncId: String) = mapOf(
        "subscriptionSyncId" to syncId,
        "subscriptionTitle" to subscriptionTitle,
        "subscriptionQuery" to subscriptionQuery,
        "subscriptionPage" to subscriptionPage.toString(),
        "threadId" to threadId.value.toString(),
        "threadTitle" to threadTitle,
        "threadPage" to threadPage.toString(),
        "postId" to postId.value.toString(),
        "postTitle" to postTitle,
        "authorId" to authorId?.value?.toString(),
        "anchorPostId" to anchorPostId.toString(),
        "anchorPostRatio" to anchorPostRatio?.toString(),
        "anchorBlockId" to anchorBlockId,
        "anchorBlockType" to anchorBlockType,
        "anchorBlockRatio" to anchorBlockRatio?.toString(),
        "viewportHeight" to viewportHeight?.toString(),
        "firstVisibleItemIndex" to firstVisibleItemIndex?.toString(),
        "firstVisibleItemOffset" to firstVisibleItemOffset?.toString(),
        "lastVisitTime" to lastVisitTime.toString(),
        "coverUrl" to coverUrl,
    )
}

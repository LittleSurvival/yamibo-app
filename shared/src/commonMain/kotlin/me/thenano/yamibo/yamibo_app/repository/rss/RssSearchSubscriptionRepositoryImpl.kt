package me.thenano.yamibo.yamibo_app.repository.rss

import io.github.littlesurvival.YamiboForum
import io.github.littlesurvival.core.YamiboResult
import io.github.littlesurvival.dto.model.ThreadSummary
import io.github.littlesurvival.dto.page.SearchPage
import io.github.littlesurvival.dto.page.TagPage
import io.github.littlesurvival.dto.value.ForumId
import io.github.littlesurvival.dto.value.SearchId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import me.thenano.yamibo.yamibo_app.Database
import me.thenano.yamibo.yamibo_app.Logger
import me.thenano.yamibo.yamibo_app.repository.AuthRepository
import me.thenano.yamibo.yamibo_app.repository.ForumRepository
import me.thenano.yamibo.yamibo_app.repository.RssSearchSubscriptionRepository
import me.thenano.yamibo.yamibo_app.repository.appsync.AppSyncMutationRecorder
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperationKind
import me.thenano.yamibo.yamibo_app.util.time.currentTimeMillis
import me.thenano.yamibo.yamibo_app.RssSearchSubscription

class RssSearchSubscriptionRepositoryImpl private constructor(
    private val db: Database,
    private val authRepository: AuthRepository,
    private val forumRepository: ForumRepository,
    private val mutationRecorder: AppSyncMutationRecorder?,
    private val json: Json,
) : RssSearchSubscriptionRepository {
    constructor(
        db: Database,
        authRepository: AuthRepository,
        forumRepository: ForumRepository,
        json: Json = defaultRssSearchJson(),
    ) : this(db, authRepository, forumRepository, null, json)

    internal constructor(
        db: Database,
        authRepository: AuthRepository,
        forumRepository: ForumRepository,
        mutationRecorder: AppSyncMutationRecorder,
    ) : this(
        db,
        authRepository,
        forumRepository,
        mutationRecorder,
        defaultRssSearchJson(),
    )

    private val subscriptionQueries = db.rssSearchSubscriptionQueries
    private val resultQueries = db.rssSearchSubscriptionResultQueries
    private val pageCacheQueries = db.rssSearchPageCacheQueries
    private val subscriptionsFlow = MutableStateFlow<List<RssSearchSubscriptionRepository.SubscriptionSummary>>(emptyList())
    private val unreadCountFlow = MutableStateFlow(0)

    override val subscriptions: StateFlow<List<RssSearchSubscriptionRepository.SubscriptionSummary>> =
        subscriptionsFlow.asStateFlow()
    override val unreadCount: StateFlow<Int> = unreadCountFlow.asStateFlow()

    init {
        reloadState()
    }

    override suspend fun createFromSearch(
        title: String,
        query: String,
        forumId: ForumId?,
        forumName: String?,
        searchPage: SearchPage,
    ): YamiboResult<Long> {
        val keyword = normalizeKeyword(query)
        if (keyword.isBlank()) return YamiboResult.Failure("搜尋關鍵字不能為空白")
        findBySearch(keyword, forumId)?.let { return YamiboResult.Success(it.id) }
        val now = currentTimeMillis()
        val scopedForumId = searchPage.forumId ?: forumId
        val scopedForumName = forumName ?: scopedForumId?.let { YamiboForum.toForumName(it) }
        recordSubscriptionMutation(
            entityId = rssSearchSubscriptionSyncId(keyword, scopedForumId?.value?.toLong()),
            kind = SyncOperationKind.Put,
            fields = subscriptionFields(
                title = keyword,
                query = keyword,
                forumId = scopedForumId?.value?.toLong(),
                forumName = scopedForumName,
                enabled = true,
                createdAt = now,
                updatedAt = now,
            ),
        ) {
            subscriptionQueries.insertSubscription(
                title = keyword,
                query = keyword,
                forumId = scopedForumId?.value?.toLong(),
                forumName = scopedForumName,
                enabled = 1,
                createdAt = now,
                updatedAt = now,
                lastRefreshStartedAt = null,
                lastRefreshFinishedAt = null,
                lastRefreshStatus = null,
                lastRefreshMessage = null,
                lastSearchId = searchPage.searchId?.value?.toLong(),
                lastTotalCount = searchPage.totalCount.toLong(),
            )
        }
        val id = subscriptionQueries.lastInsertedId().executeAsOne()
        saveSearchPageCache(
            subscriptionId = id,
            pageIndex = searchPage.pageNav?.currentPage ?: 1,
            searchPage = normalizeSearchPageForum(searchPage, scopedForumId),
            fetchedAt = now,
        )
        val seededPage = normalizeSearchPageForum(searchPage, scopedForumId)
        mergeResults(id, seededPage.threads, now, pageIndex = seededPage.pageNav?.currentPage ?: 1, pageForumId = seededPage.forumId)
        reloadState()
        return YamiboResult.Success(id)
    }

    override suspend fun ensureSubscription(
        query: String,
        forumId: ForumId?,
        forumName: String?,
    ): YamiboResult<Long> {
        val keyword = normalizeKeyword(query)
        if (keyword.isBlank()) return YamiboResult.Failure("搜尋關鍵字不能為空白")
        findBySearch(keyword, forumId)?.let { return YamiboResult.Success(it.id) }
        val now = currentTimeMillis()
        val scopedForumName = forumName ?: forumId?.let { YamiboForum.toForumName(it) }
        recordSubscriptionMutation(
            entityId = rssSearchSubscriptionSyncId(keyword, forumId?.value?.toLong()),
            kind = SyncOperationKind.Put,
            fields = subscriptionFields(
                title = keyword,
                query = keyword,
                forumId = forumId?.value?.toLong(),
                forumName = scopedForumName,
                enabled = true,
                createdAt = now,
                updatedAt = now,
            ),
        ) {
            subscriptionQueries.insertSubscription(
                title = keyword,
                query = keyword,
                forumId = forumId?.value?.toLong(),
                forumName = scopedForumName,
                enabled = 1,
                createdAt = now,
                updatedAt = now,
                lastRefreshStartedAt = null,
                lastRefreshFinishedAt = null,
                lastRefreshStatus = null,
                lastRefreshMessage = null,
                lastSearchId = null,
                lastTotalCount = 0,
            )
        }
        val id = subscriptionQueries.lastInsertedId().executeAsOne()
        reloadState()
        return YamiboResult.Success(id)
    }

    override suspend fun findBySearch(
        query: String,
        forumId: ForumId?,
    ): RssSearchSubscriptionRepository.SubscriptionSummary? {
        val keyword = normalizeKeyword(query)
        val forumValue = forumId?.value
        return subscriptionQueries.getAll()
            .executeAsList()
            .firstOrNull { row ->
                normalizeKeyword(row.query).equals(keyword, ignoreCase = true) &&
                    row.forumId?.toInt() == forumValue
            }
            ?.toSummary()
    }

    override suspend fun refresh(subscriptionId: Long): YamiboResult<RssSearchSubscriptionRepository.RefreshSummary> {
        val subscription = subscriptionQueries.getById(subscriptionId).executeAsOneOrNull()
            ?: return YamiboResult.Failure("RSS 訂閱不存在")
        val formHash = authRepository.currentUser()?.formHash ?: return YamiboResult.NotLoggedIn
        val startedAt = currentTimeMillis()
        subscriptionQueries.updateRefreshStarted(
            lastRefreshStartedAt = startedAt,
            lastRefreshStatus = RssSearchSubscriptionRepository.RefreshStatus.Running.name,
            lastRefreshMessage = null,
            updatedAt = startedAt,
            id = subscriptionId,
        )
        reloadState()

        return when (val result = forumRepository.fetchSearch(subscription.query, subscription.forumId?.toInt()?.let(::ForumId), formHash)) {
            is YamiboResult.Success -> {
                val beforeThreadIds = resultQueries
                    .getBySubscription(subscriptionId, Long.MAX_VALUE, 0)
                    .executeAsList()
                    .map { it.threadId }
                    .toSet()
                val now = currentTimeMillis()
                val scopedPage = normalizeSearchPageForum(result.value, subscription.forumId?.toInt()?.let(::ForumId))
                saveSearchPageCache(subscriptionId, scopedPage.pageNav?.currentPage ?: 1, scopedPage, now)
                mergeResults(subscriptionId, scopedPage.threads, now, pageIndex = 1, pageForumId = scopedPage.forumId)
                val newCount = result.value.threads.count { it.tid.value.toLong() !in beforeThreadIds }
                val totalCount = resultQueries.countBySubscription(subscriptionId).executeAsOne().toInt()
                subscriptionQueries.updateRefreshFinished(
                    lastRefreshFinishedAt = now,
                    lastRefreshStatus = RssSearchSubscriptionRepository.RefreshStatus.Success.name,
                    lastRefreshMessage = null,
                    lastSearchId = scopedPage.searchId?.value?.toLong(),
                    lastTotalCount = scopedPage.totalCount.toLong(),
                    updatedAt = now,
                    id = subscriptionId,
                )
                reloadState()
                YamiboResult.Success(
                    RssSearchSubscriptionRepository.RefreshSummary(
                        subscriptionId = subscriptionId,
                        fetchedCount = result.value.threads.size,
                        newCount = newCount,
                        totalCount = totalCount,
                    )
                )
            }
            else -> {
                val now = currentTimeMillis()
                subscriptionQueries.updateRefreshFinished(
                    lastRefreshFinishedAt = now,
                    lastRefreshStatus = RssSearchSubscriptionRepository.RefreshStatus.Failed.name,
                    lastRefreshMessage = result.message(),
                    lastSearchId = subscription.lastSearchId,
                    lastTotalCount = subscription.lastTotalCount,
                    updatedAt = now,
                    id = subscriptionId,
                )
                reloadState()
                YamiboResult.Failure(result.message())
            }
        }
    }

    override suspend fun refreshAllEnabled(): List<YamiboResult<RssSearchSubscriptionRepository.RefreshSummary>> {
        return subscriptionQueries.getEnabled().executeAsList().map { refresh(it.id) }
    }

    override suspend fun getSubscription(subscriptionId: Long): RssSearchSubscriptionRepository.SubscriptionSummary? {
        return subscriptionQueries.getById(subscriptionId).executeAsOneOrNull()?.toSummary()
    }

    override suspend fun getCatalogPage(
        subscriptionId: Long,
        page: Int,
        pageSize: Int,
    ): RssSearchSubscriptionRepository.CatalogPage? {
        val subscriptionRow = subscriptionQueries.getById(subscriptionId).executeAsOneOrNull() ?: return null
        val formHash = authRepository.currentUser()?.formHash ?: return null
        val safePage = page.coerceAtLeast(1)
        val forumId = subscriptionRow.forumId?.toInt()?.let(::ForumId)
        var effectiveSubscriptionRow = subscriptionRow
        val searchResult = if (safePage == 1) {
            forumRepository.fetchSearch(subscriptionRow.query, forumId, formHash)
        } else if (subscriptionRow.lastSearchId == null) {
            when (val first = forumRepository.fetchSearch(subscriptionRow.query, forumId, formHash)) {
                is YamiboResult.Success -> {
                    val now = currentTimeMillis()
                    val firstPage = normalizeSearchPageForum(first.value, forumId)
                    saveSearchPageCache(subscriptionId, firstPage.pageNav?.currentPage ?: 1, firstPage, now)
                    mergeResults(subscriptionId, firstPage.threads, now, pageIndex = 1, pageForumId = firstPage.forumId)
                    subscriptionQueries.updateRefreshFinished(
                        lastRefreshFinishedAt = now,
                        lastRefreshStatus = RssSearchSubscriptionRepository.RefreshStatus.Success.name,
                        lastRefreshMessage = null,
                        lastSearchId = firstPage.searchId?.value?.toLong(),
                        lastTotalCount = firstPage.totalCount.toLong(),
                        updatedAt = now,
                        id = subscriptionId,
                    )
                    effectiveSubscriptionRow = subscriptionQueries.getById(subscriptionId).executeAsOne()
                    firstPage.searchId?.let { forumRepository.fetchSearchById(subscriptionRow.query, it, safePage) }
                        ?: YamiboResult.Failure("搜尋頁缺少 search id")
                }
                else -> first
            }
        } else {
            forumRepository.fetchSearchById(subscriptionRow.query, SearchId(subscriptionRow.lastSearchId.toInt()), safePage)
        }
        val searchPage = when (searchResult) {
            is YamiboResult.Success -> normalizeSearchPageForum(searchResult.value, forumId)
            else -> return buildCachedCatalogPage(effectiveSubscriptionRow, safePage)
        }
        val now = currentTimeMillis()
        saveSearchPageCache(subscriptionId, safePage, searchPage, now)
        mergeResults(subscriptionId, searchPage.threads, now, pageIndex = safePage, pageForumId = searchPage.forumId)
        subscriptionQueries.updateRefreshFinished(
            lastRefreshFinishedAt = now,
            lastRefreshStatus = RssSearchSubscriptionRepository.RefreshStatus.Success.name,
            lastRefreshMessage = null,
            lastSearchId = searchPage.searchId?.value?.toLong() ?: effectiveSubscriptionRow.lastSearchId,
            lastTotalCount = searchPage.totalCount.toLong(),
            updatedAt = now,
            id = subscriptionId,
        )
        reloadState()
        val subscription = subscriptionQueries.getById(subscriptionId).executeAsOne().toSummary()
        return RssSearchSubscriptionRepository.CatalogPage(
            subscription = subscription,
            tagPage = TagPage(
                tagName = subscription.title,
                threadSummaries = searchPage.threads,
                pageNav = searchPage.pageNav?.copy(currentPage = safePage),
            ),
            readThreadIds = readThreadIds(subscriptionId),
            totalResults = searchPage.totalCount,
        )
    }

    override suspend fun getCachedCatalogPage(
        subscriptionId: Long,
        page: Int,
        pageSize: Int,
    ): RssSearchSubscriptionRepository.CatalogPage? {
        val subscriptionRow = subscriptionQueries.getById(subscriptionId).executeAsOneOrNull() ?: return null
        return buildCachedCatalogPage(subscriptionRow, page)
    }

    private fun buildCachedCatalogPage(
        subscriptionRow: RssSearchSubscription,
        page: Int,
    ): RssSearchSubscriptionRepository.CatalogPage? {
        val subscription = subscriptionRow.toSummary()
        val safePage = page.coerceAtLeast(1)
        val cache = pageCacheQueries.getByPage(subscription.id, safePage.toLong()).executeAsOneOrNull()
            ?: return null
        val searchPage = runCatching {
            normalizeSearchPageForum(
                json.decodeFromString<SearchPage>(cache.pageJson),
                subscription.forumId,
            )
        }
            .onFailure { Logger.d(TAG, "Failed to decode cached RSS catalog page subscriptionId=${subscription.id} page=$safePage", it) }
            .getOrNull() ?: return null
        return RssSearchSubscriptionRepository.CatalogPage(
            subscription = subscription,
            tagPage = TagPage(
                tagName = subscription.title,
                threadSummaries = normalizeThreadsForum(searchPage.threads, searchPage.forumId ?: subscription.forumId),
                pageNav = searchPage.pageNav?.copy(currentPage = safePage),
            ),
            readThreadIds = readThreadIds(subscription.id),
            totalResults = searchPage.totalCount,
        )
    }

    override suspend fun markRead(subscriptionId: Long, threadId: Long) {
        resultQueries.markRead(currentTimeMillis(), subscriptionId, threadId)
        reloadState()
    }

    override suspend fun markUnread(subscriptionId: Long, threadId: Long) {
        resultQueries.markUnread(subscriptionId, threadId)
        reloadState()
    }

    override suspend fun rename(subscriptionId: Long, title: String) {
        val subscription = subscriptionQueries.getById(subscriptionId).executeAsOneOrNull() ?: return
        val now = currentTimeMillis()
        recordSubscriptionMutation(
            entityId = rssSearchSubscriptionSyncId(subscription.query, subscription.forumId),
            kind = SyncOperationKind.Patch,
            fields = mapOf("title" to title, "updatedAt" to now.toString()),
        ) {
            subscriptionQueries.rename(title, now, subscriptionId)
        }
        reloadState()
    }

    override suspend fun setEnabled(subscriptionId: Long, enabled: Boolean) {
        val subscription = subscriptionQueries.getById(subscriptionId).executeAsOneOrNull() ?: return
        val now = currentTimeMillis()
        recordSubscriptionMutation(
            entityId = rssSearchSubscriptionSyncId(subscription.query, subscription.forumId),
            kind = SyncOperationKind.Patch,
            fields = mapOf("enabled" to enabled.toString(), "updatedAt" to now.toString()),
        ) {
            subscriptionQueries.setEnabled(if (enabled) 1 else 0, now, subscriptionId)
        }
        reloadState()
    }

    override suspend fun delete(subscriptionId: Long) {
        val subscription = subscriptionQueries.getById(subscriptionId).executeAsOneOrNull() ?: return
        recordSubscriptionMutation(
            entityId = rssSearchSubscriptionSyncId(subscription.query, subscription.forumId),
            kind = SyncOperationKind.Delete,
            fields = emptyMap(),
        ) {
            pageCacheQueries.deleteBySubscription(subscriptionId)
            resultQueries.deleteBySubscription(subscriptionId)
            subscriptionQueries.deleteById(subscriptionId)
        }
        reloadState()
    }

    private fun recordSubscriptionMutation(
        entityId: String,
        kind: SyncOperationKind,
        fields: Map<String, String?>,
        mutation: () -> Unit,
    ) {
        val recorder = mutationRecorder
        if (recorder == null) {
            db.transaction { mutation() }
        } else {
            recorder.record(
                domain = RSS_SUBSCRIPTION_DOMAIN,
                entityId = entityId,
                kind = kind,
                fields = fields,
                entityGeneration = recorder.currentGeneration(
                    domain = RSS_SUBSCRIPTION_DOMAIN,
                    entityId = entityId,
                ),
            ) {
                mutation()
            }
        }
    }

    private fun subscriptionFields(
        title: String,
        query: String,
        forumId: Long?,
        forumName: String?,
        enabled: Boolean,
        createdAt: Long,
        updatedAt: Long,
    ): Map<String, String?> = mapOf(
        "title" to title,
        "query" to query,
        "forumId" to forumId?.toString(),
        "forumName" to forumName,
        "enabled" to enabled.toString(),
        "createdAt" to createdAt.toString(),
        "updatedAt" to updatedAt.toString(),
    )

    private fun mergeResults(
        subscriptionId: Long,
        threads: List<ThreadSummary>,
        seenAt: Long,
        pageIndex: Int?,
        pageForumId: ForumId? = null,
    ) {
        db.transaction {
            threads.forEachIndexed { index, thread ->
                val normalizedThread = normalizeThreadForum(thread, pageForumId)
                resultQueries.insertResultIgnore(
                    subscriptionId = subscriptionId,
                    threadId = normalizedThread.tid.value.toLong(),
                    title = normalizedThread.title,
                    forumId = normalizedThread.fid?.value?.toLong(),
                    attachmentType = normalizedThread.attachmentType?.name,
                    hasPoll = if (normalizedThread.hasPoll) 1 else 0,
                    url = normalizedThread.url,
                    authorId = normalizedThread.author?.uid?.value?.toLong(),
                    authorName = normalizedThread.author?.name,
                    authorAvatarUrl = normalizedThread.author?.avatarUrl,
                    description = normalizedThread.description,
                    replyCount = normalizedThread.replyCount?.toLong(),
                    viewCount = normalizedThread.viewCount?.toLong(),
                    tag = normalizedThread.tag,
                    lastUpdateText = normalizedThread.lastUpdate?.text,
                    lastUpdateSpecialText = normalizedThread.lastUpdate?.specialText,
                    lastUpdateEpoch = normalizedThread.lastUpdate?.epoch,
                    firstSeenAt = seenAt,
                    lastSeenAt = seenAt,
                    readAt = null,
                    pageIndex = pageIndex?.toLong(),
                    positionInPage = index.toLong(),
                )
                resultQueries.updateExistingResult(
                    title = normalizedThread.title,
                    forumId = normalizedThread.fid?.value?.toLong(),
                    attachmentType = normalizedThread.attachmentType?.name,
                    hasPoll = if (normalizedThread.hasPoll) 1 else 0,
                    url = normalizedThread.url,
                    authorId = normalizedThread.author?.uid?.value?.toLong(),
                    authorName = normalizedThread.author?.name,
                    authorAvatarUrl = normalizedThread.author?.avatarUrl,
                    description = normalizedThread.description,
                    replyCount = normalizedThread.replyCount?.toLong(),
                    viewCount = normalizedThread.viewCount?.toLong(),
                    tag = normalizedThread.tag,
                    lastUpdateText = normalizedThread.lastUpdate?.text,
                    lastUpdateSpecialText = normalizedThread.lastUpdate?.specialText,
                    lastUpdateEpoch = normalizedThread.lastUpdate?.epoch,
                    lastSeenAt = seenAt,
                    pageIndex = pageIndex?.toLong(),
                    positionInPage = index.toLong(),
                    subscriptionId = subscriptionId,
                    threadId = normalizedThread.tid.value.toLong(),
                )
            }
        }
    }

    private fun saveSearchPageCache(
        subscriptionId: Long,
        pageIndex: Int,
        searchPage: SearchPage,
        fetchedAt: Long,
    ) {
        pageCacheQueries.upsert(
            subscriptionId = subscriptionId,
            pageIndex = pageIndex.coerceAtLeast(1).toLong(),
            pageJson = json.encodeToString(searchPage),
            fetchedAt = fetchedAt,
        )
    }

    private fun normalizeSearchPageForum(searchPage: SearchPage, fallbackForumId: ForumId?): SearchPage {
        val forumId = searchPage.forumId ?: fallbackForumId
        return searchPage.copy(
            forumId = forumId,
            threads = normalizeThreadsForum(searchPage.threads, forumId),
        )
    }

    private fun normalizeThreadsForum(threads: List<ThreadSummary>, fallbackForumId: ForumId?): List<ThreadSummary> =
        threads.map { normalizeThreadForum(it, fallbackForumId) }

    private fun normalizeThreadForum(thread: ThreadSummary, fallbackForumId: ForumId?): ThreadSummary =
        if (thread.fid != null || fallbackForumId == null) thread else thread.copy(fid = fallbackForumId)

    private fun readThreadIds(subscriptionId: Long): Set<Long> =
        resultQueries
            .getBySubscription(subscriptionId, Long.MAX_VALUE, 0)
            .executeAsList()
            .filter { it.readAt != null }
            .map { it.threadId }
            .toSet()

    private fun reloadState() {
        val rows = subscriptionQueries.getAll().executeAsList()
        subscriptionsFlow.value = rows.map { it.toSummary() }
        unreadCountFlow.value = resultQueries.countUnreadAll().executeAsOne().toInt()
    }

    private fun RssSearchSubscription.toSummary(): RssSearchSubscriptionRepository.SubscriptionSummary {
        val forumIdValue = forumId?.toInt()
        return RssSearchSubscriptionRepository.SubscriptionSummary(
            id = id,
            title = title,
            query = query,
            forumId = forumIdValue?.let(::ForumId),
            forumName = forumName ?: forumIdValue?.let { YamiboForum.toForumName(ForumId(it)) },
            enabled = enabled != 0L,
            createdAt = createdAt,
            updatedAt = updatedAt,
            lastRefreshStartedAt = lastRefreshStartedAt,
            lastRefreshFinishedAt = lastRefreshFinishedAt,
            lastRefreshStatus = lastRefreshStatus?.let { raw ->
                runCatching { RssSearchSubscriptionRepository.RefreshStatus.valueOf(raw) }
                    .onFailure { Logger.d(TAG, "Failed to parse refresh status value=$raw", it) }
                    .getOrNull()
            },
            lastRefreshMessage = lastRefreshMessage,
            lastTotalCount = lastTotalCount.toInt(),
            unreadCount = resultQueries.countUnreadBySubscription(id).executeAsOne().toInt(),
        )
    }

    private fun normalizeKeyword(value: String): String =
        value.trim().replace(Regex("\\s+"), " ")
}

private const val TAG = "RssSearchSubscriptionRepository"
private const val RSS_SUBSCRIPTION_DOMAIN = "rss.search-subscription"

private fun defaultRssSearchJson() = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

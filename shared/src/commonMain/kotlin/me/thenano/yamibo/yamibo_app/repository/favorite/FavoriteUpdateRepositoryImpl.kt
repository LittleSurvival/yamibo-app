package me.thenano.yamibo.yamibo_app.repository.favorite

import io.github.littlesurvival.YamiboForum
import io.github.littlesurvival.core.YamiboResult
import io.github.littlesurvival.dto.page.Post
import io.github.littlesurvival.dto.page.TagPage
import io.github.littlesurvival.dto.page.ThreadPage
import io.github.littlesurvival.dto.value.TagId
import io.github.littlesurvival.dto.value.ThreadId
import io.github.littlesurvival.dto.value.UserId
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.thenano.yamibo.yamibo_app.Database
import me.thenano.yamibo.yamibo_app.i18n.i18n
import me.thenano.yamibo.yamibo_app.repository.*
import me.thenano.yamibo.yamibo_app.repository.FavoriteUpdateRepository.*
import me.thenano.yamibo.yamibo_app.repository.appsync.AppSyncMutationRecorder
import me.thenano.yamibo.yamibo_app.repository.appsync.backfillFavoriteUpdateSyncState
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncDomainId
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncEntityId
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperationKind
import me.thenano.yamibo.yamibo_app.repository.backup.favoriteUpdateEventIdentity
import me.thenano.yamibo.yamibo_app.store.appsync.LocalSyncOperationDraft
import me.thenano.yamibo.yamibo_app.util.time.currentTimeMillis
import me.thenano.yamibo.yamibo_app.*
import kotlin.math.max
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

class FavoriteUpdateRepositoryImpl internal constructor(
    private val db: Database,
    private val localFavoriteRepository: FavoriteStoreRepository,
    private val threadRepository: ThreadRepository,
    private val tagRepository: TagRepository,
    private val rssSearchSubscriptionRepository: RssSearchSubscriptionRepository,
    private val mutationRecorder: AppSyncMutationRecorder? = null,
) : FavoriteUpdateRepository {
    private val targetQueries = db.favoriteUpdateTrackedTargetQueries
    private val eventQueries = db.favoriteUpdateEventQueries
    private val runQueries = db.favoriteUpdateRunQueries
    private val filterQueries = db.favoriteUpdateFidFilterQueries
    private val categoryFilterQueries = db.favoriteUpdateCategoryFilterQueries
    private val fidChoiceQueries = db.favoriteUpdateFidChoiceQueries
    private val categoryChoiceQueries = db.favoriteUpdateCategoryChoiceQueries
    private val itemQueries = db.localFavoriteItemQueries
    private val rssResultQueries = db.rssSearchSubscriptionResultQueries
    private val interruptRequestedRunIds = linkedSetOf<String>()
    private val stateFlow = MutableStateFlow<RunState>(RunState.Idle)

    override val state: StateFlow<RunState> = stateFlow.asStateFlow()

    init {
        backfillFavoriteUpdateSyncState(db)
        stateFlow.value = runQueries.getLatestRecoverable()
            .executeAsOneOrNull()
            ?.toSnapshot()
            ?.toState()
            ?: RunState.Idle
    }

    override suspend fun startRun(): String {
        val now = currentTimeMillis()
        val runId = "favorite-update-$now-${Random.nextInt(1000, 9999)}"
        val snapshot = RunSnapshot(
            runId = runId,
            status = RunStatus.RUNNING,
            phase = RunPhase.PREPARING,
            startedAt = now,
            updatedAt = now,
            finishedAt = null,
            totalCount = 0,
            completedCount = 0,
            skippedCount = 0,
            failedCount = 0,
            detectedCount = 0,
            currentItem = i18n("準備檢查收藏更新"),
            logMessage = null,
            warningMessage = null,
            errorMessage = null,
        )
        interruptRequestedRunIds.remove(runId)
        persistSnapshot(snapshot)
        stateFlow.value = RunState.Running(snapshot)
        return runId
    }

    override suspend fun resumeInterruptedRun(): String? {
        val latest = getLatestSnapshot() ?: return null
        return when (latest.status) {
            RunStatus.RUNNING -> latest.runId
            RunStatus.INTERRUPTED -> {
                interruptRequestedRunIds.remove(latest.runId)
                updateSnapshot(
                    latest.copy(
                        status = RunStatus.RUNNING,
                        phase = RunPhase.CHECKING,
                        errorMessage = null,
                        currentItem = latest.currentItem ?: i18n("繼續檢查收藏更新"),
                    )
                ).runId
            }
            else -> startRun()
        }
    }

    override suspend fun interruptRun(runId: String) {
        interruptRequestedRunIds += runId
        val snapshot = runQueries.getByRunId(runId).executeAsOneOrNull()?.toSnapshot() ?: return
        if (snapshot.status == RunStatus.RUNNING) {
            interruptRun(snapshot, i18n("更新檢查已中斷"))
        }
    }

    override suspend fun cancelRun(runId: String) {
        interruptRequestedRunIds += runId
        val snapshot = runQueries.getByRunId(runId).executeAsOneOrNull()?.toSnapshot() ?: return
        val now = currentTimeMillis()
        val canceled = snapshot.copy(
            status = RunStatus.CANCELED,
            phase = RunPhase.CANCELED,
            updatedAt = now,
            finishedAt = now,
            currentItem = i18n("更新檢查已取消"),
            errorMessage = i18n("更新檢查已取消"),
        )
        persistSnapshot(canceled)
        stateFlow.value = RunState.Idle
        interruptRequestedRunIds.remove(runId)
    }

    override suspend fun markRunInterrupted(runId: String, reason: String) {
        val snapshot = runQueries.getByRunId(runId).executeAsOneOrNull()?.toSnapshot() ?: return
        if (snapshot.status == RunStatus.RUNNING) interruptRun(snapshot, reason)
    }

    override suspend fun getLatestSnapshot(): RunSnapshot? =
        runQueries.getLatestRecoverable().executeAsOneOrNull()?.toSnapshot()

    override suspend fun getRunSnapshot(runId: String): RunSnapshot? =
        runQueries.getByRunId(runId).executeAsOneOrNull()?.toSnapshot()

    override suspend fun runUpdate(runId: String) {
        interruptRequestedRunIds.remove(runId)
        var current = runQueries.getByRunId(runId).executeAsOneOrNull()?.toSnapshot() ?: return
        if (shouldStop(runId)) {
            interruptRun(current, i18n("更新檢查已中斷"))
            return
        }

        val favorites = getFavoriteUpdateCandidates()
        refreshFidFilters(favorites)
        refreshCategoryFilters(favorites)
        val enabledFids = activeFidRestriction()
        val enabledCategories = activeCategoryRestriction()
        val categoryIdsByItem = if (enabledCategories.isEmpty()) {
            emptyMap()
        } else {
            favorites.associate { item -> item.id to localFavoriteRepository.getCategoryIdsForItem(item.id) }
        }
        val targets = favorites.filter { item ->
            val fid = item.scopeFid()
            val fidMatches = enabledFids.isEmpty() || (fid != null && fid in enabledFids)
            val categoryMatches = enabledCategories.isEmpty() ||
                categoryIdsByItem[item.id].orEmpty().any { it in enabledCategories }
            fidMatches && categoryMatches
        }
        val resumeFrom = current.completedCount.coerceIn(0, targets.size)
        current = updateSnapshot(
            current.copy(
                phase = RunPhase.CHECKING,
                totalCount = targets.size,
                currentItem = if (resumeFrom > 0) {
                    i18n("已載入 {} 個追蹤項目，從第 {} 項繼續", targets.size, resumeFrom + 1)
                } else {
                    i18n("已載入 {} 個追蹤項目", targets.size)
                },
            )
        )

        val aliveKeys = favorites.map { it.targetKey() }.toSet()
        targetQueries.getAll().executeAsList().forEach { target ->
            val key = "${target.targetType}:${target.targetId}:${target.authorId ?: 0L}"
            if (key !in aliveKeys) {
                targetQueries.deleteByTarget(target.targetType, target.targetId, target.authorId ?: 0L)
            }
        }

        for ((index, item) in targets.withIndex().drop(resumeFrom)) {
            if (shouldStop(runId)) {
                interruptRun(current, i18n("更新檢查已中斷"))
                return
            }
            current = updateSnapshot(
                current.copy(currentItem = i18n("[{}/{}] 已載入 #{} {}", index + 1, targets.size, item.targetId, item.title))
            )
            val result = checkItem(item)
            if (shouldStop(runId)) {
                interruptRun(current, i18n("更新檢查已中斷"))
                return
            }
            current = when (result) {
                CheckResult.Skipped -> updateSnapshot(current.copy(skippedCount = current.skippedCount + 1))
                is CheckResult.Failed -> updateSnapshot(
                    current.copy(
                        failedCount = current.failedCount + 1,
                        warningMessage = appendLine(current.warningMessage, result.reason),
                    )
                )
                is CheckResult.Checked -> updateSnapshot(
                    current.copy(
                        completedCount = current.completedCount + 1,
                        detectedCount = current.detectedCount + result.detectedCount,
                    )
                )
            }
            delay(250.milliseconds)
        }

        val now = currentTimeMillis()
        val completed = current.copy(
            status = RunStatus.COMPLETED,
            phase = RunPhase.COMPLETED,
            updatedAt = now,
            finishedAt = now,
            currentItem = i18n("更新檢查完成"),
        )
        persistSnapshot(completed)
        stateFlow.value = RunState.Completed(completed)
        interruptRequestedRunIds.remove(runId)
    }

    override suspend fun getActiveEvents(): List<UpdateEvent> =
        eventQueries.getActive().executeAsList().map { it.toModel() }

    override suspend fun getActiveEventsFiltered(): List<UpdateEvent> {
        val events = getActiveEvents()
        val enabledFids = activeFidRestriction()
        val enabledCategories = activeCategoryRestriction()
        if (enabledFids.isEmpty() && enabledCategories.isEmpty()) return events

        val favoritesByKey = if (enabledCategories.isEmpty()) {
            emptyMap()
        } else {
            localFavoriteRepository.getAllFavoriteItems().associateBy { it.targetKey() }
        }
        val categoryIdsByItem = mutableMapOf<Long, Set<Long>>()
        if (enabledCategories.isNotEmpty()) {
            favoritesByKey.values.forEach { item ->
                categoryIdsByItem[item.id] = localFavoriteRepository.getCategoryIdsForItem(item.id)
            }
        }
        return events.filter { event ->
            val eventFid = if (event.targetType == FavoriteStoreRepository.FavoriteTargetType.TagManga) {
                TAG_MANGA_SCOPE_FID
            } else {
                event.fid
            }
            val fidMatches = eventFid == null || enabledFids.isEmpty() || eventFid in enabledFids
            val categoryMatches = if (enabledCategories.isEmpty()) {
                true
            } else {
                val item = favoritesByKey[event.targetKey()] ?: return@filter false
                categoryIdsByItem[item.id].orEmpty().any { it in enabledCategories }
            }
            fidMatches && categoryMatches
        }
    }

    override suspend fun markEventRead(eventId: Long) {
        val event = eventQueries.getById(eventId).executeAsOneOrNull() ?: return
        val now = currentTimeMillis()
        recordEventPatch(event.syncId, "readAt", now) {
            eventQueries.markRead(now, eventId)
        }
    }

    override suspend fun dismissEvent(eventId: Long) {
        val event = eventQueries.getById(eventId).executeAsOneOrNull() ?: return
        val now = currentTimeMillis()
        recordEventPatch(event.syncId, "dismissedAt", now) {
            eventQueries.dismiss(now, eventId)
        }
    }

    override suspend fun dismissEvents(eventIds: List<Long>) {
        if (eventIds.isEmpty()) return
        val events = eventIds.distinct().mapNotNull {
            eventQueries.getById(it).executeAsOneOrNull()
        }
        val now = currentTimeMillis()
        val drafts = events.map {
            LocalSyncOperationDraft(
                domainId = SyncDomainId(EVENT_DOMAIN),
                entityId = SyncEntityId(it.syncId),
                kind = SyncOperationKind.Patch,
                fields = mapOf("dismissedAt" to now.toString()),
            )
        }
        recordBatch(drafts) {
            events.forEach { eventQueries.dismiss(now, it.id) }
        }
    }

    override suspend fun dismissAllEvents() {
        dismissEvents(eventQueries.getActive().executeAsList().map { it.id })
    }

    override suspend fun getFidFilters(): List<FidFilter> =
        refreshAndGetFidFilters()

    override suspend fun setFidEnabled(fid: Int, enabled: Boolean) {
        val now = currentTimeMillis()
        val value = if (enabled) 1L else 0L
        recordFilterChoice(
            domain = FID_FILTER_DOMAIN,
            entityId = "fid:$fid",
            fields = mapOf("fid" to fid.toString(), "enabled" to enabled.toString()),
        ) { operationId ->
            fidChoiceQueries.upsertChoice(fid.toLong(), value, operationId, now)
            filterQueries.setEnabled(value, now, fid.toLong())
        }
    }

    override suspend fun getCategoryFilters(): List<CategoryFilter> {
        refreshCategoryFilters(getFavoriteUpdateCandidates())
        return categoryFilterQueries.getAll().executeAsList().map { it.toModel() }
    }

    override suspend fun setCategoryEnabled(categoryId: Long, enabled: Boolean) {
        val category = db.localFavoriteCategoryQueries.getById(categoryId).executeAsOneOrNull()
            ?: return
        val syncId = category.syncId ?: return
        val now = currentTimeMillis()
        val value = if (enabled) 1L else 0L
        recordFilterChoice(
            domain = CATEGORY_FILTER_DOMAIN,
            entityId = "category:$syncId",
            fields = mapOf("categorySyncId" to syncId, "enabled" to enabled.toString()),
        ) { operationId ->
            categoryChoiceQueries.upsertChoice(syncId, value, operationId, now)
            categoryFilterQueries.setEnabled(value, now, categoryId)
        }
    }

    override suspend fun getScopeTargets(): List<ScopeTarget> =
        getFavoriteUpdateCandidates().map { item ->
            ScopeTarget(
                fid = item.scopeFid(),
                categoryIds = localFavoriteRepository.getCategoryIdsForItem(item.id),
            )
        }

    private suspend fun checkItem(item: FavoriteStoreRepository.FavoriteItem): CheckResult {
        return when (item.targetType) {
            FavoriteStoreRepository.FavoriteTargetType.ThreadNormal -> checkThread(item, TargetMode.NormalThread, null)
            FavoriteStoreRepository.FavoriteTargetType.ThreadNovel -> checkThread(item, TargetMode.NovelThread, item.authorId)
            FavoriteStoreRepository.FavoriteTargetType.TagManga -> checkTagManga(item)
            FavoriteStoreRepository.FavoriteTargetType.RssSearch -> checkRssSearch(item)
        }
    }

    private suspend fun checkThread(
        item: FavoriteStoreRepository.FavoriteItem,
        mode: TargetMode,
        authorId: UserId?,
    ): CheckResult {
        val threadId = ThreadId(item.targetId.toInt())
        val result = threadRepository.fetchThread(threadId, authorId, page = 1, reverse = true)
        return when (result) {
            is YamiboResult.Success -> handleThreadPage(item, mode, result.value, authorId)
            else -> result.toCheckFailure(item.title)
        }
    }

    private fun handleThreadPage(
        item: FavoriteStoreRepository.FavoriteItem,
        mode: TargetMode,
        page: ThreadPage,
        authorId: UserId?,
    ): CheckResult {
        val now = currentTimeMillis()
        val authorIdValue = authorId?.value?.toLong() ?: 0L
        val existing = targetQueries.getByTarget(item.targetType.name, item.targetId, authorIdValue).executeAsOneOrNull()
        val postsForMode = if (mode == TargetMode.NovelThread && authorId != null) {
            page.posts.filter { it.author.uid.value == authorId.value }
        } else {
            page.posts
        }
        val latest = postsForMode.maxByOrNull { it.pid.value }
        val latestPid = latest?.pid?.value?.toLong()
        val latestUpdateMillis = latest?.updateTimeMillis()
        val pageCount = page.pageNav?.totalPages ?: page.pageNav?.currentPage
        val replyCount = page.thread.totalReplies

        if (item.forumId == null || item.forumName.isNullOrBlank()) {
            itemQueries.updateForumInfo(
                forumId = page.thread.forum.fid.value.toLong(),
                forumName = page.thread.forum.name,
                id = item.id,
            )
        }

        if (existing?.baselineReady != 1L) {
            val shouldReportImportedUpdate = shouldReportImportedUpdate(
                item = item,
                latestPid = latestPid,
                latestUpdateMillis = latestUpdateMillis,
                existing = existing,
            )
            val detectedCount = if (shouldReportImportedUpdate && latestPid != null) {
                insertEvent(
                    item = item,
                    mode = mode,
                    summary = mode.importedUpdateSummary(),
                    latestPostTitle = latest.title.takeIf { it.isNotBlank() },
                    detailIds = listOf(latestPid),
                    ambiguous = false,
                    detectedAt = now,
                    sourceDiscriminator = threadSourceDiscriminator(latestPid, latestUpdateMillis),
                )
                1
            } else {
                0
            }
            upsertTarget(
                existing = existing,
                item = item,
                mode = mode,
                latestPostId = latestPid,
                latestAuthorPostId = if (mode == TargetMode.NovelThread) latestPid else null,
                replyCount = replyCount,
                pageCount = pageCount,
                checkedAt = now,
                updatedAt = latestUpdateMillis,
                baselineReady = true,
            )
            return CheckResult.Checked(detectedCount)
        }

        val knownLatest = if (mode == TargetMode.NovelThread) existing.knownLatestAuthorPostId else existing.knownLatestPostId
        val newPosts = if (knownLatest == null) emptyList() else postsForMode.filter { it.pid.value.toLong() > knownLatest }
        val changedByPostId = latestPid != null && knownLatest != null && latestPid > knownLatest
        val changedByImportedTime = shouldReportImportedUpdate(
            item = item,
            latestPid = latestPid,
            latestUpdateMillis = latestUpdateMillis,
            existing = existing,
        )
        val changedByTime = latestUpdateMillis != null &&
            existing.lastUpdatedAt != null &&
            latestUpdateMillis > existing.lastUpdatedAt + UPDATE_TIME_TOLERANCE_MILLIS
        val changed = changedByPostId || changedByImportedTime || changedByTime
        val ambiguous = changed && newPosts.isEmpty()
        val detectedCount = if (changed) {
            val summary = when {
                changedByImportedTime -> mode.importedUpdateSummary()
                changedByTime && !changedByPostId -> mode.editedUpdateSummary()
                ambiguous -> i18n("可能有多筆新內容")
                mode == TargetMode.NovelThread -> i18n("作者新增 {} 則內容", newPosts.size)
                else -> i18n("新增 {} 則回覆", newPosts.size)
            }
            val detailIds = when {
                newPosts.isNotEmpty() -> newPosts.map { it.pid.value.toLong() }
                latestPid != null -> listOf(latestPid)
                else -> emptyList()
            }
            insertEvent(
                item = item,
                mode = mode,
                summary = summary,
                latestPostTitle = latest?.title?.takeIf { it.isNotBlank() },
                detailIds = detailIds,
                ambiguous = ambiguous,
                detectedAt = now,
                sourceDiscriminator = latestPid?.let {
                    threadSourceDiscriminator(it, latestUpdateMillis)
                },
            )
            1
        } else {
            0
        }

        upsertTarget(
            existing = existing,
            item = item,
            mode = mode,
            latestPostId = latestPid ?: existing.knownLatestPostId,
            latestAuthorPostId = if (mode == TargetMode.NovelThread) latestPid ?: existing.knownLatestAuthorPostId else existing.knownLatestAuthorPostId,
            replyCount = replyCount,
            pageCount = pageCount,
            checkedAt = now,
            updatedAt = latestUpdateMillis ?: existing.lastUpdatedAt,
            baselineReady = true,
        )
        return CheckResult.Checked(detectedCount)
    }

    private fun shouldReportImportedUpdate(
        item: FavoriteStoreRepository.FavoriteItem,
        latestPid: Long?,
        latestUpdateMillis: Long?,
        existing: FavoriteUpdateTrackedTarget?,
    ): Boolean {
        if (latestPid == null || latestUpdateMillis == null) return false
        val favoriteUpdatedAt = item.lastUpdatedTime ?: return false
        if (latestUpdateMillis <= favoriteUpdatedAt + UPDATE_TIME_TOLERANCE_MILLIS) return false
        return existing?.lastUpdatedAt != latestUpdateMillis
    }

    private fun Post.updateTimeMillis(): Long? {
        val latestEpoch = maxOf(timeCreate.epoch, lastEditedTime?.epoch ?: 0L)
        return latestEpoch.takeIf { it > 0L }?.let { it * 1000L }
    }

    private fun TargetMode.importedUpdateSummary(): String = when (this) {
        TargetMode.NovelThread -> i18n("作者有新的內容")
        TargetMode.NormalThread -> i18n("帖子有新的內容")
        TargetMode.TagManga -> i18n("Tag 有新的帖子")
        TargetMode.RssSearch -> i18n("RSS 有新的帖子")
    }

    private fun TargetMode.editedUpdateSummary(): String = when (this) {
        TargetMode.NovelThread -> i18n("作者更新內容")
        TargetMode.NormalThread -> i18n("帖子內容已更新")
        TargetMode.TagManga -> i18n("Tag 內容已更新")
        TargetMode.RssSearch -> i18n("RSS 內容已更新")
    }

    private suspend fun checkRssSearch(item: FavoriteStoreRepository.FavoriteItem): CheckResult {
        val now = currentTimeMillis()
        val authorId = 0L
        val existing = targetQueries.getByTarget(item.targetType.name, item.targetId, authorId).executeAsOneOrNull()
        when (val result = rssSearchSubscriptionRepository.refresh(item.targetId)) {
            is YamiboResult.Success -> Unit
            else -> return result.toCheckFailure(item.title)
        }
        val currentRows = rssResultQueries
            .getBySubscription(item.targetId, Long.MAX_VALUE, 0)
            .executeAsList()
        val currentThreadIds = currentRows.map { it.threadId }.toSet()
        val knownIds = existing?.knownThreadIds.csvLongs().toMutableSet()
        val baselineReady = existing?.baselineReady == 1L
        val newIds = if (baselineReady) currentThreadIds.filterNot { it in knownIds } else emptyList()
        val newRows = currentRows.filter { it.threadId in newIds }
        val detectedCount = if (newRows.isNotEmpty()) {
            insertEvent(
                item = item,
                mode = TargetMode.RssSearch,
                summary = i18n("RSS 新增 {} 個帖子", newRows.size),
                latestPostTitle = newRows.firstOrNull()?.title?.takeIf { it.isNotBlank() },
                detailIds = newRows.map { it.threadId },
                ambiguous = false,
                detectedAt = now,
            )
            1
        } else {
            0
        }
        knownIds += currentThreadIds
        targetQueries.upsert(
            targetType = item.targetType.name,
            targetId = item.targetId,
            authorId = authorId,
            fid = item.scopeFid()?.toLong(),
            forumName = item.scopeForumName(item.scopeFid() ?: RSS_SCOPE_FID),
            title = item.title,
            mode = TargetMode.RssSearch.name,
            coverUrl = item.coverUrl,
            knownLatestPostId = currentThreadIds.maxOrNull(),
            knownLatestAuthorPostId = null,
            knownReplyCount = null,
            knownPageCount = null,
            knownThreadIds = knownIds.joinToString(","),
            knownFirstThreadId = currentThreadIds.firstOrNull(),
            knownMaxPage = null,
            tagPageFingerprints = null,
            baselineReady = 1L,
            lastCheckedAt = now,
            lastUpdatedAt = if (detectedCount > 0) now else existing?.lastUpdatedAt,
            lastError = null,
            consecutiveFailures = 0L,
        )
        return CheckResult.Checked(detectedCount)
    }

    private suspend fun checkTagManga(item: FavoriteStoreRepository.FavoriteItem): CheckResult {
        val now = currentTimeMillis()
        val authorId = 0L
        val existing = targetQueries.getByTarget(item.targetType.name, item.targetId, authorId).executeAsOneOrNull()
        val pageOne = when (val result = tagRepository.fetchTagPage(TagId(item.targetId.toInt()), 1)) {
            is YamiboResult.Success -> result.value
            else -> return result.toCheckFailure(item.title)
        }
        val knownIds = existing?.knownThreadIds?.csvLongs()?.toMutableSet() ?: linkedSetOf()
        val firstPageIds = pageOne.threadSummaries.map { it.tid.value.toLong() }
        val currentMaxPage = pageOne.pageNav?.totalPages ?: pageOne.pageNav?.currentPage ?: 1
        val previousMaxPage = existing?.knownMaxPage?.toInt() ?: currentMaxPage
        val pagesToScan = linkedSetOf(1, previousMaxPage, currentMaxPage)
        if (currentMaxPage > previousMaxPage) {
            for (page in (previousMaxPage + 1)..currentMaxPage) pagesToScan += page
        }

        var maxPageSeen = currentMaxPage
        val scannedPages = linkedMapOf<Int, TagPage>()
        scannedPages[1] = pageOne
        var cursor = 0
        while (cursor < pagesToScan.size && pagesToScan.size <= MAX_TAG_SCAN_PAGES) {
            val pageIndex = pagesToScan.elementAt(cursor++)
            val page = if (pageIndex == 1) pageOne else fetchTagPageOrFailure(item, pageIndex).getOrElse {
                return CheckResult.Failed(it.message ?: i18n("Tag 頁面載入失敗"))
            }
            scannedPages[pageIndex] = page
            val pageMax = page.pageNav?.totalPages ?: maxPageSeen
            if (pageMax > maxPageSeen) {
                for (next in (maxPageSeen + 1)..pageMax) pagesToScan += next
                maxPageSeen = pageMax
            }
        }

        val baselineReady = existing?.baselineReady == 1L
        val allScannedThreads = scannedPages.values.flatMap { it.threadSummaries }
        val newThreads = if (baselineReady) {
            allScannedThreads.filter { it.tid.value.toLong() !in knownIds }.distinctBy { it.tid.value }
        } else {
            emptyList()
        }
        val ambiguous = pagesToScan.size > MAX_TAG_SCAN_PAGES
        val fingerprints = scannedPages.map { (page, tagPage) ->
            "$page:${tagPage.threadSummaries.map { it.tid.value }.joinToString("|")}"
        }.joinToString(";")
        val detectedCount = when {
            !baselineReady -> 0
            newThreads.isNotEmpty() -> {
                insertEvent(
                    item = item,
                    mode = TargetMode.TagManga,
                    summary = i18n("Tag 新增 {} 個帖子", newThreads.size),
                    latestPostTitle = newThreads.firstOrNull()?.title?.takeIf { it.isNotBlank() },
                    detailIds = newThreads.map { it.tid.value.toLong() },
                    ambiguous = false,
                    detectedAt = now,
                )
                1
            }
            ambiguous -> {
                insertEvent(
                    item = item,
                    mode = TargetMode.TagManga,
                    summary = i18n("Tag 頁數變動過大，可能有多個新帖子"),
                    latestPostTitle = null,
                    detailIds = emptyList(),
                    ambiguous = true,
                    detectedAt = now,
                    sourceDiscriminator = "scan:$fingerprints",
                )
                1
            }
            else -> 0
        }

        knownIds += allScannedThreads.map { it.tid.value.toLong() }
        val firstThreadId = firstPageIds.firstOrNull() ?: existing?.knownFirstThreadId
        targetQueries.upsert(
            targetType = item.targetType.name,
            targetId = item.targetId,
            authorId = authorId,
            fid = item.forumId?.value?.toLong(),
            forumName = item.forumName,
            title = pageOne.tagName.ifBlank { item.title },
            mode = TargetMode.TagManga.name,
            coverUrl = item.coverUrl,
            knownLatestPostId = null,
            knownLatestAuthorPostId = null,
            knownReplyCount = null,
            knownPageCount = null,
            knownThreadIds = knownIds.joinToString(","),
            knownFirstThreadId = firstThreadId,
            knownMaxPage = max(maxPageSeen, currentMaxPage).toLong(),
            tagPageFingerprints = fingerprints,
            baselineReady = 1L,
            lastCheckedAt = now,
            lastUpdatedAt = if (detectedCount > 0) now else existing?.lastUpdatedAt,
            lastError = null,
            consecutiveFailures = 0L,
        )
        return CheckResult.Checked(detectedCount)
    }

    private suspend fun fetchTagPageOrFailure(
        item: FavoriteStoreRepository.FavoriteItem,
        page: Int,
    ): Result<TagPage> {
        return when (val result = tagRepository.fetchTagPage(TagId(item.targetId.toInt()), page)) {
            is YamiboResult.Success -> Result.success(result.value)
            else -> Result.failure(IllegalStateException(result.favoriteUpdateFailureReason(item.title)))
        }
    }

    private fun upsertTarget(
        existing: FavoriteUpdateTrackedTarget?,
        item: FavoriteStoreRepository.FavoriteItem,
        mode: TargetMode,
        latestPostId: Long?,
        latestAuthorPostId: Long?,
        replyCount: Int?,
        pageCount: Int?,
        checkedAt: Long,
        updatedAt: Long?,
        baselineReady: Boolean,
    ) {
        targetQueries.upsert(
            targetType = item.targetType.name,
            targetId = item.targetId,
            authorId = item.authorId?.value?.toLong() ?: 0L,
            fid = item.forumId?.value?.toLong(),
            forumName = item.forumName,
            title = item.title,
            mode = mode.name,
            coverUrl = item.coverUrl,
            knownLatestPostId = latestPostId,
            knownLatestAuthorPostId = latestAuthorPostId,
            knownReplyCount = replyCount?.toLong(),
            knownPageCount = pageCount?.toLong(),
            knownThreadIds = existing?.knownThreadIds,
            knownFirstThreadId = existing?.knownFirstThreadId,
            knownMaxPage = existing?.knownMaxPage,
            tagPageFingerprints = existing?.tagPageFingerprints,
            baselineReady = if (baselineReady) 1L else 0L,
            lastCheckedAt = checkedAt,
            lastUpdatedAt = updatedAt,
            lastError = null,
            consecutiveFailures = 0L,
        )
    }

    private fun insertEvent(
        item: FavoriteStoreRepository.FavoriteItem,
        mode: TargetMode,
        summary: String,
        latestPostTitle: String?,
        detailIds: List<Long>,
        ambiguous: Boolean,
        detectedAt: Long,
        sourceDiscriminator: String? = null,
    ) {
        val authorId = item.authorId?.value?.toLong() ?: 0L
        val identity = favoriteUpdateEventIdentity(
            targetType = item.targetType.name,
            targetId = item.targetId,
            authorId = authorId.takeIf { it != 0L },
            mode = mode.name,
            detailIds = detailIds,
            ambiguous = ambiguous,
            detectedAt = detectedAt,
            summary = summary,
            title = item.title,
            sourceDiscriminator = sourceDiscriminator,
        )
        if (eventQueries.getBySyncId(identity.syncId).executeAsOneOrNull() != null) return
        val fields = mapOf(
            "targetType" to item.targetType.name,
            "targetId" to item.targetId.toString(),
            "authorId" to authorId.toString(),
            "fid" to item.forumId?.value?.toString(),
            "forumName" to item.forumName,
            "title" to item.title,
            "latestPostTitle" to latestPostTitle,
            "mode" to mode.name,
            "summary" to summary,
            "detailIds" to detailIds.distinct().sorted().joinToString(","),
            "coverUrl" to item.coverUrl,
            "detectedAt" to detectedAt.toString(),
            "ambiguous" to ambiguous.toString(),
            "sourceFingerprint" to identity.sourceFingerprint,
            "sourceDiscriminator" to identity.sourceDiscriminator,
        )
        recordMutation(
            domain = EVENT_DOMAIN,
            entityId = identity.syncId,
            kind = SyncOperationKind.Put,
            fields = fields,
        ) {
            eventQueries.upsertBySyncId(
                targetType = item.targetType.name,
                targetId = item.targetId,
                authorId = authorId,
                fid = item.forumId?.value?.toLong(),
                forumName = item.forumName,
                title = item.title,
                latestPostTitle = latestPostTitle,
                mode = mode.name,
                summary = summary,
                detailIds = detailIds.distinct().sorted().joinToString(","),
                coverUrl = item.coverUrl,
                detectedAt = detectedAt,
                readAt = null,
                dismissedAt = null,
                ambiguous = if (ambiguous) 1L else 0L,
                syncId = identity.syncId,
                sourceFingerprint = identity.sourceFingerprint,
                sourceDiscriminator = identity.sourceDiscriminator,
            )
        }
    }

    private fun refreshFidFilters(favorites: List<FavoriteStoreRepository.FavoriteItem>) {
        val now = currentTimeMillis()
        val counts = favorites.mapNotNull { item ->
            val fid = item.scopeFid() ?: return@mapNotNull null
            val name = item.scopeForumName(fid)
            fid to name
        }.groupingBy { it }.eachCount()
        val existing = filterQueries.getAll().executeAsList().associateBy { it.fid.toInt() }
        val choices = fidChoiceQueries.getAll().executeAsList().associateBy { it.fid.toInt() }
        counts.entries.forEach { (fidAndName, count) ->
            val (fid, name) = fidAndName
            filterQueries.upsertFilter(
                fid = fid.toLong(),
                forumName = name,
                enabled = choices[fid]?.enabled ?: existing[fid]?.enabled ?: 1L,
                itemCount = count.toLong(),
                updatedAt = now,
            )
        }
        val activeFids = counts.keys.map { it.first.toLong() }
        if (activeFids.isNotEmpty()) {
            filterQueries.deleteMissing(activeFids)
        } else {
            filterQueries.deleteAll()
        }
    }

    private suspend fun refreshAndGetFidFilters(): List<FidFilter> {
        refreshFidFilters(getFavoriteUpdateCandidates())
        return filterQueries.getAll().executeAsList().map { it.toModel() }
    }

    private suspend fun refreshCategoryFilters(favorites: List<FavoriteStoreRepository.FavoriteItem>) {
        val now = currentTimeMillis()
        val counts = mutableMapOf<Long, Int>()
        favorites.forEach { item ->
            localFavoriteRepository.getCategoryIdsForItem(item.id).forEach { categoryId ->
                counts[categoryId] = (counts[categoryId] ?: 0) + 1
            }
        }
        val existing = categoryFilterQueries.getAll().executeAsList().associateBy { it.categoryId }
        val activeCategories = localFavoriteRepository.getCategories()
        activeCategories.forEach { category ->
            val choice = db.localFavoriteCategoryQueries.getById(category.id)
                .executeAsOneOrNull()
                ?.syncId
                ?.let {
                categoryChoiceQueries.getBySyncId(it).executeAsOneOrNull()
            }
            categoryFilterQueries.upsertFilter(
                categoryId = category.id,
                categoryName = category.name,
                enabled = choice?.enabled ?: existing[category.id]?.enabled ?: 1L,
                itemCount = (counts[category.id] ?: 0).toLong(),
                updatedAt = now,
            )
        }
        val categoryIds = activeCategories.map { it.id }
        if (categoryIds.isNotEmpty()) {
            categoryFilterQueries.deleteMissing(categoryIds)
        } else {
            categoryFilterQueries.deleteAll()
        }
    }

    private fun activeFidRestriction(): Set<Int> {
        val filters = filterQueries.getAll().executeAsList()
        val enabled = filters.filter { it.enabled == 1L }
        return if (filters.isEmpty() || enabled.size == filters.size) {
            emptySet()
        } else {
            enabled.map { it.fid.toInt() }.toSet()
        }
    }

    private fun activeCategoryRestriction(): Set<Long> {
        val filters = categoryFilterQueries.getAll().executeAsList()
        val enabled = filters.filter { it.enabled == 1L }
        return if (filters.isEmpty() || enabled.size == filters.size) {
            emptySet()
        } else {
            enabled.map { it.categoryId }.toSet()
        }
    }

    private fun recordEventPatch(
        syncId: String,
        field: String,
        value: Long,
        mutation: () -> Unit,
    ) {
        recordMutation(
            domain = EVENT_DOMAIN,
            entityId = syncId,
            kind = SyncOperationKind.Patch,
            fields = mapOf(field to value.toString()),
            mutation = mutation,
        )
    }

    private fun recordFilterChoice(
        domain: String,
        entityId: String,
        fields: Map<String, String?>,
        mutation: (String?) -> Unit,
    ) {
        val recorder = mutationRecorder
        if (recorder == null) {
            mutation(null)
        } else {
            recorder.record(
                domain = domain,
                entityId = entityId,
                kind = SyncOperationKind.Put,
                fields = fields,
            ) { operation ->
                mutation(operation?.operationId?.value)
            }
        }
    }

    private fun recordMutation(
        domain: String,
        entityId: String,
        kind: SyncOperationKind,
        fields: Map<String, String?>,
        mutation: () -> Unit,
    ) {
        val recorder = mutationRecorder
        if (recorder == null) {
            mutation()
        } else {
            recorder.record(domain, entityId, kind, fields) { mutation() }
        }
    }

    private fun recordBatch(
        drafts: List<LocalSyncOperationDraft>,
        mutation: () -> Unit,
    ) {
        val recorder = mutationRecorder
        if (recorder == null) {
            db.transaction { mutation() }
        } else {
            recorder.recordBatch(drafts) { mutation() }
        }
    }

    private suspend fun getFavoriteUpdateCandidates(): List<FavoriteStoreRepository.FavoriteItem> =
        localFavoriteRepository.getAllFavoriteItems()
            .filter { it.targetType != FavoriteStoreRepository.FavoriteTargetType.ThreadNormal || it.targetId > 0L }

    private fun persistSnapshot(snapshot: RunSnapshot) {
        runQueries.upsertRun(
            runId = snapshot.runId,
            status = snapshot.status.name,
            phase = snapshot.phase.name,
            startedAt = snapshot.startedAt,
            updatedAt = snapshot.updatedAt,
            finishedAt = snapshot.finishedAt,
            totalCount = snapshot.totalCount.toLong(),
            completedCount = snapshot.completedCount.toLong(),
            skippedCount = snapshot.skippedCount.toLong(),
            failedCount = snapshot.failedCount.toLong(),
            detectedCount = snapshot.detectedCount.toLong(),
            currentItem = snapshot.currentItem,
            logMessage = snapshot.logMessage,
            warningMessage = snapshot.warningMessage,
            errorMessage = snapshot.errorMessage,
        )
    }

    private fun updateSnapshot(snapshot: RunSnapshot): RunSnapshot {
        val updated = snapshot.copy(updatedAt = currentTimeMillis())
        persistSnapshot(updated)
        stateFlow.value = updated.toState()
        return updated
    }

    private fun interruptRun(snapshot: RunSnapshot, reason: String) {
        val latest = runQueries.getByRunId(snapshot.runId).executeAsOneOrNull()?.toSnapshot() ?: snapshot
        if (latest.status != RunStatus.RUNNING) return
        val now = currentTimeMillis()
        val interrupted = latest.copy(
            status = RunStatus.INTERRUPTED,
            phase = RunPhase.INTERRUPTED,
            updatedAt = now,
            errorMessage = reason,
        )
        persistSnapshot(interrupted)
        stateFlow.value = RunState.Interrupted(interrupted)
        interruptRequestedRunIds.remove(latest.runId)
    }

    private fun FavoriteUpdateRun.toSnapshot(): RunSnapshot =
        RunSnapshot(
            runId = runId,
            status = RunStatus.valueOf(status),
            phase = RunPhase.valueOf(phase),
            startedAt = startedAt,
            updatedAt = updatedAt,
            finishedAt = finishedAt,
            totalCount = totalCount.toInt(),
            completedCount = completedCount.toInt(),
            skippedCount = skippedCount.toInt(),
            failedCount = failedCount.toInt(),
            detectedCount = detectedCount.toInt(),
            currentItem = currentItem,
            logMessage = logMessage,
            warningMessage = warningMessage,
            errorMessage = errorMessage,
        )

    private fun RunSnapshot.toState(): RunState = when (status) {
        RunStatus.RUNNING -> RunState.Running(this)
        RunStatus.INTERRUPTED -> RunState.Interrupted(this)
        RunStatus.FAILED -> RunState.Failed(this)
        RunStatus.COMPLETED -> RunState.Completed(this)
        RunStatus.CANCELED -> RunState.Idle
    }

    private fun FavoriteUpdateEvent.toModel(): UpdateEvent =
        UpdateEvent(
            id = id,
            targetType = FavoriteStoreRepository.FavoriteTargetType.fromStorage(targetType),
            targetId = targetId,
            authorId = authorId?.takeIf { it != 0L },
            fid = fid?.toInt(),
            forumName = forumName,
            title = title,
            latestPostTitle = latestPostTitle,
            mode = TargetMode.valueOf(mode),
            summary = summary,
            detailIds = detailIds.csvLongs(),
            coverUrl = coverUrl,
            detectedAt = detectedAt,
            readAt = readAt,
            dismissedAt = dismissedAt,
            ambiguous = ambiguous == 1L,
        )

    private fun FavoriteUpdateFidFilter.toModel(): FidFilter =
        FidFilter(
            fid = fid.toInt(),
            forumName = forumName,
            enabled = enabled == 1L,
            itemCount = itemCount.toInt(),
        )

    private fun FavoriteUpdateCategoryFilter.toModel(): CategoryFilter =
        CategoryFilter(
            categoryId = categoryId,
            categoryName = categoryName,
            enabled = enabled == 1L,
            itemCount = itemCount.toInt(),
        )

    private fun FavoriteStoreRepository.FavoriteItem.targetKey(): String =
        "${targetType.name}:$targetId:${authorId?.value?.toLong() ?: 0L}"

    private fun FavoriteStoreRepository.FavoriteItem.scopeFid(): Int? =
        forumId?.value ?: when (targetType) {
            FavoriteStoreRepository.FavoriteTargetType.TagManga -> TAG_MANGA_SCOPE_FID
            FavoriteStoreRepository.FavoriteTargetType.RssSearch -> RSS_SCOPE_FID
            else -> null
        }

    private fun FavoriteStoreRepository.FavoriteItem.scopeForumName(fid: Int): String =
        when (fid) {
            TAG_MANGA_SCOPE_FID -> i18n("標籤")
            RSS_SCOPE_FID -> i18n("RSS")
            else -> forumName ?: forumId?.let { YamiboForum.toForumName(it) } ?: i18n("版塊 {}", fid)
        }

    private fun UpdateEvent.targetKey(): String =
        "${targetType.name}:$targetId:${authorId ?: 0L}"

    private fun String?.csvLongs(): List<Long> =
        this?.split(",")?.mapNotNull { it.trim().toLongOrNull() }.orEmpty()

    private fun appendLine(existing: String?, line: String): String =
        listOfNotNull(existing, line).joinToString("\n")

    private fun threadSourceDiscriminator(postId: Long, updatedAt: Long?): String =
        if (updatedAt == null) "post:$postId" else "post:$postId:revision:$updatedAt"

    private fun YamiboResult<*>.toCheckFailure(itemTitle: String): CheckResult.Failed =
        CheckResult.Failed(favoriteUpdateFailureReason(itemTitle))

    private fun shouldStop(runId: String): Boolean {
        if (runId in interruptRequestedRunIds) return true
        val snapshot = runQueries.getByRunId(runId).executeAsOneOrNull()?.toSnapshot() ?: return false
        return snapshot.status != RunStatus.RUNNING
    }

    private sealed interface CheckResult {
        data object Skipped : CheckResult
        data class Checked(val detectedCount: Int) : CheckResult
        data class Failed(val reason: String) : CheckResult
    }

    companion object {
        private const val EVENT_DOMAIN = "favorite.update-event"
        private const val FID_FILTER_DOMAIN = "favorite.update-fid-filter"
        private const val CATEGORY_FILTER_DOMAIN = "favorite.update-category-filter"
        private const val MAX_TAG_SCAN_PAGES = 8
        private const val UPDATE_TIME_TOLERANCE_MILLIS = 60_000L
        private const val TAG_MANGA_SCOPE_FID = -100_000
        private const val RSS_SCOPE_FID = -100_001
    }
}

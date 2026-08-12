package me.thenano.yamibo.yamibo_app.repository.favorite

import io.github.littlesurvival.YamiboForum
import io.github.littlesurvival.core.YamiboResult
import io.github.littlesurvival.dto.page.FavoriteType
import io.github.littlesurvival.dto.page.ThreadPage
import io.github.littlesurvival.dto.value.FavoriteId
import io.github.littlesurvival.dto.value.FormHash
import io.github.littlesurvival.dto.value.ThreadId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import me.thenano.yamibo.yamibo_app.Database
import me.thenano.yamibo.yamibo_app.i18n.i18n
import me.thenano.yamibo.yamibo_app.repository.AuthRepository
import me.thenano.yamibo.yamibo_app.repository.FavoriteRepository
import me.thenano.yamibo.yamibo_app.repository.FavoriteSyncRepository
import me.thenano.yamibo.yamibo_app.repository.FavoriteSyncRepository.FavoriteSyncActionResult
import me.thenano.yamibo.yamibo_app.repository.FavoriteSyncRepository.FavoriteSyncBulkDeleteResult
import me.thenano.yamibo.yamibo_app.repository.FavoriteSyncRepository.FavoriteSyncDeleteResult
import me.thenano.yamibo.yamibo_app.repository.FavoriteSyncRepository.FavoriteSyncPhase
import me.thenano.yamibo.yamibo_app.repository.FavoriteSyncRepository.FavoriteSyncSnapshot
import me.thenano.yamibo.yamibo_app.repository.FavoriteSyncRepository.FavoriteSyncState
import me.thenano.yamibo.yamibo_app.repository.FavoriteSyncRepository.FavoriteSyncStatus
import me.thenano.yamibo.yamibo_app.repository.FavoriteStoreRepository
import me.thenano.yamibo.yamibo_app.repository.ThreadRepository
import me.thenano.yamibo.yamibo_app.repository.contentcover.findThreadCoverCandidate
import me.thenano.yamibo.yamibo_app.util.time.currentTimeMillis
import me.thenano.yamibo.yamibo_app.util.time.epochMillisOrNull
import me.thenano.yamibo.yamibo_app.FavoriteSyncTask
import kotlin.random.Random

class FavoriteSyncRepositoryImpl(
    db: Database,
    private val authRepository: AuthRepository,
    private val favoriteRepository: FavoriteRepository,
    private val localFavoriteRepository: FavoriteStoreRepository,
    private val threadRepository: ThreadRepository,
) : FavoriteSyncRepository {
    private val taskQueries = db.favoriteSyncTaskQueries
    private val mappingQueries = db.favoriteSyncRemoteThreadQueries
    private val itemQueries = db.localFavoriteItemQueries
    private val stateFlow = MutableStateFlow<FavoriteSyncState>(FavoriteSyncState.Idle)
    private val interruptRequestedRunIds = linkedSetOf<String>()

    override val state: StateFlow<FavoriteSyncState> = stateFlow.asStateFlow()

    init {
        stateFlow.value = taskQueries
            .getLatestRecoverable()
            .executeAsOneOrNull()
            ?.toSnapshot()
            ?.toState()
            ?: FavoriteSyncState.Idle
    }

    override suspend fun startRemoteImport(targetCategoryId: Long): String {
        val now = currentTimeMillis()
        val runId = "favorite-sync-$now-${Random.nextInt(1000, 9999)}"
        val snapshot = FavoriteSyncSnapshot(
            runId = runId,
            status = FavoriteSyncStatus.RUNNING,
            phase = FavoriteSyncPhase.PREPARING,
            targetCategoryId = targetCategoryId,
            startedAt = now,
            updatedAt = now,
            lastCompletedAt = null,
            elapsedDurationMs = 0L,
            currentPage = 0,
            totalPages = null,
            scannedCount = 0,
            importedCount = 0,
            uploadedCount = 0,
            uploadTargetCount = 0,
            skippedCount = 0,
            failedCount = 0,
            logMessage = i18n("準備同步任務"),
            warningMessage = null,
            errorMessage = null,
        )
        interruptRequestedRunIds.remove(runId)
        persistSnapshot(snapshot)
        stateFlow.value = FavoriteSyncState.Running(snapshot)
        return runId
    }

    override fun observeRun(runId: String): Flow<FavoriteSyncState> {
        return state.map { current ->
            when (current) {
                FavoriteSyncState.Idle -> FavoriteSyncState.Idle
                is FavoriteSyncState.Running ->
                    if (current.snapshot.runId == runId) current else FavoriteSyncState.Idle
                is FavoriteSyncState.Interrupted ->
                    if (current.snapshot.runId == runId) current else FavoriteSyncState.Idle
                is FavoriteSyncState.Failed ->
                    if (current.snapshot.runId == runId) current else FavoriteSyncState.Idle
                is FavoriteSyncState.Completed ->
                    if (current.snapshot.runId == runId) current else FavoriteSyncState.Idle
            }
        }
    }

    override suspend fun resumeInterruptedRun(): String? {
        val latest = getLatestSnapshot() ?: return null
        return when (latest.status) {
            FavoriteSyncStatus.RUNNING -> latest.runId
            FavoriteSyncStatus.INTERRUPTED,
            FavoriteSyncStatus.FAILED,
            FavoriteSyncStatus.COMPLETED -> startRemoteImport(latest.targetCategoryId)
        }
    }

    override suspend fun cancelUiAttachment(runId: String) {
        val current = currentSnapshotOrNull()
        if (current?.runId == runId) {
            stateFlow.value = current.toState()
        }
    }

    override suspend fun interruptRun(runId: String) {
        interruptRequestedRunIds += runId
        val snapshot = taskQueries.getByRunId(runId).executeAsOneOrNull()?.toSnapshot() ?: return
        if (snapshot.status == FavoriteSyncStatus.RUNNING) {
            interruptRun(snapshot, i18n("同步已取消。"))
        }
    }

    override suspend fun markRunInterrupted(runId: String, reason: String) {
        val snapshot = taskQueries.getByRunId(runId).executeAsOneOrNull()?.toSnapshot() ?: return
        if (snapshot.status == FavoriteSyncStatus.RUNNING) {
            interruptRun(snapshot, reason)
        }
    }

    override suspend fun getLatestSnapshot(): FavoriteSyncSnapshot? {
        return taskQueries.getLatestRecoverable().executeAsOneOrNull()?.toSnapshot()
    }

    override suspend fun syncLocalFavoriteItem(itemId: Long): FavoriteSyncActionResult {
        val item = itemQueries.getById(itemId).executeAsOneOrNull()
            ?: return FavoriteSyncActionResult(false, i18n("找不到要同步的收藏。"))
        val threadId = item.asThreadIdOrNull()
            ?: return FavoriteSyncActionResult(false, i18n("這種類型的收藏目前不支援同步到百合會。"))
        val existingMapping = mappingQueries.getByThreadId(threadId.value.toLong()).executeAsOneOrNull()
        val formHash = when (val formHashResult = ensureFormHash()) {
            is YamiboResult.Success -> formHashResult.value
            else -> return FavoriteSyncActionResult(
                false,
                formHashResult.favoriteSyncFailureMessage(favoriteSyncActionFailureMessages()),
            )
        }

        // Persist the remote-sync intent before the request. If cancellation occurs after the
        // server accepts it but before the response is processed, removal must still offer the
        // remote option instead of silently treating the item as local-only.
        if (existingMapping == null) {
            upsertRemoteMapping(
                threadId = threadId,
                remoteFavoriteId = null,
                remoteFavoritedOrder = null,
                itemId = item.id,
            )
        }

        when (val addResult = threadRepository.addFavorite(threadId, formHash)) {
            is YamiboResult.Success -> {
                upsertRemoteMapping(
                    threadId = threadId,
                    remoteFavoriteId = addResult.value.favId,
                    remoteFavoritedOrder = addResult.value.favId?.value?.toLong(),
                    itemId = item.id,
                )
                return if (addResult.value.favId != null) {
                    FavoriteSyncActionResult(true, i18n("已同步至百合會"))
                } else {
                    FavoriteSyncActionResult(true, i18n("百合會已收藏，但未取得收藏識別碼"))
                }
            }
            else -> {
                if (existingMapping == null) mappingQueries.deleteByItemId(item.id)
                return FavoriteSyncActionResult(
                    false,
                    addResult.favoriteSyncFailureMessage(favoriteSyncActionFailureMessages()),
                )
            }
        }
    }

    override suspend fun hasRemoteFavorite(itemId: Long): Boolean {
        return mappingQueries.getByItemId(itemId).executeAsOneOrNull() != null
    }

    override suspend fun getRemoteFavoriteOrderMap(itemIds: Set<Long>): Map<Long, Long> {
        if (itemIds.isEmpty()) return emptyMap()
        return mappingQueries.getAll().executeAsList()
            .mapNotNull { mapping ->
                val itemId = mapping.itemId ?: return@mapNotNull null
                if (itemId !in itemIds) return@mapNotNull null
                val order = mapping.remoteFavoritedOrder ?: mapping.remoteFavoriteId ?: return@mapNotNull null
                itemId to order
            }
            .toMap()
    }

    override suspend fun runImport(runId: String) {
        try {
            runImportUntilComplete(runId)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                markRunInterrupted(runId, i18n("同步已取消。"))
            }
        } finally {
            interruptRequestedRunIds.remove(runId)
        }
    }

    private suspend fun runImportUntilComplete(runId: String) {
        interruptRequestedRunIds.remove(runId)
        val initial = taskQueries.getByRunId(runId).executeAsOneOrNull()?.toSnapshot() ?: return
        if (shouldStop(runId)) {
            interruptRun(initial, i18n("同步已取消。"))
            return
        }

        val category = localFavoriteRepository.getCategories().firstOrNull { it.id == initial.targetCategoryId }
        if (category == null) {
            failRun(initial, i18n("同步目標類別不存在，請重新選擇。"))
            return
        }

        val warnings = linkedSetOf<String>()
        val logs = mutableListOf<String>()

        var current = updateSnapshot(
            initial.copy(
                phase = FavoriteSyncPhase.PREPARING,
                errorMessage = null,
                warningMessage = null,
                logMessage = null,
            ),
            warnings = warnings,
            logs = logs,
        )

        appendLog(logs, i18n("準備同步任務"))
        appendLog(logs, i18n("開始同步"))
        appendLog(logs, i18n("頁數 正在取得收藏頁"))
        current = updateSnapshot(
            current.copy(phase = FavoriteSyncPhase.FETCHING_REMOTE),
            warnings = warnings,
            logs = logs,
        )

        val remoteItems = linkedMapOf<ThreadId, RemoteFavoriteItem>()
        var page = 1
        var totalPages: Int? = null

        while (true) {
            if (shouldStop(runId)) {
                interruptRun(current, i18n("同步已取消。"))
                return
            }

            when (val result = favoriteRepository.fetchFavorites(type = FavoriteType.Thread, page = page)) {
                is YamiboResult.Success -> {
                    val pageTotal = result.value.pageNav?.totalPages
                    if (totalPages != null && pageTotal != null && totalPages != pageTotal) {
                        warnings += truncateFavoriteMessage(i18n("同步期間網站收藏頁數發生變動，建議再同步一次。"))
                    }
                    totalPages = pageTotal ?: totalPages ?: page

                    result.value.items.forEach { remote ->
                        val threadId = remote.toThreadId()
                        if (threadId == null) {
                            warnings += truncateFavoriteMessage(i18n("收藏項目缺少帖子 ID：{}", remote.name))
                            return@forEach
                        }
                        if (remoteItems.containsKey(threadId)) {
                            warnings += truncateFavoriteMessage(i18n("網站收藏列表出現重複帖子：{}", formatPostLabel(threadId, remote.name)))
                        }
                        remoteItems[threadId] = RemoteFavoriteItem(
                            threadId = threadId,
                            favoriteId = remote.favId,
                            title = remote.name,
                        )
                    }

                    appendLog(logs, i18n("頁數 {}/{} 頁", page, totalPages))
                    appendLog(logs, i18n("已取得 {} 項收藏", remoteItems.size))
                    current = updateSnapshot(
                        current.copy(
                            phase = FavoriteSyncPhase.FETCHING_REMOTE,
                            currentPage = page,
                            totalPages = totalPages,
                            scannedCount = remoteItems.size,
                        ),
                        warnings = warnings,
                        logs = logs,
                    )

                    val reachedLastPage = page >= totalPages
                    val hasMoreByNav = result.value.pageNav?.nextUrl != null
                    if (reachedLastPage || (!hasMoreByNav && result.value.items.isEmpty())) {
                        break
                    }
                    page += 1
                }

                is YamiboResult.NotLoggedIn -> {
                    failRun(current.copy(failedCount = current.failedCount + 1), i18n("目前未登入百合會，請重新登入後再同步。"))
                    return
                }

                is YamiboResult.NoPermission -> {
                    failRun(current.copy(failedCount = current.failedCount + 1), result.reason)
                    return
                }

                is YamiboResult.Maintenance -> {
                    failRun(current.copy(failedCount = current.failedCount + 1), i18n("百合會目前維護中，請稍後再試。"))
                    return
                }

                is YamiboResult.WafChallenge -> {
                    interruptRun(current, result.message())
                    return
                }

                is YamiboResult.Failure -> {
                    failRun(current.copy(failedCount = current.failedCount + 1), truncateFavoriteMessage(result.reason))
                    return
                }
            }
        }

        if (shouldStop(runId)) {
            interruptRun(current, i18n("同步已取消。"))
            return
        }

        appendLog(logs, i18n("匯入網站帖子"))
        current = updateSnapshot(
            current.copy(
                phase = FavoriteSyncPhase.IMPORTING_REMOTE,
                scannedCount = remoteItems.size,
            ),
            warnings = warnings,
            logs = logs,
        )

        val duplicateSyncedPathCounts = linkedMapOf<String, Int>()
        val remoteThreadIds = remoteItems.keys.toSet()

        for (remoteItem in remoteItems.values) {
            if (shouldStop(runId)) {
                interruptRun(current, i18n("同步已取消。"))
                return
            }
            appendLog(
                logs,
                i18n("[{}/{}] 已載入 #{} {}", current.importedCount + current.failedCount + 1, remoteItems.size, remoteItem.threadId.value, remoteItem.title)
            )
            current = updateSnapshot(current, warnings = warnings, logs = logs)

            val existingItem =
                itemQueries.findAnyThreadByTargetId(remoteItem.threadId.value.toLong()).executeAsOneOrNull()
            val existingMapping = mappingQueries.getByThreadId(remoteItem.threadId.value.toLong()).executeAsOneOrNull()

            val itemId = when {
                existingItem != null && existingMapping?.itemId != null -> {
                    val path = localFavoriteRepository
                        .getFavoritePaths(existingItem.id)
                        .firstOrNull()
                        ?: category.name
                    duplicateSyncedPathCounts[path] = (duplicateSyncedPathCounts[path] ?: 0) + 1
                    current = updateSnapshot(
                        current.copy(importedCount = current.importedCount + 1),
                        warnings = warnings,
                        logs = logs,
                    )
                    existingItem.id
                }

                existingItem != null -> {
                    localFavoriteRepository.addItemsToLocations(
                        itemIds = setOf(existingItem.id),
                        categoryIds = setOf(current.targetCategoryId),
                    )
                    current = updateSnapshot(
                        current.copy(importedCount = current.importedCount + 1),
                        warnings = warnings,
                        logs = logs,
                    )
                    existingItem.id
                }

                else -> {
                    when (val threadResult = fetchThreadSummary(remoteItem.threadId)) {
                        is YamiboResult.Success -> {
                            persistRemoteThreadIntoLocal(threadResult.value, current.targetCategoryId)
                            current = updateSnapshot(
                                current.copy(importedCount = current.importedCount + 1),
                                warnings = warnings,
                                logs = logs,
                            )
                            itemQueries.findAnyThreadByTargetId(remoteItem.threadId.value.toLong())
                                .executeAsOneOrNull()
                                ?.id
                        }

                        is YamiboResult.NotLoggedIn -> {
                            failRun(current.copy(failedCount = current.failedCount + 1), i18n("目前未登入百合會，請重新登入後再同步。"))
                            return
                        }

                        is YamiboResult.NoPermission -> {
                            warnings += importFailureMessage(remoteItem, threadResult.reason)
                            current = updateSnapshot(
                                current.copy(failedCount = current.failedCount + 1),
                                warnings = warnings,
                                logs = logs,
                            )
                            null
                        }

                        is YamiboResult.Maintenance -> {
                            failRun(current.copy(failedCount = current.failedCount + 1), i18n("百合會目前維護中，請稍後再試。"))
                            return
                        }

                        is YamiboResult.WafChallenge -> {
                            interruptRun(current, threadResult.message())
                            return
                        }

                        is YamiboResult.Failure -> {
                            warnings += importFailureMessage(remoteItem, threadResult.reason)
                            current = updateSnapshot(
                                current.copy(failedCount = current.failedCount + 1),
                                warnings = warnings,
                                logs = logs,
                            )
                            null
                        }
                    }
                }
            }

            upsertRemoteMapping(
                threadId = remoteItem.threadId,
                remoteFavoriteId = remoteItem.favoriteId,
                remoteFavoritedOrder = remoteItem.favoriteId.value.toLong(),
                itemId = itemId,
            )
        }

        duplicateSyncedPathCounts.forEach { (path, count) ->
            appendLog(logs, i18n("有 {} 項收藏已曾同步過至 {}，不進行重複同步", count, path))
        }
        current = updateSnapshot(current, warnings = warnings, logs = logs)
        if (shouldStop(runId)) {
            interruptRun(current, i18n("同步已取消。"))
            return
        }

        val formHash = when (val formHashResult = ensureFormHash()) {
            is YamiboResult.Success -> formHashResult.value
            is YamiboResult.NotLoggedIn -> {
                failRun(current, i18n("目前未登入百合會，請重新登入後再同步。"))
                return
            }

            is YamiboResult.NoPermission -> {
                failRun(current, formHashResult.reason)
                return
            }

            is YamiboResult.Maintenance -> {
                failRun(current, i18n("百合會目前維護中，請稍後再試。"))
                return
            }

            is YamiboResult.WafChallenge -> {
                interruptRun(current, formHashResult.message())
                return
            }

            is YamiboResult.Failure -> {
                failRun(current, truncateFavoriteMessage(formHashResult.reason))
                return
            }
        }

        current = updateSnapshot(
            current.copy(phase = FavoriteSyncPhase.UPLOADING_LOCAL),
            warnings = warnings,
            logs = logs,
        )

        val categoryThreadItems = collectCategoryThreadItems(current.targetCategoryId)
        val uploadTargets = categoryThreadItems.filter { ThreadId(it.targetId.toInt()) !in remoteThreadIds }

        current = updateSnapshot(
            current.copy(uploadTargetCount = uploadTargets.size),
            warnings = warnings,
            logs = logs,
        )

        for ((_, _, targetId, title) in uploadTargets) {
            if (shouldStop(runId)) {
                interruptRun(current, i18n("同步已取消。"))
                return
            }

            val threadId = ThreadId(targetId.toInt())
            when (val addResult = threadRepository.addFavorite(threadId, formHash)) {
                is YamiboResult.Success -> {
                    current = updateSnapshot(
                        current.copy(uploadedCount = current.uploadedCount + 1),
                        warnings = warnings,
                        logs = logs,
                    )
                }

                is YamiboResult.NotLoggedIn -> {
                    failRun(current, i18n("目前未登入百合會，請重新登入後再同步。"))
                    return
                }

                is YamiboResult.NoPermission -> {
                    warnings += uploadFailureMessage(title, threadId, addResult.reason)
                    current = updateSnapshot(
                        current.copy(failedCount = current.failedCount + 1),
                        warnings = warnings,
                        logs = logs,
                    )
                }

                is YamiboResult.Maintenance -> {
                    failRun(current, i18n("百合會目前維護中，請稍後再試。"))
                    return
                }

                is YamiboResult.WafChallenge -> {
                    interruptRun(current, addResult.message())
                    return
                }

                is YamiboResult.Failure -> {
                    warnings += uploadFailureMessage(title, threadId, addResult.reason)
                    current = updateSnapshot(
                        current.copy(failedCount = current.failedCount + 1),
                        warnings = warnings,
                        logs = logs,
                    )
                }
            }
        }

        if (shouldStop(runId)) {
            interruptRun(current, i18n("同步已取消。"))
            return
        }

        if (current.uploadedCount > 0) {
            when (val reconcile = fetchRemoteFavoritesSilently()) {
                is RemoteFetchResult.Success -> {
                    reconcile.items.values.forEach { remoteItem ->
                        val localItem =
                            itemQueries.findAnyThreadByTargetId(remoteItem.threadId.value.toLong()).executeAsOneOrNull()
                        upsertRemoteMapping(
                            threadId = remoteItem.threadId,
                            remoteFavoriteId = remoteItem.favoriteId,
                            remoteFavoritedOrder = remoteItem.favoriteId.value.toLong(),
                            itemId = localItem?.id,
                        )
                    }
                }

                is RemoteFetchResult.Failure -> {
                    warnings += truncateFavoriteMessage(i18n("重新對齊網站收藏資料失敗：{}", reconcile.reason))
                }
            }
        }

        if (shouldStop(runId)) {
            interruptRun(current, i18n("同步已取消。"))
            return
        }

        val completedAt = currentTimeMillis()
        val completed = current.copy(
            status = FavoriteSyncStatus.COMPLETED,
            phase = FavoriteSyncPhase.COMPLETED,
            updatedAt = completedAt,
            lastCompletedAt = completedAt,
            elapsedDurationMs = (completedAt - current.startedAt).coerceAtLeast(0L),
            warningMessage = warnings.takeIf { it.isNotEmpty() }?.joinToString("\n"),
            logMessage = logs.takeIf { it.isNotEmpty() }?.joinToString("\n"),
            errorMessage = null,
        )
        persistSnapshot(completed)
        stateFlow.value = FavoriteSyncState.Completed(completed)
    }

    override suspend fun removeLocalFavoriteItem(itemId: Long, removeRemote: Boolean): FavoriteSyncDeleteResult {
        val item = itemQueries.getById(itemId).executeAsOneOrNull()
            ?: return FavoriteSyncDeleteResult(success = true)
        val mapping = mappingQueries.getByItemId(itemId).executeAsOneOrNull()

        if (removeRemote && mapping != null) {
            val remoteFavoriteId = mapping.remoteFavoriteId?.let { FavoriteId(it.toInt()) }
                ?: return FavoriteSyncDeleteResult(
                    false,
                    i18n("百合會收藏識別碼不可用。請到收藏頁使用「同步百合會收藏」重新對齊識別碼後再試。"),
                )

            val formHash = when (val formHashResult = ensureFormHash()) {
                is YamiboResult.Success -> formHashResult.value
                else -> return FavoriteSyncDeleteResult(
                    false,
                    formHashResult.favoriteSyncFailureMessage(favoriteSyncDeleteFailureMessages()),
                )
            }

            when (val remoteResult = favoriteRepository.removeFavorite(remoteFavoriteId, formHash)) {
                is YamiboResult.Success -> Unit
                else -> return FavoriteSyncDeleteResult(
                    false,
                    remoteResult.favoriteSyncFailureMessage(favoriteSyncDeleteFailureMessages()),
                )
            }
        }

        localFavoriteRepository.deleteFavoriteItems(setOf(item.id))
        if (!removeRemote && mapping?.remoteFavoriteId != null) {
            mappingQueries.upsertMapping(
                threadId = mapping.threadId,
                remoteFavoriteId = mapping.remoteFavoriteId,
                remoteFavoritedOrder = mapping.remoteFavoritedOrder,
                itemId = null,
                lastSeenAt = mapping.lastSeenAt,
                lastSyncedAt = currentTimeMillis(),
            )
        } else {
            mappingQueries.deleteByItemId(item.id)
        }
        return FavoriteSyncDeleteResult(success = true)
    }

    override suspend fun removeLocalFavoriteItems(
        itemIds: Set<Long>,
        removeRemote: Boolean,
    ): FavoriteSyncBulkDeleteResult {
        var deletedCount = 0
        val messages = mutableListOf<String>()
        for (itemId in itemIds) {
            val result = removeLocalFavoriteItem(itemId, removeRemote = removeRemote)
            if (result.success) {
                deletedCount += 1
            } else {
                messages += result.message ?: i18n("刪除失敗")
            }
        }
        return FavoriteSyncBulkDeleteResult(
            deletedCount = deletedCount,
            failedCount = itemIds.size - deletedCount,
            messages = messages,
        )
    }

    private suspend fun persistRemoteThreadIntoLocal(threadPage: ThreadPage, categoryId: Long) {
        val isNovel = YamiboForum.isNovelForum(threadPage.thread.forum.fid)
        val authorId = threadPage.posts.firstOrNull()?.author?.uid
        val coverUrl = findThreadCoverCandidate(threadPage)
        val lastUpdatedTime = extractLastUpdatedTime(threadPage)
        if (isNovel) {
            localFavoriteRepository.addNovelThreadFavorite(
                tid = threadPage.thread.tid,
                title = threadPage.thread.title,
                authorId = authorId,
                coverUrl = coverUrl,
                lastUpdatedTime = lastUpdatedTime,
                forumId = threadPage.thread.forum.fid,
                forumName = threadPage.thread.forum.name,
                categoryIds = listOf(categoryId),
            )
        } else {
            localFavoriteRepository.addNormalThreadFavorite(
                tid = threadPage.thread.tid,
                title = threadPage.thread.title,
                coverUrl = coverUrl,
                lastUpdatedTime = lastUpdatedTime,
                forumId = threadPage.thread.forum.fid,
                forumName = threadPage.thread.forum.name,
                categoryIds = listOf(categoryId),
            )
        }
    }

    private suspend fun collectCategoryThreadItems(categoryId: Long): List<FavoriteStoreRepository.FavoriteItem> {
        val content = localFavoriteRepository.getCategoryContent(categoryId)
        return buildList {
            addAll(content.directItems)
            content.collections.forEach { addAll(it.items) }
        }
            .distinctBy { it.id }
            .filter {
                it.targetType == FavoriteStoreRepository.FavoriteTargetType.ThreadNormal ||
                    it.targetType == FavoriteStoreRepository.FavoriteTargetType.ThreadNovel
            }
    }

    private suspend fun ensureFormHash(): YamiboResult<FormHash> {
        authRepository.currentUser()?.formHash?.let { return YamiboResult.Success(it) }
        return when (val authResult = authRepository.fetchStatus()) {
            is YamiboResult.Success -> {
                authRepository.currentUser()?.formHash?.let { YamiboResult.Success(it) }
                    ?: YamiboResult.Failure(i18n("登入狀態已更新，但仍無法取得 formHash。"))
            }

            is YamiboResult.NotLoggedIn,
            is YamiboResult.NoPermission,
            is YamiboResult.Maintenance,
            is YamiboResult.WafChallenge,
            is YamiboResult.Failure -> authResult
        }
    }

    private fun extractLastUpdatedTime(threadPage: ThreadPage): Long? {
        val firstPost = threadPage.posts.firstOrNull { it.floor == 1 } ?: threadPage.posts.firstOrNull()
        return firstPost?.lastEditedTime?.epochMillisOrNull() ?: firstPost?.timeCreate?.epochMillisOrNull()
    }

    private suspend fun fetchThreadSummary(threadId: ThreadId): YamiboResult<ThreadPage> {
        repeat(2) { attempt ->
            val result = threadRepository.fetchThread(threadId)
            if (result !is YamiboResult.Failure || attempt == 1) {
                return result
            }
        }
        return threadRepository.fetchThread(threadId)
    }

    private suspend fun fetchRemoteFavoritesSilently(): RemoteFetchResult {
        val items = linkedMapOf<ThreadId, RemoteFavoriteItem>()
        var page = 1
        var totalPages: Int? = null

        while (true) {
            when (val result = favoriteRepository.fetchFavorites(type = FavoriteType.Thread, page = page)) {
                is YamiboResult.Success -> {
                    totalPages = result.value.pageNav?.totalPages ?: totalPages ?: page
                    result.value.items.forEach { remote ->
                        val threadId = remote.toThreadId() ?: return@forEach
                        items[threadId] = RemoteFavoriteItem(
                            threadId = threadId,
                            favoriteId = remote.favId,
                            title = remote.name,
                        )
                    }

                    val reachedLastPage = page >= totalPages
                    val hasMoreByNav = result.value.pageNav?.nextUrl != null
                    if (reachedLastPage || (!hasMoreByNav && result.value.items.isEmpty())) {
                        return RemoteFetchResult.Success(items, page, totalPages)
                    }
                    page += 1
                }
                else -> return RemoteFetchResult.Failure(i18n(result.message()))
            }
        }
    }

    private fun upsertRemoteMapping(
        threadId: ThreadId,
        remoteFavoriteId: FavoriteId?,
        remoteFavoritedOrder: Long? = remoteFavoriteId?.value?.toLong(),
        itemId: Long?,
    ) {
        val now = currentTimeMillis()
        mappingQueries.upsertMapping(
            threadId = threadId.value.toLong(),
            remoteFavoriteId = remoteFavoriteId?.value?.toLong(),
            remoteFavoritedOrder = remoteFavoritedOrder,
            itemId = itemId,
            lastSeenAt = now,
            lastSyncedAt = now,
        )
    }

    private fun appendLog(logs: MutableList<String>, message: String) {
        logs += truncateFavoriteMessage(message)
    }

    private fun formatPostLabel(threadId: ThreadId, title: String): String {
        return "#${threadId.value} ${title.ifBlank { i18n("未命名帖子") }}"
    }

    private fun importFailureMessage(
        remoteItem: RemoteFavoriteItem,
        reason: String,
    ): String {
        return truncateFavoriteMessage(i18n("無法匯入帖子 {}：{}", formatPostLabel(remoteItem.threadId, remoteItem.title), reason))
    }

    private fun uploadFailureMessage(
        title: String,
        threadId: ThreadId,
        reason: String,
    ): String {
        return truncateFavoriteMessage(i18n("無法同步到百合會 {}：{}", formatPostLabel(threadId, title), reason))
    }

    private fun shouldStop(runId: String): Boolean {
        if (runId in interruptRequestedRunIds) return true
        val snapshot = taskQueries.getByRunId(runId).executeAsOneOrNull()?.toSnapshot() ?: return false
        return snapshot.status != FavoriteSyncStatus.RUNNING
    }

    private fun currentSnapshotOrNull(): FavoriteSyncSnapshot? {
        return when (val current = stateFlow.value) {
            FavoriteSyncState.Idle -> null
            is FavoriteSyncState.Running -> current.snapshot
            is FavoriteSyncState.Interrupted -> current.snapshot
            is FavoriteSyncState.Failed -> current.snapshot
            is FavoriteSyncState.Completed -> current.snapshot
        }
    }

    private fun updateSnapshot(
        snapshot: FavoriteSyncSnapshot,
        warnings: Set<String>? = null,
        logs: List<String>? = null,
    ): FavoriteSyncSnapshot {
        val now = currentTimeMillis()
        val updated = snapshot.copy(
            updatedAt = now,
            elapsedDurationMs = (now - snapshot.startedAt).coerceAtLeast(0L),
            warningMessage = warnings?.takeIf { it.isNotEmpty() }?.joinToString("\n") ?: snapshot.warningMessage,
            logMessage = logs?.takeIf { it.isNotEmpty() }?.joinToString("\n") ?: snapshot.logMessage,
        )
        persistSnapshot(updated)
        stateFlow.value = updated.toState()
        return updated
    }

    private fun interruptRun(snapshot: FavoriteSyncSnapshot, reason: String) {
        val now = currentTimeMillis()
        val interrupted = snapshot.copy(
            status = FavoriteSyncStatus.INTERRUPTED,
            phase = FavoriteSyncPhase.INTERRUPTED,
            updatedAt = now,
            elapsedDurationMs = (now - snapshot.startedAt).coerceAtLeast(0L),
            errorMessage = reason,
        )
        persistSnapshot(interrupted)
        stateFlow.value = FavoriteSyncState.Interrupted(interrupted)
    }

    private fun failRun(snapshot: FavoriteSyncSnapshot, reason: String) {
        val now = currentTimeMillis()
        val failed = snapshot.copy(
            status = FavoriteSyncStatus.FAILED,
            phase = FavoriteSyncPhase.FAILED,
            updatedAt = now,
            elapsedDurationMs = (now - snapshot.startedAt).coerceAtLeast(0L),
            errorMessage = reason,
        )
        persistSnapshot(failed)
        stateFlow.value = FavoriteSyncState.Failed(failed)
    }

    private fun persistSnapshot(snapshot: FavoriteSyncSnapshot) {
        taskQueries.insertOrReplace(
            runId = snapshot.runId,
            status = snapshot.status.name,
            targetCategoryId = snapshot.targetCategoryId,
            startedAt = snapshot.startedAt,
            updatedAt = snapshot.updatedAt,
            lastCompletedAt = snapshot.lastCompletedAt,
            elapsedDurationMs = snapshot.elapsedDurationMs,
            phase = snapshot.phase.name,
            currentPage = snapshot.currentPage.toLong(),
            totalPages = snapshot.totalPages?.toLong(),
            scannedCount = snapshot.scannedCount.toLong(),
            importedCount = snapshot.importedCount.toLong(),
            uploadedCount = snapshot.uploadedCount.toLong(),
            uploadTargetCount = snapshot.uploadTargetCount.toLong(),
            skippedCount = snapshot.skippedCount.toLong(),
            failedCount = snapshot.failedCount.toLong(),
            logMessage = snapshot.logMessage,
            warningMessage = snapshot.warningMessage,
            errorMessage = snapshot.errorMessage,
        )
    }

    private fun FavoriteSyncTask.toSnapshot(): FavoriteSyncSnapshot {
        return FavoriteSyncSnapshot(
            runId = runId,
            status = FavoriteSyncStatus.valueOf(status),
            phase = FavoriteSyncPhase.valueOf(phase),
            targetCategoryId = targetCategoryId,
            startedAt = startedAt,
            updatedAt = updatedAt,
            lastCompletedAt = lastCompletedAt,
            elapsedDurationMs = elapsedDurationMs,
            currentPage = currentPage.toInt(),
            totalPages = totalPages?.toInt(),
            scannedCount = scannedCount.toInt(),
            importedCount = importedCount.toInt(),
            uploadedCount = uploadedCount.toInt(),
            uploadTargetCount = uploadTargetCount.toInt(),
            skippedCount = skippedCount.toInt(),
            failedCount = failedCount.toInt(),
            logMessage = logMessage,
            warningMessage = warningMessage,
            errorMessage = errorMessage,
        )
    }

    private fun FavoriteSyncSnapshot.toState(): FavoriteSyncState {
        return when (status) {
            FavoriteSyncStatus.RUNNING -> FavoriteSyncState.Running(this)
            FavoriteSyncStatus.INTERRUPTED -> FavoriteSyncState.Interrupted(this)
            FavoriteSyncStatus.FAILED -> FavoriteSyncState.Failed(this)
            FavoriteSyncStatus.COMPLETED -> FavoriteSyncState.Completed(this)
        }
    }

    private fun me.thenano.yamibo.yamibo_app.LocalFavoriteItem.asThreadIdOrNull(): ThreadId? {
        return when (FavoriteStoreRepository.FavoriteTargetType.fromStorage(targetType)) {
            FavoriteStoreRepository.FavoriteTargetType.ThreadNormal,
            FavoriteStoreRepository.FavoriteTargetType.ThreadNovel -> ThreadId(targetId.toInt())
            FavoriteStoreRepository.FavoriteTargetType.TagManga,
            FavoriteStoreRepository.FavoriteTargetType.RssSearch -> null
        }
    }

    private sealed interface RemoteFetchResult {
        data class Success(
            val items: LinkedHashMap<ThreadId, RemoteFavoriteItem>,
            val currentPage: Int,
            val totalPages: Int?,
        ) : RemoteFetchResult

        data class Failure(val reason: String) : RemoteFetchResult
    }

    private data class RemoteFavoriteItem(
        val threadId: ThreadId,
        val favoriteId: FavoriteId,
        val title: String,
    )
}

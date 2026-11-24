package me.thenano.yamibo.yamibo_app.profile.download

import me.thenano.yamibo.yamibo_app.components.feedback.forumErrorMessage
import me.thenano.yamibo.yamibo_app.LocalForumErrorMessageResolver
import YamiboIcons
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyColumn
import me.thenano.yamibo.yamibo_app.repository.download.sourceKeys
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.littlesurvival.core.YamiboResult
import io.github.littlesurvival.dto.value.ThreadId
import io.github.littlesurvival.dto.value.UserId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.thenano.yamibo.yamibo_app.LocalAppSettingsRepository
import me.thenano.yamibo.yamibo_app.LocalAppTaskManager
import me.thenano.yamibo.yamibo_app.LocalAuthRepository
import me.thenano.yamibo.yamibo_app.LocalDownloadRepository
import me.thenano.yamibo.yamibo_app.Logger
import me.thenano.yamibo.yamibo_app.components.controls.YamiboActionChip
import me.thenano.yamibo.yamibo_app.components.controls.YamiboMultiSelectDialog
import me.thenano.yamibo.yamibo_app.components.controls.YamiboSingleSelectDialog
import me.thenano.yamibo.yamibo_app.components.navigation.YamiboScrollableTabRow
import me.thenano.yamibo.yamibo_app.components.navigation.YamiboTopBar
import me.thenano.yamibo.yamibo_app.components.storage.YamiboStorageUsageOverview
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme
import me.thenano.yamibo.yamibo_app.core.cache.CacheStorageUsage
import me.thenano.yamibo.yamibo_app.i18n.i18n
import me.thenano.yamibo.yamibo_app.navigation.LocalNavigator
import me.thenano.yamibo.yamibo_app.navigation.ScreenId
import me.thenano.yamibo.yamibo_app.profile.settings.ISettingsCategoryScreen
import me.thenano.yamibo.yamibo_app.repository.DownloadRepository
import me.thenano.yamibo.yamibo_app.repository.download.*
import me.thenano.yamibo.yamibo_app.util.formatDownloadedByteSize
import me.thenano.yamibo.yamibo_app.task.AppTaskKey
import me.thenano.yamibo.yamibo_app.task.isActive
import me.thenano.yamibo.yamibo_app.util.state

private enum class DownloadManagerTab(val title: String) {
    Queue("下載佇列"),
    Content("下載內容管理"),
}

private sealed interface DownloadContentManagementState {
    data object Loading : DownloadContentManagementState
    data class Ready(
        val summary: DownloadedContentSummary,
        val groups: List<DownloadedContentGroup>,
    ) : DownloadContentManagementState
    data class Error(val message: String) : DownloadContentManagementState
}

private data class DownloadRemovalTarget(val key: DownloadTaskKey, val title: String, val imageBytes: Long = 0)
private data class DownloadRemovalRequest(val tasksOnly: Boolean, val targets: List<DownloadRemovalTarget>)
private data class DownloadRemovalOutcome(val target: DownloadRemovalTarget, val error: String? = null)
private data class DownloadRemovalProgress(
    val request: DownloadRemovalRequest? = null,
    val processed: Int = 0,
    val outcomes: List<DownloadRemovalOutcome> = emptyList(),
)
// Retain the current batch result when navigating away or changing app language.
private val downloadRemovalProgress = MutableStateFlow(DownloadRemovalProgress())
private val downloadRemovalTaskKey = AppTaskKey("download-control:batch-remove")

private fun DownloadStatus.canRemoveTask(): Boolean =
    this == DownloadStatus.Queued || this == DownloadStatus.Downloading || this == DownloadStatus.Paused || this == DownloadStatus.Failed

@Composable
fun DownloadQueueScreen(screenId: ScreenId) {
    val errorMessages = LocalForumErrorMessageResolver.current
    val colors = YamiboTheme.colors
    val navigator = LocalNavigator.current
    val repository = LocalDownloadRepository.current
    val appSettingsRepository = LocalAppSettingsRepository.current
    val downloadedContentRefreshAutoUpdate = appSettingsRepository.downloadedContentRefreshAutoUpdate.state()
    val storageFolderUri = appSettingsRepository.backupFolderUri.state()
    val queue by repository.queue.collectAsState()
    val owner = LocalAuthRepository.current.currentUser()?.uid?.value ?: 0
    val entries = remember(queue, owner) { queue.filter { it.owner == null || it.owner == owner } }
    val feedbackController = me.thenano.yamibo.yamibo_app.LocalAppFeedbackController.current
    val appTaskManager = LocalAppTaskManager.current
    val scope = rememberCoroutineScope()
    var summary by remember { mutableStateOf(DownloadQueueSummary()) }
    var folderReady by remember { mutableStateOf(true) }
    var selectedTab by remember { mutableStateOf(DownloadManagerTab.Queue) }
    var contentState by remember { mutableStateOf<DownloadContentManagementState>(DownloadContentManagementState.Loading) }
    var contentReloadToken by remember { mutableStateOf(0) }
    var catalogUpdateToken by remember { mutableStateOf(0) }
    var lastContentReloadToken by remember { mutableStateOf(0) }
    var contentSortMode by rememberSaveable { mutableStateOf(DownloadedContentSortMode.DownloadedAt) }
    var contentSortDescending by rememberSaveable { mutableStateOf(true) }
    var selectedContentFilterKeys by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val queueStatuses = remember(entries) { entries.map { it.key to it.status } }
    val contentRevision = remember(entries) { downloadedContentRevision(entries) }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedKeys by remember { mutableStateOf(emptySet<DownloadTaskKey>()) }
    var confirmMigration by remember { mutableStateOf(false) }
    var migrationProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var migrationError by remember { mutableStateOf<String?>(null) }
    var pendingRemoval by remember { mutableStateOf<DownloadRemovalRequest?>(null) }
    val removalProgress by downloadRemovalProgress.collectAsState()
    val lastRemoval = removalProgress.request
    val outcomes = removalProgress.outcomes
    val failures = remember(outcomes) { outcomes.filter { it.error != null } }
    val progress = removalProgress.processed
    var showFailureDetails by remember { mutableStateOf(false) }
    val appTasks by appTaskManager.tasks.collectAsState()
    val busy = appTasks[downloadRemovalTaskKey]?.isActive == true || migrationProgress != null
    val contentGroups = (contentState as? DownloadContentManagementState.Ready)?.groups.orEmpty()
    val filterOptions = remember(contentGroups) { buildDownloadedContentFilterOptions(contentGroups) }
    val normalizedFilterKeys = selectedContentFilterKeys.filterTo(mutableSetOf()) { key ->
        key != DOWNLOADED_CONTENT_FILTER_ALL && filterOptions.any { it.key == key }
    }
    val selectableTargets = remember(entries, selectedTab, contentGroups, normalizedFilterKeys, contentSortMode, contentSortDescending) {
        if (selectedTab == DownloadManagerTab.Queue) {
            entries.filter { it.status.canRemoveTask() }.map { DownloadRemovalTarget(it.key, it.title) }
        } else {
            filterAndSortDownloadedContentGroups(contentGroups, normalizedFilterKeys, contentSortMode, contentSortDescending)
                .flatMap { it.items }.distinctBy { it.key }
                .flatMap { item -> item.sourceKeys.map { DownloadRemovalTarget(it, item.title) } }
        }
    }
    val selectableKeys = selectableTargets.mapTo(mutableSetOf()) { it.key }
    LaunchedEffect(selectableKeys) { selectedKeys = selectedKeys.intersect(selectableKeys) }

    fun leaveSelection(): Boolean {
        if (!selectionMode) return false
        selectionMode = false
        selectedKeys = emptySet()
        return true
    }
    DisposableEffect(navigator, screenId) {
        val handler = { navigator.currentScreen.id == screenId && leaveSelection() }
        navigator.backHandlers.add(handler)
        onDispose { navigator.backHandlers.remove(handler) }
    }
    fun toggleKeys(keys: Set<DownloadTaskKey>) {
        if (busy || keys.isEmpty()) return
        selectionMode = true
        selectedKeys = if (selectedKeys.containsAll(keys)) selectedKeys - keys else selectedKeys + keys
    }
    fun requestRemoval(tasksOnly: Boolean, targets: List<DownloadRemovalTarget>) {
        if (!busy && targets.isNotEmpty()) pendingRemoval = DownloadRemovalRequest(tasksOnly, targets.distinctBy { it.key })
    }
    fun executeRemoval(request: DownloadRemovalRequest) {
        if (appTaskManager.state(downloadRemovalTaskKey)?.isActive == true) return
        pendingRemoval = null
        downloadRemovalProgress.value = DownloadRemovalProgress(request)
        appTaskManager.launch(downloadRemovalTaskKey) {
            try {
                val keys = request.targets.mapTo(mutableSetOf()) { it.key }
                val errors = repository.removeItems(keys, request.tasksOnly) { done ->
                    downloadRemovalProgress.update { it.copy(processed = done) }
                }
                downloadRemovalProgress.update { state ->
                    state.copy(processed = keys.size, outcomes = request.targets.map { target -> DownloadRemovalOutcome(target, errors[target.key]?.let { it.message ?: "清除失敗" }) })
                }
                selectedKeys -= keys - errors.keys
                val results = downloadRemovalProgress.value.outcomes
                val failed = results.count { it.error != null }
                feedbackController.post(i18n("成功 {} 項，失敗 {} 項", results.size - failed, failed))
            } finally {
                if (!request.tasksOnly) catalogUpdateToken++
            }
        }
    }


    suspend fun reloadContentManagement(forceRefresh: Boolean) {
        if (contentState !is DownloadContentManagementState.Ready) {
            contentState = DownloadContentManagementState.Loading
        }
        contentState = try {
            withContext(Dispatchers.Default) {
                val groups = repository.getDownloadedContentGroups(forceRefresh)
                DownloadContentManagementState.Ready(
                    summary = summarizeDownloadedContent(groups),
                    groups = groups,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Logger.e("DownloadQueueScreen", "Failed to load downloaded content management", error)
            DownloadContentManagementState.Error(error.message ?: i18n("載入下載內容失敗"))
        }
    }

    LaunchedEffect(selectedTab, queueStatuses, contentReloadToken, storageFolderUri) {
        if (selectedTab == DownloadManagerTab.Queue) {
            summary = repository.getSummary()
            folderReady = repository.isStorageReady()
        }
    }

    LaunchedEffect(selectedTab, contentRevision, contentReloadToken, catalogUpdateToken, storageFolderUri) {
        if (selectedTab == DownloadManagerTab.Content) {
            val forceRefresh = lastContentReloadToken != contentReloadToken
            reloadContentManagement(forceRefresh)
            lastContentReloadToken = contentReloadToken
        }
    }

    Scaffold(
        topBar = {
            YamiboTopBar(
                title = if (selectionMode) i18n("已選取 {} 項", selectedKeys.size) else i18n("下載管理"),
                onBack = { if (!leaveSelection()) navigator.pop() },
            )
        },
        containerColor = colors.creamBackground,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(colors.creamBackground),
        ) {
            DownloadManagerTabRow(selectedTab) {
                if (!busy && it != selectedTab) {
                    leaveSelection()
                    selectedTab = it
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                if (selectionMode) {
                    FlowRow(
                        modifier = Modifier.padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        itemVerticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (selectedTab == DownloadManagerTab.Queue) {
                            YamiboActionChip(i18n("全選"), { selectedKeys = selectableKeys }, enabled = !busy && selectedKeys != selectableKeys)
                            YamiboActionChip(i18n("取消"), { leaveSelection() }, enabled = !busy)
                            YamiboActionChip(i18n("全不選"), { selectedKeys = emptySet() }, enabled = !busy && selectedKeys.isNotEmpty())
                            YamiboActionChip(i18n("反選"), { selectedKeys = selectableKeys - selectedKeys }, enabled = !busy && selectableKeys.isNotEmpty())
                        }
                        SmallQueueButton(
                            text = if (selectedTab == DownloadManagerTab.Queue) i18n("移除任務") else i18n("刪除下載內容"),
                            onClick = { requestRemoval(selectedTab == DownloadManagerTab.Queue, selectableTargets.filter { it.key in selectedKeys }) },
                            destructive = true,
                            enabled = !busy && selectedKeys.isNotEmpty(),
                        )
                    }
                }
                if (appTasks[downloadRemovalTaskKey]?.isActive == true) {
                    Text(i18n("處理中 {}/{}", progress, lastRemoval?.targets?.size ?: 0), color = colors.textStrong)
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = colors.brownPrimary, trackColor = colors.brownPrimary.copy(alpha = 0.12f))
                } else if (failures.isNotEmpty() && lastRemoval?.tasksOnly == (selectedTab == DownloadManagerTab.Queue)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(i18n("成功 {} 項，失敗 {} 項", outcomes.size - failures.size, failures.size), modifier = Modifier.weight(1f), color = colors.textStrong)
                        YamiboActionChip(i18n("失敗明細"), onClick = { showFailureDetails = true })
                    }
                }
            }
            when (selectedTab) {
                DownloadManagerTab.Queue -> DownloadQueueTab(
                    entries = entries,
                    selectionMode = selectionMode,
                    selectedKeys = selectedKeys,
                    busy = busy,
                    onToggle = { toggleKeys(setOf(it)) },
                    onRemove = { requestRemoval(true, listOf(DownloadRemovalTarget(it.key, it.title))) },
                    summary = summary,
                    folderReady = folderReady,
                    onOpenStorageSettings = { navigator.navigate(ISettingsCategoryScreen("storage")) },
                    onPauseAll = {
                        appTaskManager.launch(AppTaskKey("download-control:pause-all")) {
                            repository.pauseAll()
                        }
                    },
                    onResumeAll = {
                        appTaskManager.launch(AppTaskKey("download-control:resume-all")) {
                            repository.resumeAll()
                        }
                    },
                    onRetry = { entry ->
                        appTaskManager.launch(AppTaskKey("download-control:retry:${entry.key.stableId}")) {
                            repository.retry(entry.key)
                                .onFailure { error ->
                                    Logger.e("DownloadQueueScreen", "Failed to retry download key=${entry.key.stableId}", error)
                                    feedbackController.post(error.message ?: i18n("重試失敗"))
                                }
                        }
                    },
                )
                DownloadManagerTab.Content -> DownloadContentManagementTab(
                    state = contentState,
                    onMigrate = { confirmMigration = true },
                    onRescan = { contentReloadToken++ },
                    migrationProgress = migrationProgress,
                    selectionActions = {
                        val allSelected = selectableKeys.isNotEmpty() && selectedKeys.containsAll(selectableKeys)
                        YamiboActionChip(
                            text = if (allSelected) i18n("取消全選") else i18n("全選"),
                            onClick = { toggleKeys(selectableKeys) },
                            enabled = !busy && selectableKeys.isNotEmpty(),
                        )
                        YamiboActionChip(i18n("取消"), { leaveSelection() }, enabled = !busy)
                    },
                    selectionMode = selectionMode,
                    selectedKeys = selectedKeys,
                    busy = busy,
                    onToggle = ::toggleKeys,
                    sortMode = contentSortMode,
                    sortDescending = contentSortDescending,
                    selectedFilterKeys = selectedContentFilterKeys.toSet(),
                    onSelectSortMode = { mode ->
                        if (mode == contentSortMode) {
                            contentSortDescending = !contentSortDescending
                        } else {
                            contentSortMode = mode
                            contentSortDescending = true
                        }
                    },
                    onSelectFilterKeys = { keys ->
                        selectedContentFilterKeys = keys.toList()
                    },
                    onRetryLoad = {
                        scope.launch {
                            if (downloadedContentRefreshAutoUpdate) {
                                val refreshed = refreshUpdateAvailableDownloads(entries, repository)
                                if (refreshed > 0) {
                                    feedbackController.post(i18n("已自動刷新 {} 項可更新內容", refreshed))
                                }
                            }
                            contentReloadToken++
                        }
                    },
                    onClearGroup = { group ->
                        requestRemoval(false, group.items.flatMap { item -> item.sourceKeys.map { DownloadRemovalTarget(it, item.title) } })
                    },
                    onClearItem = { item ->
                        requestRemoval(false, item.sourceKeys.map { DownloadRemovalTarget(it, item.title) })
                    },
                    onRefreshItem = { item ->
                        val handle = appTaskManager.launch(AppTaskKey("download-control:refresh:${item.key.stableId}")) {
                            val result = when (val key = item.key) {
                                is ThreadPageDownloadKey -> repository.refreshPage(
                                    ThreadId(key.tid),
                                    item.title,
                                    key.authorId?.let(::UserId),
                                    key.page,
                                )
                                is TagMangaChapterDownloadKey -> repository.refreshTagMangaChapter(key)
                                is RssMangaChapterDownloadKey -> repository.refreshRssMangaChapter(key)
                            }
                            when (result) {
                                is YamiboResult.Success -> {
                                    feedbackController.post(i18n("已重新載入下載內容"))
                                }
                                else -> feedbackController.post(i18n("重新載入失敗：{}", i18n(errorMessages.resolve(result))))
                            }
                        }
                        scope.launch {
                            handle.state.first { !it.isActive }
                            catalogUpdateToken++
                        }
                    },
                )
            }
        }
    }
    if (confirmMigration || migrationError != null) {
        val failure = migrationError
        AlertDialog(
            containerColor = colors.creamSurface, titleContentColor = colors.textOnSurface, textContentColor = colors.textOnSurface,
            onDismissRequest = { confirmMigration = false; migrationError = null },
            title = { Text(i18n("轉換舊版圖片")) },
            text = {
                if (failure == null) Text(i18n("將已下載的散圖和 CBZ 匯入共用下載庫。確認新檔可讀後才移除舊檔，中斷後可以繼續。"))
                else LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(failure.lines()) { Text(it) }
                }
            },
            confirmButton = { YamiboActionChip(if (failure == null) i18n("轉換") else i18n("重試"), onClick = {
                confirmMigration = false
                migrationError = null
                scope.launch {
                    migrationProgress = 0 to 0
                    try {
                        val errors = repository.migrateImages { done, total -> migrationProgress = done to total }
                        if (errors.isEmpty()) feedbackController.post(i18n("圖片轉換完成"))
                        else migrationError = errors.entries.joinToString("\n") { (key, error) -> "${key.stableId} · ${error.message.orEmpty()}" }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        migrationError = error.message ?: i18n("轉換失敗")
                    } finally {
                        migrationProgress = null
                        catalogUpdateToken++
                    }
                }
            }, selected = true) },
            dismissButton = { YamiboActionChip(i18n("取消"), { confirmMigration = false; migrationError = null }) },
        )
    }
    pendingRemoval?.let { request ->
        AlertDialog(
            containerColor = colors.creamSurface,
            titleContentColor = colors.textOnSurface,
            textContentColor = colors.textOnSurface,
            onDismissRequest = { pendingRemoval = null },
            title = { Text(if (request.tasksOnly) i18n("移除任務") else i18n("刪除下載內容")) },
            text = {
                Column {
                    Text(i18n("共 {} 項", request.targets.size))
                    Text(if (request.tasksOnly) i18n("停止所選任務並從佇列移除，保留已下載的完整內容。")
                        else i18n("刪除所選離線內容及圖片，此操作無法復原。"))
                    if (!request.tasksOnly) Text(i18n("已知圖片大小：{}", formatDownloadedByteSize(request.targets.sumOf { it.imageBytes })))
                }
            },
            confirmButton = { YamiboActionChip(i18n("確定"), onClick = { executeRemoval(request) }, enabled = !busy, selected = true) },
            dismissButton = { YamiboActionChip(i18n("取消"), onClick = { pendingRemoval = null }) },
        )
    }
    if (showFailureDetails) {
        AlertDialog(
            containerColor = colors.creamSurface,
            titleContentColor = colors.textOnSurface,
            textContentColor = colors.textOnSurface,
            onDismissRequest = { showFailureDetails = false },
            title = { Text(i18n("失敗明細")) },
            text = {
                LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(failures, key = { it.target.key.stableId }) { outcome ->
                        Column {
                            Text(outcome.target.title, fontWeight = FontWeight.Medium)
                            outcome.error?.let { Text(i18n(forumErrorMessage(it))) }
                        }
                    }
                }
            },
            confirmButton = { YamiboActionChip(i18n("關閉"), onClick = { showFailureDetails = false }) },
            dismissButton = {
                YamiboActionChip(i18n("重試失敗項目"), onClick = {
                    showFailureDetails = false
                    lastRemoval?.let { requestRemoval(it.tasksOnly, failures.map { result -> result.target }) }
                }, enabled = !busy)
            },
        )
    }
}

@Composable
private fun DownloadManagerTabRow(
    selectedTab: DownloadManagerTab,
    onSelect: (DownloadManagerTab) -> Unit,
) {
    val colors = YamiboTheme.colors
    val tabs = remember { DownloadManagerTab.entries }
    YamiboScrollableTabRow(selectedIndex = tabs.indexOf(selectedTab).coerceAtLeast(0)) {
        tabs.forEach { tab ->
            Tab(
                selected = tab == selectedTab,
                onClick = { onSelect(tab) },
                text = {
                    Text(
                        text = i18n(tab.title),
                        color = colors.textOnSurface,
                        fontSize = 14.sp,
                        maxLines = 1,
                    )
                },
            )
        }
    }
}

@Composable
private fun DownloadQueueTab(
    entries: List<DownloadQueueEntry>,
    selectionMode: Boolean,
    selectedKeys: Set<DownloadTaskKey>,
    busy: Boolean,
    onToggle: (DownloadTaskKey) -> Unit,
    onRemove: (DownloadQueueEntry) -> Unit,
    summary: DownloadQueueSummary,
    folderReady: Boolean,
    onOpenStorageSettings: () -> Unit,
    onPauseAll: () -> Unit,
    onResumeAll: () -> Unit,
    onRetry: (DownloadQueueEntry) -> Unit,
) {
    // Presentation order is independent of the repository's execution order.
    val newestFirstEntries = remember(entries) { entries.sortedByDescending { it.queuedAt } }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            DownloadSummaryCard(
                summary = summary,
                entries = entries,
                onPauseAll = { if (!busy) onPauseAll() },
                onResumeAll = { if (!busy) onResumeAll() },
            )
        }
        item { DownloadFolderSettingsCard(folderReady, onOpenStorageSettings) }
        if (entries.isEmpty()) {
            item { EmptyDownloadQueueCard() }
        } else {
            items(newestFirstEntries, key = { it.key.stableId }) { entry ->
                DownloadQueueEntryCard(
                    entry = entry, selectionMode = selectionMode, selected = entry.key in selectedKeys, busy = busy,
                    onToggle = { onToggle(entry.key) }, onRemove = { onRemove(entry) }, onRetry = { onRetry(entry) },
                )
            }
        }
    }
}

@Composable
private fun DownloadSummaryCard(
    summary: DownloadQueueSummary,
    entries: List<DownloadQueueEntry>,
    onPauseAll: () -> Unit,
    onResumeAll: () -> Unit,
) {
    val colors = YamiboTheme.colors
    val hasActive = entries.any { it.status == DownloadStatus.Queued || it.status == DownloadStatus.Downloading }
    val hasPaused = entries.any { it.status == DownloadStatus.Paused }
    YamiboDownloadCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(YamiboIcons.Download, contentDescription = null, tint = colors.orangeAccent, modifier = Modifier.size(28.dp))
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(i18n("下載佇列"), color = colors.textStrong, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                Text(
                    i18n(
                        "{} · 下載中 {}，等待 {}，失敗 {}",
                        downloadManagerTitleStatus(entries, summary),
                        summary.downloading,
                        summary.queued,
                        summary.failed,
                    ),
                    color = colors.textDark.copy(alpha = 0.68f),
                    fontSize = 13.sp,
                )
            }
            when {
                hasActive -> SmallQueueButton(i18n("暫停"), onPauseAll)
                hasPaused -> SmallQueueButton(i18n("繼續"), onResumeAll)
            }
        }
        if (summary.active > 0) {
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { if (summary.active == 0) 0f else summary.downloading.toFloat() / summary.active.toFloat() },
                modifier = Modifier.fillMaxWidth(),
                color = colors.orangeAccent,
                trackColor = colors.brownLight.copy(alpha = 0.25f),
            )
        }
    }
}

@Composable
private fun DownloadFolderSettingsCard(
    folderReady: Boolean,
    onOpenStorageSettings: () -> Unit,
) {
    val colors = YamiboTheme.colors
    YamiboDownloadCard {
        Text(i18n("下載資料夾"), color = colors.textStrong, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            text = if (folderReady) {
                i18n("下載與備份資料夾由儲存空間設定統一管理。")
            } else {
                i18n("尚未設定下載資料夾，請先前往儲存空間選擇位置。")
            },
            color = colors.textDark.copy(alpha = 0.68f),
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(12.dp))
        SmallQueueButton(i18n("前往儲存空間"), onOpenStorageSettings)
    }
}

@Composable
private fun EmptyDownloadQueueCard() {
    val colors = YamiboTheme.colors
    YamiboDownloadCard {
        Text(i18n("目前沒有下載任務"), color = colors.textStrong, fontWeight = FontWeight.SemiBold)
        Text(i18n("可在閱讀頁使用下載按鈕加入目前頁或完整 Thread。"), color = colors.textDark.copy(alpha = 0.68f), fontSize = 13.sp)
    }
}

@Composable
private fun DownloadQueueEntryCard(
    entry: DownloadQueueEntry,
    selectionMode: Boolean,
    selected: Boolean,
    busy: Boolean,
    onToggle: () -> Unit,
    onRemove: () -> Unit,
    onRetry: () -> Unit,
) {
    val colors = YamiboTheme.colors
    val statusColor = when (entry.status) {
        DownloadStatus.Failed -> colors.redAccent
        DownloadStatus.Downloading, DownloadStatus.Queued -> colors.orangeAccent
        else -> colors.brownPrimary
    }
    YamiboDownloadCard {
        val selectable = selectionMode && entry.status.canRemoveTask()
        Row(
            modifier = Modifier.fillMaxWidth().then(
                if (entry.status.canRemoveTask()) Modifier.downloadRowSelection(selectionMode, selected, !busy, onToggle)
                else Modifier
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectable) DownloadSelectionMark(if (selected) ToggleableState.On else ToggleableState.Off, !busy)
            Icon(
                imageVector = if (entry.status == DownloadStatus.Downloaded) YamiboIcons.Downloaded else YamiboIcons.Download,
                contentDescription = null,
                tint = statusColor,
                modifier = Modifier.size(24.dp),
            )
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(entry.title, color = colors.textStrong, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${downloadEntryLabel(entry)} · ${entryDetailLabel(entry)}${entry.message?.let { " · ${i18n(forumErrorMessage(it))}" }.orEmpty()}",
                    color = if (entry.status == DownloadStatus.Failed) colors.redAccent else colors.textDark.copy(alpha = 0.68f),
                    fontSize = 12.sp,
                )
            }
            if (!selectionMode && entry.status == DownloadStatus.Failed) {
                SmallQueueButton(i18n("重試"), onRetry, enabled = !busy)
            }
        }
        if (!selectionMode && entry.status.canRemoveTask()) {
            SmallQueueButton(i18n("移除任務"), onRemove, destructive = true, enabled = !busy)
        }
    }
}

@Composable
private fun DownloadContentManagementTab(
    onMigrate: () -> Unit,
    onRescan: () -> Unit,
    migrationProgress: Pair<Int, Int>?,
    state: DownloadContentManagementState,
    selectionActions: @Composable () -> Unit,
    selectionMode: Boolean,
    selectedKeys: Set<DownloadTaskKey>,
    busy: Boolean,
    onToggle: (Set<DownloadTaskKey>) -> Unit,
    sortMode: DownloadedContentSortMode,
    sortDescending: Boolean,
    selectedFilterKeys: Set<String>,
    onSelectSortMode: (DownloadedContentSortMode) -> Unit,
    onSelectFilterKeys: (Set<String>) -> Unit,
    onRetryLoad: () -> Unit,
    onClearGroup: (DownloadedContentGroup) -> Unit,
    onClearItem: (DownloadedContentItem) -> Unit,
    onRefreshItem: (DownloadedContentItem) -> Unit,
) {
    val colors = YamiboTheme.colors
    val groups = (state as? DownloadContentManagementState.Ready)?.groups.orEmpty()
    var expandedGroups by remember { mutableStateOf(emptySet<String>()) }
    var showSortDialog by remember { mutableStateOf(false) }
    var showFilterDialog by remember { mutableStateOf(false) }
    val filterOptions = remember(groups) { buildDownloadedContentFilterOptions(groups) }
    val normalizedFilterKeys = remember(selectedFilterKeys, filterOptions) {
        selectedFilterKeys.filterTo(mutableSetOf()) { selected ->
            selected != DOWNLOADED_CONTENT_FILTER_ALL && filterOptions.any { it.key == selected }
        }
    }
    val displayedGroups = remember(groups, normalizedFilterKeys, sortMode, sortDescending) {
        filterAndSortDownloadedContentGroups(
            groups = groups,
            selectedFilterKeys = normalizedFilterKeys,
            sortMode = sortMode,
            descending = sortDescending,
        )
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        when (state) {
            DownloadContentManagementState.Loading -> {
                item { DownloadContentLoadingCard() }
            }
            is DownloadContentManagementState.Error -> {
                item {
                    YamiboDownloadCard {
                        Text(i18n("下載內容管理"), color = colors.textStrong, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                        Spacer(Modifier.height(6.dp))
                        Text(state.message, color = colors.redAccent, fontSize = 13.sp)
                        Spacer(Modifier.height(10.dp))
                        SmallQueueButton(i18n("重新載入"), onRetryLoad)
                    }
                }
            }
            is DownloadContentManagementState.Ready -> {
                stickyHeader(key = "content-controls") {
                    DownloadContentControlRow(
                        sortMode = sortMode,
                        sortDescending = sortDescending,
                        filterActive = normalizedFilterKeys.isNotEmpty(),
                        onSortClick = { if (!busy) showSortDialog = true },
                        onFilterClick = { if (!busy) showFilterDialog = true },
                    ) {
                        if (selectionMode) {
                            selectionActions()
                        } else if (migrationProgress == null) {
                            YamiboActionChip(i18n("重新掃描下載資料夾"), onRescan, enabled = !busy)
                        }
                    }
                }
                item {
                    Spacer(Modifier.height(12.dp))
                    DownloadContentSummaryCard(state.summary)
                    if (migrationProgress != null) {
                        Text(i18n("處理中 {}/{}", migrationProgress.first, migrationProgress.second), color = colors.textStrong)
                    } else if (!selectionMode && groups.any { group -> group.items.any { it.needsMigration } }) {
                        YamiboActionChip(i18n("轉換舊版圖片"), onMigrate, enabled = !busy)
                    }
                }
                if (groups.isEmpty()) {
                    item {
                        Spacer(Modifier.height(12.dp))
                        YamiboDownloadCard {
                            Text(i18n("目前沒有已下載內容"), color = colors.textStrong, fontWeight = FontWeight.SemiBold)
                        }
                    }
                } else if (displayedGroups.isEmpty()) {
                    item {
                        Spacer(Modifier.height(12.dp))
                        YamiboDownloadCard {
                            Text(i18n("沒有符合篩選的下載內容"), color = colors.textStrong, fontWeight = FontWeight.SemiBold)
                        }
                    }
                } else {
                    displayedGroups.forEach { group ->
                        val expanded = group.id in expandedGroups
                        item(key = "group:${group.id}", contentType = "group") {
                            DownloadContentGroupHeader(
                                modifier = downloadPanelMotion().padding(top = 12.dp),
                                group = group,
                                selectionMode = selectionMode,
                                selectedKeys = selectedKeys,
                                busy = busy,
                                onToggleSelection = { onToggle(group.items.flatMapTo(mutableSetOf()) { it.sourceKeys }) },
                                expanded = expanded,
                                onToggleExpanded = {
                                    expandedGroups = if (expanded) expandedGroups - group.id else expandedGroups + group.id
                                },
                            )
                        }
                        if (expanded) {
                            itemsIndexed(group.items, key = { _, item -> "item:${group.id}:${item.key.stableId}" }, contentType = { _, _ -> "content" }) { index, item ->
                                DownloadPanelSection(modifier = downloadPanelMotion(), last = selectionMode && index == group.items.lastIndex) {
                                    DownloadContentItemRow(item, selectionMode, item.key in selectedKeys, busy, { onToggle(item.sourceKeys.toSet()) }, onClearItem, onRefreshItem)
                                }
                            }
                            if (!selectionMode) item(key = "clear:${group.id}", contentType = "clear") {
                                DownloadPanelSection(modifier = downloadPanelMotion(), last = true) {
                                    SmallQueueButton(
                                        text = i18n("刪除整組下載內容"),
                                        enabled = !busy,
                                        onClick = { onClearGroup(group) },
                                        destructive = true,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (showSortDialog) {
        YamiboSingleSelectDialog(
            title = i18n("排序"),
            options = DownloadedContentSortMode.entries,
            selected = sortMode,
            onDismiss = { showSortDialog = false },
            onSelect = onSelectSortMode,
            label = { it.downloadedContentSortLabel() },
            selectedText = if (sortDescending) "↓" else "↑",
            footer = { YamiboActionChip(i18n("確定"), onClick = { showSortDialog = false }) },
        )
    }
    if (showFilterDialog) {
        val allOption = DownloadedContentFilterOption(
            key = DOWNLOADED_CONTENT_FILTER_ALL,
            label = i18n("全部"),
            count = groups.sumOf { it.itemCount },
        )
        val options = listOf(allOption) + filterOptions
        val selectedOptions = if (normalizedFilterKeys.isEmpty()) {
            setOf(allOption)
        } else {
            options.filterTo(mutableSetOf()) { it.key in normalizedFilterKeys }
        }
        YamiboMultiSelectDialog(
            title = i18n("篩選下載內容"),
            options = options,
            selected = selectedOptions,
            onConfirm = { selected ->
                showFilterDialog = false
                onSelectFilterKeys(
                    if (allOption in selected) {
                        emptySet()
                    } else {
                        selected.mapTo(mutableSetOf()) { it.key }
                    }
                )
            },
            onCancel = { showFilterDialog = false },
            onDismissRequest = { showFilterDialog = false },
            label = { option -> i18n("{} ({})", i18n(option.label), option.count) },
            toggleSelection = { option, current ->
                when {
                    option.key == DOWNLOADED_CONTENT_FILTER_ALL -> setOf(option)
                    option in current -> (current - option).ifEmpty { setOf(allOption) }
                    else -> (current - allOption) + option
                }
            },
        )
    }
}

@Composable
private fun DownloadContentControlRow(
    sortMode: DownloadedContentSortMode,
    sortDescending: Boolean,
    filterActive: Boolean,
    onSortClick: () -> Unit,
    onFilterClick: () -> Unit,
    actions: @Composable () -> Unit,
) {
    val horizontalSpacing = Arrangement.spacedBy(8.dp)
    val verticalSpacing = Arrangement.spacedBy(4.dp)
    FlowRow(
        modifier = Modifier.fillMaxWidth().background(YamiboTheme.colors.creamBackground),
        horizontalArrangement = horizontalSpacing,
        verticalArrangement = verticalSpacing,
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        YamiboActionChip(
            text = i18n("排序: {} {}", sortMode.downloadedContentSortLabel(), if (sortDescending) "↓" else "↑"),
            onClick = onSortClick,
        )
        FlowRow(
            horizontalArrangement = horizontalSpacing,
            verticalArrangement = verticalSpacing,
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            YamiboActionChip(
                text = i18n("篩選"),
                onClick = onFilterClick,
                selected = filterActive,
            )
            actions()
        }
    }
}

@Composable
private fun DownloadContentLoadingCard() {
    val colors = YamiboTheme.colors
    YamiboDownloadCard {
        Text(i18n("下載內容管理"), color = colors.textStrong, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
        Spacer(Modifier.height(6.dp))
        Text(i18n("載入中..."), color = colors.textDark.copy(alpha = 0.72f), fontSize = 13.sp)
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(
            color = colors.orangeAccent,
            trackColor = colors.creamBackground,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun DownloadContentSummaryCard(summary: DownloadedContentSummary) {
    val colors = YamiboTheme.colors
    YamiboDownloadCard {
        YamiboStorageUsageOverview(
            title = i18n("儲存空間"),
            usages = listOf(
                CacheStorageUsage("offline_images", i18n("下載圖片"), summary.imageBytes),
            ),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            i18n(
                "共 {} 帖，圖片 {} 張",
                summary.totalItems,
                summary.imageCount,
            ),
            color = colors.textDark.copy(alpha = 0.72f),
            fontSize = 13.sp,
        )
        Text(
            i18n("圖片約佔 {}", formatDownloadedByteSize(summary.imageBytes)),
            color = colors.brownPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun DownloadContentGroupHeader(
    group: DownloadedContentGroup,
    selectionMode: Boolean,
    selectedKeys: Set<DownloadTaskKey>,
    busy: Boolean,
    onToggleSelection: () -> Unit,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = YamiboTheme.colors
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(180),
    )
    val selection = when {
        group.items.all { it.key in selectedKeys } -> ToggleableState.On
        group.items.any { it.key in selectedKeys } -> ToggleableState.Indeterminate
        else -> ToggleableState.Off
    }
    DownloadPanelSection(modifier = modifier, first = true, last = !expanded) {
        Row(
            modifier = Modifier.fillMaxWidth().then(
                if (selectionMode) Modifier.triStateToggleable(
                    state = selection, interactionSource = null, indication = null,
                    enabled = !busy, role = Role.Checkbox, onClick = onToggleSelection,
                ) else Modifier.combinedClickable(
                    interactionSource = null, indication = null,
                    onClick = onToggleExpanded,
                    onLongClickLabel = i18n("多選"),
                    onLongClick = if (busy) null else onToggleSelection,
                )
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectionMode) DownloadSelectionMark(selection, !busy)
            Icon(
                imageVector = YamiboIcons.Book,
                contentDescription = null,
                tint = colors.brownPrimary,
                modifier = Modifier.size(22.dp),
            )
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(group.title, color = colors.textStrong, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    i18n("{} 項 · {} 張圖片 · {}", group.itemCount, group.imageCount, formatDownloadedByteSize(group.imageBytes)),
                    color = colors.textDark.copy(alpha = 0.68f),
                    fontSize = 12.sp,
                )
            }
            val expandLabel = i18n("點擊展開 / 收起")
            Box(
                modifier = if (selectionMode) Modifier.size(48.dp)
                    .clickable(interactionSource = null, indication = null, role = Role.Button, onClick = onToggleExpanded)
                    .semantics { contentDescription = expandLabel } else Modifier,
                contentAlignment = Alignment.Center,
            ) {
                Text("▼", color = colors.brownPrimary, fontSize = 12.sp, modifier = Modifier.graphicsLayer { rotationZ = arrowRotation })
            }
        }
    }
}

private fun LazyItemScope.downloadPanelMotion() = Modifier.animateItem(
    fadeInSpec = tween(180),
    placementSpec = tween(180),
    fadeOutSpec = tween(120),
)

// Adjacent lazy items share the panel background so even long chapter lists compose on demand.
@Composable
private fun DownloadPanelSection(
    modifier: Modifier = Modifier,
    first: Boolean = false,
    last: Boolean,
    content: @Composable () -> Unit,
) {
    val colors = YamiboTheme.colors
    val top = if (first) 18.dp else 0.dp
    val bottom = if (last) 18.dp else 0.dp
    Column(
        modifier = modifier.fillMaxWidth()
            .background(colors.creamSurface, RoundedCornerShape(top, top, bottom, bottom))
            .padding(horizontal = 16.dp),
    ) {
        if (!first) HorizontalDivider(color = colors.brownPrimary.copy(alpha = 0.15f))
        Box(Modifier.padding(vertical = if (first) 12.dp else 4.dp)) { content() }
    }
}

@Composable
private fun DownloadContentItemRow(
    item: DownloadedContentItem,
    selectionMode: Boolean,
    selected: Boolean,
    busy: Boolean,
    onToggle: () -> Unit,
    onClearItem: (DownloadedContentItem) -> Unit,
    onRefreshItem: (DownloadedContentItem) -> Unit,
) {
    var opening by remember(item.key) { mutableStateOf(false) }
    if (opening) DownloadedReaderDialog(item) { opening = false }
    val colors = YamiboTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth()
            .downloadRowSelection(selectionMode, selected, !busy, onToggle)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode) DownloadSelectionMark(if (selected) ToggleableState.On else ToggleableState.Off, !busy)
        Column(Modifier.weight(1f).then(if (selectionMode) Modifier else Modifier.clickable(enabled = !busy) { opening = true })) {
            Text(when (val key = item.key) {
                is ThreadPageDownloadKey -> i18n("第 {} 頁", key.page)
                else -> item.title
            }, color = colors.textStrong, fontWeight = FontWeight.Medium, fontSize = 13.sp)
            Text(
                i18n("{} 張圖片 · {}", item.imageCount, formatDownloadedByteSize(item.imageBytes)),
                color = colors.textDark.copy(alpha = 0.62f),
                fontSize = 12.sp,
            )
        }
        if (!selectionMode) {
            SmallQueueButton(i18n("重新載入"), { onRefreshItem(item) }, enabled = !busy)
            Spacer(Modifier.size(6.dp))
            SmallQueueButton(i18n("清除"), { onClearItem(item) }, destructive = true, enabled = !busy)
        }
    }
}

@Composable
private fun Modifier.downloadRowSelection(
    selectionMode: Boolean,
    selected: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
): Modifier {
    if (selectionMode) return toggleable(
        value = selected, interactionSource = null, indication = null,
        enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle() },
    )
    if (!enabled) return this
    val onLongPress by rememberUpdatedState(onToggle)
    val label = i18n("多選")
    return pointerInput(Unit) { detectTapGestures(onLongPress = { onLongPress() }) }
        .semantics(mergeDescendants = true) { onLongClick(label) { onLongPress(); true } }
}

@Composable
private fun DownloadSelectionMark(state: ToggleableState, enabled: Boolean) {
    val colors = YamiboTheme.colors
    TriStateCheckbox(
        state = state,
        onClick = null,
        modifier = Modifier.padding(end = 10.dp),
        enabled = enabled,
        colors = CheckboxDefaults.colors(
            checkedColor = colors.brownDeep,
            uncheckedColor = colors.textDark.copy(alpha = 0.65f),
            checkmarkColor = colors.textOnDeepHigh,
        ),
    )
}

@Composable
private fun YamiboDownloadCard(content: @Composable ColumnScope.() -> Unit) {
    val colors = YamiboTheme.colors
    Card(
        shape = RoundedCornerShape(18.dp),
        elevation = CardDefaults.cardElevation(2.dp),
        colors = CardDefaults.cardColors(containerColor = colors.creamSurface),
        modifier = Modifier.fillMaxWidth().animateContentSize(tween(180)),
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

@Composable
private fun SmallQueueButton(
    text: String,
    onClick: () -> Unit,
    destructive: Boolean = false,
    enabled: Boolean = true,
) {
    val colors = YamiboTheme.colors
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        border = BorderStroke(1.dp, colors.brownPrimary.copy(alpha = if (enabled) 0.45f else 0.18f)),
        colors = ButtonDefaults.outlinedButtonColors(
            disabledContentColor = colors.textOnSurface.copy(alpha = 0.38f),
            contentColor = if (destructive) colors.redAccent else colors.brownPrimary,
            containerColor = Color.Transparent,
        ),
    ) {
        Text(text, fontSize = 12.sp)
    }
}

private fun downloadManagerTitleStatus(
    entries: List<DownloadQueueEntry>,
    summary: DownloadQueueSummary,
): String {
    val downloading = entries.firstOrNull { it.status == DownloadStatus.Downloading }
    if (downloading != null) return downloadEntryLabel(downloading)
    if (summary.queued > 0) return i18n("等待中 {}", summary.queued)
    if (summary.failed > 0) return i18n("失敗 {}", summary.failed)
    if (summary.downloaded > 0) return i18n("已下載 {}", summary.downloaded)
    return i18n("空閒")
}

internal fun downloadEntryLabel(entry: DownloadQueueEntry): String = when {
    entry.status == DownloadStatus.Downloading && entry.stage != null -> stageLabel(entry)
    else -> statusLabel(entry.status)
}

internal fun stageLabel(entry: DownloadQueueEntry): String = when (entry.stage) {
    DownloadStage.Preparing -> i18n("準備中")
    DownloadStage.FetchingContent -> i18n("正在取得內容")
    DownloadStage.DownloadingText -> i18n("下載文字")
    DownloadStage.DownloadingImages -> {
        if (entry.progressTotal > 0) {
            i18n("下載圖片 {}/{}", entry.progressCurrent, entry.progressTotal)
        } else {
            i18n("下載圖片")
        }
    }
    DownloadStage.Saving -> i18n("儲存中")
    null -> statusLabel(entry.status)
}

private fun statusLabel(status: DownloadStatus): String = when (status) {
    DownloadStatus.NotDownloaded -> i18n("未下載")
    DownloadStatus.Queued -> i18n("等待中")
    DownloadStatus.Downloading -> i18n("下載中")
    DownloadStatus.Downloaded -> i18n("已下載")
    DownloadStatus.Failed -> i18n("下載失敗")
    DownloadStatus.Paused -> i18n("已暫停")
    DownloadStatus.UpdateAvailable -> i18n("可刷新")
}

private fun DownloadedContentSortMode.downloadedContentSortLabel(): String = when (this) {
    DownloadedContentSortMode.TotalSize -> i18n("總大小")
    DownloadedContentSortMode.DownloadedAt -> i18n("下載時間")
}

private fun entryDetailLabel(entry: DownloadQueueEntry): String = when (val key = entry.key) {
    is ThreadPageDownloadKey -> i18n("第 {} 頁", key.page)
    is TagMangaChapterDownloadKey -> i18n("標籤漫畫章節")
    is RssMangaChapterDownloadKey -> i18n("RSS 漫畫章節")
}

private suspend fun refreshUpdateAvailableDownloads(
    entries: List<DownloadQueueEntry>,
    repository: DownloadRepository,
): Int {
    var refreshed = 0
    entries
        .filter { it.status == DownloadStatus.UpdateAvailable }
        .distinctBy { it.key.stableId }
        .forEach { entry ->
            val result = when (val key = entry.key) {
                is ThreadPageDownloadKey -> repository.refreshPage(
                    ThreadId(key.tid),
                    entry.title,
                    key.authorId?.let(::UserId),
                    key.page,
                )
                is TagMangaChapterDownloadKey -> repository.refreshTagMangaChapter(key)
                is RssMangaChapterDownloadKey -> repository.refreshRssMangaChapter(key)
            }
            if (result is YamiboResult.Success) refreshed += 1
        }
    return refreshed
}

package me.thenano.yamibo.yamibo_app.profile.download

import me.thenano.yamibo.yamibo_app.components.feedback.forumErrorMessage
import me.thenano.yamibo.yamibo_app.LocalForumErrorMessageResolver
import YamiboIcons
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
    val outcomes: List<DownloadRemovalOutcome> = emptyList(),
)
// Retain the current batch result when navigating away or changing app language.
private val downloadRemovalProgress = MutableStateFlow(DownloadRemovalProgress())
private val downloadRemovalTaskKey = AppTaskKey("download-control:batch-remove")

private fun DownloadStatus.canRemoveTask(): Boolean =
    this == DownloadStatus.Queued || this == DownloadStatus.Downloading || this == DownloadStatus.Paused || this == DownloadStatus.Failed

@Composable
fun DownloadQueueScreen(screenId: String) {
    val errorMessages = LocalForumErrorMessageResolver.current
    val colors = YamiboTheme.colors
    val navigator = LocalNavigator.current
    val repository = LocalDownloadRepository.current
    val appSettingsRepository = LocalAppSettingsRepository.current
    val downloadedContentRefreshAutoUpdate = appSettingsRepository.downloadedContentRefreshAutoUpdate.state()
    val storageFolderUri = appSettingsRepository.backupFolderUri.state()
    val entries by repository.queue.collectAsState()
    val feedbackController = me.thenano.yamibo.yamibo_app.LocalAppFeedbackController.current
    val appTaskManager = LocalAppTaskManager.current
    val scope = rememberCoroutineScope()
    var summary by remember { mutableStateOf(DownloadQueueSummary()) }
    var folderReady by remember { mutableStateOf(true) }
    var selectedTab by remember { mutableStateOf(DownloadManagerTab.Queue) }
    var contentState by remember { mutableStateOf<DownloadContentManagementState>(DownloadContentManagementState.Loading) }
    var contentReloadToken by remember { mutableStateOf(0) }
    var lastContentReloadToken by remember { mutableStateOf(0) }
    var contentSortMode by rememberSaveable { mutableStateOf(DownloadedContentSortMode.DownloadedAt) }
    var contentSortDescending by rememberSaveable { mutableStateOf(true) }
    var selectedContentFilterKeys by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val queueStatuses = remember(entries) { entries.map { it.key to it.status } }
    val contentRevision = remember(entries) { downloadedContentRevision(entries) }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedKeys by remember { mutableStateOf(emptySet<DownloadTaskKey>()) }
    var pendingRemoval by remember { mutableStateOf<DownloadRemovalRequest?>(null) }
    val removalProgress by downloadRemovalProgress.collectAsState()
    val lastRemoval = removalProgress.request
    val outcomes = removalProgress.outcomes
    val progress = outcomes.size
    var showResults by remember { mutableStateOf(false) }
    val appTasks by appTaskManager.tasks.collectAsState()
    val busy = appTasks[downloadRemovalTaskKey]?.isActive == true
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
                .map { DownloadRemovalTarget(it.key, it.title, it.imageBytes) }
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
        if (!busy) selectedKeys = if (selectedKeys.containsAll(keys)) selectedKeys - keys else selectedKeys + keys
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
                request.targets.forEach { target ->
                    val result = if (request.tasksOnly) repository.removeTask(target.key) else clearItem(target.key, repository)
                    val outcome = DownloadRemovalOutcome(target, result.exceptionOrNull()?.let { it.message ?: "清除失敗" })
                    downloadRemovalProgress.update { it.copy(outcomes = it.outcomes + outcome) }
                    if (result.isSuccess) selectedKeys = selectedKeys - target.key
                }
                val results = downloadRemovalProgress.value.outcomes
                val failed = results.count { it.error != null }
                feedbackController.post(i18n("成功 {} 項，失敗 {} 項", results.size - failed, failed))
            } finally {
                contentReloadToken++
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

    LaunchedEffect(selectedTab, contentRevision, contentReloadToken, storageFolderUri) {
        if (selectedTab == DownloadManagerTab.Content) {
            val forceRefresh = lastContentReloadToken != contentReloadToken
            reloadContentManagement(forceRefresh)
            lastContentReloadToken = contentReloadToken
        }
    }

    Scaffold(
        topBar = {
            YamiboTopBar(
                title = i18n("下載管理"),
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { if (!leaveSelection()) selectionMode = true }, enabled = !busy) {
                        Text(if (selectionMode) i18n("取消選取") else i18n("多選"))
                    }
                    if (selectionMode) Text(i18n("已選取 {} 項", selectedKeys.size), color = colors.textStrong)
                }
                if (selectionMode) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { selectedKeys = selectableKeys }, enabled = !busy) { Text(i18n("全選")) }
                        TextButton(onClick = { selectedKeys = emptySet() }, enabled = !busy) { Text(i18n("全不選")) }
                        TextButton(onClick = { selectedKeys = selectableKeys - selectedKeys }, enabled = !busy) { Text(i18n("反選")) }
                    }
                    TextButton(
                        enabled = !busy && selectedKeys.isNotEmpty(),
                        onClick = { requestRemoval(selectedTab == DownloadManagerTab.Queue, selectableTargets.filter { it.key in selectedKeys }) },
                    ) { Text(if (selectedTab == DownloadManagerTab.Queue) i18n("移除任務") else i18n("刪除下載內容")) }
                }
                if (busy) {
                    Text(i18n("處理中 {}/{}", progress, lastRemoval?.targets?.size ?: 0))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else if (outcomes.isNotEmpty()) {
                    val failed = outcomes.count { it.error != null }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(i18n("成功 {} 項，失敗 {} 項", outcomes.size - failed, failed), modifier = Modifier.weight(1f))
                        TextButton(onClick = { showResults = true }) { Text(i18n("查看結果")) }
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
                        selectedKeys = emptySet()
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
                        requestRemoval(false, group.items.map { DownloadRemovalTarget(it.key, it.title, it.imageBytes) })
                    },
                    onClearItem = { item ->
                        requestRemoval(false, listOf(DownloadRemovalTarget(item.key, item.title, item.imageBytes)))
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
                            contentReloadToken++
                        }
                    },
                )
            }
        }
    }
    pendingRemoval?.let { request ->
        AlertDialog(
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
            confirmButton = { TextButton(onClick = { executeRemoval(request) }, enabled = !busy) { Text(i18n("確定")) } },
            dismissButton = { TextButton(onClick = { pendingRemoval = null }) { Text(i18n("取消")) } },
        )
    }
    if (showResults) {
        AlertDialog(
            onDismissRequest = { showResults = false },
            title = { Text(i18n("處理結果")) },
            text = {
                LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(outcomes, key = { it.target.key.stableId }) { outcome ->
                        Column {
                            Text(outcome.target.title, fontWeight = FontWeight.Medium)
                            Text(outcome.error?.let { i18n(forumErrorMessage(it)) } ?: i18n("成功"))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showResults = false }) { Text(i18n("關閉")) } },
            dismissButton = {
                if (outcomes.any { it.error != null }) TextButton(onClick = {
                    showResults = false
                    lastRemoval?.let { requestRemoval(it.tasksOnly, outcomes.filter { result -> result.error != null }.map { result -> result.target }) }
                }, enabled = !busy) { Text(i18n("重試失敗項目")) }
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (selectionMode && entry.status.canRemoveTask()) Checkbox(selected, { onToggle() }, enabled = !busy)
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
    state: DownloadContentManagementState,
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
        verticalArrangement = Arrangement.spacedBy(12.dp),
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
                item {
                    DownloadContentControlRow(
                        sortMode = sortMode,
                        sortDescending = sortDescending,
                        filterActive = normalizedFilterKeys.isNotEmpty(),
                        onSortClick = { if (!busy) showSortDialog = true },
                        onFilterClick = { if (!busy) showFilterDialog = true },
                    )
                }
                item { DownloadContentSummaryCard(state.summary) }
                if (groups.isEmpty()) {
                    item {
                        YamiboDownloadCard {
                            Text(i18n("目前沒有已下載內容"), color = colors.textStrong, fontWeight = FontWeight.SemiBold)
                        }
                    }
                } else if (displayedGroups.isEmpty()) {
                    item {
                        YamiboDownloadCard {
                            Text(i18n("沒有符合篩選的下載內容"), color = colors.textStrong, fontWeight = FontWeight.SemiBold)
                        }
                    }
                } else {
                    displayedGroups.forEach { group ->
                        val expanded = group.id in expandedGroups
                        item(key = "group:${group.id}", contentType = "group") {
                            DownloadContentGroupCard(
                                group = group,
                                selectionMode = selectionMode,
                                selectedKeys = selectedKeys,
                                busy = busy,
                                onToggleSelection = { onToggle(group.items.mapTo(mutableSetOf()) { it.key }) },
                                expanded = expanded,
                                onToggleExpanded = {
                                    expandedGroups = if (expanded) expandedGroups - group.id else expandedGroups + group.id
                                },
                            )
                        }
                        if (expanded) {
                            items(group.items, key = { "item:${group.id}:${it.key.stableId}" }, contentType = { "content" }) { item ->
                                YamiboDownloadCard {
                                    DownloadContentItemRow(item, selectionMode, item.key in selectedKeys, busy, { onToggle(setOf(item.key)) }, onClearItem, onRefreshItem)
                                }
                            }
                            if (!selectionMode) item(key = "clear:${group.id}", contentType = "clear") {
                                YamiboDownloadCard {
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
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        YamiboActionChip(
            text = i18n("排序: {} {}", sortMode.downloadedContentSortLabel(), if (sortDescending) "↓" else "↑"),
            onClick = onSortClick,
        )
        YamiboActionChip(
            text = i18n("篩選"),
            onClick = onFilterClick,
            selected = filterActive,
        )
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
                CacheStorageUsage("thread_downloads", i18n("Thread"), summary.threadImageBytes),
                CacheStorageUsage("tag_manga_downloads", i18n("標籤漫畫"), summary.tagMangaImageBytes),
            ),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            i18n(
                "共 {} 項，Thread {} 頁，標籤漫畫 {} 章，圖片 {} 張",
                summary.totalItems,
                summary.threadPages,
                summary.tagMangaChapters,
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
private fun DownloadContentGroupCard(
    group: DownloadedContentGroup,
    selectionMode: Boolean,
    selectedKeys: Set<DownloadTaskKey>,
    busy: Boolean,
    onToggleSelection: () -> Unit,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
) {
    val colors = YamiboTheme.colors
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(180),
    )
    YamiboDownloadCard {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onToggleExpanded),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectionMode) TriStateCheckbox(
                state = when {
                    group.items.all { it.key in selectedKeys } -> ToggleableState.On
                    group.items.any { it.key in selectedKeys } -> ToggleableState.Indeterminate
                    else -> ToggleableState.Off
                },
                onClick = onToggleSelection,
                enabled = !busy,
            )
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
            Text(
                "▼",
                color = colors.brownPrimary,
                fontSize = 12.sp,
                modifier = Modifier.graphicsLayer { rotationZ = arrowRotation },
            )
        }
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
    val colors = YamiboTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode) Checkbox(selected, { onToggle() }, enabled = !busy)
        Column(Modifier.weight(1f)) {
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
        colors = ButtonDefaults.outlinedButtonColors(
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

private suspend fun clearItem(
    key: DownloadTaskKey,
    repository: DownloadRepository,
): Result<Unit> = when (key) {
    is ThreadPageDownloadKey -> repository.clearPage(key)
    is TagMangaChapterDownloadKey -> repository.clearTagMangaChapter(key)
    is RssMangaChapterDownloadKey -> repository.clearRssMangaChapter(key)
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

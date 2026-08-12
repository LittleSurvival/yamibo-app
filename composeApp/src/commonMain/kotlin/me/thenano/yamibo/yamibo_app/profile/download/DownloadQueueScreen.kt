package me.thenano.yamibo.yamibo_app.profile.download

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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.littlesurvival.core.YamiboResult
import io.github.littlesurvival.dto.value.TagId
import io.github.littlesurvival.dto.value.ThreadId
import io.github.littlesurvival.dto.value.UserId
import kotlinx.coroutines.Dispatchers
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
import me.thenano.yamibo.yamibo_app.profile.settings.backup.IBackupSettingsScreen
import me.thenano.yamibo.yamibo_app.repository.DownloadRepository
import me.thenano.yamibo.yamibo_app.repository.download.*
import me.thenano.yamibo.yamibo_app.util.formatDownloadedByteSize
import me.thenano.yamibo.yamibo_app.task.AppTaskKey
import me.thenano.yamibo.yamibo_app.task.isActive
import me.thenano.yamibo.yamibo_app.util.state

private enum class DownloadManagerTab(val title: String) {
    Queue(i18n("下載佇列")),
    Content(i18n("下載內容管理")),
}

private sealed interface DownloadContentManagementState {
    data object Loading : DownloadContentManagementState
    data class Ready(
        val summary: DownloadedContentSummary,
        val groups: List<DownloadedContentGroup>,
    ) : DownloadContentManagementState
    data class Error(val message: String) : DownloadContentManagementState
}

@Composable
fun DownloadQueueScreen() {
    val colors = YamiboTheme.colors
    val navigator = LocalNavigator.current
    val repository = LocalDownloadRepository.current
    val appSettingsRepository = LocalAppSettingsRepository.current
    val downloadedContentRefreshAutoUpdate = appSettingsRepository.downloadedContentRefreshAutoUpdate.state()
    val entries by repository.queue.collectAsState()
    val feedbackController = me.thenano.yamibo.yamibo_app.LocalAppFeedbackController.current
    val appTaskManager = LocalAppTaskManager.current
    val scope = rememberCoroutineScope()
    var summary by remember { mutableStateOf(DownloadQueueSummary()) }
    var folderReady by remember { mutableStateOf(true) }
    var selectedTab by remember { mutableStateOf(DownloadManagerTab.Queue) }
    var contentState by remember { mutableStateOf<DownloadContentManagementState>(DownloadContentManagementState.Loading) }
    var contentReloadToken by remember { mutableStateOf(0) }
    var contentSortMode by rememberSaveable { mutableStateOf(DownloadedContentSortMode.DownloadedAt) }
    var contentSortDescending by rememberSaveable { mutableStateOf(true) }
    var selectedContentFilterKeys by rememberSaveable { mutableStateOf(emptyList<String>()) }

    suspend fun reloadContentManagement() {
        contentState = DownloadContentManagementState.Loading
        contentState = runCatching {
            withContext(Dispatchers.Default) {
                DownloadContentManagementState.Ready(
                    summary = repository.getDownloadedContentSummary(),
                    groups = repository.getDownloadedContentGroups(),
                )
            }
        }.getOrElse { error ->
            Logger.e("DownloadQueueScreen", "Failed to load downloaded content management", error)
            DownloadContentManagementState.Error(error.message ?: i18n("載入下載內容失敗"))
        }
    }

    LaunchedEffect(entries, contentReloadToken) {
        summary = repository.getSummary()
        folderReady = repository.isStorageReady()
        reloadContentManagement()
    }

    Scaffold(
        topBar = {
            YamiboTopBar(
                title = i18n("下載管理"),
                onBack = navigator::pop,
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
            DownloadManagerTabRow(selectedTab) { selectedTab = it }
            when (selectedTab) {
                DownloadManagerTab.Queue -> DownloadQueueTab(
                    entries = entries,
                    summary = summary,
                    folderReady = folderReady,
                    onOpenSettings = { navigator.navigate(IBackupSettingsScreen()) },
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
                    onSelectFilterKeys = { keys -> selectedContentFilterKeys = keys.toList() },
                    onRetryLoad = {
                        scope.launch {
                            contentReloadToken++
                            if (downloadedContentRefreshAutoUpdate) {
                                val refreshed = refreshUpdateAvailableDownloads(entries, repository)
                                if (refreshed > 0) {
                                    reloadContentManagement()
                                    feedbackController.post(i18n("已自動刷新 {} 項可更新內容", refreshed))
                                }
                            }
                        }
                    },
                    onClearGroup = { group ->
                        val handle = appTaskManager.launch(AppTaskKey("download-control:clear-group:${group.id}")) {
                            clearGroup(group, repository)
                                .onSuccess {
                                    reloadContentManagement()
                                    feedbackController.post(i18n("已清除下載內容"))
                                }
                                .onFailure { error ->
                                    Logger.e("DownloadQueueScreen", "Failed to clear download group id=${group.id}", error)
                                    feedbackController.post(error.message ?: i18n("清除失敗"))
                                }
                        }
                        scope.launch {
                            handle.state.first { !it.isActive }
                            reloadContentManagement()
                        }
                    },
                    onClearItem = { item ->
                        val handle = appTaskManager.launch(AppTaskKey("download-control:clear-item:${item.key.stableId}")) {
                            clearItem(item.key, repository)
                                .onSuccess {
                                    reloadContentManagement()
                                    feedbackController.post(i18n("已清除下載內容"))
                                }
                                .onFailure { error ->
                                    Logger.e("DownloadQueueScreen", "Failed to clear download item key=${item.key.stableId}", error)
                                    feedbackController.post(error.message ?: i18n("清除失敗"))
                                }
                        }
                        scope.launch {
                            handle.state.first { !it.isActive }
                            reloadContentManagement()
                        }
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
                                    reloadContentManagement()
                                    feedbackController.post(i18n("已重新載入下載內容"))
                                }
                                else -> feedbackController.post(i18n("重新載入失敗：{}", i18n(result.message())))
                            }
                        }
                        scope.launch {
                            handle.state.first { !it.isActive }
                            reloadContentManagement()
                        }
                    },
                )
            }
        }
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
                        text = tab.title,
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
    summary: DownloadQueueSummary,
    folderReady: Boolean,
    onOpenSettings: () -> Unit,
    onPauseAll: () -> Unit,
    onResumeAll: () -> Unit,
    onRetry: (DownloadQueueEntry) -> Unit,
) {
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
                onPauseAll = onPauseAll,
                onResumeAll = onResumeAll,
            )
        }
        if (!folderReady) {
            item { BackupFolderRequiredCard(onOpenSettings) }
        }
        if (entries.isEmpty()) {
            item { EmptyDownloadQueueCard() }
        } else {
            items(entries, key = { it.key.stableId }) { entry ->
                DownloadQueueEntryCard(entry = entry, onRetry = { onRetry(entry) })
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
private fun BackupFolderRequiredCard(onOpenSettings: () -> Unit) {
    val colors = YamiboTheme.colors
    YamiboDownloadCard {
        Text(i18n("尚未設定下載資料夾"), color = colors.textStrong, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(i18n("下載內容會存放在本地資料備份指定的資料夾。"), color = colors.textDark.copy(alpha = 0.68f), fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))
        SmallQueueButton(i18n("前往設定"), onOpenSettings)
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
            Icon(
                imageVector = if (entry.status == DownloadStatus.Downloaded) YamiboIcons.Downloaded else YamiboIcons.Download,
                contentDescription = null,
                tint = statusColor,
                modifier = Modifier.size(24.dp),
            )
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(entry.title, color = colors.textStrong, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${downloadEntryLabel(entry)} · ${entryDetailLabel(entry)}${entry.message?.let { " · $it" }.orEmpty()}",
                    color = if (entry.status == DownloadStatus.Failed) colors.redAccent else colors.textDark.copy(alpha = 0.68f),
                    fontSize = 12.sp,
                )
            }
            if (entry.status == DownloadStatus.Failed) {
                SmallQueueButton(i18n("重試"), onRetry)
            }
        }
    }
}

@Composable
private fun DownloadContentManagementTab(
    state: DownloadContentManagementState,
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
    var expandedGroups by remember(groups) { mutableStateOf(emptySet<String>()) }
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
                        onSortClick = { showSortDialog = true },
                        onFilterClick = { showFilterDialog = true },
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
                    items(displayedGroups, key = { it.id }) { group ->
                        val expanded = group.id in expandedGroups
                        DownloadContentGroupCard(
                            group = group,
                            expanded = expanded,
                            onToggleExpanded = {
                                expandedGroups = if (expanded) expandedGroups - group.id else expandedGroups + group.id
                            },
                            onClearGroup = { onClearGroup(group) },
                            onClearItem = onClearItem,
                            onRefreshItem = onRefreshItem,
                        )
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
            label = { option -> i18n("{} ({})", option.label, option.count) },
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
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onClearGroup: () -> Unit,
    onClearItem: (DownloadedContentItem) -> Unit,
    onRefreshItem: (DownloadedContentItem) -> Unit,
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
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(tween(180)) + fadeIn(tween(180)),
            exit = shrinkVertically(tween(160)) + fadeOut(tween(160)),
        ) {
            Column(Modifier.fillMaxWidth()) {
                Spacer(Modifier.height(12.dp))
                group.items.forEach { item ->
                    DownloadContentItemRow(item, onClearItem, onRefreshItem)
                }
                Spacer(Modifier.height(8.dp))
                SmallQueueButton(
                    text = if (group.type == DownloadedContentGroupType.TagManga) i18n("清除整個標籤") else i18n("清除整個 Thread"),
                    onClick = onClearGroup,
                    destructive = true,
                )
            }
        }
    }
}

@Composable
private fun DownloadContentItemRow(
    item: DownloadedContentItem,
    onClearItem: (DownloadedContentItem) -> Unit,
    onRefreshItem: (DownloadedContentItem) -> Unit,
) {
    val colors = YamiboTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(item.detail, color = colors.textStrong, fontWeight = FontWeight.Medium, fontSize = 13.sp)
            Text(
                i18n("{} 張圖片 · {}", item.imageCount, formatDownloadedByteSize(item.imageBytes)),
                color = colors.textDark.copy(alpha = 0.62f),
                fontSize = 12.sp,
            )
        }
        SmallQueueButton(i18n("重新載入"), { onRefreshItem(item) })
        Spacer(Modifier.size(6.dp))
        SmallQueueButton(i18n("清除"), { onClearItem(item) }, destructive = true)
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
) {
    val colors = YamiboTheme.colors
    OutlinedButton(
        onClick = onClick,
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

private suspend fun clearGroup(
    group: DownloadedContentGroup,
    repository: DownloadRepository,
): Result<Unit> {
    val firstKey = group.items.firstOrNull()?.key ?: return Result.success(Unit)
    return when (firstKey) {
        is ThreadPageDownloadKey -> repository.clearThread(firstKey)
        is TagMangaChapterDownloadKey -> repository.clearTagManga(TagId(firstKey.tagId))
        is RssMangaChapterDownloadKey -> repository.clearRssManga(firstKey.subscriptionId)
    }
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

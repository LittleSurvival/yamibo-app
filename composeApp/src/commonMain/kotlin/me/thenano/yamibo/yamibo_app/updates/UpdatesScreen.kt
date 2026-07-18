package me.thenano.yamibo.yamibo_app.updates

import YamiboIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.littlesurvival.dto.value.ThreadId
import io.github.littlesurvival.dto.value.UserId
import me.thenano.yamibo.yamibo_app.LocalAppSettingsRepository
import me.thenano.yamibo.yamibo_app.LocalDownloadRepository
import me.thenano.yamibo.yamibo_app.LocalFavoriteUpdateRepository
import me.thenano.yamibo.yamibo_app.LocalFavoriteUpdateRunner
import me.thenano.yamibo.yamibo_app.components.controls.YamiboActionChip
import me.thenano.yamibo.yamibo_app.components.feedback.YamiboEmptyContent
import me.thenano.yamibo.yamibo_app.components.feedback.YamiboErrorContent
import me.thenano.yamibo.yamibo_app.components.feedback.YamiboLoadingContent
import me.thenano.yamibo.yamibo_app.components.navigation.YamiboMainTabIconAction
import me.thenano.yamibo.yamibo_app.components.navigation.YamiboMainTabTopBar
import me.thenano.yamibo.yamibo_app.components.theme.YamiboSnackbarHost
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme
import me.thenano.yamibo.yamibo_app.favorite.updates.FavoriteUpdateRunner
import me.thenano.yamibo.yamibo_app.i18n.i18n
import me.thenano.yamibo.yamibo_app.i18n.localizedLabel
import me.thenano.yamibo.yamibo_app.navigation.ComposableNavigator
import me.thenano.yamibo.yamibo_app.navigation.LocalNavigator
import me.thenano.yamibo.yamibo_app.repository.FavoriteUpdateRepository
import me.thenano.yamibo.yamibo_app.repository.FavoriteStoreRepository
import me.thenano.yamibo.yamibo_app.repository.ReadHistoryRepository
import me.thenano.yamibo.yamibo_app.repository.download.DownloadStatus
import me.thenano.yamibo.yamibo_app.repository.download.ThreadPageDownloadKey
import me.thenano.yamibo.yamibo_app.thread.detail.novel.INovelThreadDetailScreen
import me.thenano.yamibo.yamibo_app.thread.detail.rss.IRssSearchSubscriptionDetailScreen
import me.thenano.yamibo.yamibo_app.thread.detail.tag.ITagDetailScreen
import me.thenano.yamibo.yamibo_app.thread.reader.IThreadReaderScreen
import me.thenano.yamibo.yamibo_app.util.state
import me.thenano.yamibo.yamibo_app.updates.components.*

private sealed interface UpdatesScreenState {
    data object Loading : UpdatesScreenState
    data class Success(
        val events: List<FavoriteUpdateRepository.UpdateEvent>,
        val filters: List<FavoriteUpdateRepository.FidFilter> = emptyList(),
        val categoryFilters: List<FavoriteUpdateRepository.CategoryFilter> = emptyList(),
        val filtersLoaded: Boolean = false,
        val filtersLoading: Boolean = false,
        val scopeTargets: List<FavoriteUpdateRepository.ScopeTarget> = emptyList(),
        val scopeTargetsLoaded: Boolean = false,
        val scopeTargetsLoading: Boolean = false,
    ) : UpdatesScreenState
    data class Error(val message: String) : UpdatesScreenState
}

internal data class UpdateDownloadTargetKey(
    val targetId: Long,
    val authorId: Long?,
)

private data class UpdateDownloadHintState(
    val hasFailedDownload: Boolean = false,
    val hasUpdateAvailable: Boolean = false,
    val hasDownloaded: Boolean = false,
)

internal fun favoriteUpdateThreadTargetKeys(
    events: List<FavoriteUpdateRepository.UpdateEvent>,
): Set<UpdateDownloadTargetKey> =
    events
        .asSequence()
        .filter {
            it.targetType != FavoriteStoreRepository.FavoriteTargetType.TagManga &&
                it.targetType != FavoriteStoreRepository.FavoriteTargetType.RssSearch
        }
        .map { UpdateDownloadTargetKey(it.targetId, it.authorId) }
        .toSet()

internal fun favoriteUpdateAutoRefreshEntries(
    entries: List<me.thenano.yamibo.yamibo_app.repository.download.DownloadQueueEntry>,
    targetKeys: Set<UpdateDownloadTargetKey>,
): List<Pair<me.thenano.yamibo.yamibo_app.repository.download.DownloadQueueEntry, ThreadPageDownloadKey>> =
    entries
        .asSequence()
        .filter { it.status == DownloadStatus.UpdateAvailable }
        .mapNotNull { entry ->
            val key = entry.key as? ThreadPageDownloadKey ?: return@mapNotNull null
            val targetKey = UpdateDownloadTargetKey(
                targetId = key.tid.toLong(),
                authorId = key.authorId?.toLong(),
            )
            if (targetKey in targetKeys) entry to key else null
        }
        .distinctBy { it.second }
        .toList()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdatesScreen() {
    val colors = YamiboTheme.colors
    val favoriteUpdateRepository = LocalFavoriteUpdateRepository.current
    val favoriteUpdateRunner = LocalFavoriteUpdateRunner.current
    val downloadRepository = LocalDownloadRepository.current
    val favoriteUpdateRunState by favoriteUpdateRunner.state.collectAsState()
    val favoriteUpdateRefreshKey = favoriteUpdateRunState.refreshKey()
    val appSettingsRepository = LocalAppSettingsRepository.current
    val favoriteUpdateInterval = appSettingsRepository.favoriteUpdateInterval.state()
    val favoriteUpdateAutoDownload = appSettingsRepository.favoriteUpdateAutoDownload.state()
    val favoriteUpdateHiddenRunId = appSettingsRepository.favoriteUpdateHiddenRunId.state()
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val feedbackController = me.thenano.yamibo.yamibo_app.LocalAppFeedbackController.current

    var state by remember { mutableStateOf<UpdatesScreenState>(UpdatesScreenState.Loading) }
    var isRefreshing by remember { mutableStateOf(false) }
    var isSelectMode by remember { mutableStateOf(false) }
    var selectedEventIds by remember { mutableStateOf(setOf<Long>()) }
    var showGlobalRefreshConfirm by remember { mutableStateOf(false) }
    var showUpdateScopeDialog by remember { mutableStateOf(false) }
    var showResultFilterDialog by remember { mutableStateOf(false) }
    var selectedResultFilterKeys by remember { mutableStateOf(setOf(UPDATE_RESULT_FILTER_ALL)) }

    val updateContent = state as? UpdatesScreenState.Success
    val updateDownloadTargetKeys = remember(updateContent?.events) {
        favoriteUpdateThreadTargetKeys(updateContent?.events.orEmpty())
    }
    val updateDownloadHints by remember(downloadRepository, updateDownloadTargetKeys) {
        downloadRepository.queue
            .map { queue ->
                if (updateDownloadTargetKeys.isEmpty()) {
                    emptyMap()
                } else {
                    queue
                        .asSequence()
                        .mapNotNull { entry ->
                            val key = entry.key as? ThreadPageDownloadKey ?: return@mapNotNull null
                            val targetKey = UpdateDownloadTargetKey(
                                targetId = key.tid.toLong(),
                                authorId = key.authorId?.toLong(),
                            )
                            if (targetKey !in updateDownloadTargetKeys) return@mapNotNull null
                            targetKey to entry.status
                        }
                        .groupBy({ it.first }, { it.second })
                        .mapValues { (_, statuses) ->
                            UpdateDownloadHintState(
                                hasFailedDownload = statuses.any { it == DownloadStatus.Failed },
                                hasUpdateAvailable = statuses.any { it == DownloadStatus.UpdateAvailable },
                                hasDownloaded = statuses.any { it == DownloadStatus.Downloaded },
                            )
                        }
                }
            }
            .distinctUntilChanged()
    }.collectAsState(emptyMap())
    val updateScopeFilterActive = updateContent?.let {
        it.filters.isUpdateScopeFilterRestricted { filter -> filter.enabled } ||
            it.categoryFilters.isUpdateScopeFilterRestricted { filter -> filter.enabled }
    } == true

    suspend fun loadUpdates() {
        val updates = withContext(Dispatchers.Default) {
            UpdatesScreenState.Success(
                events = favoriteUpdateRepository.getActiveEvents(),
            )
        }
        state = updates
    }

    suspend fun loadUpdateFilters() {
        val current = state as? UpdatesScreenState.Success ?: return
        if (current.filtersLoaded || current.filtersLoading) return
        state = current.copy(filtersLoading = true)
        val filters = withContext(Dispatchers.Default) {
            favoriteUpdateRepository.getFidFilters() to favoriteUpdateRepository.getCategoryFilters()
        }
        val latest = state as? UpdatesScreenState.Success ?: return
        state = latest.copy(
            filters = filters.first,
            categoryFilters = filters.second,
            filtersLoaded = true,
            filtersLoading = false,
        )
    }

    suspend fun loadScopeTargetsForDialog(current: UpdatesScreenState.Success) {
        if (current.scopeTargetsLoaded || current.scopeTargetsLoading) return
        state = current.copy(scopeTargetsLoading = true)
        val targets = withContext(Dispatchers.Default) {
            favoriteUpdateRepository.getScopeTargets()
        }
        val latest = state as? UpdatesScreenState.Success ?: return
        state = latest.copy(scopeTargets = targets, scopeTargetsLoaded = true, scopeTargetsLoading = false)
    }

    LaunchedEffect(favoriteUpdateRefreshKey) {
        loadUpdates()
    }

    LaunchedEffect(updateContent?.events) {
        updateContent ?: return@LaunchedEffect
        delay(350)
        loadUpdateFilters()
    }

    LaunchedEffect(updateContent?.events, favoriteUpdateAutoDownload) {
        val threadEvents = updateContent?.events
            ?.filter {
                it.targetType != FavoriteStoreRepository.FavoriteTargetType.TagManga &&
                    it.targetType != FavoriteStoreRepository.FavoriteTargetType.RssSearch
            }
            ?.distinctBy { it.targetId to it.authorId }
            .orEmpty()

        threadEvents.forEach { event ->
            downloadRepository.markThreadUpdateAvailable(
                tid = ThreadId(event.targetId.toInt()),
                authorId = event.authorId?.toInt()?.let(::UserId),
            )
        }

        if (favoriteUpdateAutoDownload) {
            favoriteUpdateAutoRefreshEntries(
                entries = downloadRepository.queue.value,
                targetKeys = favoriteUpdateThreadTargetKeys(threadEvents),
            )
                .forEach { (entry, key) ->
                    downloadRepository.refreshPage(
                        tid = ThreadId(key.tid),
                        title = entry.title,
                        authorId = key.authorId?.let(::UserId),
                        page = key.page,
                    )
                }
        }
    }

    DisposableEffect(isSelectMode, navigator) {
        if (!isSelectMode) {
            onDispose { }
        } else {
            val handler = {
                isSelectMode = false
                selectedEventIds = emptySet()
                true
            }
            navigator.backHandlers.add(handler)
            onDispose { navigator.backHandlers.remove(handler) }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = colors.creamBackground,
        topBar = {
            if (isSelectMode) {
                UpdatesSelectTopBar(
                    onSelectAll = {
                        val success = state as? UpdatesScreenState.Success
                        if (success != null) {
                            selectedEventIds = success.events.map { it.id }.toSet()
                        }
                    },
                    onClearAll = {
                        scope.launch {
                            favoriteUpdateRepository.dismissAllEvents()
                            isSelectMode = false
                            selectedEventIds = emptySet()
                            loadUpdates()
                            feedbackController.post(i18n("已刪除全部更新紀錄"))
                        }
                    },
                    onCancel = {
                        isSelectMode = false
                        selectedEventIds = emptySet()
                    },
                    onDeleteSelected = {
                        if (selectedEventIds.isNotEmpty()) {
                            scope.launch {
                                val deletedCount = selectedEventIds.size
                                favoriteUpdateRepository.dismissEvents(selectedEventIds.toList())
                                isSelectMode = false
                                selectedEventIds = emptySet()
                                loadUpdates()
                                feedbackController.post(i18n("已刪除 {} 項紀錄", deletedCount))
                            }
                        }
                    },
                    selectedCount = selectedEventIds.size
                )
            } else {
                YamiboMainTabTopBar(title = i18n("更新")) {
                    YamiboMainTabIconAction(
                        icon = YamiboIcons.FilterList,
                        contentDescription = i18n("範圍"),
                        onClick = {
                            val current = state as? UpdatesScreenState.Success
                            showUpdateScopeDialog = true
                            if (current != null) {
                                scope.launch {
                                    loadUpdateFilters()
                                    val latest = state as? UpdatesScreenState.Success ?: return@launch
                                    loadScopeTargetsForDialog(latest)
                                }
                            }
                        },
                        iconSize = 26,
                        tint = if (updateScopeFilterActive) colors.orangeAccent else colors.textOnBackground,
                    )
                    YamiboMainTabIconAction(
                        icon = YamiboIcons.Trashcan,
                        contentDescription = i18n("多選刪除"),
                        onClick = {
                            isSelectMode = true
                            selectedEventIds = emptySet()
                        },
                    )
                }
            }
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
                .background(colors.creamBackground),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                when (val current = state) {
                    UpdatesScreenState.Loading -> YamiboLoadingContent()
                    is UpdatesScreenState.Error -> YamiboErrorContent(
                        message = current.message,
                        onRetry = {
                            state = UpdatesScreenState.Loading
                            scope.launch { loadUpdates() }
                        },
                    )
                    is UpdatesScreenState.Success -> PullToRefreshBox(
                        isRefreshing = isRefreshing,
                        onRefresh = {
                            isRefreshing = true
                            scope.launch {
                                loadUpdates()
                                isRefreshing = false
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        val resultFilterOptions = remember(current.events) {
                            buildUpdateResultFilterOptions(current.events)
                        }
                        val normalizedResultFilterKeys = remember(selectedResultFilterKeys, resultFilterOptions) {
                            normalizeUpdateResultFilterKeys(selectedResultFilterKeys, resultFilterOptions)
                        }
                        val filteredUpdateEvents = remember(current.events, normalizedResultFilterKeys, resultFilterOptions) {
                            filterUpdateEvents(current.events, normalizedResultFilterKeys, resultFilterOptions)
                        }
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 24.dp),
                        ) {
                            item {
                                FavoriteUpdateHeader(
                                    runState = favoriteUpdateRunState,
                                    onGlobalFavoriteUpdate = { showGlobalRefreshConfirm = true },
                                    onCancelFavoriteUpdate = { runId ->
                                        scope.launch {
                                            favoriteUpdateRunner.cancelUpdate(runId)
                                            loadUpdates()
                                            feedbackController.post(i18n("已取消收藏更新檢查"), duration = me.thenano.yamibo.yamibo_app.feedback.AppFeedbackDuration.Short)
                                        }
                                    },
                                    onInterruptFavoriteUpdate = { runId ->
                                        scope.launch {
                                            favoriteUpdateRunner.interruptUpdate(runId)
                                            loadUpdates()
                                            feedbackController.post(i18n("已中斷收藏更新檢查"), duration = me.thenano.yamibo.yamibo_app.feedback.AppFeedbackDuration.Short)
                                        }
                                    },
                                    onResumeFavoriteUpdate = {
                                        scope.launch {
                                            when (val result = favoriteUpdateRunner.resumeInterruptedUpdate()) {
                                                is FavoriteUpdateRunner.LaunchResult.Started -> {
                                                    appSettingsRepository.favoriteUpdateHiddenRunId.setValue("")
                                                    loadUpdates()
                                                    feedbackController.post(i18n("繼續檢查收藏更新"), duration = me.thenano.yamibo.yamibo_app.feedback.AppFeedbackDuration.Short)
                                                }
                                                is FavoriteUpdateRunner.LaunchResult.Rejected -> {
                                                    feedbackController.post(i18n(result.reason), duration = me.thenano.yamibo.yamibo_app.feedback.AppFeedbackDuration.Short)
                                                }
                                                null -> {
                                                    feedbackController.post(i18n("沒有可繼續的收藏更新任務"), duration = me.thenano.yamibo.yamibo_app.feedback.AppFeedbackDuration.Short)
                                                }
                                            }
                                        }
                                    },
                                    favoriteUpdateInterval = favoriteUpdateInterval,
                                    onFavoriteUpdateIntervalChange = { interval ->
                                        scope.launch {
                                            appSettingsRepository.favoriteUpdateInterval.setValue(interval)
                                            favoriteUpdateRunner.schedulePeriodicUpdate(interval)
                                            feedbackController.post(
                                                i18n("刷新週期已改為 {}", interval.localizedLabel()),
                                                duration = me.thenano.yamibo.yamibo_app.feedback.AppFeedbackDuration.Short,
                                            )
                                        }
                                    },
                                    favoriteUpdateHiddenRunId = favoriteUpdateHiddenRunId,
                                    onHideFavoriteUpdateStatus = { runId ->
                                        appSettingsRepository.favoriteUpdateHiddenRunId.setValue(runId)
                                    },
                                    resultFilterActive = isUpdateResultFilterRestricted(normalizedResultFilterKeys, resultFilterOptions),
                                    resultFilterLabel = updateResultFilterLabel(normalizedResultFilterKeys, resultFilterOptions),
                                    onShowResultFilter = { showResultFilterDialog = true },
                                )
                            }
                            if (filteredUpdateEvents.isEmpty()) {
                                item {
                                    YamiboEmptyContent(
                                        message = i18n("沒有偵測到更新"),
                                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 80.dp)
                                    )
                                }
                            } else {
                                items(filteredUpdateEvents, key = { it.id }) { event ->
                                    val downloadHintState = updateDownloadHints[
                                        UpdateDownloadTargetKey(event.targetId, event.authorId)
                                    ]
                                    FavoriteUpdateCard(
                                        event = event,
                                        isSelected = selectedEventIds.contains(event.id),
                                        downloadHint = when {
                                            downloadHintState?.hasFailedDownload == true -> i18n("下載狀態需處理")
                                            downloadHintState?.hasUpdateAvailable == true -> i18n("離線頁可刷新")
                                            downloadHintState?.hasDownloaded == true -> i18n("已下載")
                                            else -> null
                                        },
                                        downloadHintIsError = downloadHintState?.hasFailedDownload == true,
                                        onClick = {
                                            if (isSelectMode) {
                                                selectedEventIds = if (event.id in selectedEventIds) {
                                                    selectedEventIds - event.id
                                                } else {
                                                    selectedEventIds + event.id
                                                }
                                            } else {
                                                scope.launch { favoriteUpdateRepository.markEventRead(event.id) }
                                                navigateFavoriteUpdateEvent(event, navigator)
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showGlobalRefreshConfirm) {
        AlertDialog(
            onDismissRequest = { showGlobalRefreshConfirm = false },
            title = { Text(i18n("全域刷新收藏更新")) },
            text = { Text(i18n("將重新檢查所有收藏並建立新的更新任務。網站維護中可能會產生大量錯誤記錄。")) },
            confirmButton = {
                YamiboActionChip(text = i18n("開始刷新"), onClick = {
                    showGlobalRefreshConfirm = false
                    scope.launch {
                        when (val result = favoriteUpdateRunner.startGlobalRefresh()) {
                            is FavoriteUpdateRunner.LaunchResult.Started -> {
                                appSettingsRepository.favoriteUpdateHiddenRunId.setValue("")
                                feedbackController.post(
                                    i18n("開始全域刷新收藏更新"),
                                    duration = me.thenano.yamibo.yamibo_app.feedback.AppFeedbackDuration.Short
                                )
                            }
                            is FavoriteUpdateRunner.LaunchResult.Rejected -> {
                                feedbackController.post(i18n(result.reason), duration = me.thenano.yamibo.yamibo_app.feedback.AppFeedbackDuration.Short)
                            }
                        }
                    }
                })
            },
            dismissButton = { YamiboActionChip(text = i18n("取消"), onClick = { showGlobalRefreshConfirm = false }) },
            containerColor = colors.creamSurface,
            titleContentColor = colors.textStrong,
            textContentColor = colors.textDark,
        )
    }

    val updateSuccess = state as? UpdatesScreenState.Success
    if (showResultFilterDialog && updateSuccess != null) {
        val options = buildUpdateResultFilterOptions(updateSuccess.events)
        UpdateResultFilterDialog(
            options = options,
            selectedKeys = normalizeUpdateResultFilterKeys(selectedResultFilterKeys, options),
            onDismiss = { showResultFilterDialog = false },
            onConfirm = { selected ->
                selectedResultFilterKeys = normalizeUpdateResultFilterKeys(selected, options)
                showResultFilterDialog = false
            },
        )
    }

    if (showUpdateScopeDialog && updateSuccess != null) {
        if (!updateSuccess.filtersLoaded || updateSuccess.filtersLoading ||
            !updateSuccess.scopeTargetsLoaded || updateSuccess.scopeTargetsLoading
        ) {
            LaunchedEffect(showUpdateScopeDialog, updateSuccess) {
                loadUpdateFilters()
                val latest = state as? UpdatesScreenState.Success ?: return@LaunchedEffect
                loadScopeTargetsForDialog(latest)
            }
            AlertDialog(
                onDismissRequest = { showUpdateScopeDialog = false },
                title = { Text(i18n("範圍")) },
                text = { Text(i18n("正在載入...")) },
                confirmButton = { YamiboActionChip(text = i18n("取消"), onClick = { showUpdateScopeDialog = false }) },
                containerColor = colors.creamSurface,
                titleContentColor = colors.textStrong,
                textContentColor = colors.textDark,
            )
        } else {
            FavoriteUpdateScopeDialog(
                forumFilters = updateSuccess.filters,
                categoryFilters = updateSuccess.categoryFilters,
                scopeTargets = updateSuccess.scopeTargets,
                onDismiss = { showUpdateScopeDialog = false },
                onConfirm = { forumChanges, categoryChanges ->
                    val newFilters = updateSuccess.filters.map { filter ->
                        forumChanges[filter.fid]?.let { filter.copy(enabled = it) } ?: filter
                    }
                    val newCategoryFilters = updateSuccess.categoryFilters.map { filter ->
                        categoryChanges[filter.categoryId]?.let { filter.copy(enabled = it) } ?: filter
                    }
                    state = updateSuccess.copy(
                        filters = newFilters,
                        categoryFilters = newCategoryFilters,
                    )
                    scope.launch {
                        forumChanges.forEach { (fid, enabled) ->
                            favoriteUpdateRepository.setFidEnabled(fid, enabled)
                        }
                        categoryChanges.forEach { (categoryId, enabled) ->
                            favoriteUpdateRepository.setCategoryEnabled(categoryId, enabled)
                        }
                        loadUpdates()
                        showUpdateScopeDialog = false
                    }
                },
            )
        }
    }
}

private inline fun <T> List<T>.isUpdateScopeFilterRestricted(isEnabled: (T) -> Boolean): Boolean {
    if (isEmpty()) return false
    val enabledCount = count(isEnabled)
    return enabledCount in 1 until size
}

private fun FavoriteUpdateRepository.RunState.refreshKey(): String = when (this) {
    FavoriteUpdateRepository.RunState.Idle -> "idle"
    is FavoriteUpdateRepository.RunState.Running -> "running:${snapshot.detectedCount}"
    is FavoriteUpdateRepository.RunState.Interrupted -> "interrupted:${snapshot.detectedCount}"
    is FavoriteUpdateRepository.RunState.Failed -> "failed:${snapshot.detectedCount}"
    is FavoriteUpdateRepository.RunState.Completed -> "completed:${snapshot.detectedCount}"
}

private fun navigateFavoriteUpdateEvent(
    event: FavoriteUpdateRepository.UpdateEvent,
    navigator: ComposableNavigator,
) {
    when (event.targetType) {
        FavoriteStoreRepository.FavoriteTargetType.TagManga -> navigator.navigate(
            ITagDetailScreen(
                tagId = io.github.littlesurvival.dto.value.TagId(event.targetId.toInt()),
                title = event.title,
            )
        )
        FavoriteStoreRepository.FavoriteTargetType.ThreadNovel -> navigator.navigate(
            INovelThreadDetailScreen(
                tid = io.github.littlesurvival.dto.value.ThreadId(event.targetId.toInt()),
                title = event.title,
                authorId = event.authorId?.toInt()?.let { io.github.littlesurvival.dto.value.UserId(it) },
            )
        )
        FavoriteStoreRepository.FavoriteTargetType.ThreadNormal -> navigator.navigate(
            IThreadReaderScreen(
                tid = io.github.littlesurvival.dto.value.ThreadId(event.targetId.toInt()),
                title = event.title,
                threadType = ReadHistoryRepository.ThreadEntryType.Normal,
            )
        )
        FavoriteStoreRepository.FavoriteTargetType.RssSearch -> navigator.navigate(
            IRssSearchSubscriptionDetailScreen(subscriptionId = event.targetId)
        )
    }
}

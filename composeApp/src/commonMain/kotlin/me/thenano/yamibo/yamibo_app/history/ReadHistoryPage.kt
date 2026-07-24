package me.thenano.yamibo.yamibo_app.history

import androidx.compose.animation.*
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.littlesurvival.dto.model.PageNav
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.thenano.yamibo.yamibo_app.*
import me.thenano.yamibo.yamibo_app.components.controls.YamiboActionChip
import me.thenano.yamibo.yamibo_app.components.controls.YamiboMultiSelectDialog
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme
import me.thenano.yamibo.yamibo_app.favorite.*
import me.thenano.yamibo.yamibo_app.forum.components.PageNavigation
import me.thenano.yamibo.yamibo_app.history.components.*
import me.thenano.yamibo.yamibo_app.i18n.i18n
import me.thenano.yamibo.yamibo_app.navigation.LocalNavigator
import me.thenano.yamibo.yamibo_app.repository.ContentCoverRepository
import me.thenano.yamibo.yamibo_app.repository.FavoriteStoreRepository
import me.thenano.yamibo.yamibo_app.repository.ReadHistoryRepository
import me.thenano.yamibo.yamibo_app.repository.ReadHistoryRepository.ThreadReadingHistory
import me.thenano.yamibo.yamibo_app.thread.reader.IImageReaderScreen
import me.thenano.yamibo.yamibo_app.thread.reader.IThreadReaderScreen
import kotlin.math.ceil
import kotlin.time.Duration.Companion.milliseconds

private const val PAGE_SIZE = 20

@Composable
fun ReadHistoryPage(reTapToken: Int = 0) {
    val colors = YamiboTheme.colors
    val appSettingsRepository = LocalAppSettingsRepository.current
    val readHistoryRepo = LocalReadHistoryRepository.current
    val favoriteRepository = LocalFavoriteRepository.current
    val favoriteSyncRepository = LocalFavoriteSyncRepository.current
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val feedbackController = LocalAppFeedbackController.current
    val confirmationController = LocalAppConfirmationController.current
    val appTaskManager = LocalAppTaskManager.current
    val allLabel = i18n("全部")
    val filterPrefix = i18n("篩選")

    var state by remember { mutableStateOf<HistoryState>(HistoryState.Loading) }
    var currentPage by remember { mutableIntStateOf(1) }
    var mode by remember { mutableStateOf(PageMode.Normal) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedItems by remember { mutableStateOf(setOf<ReadHistoryRepository.AnyReadingHistory>()) }
    var selectedFilters by remember { mutableStateOf<Set<ReadHistoryRepository.HistoryFilter>>(emptySet()) }
    var filterCounts by remember { mutableStateOf<List<ReadHistoryRepository.HistoryFilterCount>>(emptyList()) }
    var showFilterDialog by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    var favoriteDialogTarget by remember { mutableStateOf<FavoriteTargetPayload?>(null) }
    var favoriteDialogCategorySelection by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var favoriteDialogSelection by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var favoriteDialogCategories by remember {
        mutableStateOf<List<FavoriteStoreRepository.FavoriteCategory>>(emptyList())
    }
    var favoriteCollectionOptions by remember {
        mutableStateOf<List<FavoriteStoreRepository.FavoriteCollectionOption>>(emptyList())
    }
    var favoriteRefreshToken by remember { mutableIntStateOf(0) }
    val favoriteRepositoryRevision by favoriteRepository.favoriteItemRevision.collectAsState()
    val effectiveFavoriteRefreshToken = favoriteRefreshToken + favoriteRepositoryRevision.toInt()
    var pendingFavoriteRemovalTarget by remember { mutableStateOf<FavoriteTargetPayload?>(null) }
    var pendingFavoriteRemovalSelection by remember { mutableStateOf<FavoriteLocationSelection?>(null) }
    var pendingFavoriteRemovalSuccessMessage by remember { mutableStateOf(i18n("已移除收藏")) }
    var showFavoriteRemovalConfirm by remember { mutableStateOf(false) }
    var showFavoriteMultiPathDialog by remember { mutableStateOf(false) }
    var showFavoriteAddSyncConfirm by remember { mutableStateOf(false) }
    var showFavoriteRemoveSyncConfirm by remember { mutableStateOf(false) }

    /** Re-tap History tab → open the topmost item's reader */
    LaunchedEffect(reTapToken) {
        if (reTapToken == 0) return@LaunchedEffect
        val topItem = (state as? HistoryState.Success)?.items?.firstOrNull() ?: return@LaunchedEffect
        when (topItem) {
            is ThreadReadingHistory -> navigator.navigate(
                IThreadReaderScreen(
                    tid = topItem.threadId,
                    title = topItem.threadName,
                    threadType = topItem.threadType,
                    authorId = topItem.authorId,
                    initialPage = topItem.page
                )
            )
            is ReadHistoryRepository.TagMangaReadingHistory -> navigator.navigate(
                IImageReaderScreen(
                    tid = topItem.threadId,
                    postId = null,
                    fid = null,
                    threadTitle = topItem.threadTitle,
                    authorId = null,
                    imageList = emptyList(),
                    tagId = topItem.tagId,
                    tagName = topItem.tagName,
                    tagPage = topItem.tagPage,
                    tagThreads = emptyList(),
                    initialPage = topItem.threadImagePageIndex + 1
                )
            )
            is ReadHistoryRepository.TagCatalogReadingHistory -> navigator.navigate(
                IThreadReaderScreen(
                    tid = topItem.threadId,
                    title = topItem.threadTitle,
                    threadType = ReadHistoryRepository.ThreadEntryType.Normal,
                    authorId = topItem.authorId,
                    initialPage = topItem.threadPage,
                    targetPid = topItem.postId,
                    catalogCoverTargetType = ContentCoverRepository.TargetType.TagManga,
                    catalogCoverTargetId = topItem.tagId.value.toLong(),
                    catalogTagId = topItem.tagId,
                    catalogTagName = topItem.tagName,
                    catalogTagPage = topItem.tagPage,
                )
            )
            is ReadHistoryRepository.RssSearchReadingHistory -> navigator.navigate(
                IImageReaderScreen(
                    tid = topItem.threadId,
                    postId = null,
                    fid = null,
                    threadTitle = topItem.threadTitle,
                    authorId = null,
                    imageList = emptyList(),
                    initialPage = topItem.threadImagePageIndex + 1,
                    rssSubscriptionId = topItem.subscriptionId,
                    rssTitle = topItem.subscriptionTitle,
                    rssQuery = topItem.subscriptionQuery,
                    rssPage = topItem.subscriptionPage,
                    rssThreads = emptyList(),
                )
            )
            is ReadHistoryRepository.RssCatalogReadingHistory -> navigator.navigate(
                IThreadReaderScreen(
                    tid = topItem.threadId,
                    title = topItem.threadTitle,
                    threadType = ReadHistoryRepository.ThreadEntryType.Normal,
                    authorId = topItem.authorId,
                    initialPage = topItem.threadPage,
                    targetPid = topItem.postId,
                    catalogCoverTargetType = ContentCoverRepository.TargetType.RssSearch,
                    catalogCoverTargetId = topItem.subscriptionId,
                    catalogRssSubscriptionId = topItem.subscriptionId,
                    catalogRssTitle = topItem.subscriptionTitle,
                    catalogRssQuery = topItem.subscriptionQuery,
                    catalogRssPage = topItem.subscriptionPage,
                )
            )
            else -> {}
        }
    }

    suspend fun refreshFilterCounts() {
        val refreshedCounts = withContext(Dispatchers.Default) {
            val actualCounts = readHistoryRepo.getCombinedHistoryFilterCounts()
            actualCounts
                .groupBy { it.filter }
                .map { (filter, counts) ->
                    ReadHistoryRepository.HistoryFilterCount(
                        filter = filter,
                        label = when (filter) {
                            ReadHistoryRepository.HistoryFilter.All -> allLabel
                            ReadHistoryRepository.HistoryFilter.Tag -> "Tag"
                            ReadHistoryRepository.HistoryFilter.Rss -> "RSS"
                            is ReadHistoryRepository.HistoryFilter.Forum -> counts
                                .maxBy { it.count }
                                .label
                        },
                        count = counts.sumOf { it.count },
                    )
                }
                .filter { it.count > 0L }
                .sortedWith(
                    compareByDescending<ReadHistoryRepository.HistoryFilterCount> {
                        it.filter == ReadHistoryRepository.HistoryFilter.All
                    }.thenByDescending { it.count }.thenBy { it.label }
                )
        }
        filterCounts = refreshedCounts
        val availableFilters = refreshedCounts.mapTo(mutableSetOf()) { it.filter }
        selectedFilters = normalizeHistoryFilters(selectedFilters)
            .filterTo(mutableSetOf()) { it in availableFilters }
    }

    suspend fun loadPage(page: Int) {
        state = HistoryState.Loading
        try {
            refreshFilterCounts()
            val count = readHistoryRepo.getCombinedHistoryCountByFilters(selectedFilters)
            if (count == 0L) {
                state = HistoryState.Empty
                return
            }
            state = HistoryState.Success(
                items = readHistoryRepo.getCombinedHistoryPageByFilters(selectedFilters, page, PAGE_SIZE),
                totalCount = count,
                currentPage = page
            )
            currentPage = page
        } catch (e: Exception) {
            Logger.e("ReadHistoryPage", "Failed to load read history page=$page filters=${selectedFilters.joinToString()}", e)
            state = HistoryState.Error(e.message ?: i18n("載入失敗"))
        }
    }

    suspend fun doSearch(query: String, page: Int = 1) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        state = HistoryState.Loading
        try {
            val count = readHistoryRepo.searchCombinedHistoryCount(trimmed)
            if (count == 0L) {
                state = HistoryState.Empty
                return
            }
            state = HistoryState.Success(
                items = readHistoryRepo.searchCombinedHistory(trimmed, page, PAGE_SIZE),
                totalCount = count,
                currentPage = page
            )
            currentPage = page
        } catch (e: Exception) {
            Logger.e("ReadHistoryPage", "Failed to search read history page=$page", e)
            state = HistoryState.Error(e.message ?: i18n("搜尋失敗"))
        }
    }

    fun threadPayload(history: ThreadReadingHistory): FavoriteTargetPayload.Thread {
        return FavoriteTargetPayload.Thread(
            tid = history.threadId,
            title = history.threadName,
            threadType = history.threadType,
            authorId = history.authorId,
            coverUrl = history.threadCover,
            lastUpdatedTime = history.lastUpdatedTime,
            forumId = history.forumId,
            forumName = history.forumName
        )
    }

    fun launchFavoriteTask(
        action: String,
        target: FavoriteTargetPayload,
        operation: suspend () -> Unit,
    ) {
        appTaskManager.launch(
            key = me.thenano.yamibo.yamibo_app.task.AppTaskKey("favorite:$action:${target.taskIdentity()}"),
            operation = operation,
        )
    }

    suspend fun openFavoriteDialogWithSelection(target: FavoriteTargetPayload) {
        favoriteDialogCategories = favoriteRepository.getCategories()
        favoriteCollectionOptions = favoriteRepository.getCollectionOptions()
        val selection = favoriteRepository.getFavoriteLocationSelection(target)
        favoriteDialogCategorySelection = selection.categoryIds
        favoriteDialogSelection = selection.collectionIds
        favoriteDialogTarget = target
    }

    suspend fun completeFavoriteAdd(target: FavoriteTargetPayload, syncToRemote: Boolean) {
        val syncResult = withContext(Dispatchers.Default) {
            addFavoriteAndMaybeSync(favoriteRepository, favoriteSyncRepository, target, syncToRemote)
        }
        val message = when {
            syncResult == null -> i18n("已加入收藏，預設存入未分類")
            syncResult.success -> i18n("已加入收藏，{}", (syncResult.message?.let { i18n(it) }?.takeIf { it.isNotBlank() } ?: i18n("已同步到百合會。")))
            else -> i18n("已加入收藏，但同步失敗：{}", (syncResult.message?.let { i18n(it) }?.takeIf { it.isNotBlank() } ?: i18n("請稍後再試")))
        }
        feedbackController.post(message)
    }

    suspend fun completeSavedFavoriteSync(target: FavoriteTargetPayload, syncToRemote: Boolean) {
        val feedbackGroup = "favorite-sync:${target.taskIdentity()}"
        if (syncToRemote) {
            feedbackController.post(i18n("正在同步到百合會..."), groupKey = feedbackGroup)
        }
        val syncResult = withContext(Dispatchers.Default) {
            syncExistingFavoriteIfRequested(favoriteRepository, favoriteSyncRepository, target, syncToRemote)
        }
        val message = when {
            syncResult == null -> i18n("已加入本地收藏，預設存入未分類")
            syncResult.success -> i18n("已加入本地收藏，{}", (syncResult.message?.let { i18n(it) }?.takeIf { it.isNotBlank() } ?: i18n("已同步到百合會。")))
            else -> i18n("已加入本地收藏，但同步到百合會失敗：{}", (syncResult.message?.let { i18n(it) }?.takeIf { it.isNotBlank() } ?: i18n("請稍後再試")))
        }
        feedbackController.post(message, groupKey = feedbackGroup)
    }

    suspend fun completeFavoriteRemoval(target: FavoriteTargetPayload, removeRemote: Boolean) {
        completeFavoriteRemovalWithFeedback(
            favoriteRepository = favoriteRepository,
            favoriteSyncRepository = favoriteSyncRepository,
            target = target,
            removeRemote = removeRemote,
            feedbackController = feedbackController,
            confirmationController = confirmationController,
            appTaskManager = appTaskManager,
            successMessage = pendingFavoriteRemovalSuccessMessage,
            failureMessage = i18n("移除收藏失敗"),
        )
    }

    suspend fun maybePromptRemoteRemoval(target: FavoriteTargetPayload) {
        val shouldPromptRemote = target.supportsRemoteWebsiteSync() &&
            hasRemoteFavoriteForTarget(favoriteRepository, favoriteSyncRepository, target)
        when {
            shouldPromptRemote && appSettingsRepository.favoriteRemoveSyncPromptEnabled.getValue() -> {
                showFavoriteRemoveSyncConfirm = true
            }
            else -> {
                completeFavoriteRemoval(
                    target = target,
                    removeRemote = shouldPromptRemote && appSettingsRepository.favoriteRemoveSyncDefault.getValue(),
                )
            }
        }
    }

    suspend fun toggleFavoriteQuickWithFeedback(target: FavoriteTargetPayload) {
        val selection = favoriteRepository.getFavoriteLocationSelection(target)
        if (selection.item != null) {
            pendingFavoriteRemovalTarget = target
            pendingFavoriteRemovalSelection = selection
            pendingFavoriteRemovalSuccessMessage = i18n("已移除收藏")
            if (appSettingsRepository.skipFavoriteRemovalConfirm.getValue()) {
                if (selection.paths.size > 1) {
                    showFavoriteMultiPathDialog = true
                } else {
                    maybePromptRemoteRemoval(target)
                }
            } else {
                showFavoriteRemovalConfirm = true
            }
            return
        }

        if (target.supportsRemoteWebsiteSync() && appSettingsRepository.favoriteAddSyncPromptEnabled.getValue()) {
            favoriteRepository.saveFavorite(target)
            favoriteRefreshToken += 1
            pendingFavoriteRemovalTarget = target
            showFavoriteAddSyncConfirm = true
        } else {
            completeFavoriteAdd(
                target = target,
                syncToRemote = target.supportsRemoteWebsiteSync() && appSettingsRepository.favoriteAddSyncDefault.getValue(),
            )
        }
    }

    LaunchedEffect(navigator.stack.size) {
        if (navigator.currentScreen is IMainScreen) {
            // Let the popped reader dispose and persist its final visible anchor before reading history again.
            delay(320.milliseconds)
            if (mode == PageMode.Search && searchQuery.isNotBlank()) {
                doSearch(searchQuery, currentPage)
            } else if (mode != PageMode.Select) {
                loadPage(currentPage)
            }
        }
    }

    DisposableEffect(mode, navigator) {
        if (mode == PageMode.Normal) {
            onDispose { }
        } else {
            val handler = {
                when (mode) {
                    PageMode.Search -> {
                        searchQuery = ""
                        mode = PageMode.Normal
                        scope.launch { loadPage(1) }
                        true
                    }

                    PageMode.Select -> {
                        selectedItems = emptySet()
                        mode = PageMode.Normal
                        true
                    }

                    PageMode.Normal -> false
                }
            }
            navigator.backHandlers.add(handler)
            onDispose { navigator.backHandlers.remove(handler) }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.creamBackground)
    ) {
        AnimatedContent(
            modifier = Modifier.fillMaxWidth(),
            targetState = mode,
            transitionSpec = {
                (fadeIn(animationSpec = spring(stiffness = 500f)) + slideInVertically { -it / 4 }) togetherWith
                    (fadeOut(animationSpec = spring(stiffness = 700f)) + slideOutVertically { -it / 4 })
            },
            label = "history_top_bar"
        ) { currentMode ->
            when (currentMode) {
                PageMode.Normal -> NormalTopBar(
                    onSearch = { mode = PageMode.Search },
                    onMultiSelect = {
                        selectedItems = emptySet()
                        mode = PageMode.Select
                    }
                )

                PageMode.Search -> SearchTopBar(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    onSearch = { scope.launch { doSearch(searchQuery) } },
                    onBack = {
                        searchQuery = ""
                        mode = PageMode.Normal
                        scope.launch { loadPage(1) }
                    },
                    focusRequester = focusRequester
                )

                PageMode.Select -> SelectTopBar(
                    onSelectAll = {
                        val current = state as? HistoryState.Success ?: return@SelectTopBar
                        selectedItems = current.items.toSet()
                    },
                    onClearAll = {
                        scope.launch {
                            readHistoryRepo.deleteAllCombinedHistory()
                            selectedItems = emptySet()
                            mode = PageMode.Normal
                            state = HistoryState.Empty
                            feedbackController.post(i18n("已刷新閱讀歷史紀錄"))
                        }
                    },
                    onCancel = {
                        selectedItems = emptySet()
                        mode = PageMode.Normal
                    },
                    onDeleteSelected = {
                        if (selectedItems.isNotEmpty()) {
                            scope.launch {
                                val deletedAmount = selectedItems.size
                                readHistoryRepo.deleteCombinedHistoryBatch(selectedItems.toList())
                                selectedItems = emptySet()
                                mode = PageMode.Normal
                                loadPage(1)
                                feedbackController.post(i18n("已刪除 {} 項紀錄", deletedAmount))
                            }
                        }
                    },
                    selectedCount = selectedItems.size
                )
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (val current = state) {
                is HistoryState.Loading -> LoadingContent()
                is HistoryState.Empty -> EmptyContent(mode)
                is HistoryState.Error -> ErrorContent(current.message) { scope.launch { loadPage(currentPage) } }
                is HistoryState.Success -> {
                    val totalPages = ceil(current.totalCount.toDouble() / PAGE_SIZE).toInt()
                    key(current.currentPage) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp)
                        ) {
                            groupByDate(current.items).forEachIndexed { index, (dateLabel, entries) ->
                                item(key = "header_$dateLabel") {
                                    Row(
                                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)
                                            .fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = dateLabel,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = colors.textDark.copy(alpha = 0.5f),
                                            modifier = Modifier.weight(1f),
                                        )
                                        if (index == 0 && mode == PageMode.Normal) {
                                            val filterLabel = selectedHistoryFilterLabel(
                                                selectedFilters = selectedFilters,
                                                filterCounts = filterCounts,
                                                allLabel = allLabel,
                                            )
                                            YamiboActionChip("$filterPrefix: $filterLabel", onClick = { showFilterDialog = true })
                                        }
                                    }
                                }
                                items(entries, key = { itemKey(it) }) { history ->
                                    when (history) {
                                        is ThreadReadingHistory -> ThreadHistoryItem(
                                            history = history,
                                            pageMode = mode,
                                            selectedItems = selectedItems,
                                            onToggleSelection = {
                                                selectedItems =
                                                    if (history in selectedItems) selectedItems - history else selectedItems + history
                                            },
                                            onDelete = {
                                                scope.launch {
                                                    readHistoryRepo.deleteHistoryBatch(listOf(history))
                                                    loadPage(currentPage)
                                                    feedbackController.post(i18n("已刪除這筆紀錄"))
                                                }
                                            },
                                            onFavorite = {
                                                scope.launch {
                                                    toggleFavoriteQuickWithFeedback(
                                                        threadPayload(
                                                            history
                                                        )
                                                    )
                                                }
                                            },
                                            onFavoriteLongPress = {
                                                scope.launch {
                                                    openFavoriteDialogWithSelection(
                                                        threadPayload(history)
                                                    )
                                                }
                                            },
            favoriteRefreshToken = effectiveFavoriteRefreshToken,
                                            navigator = navigator
                                        )
                                        is ReadHistoryRepository.TagMangaReadingHistory -> TagHistoryItem(
                                            history = history,
                                            pageMode = mode,
                                            selectedItems = selectedItems,
                                            onToggleSelection = {
                                                selectedItems =
                                                    if (history in selectedItems) selectedItems - history else selectedItems + history
                                            },
                                            onDelete = {
                                                scope.launch {
                                                    readHistoryRepo.deleteMangaTagHistory(history.tagId)
                                                    loadPage(currentPage)
                                                    feedbackController.post(i18n("已刪除這筆紀錄"))
                                                }
                                            },
                                            onFavorite = {
                                                scope.launch {
                                                    toggleFavoriteQuickWithFeedback(
                                                        FavoriteTargetPayload.TagManga(
                                                            tagId = history.tagId,
                                                            tagName = history.tagName,
                                                            coverUrl = history.coverUrl
                                                        )
                                                    )
                                                }
                                            },
                                            onFavoriteLongPress = {
                                                scope.launch {
                                                    openFavoriteDialogWithSelection(
                                                        FavoriteTargetPayload.TagManga(
                                                            tagId = history.tagId,
                                                            tagName = history.tagName,
                                                            coverUrl = history.coverUrl
                                                        )
                                                    )
                                                }
                                            },
            favoriteRefreshToken = effectiveFavoriteRefreshToken,
                                            navigator = navigator
                                        )
                                        is ReadHistoryRepository.TagCatalogReadingHistory -> TagCatalogHistoryItem(
                                            history = history,
                                            pageMode = mode,
                                            selectedItems = selectedItems,
                                            onToggleSelection = {
                                                selectedItems =
                                                    if (history in selectedItems) selectedItems - history else selectedItems + history
                                            },
                                            onDelete = {
                                                scope.launch {
                                                    readHistoryRepo.deleteTagCatalogThreadHistory(history.tagId)
                                                    loadPage(currentPage)
                                                    feedbackController.post(i18n("已刪除這筆紀錄"))
                                                }
                                            },
                                            onFavorite = {
                                                scope.launch {
                                                    toggleFavoriteQuickWithFeedback(
                                                        FavoriteTargetPayload.TagManga(
                                                            tagId = history.tagId,
                                                            tagName = history.tagName,
                                                            coverUrl = history.coverUrl
                                                        )
                                                    )
                                                }
                                            },
                                            onFavoriteLongPress = {
                                                scope.launch {
                                                    openFavoriteDialogWithSelection(
                                                        FavoriteTargetPayload.TagManga(
                                                            tagId = history.tagId,
                                                            tagName = history.tagName,
                                                            coverUrl = history.coverUrl
                                                        )
                                                    )
                                                }
                                            },
            favoriteRefreshToken = effectiveFavoriteRefreshToken,
                                            navigator = navigator
                                        )
                                        is ReadHistoryRepository.RssSearchReadingHistory -> RssHistoryItem(
                                            history = history,
                                            pageMode = mode,
                                            selectedItems = selectedItems,
                                            onToggleSelection = {
                                                selectedItems =
                                                    if (history in selectedItems) selectedItems - history else selectedItems + history
                                            },
                                            onDelete = {
                                                scope.launch {
                                                    readHistoryRepo.deleteRssSearchHistory(history.subscriptionId)
                                                    loadPage(currentPage)
                                                    feedbackController.post(i18n("已刪除這筆紀錄"))
                                                }
                                            },
                                            onFavorite = {
                                                scope.launch {
                                                    toggleFavoriteQuickWithFeedback(
                                                        FavoriteTargetPayload.RssSearch(
                                                            subscriptionId = history.subscriptionId,
                                                            title = history.subscriptionTitle,
                                                            coverUrl = history.coverUrl,
                                                        )
                                                    )
                                                }
                                            },
                                            onFavoriteLongPress = {
                                                scope.launch {
                                                    openFavoriteDialogWithSelection(
                                                        FavoriteTargetPayload.RssSearch(
                                                            subscriptionId = history.subscriptionId,
                                                            title = history.subscriptionTitle,
                                                            coverUrl = history.coverUrl,
                                                        )
                                                    )
                                                }
                                            },
            favoriteRefreshToken = effectiveFavoriteRefreshToken,
                                            navigator = navigator,
                                        )
                                        is ReadHistoryRepository.RssCatalogReadingHistory -> RssCatalogHistoryItem(
                                            history = history,
                                            pageMode = mode,
                                            selectedItems = selectedItems,
                                            onToggleSelection = {
                                                selectedItems =
                                                    if (history in selectedItems) selectedItems - history else selectedItems + history
                                            },
                                            onDelete = {
                                                scope.launch {
                                                    readHistoryRepo.deleteRssCatalogThreadHistory(history.subscriptionId)
                                                    loadPage(currentPage)
                                                    feedbackController.post(i18n("已刪除這筆紀錄"))
                                                }
                                            },
                                            onFavorite = {
                                                scope.launch {
                                                    toggleFavoriteQuickWithFeedback(
                                                        FavoriteTargetPayload.RssSearch(
                                                            subscriptionId = history.subscriptionId,
                                                            title = history.subscriptionTitle,
                                                            coverUrl = history.coverUrl,
                                                        )
                                                    )
                                                }
                                            },
                                            onFavoriteLongPress = {
                                                scope.launch {
                                                    openFavoriteDialogWithSelection(
                                                        FavoriteTargetPayload.RssSearch(
                                                            subscriptionId = history.subscriptionId,
                                                            title = history.subscriptionTitle,
                                                            coverUrl = history.coverUrl,
                                                        )
                                                    )
                                                }
                                            },
            favoriteRefreshToken = effectiveFavoriteRefreshToken,
                                            navigator = navigator,
                                        )
                                        else -> {}
                                    }
                                }
                            }
                            if (totalPages > 1) {
                                item(key = "pagination") {
                                    PageNavigation(
                                        pageNav = PageNav(
                                            currentPage = current.currentPage,
                                            totalPages = totalPages,
                                            prevUrl = if (current.currentPage > 1) "prev" else null,
                                            nextUrl = if (current.currentPage < totalPages) "next" else null
                                        ),
                                        onPageChange = { page ->
                                            scope.launch {
                                                if (mode == PageMode.Search && searchQuery.isNotBlank()) doSearch(searchQuery, page)
                                                else loadPage(page)
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

    if (favoriteDialogTarget != null) {
        FavoriteCollectionPickerDialog(
            categories = favoriteDialogCategories,
            options = favoriteCollectionOptions,
            initialCategorySelection = favoriteDialogCategorySelection,
            initialCollectionSelection = favoriteDialogSelection,
            onDismiss = { favoriteDialogTarget = null },
            onEdit = {
                favoriteDialogTarget = null
                navigator.navigate(IFavoriteCategoryManageScreen())
            },
            onConfirm = { selectedCategories, selectedCollections ->
                scope.launch {
                    val target = favoriteDialogTarget ?: return@launch
                    val existing = favoriteRepository.findFavoriteItem(target)
                    if (existing == null) {
                        favoriteRepository.saveFavorite(
                            target,
                            categoryIds = selectedCategories.toList(),
                            collectionIds = selectedCollections.toList()
                        )
                        favoriteDialogTarget = null
                        favoriteRefreshToken += 1
                        if (target.supportsRemoteWebsiteSync() && appSettingsRepository.favoriteAddSyncPromptEnabled.getValue()) {
                            pendingFavoriteRemovalTarget = target
                            showFavoriteAddSyncConfirm = true
                        } else {
                            completeSavedFavoriteSync(
                                target = target,
                                syncToRemote = target.supportsRemoteWebsiteSync() && appSettingsRepository.favoriteAddSyncDefault.getValue(),
                            )
                        }
                    } else if (selectedCategories.isEmpty() && selectedCollections.isEmpty()) {
                        favoriteDialogTarget = null
                        pendingFavoriteRemovalTarget = target
                        pendingFavoriteRemovalSelection = favoriteRepository.getFavoriteLocationSelection(target)
                        pendingFavoriteRemovalSuccessMessage = i18n("已從所有位置移除收藏")
                        if (appSettingsRepository.skipFavoriteRemovalConfirm.getValue()) {
                            if ((pendingFavoriteRemovalSelection?.paths?.size ?: 0) > 1) {
                                showFavoriteMultiPathDialog = true
                            } else {
                                maybePromptRemoteRemoval(target)
                            }
                        } else {
                            showFavoriteRemovalConfirm = true
                        }
                    } else {
                        favoriteRepository.setItemLocations(existing.id, selectedCategories, selectedCollections)
                        favoriteDialogTarget = null
                        favoriteRefreshToken += 1
                        feedbackController.post(i18n("收藏位置已更新"))
                    }
                }
            }
        )
    }

    if (showFavoriteRemovalConfirm) {
        FavoriteRemovalConfirmDialog(
            onDismiss = {
                showFavoriteRemovalConfirm = false
                pendingFavoriteRemovalTarget = null
                pendingFavoriteRemovalSelection = null
            },
            onConfirm = { skipNextTime ->
                appSettingsRepository.skipFavoriteRemovalConfirm.setValue(skipNextTime)
                showFavoriteRemovalConfirm = false
                val target = pendingFavoriteRemovalTarget ?: return@FavoriteRemovalConfirmDialog
                val selection = pendingFavoriteRemovalSelection
                scope.launch {
                    if ((selection?.paths?.size ?: 0) > 1) {
                        showFavoriteMultiPathDialog = true
                    } else {
                        pendingFavoriteRemovalSuccessMessage = i18n("已移除收藏")
                        maybePromptRemoteRemoval(target)
                    }
                }
            },
        )
    }

    if (showFavoriteAddSyncConfirm) {
        FavoriteAddSyncConfirmDialog(
            onDismiss = {
                val target = pendingFavoriteRemovalTarget
                showFavoriteAddSyncConfirm = false
                pendingFavoriteRemovalTarget = null
                if (target != null) {
                    launchFavoriteTask("sync", target) { completeSavedFavoriteSync(target, syncToRemote = false) }
                }
            },
            onConfirm = { rememberChoice, syncRemote ->
                val target = pendingFavoriteRemovalTarget ?: return@FavoriteAddSyncConfirmDialog
                showFavoriteAddSyncConfirm = false
                if (rememberChoice) {
                    appSettingsRepository.favoriteAddSyncPromptEnabled.setValue(false)
                    appSettingsRepository.favoriteAddSyncDefault.setValue(syncRemote)
                }
                pendingFavoriteRemovalTarget = null
                launchFavoriteTask("sync", target) { completeSavedFavoriteSync(target, syncRemote) }
            },
        )
    }

    if (showFavoriteRemoveSyncConfirm) {
        FavoriteRemoveSyncConfirmDialog(
            onDismiss = {
                showFavoriteRemoveSyncConfirm = false
                pendingFavoriteRemovalTarget = null
                pendingFavoriteRemovalSelection = null
            },
            onConfirm = { rememberChoice, syncRemote ->
                val target = pendingFavoriteRemovalTarget ?: return@FavoriteRemoveSyncConfirmDialog
                showFavoriteRemoveSyncConfirm = false
                if (rememberChoice) {
                    appSettingsRepository.favoriteRemoveSyncPromptEnabled.setValue(false)
                    appSettingsRepository.favoriteRemoveSyncDefault.setValue(syncRemote)
                }
                launchFavoriteTask("remove", target) { completeFavoriteRemoval(target, syncRemote) }
            },
        )
    }

    if (showFavoriteMultiPathDialog) {
        FavoriteMultiPathRemoveDialog(
            paths = pendingFavoriteRemovalSelection?.paths.orEmpty(),
            tip = i18n("tip：長按可詳細編輯收藏路徑"),
            onDismiss = {
                showFavoriteMultiPathDialog = false
                pendingFavoriteRemovalTarget = null
                pendingFavoriteRemovalSelection = null
            },
            onRemoveAll = {
                val target = pendingFavoriteRemovalTarget ?: return@FavoriteMultiPathRemoveDialog
                showFavoriteMultiPathDialog = false
                pendingFavoriteRemovalSuccessMessage = i18n("已從所有位置移除收藏")
                scope.launch {
                    maybePromptRemoteRemoval(target)
                }
            },
        )
    }

    if (showFilterDialog) {
        ReadHistoryFilterDialog(
            options = filterCounts,
            selected = selectedFilters,
            onCancel = { showFilterDialog = false },
            onConfirm = { filters ->
                selectedFilters = normalizeHistoryFilters(filters)
                currentPage = 1
                showFilterDialog = false
                scope.launch { loadPage(1) }
            },
        )
    }
}

@Composable
private fun ReadHistoryFilterDialog(
    options: List<ReadHistoryRepository.HistoryFilterCount>,
    selected: Set<ReadHistoryRepository.HistoryFilter>,
    onCancel: () -> Unit,
    onConfirm: (Set<ReadHistoryRepository.HistoryFilter>) -> Unit,
) {
    if (options.isEmpty()) return
    val normalized = normalizeHistoryFilters(selected)
    val selectedOptions = if (normalized.isEmpty()) {
        options.firstOrNull { it.filter == ReadHistoryRepository.HistoryFilter.All }?.let(::setOf).orEmpty()
    } else {
        options.filter { it.filter in normalized }.toSet()
    }
    YamiboMultiSelectDialog(
        title = i18n("篩選類別"),
        options = options,
        selected = selectedOptions,
        onConfirm = { confirmed -> onConfirm(confirmed.map { it.filter }.toSet()) },
        onCancel = onCancel,
        label = { "${it.label} (${it.count})" },
        toggleSelection = { option, current ->
            when (option.filter) {
                ReadHistoryRepository.HistoryFilter.All -> setOf(option)
                else -> {
                    val withoutAll = current.filterNot { it.filter == ReadHistoryRepository.HistoryFilter.All }.toSet()
                    if (option in withoutAll) withoutAll - option else withoutAll + option
                }
            }
        },
    )
}

private fun normalizeHistoryFilters(
    filters: Set<ReadHistoryRepository.HistoryFilter>,
): Set<ReadHistoryRepository.HistoryFilter> {
    return if (filters.isEmpty() || ReadHistoryRepository.HistoryFilter.All in filters) {
        emptySet()
    } else {
        filters
    }
}

private fun selectedHistoryFilterLabel(
    selectedFilters: Set<ReadHistoryRepository.HistoryFilter>,
    filterCounts: List<ReadHistoryRepository.HistoryFilterCount>,
    allLabel: String,
): String {
    val normalized = normalizeHistoryFilters(selectedFilters)
    if (normalized.isEmpty()) return allLabel
    val labels = filterCounts
        .filter { it.filter in normalized }
        .map { it.label }
    return when {
        labels.isEmpty() -> allLabel
        labels.size <= 2 -> labels.joinToString("、")
        else -> labels.take(2).joinToString("、") + " +${labels.size - 2}"
    }
}

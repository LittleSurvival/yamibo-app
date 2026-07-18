package me.thenano.yamibo.yamibo_app.favorite


import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.littlesurvival.dto.value.TagId
import io.github.littlesurvival.dto.value.ThreadId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import me.thenano.yamibo.yamibo_app.*
import me.thenano.yamibo.yamibo_app.components.controls.YamiboMultiSelectDialog
import me.thenano.yamibo.yamibo_app.favorite.components.*
import me.thenano.yamibo.yamibo_app.favorite.sync.IFavoriteSyncProgressScreen
import me.thenano.yamibo.yamibo_app.i18n.i18n
import me.thenano.yamibo.yamibo_app.navigation.*
import me.thenano.yamibo.yamibo_app.repository.FavoriteSyncRepository.FavoriteSyncState
import me.thenano.yamibo.yamibo_app.repository.FavoriteShareRepository
import me.thenano.yamibo.yamibo_app.repository.FavoriteStoreRepository.*
import me.thenano.yamibo.yamibo_app.repository.ReadHistoryRepository
import me.thenano.yamibo.yamibo_app.repository.settings.FavoriteSortMode
import me.thenano.yamibo.yamibo_app.profile.settings.access.IBackgroundAccessSetupScreen
import me.thenano.yamibo.yamibo_app.profile.settings.backup.IBackupSettingsScreen
import me.thenano.yamibo.yamibo_app.components.theme.YamiboSnackbarHost
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme
import me.thenano.yamibo.yamibo_app.thread.detail.novel.INovelThreadDetailScreen
import me.thenano.yamibo.yamibo_app.thread.detail.rss.IRssSearchSubscriptionDetailScreen
import me.thenano.yamibo.yamibo_app.thread.detail.tag.ITagDetailScreen
import me.thenano.yamibo.yamibo_app.thread.reader.IThreadReaderScreen
import me.thenano.yamibo.yamibo_app.util.state
import me.thenano.yamibo.yamibo_app.util.time.currentTimeMillis

internal sealed interface FavoritePageState {
    data object Loading : FavoritePageState
    data class Ready(
        val categories: List<FavoriteCategory>,
        val selectedCategoryId: Long,
        val content: FavoriteCategoryContent,
        val lastReadMap: Map<Long, Long>,
        val remoteFavoriteOrderMap: Map<Long, Long>,
    ) : FavoritePageState
}

internal enum class FavoritePageMode { Normal, Search, Select }

internal data class FavoriteCollectionDraft(
    val collectionId: Long? = null,
    val parentCategoryId: Long? = null,
    val title: String = i18n("合成集合"),
    val initialName: String = "",
    val initialColorKey: String = "brown",
    val removeOriginalItems: Boolean = true,
    val showRemoveOriginalOption: Boolean = true,
)

private data class FavoritePickerReturnState(
    val openedCategoryId: Long?,
    val categorySelection: Set<Long>,
    val collectionSelection: Set<Long>,
)

private data class FavoriteDeleteRequest(
    val itemIds: Set<Long>,
    val categoryId: Long,
    val collectionId: Long?,
    val hasMultiPathItems: Boolean,
    val hasRemoteSyncedItems: Boolean,
)

private data class FavoriteForumFilterOption(
    val forumKey: String?,
    val label: String,
    val count: Int,
)

sealed interface FavoriteGridEntry {
    val key: String
    data class Collection(val value: FavoriteCollectionWithItems) : FavoriteGridEntry { override val key = "collection_${value.collection.id}" }
    data class Item(val value: FavoriteItem) : FavoriteGridEntry { override val key = "item_${value.id}" }
}

@Serializable
private data class FavoriteCategoryEditorRestorePayload(
    val categoryId: Long? = null,
)
@RestorableScreenEntry
class IFavoriteCategoryManageScreen : RestorableNavigatable {
    override val id = buildId()
    override val restoreDecoder = Decoder
    override fun toRestoreSnapshot(): RestorableScreenSnapshot = emptyRestoreSnapshot(restoreDecoder)
    @Composable override fun Content() { FavoriteCategoryManageScreen() }

    companion object Decoder : TypedRestorableNavigatableDecoder<IFavoriteCategoryManageScreen>(IFavoriteCategoryManageScreen::class) {
        override fun decode(payload: String): RestorableNavigatable = IFavoriteCategoryManageScreen()
    }
}
@RestorableScreenEntry
class IFavoriteCategoryEditorScreen(val categoryId: Long? = null) : RestorableNavigatable {
    override val id = buildId(categoryId ?: "new")
    override val restoreDecoder = Decoder
    override fun toRestoreSnapshot(): RestorableScreenSnapshot = restoreSnapshot(
        decoder = restoreDecoder,
        payload = FavoriteCategoryEditorRestorePayload(categoryId = categoryId),
    )
    @Composable override fun Content() { FavoriteCategoryEditorScreen(categoryId) }

    companion object Decoder : TypedRestorableNavigatableDecoder<IFavoriteCategoryEditorScreen>(IFavoriteCategoryEditorScreen::class) {
        override fun decode(payload: String): RestorableNavigatable {
            val data = decodeRestorePayload<FavoriteCategoryEditorRestorePayload>(payload)
            return IFavoriteCategoryEditorScreen(categoryId = data.categoryId)
        }
    }
}

@Composable
fun FavoritePage() {
    val colors = YamiboTheme.colors
    val appSettingsRepository = LocalAppSettingsRepository.current
    val favoriteRepository = LocalFavoriteRepository.current
    val favoriteShareRepository = LocalFavoriteShareRepository.current
    val favoriteSyncRepository = LocalFavoriteSyncRepository.current
    val favoriteSyncRunner = LocalFavoriteSyncRunner.current
    val downloadRepository = LocalDownloadRepository.current
    val rssRepository = LocalRssSearchSubscriptionRepository.current
    val readHistoryRepository = LocalReadHistoryRepository.current
    val navigator = LocalNavigator.current
    val appTaskManager = LocalAppTaskManager.current
    val scope = rememberCoroutineScope()
    val feedbackController = me.thenano.yamibo.yamibo_app.LocalAppFeedbackController.current
    val favoriteGridMode = appSettingsRepository.favoriteGridMode.state()
    val sortMode = appSettingsRepository.favoriteSortMode.state()
    val sortDescending = appSettingsRepository.favoriteSortDescending.state()
    val syncState by favoriteSyncRunner.state.collectAsState()
    val hiddenFavoritePageRuns by favoriteSyncRunner.hiddenFavoritePageRuns.collectAsState()

    var state by remember { mutableStateOf<FavoritePageState>(FavoritePageState.Loading) }
    var searchActive by remember { mutableStateOf(false) }
    var selectedItemIds by rememberSaveable { mutableStateOf(setOf<Long>()) }
    var selectedCollectionIds by rememberSaveable { mutableStateOf(setOf<Long>()) }
    var openedCollectionId by rememberSaveable { mutableStateOf<Long?>(null) }
    var pickerCategories by remember { mutableStateOf<List<FavoriteCategory>>(emptyList()) }
    var pickerOptions by remember { mutableStateOf<List<FavoriteCollectionOption>>(emptyList()) }
    var pickerCategorySelection by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var pickerCollectionSelection by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var pickerOpenedCategoryId by rememberSaveable { mutableStateOf<Long?>(null) }
    var showMoveDialog by remember { mutableStateOf(false) }
    var pickerReturnState by remember { mutableStateOf<FavoritePickerReturnState?>(null) }
    var collectionDraft by remember { mutableStateOf<FavoriteCollectionDraft?>(null) }
    var showSortDialog by remember { mutableStateOf(false) }
    var showGridModeDialog by remember { mutableStateOf(false) }
    var deleteRequest by remember { mutableStateOf<FavoriteDeleteRequest?>(null) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var showDeleteScopeDialog by remember { mutableStateOf(false) }
    var showDeleteRemoteSyncDialog by remember { mutableStateOf(false) }
    var showSyncConfirmDialog by remember { mutableStateOf(false) }
    var showSyncCategoryDialog by remember { mutableStateOf(false) }
    var syncTargetCategoryId by rememberSaveable { mutableStateOf<Long?>(null) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var searchCategoryMatchCounts by remember { mutableStateOf<Map<Long, Int>>(emptyMap()) }
    var showFavoriteFilterDialog by remember { mutableStateOf(false) }
    var selectedFavoriteForumFilterKeys by rememberSaveable { mutableStateOf(setOf<String>()) }
    var showFavoriteCounts by rememberSaveable { mutableStateOf(false) }
    var favoriteCategoryCounts by remember { mutableStateOf<Map<Long, Int>>(emptyMap()) }
    var favoriteForumCounts by remember { mutableStateOf<List<FavoriteForumFilterOption>>(emptyList()) }
    var showShareExportDialog by remember { mutableStateOf(false) }
    var showShareExportPreviewDialog by remember { mutableStateOf(false) }
    var shareFolderOptions by remember { mutableStateOf<List<FavoriteCategory>>(emptyList()) }
    var shareFolderSelection by remember { mutableStateOf<Set<FavoriteCategory>>(emptySet()) }
    var pendingExportJson by remember { mutableStateOf<String?>(null) }
    var pendingExportFileName by remember { mutableStateOf<String?>(null) }
    var pendingExportFolderCount by remember { mutableStateOf(0) }
    var pendingExportItemCount by remember { mutableStateOf(0) }
    var pendingImportJson by remember { mutableStateOf<String?>(null) }
    var importPreview by remember { mutableStateOf<FavoriteShareRepository.ImportPreview?>(null) }
    var showImportPreviewDialog by remember { mutableStateOf(false) }
    var showImportTargetDialog by remember { mutableStateOf(false) }
    var importTargetSelection by remember { mutableStateOf<Set<FavoriteCategory>>(emptySet()) }
    var showBatchTypeDialog by remember { mutableStateOf(false) }
    var showBatchModeDialog by remember { mutableStateOf(false) }
    var selectedBatchTypes by remember { mutableStateOf(setOf<FavoriteBatchDownloadType>()) }
    var selectedBatchMode by remember { mutableStateOf(FavoriteBatchDownloadMode.All) }
    val batchDownloadSubmissionGate = remember { FavoriteBatchDownloadSubmissionGate() }

    fun showSnackbarMessage(message: String) {
        scope.launch {
            feedbackController.post(message)
        }
    }

    fun importResultMessage(result: FavoriteShareRepository.ImportResult): String {
        return i18n(
            "已載入：新增 {} 項，重用 {} 項，跳過 {} 項",
            result.createdItemCount,
            result.reusedItemCount,
            result.skippedDuplicateCount + result.unsupportedCount + result.invalidCount,
        )
    }

    fun batchDownloadResultMessage(result: FavoriteBatchDownloadResult): String {
        val skipped = result.skipped + result.unsupported
        return when {
            result.requested == 0 -> i18n("沒有可下載的收藏")
            result.failed == 0 && skipped == 0 -> i18n("已加入下載佇列 {} 項", result.queued)
            else -> i18n("已加入 {} 項，失敗 {} 項，跳過 {} 項", result.queued, result.failed, skipped)
        }
    }

    val favoriteShareFileActions = rememberFavoriteShareFileActions(
        onExported = { fileName -> showSnackbarMessage(i18n("已匯出 {}", fileName)) },
        onExportFailed = { message -> showSnackbarMessage(message) },
        onImportPicked = { jsonText ->
            scope.launch {
                try {
                    val preview = withContext(Dispatchers.Default) {
                        favoriteShareRepository.previewImport(jsonText)
                    }
                    pendingImportJson = jsonText
                    importPreview = preview
                    showImportPreviewDialog = true
                } catch (error: IllegalArgumentException) {
                    showSnackbarMessage(error.message?.let { i18n(it) }?.takeIf { it.isNotBlank() } ?: i18n("收藏分享檔案格式錯誤"))
                }
            }
        },
        onImportFailed = { message -> showSnackbarMessage(message) },
    )

    fun exportFolders(categoryIds: Set<Long>) {
        scope.launch {
            try {
                val exportData = withContext(Dispatchers.Default) {
                    val packageData = favoriteShareRepository.export(
                        FavoriteShareRepository.ExportSelection(categoryIds = categoryIds),
                    )
                    Triple(
                        favoriteShareRepository.encode(packageData),
                        packageData.folders.size,
                        packageData.folders.sumOf { it.items.size },
                    )
                }
                pendingExportJson = exportData.first
                pendingExportFileName = "yamibo-favorites-${currentTimeMillis()}.json"
                pendingExportFolderCount = exportData.second
                pendingExportItemCount = exportData.third
                showShareExportPreviewDialog = true
            } catch (error: IllegalArgumentException) {
                showSnackbarMessage(error.message?.let { i18n(it) }?.takeIf { it.isNotBlank() } ?: i18n("匯出收藏夾失敗"))
            }
        }
    }

    fun handleSyncRejected(result: me.thenano.yamibo.yamibo_app.favorite.sync.FavoriteSyncRunner.LaunchResult.Rejected) {
        showSnackbarMessage(result.reason)
        if (result.requiresBackgroundAccessSetup) {
            navigator.navigate(IBackgroundAccessSetupScreen())
        }
    }

    suspend fun reload(preferredCategoryId: Long? = null) {
        val currentSelectedCategoryId = (state as? FavoritePageState.Ready)?.selectedCategoryId
        val snapshot = withContext(Dispatchers.Default) {
            favoriteRepository.ensureDefaults()
            val categories = favoriteRepository.getCategories()
            val savedCategoryId = appSettingsRepository.favoriteLastCategoryId.getValue().toLong()
            val selectedCategoryId = listOfNotNull(
                preferredCategoryId,
                currentSelectedCategoryId,
                savedCategoryId.takeIf { saved -> categories.any { it.id == saved } },
                categories.firstOrNull()?.id,
            ).firstOrNull() ?: 0L
            val content = if (selectedCategoryId == 0L) {
                FavoriteCategoryContent(emptyList(), emptyList())
            } else {
                favoriteRepository.getCategoryContent(selectedCategoryId)
            }
            val allSortItems = buildList {
                content.directItems.forEach(::add)
                content.collections.forEach { addAll(it.items) }
            }.distinctBy { it.id }
            val isMangaMode = appSettingsRepository.isMangaMode.getValue()
            val lastReadMap = buildMap {
                allSortItems.forEach { item ->
                    put(item.id, favoriteItemLastReadAt(item, readHistoryRepository, isMangaMode))
                }
            }
            val remoteFavoriteOrderMap = favoriteSyncRepository.getRemoteFavoriteOrderMap(
                allSortItems.map { it.id }.toSet()
            )
            FavoritePageState.Ready(
                categories = categories,
                selectedCategoryId = selectedCategoryId,
                content = content,
                lastReadMap = lastReadMap,
                remoteFavoriteOrderMap = remoteFavoriteOrderMap,
            )
        }
        val content = snapshot.content
        if (openedCollectionId != null && content.collections.none { it.collection.id == openedCollectionId }) {
            openedCollectionId = null
        }
        state = snapshot
    }

    fun importFavorites(target: FavoriteShareRepository.ImportTarget) {
        val jsonText = pendingImportJson ?: return
        scope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    favoriteShareRepository.importFavorites(jsonText, target)
                }
                showImportPreviewDialog = false
                showImportTargetDialog = false
                pendingImportJson = null
                importPreview = null
                importTargetSelection = emptySet()
                reload()
                showSnackbarMessage(importResultMessage(result))
            } catch (error: IllegalArgumentException) {
                showSnackbarMessage(error.message?.let { i18n(it) }?.takeIf { it.isNotBlank() } ?: i18n("載入收藏夾失敗"))
            }
        }
    }

    suspend fun loadSelectionIntersection(itemIds: Set<Long>) {
        if (itemIds.isEmpty()) {
            pickerCategorySelection = emptySet()
            pickerCollectionSelection = emptySet()
            return
        }
        val (categories, collections) = withContext(Dispatchers.Default) {
            val intersectedCategories = itemIds.map { favoriteRepository.getCategoryIdsForItem(it) }
                .reduce { acc, set -> acc intersect set }
            val intersectedCollections = itemIds.map { favoriteRepository.getCollectionIdsForItem(it) }
                .reduce { acc, set -> acc intersect set }
            intersectedCategories to intersectedCollections
        }
        pickerCategorySelection = categories
        pickerCollectionSelection = collections
    }

    LaunchedEffect(navigator.stack.size) { reload() }

    suspend fun refreshSearchCounts(query: String) {
        val trimmed = query.trim()
        searchCategoryMatchCounts = if (trimmed.isBlank()) {
            emptyMap()
        } else {
            withContext(Dispatchers.Default) {
                readySearchCounts(favoriteRepository, trimmed)
            }
        }
    }

    DisposableEffect(searchActive, selectedItemIds, selectedCollectionIds, openedCollectionId, navigator) {
        val selecting = selectedItemIds.isNotEmpty() || selectedCollectionIds.isNotEmpty()
        if (!searchActive && !selecting && openedCollectionId == null) {
            onDispose { }
        } else {
            val handler = {
                when {
                    selecting -> {
                        selectedItemIds = emptySet()
                        selectedCollectionIds = emptySet()
                        true
                    }
                    searchActive -> {
                        searchQuery = ""
                        searchCategoryMatchCounts = emptyMap()
                        selectedItemIds = emptySet()
                        selectedCollectionIds = emptySet()
                        searchActive = false
                        true
                    }
                    openedCollectionId != null -> {
                        openedCollectionId = null
                        true
                    }
                    else -> false
                }
            }
            navigator.backHandlers.add(handler)
            onDispose { navigator.backHandlers.remove(handler) }
        }
    }

    suspend fun performDeleteAllFavorites(request: FavoriteDeleteRequest, removeRemote: Boolean) {
        val deleteResult = withContext(Dispatchers.Default) {
            favoriteSyncRepository.removeLocalFavoriteItems(request.itemIds, removeRemote = removeRemote)
        }
        if (deleteResult.deletedCount > 0) {
            selectedItemIds = emptySet()
            selectedCollectionIds = emptySet()
            reload(request.categoryId)
        }
        showSnackbarMessage(
            when {
                deleteResult.failedCount == 0 -> i18n("已刪除收藏")
                deleteResult.deletedCount == 0 -> deleteResult.messages.firstOrNull() ?: i18n("刪除收藏失敗")
                else -> i18n("已刪除 {} 項，{} 項失敗", deleteResult.deletedCount, deleteResult.failedCount)
            }
        )
        deleteRequest = null
    }

    fun requestDeleteFavorites(itemIds: Set<Long>, categoryId: Long, collectionId: Long?) {
        scope.launch {
            val flags = withContext(Dispatchers.Default) {
                val hasMultiPath = itemIds.any { favoriteRepository.getFavoritePaths(it).size > 1 }
                val hasRemoteSynced = itemIds.any { favoriteSyncRepository.hasRemoteFavorite(it) }
                hasMultiPath to hasRemoteSynced
            }
            deleteRequest = FavoriteDeleteRequest(
                itemIds = itemIds,
                categoryId = categoryId,
                collectionId = collectionId,
                hasMultiPathItems = flags.first,
                hasRemoteSyncedItems = flags.second,
            )
            if (appSettingsRepository.skipFavoriteRemovalConfirm.getValue()) {
                if (flags.first) {
                    showDeleteScopeDialog = true
                } else {
                    if (flags.second && appSettingsRepository.favoriteRemoveSyncPromptEnabled.getValue()) {
                        showDeleteRemoteSyncDialog = true
                    } else {
                        performDeleteAllFavorites(
                            deleteRequest ?: return@launch,
                            removeRemote = flags.second && appSettingsRepository.favoriteRemoveSyncDefault.getValue(),
                        )
                    }
                }
            } else {
                showDeleteConfirmDialog = true
            }
        }
    }

    suspend fun performDeleteCurrentDirectory(request: FavoriteDeleteRequest) {
        withContext(Dispatchers.Default) {
            if (request.collectionId != null) {
                favoriteRepository.removeItemsFromCollections(request.itemIds, setOf(request.collectionId))
            } else {
                favoriteRepository.removeItemsFromCategory(request.itemIds, request.categoryId)
            }
        }
        selectedItemIds = emptySet()
        selectedCollectionIds = emptySet()
        reload(request.categoryId)
        showSnackbarMessage(i18n("已移除當下目錄收藏"))
        deleteRequest = null
    }

    val ready = state as? FavoritePageState.Ready
    LaunchedEffect(searchQuery, ready?.categories?.map { it.id }) {
        refreshSearchCounts(searchQuery)
    }
    LaunchedEffect(showFavoriteCounts, showFavoriteFilterDialog, ready?.categories?.map { it.id }) {
        favoriteCategoryCounts = if (showFavoriteCounts && ready != null) {
            withContext(Dispatchers.Default) {
                readyFavoriteCounts(favoriteRepository)
            }
        } else {
            emptyMap()
        }
    }
    LaunchedEffect(showFavoriteFilterDialog) {
        favoriteForumCounts = if (showFavoriteFilterDialog) {
            withContext(Dispatchers.Default) {
                readyFavoriteForumCounts(favoriteRepository)
            }
        } else {
            emptyList()
        }
    }
    val openedCollection = ready?.content?.collections?.firstOrNull { it.collection.id == openedCollectionId }
    val visibleItems = openedCollection?.items ?: ready?.content?.directItems.orEmpty()
    val trimmedSearchQuery = searchQuery.trim()
    val selecting = selectedItemIds.isNotEmpty() || selectedCollectionIds.isNotEmpty()
    val headerMode = when {
        selecting -> FavoritePageMode.Select
        searchActive -> FavoritePageMode.Search
        else -> FavoritePageMode.Normal
    }
    val searchEnabled = searchActive && trimmedSearchQuery.isNotBlank()
    val batchDownloadScope = remember(ready?.content, selectedItemIds, selectedCollectionIds) {
        ready?.content?.let { content ->
            buildFavoriteBatchDownloadScope(content, selectedItemIds, selectedCollectionIds)
        }
    }
    val knownForumKeys = favoriteForumCounts.mapNotNullTo(mutableSetOf()) { it.forumKey }
    val normalizedFavoriteForumFilterKeys = selectedFavoriteForumFilterKeys
        .filterTo(mutableSetOf()) { key -> knownForumKeys.isEmpty() || key in knownForumKeys }
    val categoryBadgeCounts = when {
        searchEnabled -> searchCategoryMatchCounts
        showFavoriteCounts -> favoriteCategoryCounts
        else -> emptyMap()
    }
    fun matchesForumFilter(item: FavoriteItem): Boolean {
        return normalizedFavoriteForumFilterKeys.isEmpty() ||
            favoriteForumKey(item) in normalizedFavoriteForumFilterKeys
    }
    val resolvedLastReadMap = ready?.lastReadMap.orEmpty()
    val resolvedRemoteFavoriteOrderMap = ready?.remoteFavoriteOrderMap.orEmpty()
    val collections = sortCollections(
        ready?.content?.collections.orEmpty()
            .map { collection ->
                if (normalizedFavoriteForumFilterKeys.isEmpty()) {
                    collection
                } else {
                    collection.copy(items = collection.items.filter(::matchesForumFilter))
                }
            }
            .filter { collection ->
                (normalizedFavoriteForumFilterKeys.isEmpty() || collection.items.isNotEmpty()) &&
                    (!searchEnabled || collectionMatchesQuery(collection, trimmedSearchQuery))
            },
        sortMode,
        sortDescending,
        resolvedLastReadMap,
        resolvedRemoteFavoriteOrderMap,
    )
    val items = sortItems(
        visibleItems.filter {
            matchesForumFilter(it) && (!searchEnabled || favoriteItemMatchesQuery(it, trimmedSearchQuery))
        },
        sortMode,
        sortDescending,
        resolvedLastReadMap,
        resolvedRemoteFavoriteOrderMap,
    )
    val gridEntries = buildList {
        if (openedCollection == null) addAll(collections.map(FavoriteGridEntry::Collection))
        addAll(items.map(FavoriteGridEntry::Item))
    }

    Box(Modifier.fillMaxSize().background(colors.creamBackground)) {
        if (ready == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colors.brownPrimary, modifier = Modifier.size(32.dp))
            }
        } else {
            FavoritePageContent(
                ready = ready,
                mode = headerMode,
                searchActive = searchActive,
                searchQuery = searchQuery,
                searchCategoryMatchCounts = categoryBadgeCounts,
                favoriteFilterActive = normalizedFavoriteForumFilterKeys.isNotEmpty(),
                showFavoriteCounts = showFavoriteCounts,
                sortMode = sortMode,
                sortDescending = sortDescending,
                favoriteGridMode = favoriteGridMode,
                gridEntries = gridEntries,
                openedCollection = openedCollection,
                selectedItemIds = selectedItemIds,
                selectedCollectionIds = selectedCollectionIds,
                lastReadMap = resolvedLastReadMap,
                syncState = syncState,
                hiddenFavoritePageRuns = hiddenFavoritePageRuns,
                onCreateCategory = { navigator.navigate(IFavoriteCategoryEditorScreen()) },
                onManageCategory = { navigator.navigate(IFavoriteCategoryManageScreen()) },
                onEnterSearch = { searchActive = true },
                onShowFilter = { showFavoriteFilterDialog = true },
                onToggleFavoriteCounts = { showFavoriteCounts = !showFavoriteCounts },
                onShareFavorites = {
                    scope.launch {
                        shareFolderOptions = withContext(Dispatchers.Default) {
                            favoriteRepository.getCategories()
                        }
                        if (shareFolderOptions.isEmpty()) {
                            showSnackbarMessage(i18n("沒有可分享的收藏夾"))
                        } else {
                            shareFolderSelection = emptySet()
                            showShareExportDialog = true
                        }
                    }
                },
                onLoadFavorites = {
                    favoriteShareFileActions.pickJson()
                },
                onSearchQueryChange = { searchQuery = it },
                onSearchSubmit = { },
                onExitSearch = {
                    searchQuery = ""
                    searchCategoryMatchCounts = emptyMap()
                    selectedItemIds = emptySet()
                    selectedCollectionIds = emptySet()
                    searchActive = false
                },
                onSyncFavorites = {
                    val runId = currentSyncRunId(syncState)
                    if (runId != null && syncState is FavoriteSyncState.Running) {
                        navigator.navigate(IFavoriteSyncProgressScreen(runId))
                    } else {
                        syncTargetCategoryId = ready.selectedCategoryId
                        showSyncConfirmDialog = true
                    }
                },
                onOpenSyncProgress = {
                    currentSyncRunId(syncState)?.let { navigator.navigate(IFavoriteSyncProgressScreen(it)) }
                },
                onHideSyncCard = {
                    currentSyncRunId(syncState)?.let(favoriteSyncRunner::dismissFavoritePageCard)
                },
                onResumeSync = {
                    scope.launch {
                        when (val result = favoriteSyncRunner.resumeInterruptedImport()) {
                            null -> Unit
                            is me.thenano.yamibo.yamibo_app.favorite.sync.FavoriteSyncRunner.LaunchResult.Started -> {
                                navigator.navigate(IFavoriteSyncProgressScreen(result.runId))
                            }
                            is me.thenano.yamibo.yamibo_app.favorite.sync.FavoriteSyncRunner.LaunchResult.Rejected -> {
                                handleSyncRejected(result)
                            }
                        }
                    }
                },
                onInterruptSync = {
                    val runId = currentSyncRunId(syncState)
                    if (runId != null) {
                        scope.launch {
                            favoriteSyncRunner.interruptImport(runId)
                            showSnackbarMessage(i18n("已取消同步"))
                        }
                    }
                },
                onSelectCategory = { categoryId ->
                    appSettingsRepository.favoriteLastCategoryId.setValue(categoryId.toInt())
                    openedCollectionId = null
                    selectedItemIds = emptySet()
                    selectedCollectionIds = emptySet()
                    scope.launch { reload(categoryId) }
                },
                onShowGridMode = { showGridModeDialog = true },
                onShowSort = { showSortDialog = true },
                onBackToTab = { openedCollectionId = null },
                onSelectAll = {
                    if (openedCollection == null) selectedCollectionIds = ready.content.collections.map { it.collection.id }.toSet()
                    selectedItemIds = visibleItems.map { it.id }.toSet()
                },
                onClearSelection = {
                    selectedItemIds = emptySet()
                    selectedCollectionIds = emptySet()
                },
                onCancelSelection = {
                    selectedItemIds = emptySet()
                    selectedCollectionIds = emptySet()
                },
                onOpenBatchDownload = {
                    val scopeInfo = batchDownloadScope ?: return@FavoritePageContent
                    if (batchDownloadSubmissionGate.isSubmitting) {
                        showSnackbarMessage(i18n("上一批收藏正在加入下載佇列"))
                    } else if (scopeInfo.items.isEmpty()) {
                        showSnackbarMessage(i18n("沒有可下載的收藏"))
                    } else {
                        selectedBatchTypes = scopeInfo.countByType()
                            .filterValues { it > 0 }
                            .keys
                        selectedBatchMode = FavoriteBatchDownloadMode.All
                        showBatchTypeDialog = true
                    }
                },
                onOpenMoveDialog = {
                    scope.launch {
                        val (categories, options) = withContext(Dispatchers.Default) {
                            favoriteRepository.getCategories() to favoriteRepository.getCollectionOptions()
                        }
                        pickerCategories = categories
                        pickerOptions = options
                        loadSelectionIntersection(selectedItemIds)
                        pickerOpenedCategoryId = null
                        showMoveDialog = true
                    }
                },
                onOpenMergeDialog = {
                    pickerReturnState = null
                    collectionDraft = FavoriteCollectionDraft(
                        parentCategoryId = ready.selectedCategoryId,
                        title = i18n("合成集合"),
                        showRemoveOriginalOption = true,
                    )
                },
                onEditSelectedCollection = {
                    val collection = ready.content.collections.firstOrNull { it.collection.id in selectedCollectionIds }?.collection ?: return@FavoritePageContent
                    collectionDraft = FavoriteCollectionDraft(
                        collectionId = collection.id,
                        parentCategoryId = collection.categoryId,
                        title = i18n("編輯集合"),
                        initialName = collection.name,
                        initialColorKey = collection.colorKey,
                        removeOriginalItems = false,
                        showRemoveOriginalOption = false,
                    )
                },
                onDissolveSelectedCollections = {
                    scope.launch {
                        ready.content.collections.filter { it.collection.id in selectedCollectionIds }.forEach { collection ->
                            val itemIds = collection.items.map { it.id }.toSet()
                            favoriteRepository.addItemsToLocations(itemIds = itemIds, categoryIds = setOf(ready.selectedCategoryId))
                            favoriteRepository.removeItemsFromCollections(itemIds = itemIds, collectionIds = setOf(collection.collection.id))
                            favoriteRepository.deleteCollection(collection.collection.id)
                        }
                        selectedCollectionIds = emptySet()
                        reload(ready.selectedCategoryId)
                        showSnackbarMessage(i18n("已解散集合"))
                    }
                },
                onDeleteSelectedItems = {
                    requestDeleteFavorites(selectedItemIds, ready.selectedCategoryId, openedCollection?.collection?.id)
                },
                onOpenCollection = { openedCollectionId = it },
                onToggleItem = { id -> selectedItemIds = selectedItemIds.toggle(id) },
                onToggleCollection = { id -> selectedCollectionIds = selectedCollectionIds.toggle(id) },
                onEnterSelectItem = { id -> selectedItemIds = selectedItemIds + id },
                onEnterSelectCollection = { id -> selectedCollectionIds = selectedCollectionIds + id },
                onOpenItem = { openFavoriteItem(navigator, it) },
            )
        }

    }

    if (showBatchTypeDialog && batchDownloadScope != null) {
        FavoriteBatchDownloadTypeDialog(
            scope = batchDownloadScope,
            selectedTypes = selectedBatchTypes,
            onDismiss = { showBatchTypeDialog = false },
            onNext = { confirmedTypes ->
                if (confirmedTypes.isEmpty()) {
                    showSnackbarMessage(i18n("請至少選擇一種收藏類型"))
                } else {
                    selectedBatchTypes = confirmedTypes
                    showBatchTypeDialog = false
                    showBatchModeDialog = true
                }
            },
        )
    }

    if (showBatchModeDialog && batchDownloadScope != null) {
        FavoriteBatchDownloadModeDialog(
            scope = batchDownloadScope,
            selectedTypes = selectedBatchTypes,
            selectedMode = selectedBatchMode,
            onSelectMode = { selectedBatchMode = it },
            onBack = {
                showBatchModeDialog = false
                showBatchTypeDialog = true
            },
            onDismiss = { showBatchModeDialog = false },
            onConfirm = {
                if (!batchDownloadSubmissionGate.tryStart()) return@FavoriteBatchDownloadModeDialog
                showBatchModeDialog = false
                val items = batchDownloadScope.itemsForTypes(selectedBatchTypes)
                val batchMode = selectedBatchMode.coerceFor(batchDownloadScope, selectedBatchTypes)
                selectedItemIds = emptySet()
                selectedCollectionIds = emptySet()
                showSnackbarMessage(i18n("正在加入下載佇列 {} 項", items.size))
                appTaskManager.launch(
                    key = me.thenano.yamibo.yamibo_app.task.AppTaskKey("download:favorite-batch"),
                ) {
                    try {
                        if (!downloadRepository.isStorageReady()) {
                            showSnackbarMessage(i18n("請先設定備份資料夾"))
                            navigator.navigate(IBackupSettingsScreen())
                            return@launch
                        }
                        val result = withContext(Dispatchers.Default) {
                            enqueueFavoriteBatchDownloads(downloadRepository, rssRepository, items, batchMode)
                        }
                        showSnackbarMessage(batchDownloadResultMessage(result))
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        showSnackbarMessage(i18n("加入下載佇列失敗：{}", error.message ?: i18n("未知錯誤")))
                    } finally {
                        batchDownloadSubmissionGate.finish()
                    }
                }
            },
        )
    }

    if (showFavoriteFilterDialog && ready != null) {
        val allOption = FavoriteForumFilterOption(
            forumKey = null,
            label = i18n("全部"),
            count = favoriteForumCounts.sumOf { it.count },
        )
        val options = listOf(allOption) + favoriteForumCounts
        val selectedOptions = if (normalizedFavoriteForumFilterKeys.isEmpty()) {
            setOf(allOption)
        } else {
            options.filterTo(mutableSetOf()) { option -> option.forumKey in normalizedFavoriteForumFilterKeys }
        }
        YamiboMultiSelectDialog(
            title = i18n("篩選收藏"),
            options = options,
            selected = selectedOptions,
            onConfirm = { selected ->
                showFavoriteFilterDialog = false
                selectedFavoriteForumFilterKeys = if (allOption in selected) {
                    emptySet()
                } else {
                    selected.mapNotNullTo(mutableSetOf()) { it.forumKey }
                }
                selectedItemIds = emptySet()
                selectedCollectionIds = emptySet()
            },
            onCancel = { showFavoriteFilterDialog = false },
            label = { option ->
                i18n("{} ({})", option.label, option.count)
            },
            toggleSelection = { option, current ->
                when {
                    option.forumKey == null -> setOf(option)
                    option in current -> (current - option).ifEmpty { setOf(allOption) }
                    else -> (current - allOption) + option
                }
            },
        )
    }

    if (showMoveDialog) {
        FavoriteCollectionPickerDialog(
            categories = pickerCategories,
            options = pickerOptions,
            initialCategorySelection = pickerCategorySelection,
            initialCollectionSelection = pickerCollectionSelection,
            initialOpenedCategoryId = pickerOpenedCategoryId,
            onDismiss = {
                showMoveDialog = false
                pickerOpenedCategoryId = null
            },
            onEdit = {
                showMoveDialog = false
                navigator.navigate(IFavoriteCategoryManageScreen())
            },
            onCreateCollection = { categoryId ->
                pickerReturnState = FavoritePickerReturnState(
                    openedCategoryId = categoryId,
                    categorySelection = pickerCategorySelection,
                    collectionSelection = pickerCollectionSelection,
                )
                showMoveDialog = false
                collectionDraft = FavoriteCollectionDraft(parentCategoryId = categoryId)
                    .copy(title = i18n("新增集合"), showRemoveOriginalOption = false)
            },
            onConfirm = { selectedCategories, selectedCollections ->
                scope.launch {
                    val current = state as? FavoritePageState.Ready ?: return@launch
                    withContext(Dispatchers.Default) {
                        favoriteRepository.setItemsLocations(selectedItemIds, selectedCategories, selectedCollections)
                    }
                    showMoveDialog = false
                    pickerOpenedCategoryId = null
                    pickerReturnState = null
                    selectedItemIds = emptySet()
                    selectedCollectionIds = emptySet()
                    reload(current.selectedCategoryId)
                    showSnackbarMessage(i18n("已更新收藏位置"))
                }
            },
        )
    }

    if (showSyncConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showSyncConfirmDialog = false },
            title = {
                Text(
                    i18n("同步百合會收藏"),
                    color = colors.textStrong,
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Text(
                    i18n("你確定要將百合會收藏同步到本地嗎"),
                    color = colors.textDark,
                )
            },
            confirmButton = {
                FavoriteDialogButton(
                    text = i18n("確定"),
                    background = colors.brownDeep,
                    contentColor = Color.White,
                    onClick = {
                        showSyncConfirmDialog = false
                        syncTargetCategoryId = ready?.selectedCategoryId
                        showSyncCategoryDialog = true
                    },
                )
            },
            dismissButton = {
                FavoriteDialogButton(
                    text = i18n("取消"),
                    background = colors.brownPrimary.copy(alpha = 0.1f),
                    contentColor = colors.textStrong,
                    onClick = { showSyncConfirmDialog = false },
                )
            },
            containerColor = colors.creamSurface,
            titleContentColor = colors.textStrong,
            textContentColor = colors.textDark,
        )
    }

    if (showSyncCategoryDialog && ready != null) {
        FavoriteSyncCategoryDialog(
            categories = ready.categories,
            selectedCategoryId = syncTargetCategoryId ?: ready.selectedCategoryId,
            onDismiss = { showSyncCategoryDialog = false },
            onConfirm = { categoryId ->
                showSyncCategoryDialog = false
                scope.launch {
                    when (val result = favoriteSyncRunner.startImport(categoryId)) {
                        is me.thenano.yamibo.yamibo_app.favorite.sync.FavoriteSyncRunner.LaunchResult.Started -> {
                            navigator.navigate(IFavoriteSyncProgressScreen(result.runId))
                        }
                        is me.thenano.yamibo.yamibo_app.favorite.sync.FavoriteSyncRunner.LaunchResult.Rejected -> {
                            handleSyncRejected(result)
                        }
                    }
                }
            },
            onSelect = { syncTargetCategoryId = it },
        )
    }

    if (showShareExportDialog) {
        FavoriteShareExportDialog(
            options = shareFolderOptions,
            selected = shareFolderSelection,
            onDismiss = {
                showShareExportDialog = false
                shareFolderSelection = emptySet()
            },
            onConfirm = { selected ->
                showShareExportDialog = false
                shareFolderSelection = selected
                val categoryIds = selected.mapTo(mutableSetOf()) { it.id }
                if (categoryIds.isEmpty()) {
                    showSnackbarMessage(i18n("請選擇要分享的收藏夾"))
                } else {
                    exportFolders(categoryIds)
                }
            },
        )
    }

    if (showShareExportPreviewDialog) {
        FavoriteShareExportPreviewDialog(
            folderCount = pendingExportFolderCount,
            itemCount = pendingExportItemCount,
            onDismiss = {
                showShareExportPreviewDialog = false
                pendingExportJson = null
                pendingExportFileName = null
            },
            onShare = {
                val jsonText = pendingExportJson ?: return@FavoriteShareExportPreviewDialog
                val fileName = pendingExportFileName ?: "yamibo-favorites-${currentTimeMillis()}.json"
                showShareExportPreviewDialog = false
                pendingExportJson = null
                pendingExportFileName = null
                favoriteShareFileActions.shareJson(fileName, jsonText)
            },
            onExport = {
                val jsonText = pendingExportJson ?: return@FavoriteShareExportPreviewDialog
                val fileName = pendingExportFileName ?: "yamibo-favorites-${currentTimeMillis()}.json"
                showShareExportPreviewDialog = false
                pendingExportJson = null
                pendingExportFileName = null
                favoriteShareFileActions.exportJson(fileName, jsonText)
            },
        )
    }

    if (showImportPreviewDialog) {
        val preview = importPreview
        if (preview != null) {
        FavoriteShareImportPreviewDialog(
            preview = preview,
            onDismiss = {
                showImportPreviewDialog = false
                pendingImportJson = null
                importPreview = null
            },
            onCreateFolders = {
                importFavorites(
                    FavoriteShareRepository.ImportTarget(
                        mode = FavoriteShareRepository.ImportMode.CreateFolders,
                    )
                )
            },
            onAddToExistingFolders = {
                scope.launch {
                    shareFolderOptions = withContext(Dispatchers.Default) {
                        favoriteRepository.getCategories()
                    }
                    if (shareFolderOptions.isEmpty()) {
                        showSnackbarMessage(i18n("沒有可加入的收藏夾"))
                    } else {
                        importTargetSelection = emptySet()
                        showImportTargetDialog = true
                    }
                }
            },
        )
        }
    }

    if (showImportTargetDialog) {
        FavoriteShareImportTargetDialog(
            options = shareFolderOptions,
            selected = importTargetSelection,
            onDismiss = {
                showImportTargetDialog = false
                importTargetSelection = emptySet()
            },
            onConfirm = { selected ->
                importTargetSelection = selected
                val categoryIds = selected.mapTo(mutableSetOf()) { it.id }
                if (categoryIds.isEmpty()) {
                    showSnackbarMessage(i18n("請選擇要加入的收藏夾"))
                } else {
                    importFavorites(
                        FavoriteShareRepository.ImportTarget(
                            mode = FavoriteShareRepository.ImportMode.AddToExistingFolders,
                            categoryIds = categoryIds,
                        )
                    )
                }
            },
        )
    }

    collectionDraft?.let { draft ->
        CollectionEditorDialog(
            draft = draft,
            onDismiss = {
                val returnState = pickerReturnState
                collectionDraft = null
                if (returnState != null) {
                    pickerOpenedCategoryId = returnState.openedCategoryId
                    pickerCategorySelection = returnState.categorySelection
                    pickerCollectionSelection = returnState.collectionSelection
                    showMoveDialog = true
                    pickerReturnState = null
                }
            },
            onConfirm = { name, colorKey, removeOriginal ->
                scope.launch {
                    try {
                        val current = state as? FavoritePageState.Ready ?: return@launch
                        if (draft.collectionId != null) {
                            withContext(Dispatchers.Default) {
                                favoriteRepository.updateCollection(draft.collectionId, name, colorKey)
                            }
                            collectionDraft = null
                            selectedCollectionIds = emptySet()
                            reload(current.selectedCategoryId)
                            showSnackbarMessage(i18n("已更新集合"))
                        } else if (draft.parentCategoryId != null) {
                            val createdCollection = withContext(Dispatchers.Default) {
                                favoriteRepository.createCollection(
                                    categoryId = draft.parentCategoryId,
                                    name = name,
                                    colorKey = colorKey,
                                )
                            }
                            val returnState = pickerReturnState
                            if (returnState == null) {
                                withContext(Dispatchers.Default) {
                                    favoriteRepository.addItemsToLocations(
                                        itemIds = selectedItemIds,
                                        collectionIds = setOf(createdCollection.id),
                                    )
                                    if (removeOriginal) {
                                        favoriteRepository.removeItemsFromCategory(
                                            selectedItemIds,
                                            draft.parentCategoryId
                                        )
                                    }
                                }
                                collectionDraft = null
                                selectedItemIds = emptySet()
                                selectedCollectionIds = emptySet()
                                reload(current.selectedCategoryId)
                                showSnackbarMessage(i18n("已建立集合"))
                            } else {
                                val (categories, options) = withContext(Dispatchers.Default) {
                                    favoriteRepository.getCategories() to favoriteRepository.getCollectionOptions()
                                }
                                pickerCategories = categories
                                pickerOptions = options
                                pickerCategorySelection = returnState.categorySelection
                                pickerCollectionSelection = returnState.collectionSelection
                                pickerOpenedCategoryId = returnState.openedCategoryId
                                collectionDraft = null
                                showMoveDialog = true
                                pickerReturnState = null
                                showSnackbarMessage(i18n("已新增集合"))
                            }
                        } else {
                            collectionDraft = null
                        }
                    } catch (error: IllegalArgumentException) {
                        showSnackbarMessage(error.message?.let { i18n(it) }?.takeIf { it.isNotBlank() } ?: i18n("保存失敗"))
                    }
                }
            },
        )
    }

    if (showSortDialog) {
        FavoriteSortDialog(
            selected = sortMode,
            descending = sortDescending,
            onDismiss = { showSortDialog = false },
            onSelect = { mode ->
                if (sortMode == mode) {
                    appSettingsRepository.favoriteSortDescending.setValue(!sortDescending)
                } else {
                    appSettingsRepository.favoriteSortMode.setValue(mode)
                    appSettingsRepository.favoriteSortDescending.setValue(
                        mode != FavoriteSortMode.NAME && mode != FavoriteSortMode.FORUM_NAME
                    )
                }
            },
            onConfirm = { showSortDialog = false },
        )
    }

    if (showGridModeDialog) {
        FavoriteGridModeDialog(
            selected = favoriteGridMode,
            onDismiss = { showGridModeDialog = false },
            onSelect = {
                appSettingsRepository.favoriteGridMode.setValue(it)
                showGridModeDialog = false
            },
        )
    }

    if (showDeleteConfirmDialog) {
        FavoriteRemovalConfirmDialog(
            onDismiss = {
                showDeleteConfirmDialog = false
                deleteRequest = null
            },
            onConfirm = { skipNextTime ->
                appSettingsRepository.skipFavoriteRemovalConfirm.setValue(skipNextTime)
                showDeleteConfirmDialog = false
                val request = deleteRequest ?: return@FavoriteRemovalConfirmDialog
                scope.launch {
                    if (request.hasMultiPathItems) {
                        showDeleteScopeDialog = true
                    } else {
                        if (request.hasRemoteSyncedItems && appSettingsRepository.favoriteRemoveSyncPromptEnabled.getValue()) {
                            showDeleteRemoteSyncDialog = true
                        } else {
                            performDeleteAllFavorites(
                                request,
                                removeRemote = request.hasRemoteSyncedItems && appSettingsRepository.favoriteRemoveSyncDefault.getValue(),
                            )
                        }
                    }
                }
            },
        )
    }

    if (showDeleteRemoteSyncDialog) {
        FavoriteRemoveSyncConfirmDialog(
            onDismiss = {
                showDeleteRemoteSyncDialog = false
                deleteRequest = null
            },
            onConfirm = { rememberChoice, syncRemote ->
                if (rememberChoice) {
                    appSettingsRepository.favoriteRemoveSyncPromptEnabled.setValue(false)
                    appSettingsRepository.favoriteRemoveSyncDefault.setValue(syncRemote)
                }
                showDeleteRemoteSyncDialog = false
                val request = deleteRequest ?: return@FavoriteRemoveSyncConfirmDialog
                scope.launch { performDeleteAllFavorites(request, removeRemote = syncRemote) }
            },
        )
    }

    if (showDeleteScopeDialog) {
        AlertDialog(
            onDismissRequest = {
                showDeleteScopeDialog = false
                deleteRequest = null
            },
            title = { Text(i18n("是否要刪除所有路徑下的收藏"), color = colors.textStrong, fontWeight = FontWeight.Bold) },
            text = { Text(i18n("目前選取的收藏中，至少有一項存在於多個收藏路徑。"), color = colors.textDark, fontSize = 14.sp) },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionChip(i18n("取消")) {
                        showDeleteScopeDialog = false
                        deleteRequest = null
                    }
                    ActionChip(i18n("是")) {
                        val request = deleteRequest ?: return@ActionChip
                        showDeleteScopeDialog = false
                        scope.launch {
                            if (request.hasRemoteSyncedItems && appSettingsRepository.favoriteRemoveSyncPromptEnabled.getValue()) {
                                showDeleteRemoteSyncDialog = true
                            } else {
                                performDeleteAllFavorites(
                                    request,
                                    removeRemote = request.hasRemoteSyncedItems && appSettingsRepository.favoriteRemoveSyncDefault.getValue(),
                                )
                            }
                        }
                    }
                    Surface(
                        onClick = {
                            val request = deleteRequest ?: return@Surface
                            showDeleteScopeDialog = false
                            scope.launch { performDeleteCurrentDirectory(request) }
                        },
                        shape = RoundedCornerShape(10.dp),
                        color = colors.brownDeep,
                    ) {
                        Text(
                            text = i18n("僅移除當下目錄"),
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        )
                    }
                }
            },
            confirmButton = {},
            containerColor = colors.creamSurface,
        )
    }
}

private suspend fun favoriteItemLastReadAt(
    item: FavoriteItem,
    repo: ReadHistoryRepository,
    isMangaMode: Boolean,
): Long {
    return when (item.targetType) {
        FavoriteTargetType.ThreadNormal -> repo.getPosition(ThreadId(item.targetId.toInt()), ReadHistoryRepository.ThreadEntryType.Normal)?.lastVisitTime ?: 0L
        FavoriteTargetType.ThreadNovel -> repo.getPosition(ThreadId(item.targetId.toInt()), ReadHistoryRepository.ThreadEntryType.Novel, item.authorId)?.lastVisitTime ?: 0L
        FavoriteTargetType.TagManga -> if (isMangaMode) {
            repo.getTagMangaReaderModeHistoryPosition(TagId(item.targetId.toInt()))?.lastVisitTime ?: 0L
        } else {
            repo.getTagCatalogThreadHistoryPosition(TagId(item.targetId.toInt()))?.lastVisitTime ?: 0L
        }
        FavoriteTargetType.RssSearch -> if (isMangaMode) {
            repo.getRssSearchReaderModeHistoryPosition(item.targetId)?.lastVisitTime ?: 0L
        } else {
            repo.getRssCatalogThreadHistoryPosition(item.targetId)?.lastVisitTime ?: 0L
        }
    }
}

private fun favoriteItemEffectiveUpdatedAt(item: FavoriteItem): Long {
    return item.lastUpdatedTime?.takeIf { it > 0L } ?: item.lastFavoriteStatusUpdateAt
}

private fun sortCollections(
    items: List<FavoriteCollectionWithItems>,
    mode: FavoriteSortMode,
    descending: Boolean,
    lastReadMap: Map<Long, Long>,
    remoteFavoriteOrderMap: Map<Long, Long>,
): List<FavoriteCollectionWithItems> {
    val sorted = when (mode) {
        FavoriteSortMode.DEFAULT -> items.sortedWith(compareBy<FavoriteCollectionWithItems> { it.collection.sortOrder }.thenByDescending { it.collection.createdAt })
        FavoriteSortMode.UPDATED_AT -> items.sortedByDescending {
            maxOf(it.collection.updatedAt, it.items.maxOfOrNull(::favoriteItemEffectiveUpdatedAt) ?: 0L)
        }
        FavoriteSortMode.FAVORITED_ORDER -> {
            val itemComparator = compareFavoriteItemsByOrder(descending, remoteFavoriteOrderMap)
            items.sortedWith { left, right ->
                val leftLead = left.items.sortedWith(itemComparator).firstOrNull()
                val rightLead = right.items.sortedWith(itemComparator).firstOrNull()
                when {
                    leftLead == null && rightLead == null -> left.collection.name.lowercase().compareTo(right.collection.name.lowercase())
                    leftLead == null -> 1
                    rightLead == null -> -1
                    else -> itemComparator.compare(leftLead, rightLead)
                }
            }
        }
        FavoriteSortMode.NAME -> items.sortedBy { it.collection.name.lowercase() }
        FavoriteSortMode.FORUM_NAME -> items.sortedWith(
            compareBy<FavoriteCollectionWithItems> {
                it.items
                    .groupingBy { item -> item.forumName?.lowercase().orEmpty() }
                    .eachCount()
                    .maxByOrNull { entry -> entry.value }
                    ?.key
                    .orEmpty()
            }.thenBy { it.collection.name.lowercase() }
        )
        FavoriteSortMode.LAST_READ -> items.sortedByDescending { it.items.maxOfOrNull { item -> lastReadMap[item.id] ?: 0L } ?: 0L }
    }
    return if (mode == FavoriteSortMode.FAVORITED_ORDER || descending) sorted else sorted.reversed()
}

private fun sortItems(
    items: List<FavoriteItem>,
    mode: FavoriteSortMode,
    descending: Boolean,
    lastReadMap: Map<Long, Long>,
    remoteFavoriteOrderMap: Map<Long, Long>,
): List<FavoriteItem> {
    val sorted = when (mode) {
        FavoriteSortMode.DEFAULT -> items.sortedByDescending(FavoriteItem::createdAt)
        FavoriteSortMode.UPDATED_AT -> items.sortedByDescending(::favoriteItemEffectiveUpdatedAt)
        FavoriteSortMode.FAVORITED_ORDER -> items.sortedWith(compareFavoriteItemsByOrder(descending, remoteFavoriteOrderMap))
        FavoriteSortMode.NAME -> items.sortedBy { it.title.lowercase() }
        FavoriteSortMode.FORUM_NAME -> items.sortedWith(compareBy<FavoriteItem> { it.forumName?.lowercase().orEmpty() }.thenBy { it.title.lowercase() })
        FavoriteSortMode.LAST_READ -> items.sortedByDescending { lastReadMap[it.id] ?: 0L }
    }
    return if (mode == FavoriteSortMode.FAVORITED_ORDER || descending) sorted else sorted.reversed()
}

private fun compareFavoriteItemsByOrder(
    descending: Boolean,
    remoteFavoriteOrderMap: Map<Long, Long>,
): Comparator<FavoriteItem> {
    return Comparator { left, right ->
        val leftRemoteOrder = remoteFavoriteOrderMap[left.id]
        val rightRemoteOrder = remoteFavoriteOrderMap[right.id]
        when {
            leftRemoteOrder != null && rightRemoteOrder != null -> {
                if (descending) {
                    rightRemoteOrder.compareTo(leftRemoteOrder)
                } else {
                    leftRemoteOrder.compareTo(rightRemoteOrder)
                }
            }

            leftRemoteOrder == null && rightRemoteOrder == null -> {
                if (descending) {
                    right.createdAt.compareTo(left.createdAt)
                } else {
                    left.createdAt.compareTo(right.createdAt)
                }
            }

            descending -> if (leftRemoteOrder != null) -1 else 1
            else -> if (leftRemoteOrder == null) -1 else 1
        }.takeIf { it != 0 } ?: left.title.lowercase().compareTo(right.title.lowercase())
    }
}

private suspend fun readySearchCounts(
    favoriteRepository: me.thenano.yamibo.yamibo_app.repository.FavoriteStoreRepository,
    query: String,
): Map<Long, Int> {
    val categories = favoriteRepository.getCategories()
    return buildMap {
        categories.forEach { category ->
            val content = favoriteRepository.getCategoryContent(category.id)
            put(category.id, categoryMatchCount(content, query))
        }
    }
}

private suspend fun readyFavoriteCounts(
    favoriteRepository: me.thenano.yamibo.yamibo_app.repository.FavoriteStoreRepository,
): Map<Long, Int> {
    val categories = favoriteRepository.getCategories()
    return buildMap {
        categories.forEach { category ->
            put(category.id, categoryFavoriteCount(favoriteRepository.getCategoryContent(category.id)))
        }
    }
}

private fun categoryFavoriteCount(content: FavoriteCategoryContent): Int {
    return content.directItems.size + content.collections.sumOf { it.items.size }
}

private suspend fun readyFavoriteForumCounts(
    favoriteRepository: me.thenano.yamibo.yamibo_app.repository.FavoriteStoreRepository,
): List<FavoriteForumFilterOption> {
    return favoriteRepository.getAllFavoriteItems()
        .groupBy(::favoriteForumKey)
        .map { (key, items) ->
            FavoriteForumFilterOption(
                forumKey = key,
                label = favoriteForumLabel(items.firstOrNull()),
                count = items.size,
            )
        }
        .sortedWith(compareByDescending<FavoriteForumFilterOption> { it.count }.thenBy { it.label })
}

private fun favoriteForumKey(item: FavoriteItem): String {
    when (item.targetType) {
        FavoriteTargetType.TagManga -> return "tag"
        FavoriteTargetType.RssSearch -> return "rss"
        else -> Unit
    }
    return item.forumId?.value?.toString()
        ?: item.forumName?.takeIf { it.isNotBlank() }?.let { "name:$it" }
        ?: "unknown"
}

private fun favoriteForumLabel(item: FavoriteItem?): String {
    when (item?.targetType) {
        FavoriteTargetType.TagManga -> return i18n("標籤")
        FavoriteTargetType.RssSearch -> return i18n("RSS 搜尋目錄")
        else -> Unit
    }
    return item?.forumName?.takeIf { it.isNotBlank() } ?: i18n("未知版塊")
}

private fun categoryMatchCount(
    content: FavoriteCategoryContent,
    query: String,
): Int {
    val collectionCount = content.collections.count { collectionMatchesQuery(it, query) }
    val itemCount = content.directItems.count { favoriteItemMatchesQuery(it, query) }
    return collectionCount + itemCount
}

private fun collectionMatchesQuery(
    collection: FavoriteCollectionWithItems,
    query: String,
): Boolean {
    return collection.collection.name.contains(query, ignoreCase = true) ||
        collection.items.any { favoriteItemMatchesQuery(it, query) }
}

private fun favoriteItemMatchesQuery(
    item: FavoriteItem,
    query: String,
): Boolean {
    return item.title.contains(query, ignoreCase = true) ||
        item.forumName?.contains(query, ignoreCase = true) == true
}

private fun openFavoriteItem(navigator: ComposableNavigator, item: FavoriteItem) {
    when (item.targetType) {
        FavoriteTargetType.ThreadNormal -> navigator.navigate(IThreadReaderScreen(tid = ThreadId(item.targetId.toInt()), title = item.title, threadType = ReadHistoryRepository.ThreadEntryType.Normal))
        FavoriteTargetType.ThreadNovel -> navigator.navigate(INovelThreadDetailScreen(tid = ThreadId(item.targetId.toInt()), title = item.title, authorId = item.authorId))
        FavoriteTargetType.TagManga -> navigator.navigate(ITagDetailScreen(tagId = TagId(item.targetId.toInt()), title = item.title))
        FavoriteTargetType.RssSearch -> navigator.navigate(IRssSearchSubscriptionDetailScreen(subscriptionId = item.targetId))
    }
}

private fun Set<Long>.toggle(id: Long): Set<Long> = if (id in this) this - id else this + id

private fun currentSyncRunId(state: FavoriteSyncState): String? {
    return when (state) {
        FavoriteSyncState.Idle -> null
        is FavoriteSyncState.Running -> state.snapshot.runId
        is FavoriteSyncState.Interrupted -> state.snapshot.runId
        is FavoriteSyncState.Failed -> state.snapshot.runId
        is FavoriteSyncState.Completed -> state.snapshot.runId
    }
}

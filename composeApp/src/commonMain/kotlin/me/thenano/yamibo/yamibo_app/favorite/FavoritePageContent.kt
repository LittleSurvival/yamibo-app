package me.thenano.yamibo.yamibo_app.favorite

import me.thenano.yamibo.yamibo_app.components.navigation.NavigationBackSymbol
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import me.thenano.yamibo.yamibo_app.favorite.components.*
import me.thenano.yamibo.yamibo_app.favorite.sync.FavoriteSyncStatusCard
import me.thenano.yamibo.yamibo_app.i18n.i18n
import me.thenano.yamibo.yamibo_app.i18n.localizedLabel
import me.thenano.yamibo.yamibo_app.repository.FavoriteSyncRepository.FavoriteSyncState
import me.thenano.yamibo.yamibo_app.repository.FavoriteStoreRepository
import me.thenano.yamibo.yamibo_app.repository.FavoriteStoreRepository.FavoriteCollectionWithItems
import me.thenano.yamibo.yamibo_app.repository.FavoriteStoreRepository.FavoriteItem
import me.thenano.yamibo.yamibo_app.repository.settings.FavoriteGridMode
import me.thenano.yamibo.yamibo_app.repository.settings.FavoriteSortMode
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme

@Composable
internal fun FavoritePageContent(
    ready: FavoritePageState.Ready,
    mode: FavoritePageMode,
    searchActive: Boolean,
    searchQuery: String,
    searchCategoryMatchCounts: Map<Long, Int>,
    favoriteFilterActive: Boolean,
    showFavoriteCounts: Boolean,
    sortMode: FavoriteSortMode,
    sortDescending: Boolean,
    favoriteGridMode: FavoriteGridMode,
    gridEntries: List<FavoriteGridEntry>,
    openedCollection: FavoriteCollectionWithItems?,
    selectedItemIds: Set<Long>,
    selectedCollectionIds: Set<Long>,
    lastReadMap: Map<Long, Long>,
    syncState: FavoriteSyncState,
    hiddenFavoritePageRuns: Set<String>,
    onCreateCategory: () -> Unit,
    onManageCategory: () -> Unit,
    onEnterSearch: () -> Unit,
    onShowFilter: () -> Unit,
    onToggleFavoriteCounts: () -> Unit,
    onShareFavorites: () -> Unit,
    onLoadFavorites: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSearchSubmit: () -> Unit,
    onExitSearch: () -> Unit,
    onSyncFavorites: () -> Unit,
    onOpenSyncProgress: () -> Unit,
    onHideSyncCard: () -> Unit,
    onResumeSync: () -> Unit,
    onInterruptSync: () -> Unit,
    onSelectCategory: (Long) -> Unit,
    onShowGridMode: () -> Unit,
    onShowSort: () -> Unit,
    onBackToTab: () -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onCancelSelection: () -> Unit,
    onOpenBatchDownload: () -> Unit,
    onOpenMoveDialog: () -> Unit,
    onOpenMergeDialog: () -> Unit,
    onEditSelectedCollection: () -> Unit,
    onDissolveSelectedCollections: () -> Unit,
    onDeleteSelectedItems: () -> Unit,
    onOpenCollection: (Long) -> Unit,
    onToggleItem: (Long) -> Unit,
    onToggleCollection: (Long) -> Unit,
    onEnterSelectItem: (Long) -> Unit,
    onEnterSelectCollection: (Long) -> Unit,
    onOpenItem: (FavoriteItem) -> Unit,
) {
    val colors = YamiboTheme.colors
    val selectedCount = selectedItemIds.size + selectedCollectionIds.size
    Column(Modifier.fillMaxSize()) {
        when (mode) {
            FavoritePageMode.Normal -> {
                FavoriteHeaderMenuRow(
                    title = i18n("我的收藏"),
                    filterActive = favoriteFilterActive,
                    showFavoriteCounts = showFavoriteCounts,
                    onSearch = onEnterSearch,
                    onShowFilter = onShowFilter,
                    onToggleFavoriteCounts = onToggleFavoriteCounts,
                    onCreateCategory = onCreateCategory,
                    onManageCategory = onManageCategory,
                    onSyncFavorites = onSyncFavorites,
                    onShareFavorites = onShareFavorites,
                    onLoadFavorites = onLoadFavorites,
                )
            }
            FavoritePageMode.Search -> {
                FavoriteSearchTopBar(
                    query = searchQuery,
                    onQueryChange = onSearchQueryChange,
                    onSearch = onSearchSubmit,
                    onBack = onExitSearch,
                )
            }
            FavoritePageMode.Select -> {
                SelectionHeaderRows(
                    title = i18n("已選 {} 項", selectedCount),
                    actions = buildList {
                        add(i18n("全選") to onSelectAll)
                        if (selectedCount > 0) add(i18n("下載") to onOpenBatchDownload)
                        if (selectedItemIds.isNotEmpty() && selectedCollectionIds.isEmpty()) {
                            add(i18n("移動") to onOpenMoveDialog)
                            add(i18n("合成集合") to onOpenMergeDialog)
                            add(i18n("刪除") to onDeleteSelectedItems)
                        }
                        if (selectedCollectionIds.size == 1 && selectedItemIds.isEmpty()) add(i18n("編輯") to onEditSelectedCollection)
                        if (selectedCollectionIds.isNotEmpty() && selectedItemIds.isEmpty() && openedCollection == null) add(i18n("解散") to onDissolveSelectedCollections)
                        if (selectedCollectionIds.isNotEmpty() && selectedItemIds.isEmpty()) add(i18n("清空") to onClearSelection)
                    },
                    onCancel = onCancelSelection,
                )
            }
        }

        @Suppress("DEPRECATION")
        ScrollableTabRow(
            selectedTabIndex = ready.categories.indexOfFirst { it.id == ready.selectedCategoryId }.coerceAtLeast(0),
            edgePadding = 8.dp,
            containerColor = colors.creamBackground,
            divider = { HorizontalDivider(color = colors.brownPrimary.copy(alpha = 0.12f)) },
            indicator = {},
        ) {
            ready.categories.forEach { category ->
                Tab(
                    selected = category.id == ready.selectedCategoryId,
                    onClick = { onSelectCategory(category.id) },
                    text = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(category.name, fontSize = 15.sp, fontWeight = if (category.id == ready.selectedCategoryId) FontWeight.Bold else FontWeight.Medium)
                            if (searchCategoryMatchCounts.containsKey(category.id)) {
                                Text(
                                    text = searchCategoryMatchCounts[category.id]?.toString() ?: "0",
                                    fontSize = 11.sp,
                                    color = colors.textDark.copy(alpha = 0.65f),
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Box(
                                Modifier.width(48.dp).height(3.dp).background(
                                    if (category.id == ready.selectedCategoryId) colors.brownDeep else Color.Transparent,
                                    RoundedCornerShape(999.dp),
                                )
                            )
                        }
                    },
                    selectedContentColor = colors.textStrong,
                    unselectedContentColor = colors.textDark.copy(alpha = 0.66f),
                )
            }
        }

        val runningSyncRunId = (syncState as? FavoriteSyncState.Running)?.snapshot?.runId
        if (runningSyncRunId != null && runningSyncRunId !in hiddenFavoritePageRuns) {
            FavoriteSyncStatusCard(
                state = syncState,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                onOpenProgress = onOpenSyncProgress,
                onDismiss = onHideSyncCard,
                onResume = onResumeSync,
                onInterrupt = onInterruptSync,
                messageMaxHeight = 120.dp,
                contentMaxHeight = 360.dp,
            )
        }

        if (openedCollection != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            ActionChip(NavigationBackSymbol, onBackToTab)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(openedCollection.collection.name, color = colors.textStrong, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(i18n("{} 項", openedCollection.items.size), color = colors.textDark.copy(alpha = 0.5f), fontSize = 12.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionChip(i18n("排列: {}", favoriteGridMode.localizedLabel()), onShowGridMode)
                    ActionChip(i18n("排序: {}{}", sortMode.localizedLabel(), if (sortDescending) " ↓" else " ↑"), onShowSort)
                }
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(ready.categories.firstOrNull { it.id == ready.selectedCategoryId }?.name.orEmpty(), color = colors.textDark.copy(alpha = 0.6f), fontSize = 13.sp, modifier = Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionChip(i18n("排列: {}", favoriteGridMode.localizedLabel()), onShowGridMode)
                    ActionChip(i18n("排序: {}{}", sortMode.localizedLabel(), if (sortDescending) " ↓" else " ↑"), onShowSort)
                }
            }
        }

        if (openedCollection != null) {
            Box(Modifier.fillMaxSize()) {
                if (gridEntries.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(i18n("這個集合還沒有內容"), color = colors.textDark.copy(alpha = 0.52f), fontSize = 16.sp)
                    }
                } else {
                    FavoriteGridLayout(
                        entries = gridEntries,
                        favoriteGridMode = favoriteGridMode,
                        scrollResetKey = "collection:${openedCollection.collection.id}|$sortMode|$sortDescending|$favoriteGridMode",
                        selecting = mode == FavoritePageMode.Select,
                        selectedItemIds = selectedItemIds,
                        selectedCollectionIds = selectedCollectionIds,
                        lastReadMap = lastReadMap,
                        onOpenCollection = onOpenCollection,
                        onToggleCollection = onToggleCollection,
                        onEnterSelectCollection = onEnterSelectCollection,
                        onOpenItem = onOpenItem,
                        onToggleItem = onToggleItem,
                        onEnterSelectItem = onEnterSelectItem,
                    )
                }
            }
        } else {
            val selectedTabIndex = ready.categories.indexOfFirst { it.id == ready.selectedCategoryId }.coerceAtLeast(0)
            val previousTabIndex = remember { mutableIntStateOf(selectedTabIndex) }
            val density = LocalDensity.current
            val direction = when {
                selectedTabIndex > previousTabIndex.intValue -> 1f
                selectedTabIndex < previousTabIndex.intValue -> -1f
                else -> 0f
            }
            val initialOffsetPx = with(density) { (16.dp * direction).toPx() }
            val initialAlpha = if (direction == 0f) 1f else 0.28f
            val contentOffsetX = remember(selectedTabIndex) { Animatable(initialOffsetPx) }
            val contentAlpha = remember(selectedTabIndex) { Animatable(initialAlpha) }
            LaunchedEffect(selectedTabIndex) {
                if (direction == 0f) {
                    contentOffsetX.snapTo(0f)
                    contentAlpha.snapTo(1f)
                } else {
                    launch { contentOffsetX.animateTo(targetValue = 0f, animationSpec = tween(durationMillis = 150)) }
                    contentAlpha.animateTo(targetValue = 1f, animationSpec = tween(durationMillis = 150))
                }
                previousTabIndex.intValue = selectedTabIndex
            }
            Box(Modifier.fillMaxSize().graphicsLayer { translationX = contentOffsetX.value; alpha = contentAlpha.value }) {
                Box(Modifier.fillMaxSize()) {
                    if (gridEntries.isEmpty()) {
                        val selectedCategory = ready.categories.firstOrNull { it.id == ready.selectedCategoryId }
                        val showDefaultSyncHint = !searchActive &&
                            selectedCategory?.name == FavoriteStoreRepository.DEFAULT_CATEGORY_NAME
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(i18n("目前還沒有收藏"), color = colors.textDark.copy(alpha = 0.52f), fontSize = 16.sp)
                                if (showDefaultSyncHint) {
                                    Text(
                                        text = i18n("若收藏尚未同步，可使用右上角 ⋮ > 同步百合會收藏 同步"),
                                        color = colors.textDark.copy(alpha = 0.42f),
                                        fontSize = 13.sp,
                                    )
                                }
                            }
                        }
                    } else {
                        FavoriteGridLayout(
                            entries = gridEntries,
                            favoriteGridMode = favoriteGridMode,
                            scrollResetKey = "category:${ready.selectedCategoryId}|$sortMode|$sortDescending|$favoriteGridMode",
                            selecting = mode == FavoritePageMode.Select,
                            selectedItemIds = selectedItemIds,
                            selectedCollectionIds = selectedCollectionIds,
                            lastReadMap = lastReadMap,
                            onOpenCollection = onOpenCollection,
                            onToggleCollection = onToggleCollection,
                            onEnterSelectCollection = onEnterSelectCollection,
                            onOpenItem = onOpenItem,
                            onToggleItem = onToggleItem,
                            onEnterSelectItem = onEnterSelectItem,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun FavoriteGridLayout(
    entries: List<FavoriteGridEntry>,
    favoriteGridMode: FavoriteGridMode,
    scrollResetKey: Any?,
    selecting: Boolean,
    selectedItemIds: Set<Long>,
    selectedCollectionIds: Set<Long>,
    lastReadMap: Map<Long, Long>,
    onOpenCollection: (Long) -> Unit,
    onToggleCollection: (Long) -> Unit,
    onEnterSelectCollection: (Long) -> Unit,
    onOpenItem: (FavoriteItem) -> Unit,
    onToggleItem: (Long) -> Unit,
    onEnterSelectItem: (Long) -> Unit,
) {
    val stateKey = scrollResetKey ?: "default"
    val listStates = remember { mutableStateMapOf<Any, LazyListState>() }
    val gridStates = remember { mutableStateMapOf<Any, LazyGridState>() }
    val staggeredGridStates = remember { mutableStateMapOf<Any, LazyStaggeredGridState>() }
    val contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 88.dp)
    val listState = listStates.getOrPut(stateKey) { LazyListState() }
    val gridState = gridStates.getOrPut(stateKey) { LazyGridState() }
    val staggeredGridState = staggeredGridStates.getOrPut(stateKey) { LazyStaggeredGridState() }

    when (favoriteGridMode) {
        FavoriteGridMode.FIXED_GRID -> {
            Box(Modifier.fillMaxSize()) {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = contentPadding,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(entries, key = { it.key }, span = { GridItemSpan(1) }) { entry ->
                        FavoriteGridEntryCard(
                            entry,
                            favoriteGridMode,
                            selecting,
                            selectedItemIds,
                            selectedCollectionIds,
                            lastReadMap,
                            onOpenCollection,
                            onToggleCollection,
                            onEnterSelectCollection,
                            onOpenItem,
                            onToggleItem,
                            onEnterSelectItem
                        )
                    }
                }
                FavoriteGridScrollbar(state = gridState, totalItems = entries.size, modifier = Modifier.align(Alignment.CenterEnd))
            }
        }
        FavoriteGridMode.STAGGERED -> {
            Box(Modifier.fillMaxSize()) {
                LazyVerticalStaggeredGrid(
                    state = staggeredGridState,
                    columns = StaggeredGridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = contentPadding,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalItemSpacing = 10.dp,
                ) {
                    items(entries, key = { it.key }, span = { StaggeredGridItemSpan.SingleLane }) { entry ->
                        FavoriteGridEntryCard(
                            entry,
                            favoriteGridMode,
                            selecting,
                            selectedItemIds,
                            selectedCollectionIds,
                            lastReadMap,
                            onOpenCollection,
                            onToggleCollection,
                            onEnterSelectCollection,
                            onOpenItem,
                            onToggleItem,
                            onEnterSelectItem
                        )
                    }
                }
                FavoriteStaggeredScrollbar(state = staggeredGridState, totalItems = entries.size, modifier = Modifier.align(Alignment.CenterEnd))
            }
        }
        FavoriteGridMode.ROW_CARD,
        FavoriteGridMode.ROW_CARD_TEXT -> {
            Box(Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = contentPadding,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(entries, key = { it.key }) { entry ->
                        FavoriteGridEntryCard(
                            entry,
                            favoriteGridMode,
                            selecting,
                            selectedItemIds,
                            selectedCollectionIds,
                            lastReadMap,
                            onOpenCollection,
                            onToggleCollection,
                            onEnterSelectCollection,
                            onOpenItem,
                            onToggleItem,
                            onEnterSelectItem
                        )
                    }
                }
                FavoriteListScrollbar(state = listState, totalItems = entries.size, modifier = Modifier.align(Alignment.CenterEnd))
            }
        }
    }
}

package me.thenano.yamibo.yamibo_app.forum

import YamiboIcons
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.github.littlesurvival.YamiboForum
import io.github.littlesurvival.YamiboRoute
import io.github.littlesurvival.core.YamiboResult
import io.github.littlesurvival.dto.model.ThreadSummary
import io.github.littlesurvival.dto.model.User
import io.github.littlesurvival.dto.page.FilterType
import io.github.littlesurvival.dto.page.ForumPage
import io.github.littlesurvival.dto.page.OrderType
import io.github.littlesurvival.dto.page.PinnedItem
import io.github.littlesurvival.dto.value.ForumId
import kotlinx.coroutines.launch
import me.thenano.yamibo.yamibo_app.IMainScreen
import me.thenano.yamibo.yamibo_app.LocalAuthRepository
import me.thenano.yamibo.yamibo_app.LocalForumRepository
import me.thenano.yamibo.yamibo_app.MainTab
import me.thenano.yamibo.yamibo_app.components.controls.YamiboSingleSelectDialog
import me.thenano.yamibo.yamibo_app.components.feedback.YamiboDetailedErrorContent
import me.thenano.yamibo.yamibo_app.components.navigation.YamiboTopBar
import me.thenano.yamibo.yamibo_app.components.navigation.YamiboTopBarIconAction
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme
import me.thenano.yamibo.yamibo_app.forum.components.*
import me.thenano.yamibo.yamibo_app.forum.search.ISearchScreen
import me.thenano.yamibo.yamibo_app.i18n.i18n
import me.thenano.yamibo.yamibo_app.navigation.LocalNavigator
import me.thenano.yamibo.yamibo_app.thread.detail.novel.INovelThreadDetailScreen
import me.thenano.yamibo.yamibo_app.thread.reader.IThreadReaderScreen
import me.thenano.yamibo.yamibo_app.userspace.IUserSpaceScreen
import me.thenano.yamibo.yamibo_app.webview.action.IActionWebView

/** Forum page state */
private sealed interface ForumState {
    data object Loading : ForumState
    data class Success(val page: ForumPage) : ForumState
    data class Error(val message: String) : ForumState
}

private data class ForumPageSnapshot(
    val state: ForumState,
    val currentPage: Int,
    val filterType: FilterType?,
    val orderType: OrderType?,
)

/** Main Forum Screen Entry */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForumPageScreen(fid: ForumId, name: String) {
    val colors = YamiboTheme.colors
    val forumRepository = LocalForumRepository.current
    val authRepository = LocalAuthRepository.current
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val feedbackController = me.thenano.yamibo.yamibo_app.LocalAppFeedbackController.current
    val appTaskManager = me.thenano.yamibo.yamibo_app.LocalAppTaskManager.current
    val favoriteForums by forumRepository.favoriteForums.collectAsState()
    val isFavoriteForum = fid in favoriteForums

    var state by remember { mutableStateOf<ForumState>(ForumState.Loading) }
    var currentPage by remember { mutableIntStateOf(1) }
    var isRefreshing by remember { mutableStateOf(false) }
    var selectedOrderType by remember { mutableStateOf<OrderType?>(null) }
    var selectedFilterType by remember { mutableStateOf<FilterType?>(null) }
    var showOrderDialog by remember { mutableStateOf(false) }
    var showFilterDialog by remember { mutableStateOf(false) }
    var loadGeneration by remember { mutableIntStateOf(0) }
    val pageHistory = remember { mutableStateListOf<ForumPageSnapshot>() }

    suspend fun loadPage(
        page: Int,
        filterType: FilterType? = selectedFilterType,
        orderType: OrderType? = selectedOrderType,
        preferCache: Boolean = true,
        requestGeneration: Int = loadGeneration,
    ) {
        val cached = if (preferCache) forumRepository.getCachedForumPage(fid, page, filterType, orderType) else null
        if (cached != null) {
            if (requestGeneration != loadGeneration) return
            currentPage = cached.pageNav?.currentPage ?: page
            state = ForumState.Success(cached)
            return
        }

        val result = forumRepository.fetchForum(fid, page, filterType, orderType)
        if (requestGeneration != loadGeneration) return
        state =
            when (result) {
                is YamiboResult.Success -> {
                    currentPage = result.value.pageNav?.currentPage ?: page
                    ForumState.Success(result.value)
                }

                else -> ForumState.Error(i18n(result.message()))
            }
    }

    fun restorePreviousPage(): Boolean {
        val snapshot = pageHistory.removeLastOrNull() ?: return false
        loadGeneration += 1
        state = snapshot.state
        currentPage = snapshot.currentPage
        selectedFilterType = snapshot.filterType
        selectedOrderType = snapshot.orderType
        return true
    }

    fun navigateToPage(page: Int) {
        if (page == currentPage) return
        pageHistory.add(
            ForumPageSnapshot(
                state = state,
                currentPage = currentPage,
                filterType = selectedFilterType,
                orderType = selectedOrderType,
            )
        )
        loadGeneration += 1
        val requestGeneration = loadGeneration
        state = ForumState.Loading
        scope.launch { loadPage(page, requestGeneration = requestGeneration) }
    }

    LaunchedEffect(fid) {
        pageHistory.clear()
        loadGeneration += 1
        val requestGeneration = loadGeneration
        val cached = forumRepository.getCachedForumPage(fid, filterType = selectedFilterType, orderType = selectedOrderType)
        if (cached != null) {
            if (requestGeneration != loadGeneration) return@LaunchedEffect
            currentPage = cached.pageNav?.currentPage ?: 1
            state = ForumState.Success(cached)
            return@LaunchedEffect
        }
        loadPage(1, requestGeneration = requestGeneration)
    }

    DisposableEffect(navigator) {
        val handler = { restorePreviousPage() }
        navigator.backHandlers.add(handler)
        onDispose { navigator.backHandlers.remove(handler) }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = colors.creamBackground,
        topBar = {
            /** Sticky top bar: back + forum name (always visible) */
            val forumName =
                when (val s = state) {
                    is ForumState.Success -> s.page.forum.name
                    else -> name
                }
            ForumTopBar(
                title = forumName,
                onBack = { if (!restorePreviousPage()) navigator.pop() },
                onSearch = { navigator.navigate(ISearchScreen(fid)) },
                onPostThread = {
                    navigator.navigate(
                        IActionWebView(
                            title = i18n("發表帖子"),
                            initialUrl = YamiboRoute.PostThread(fid).build(),
                            successCondition = { url -> url.contains("mod=forumdisplay") && url.contains("fid=") },
                            onSuccess = {
                                scope.launch {
                                    feedbackController.post(i18n("帖子已發表，請刷新頁面查看"))
                                }
                            },
                        )
                    )
                },
                onStarClick = {
                    val formHash = authRepository.currentUser()?.formHash
                    if (formHash == null) {
                        feedbackController.post(
                            message = i18n("未登入，請先登入後再收藏"),
                            duration = me.thenano.yamibo.yamibo_app.feedback.AppFeedbackDuration.Short
                        )
                        return@ForumTopBar
                    }
                    val removeFavorite = isFavoriteForum
                    val feedbackGroup = "forum-favorite:${fid.value}"
                    appTaskManager.launch(
                        key = me.thenano.yamibo.yamibo_app.task.AppTaskKey("forum-favorite-toggle:${fid.value}"),
                    ) {
                        if (removeFavorite) {
                            feedbackController.post(i18n("正在解除本版收藏..."), groupKey = feedbackGroup)
                            val result = forumRepository.removeFavorite(fid, formHash)
                            feedbackController.post(
                                message = if (result is YamiboResult.Success) {
                                    i18n("已解除本版收藏")
                                } else {
                                    i18n(result.message())
                                },
                                groupKey = feedbackGroup,
                            )
                        } else {
                            feedbackController.post(i18n("正在收藏本版..."), groupKey = feedbackGroup)
                            val result = forumRepository.addFavorite(fid, formHash)
                            feedbackController.post(
                                message = if (result is YamiboResult.Success) {
                                    i18n("已收藏本版")
                                } else {
                                    i18n(result.message())
                                },
                                groupKey = feedbackGroup,
                            )
                        }
                    }
                },
                isFavorite = isFavoriteForum,
                onHomeClick = {
                    navigator.popToRoot()
                    navigator.replace(IMainScreen(MainTab.Home))
                },
                onFavoriteClick = {
                    navigator.popToRoot()
                    navigator.replace(IMainScreen(MainTab.Favorite))
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier =
                Modifier.padding(paddingValues)
                    .fillMaxSize()
                    .background(colors.creamBackground)
        ) {
            when (val current = state) {
                is ForumState.Loading -> ForumLoadingSkeleton()
                is ForumState.Error ->
                    ForumErrorContent(
                        message = current.message,
                        onRetry = {
                            state = ForumState.Loading
                            scope.launch { loadPage(currentPage) }
                        }
                    )

                is ForumState.Success ->
                    PullToRefreshBox(
                        isRefreshing = isRefreshing,
                        onRefresh = {
                            isRefreshing = true
                            scope.launch {
                                when (val result =
                                    forumRepository.fetchForum(fid, currentPage, selectedFilterType, selectedOrderType)
                                ) {
                                    is YamiboResult.Success -> {
                                        forumRepository.setCachedForumPage(
                                            fid,
                                            currentPage,
                                            result.value,
                                            selectedFilterType,
                                            selectedOrderType,
                                        )
                                        state = ForumState.Success(result.value)
                                    }

                                    else -> {
                                        val msg = i18n(result.message())
                                        feedbackController.post(
                                            message = i18n("刷新失敗：{}", msg),
                                            duration = me.thenano.yamibo.yamibo_app.feedback.AppFeedbackDuration.Short
                                        )
                                    }
                                }
                                isRefreshing = false
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    ) {
                        ForumContent(
                            forumPage = current.page,
                            selectedOrderType = selectedOrderType,
                            selectedFilterType = selectedFilterType,
                            onShowOrderDialog = { showOrderDialog = true },
                            onShowFilterDialog = { showFilterDialog = true },
                            onPageChange = ::navigateToPage,
                            onSubForumClick = { subFid, subName ->
                                navigator.navigate(IForumScreen(subFid, subName))
                            },
                            onPinnedItemClick = { item ->
                                when (item) {
                                    is PinnedItem.Thread -> {
                                        navigator.navigate(
                                            IThreadReaderScreen(
                                                tid = item.tid,
                                                title = item.title
                                            )
                                        )
                                    }
                                    is PinnedItem.Announcement -> {
                                        scope.launch {
                                            feedbackController.post(
                                                i18n("暫不支持跳轉到公告頁"),
                                                duration = me.thenano.yamibo.yamibo_app.feedback.AppFeedbackDuration.Short
                                            )
                                        }
                                    }
                                }
                            },
                            onThreadClick = { thread ->
                                if (YamiboForum.isNovelForum(fid)) {
                                    navigator.navigate(
                                        INovelThreadDetailScreen(
                                            thread.tid,
                                            thread.title,
                                            thread.author?.uid
                                        )
                                    )
                                } else {
                                    navigator.navigate(
                                        IThreadReaderScreen(
                                            tid = thread.tid,
                                            title = thread.title
                                        )
                                    )
                                }
                            },
                            onAuthorClick = { user ->
                                navigator.navigate(IUserSpaceScreen(user.uid, user.name))
                            },
                        )
                    }
            }
        }
    }

    fun selectFilterOrOrder(
        newFilterType: FilterType? = selectedFilterType,
        newOrderType: OrderType? = selectedOrderType,
    ) {
        pageHistory.clear()
        loadGeneration += 1
        val requestGeneration = loadGeneration
        selectedFilterType = newFilterType
        selectedOrderType = newOrderType
        state = ForumState.Loading
        scope.launch {
            loadPage(
                currentPage,
                newFilterType,
                newOrderType,
                requestGeneration = requestGeneration,
            )
        }
    }

    val currentForumPage = (state as? ForumState.Success)?.page
    if (showOrderDialog && currentForumPage != null) {
        ForumOrderDialog(
            options = currentForumPage.orderType.orEmpty(),
            selected = selectedOrderType,
            onDismiss = { showOrderDialog = false },
            onSelect = { orderType ->
                showOrderDialog = false
                selectFilterOrOrder(newOrderType = orderType)
            },
        )
    }
    if (showFilterDialog && currentForumPage != null) {
        ForumFilterTypeDialog(
            options = currentForumPage.filterTypes.orEmpty(),
            selected = selectedFilterType,
            onDismiss = { showFilterDialog = false },
            onSelect = { filterType ->
                showFilterDialog = false
                selectFilterOrOrder(newFilterType = filterType)
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ForumTopBar(
    title: String,
    isFavorite: Boolean,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onPostThread: () -> Unit,
    onStarClick: () -> Unit,
    onHomeClick: () -> Unit,
    onFavoriteClick: () -> Unit
) {
    val colors = YamiboTheme.colors
    var showMenu by remember { mutableStateOf(false) }

    YamiboTopBar(
        title = title,
        titleFontSize = 18,
        onBack = onBack,
    ) {
            YamiboTopBarIconAction(YamiboIcons.Search, i18n("搜尋"), onSearch, iconSize = 28)
            Box(modifier = Modifier.offset(y = 0.dp)) {
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        imageVector = YamiboIcons.ThreeDots,
                        contentDescription = i18n("更多"),
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    modifier = Modifier.background(colors.creamSurface)
                ) {
                    DropdownMenuItem(
                        text = { Text(i18n("發表帖子"), color = colors.textStrong) },
                        leadingIcon = {
                            Icon(
                                imageVector = YamiboIcons.EditOrSign,
                                contentDescription = null,
                                tint = colors.brownPrimary
                            )
                        },
                        onClick = {
                            showMenu = false
                            onPostThread()
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                i18n(if (isFavorite) "解除收藏" else "收藏本版"),
                                color = colors.textStrong,
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = if (isFavorite) YamiboIcons.StarFilled else YamiboIcons.StarOutline,
                                contentDescription = null,
                                tint = colors.brownPrimary
                            )
                        },
                        onClick = {
                            showMenu = false
                            onStarClick()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(i18n("跳轉到首頁"), color = colors.textStrong) },
                        leadingIcon = {
                            Icon(
                                imageVector = YamiboIcons.Home,
                                contentDescription = null,
                                tint = colors.brownPrimary
                            )
                        },
                        onClick = {
                            showMenu = false
                            onHomeClick()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(i18n("跳轉到收藏"), color = colors.textStrong) },
                        leadingIcon = {
                            Icon(
                                imageVector = YamiboIcons.Explore,
                                contentDescription = null,
                                tint = colors.brownPrimary
                            )
                        },
                        onClick = {
                            showMenu = false
                            onFavoriteClick()
                        }
                    )
                }
            }
    }
}

/** Forum Content (scrollable body below sticky top bar) */
@Composable
private fun ForumContent(
    forumPage: ForumPage,
    selectedOrderType: OrderType?,
    selectedFilterType: FilterType?,
    onShowOrderDialog: () -> Unit,
    onShowFilterDialog: () -> Unit,
    onPageChange: (Int) -> Unit,
    onSubForumClick: (ForumId, String) -> Unit,
    onPinnedItemClick: (PinnedItem) -> Unit,
    onThreadClick: (ThreadSummary) -> Unit,
    onAuthorClick: (User) -> Unit,
) {
    key(forumPage.pageNav?.currentPage ?: 1) {
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            /** forum stats bar */
            item {
                ForumStatsBar(
                    forum = forumPage.forum,
                    selectedOrderType = selectedOrderType,
                    selectedFilterType = selectedFilterType,
                    showOrder = !forumPage.orderType.isNullOrEmpty(),
                    showFilter = !forumPage.filterTypes.isNullOrEmpty(),
                    onOrderClick = onShowOrderDialog,
                    onFilterClick = onShowFilterDialog,
                )
            }

            /** sub forums */
            if (forumPage.subForums.isNotEmpty()) {
                item { SubForumRow(subForums = forumPage.subForums, onClick = onSubForumClick) }
            }

            /** pinned items */
            if (forumPage.pinnedItems.isNotEmpty()) {
                item { PinnedSection(items = forumPage.pinnedItems, onItemClick = onPinnedItemClick) }
            }

            /** thread list */
            items(forumPage.threads, key = { it.tid.value }) { thread ->
                ThreadCard(
                    thread = thread,
                    onClick = { onThreadClick(thread) },
                    onAuthorClick = onAuthorClick,
                )
            }

            /** page navigation */
            if (forumPage.pageNav != null) {
                item { PageNavigation(pageNav = forumPage.pageNav!!, onPageChange = onPageChange) }
            }
        }
    }
}

@Composable
private fun ForumOrderDialog(
    options: List<OrderType>,
    selected: OrderType?,
    onDismiss: () -> Unit,
    onSelect: (OrderType?) -> Unit,
) {
    ForumOptionDialog(
        title = i18n("排序"),
        defaultLabel = i18n("全部"),
        options = options,
        selected = selected,
        optionLabel = { it.name },
        onDismiss = onDismiss,
        onSelect = onSelect,
    )
}

@Composable
private fun ForumFilterTypeDialog(
    options: List<FilterType>,
    selected: FilterType?,
    onDismiss: () -> Unit,
    onSelect: (FilterType?) -> Unit,
) {
    ForumOptionDialog(
        title = i18n("分類"),
        defaultLabel = i18n("全部"),
        options = options,
        selected = selected,
        optionLabel = { it.name },
        onDismiss = onDismiss,
        onSelect = onSelect,
    )
}

@Composable
private fun <T> ForumOptionDialog(
    title: String,
    defaultLabel: String,
    options: List<T>,
    selected: T?,
    optionLabel: (T) -> String,
    onDismiss: () -> Unit,
    onSelect: (T?) -> Unit,
) {
    val visibleOptions = options
        .distinctBy(optionLabel)
        .filterNot { optionLabel(it) == defaultLabel }

    YamiboSingleSelectDialog(
        title = title,
        options = listOf<T?>(null) + visibleOptions,
        selected = selected,
        onDismiss = onDismiss,
        onSelect = onSelect,
        label = { it?.let(optionLabel) ?: defaultLabel },
        dismissOnSelect = true,
        footer = null,
    )
}

/** Loading skeleton */
@Composable
private fun ForumLoadingSkeleton() {
    val colors = YamiboTheme.colors
    val shimmerColor = colors.brownLight

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
        val shimmerAnim = rememberInfiniteTransition(label = "forum_shimmer")
        val shimmerX by
        shimmerAnim.animateFloat(
            initialValue = -widthPx,
            targetValue = widthPx * 2f,
            animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)),
            label = "forum_shimmer_x"
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize().background(colors.creamBackground),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Box(
                    modifier =
                        Modifier.fillMaxWidth()
                            .height(70.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .shimmer(shimmerX, shimmerColor)
                )
            }
            items(6) {
                Box(
                    modifier =
                        Modifier.fillMaxWidth()
                            .height(100.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .shimmer(shimmerX, shimmerColor)
                )
            }
        }
    }
}

/** Shimmer modifier */
private fun Modifier.shimmer(translateX: Float, baseColor: Color): Modifier =
    this.drawBehind {
        val brush =
            Brush.linearGradient(
                colors =
                    listOf(
                        baseColor.copy(alpha = 0.25f),
                        baseColor.copy(alpha = 0.50f),
                        baseColor.copy(alpha = 0.25f),
                    ),
                start = Offset(translateX, 0f),
                end = Offset(translateX + size.width, size.height)
            )
        drawRect(brush)
    }

/** Error content */
@Composable
private fun ForumErrorContent(message: String, onRetry: () -> Unit) {
    val colors = YamiboTheme.colors
    YamiboDetailedErrorContent(
        message = message,
        onRetry = onRetry,
        titleColor = colors.textOnSurface,
        retryContentColor = colors.textOnDeepHigh,
        retryHorizontalPadding = 24.dp,
    )
}

package me.thenano.yamibo.yamibo_app.forum.search

import androidx.compose.animation.AnimatedContent
import me.thenano.yamibo.yamibo_app.components.navigation.NavigationBackSymbol
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.littlesurvival.YamiboForum
import io.github.littlesurvival.core.YamiboResult
import io.github.littlesurvival.dto.model.ThreadSummary
import io.github.littlesurvival.dto.page.SearchPage
import io.github.littlesurvival.dto.value.ForumId
import io.github.littlesurvival.dto.value.SearchId
import kotlinx.coroutines.launch
import me.thenano.yamibo.yamibo_app.Logger
import me.thenano.yamibo.yamibo_app.LocalAuthRepository
import me.thenano.yamibo.yamibo_app.LocalFavoriteRepository
import me.thenano.yamibo.yamibo_app.LocalForumRepository
import me.thenano.yamibo.yamibo_app.LocalRssSearchSubscriptionRepository
import me.thenano.yamibo.yamibo_app.forum.components.PageNavigation
import me.thenano.yamibo.yamibo_app.forum.components.ThreadCard
import me.thenano.yamibo.yamibo_app.favorite.FavoriteCollectionPickerDialog
import me.thenano.yamibo.yamibo_app.favorite.FavoriteTargetPayload
import me.thenano.yamibo.yamibo_app.favorite.IFavoriteCategoryManageScreen
import me.thenano.yamibo.yamibo_app.favorite.findFavoriteItem
import me.thenano.yamibo.yamibo_app.favorite.getFavoriteLocationSelection
import me.thenano.yamibo.yamibo_app.favorite.saveFavorite
import me.thenano.yamibo.yamibo_app.i18n.i18n
import me.thenano.yamibo.yamibo_app.navigation.LocalNavigator
import me.thenano.yamibo.yamibo_app.navigation.IInAppLinkResolvingScreen
import me.thenano.yamibo.yamibo_app.components.theme.YamiboSnackbarHost
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme
import me.thenano.yamibo.yamibo_app.repository.FavoriteStoreRepository as FavoriteStoreRepositoryType
import me.thenano.yamibo.yamibo_app.thread.detail.rss.IRssSearchSubscriptionDetailScreen
import me.thenano.yamibo.yamibo_app.thread.detail.novel.INovelThreadDetailScreen
import me.thenano.yamibo.yamibo_app.thread.reader.IThreadReaderScreen
import me.thenano.yamibo.yamibo_app.userspace.IUserSpaceScreen

private sealed interface SearchState {
    data object Idle : SearchState
    data object Loading : SearchState
    data class Success(val page: SearchPage) : SearchState
    data class Error(val message: String) : SearchState
}

private data class SearchPageSnapshot(
    val state: SearchState,
    val currentPage: Int,
    val currentSearchId: SearchId?,
)

private data class PendingRssFavorite(
    val keyword: String,
    val searchPage: SearchPage,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(fid: ForumId?) {
    val colors = YamiboTheme.colors
    val forumRepository = LocalForumRepository.current
    val authRepository = LocalAuthRepository.current
    val rssRepository = LocalRssSearchSubscriptionRepository.current
    val favoriteRepository = LocalFavoriteRepository.current
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val feedbackController = me.thenano.yamibo.yamibo_app.LocalAppFeedbackController.current
    val focusRequester = remember { FocusRequester() }
    var searchFieldPlaced by remember { mutableStateOf(false) }

    var query by remember { mutableStateOf("") }
    var state by remember { mutableStateOf<SearchState>(SearchState.Idle) }
    var currentPage by remember { mutableIntStateOf(1) }
    var currentSearchId by remember { mutableStateOf<SearchId?>(null) }
    val pageHistory = remember { mutableStateListOf<SearchPageSnapshot>() }
    var pendingRssFavorite by remember { mutableStateOf<PendingRssFavorite?>(null) }
    var favoriteDialogCategories by remember {
        mutableStateOf(emptyList<FavoriteStoreRepositoryType.FavoriteCategory>())
    }
    var favoriteCollectionOptions by remember {
        mutableStateOf(emptyList<FavoriteStoreRepositoryType.FavoriteCollectionOption>())
    }
    var favoriteDialogCategorySelection by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var favoriteDialogCollectionSelection by remember { mutableStateOf<Set<Long>>(emptySet()) }

    fun navigateThread(thread: ThreadSummary) {
        val isNovelThread = fid?.let { YamiboForum.isNovelForum(it) }
            ?: YamiboForum.isNovelForum(thread.tag ?: "")
        if (isNovelThread) {
            navigator.navigate(INovelThreadDetailScreen(thread.tid, thread.title, thread.author?.uid))
        } else {
            navigator.navigate(IThreadReaderScreen(tid = thread.tid, title = thread.title))
        }
    }

    fun restorePreviousSearchPage(): Boolean {
        val snapshot = pageHistory.removeLastOrNull() ?: return false
        state = snapshot.state
        currentPage = snapshot.currentPage
        currentSearchId = snapshot.currentSearchId
        return true
    }

    fun doSearch(page: Int = 1, recordHistory: Boolean = false) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return

        if (recordHistory) {
            pageHistory.add(SearchPageSnapshot(state, currentPage, currentSearchId))
        } else if (page == 1) {
            pageHistory.clear()
        }

        state = SearchState.Loading
        currentPage = page
        scope.launch {
            val looksLikeUrl = trimmed.startsWith("http://") || trimmed.startsWith("https://")
            if (page == 1 && looksLikeUrl) {
                state = SearchState.Idle
                navigator.navigate(IInAppLinkResolvingScreen(trimmed))
                return@launch
            }

            val formHash = authRepository.currentUser()?.formHash
            if (formHash == null) {
                state = SearchState.Error(i18n("請先登入後再搜尋"))
                return@launch
            }

            val result = if (page == 1 || currentSearchId == null) {
                forumRepository.fetchSearch(trimmed, fid, formHash)
            } else {
                forumRepository.fetchSearchById(trimmed, currentSearchId!!, page)
            }
            state = when (result) {
                is YamiboResult.Success -> {
                    if (result.value.threads.isEmpty()) {
                        SearchState.Error(i18n("沒有找到搜尋結果"))
                    } else {
                        if (page == 1) currentSearchId = result.value.searchId
                        SearchState.Success(result.value)
                    }
                }

                else -> SearchState.Error(i18n(result.message()))
            }
        }
    }

    suspend fun saveRssFavorite(
        keyword: String,
        searchPage: SearchPage,
        categoryIds: Set<Long> = emptySet(),
        collectionIds: Set<Long> = emptySet(),
    ): Long? {
        val existingSubscription = rssRepository.findBySearch(keyword, fid)
        val result = rssRepository.createFromSearch(
            title = keyword,
            query = keyword,
            forumId = fid,
            forumName = fid?.let { YamiboForum.toForumName(it) },
            searchPage = searchPage,
        )
        if (result !is YamiboResult.Success) {
            feedbackController.post(i18n("保存失敗"))
            return null
        }

        val target = FavoriteTargetPayload.RssSearch(
            subscriptionId = result.value,
            title = keyword,
            coverUrl = null,
        )
        try {
            val existingItem = favoriteRepository.findFavoriteItem(target)
            if (existingItem == null) {
                favoriteRepository.saveFavorite(
                    target = target,
                    categoryIds = categoryIds.toList(),
                    collectionIds = collectionIds.toList(),
                )
            } else if (categoryIds.isNotEmpty() || collectionIds.isNotEmpty()) {
                favoriteRepository.setItemLocations(existingItem.id, categoryIds, collectionIds)
            }
        } catch (error: Throwable) {
            Logger.e("SearchScreen", "Failed to save RSS favorite keyword=$keyword", error)
            if (existingSubscription == null) {
                rssRepository.delete(result.value)
            }
            feedbackController.post(i18n("保存失敗"))
            return null
        }

        feedbackController.post(
            if (existingSubscription == null) i18n("已收藏為 RSS 訂閱") else i18n("已收藏此 RSS 訂閱")
        )
        return result.value
    }

    suspend fun openRssFavoritePicker(keyword: String, searchPage: SearchPage) {
        favoriteRepository.ensureDefaults()
        favoriteDialogCategories = favoriteRepository.getCategories()
        favoriteCollectionOptions = favoriteRepository.getCollectionOptions()
        val existingSubscription = rssRepository.findBySearch(keyword, fid)
        if (existingSubscription != null) {
            val selection = favoriteRepository.getFavoriteLocationSelection(
                FavoriteTargetPayload.RssSearch(
                    subscriptionId = existingSubscription.id,
                    title = keyword,
                    coverUrl = null,
                )
            )
            favoriteDialogCategorySelection = selection.categoryIds
            favoriteDialogCollectionSelection = selection.collectionIds
        } else {
            favoriteDialogCategorySelection = emptySet()
            favoriteDialogCollectionSelection = emptySet()
        }
        pendingRssFavorite = PendingRssFavorite(keyword, searchPage)
    }

    DisposableEffect(navigator) {
        val handler = { restorePreviousSearchPage() }
        navigator.backHandlers.add(handler)
        onDispose { navigator.backHandlers.remove(handler) }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.creamBackground)
                .systemBarsPadding(),
        ) {
            Surface(color = colors.brownDeep, shadowElevation = 4.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { if (!restorePreviousSearchPage()) navigator.pop() }) {
                        Text(NavigationBackSymbol, color = Color.White, fontSize = 20.sp)
                    }
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier
                            .weight(1f)
                            .onPlaced { searchFieldPlaced = true }
                            .focusProperties { canFocus = searchFieldPlaced }
                            .focusRequester(focusRequester),
                        placeholder = {
                            Text(
                                text = i18n("搜尋標題或連結..."),
                                color = Color.White.copy(alpha = 0.5f),
                                fontSize = 15.sp,
                            )
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { doSearch(1) }),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            cursorColor = colors.orangeAccent,
                            focusedBorderColor = colors.orangeAccent,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.3f),
                            focusedContainerColor = Color.White.copy(alpha = 0.08f),
                            unfocusedContainerColor = Color.White.copy(alpha = 0.05f),
                        ),
                        shape = RoundedCornerShape(12.dp),
                        textStyle = LocalTextStyle.current.copy(fontSize = 15.sp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Surface(
                        onClick = { doSearch(1) },
                        shape = RoundedCornerShape(12.dp),
                        color = colors.orangeAccent,
                    ) {
                        Text(
                            text = i18n("搜尋"),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            AnimatedContent(
                targetState = state,
                transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) },
                label = "search_screen_content",
            ) { currentState ->
                when (currentState) {
                    is SearchState.Idle -> SearchIdleContent()
                    is SearchState.Loading -> SearchLoadingContent()
                    is SearchState.Error -> SearchErrorContent(currentState.message)
                    is SearchState.Success -> SearchResultContent(
                        searchPage = currentState.page,
                        onThreadClick = ::navigateThread,
                        onPageChange = { doSearch(it, recordHistory = true) },
                        onSaveAsRss = {
                            val keyword = query.trim()
                            val currentState = state as? SearchState.Success
                            if (currentState == null) {
                                    feedbackController.post(i18n("保存失敗"))
                            } else {
                                scope.launch {
                                    val subscriptionId = saveRssFavorite(keyword, currentState.page)
                                    if (subscriptionId != null) {
                                        navigator.navigate(IRssSearchSubscriptionDetailScreen(subscriptionId))
                                    }
                                }
                            }
                        },
                        onSaveAsRssLongPress = {
                            val keyword = query.trim()
                            val currentState = state as? SearchState.Success
                            if (currentState == null) {
                                feedbackController.post(i18n("保存失敗"))
                            } else {
                                scope.launch { openRssFavoritePicker(keyword, currentState.page) }
                            }
                        },
                    )
                }
            }
        }

    }

    pendingRssFavorite?.let { pending ->
        FavoriteCollectionPickerDialog(
            categories = favoriteDialogCategories,
            options = favoriteCollectionOptions,
            initialCategorySelection = favoriteDialogCategorySelection,
            initialCollectionSelection = favoriteDialogCollectionSelection,
            onDismiss = { pendingRssFavorite = null },
            onEdit = {
                pendingRssFavorite = null
                navigator.navigate(IFavoriteCategoryManageScreen())
            },
            onConfirm = { selectedCategories, selectedCollections ->
                scope.launch {
                    val subscriptionId = saveRssFavorite(
                        keyword = pending.keyword,
                        searchPage = pending.searchPage,
                        categoryIds = selectedCategories,
                        collectionIds = selectedCollections,
                    )
                    pendingRssFavorite = null
                    if (subscriptionId != null) {
                        navigator.navigate(IRssSearchSubscriptionDetailScreen(subscriptionId))
                    }
                }
            },
        )
    }
}

@Composable
private fun SearchIdleContent() {
    val colors = YamiboTheme.colors
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = YamiboIcons.Search,
                contentDescription = null,
                tint = colors.brownPrimary.copy(alpha = 0.4f),
                modifier = Modifier.size(64.dp),
            )
            Spacer(Modifier.size(12.dp))
            Text(i18n("輸入關鍵字或帖子連結"), color = colors.brownPrimary.copy(alpha = 0.6f), fontSize = 15.sp)
        }
    }
}

@Composable
private fun SearchLoadingContent() {
    val colors = YamiboTheme.colors
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                color = colors.brownDeep,
                strokeWidth = 3.dp,
                modifier = Modifier.size(36.dp),
            )
            Spacer(Modifier.size(12.dp))
            Text(i18n("搜尋中..."), color = colors.brownPrimary.copy(alpha = 0.7f), fontSize = 14.sp)
        }
    }
}

@Composable
private fun SearchErrorContent(message: String) {
    val colors = YamiboTheme.colors
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Card(
            shape = RoundedCornerShape(20.dp),
            elevation = CardDefaults.cardElevation(4.dp),
            colors = CardDefaults.cardColors(containerColor = colors.creamSurface),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("!", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = colors.textStrong.copy(alpha = 0.6f))
                Spacer(Modifier.size(8.dp))
                Text(message, color = colors.textStrong, fontSize = 14.sp, lineHeight = 20.sp)
            }
        }
    }
}

@Composable
private fun SearchResultContent(
    searchPage: SearchPage,
    onThreadClick: (ThreadSummary) -> Unit,
    onPageChange: (Int) -> Unit,
    onSaveAsRss: () -> Unit,
    onSaveAsRssLongPress: () -> Unit,
) {
    val colors = YamiboTheme.colors
    val navigator = LocalNavigator.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = i18n("找到 {} 筆搜尋結果", searchPage.totalCount),
                    modifier = Modifier.weight(1f),
                    color = colors.brownPrimary.copy(alpha = 0.7f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Surface(
                    modifier = Modifier.pointerInput(onSaveAsRss, onSaveAsRssLongPress) {
                        detectTapGestures(
                            onTap = { onSaveAsRss() },
                            onLongPress = { onSaveAsRssLongPress() },
                        )
                    },
                    shape = RoundedCornerShape(10.dp),
                    color = colors.brownPrimary.copy(alpha = 0.12f),
                ) {
                    Text(
                        text = i18n("收藏為 RSS 訂閱"),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        color = colors.textOnTint,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
        items(searchPage.threads, key = { it.tid.value }) { thread ->
            ThreadCard(
                thread = thread,
                onClick = { onThreadClick(thread) },
                onAuthorClick = { user -> navigator.navigate(IUserSpaceScreen(user.uid, user.name)) },
            )
        }
        searchPage.pageNav?.let { nav ->
            item { PageNavigation(pageNav = nav, onPageChange = onPageChange) }
        }
    }
}

package me.thenano.yamibo.yamibo_app.userspace

import YamiboIcons
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.littlesurvival.YamiboForum
import io.github.littlesurvival.YamiboRoute
import io.github.littlesurvival.core.YamiboResult
import io.github.littlesurvival.dto.model.ThreadSummary
import io.github.littlesurvival.dto.page.*
import io.github.littlesurvival.dto.value.UserId
import kotlinx.coroutines.launch
import me.thenano.yamibo.yamibo_app.LocalAuthRepository
import me.thenano.yamibo.yamibo_app.LocalUserSpaceRepository
import me.thenano.yamibo.yamibo_app.components.controls.YamiboActionChip
import me.thenano.yamibo.yamibo_app.components.feedback.YamiboErrorContent
import me.thenano.yamibo.yamibo_app.components.feedback.YamiboLoadingContent
import me.thenano.yamibo.yamibo_app.components.navigation.YamiboMainTabTopBar
import me.thenano.yamibo.yamibo_app.components.navigation.YamiboScrollableTabRow
import me.thenano.yamibo.yamibo_app.components.navigation.YamiboTopBar
import me.thenano.yamibo.yamibo_app.components.navigation.YamiboTopBarIconAction
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme
import me.thenano.yamibo.yamibo_app.components.user.UserAvatar
import me.thenano.yamibo.yamibo_app.i18n.i18n
import me.thenano.yamibo.yamibo_app.message.IMessageCenterScreen
import me.thenano.yamibo.yamibo_app.message.IPrivateMessageScreen
import me.thenano.yamibo.yamibo_app.navigation.ComposableNavigator
import me.thenano.yamibo.yamibo_app.navigation.LocalNavigator
import me.thenano.yamibo.yamibo_app.thread.detail.novel.INovelThreadDetailScreen
import me.thenano.yamibo.yamibo_app.thread.reader.IThreadReaderScreen
import me.thenano.yamibo.yamibo_app.userspace.blog.IBlogReaderScreen
import me.thenano.yamibo.yamibo_app.webview.action.IActionWebView

enum class UserSpaceSubPage(val selfTitle: String, val otherTitle: String = selfTitle) {
    Profile(i18n("我的資料"), i18n("Ta的資料")),
    Threads(i18n("我的主題"), i18n("Ta的主題")),
    Replies(i18n("我的回覆"), i18n("Ta的回覆")),
    MyBlogs(i18n("我的日志"), i18n("Ta的日志")),
    FriendBlogs(i18n("好友的日志")),
    ViewAllBlogs(i18n("隨便看看")),
    Friends(i18n("我的好友")),
    Online(i18n("在線成員")),
    Visitors(i18n("我的訪客")),
    Traces(i18n("我的足跡"));

    fun title(isSelf: Boolean): String = if (isSelf) selfTitle else otherTitle
}

enum class UserSpaceSection {
    Space,
    Threads,
    Blogs,
    Friends,
}

private sealed interface UserSpaceState {
    data object Loading : UserSpaceState
    data class Success(val content: UserSpaceScreenContent) : UserSpaceState
    data class Error(val message: String) : UserSpaceState
}

internal sealed interface UserSpaceScreenContent {
    data class Profile(val page: ProfilePage) : UserSpaceScreenContent
    data class Threads(val page: UserSpaceThreadPage) : UserSpaceScreenContent
    data class Replies(val page: UserSpaceThreadReplyPage) : UserSpaceScreenContent
    data class Blogs(val page: UserSpaceBlogPage) : UserSpaceScreenContent
    data class Friends(val page: UserSpaceFriendPage) : UserSpaceScreenContent
}

private enum class ViewAllBlogFilter(
    val title: String,
    val apiType: YamiboRoute.UserSpace.Blog.ViewAllType,
) {
    Latest(i18n("最新發表的日志"), YamiboRoute.UserSpace.Blog.ViewAllType.Latest),
    Hot(i18n("推薦閱讀的日志"), YamiboRoute.UserSpace.Blog.ViewAllType.Hot),
}

@Composable
fun UserSpaceScreen(
    userId: UserId? = null,
    titleHint: String? = null,
    group: UserSpaceSection = UserSpaceSection.Space,
    initialTab: UserSpaceSubPage = UserSpaceSubPage.Profile,
    mainTabTopBar: Boolean = false,
) {
    val colors = YamiboTheme.colors
    val repository = LocalUserSpaceRepository.current
    val authRepository = LocalAuthRepository.current
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val feedbackController = me.thenano.yamibo.yamibo_app.LocalAppFeedbackController.current
    val appTaskManager = me.thenano.yamibo.yamibo_app.LocalAppTaskManager.current
    val currentUser = authRepository.currentUser()
    val isSelf = userId == null || currentUser?.uid?.value == userId.value
    val tabs = remember(isSelf, group) { tabsFor(group, isSelf) }

    var selectedTab by remember(userId, group, initialTab) {
        mutableStateOf(if (initialTab in tabs) initialTab else tabs.first())
    }
    var currentPage by remember { mutableIntStateOf(1) }
    var state by remember { mutableStateOf<UserSpaceState>(UserSpaceState.Loading) }
    var profile by remember { mutableStateOf(repository.getCachedProfile(userId)) }
    var isRefreshing by remember { mutableStateOf(false) }
    var viewAllBlogFilter by remember(userId, group) { mutableStateOf(ViewAllBlogFilter.Latest) }
    var addFriendProfile by remember { mutableStateOf<ProfilePage?>(null) }
    var addFriendPopout by remember { mutableStateOf<AddFriendPopoutScreen?>(null) }
    var addFriendError by remember { mutableStateOf<String?>(null) }
    var isAddFriendLoading by remember { mutableStateOf(false) }
    var isAddFriendSubmitting by remember { mutableStateOf(false) }

    suspend fun loadTab(tab: UserSpaceSubPage, page: Int, preferCache: Boolean = true) {
        if (preferCache) {
            cachedContent(repository, userId, tab, page, viewAllBlogFilter.apiType)?.let {
                currentPage = page
                state = UserSpaceState.Success(it)
                return
            }
        }

        val result = fetchContent(repository, userId, tab, page, viewAllBlogFilter.apiType)
        state = when (result) {
            is YamiboResult.Success -> {
                currentPage = result.value.pageNumber() ?: page
                UserSpaceState.Success(result.value)
            }
            else -> UserSpaceState.Error(i18n(result.message()))
        }
    }

    LaunchedEffect(userId) {
        profile = repository.getCachedProfile(userId)
        if (profile == null) {
            when (val result = repository.fetchProfile(userId)) {
                is YamiboResult.Success -> profile = result.value
                else -> Unit
            }
        }
    }

    LaunchedEffect(userId, group, selectedTab, viewAllBlogFilter) {
        currentPage = 1
        state = UserSpaceState.Loading
        loadTab(selectedTab, 1)
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = colors.creamBackground,
        topBar = {
            if (mainTabTopBar) {
                UserSpaceMainTopBar(
                    title = group.mainTitle(),
                    profile = currentUser,
                    onSpaceClick = {
                        navigator.navigate(IUserSpaceScreen(currentUser?.uid, currentUser?.username))
                    },
                )
            } else {
                UserSpaceTopBar(
                    title = topBarTitle(profile, titleHint, group, selectedTab, isSelf),
                    showEdit = isSelf && group == UserSpaceSection.Blogs,
                    onBack = { navigator.pop() },
                    onEdit = {
                        navigator.navigate(userSpaceEditActionWebView(group))
                    }
                )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
                .background(colors.creamBackground)
        ) {
            if (tabs.size > 1) {
                UserSpaceSubPageRow(
                    tabs = tabs,
                    selectedTab = selectedTab,
                    isSelf = isSelf,
                    onSelect = { selectedTab = it }
                )
            }
            if (selectedTab == UserSpaceSubPage.ViewAllBlogs) {
                UserSpaceViewAllBlogFilterRow(
                    selected = viewAllBlogFilter,
                    onSelect = { viewAllBlogFilter = it },
                )
            }

            Box(modifier = Modifier.fillMaxSize()) {
                AnimatedContent(
                    targetState = state,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "userspace_state",
                ) { current ->
                    when (current) {
                        UserSpaceState.Loading -> UserSpaceLoadingContent()
                        is UserSpaceState.Error -> UserSpaceErrorContent(
                            message = current.message,
                            onRetry = {
                                state = UserSpaceState.Loading
                                scope.launch { loadTab(selectedTab, currentPage, preferCache = false) }
                            }
                        )
                        is UserSpaceState.Success -> PullToRefreshBox(
                            isRefreshing = isRefreshing,
                            onRefresh = {
                                isRefreshing = true
                                scope.launch {
                                    loadTab(selectedTab, currentPage, preferCache = false)
                                    if (selectedTab == UserSpaceSubPage.Profile) {
                                        when (val result = repository.fetchProfile(userId)) {
                                            is YamiboResult.Success -> profile = result.value
                                            else -> Unit
                                        }
                                    }
                                    isRefreshing = false
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        ) {
                            UserSpaceSectionContent(
                                content = current.content,
                                isSelf = isSelf,
                                selectedTab = selectedTab,
                                currentPage = currentPage,
                                onNavigateSection = { targetGroup, targetTab ->
                                    navigator.navigate(
                                        IUserSpaceScreen(
                                            userId = userId,
                                            titleHint = profile?.username ?: titleHint,
                                            group = targetGroup,
                                            initialTab = targetTab,
                                        )
                                    )
                                },
                                onOpenMessageCenter = { tab ->
                                    navigator.navigate(IMessageCenterScreen(initialTab = tab))
                                },
                                onAddFriend = { targetProfile ->
                                    addFriendProfile = targetProfile
                                    addFriendPopout = null
                                    addFriendError = null
                                    isAddFriendLoading = true
                                    scope.launch {
                                        when (val result = repository.fetchAddFriendPopoutScreen(targetProfile.uid)) {
                                            is YamiboResult.Success -> addFriendPopout = result.value
                                            else -> addFriendError = i18n(result.message())
                                        }
                                        isAddFriendLoading = false
                                    }
                                },
                                onPageChange = { page ->
                                    state = UserSpaceState.Loading
                                    scope.launch { loadTab(selectedTab, page) }
                                },
                                onThreadClick = { thread -> navigateThread(thread, navigator) },
                                onUserClick = { user -> navigator.navigate(IUserSpaceScreen(user.uid, user.name)) },
                                onOpenPrivateMessage = { user -> navigator.navigate(IPrivateMessageScreen(user.uid, user.name)) },
                                onBlogClick = { blog ->
                                    navigator.navigate(
                                        IBlogReaderScreen(
                                            blogId = blog.bId,
                                            userId = blog.author.uid,
                                            titleHint = blog.title,
                                        )
                                    )
                                },
                                onReplyQuoteClick = {
                                    scope.launch {
                                        feedbackController.post(i18n("TODO: 回覆定位跳轉尚未接入"), duration = me.thenano.yamibo.yamibo_app.feedback.AppFeedbackDuration.Short)
                                    }
                                },
                                onMessageAction = {
                                    scope.launch {
                                        feedbackController.post(i18n("TODO: 消息互動尚未接入"), duration = me.thenano.yamibo.yamibo_app.feedback.AppFeedbackDuration.Short)
                                    }
                                },
                                onOpenWebView = { title, url ->
                                    navigator.navigate(
                                        IActionWebView(
                                            title = title,
                                            initialUrl = fullYamiboUrl(url),
                                            successCondition = { false },
                                        )
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    val targetProfile = addFriendProfile
    if (targetProfile != null) {
        AddFriendDialog(
            profile = targetProfile,
            popout = addFriendPopout,
            loading = isAddFriendLoading,
            error = addFriendError,
            submitting = isAddFriendSubmitting,
            onDismiss = {
                if (!isAddFriendSubmitting) {
                    addFriendProfile = null
                    addFriendPopout = null
                    addFriendError = null
                }
            },
            onSubmit = { note, option ->
                val formHash = authRepository.currentUser()?.formHash ?: targetProfile.formHash
                if (formHash == null) {
                feedbackController.post(i18n("登入狀態已失效，請重新登入"))
                    return@AddFriendDialog
                }
                isAddFriendSubmitting = true
                appTaskManager.launch(
                    key = me.thenano.yamibo.yamibo_app.task.AppTaskKey("user-add-friend:${targetProfile.uid.value}"),
                ) {
                    when (val result = repository.addFriend(targetProfile.uid, formHash, note, option.id)) {
                        is YamiboResult.Success -> {
                            repository.clearFriendPages()
                            addFriendProfile = null
                            addFriendPopout = null
                            addFriendError = null
                            feedbackController.post(
                                result.value.takeIf { it.isNotBlank() } ?: i18n("好友請求已送出"),
                                duration = me.thenano.yamibo.yamibo_app.feedback.AppFeedbackDuration.Short,
                            )
                        }
                        else -> feedbackController.post(
                            i18n("加為好友失敗：{}", i18n(result.message())),
                            duration = me.thenano.yamibo.yamibo_app.feedback.AppFeedbackDuration.Short,
                        )
                    }
                    isAddFriendSubmitting = false
                }
            },
        )
    }
}

@Composable
private fun AddFriendDialog(
    profile: ProfilePage,
    popout: AddFriendPopoutScreen?,
    loading: Boolean,
    error: String?,
    submitting: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (String, AddFriendOption) -> Unit,
) {
    val colors = YamiboTheme.colors
    var note by remember(profile.uid) { mutableStateOf("") }
    var selectedOption by remember(popout) {
        mutableStateOf(popout?.availableOption?.firstOrNull { it.id == 1 } ?: popout?.availableOption?.firstOrNull())
    }
    var menuExpanded by remember { mutableStateOf(false) }
    val targetName = popout?.user?.name?.takeIf { it.isNotBlank() } ?: profile.username

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.creamBackground,
        confirmButton = {},
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                UserAvatar(popout?.user?.avatarUrl ?: profile.avatarUrl, size = 132)
                Spacer(Modifier.height(18.dp))
                when {
                    loading -> {
                        CircularProgressIndicator(color = colors.brownPrimary)
                        Spacer(Modifier.height(12.dp))
                        Text(i18n("正在載入好友申請表單"), color = colors.textDark, fontSize = 14.sp)
                    }
                    error != null -> {
                        Text(error, color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
                    }
                    popout != null && selectedOption != null -> {
                        Text(
                            text = i18n("添加 {} 為好友，附言:", targetName),
                            color = colors.textDark,
                            fontSize = 14.sp,
                        )
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(
                            value = note,
                            onValueChange = { if (it.length <= 10) note = it },
                            enabled = !submitting,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = i18n("(附言為可選，{} 會看到這條附言，最多 10 個字)", targetName),
                            color = colors.brownPrimary.copy(alpha = 0.65f),
                            fontSize = 12.sp,
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(i18n("分組:"), color = colors.textDark, fontSize = 14.sp)
                            Box {
                                TextButton(
                                    enabled = !submitting,
                                    onClick = { menuExpanded = true },
                                ) {
                                    Text(
                                        selectedOption?.reason.orEmpty(),
                                        color = colors.textDark,
                                        fontSize = 14.sp,
                                    )
                                }
                                DropdownMenu(
                                    expanded = menuExpanded,
                                    onDismissRequest = { menuExpanded = false },
                                    containerColor = colors.creamSurface,
                                ) {
                                    popout.availableOption.forEach { option ->
                                        DropdownMenuItem(
                                            text = { Text(option.reason, color = colors.textDark) },
                                            onClick = {
                                                selectedOption = option
                                                menuExpanded = false
                                            },
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Button(
                            enabled = !submitting,
                            onClick = { selectedOption?.let { onSubmit(note, it) } },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = colors.brownDeep),
                            shape = RoundedCornerShape(4.dp),
                        ) {
                            if (submitting) {
                                CircularProgressIndicator(color = colors.creamBackground, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                            } else {
                                Text(i18n("確定"), fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    else -> {
                        Text(i18n("沒有可用的好友分組"), color = colors.textDark, fontSize = 14.sp)
                    }
                }
            }
        },
    )
}

private fun tabsFor(group: UserSpaceSection, isSelf: Boolean): List<UserSpaceSubPage> = when (group) {
    UserSpaceSection.Space -> listOf(UserSpaceSubPage.Profile)
    UserSpaceSection.Threads -> listOf(UserSpaceSubPage.Threads, UserSpaceSubPage.Replies)
    UserSpaceSection.Blogs -> if (isSelf) {
        listOf(UserSpaceSubPage.FriendBlogs, UserSpaceSubPage.MyBlogs, UserSpaceSubPage.ViewAllBlogs)
    } else {
        listOf(UserSpaceSubPage.MyBlogs)
    }
    UserSpaceSection.Friends -> listOf(
        UserSpaceSubPage.Friends,
        UserSpaceSubPage.Online,
        UserSpaceSubPage.Visitors,
        UserSpaceSubPage.Traces,
    )
}

private fun UserSpaceSection.mainTitle(): String = when (this) {
    UserSpaceSection.Space -> i18n("我的資料")
    UserSpaceSection.Threads -> i18n("我的主題")
    UserSpaceSection.Blogs -> i18n("我的日志")
    UserSpaceSection.Friends -> i18n("我的好友")
}

@Composable
private fun UserSpaceViewAllBlogFilterRow(
    selected: ViewAllBlogFilter,
    onSelect: (ViewAllBlogFilter) -> Unit,
) {
    val colors = YamiboTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.creamSurface)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ViewAllBlogFilter.entries.forEach { filter ->
            YamiboActionChip(text = filter.title, selected = filter == selected, onClick = { onSelect(filter) })
        }
    }
    HorizontalDivider(color = colors.brownLight.copy(alpha = 0.45f))
}

@Composable
private fun UserSpaceTopBar(
    title: String,
    showEdit: Boolean,
    onBack: () -> Unit,
    onEdit: () -> Unit,
) {
    YamiboTopBar(
        title = title,
        applyStatusPadding = true,
        onBack = onBack,
    ) {
        if (showEdit) {
            YamiboTopBarIconAction(YamiboIcons.EditOrSign, i18n("編輯"), onEdit)
        }
    }
}

@Composable
private fun UserSpaceMainTopBar(
    title: String,
    profile: ProfilePage?,
    onSpaceClick: () -> Unit,
) {
    val colors = YamiboTheme.colors
    YamiboMainTabTopBar(
        title = title,
    ) {
        Surface(onClick = onSpaceClick, shape = RoundedCornerShape(18.dp), color = Color.Transparent) {
            Row(
                modifier = Modifier.padding(start = 6.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                UserAvatar(profile?.avatarUrl, size = 28)
                Text(
                    text = i18n("我的空間"),
                    color = colors.textOnBackground,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun UserSpaceSubPageRow(
    tabs: List<UserSpaceSubPage>,
    selectedTab: UserSpaceSubPage,
    isSelf: Boolean,
    onSelect: (UserSpaceSubPage) -> Unit,
) {
    val colors = YamiboTheme.colors
    val selectedIndex = tabs.indexOf(selectedTab).coerceAtLeast(0)
    YamiboScrollableTabRow(
        selectedIndex = selectedIndex,
    ) {
        tabs.forEach { tab ->
            Tab(
                selected = tab == selectedTab,
                onClick = { onSelect(tab) },
                text = {
                    Text(
                        text = tab.title(isSelf),
                        color = colors.textOnSurface,
                        fontSize = 14.sp,
                        maxLines = 1,
                    )
                }
            )
        }
    }
}

@Composable
private fun UserSpaceLoadingContent() {
    YamiboLoadingContent()
}

@Composable
private fun UserSpaceErrorContent(message: String, onRetry: () -> Unit) {
    YamiboErrorContent(message = message, onRetry = onRetry)
}

private suspend fun fetchContent(
    repository: me.thenano.yamibo.yamibo_app.repository.UserSpaceRepository,
    userId: UserId?,
    tab: UserSpaceSubPage,
    page: Int,
    viewAllType: YamiboRoute.UserSpace.Blog.ViewAllType,
): YamiboResult<UserSpaceScreenContent> {
    return when (tab) {
        UserSpaceSubPage.Profile -> repository.fetchProfile(userId).mapSuccess { UserSpaceScreenContent.Profile(it) }
        UserSpaceSubPage.Threads -> repository.fetchThreads(userId, page).mapSuccess { UserSpaceScreenContent.Threads(it) }
        UserSpaceSubPage.Replies -> repository.fetchReplies(userId, page).mapSuccess { UserSpaceScreenContent.Replies(it) }
        UserSpaceSubPage.MyBlogs -> repository.fetchMyBlogs(userId, page).mapSuccess { UserSpaceScreenContent.Blogs(it) }
        UserSpaceSubPage.FriendBlogs -> repository.fetchFriendBlogs(page).mapSuccess { UserSpaceScreenContent.Blogs(it) }
        UserSpaceSubPage.ViewAllBlogs -> repository.fetchViewAllBlogs(viewAllType, page).mapSuccess { UserSpaceScreenContent.Blogs(it) }
        UserSpaceSubPage.Friends -> repository.fetchFriends(YamiboRoute.UserSpace.FriendPageType.MyFriend, page).mapSuccess { UserSpaceScreenContent.Friends(it) }
        UserSpaceSubPage.Online -> repository.fetchFriends(YamiboRoute.UserSpace.FriendPageType.OnlineMember, page).mapSuccess { UserSpaceScreenContent.Friends(it) }
        UserSpaceSubPage.Visitors -> repository.fetchFriends(YamiboRoute.UserSpace.FriendPageType.MyVisitor, page).mapSuccess { UserSpaceScreenContent.Friends(it) }
        UserSpaceSubPage.Traces -> repository.fetchFriends(YamiboRoute.UserSpace.FriendPageType.MyTrace, page).mapSuccess { UserSpaceScreenContent.Friends(it) }
    }
}

private fun cachedContent(
    repository: me.thenano.yamibo.yamibo_app.repository.UserSpaceRepository,
    userId: UserId?,
    tab: UserSpaceSubPage,
    page: Int,
    viewAllType: YamiboRoute.UserSpace.Blog.ViewAllType,
): UserSpaceScreenContent? {
    return when (tab) {
        UserSpaceSubPage.Profile -> repository.getCachedProfile(userId)?.let { UserSpaceScreenContent.Profile(it) }
        UserSpaceSubPage.Threads -> repository.getCachedThreads(userId, page)?.let { UserSpaceScreenContent.Threads(it) }
        UserSpaceSubPage.Replies -> repository.getCachedReplies(userId, page)?.let { UserSpaceScreenContent.Replies(it) }
        UserSpaceSubPage.MyBlogs -> repository.getCachedMyBlogs(userId, page)?.let { UserSpaceScreenContent.Blogs(it) }
        UserSpaceSubPage.FriendBlogs -> repository.getCachedFriendBlogs(page)?.let { UserSpaceScreenContent.Blogs(it) }
        UserSpaceSubPage.ViewAllBlogs -> repository.getCachedViewAllBlogs(viewAllType, page)?.let { UserSpaceScreenContent.Blogs(it) }
        UserSpaceSubPage.Friends -> repository.getCachedFriends(YamiboRoute.UserSpace.FriendPageType.MyFriend, page)?.let { UserSpaceScreenContent.Friends(it) }
        UserSpaceSubPage.Online -> repository.getCachedFriends(YamiboRoute.UserSpace.FriendPageType.OnlineMember, page)?.let { UserSpaceScreenContent.Friends(it) }
        UserSpaceSubPage.Visitors -> repository.getCachedFriends(YamiboRoute.UserSpace.FriendPageType.MyVisitor, page)?.let { UserSpaceScreenContent.Friends(it) }
        UserSpaceSubPage.Traces -> repository.getCachedFriends(YamiboRoute.UserSpace.FriendPageType.MyTrace, page)?.let { UserSpaceScreenContent.Friends(it) }
    }
}

private fun UserSpaceScreenContent.pageNumber(): Int? = when (this) {
    is UserSpaceScreenContent.Profile -> null
    is UserSpaceScreenContent.Threads -> page.pageNav?.currentPage
    is UserSpaceScreenContent.Replies -> page.pageNav?.currentPage
    is UserSpaceScreenContent.Blogs -> page.pageNav?.currentPage
    is UserSpaceScreenContent.Friends -> page.pageNav?.currentPage
}

private fun <T, R> YamiboResult<T>.mapSuccess(transform: (T) -> R): YamiboResult<R> = when (this) {
    is YamiboResult.Success -> YamiboResult.Success(transform(value))
    is YamiboResult.Failure -> this
    is YamiboResult.NotLoggedIn -> this
    is YamiboResult.NoPermission -> this
    is YamiboResult.Maintenance -> this
}

private fun topBarTitle(
    profile: ProfilePage?,
    titleHint: String?,
    group: UserSpaceSection,
    tab: UserSpaceSubPage,
    isSelf: Boolean,
): String {
    val name = profile?.username ?: titleHint ?: i18n("用戶")
    return when (group) {
        UserSpaceSection.Space -> if (isSelf) i18n("我的資料") else i18n("{}的資料", name)
        UserSpaceSection.Threads -> if (isSelf) i18n("我的主題") else i18n("{} - Ta的主題", name)
        UserSpaceSection.Blogs -> if (isSelf) i18n("我的日志") else i18n("{} - Ta的日志", name)
        UserSpaceSection.Friends -> tab.title(isSelf)
    }
}

private fun navigateThread(thread: ThreadSummary, navigator: ComposableNavigator) {
    val fid = thread.fid
    if (fid != null && YamiboForum.isNovelForum(fid)) {
        navigator.navigate(INovelThreadDetailScreen(thread.tid, thread.title, thread.author?.uid))
    } else {
        navigator.navigate(IThreadReaderScreen(tid = thread.tid, title = thread.title))
    }
}

private fun fullYamiboUrl(url: String): String =
    YamiboRoute.Domain.toFullLink(url)

private fun userSpaceEditActionWebView(group: UserSpaceSection): IActionWebView {
    return when (group) {
        UserSpaceSection.Blogs -> IActionWebView(
            title = i18n("發日志"),
            initialUrl = YamiboRoute.SendBlogPage.build(),
            successCondition = { url -> isSendBlogSuccessUrl(url) },
        )
        else -> error("UserSpace edit action is only available for blog pages.")
    }

}

private fun isSendBlogSuccessUrl(url: String): Boolean {
    return url.startsWith("https://bbs.yamibo.com/home.php?mod=spacecp&ac=blog&blogid=")
}

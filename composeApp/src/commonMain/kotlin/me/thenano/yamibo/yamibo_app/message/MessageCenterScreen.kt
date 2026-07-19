package me.thenano.yamibo.yamibo_app.message

import YamiboIcons
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
import io.github.littlesurvival.YamiboRoute
import io.github.littlesurvival.core.YamiboResult
import io.github.littlesurvival.dto.page.ProfilePage
import io.github.littlesurvival.dto.page.UserSpaceNoticePage
import io.github.littlesurvival.dto.page.UserSpacePrivateMessagePage
import kotlinx.coroutines.launch
import me.thenano.yamibo.yamibo_app.LocalAuthRepository
import me.thenano.yamibo.yamibo_app.LocalUserSpaceRepository
import me.thenano.yamibo.yamibo_app.components.feedback.YamiboErrorContent
import me.thenano.yamibo.yamibo_app.components.feedback.YamiboLoadingContent
import me.thenano.yamibo.yamibo_app.components.navigation.YamiboMainTabTopBar
import me.thenano.yamibo.yamibo_app.components.navigation.YamiboScrollableTabRow
import me.thenano.yamibo.yamibo_app.components.navigation.YamiboTopBar
import me.thenano.yamibo.yamibo_app.components.navigation.YamiboTopBarIconAction
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme
import me.thenano.yamibo.yamibo_app.components.user.UserAvatar
import me.thenano.yamibo.yamibo_app.i18n.i18n
import me.thenano.yamibo.yamibo_app.navigation.LocalNavigator
import me.thenano.yamibo.yamibo_app.userspace.IUserSpaceScreen
import me.thenano.yamibo.yamibo_app.webview.action.IActionWebView

enum class MessageCenterTab(val title: String) {
    PrivateMessages(i18n("我的消息")),
    Notices(i18n("我的提醒")),
}

private sealed interface MessageCenterState {
    data object Loading : MessageCenterState
    data class Success(val content: MessageCenterContent) : MessageCenterState
    data class Error(val message: String) : MessageCenterState
}

internal sealed interface MessageCenterContent {
    data class PrivateMessages(val page: UserSpacePrivateMessagePage) : MessageCenterContent
    data class Notices(val page: UserSpaceNoticePage) : MessageCenterContent
}

@Composable
fun MessageCenterScreen(
    initialTab: MessageCenterTab = MessageCenterTab.PrivateMessages,
    mainTabTopBar: Boolean = false,
    onPrivateMessageUnreadChange: (Boolean) -> Unit = {},
) {
    val colors = YamiboTheme.colors
    val userSpaceRepository = LocalUserSpaceRepository.current
    val authRepository = LocalAuthRepository.current
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val feedbackController = me.thenano.yamibo.yamibo_app.LocalAppFeedbackController.current
    val currentUser = authRepository.currentUser()

    var selectedTab by remember { mutableStateOf(initialTab) }
    var currentPage by remember { mutableIntStateOf(1) }
    var state by remember { mutableStateOf<MessageCenterState>(MessageCenterState.Loading) }
    var isRefreshing by remember { mutableStateOf(false) }

    suspend fun loadTab(tab: MessageCenterTab, page: Int, preferCache: Boolean = true) {
        if (preferCache) {
            cachedContent(userSpaceRepository, tab, page)?.let {
                if (tab != selectedTab) return
                currentPage = page
                state = MessageCenterState.Success(it)
                if (it is MessageCenterContent.PrivateMessages) {
                    onPrivateMessageUnreadChange(it.page.hasUnreadMessages())
                }
                return
            }
        }
        val result = fetchContent(userSpaceRepository, tab, page)
        if (tab != selectedTab) return
        state = when (result) {
            is YamiboResult.Success -> {
                val content = result.value
                currentPage = content.pageNumber() ?: page
                if (content is MessageCenterContent.PrivateMessages) {
                    onPrivateMessageUnreadChange(content.page.hasUnreadMessages())
                }
                MessageCenterState.Success(content)
            }
            else -> MessageCenterState.Error(i18n(result.message()))
        }
    }

    LaunchedEffect(selectedTab) {
        currentPage = 1
        loadTab(selectedTab, 1)
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = colors.creamBackground,
        topBar = {
            if (mainTabTopBar) {
                MessageCenterMainTopBar(
                    title = i18n("我的消息"),
                    profile = currentUser,
                    onSpaceClick = {
                        navigator.navigate(IUserSpaceScreen(currentUser?.uid, currentUser?.username))
                    },
                )
            } else {
                MessageCenterTopBar(
                    title = selectedTab.title,
                    showEdit = selectedTab == MessageCenterTab.PrivateMessages,
                    onBack = { navigator.pop() },
                    onEdit = { navigator.navigate(sendPrivateMessageWebView()) },
                )
            }
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
                .background(colors.creamBackground),
        ) {
            MessageCenterTabRow(
                selectedTab = selectedTab,
                onSelect = { tab ->
                    if (tab != selectedTab) {
                        selectedTab = tab
                        currentPage = 1
                        isRefreshing = false
                        state = MessageCenterState.Loading
                    }
                },
            )
            Box(modifier = Modifier.fillMaxSize()) {
                when (val current = state) {
                    MessageCenterState.Loading -> YamiboLoadingContent()
                    is MessageCenterState.Error -> YamiboErrorContent(
                        message = current.message,
                        onRetry = {
                            state = MessageCenterState.Loading
                            scope.launch { loadTab(selectedTab, currentPage, preferCache = false) }
                        },
                    )
                    is MessageCenterState.Success -> PullToRefreshBox(
                        isRefreshing = isRefreshing,
                        onRefresh = {
                            isRefreshing = true
                            scope.launch {
                                loadTab(selectedTab, currentPage, preferCache = false)
                                isRefreshing = false
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        MessageCenterMainContent(
                            content = current.content,
                            selectedTab = selectedTab,
                            currentPage = currentPage,
                            onPageChange = { page ->
                                state = MessageCenterState.Loading
                                scope.launch { loadTab(selectedTab, page) }
                            },
                            onUserClick = { user -> navigator.navigate(IUserSpaceScreen(user.uid, user.name)) },
                            onNoticeUserClick = { userId -> navigator.navigate(IUserSpaceScreen(userId)) },
                            onOpenPrivateMessage = { user -> navigator.navigate(IPrivateMessageScreen(user.uid, user.name)) },
                            onMessageAction = {
                                scope.launch {
                                    feedbackController.post(i18n("TODO: 消息互動尚未接入"), duration = me.thenano.yamibo.yamibo_app.feedback.AppFeedbackDuration.Short)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageCenterMainTopBar(
    title: String,
    profile: ProfilePage?,
    onSpaceClick: () -> Unit,
) {
    val colors = YamiboTheme.colors
    YamiboMainTabTopBar(title = title) {
        Surface(onClick = onSpaceClick, shape = RoundedCornerShape(18.dp), color = Color.Transparent) {
            Row(
                modifier = Modifier.padding(start = 6.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                UserAvatar(profile?.avatarUrl, size = 28)
                Text(
                    text = i18n("我的空間"),
                    color = colors.brownDeep,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun MessageCenterTopBar(
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
private fun MessageCenterTabRow(
    selectedTab: MessageCenterTab,
    onSelect: (MessageCenterTab) -> Unit,
) {
    val colors = YamiboTheme.colors
    val tabs = remember { MessageCenterTab.entries }
    val selectedIndex = tabs.indexOf(selectedTab).coerceAtLeast(0)
    YamiboScrollableTabRow(selectedIndex = selectedIndex) {
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

private suspend fun fetchContent(
    repository: me.thenano.yamibo.yamibo_app.repository.UserSpaceRepository,
    tab: MessageCenterTab,
    page: Int,
): YamiboResult<MessageCenterContent> = when (tab) {
    MessageCenterTab.PrivateMessages -> repository.fetchPrivateMessages(page).mapSuccess { MessageCenterContent.PrivateMessages(it) }
    MessageCenterTab.Notices -> repository.fetchNotices(page).mapSuccess { MessageCenterContent.Notices(it) }
}

private fun cachedContent(
    repository: me.thenano.yamibo.yamibo_app.repository.UserSpaceRepository,
    tab: MessageCenterTab,
    page: Int,
): MessageCenterContent? = when (tab) {
    MessageCenterTab.PrivateMessages -> repository.getCachedPrivateMessages(page)?.let { MessageCenterContent.PrivateMessages(it) }
    MessageCenterTab.Notices -> repository.getCachedNotices(page)?.let { MessageCenterContent.Notices(it) }
}

private fun MessageCenterContent.pageNumber(): Int? = when (this) {
    is MessageCenterContent.PrivateMessages -> page.pageNav?.currentPage
    is MessageCenterContent.Notices -> page.pageNav?.currentPage
}

private fun UserSpacePrivateMessagePage.hasUnreadMessages(): Boolean =
    (unreadCount ?: 0) > 0 || messages.any { (it.unreadCount ?: 0) > 0 }

private fun <T, R> YamiboResult<T>.mapSuccess(transform: (T) -> R): YamiboResult<R> = when (this) {
    is YamiboResult.Success -> YamiboResult.Success(transform(value))
    is YamiboResult.Failure -> this
    is YamiboResult.NotLoggedIn -> this
    is YamiboResult.NoPermission -> this
    is YamiboResult.Maintenance -> this
}

private fun sendPrivateMessageWebView(): IActionWebView =
    IActionWebView(
        title = i18n("發消息"),
        initialUrl = YamiboRoute.SendPrivateMessagePage.build(),
        successCondition = { url -> isMessageListUrl(url) },
    )

private fun isMessageListUrl(url: String): Boolean {
    val target = YamiboRoute.UserSpace.Notification(
        type = YamiboRoute.UserSpace.NotificationType.MyMessage,
        page = 1,
    ).build()
    return url.startsWith(target.substringBefore("&page=")) &&
        url.contains("mod=space") &&
        url.contains("do=pm") &&
        !url.contains("spacecp")
}

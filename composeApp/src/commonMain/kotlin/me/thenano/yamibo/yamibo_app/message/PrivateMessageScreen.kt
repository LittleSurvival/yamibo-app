package me.thenano.yamibo.yamibo_app.message

import YamiboIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.littlesurvival.core.YamiboResult
import io.github.littlesurvival.dto.model.PageNav
import io.github.littlesurvival.dto.page.PrivateMessage
import io.github.littlesurvival.dto.page.PrivateMessagePage
import io.github.littlesurvival.dto.page.PrivateMessageType
import io.github.littlesurvival.dto.page.ProfilePage
import io.github.littlesurvival.dto.value.UserId
import kotlinx.coroutines.launch
import me.thenano.yamibo.yamibo_app.LocalAuthRepository
import me.thenano.yamibo.yamibo_app.LocalUserSpaceRepository
import me.thenano.yamibo.yamibo_app.components.feedback.YamiboEmptyContent
import me.thenano.yamibo.yamibo_app.components.feedback.YamiboErrorContent
import me.thenano.yamibo.yamibo_app.components.feedback.YamiboLoadingContent
import me.thenano.yamibo.yamibo_app.components.input.YamiboMessageInputBar
import me.thenano.yamibo.yamibo_app.components.navigation.YamiboPageNavigation
import me.thenano.yamibo.yamibo_app.components.navigation.YamiboTopBar
import me.thenano.yamibo.yamibo_app.components.navigation.YamiboTopBarIconAction
import me.thenano.yamibo.yamibo_app.components.user.UserAvatar
import me.thenano.yamibo.yamibo_app.i18n.i18n
import me.thenano.yamibo.yamibo_app.components.theme.YamiboSnackbarHost
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme
import me.thenano.yamibo.yamibo_app.thread.reader.components.post.impl.HtmlRenderer
import me.thenano.yamibo.yamibo_app.task.isActive

private sealed interface PrivateMessageState {
    data object Loading : PrivateMessageState
    data class Success(val page: PrivateMessagePage) : PrivateMessageState
    data class Error(val message: String) : PrivateMessageState
}

@Composable
fun PrivateMessageScreen(
    toUser: UserId,
    titleHint: String? = null,
) {
    val colors = YamiboTheme.colors
    val repository = LocalUserSpaceRepository.current
    val authRepository = LocalAuthRepository.current
    val currentUser = authRepository.currentUser()
    val navigator = me.thenano.yamibo.yamibo_app.navigation.LocalNavigator.current
    val scope = rememberCoroutineScope()
    val feedbackController = me.thenano.yamibo.yamibo_app.LocalAppFeedbackController.current
    val appTaskManager = me.thenano.yamibo.yamibo_app.LocalAppTaskManager.current
    val appTasks by appTaskManager.tasks.collectAsState()
    val listState = rememberLazyListState()
    var currentPage by remember(toUser) { mutableIntStateOf(1) }
    var state by remember(toUser) {
        mutableStateOf(
            repository.getCachedPrivateMessagePage(toUser)?.let { PrivateMessageState.Success(it) }
                ?: PrivateMessageState.Loading
        )
    }
    var input by remember(toUser) { mutableStateOf("") }
    var handledSendSubmissionId by remember(toUser) { mutableStateOf<Long?>(null) }

    suspend fun loadPage(page: Int? = null, preferCache: Boolean = true) {
        if (preferCache) {
            repository.getCachedPrivateMessagePage(toUser, page)?.let {
                currentPage = it.pageNav?.currentPage ?: page ?: currentPage
                state = PrivateMessageState.Success(it)
                return
            }
        }
        state = PrivateMessageState.Loading
        state = when (val result = repository.fetchPrivateMessagePage(toUser, page)) {
            is YamiboResult.Success -> {
                currentPage = result.value.pageNav?.currentPage ?: page ?: 1
                PrivateMessageState.Success(result.value)
            }
            else -> PrivateMessageState.Error(i18n(result.message()))
        }
    }

    LaunchedEffect(toUser) {
        if (state is PrivateMessageState.Loading) loadPage()
    }

    LaunchedEffect((state as? PrivateMessageState.Success)?.page?.messages?.size) {
        val size = (state as? PrivateMessageState.Success)?.page?.messages?.size ?: return@LaunchedEffect
        if (size > 0) listState.animateScrollToItem(size - 1)
    }

    val sendTaskKey = (state as? PrivateMessageState.Success)?.page?.let { page ->
        me.thenano.yamibo.yamibo_app.task.AppTaskKey("message-send:${page.pmId}")
    }
    val sendTaskState = sendTaskKey?.let(appTasks::get)
    val sending = sendTaskState?.isActive == true
    LaunchedEffect(sendTaskState) {
        val succeeded = sendTaskState as? me.thenano.yamibo.yamibo_app.task.AppTaskState.Succeeded
            ?: return@LaunchedEffect
        if (handledSendSubmissionId == succeeded.submissionId) return@LaunchedEffect
        handledSendSubmissionId = succeeded.submissionId
        input = ""
        loadPage(page = null, preferCache = false)
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = colors.creamBackground,
        topBar = {
            PrivateMessageTopBar(
                title = (state as? PrivateMessageState.Success)?.page?.title
                    ?: titleHint?.let { i18n("正在與{}聊天中......", it) }
                    ?: i18n("聊天"),
                onBack = { navigator.pop() },
                onRefresh = {
                    scope.launch { loadPage(page = null, preferCache = false) }
                },
            )
        },
        bottomBar = {
            val current = state as? PrivateMessageState.Success
            PrivateMessageInputBar(
                value = input,
                enabled = current != null && !sending,
                sending = sending,
                onValueChange = { input = it },
                onSend = {
                    val page = current?.page ?: return@PrivateMessageInputBar
                    val formHash = authRepository.currentUser()?.formHash
                    val message = input.trim()
                    when {
                        formHash == null -> {
                            feedbackController.post(i18n("請先登入後再發送消息"), duration = me.thenano.yamibo.yamibo_app.feedback.AppFeedbackDuration.Short)
                        }
                        message.isBlank() -> {
                            feedbackController.post(i18n("請輸入內容"), duration = me.thenano.yamibo.yamibo_app.feedback.AppFeedbackDuration.Short)
                        }
                        else -> appTaskManager.submit(
                            key = requireNotNull(sendTaskKey),
                        ) {
                            when (val result = repository.sendPrivateMessage(page.pmId, page.toUser, message, formHash)) {
                                is YamiboResult.Success -> {
                                    repository.clearPrivateMessagePages(toUser)
                                    repository.clearMessagePages()
                                    me.thenano.yamibo.yamibo_app.task.AppTaskResult.Success()
                                }
                                else -> {
                                    val reason = i18n(result.message())
                                    me.thenano.yamibo.yamibo_app.task.AppTaskResult.Failure(
                                        message = reason,
                                        feedback = me.thenano.yamibo.yamibo_app.feedback.AppFeedbackEvent(reason),
                                    )
                                }
                            }
                        }
                    }
                },
            )
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
                .background(colors.creamBackground),
        ) {
            when (val current = state) {
                PrivateMessageState.Loading -> PrivateMessageLoading()
                is PrivateMessageState.Error -> PrivateMessageError(current.message) {
                    scope.launch { loadPage(currentPage, preferCache = false) }
                }
                is PrivateMessageState.Success -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 16.dp),
                    ) {
                        if (current.page.messages.isEmpty()) {
                            item { EmptyPrivateMessages() }
                        } else {
                            itemsIndexed(
                                items = current.page.messages,
                                key = { index, message ->
                                    "${message.messageType}_${message.timeInfo.text}_${message.contentHtml.hashCode()}_$index"
                                },
                            ) { _, message ->
                                PrivateMessageBubble(message, currentUser)
                            }
                        }
                        current.page.pageNav?.let { nav ->
                            item {
                                PrivateMessagePageNavigation(nav, currentPage) { target ->
                                    scope.launch { loadPage(target) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PrivateMessageTopBar(title: String, onBack: () -> Unit, onRefresh: () -> Unit) {
    YamiboTopBar(
        title = title,
        titleAlign = TextAlign.Center,
        titleFontSize = 18,
        onBack = onBack,
    ) {
        YamiboTopBarIconAction(YamiboIcons.Reload, i18n("刷新"), onRefresh, iconSize = 22)
    }
}

@Composable
private fun PrivateMessageBubble(message: PrivateMessage, currentUser: ProfilePage?) {
    val colors = YamiboTheme.colors
    val isSelf = message.messageType == PrivateMessageType.Self
    val displayName = if (isSelf) currentUser?.username ?: message.user.name else message.user.name
    val avatarUrl = if (isSelf) currentUser?.avatarUrl ?: message.user.avatarUrl else message.user.avatarUrl
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = if (isSelf) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        if (!isSelf) {
            UserAvatar(avatarUrl, size = 38)
            Spacer(Modifier.width(8.dp))
        }
        Column(
            modifier = Modifier.fillMaxWidth(0.78f),
            horizontalAlignment = if (isSelf) Alignment.End else Alignment.Start,
        ) {
            Text(
                text = displayName,
                color = colors.brownDeep,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = if (isSelf) TextAlign.End else TextAlign.Start,
            )
            Spacer(Modifier.height(4.dp))
            Surface(
                shape = RoundedCornerShape(
                    topStart = if (isSelf) 12.dp else 2.dp,
                    topEnd = if (isSelf) 2.dp else 12.dp,
                    bottomStart = 12.dp,
                    bottomEnd = 12.dp,
                ),
                color = colors.creamSurface,
            ) {
                HtmlRenderer(
                    html = message.contentHtml,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = message.timeInfo.text,
                color = colors.brownLight,
                fontSize = 12.sp,
                textAlign = if (isSelf) TextAlign.End else TextAlign.Start,
            )
        }
        if (isSelf) {
            Spacer(Modifier.width(8.dp))
            UserAvatar(avatarUrl, size = 38)
        }
    }
}

@Composable
private fun PrivateMessageInputBar(
    value: String,
    enabled: Boolean,
    sending: Boolean,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    YamiboMessageInputBar(
        value = value,
        placeholder = i18n("請輸入內容..."),
        enabled = enabled,
        sending = sending,
        sendText = i18n("發送"),
        sendingText = i18n("發送中"),
        onValueChange = onValueChange,
        onSend = onSend,
    )
}

@Composable
private fun PrivateMessagePageNavigation(pageNav: PageNav, currentPage: Int, onPageChange: (Int) -> Unit) {
    YamiboPageNavigation(pageNav = pageNav, currentPage = currentPage, onPageChange = onPageChange)
}

@Composable
private fun PrivateMessageLoading() {
    YamiboLoadingContent()
}

@Composable
private fun PrivateMessageError(message: String, onRetry: () -> Unit) {
    YamiboErrorContent(message = message, onRetry = onRetry)
}

@Composable
private fun EmptyPrivateMessages() {
    YamiboEmptyContent(message = i18n("沒有找到消息"), modifier = Modifier.padding(vertical = 80.dp))
}

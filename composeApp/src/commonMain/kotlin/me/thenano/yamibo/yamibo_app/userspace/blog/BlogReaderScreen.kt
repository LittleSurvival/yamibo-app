package me.thenano.yamibo.yamibo_app.userspace.blog

import YamiboIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.littlesurvival.YamiboRoute
import io.github.littlesurvival.core.YamiboResult
import io.github.littlesurvival.dto.model.PageNav
import io.github.littlesurvival.dto.model.User
import io.github.littlesurvival.dto.page.BlogComment
import io.github.littlesurvival.dto.page.BlogPage
import io.github.littlesurvival.dto.value.BlogId
import io.github.littlesurvival.dto.value.UserId
import kotlinx.coroutines.launch
import me.thenano.yamibo.yamibo_app.LocalAuthRepository
import me.thenano.yamibo.yamibo_app.LocalBlogRepository
import me.thenano.yamibo.yamibo_app.components.controls.YamiboPrimaryButton
import me.thenano.yamibo.yamibo_app.components.controls.YamiboSmallActionButton
import me.thenano.yamibo.yamibo_app.components.controls.YamiboStatBadge
import me.thenano.yamibo.yamibo_app.components.feedback.YamiboEmptyContent
import me.thenano.yamibo.yamibo_app.components.feedback.YamiboErrorContent
import me.thenano.yamibo.yamibo_app.components.feedback.YamiboLoadingContent
import me.thenano.yamibo.yamibo_app.components.navigation.YamiboPageNavigation
import me.thenano.yamibo.yamibo_app.components.navigation.YamiboTopBar
import me.thenano.yamibo.yamibo_app.components.user.UserAvatar
import me.thenano.yamibo.yamibo_app.components.user.UserIdentityRow
import me.thenano.yamibo.yamibo_app.i18n.i18n
import me.thenano.yamibo.yamibo_app.navigation.LocalNavigator
import me.thenano.yamibo.yamibo_app.components.theme.YamiboSnackbarHost
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme
import me.thenano.yamibo.yamibo_app.thread.reader.components.post.impl.HtmlRenderer
import me.thenano.yamibo.yamibo_app.userspace.IUserSpaceScreen
import me.thenano.yamibo.yamibo_app.task.isActive
import me.thenano.yamibo.yamibo_app.webview.IPlatformWebView

private sealed interface BlogReaderState {
    data object Loading : BlogReaderState
    data class Success(val page: BlogPage) : BlogReaderState
    data class Error(val message: String) : BlogReaderState
}

@Composable
fun BlogReaderScreen(
    blogId: BlogId,
    userId: UserId? = null,
    titleHint: String? = null,
) {
    val colors = YamiboTheme.colors
    val repository = LocalBlogRepository.current
    val authRepository = LocalAuthRepository.current
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val feedbackController = me.thenano.yamibo.yamibo_app.LocalAppFeedbackController.current
    val appTaskManager = me.thenano.yamibo.yamibo_app.LocalAppTaskManager.current
    val appTasks by appTaskManager.tasks.collectAsState()
    val commentTaskKey = remember(blogId) {
        me.thenano.yamibo.yamibo_app.task.AppTaskKey("blog-comment:$blogId")
    }
    var currentPage by remember(blogId, userId) { mutableIntStateOf(1) }
    var state by remember(blogId, userId) {
        mutableStateOf(
            repository.getCachedBlogPage(blogId, userId, 1)?.let { BlogReaderState.Success(it) }
                ?: BlogReaderState.Loading
        )
    }
    var commentText by remember(blogId) { mutableStateOf("") }
    var handledCommentSubmissionId by remember(blogId) { mutableStateOf<Long?>(null) }

    suspend fun loadPage(page: Int, preferCache: Boolean = true) {
        if (preferCache) {
            repository.getCachedBlogPage(blogId, userId, page)?.let {
                currentPage = page
                state = BlogReaderState.Success(it)
                return
            }
        }
        state = BlogReaderState.Loading
        state = when (val result = repository.fetchBlogPage(blogId, userId, page)) {
            is YamiboResult.Success -> {
                currentPage = result.value.pageNav?.currentPage ?: page
                BlogReaderState.Success(result.value)
            }
            else -> BlogReaderState.Error(i18n(result.message()))
        }
    }

    LaunchedEffect(blogId, userId) {
        if (state is BlogReaderState.Loading) loadPage(1)
    }

    val commentTaskState = appTasks[commentTaskKey]
    val submitting = commentTaskState?.isActive == true
    LaunchedEffect(commentTaskState) {
        val succeeded = commentTaskState as? me.thenano.yamibo.yamibo_app.task.AppTaskState.Succeeded
            ?: return@LaunchedEffect
        if (handledCommentSubmissionId == succeeded.submissionId) return@LaunchedEffect
        handledCommentSubmissionId = succeeded.submissionId
        commentText = ""
        loadPage(currentPage, preferCache = false)
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = colors.creamBackground,
        topBar = {
            BlogReaderTopBar(
                title = i18n("日志"),
                onBack = { navigator.pop() },
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
                BlogReaderState.Loading -> BlogReaderLoading()
                is BlogReaderState.Error -> BlogReaderError(current.message) {
                    scope.launch { loadPage(currentPage, preferCache = false) }
                }
                is BlogReaderState.Success -> {
                    val page = current.page
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 24.dp),
                    ) {
                        item {
                            RootBlogCard(
                                page = page,
                                titleHint = titleHint,
                                onUserClick = { user -> navigator.navigate(IUserSpaceScreen(user.uid, user.name)) },
                                onUrlClick = { title, url ->
                                    navigator.navigate(IPlatformWebView(YamiboRoute.Domain.toFullLink(url), title = title))
                                },
                            )
                        }
                        item { BlogCommentSectionTitle(page.blogComments.size) }
                        if (page.blogComments.isEmpty()) {
                            item { BlogEmptyComments() }
                        } else {
                            items(page.blogComments, key = { it.bcId?.value ?: it.hashCode() }) { comment ->
                                BlogCommentCard(
                                    comment = comment,
                                    onUserClick = { user -> navigator.navigate(IUserSpaceScreen(user.uid, user.name)) },
                                    onReplyClick = { url ->
                                        navigator.navigate(IPlatformWebView(YamiboRoute.Domain.toFullLink(url), title = i18n("回覆")))
                                    },
                                )
                            }
                        }
                        page.pageNav?.let { nav ->
                            item {
                                BlogPageNavigation(nav = nav, currentPage = currentPage) { target ->
                                    scope.launch { loadPage(target) }
                                }
                            }
                        }
                        item {
                            BlogCommentEditor(
                                value = commentText,
                                enabled = authRepository.currentUser()?.formHash != null && !submitting,
                                submitting = submitting,
                                onValueChange = { commentText = it },
                                onSubmit = {
                                    val user = authRepository.currentUser()
                                    val formHash = user?.formHash
                                    val message = commentText.trim()
                                    when {
                                        formHash == null -> {
                                            feedbackController.post(i18n("請先登入後再評論"), duration = me.thenano.yamibo.yamibo_app.feedback.AppFeedbackDuration.Short)
                                        }
                                        message.isBlank() -> {
                                            feedbackController.post(i18n("請輸入評論內容"), duration = me.thenano.yamibo.yamibo_app.feedback.AppFeedbackDuration.Short)
                                        }
                                        else -> appTaskManager.submit(
                                            key = commentTaskKey,
                                        ) {
                                            when (val result = repository.postBlogComment(blogId, page.rootBlog.author.uid, message, formHash)) {
                                                is YamiboResult.Success -> {
                                                    repository.clearCachedBlog(blogId)
                                                    me.thenano.yamibo.yamibo_app.task.AppTaskResult.Success(
                                                        feedback = me.thenano.yamibo.yamibo_app.feedback.AppFeedbackEvent(result.value),
                                                    )
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
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BlogReaderTopBar(title: String, onBack: () -> Unit) {
    YamiboTopBar(title = title, onBack = onBack)
}

@Composable
private fun RootBlogCard(
    page: BlogPage,
    titleHint: String?,
    onUserClick: (User) -> Unit,
    onUrlClick: (String, String) -> Unit,
) {
    val colors = YamiboTheme.colors
    val root = page.rootBlog
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp)) {
        Text(
            text = page.blogInfo.title.ifBlank { titleHint ?: i18n("日志") },
            color = colors.textOnBackground,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 28.sp,
        )
        Spacer(Modifier.height(12.dp))
        BlogAuthorLine(root.author, root.timeInfo.text, onUserClick)
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            page.blogInfo.totalViews?.let { YamiboStatBadge(icon = YamiboIcons.Views, value = it.toString()) }
            page.blogInfo.totalReplies?.let {
                Spacer(Modifier.width(10.dp))
                YamiboStatBadge(icon = YamiboIcons.Comment, value = it.toString())
            }
        }
        Spacer(Modifier.height(12.dp))
        HtmlRenderer(html = root.contentHtml, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(14.dp))
        BlogActionRow(page, onUrlClick)
    }
}

@Composable
private fun BlogActionRow(page: BlogPage, onUrlClick: (String, String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        page.blogInfo.collectUrl?.let { BlogSmallButton(i18n("收藏")) { onUrlClick(i18n("收藏日志"), it) } }
        page.blogInfo.shareUrl?.let { BlogSmallButton(i18n("分享")) { onUrlClick(i18n("分享日志"), it) } }
        page.blogInfo.inviteUrl?.let { BlogSmallButton(i18n("邀請")) { onUrlClick(i18n("邀請閱讀"), it) } }
    }
}

@Composable
private fun BlogCommentSectionTitle(count: Int) {
    val colors = YamiboTheme.colors
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(color = colors.brownLight.copy(alpha = 0.45f))
        Text(
            text = if (count > 0) i18n("日志評論") else i18n("日志評論"),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 16.dp),
            color = colors.textOnBackground,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
        HorizontalDivider(color = colors.brownLight.copy(alpha = 0.35f))
    }
}

@Composable
private fun BlogCommentCard(
    comment: BlogComment,
    onUserClick: (User) -> Unit,
    onReplyClick: (String) -> Unit,
) {
    val colors = YamiboTheme.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            UserAvatar(
                comment.author.avatarUrl,
                size = 38,
                modifier = Modifier.clickable { onUserClick(comment.author) })
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = comment.author.name,
                    modifier = Modifier.clickable { onUserClick(comment.author) },
                    color = colors.textOnBackground,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(comment.timeInfo.text, color = colors.brownLight, fontSize = 12.sp)
            }
            comment.replyUrl?.let { url ->
                Text(
                    text = i18n("回覆"),
                    modifier = Modifier.clickable { onReplyClick(url) }.padding(6.dp),
                    color = colors.orangeAccent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        HtmlRenderer(html = comment.contentHtml, modifier = Modifier.fillMaxWidth())
    }
    HorizontalDivider(color = colors.brownLight.copy(alpha = 0.35f))
}

@Composable
private fun BlogCommentEditor(
    value: String,
    enabled: Boolean,
    submitting: Boolean,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().height(142.dp),
            enabled = enabled,
            placeholder = { Text(if (enabled) i18n("內容區") else i18n("請先登入後再評論")) },
        )
        Spacer(Modifier.height(12.dp))
        YamiboPrimaryButton(
            text = i18n("評論"),
            busyText = i18n("評論中..."),
            enabled = enabled,
            busy = submitting,
            onClick = onSubmit,
            modifier = Modifier.fillMaxWidth(),
            fillContentWidth = true,
        )
    }
}

@Composable
private fun BlogAuthorLine(user: User, time: String, onUserClick: (User) -> Unit) {
    UserIdentityRow(user = user, time = time, avatarSize = 42, onUserClick = onUserClick)
}

@Composable
private fun BlogSmallButton(text: String, onClick: () -> Unit) {
    YamiboSmallActionButton(text = text, onClick = onClick)
}

@Composable
private fun BlogPageNavigation(nav: PageNav, currentPage: Int, onPageChange: (Int) -> Unit) {
    YamiboPageNavigation(pageNav = nav, currentPage = currentPage, onPageChange = onPageChange)
}

@Composable
private fun BlogEmptyComments() {
    YamiboEmptyContent(
        message = i18n("沒有找到評論"),
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 48.dp)
    )
}

@Composable
private fun BlogReaderLoading() {
    YamiboLoadingContent()
}

@Composable
private fun BlogReaderError(message: String, onRetry: () -> Unit) {
    YamiboErrorContent(message = message, onRetry = onRetry)
}


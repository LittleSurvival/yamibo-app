package me.thenano.yamibo.yamibo_app.thread.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.LocalPlatformContext
import io.github.littlesurvival.YamiboRoute
import io.github.littlesurvival.core.YamiboResult
import io.github.littlesurvival.dto.page.Post
import io.github.littlesurvival.dto.page.ThreadPage
import io.github.littlesurvival.dto.value.FormHash
import io.github.littlesurvival.dto.value.PostId
import io.github.littlesurvival.dto.value.ThreadId
import io.github.littlesurvival.dto.value.UserId
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.thenano.yamibo.yamibo_app.repository.ChapterCommentIndex
import me.thenano.yamibo.yamibo_app.LocalAuthRepository
import me.thenano.yamibo.yamibo_app.LocalNovelThreadCacheRepository
import me.thenano.yamibo.yamibo_app.LocalThreadRepository
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme
import me.thenano.yamibo.yamibo_app.i18n.i18n
import me.thenano.yamibo.yamibo_app.navigation.LocalNavigator
import me.thenano.yamibo.yamibo_app.repository.ReadHistoryRepository
import me.thenano.yamibo.yamibo_app.repository.isValidThreadPage
import me.thenano.yamibo.yamibo_app.repository.validatedThreadPage
import me.thenano.yamibo.yamibo_app.repository.inapplinknavigation.InAppLinkContext
import me.thenano.yamibo.yamibo_app.thread.detail.novel.components.ThreadErrorContent
import me.thenano.yamibo.yamibo_app.thread.detail.novel.components.ThreadLoadingSkeleton
import me.thenano.yamibo.yamibo_app.thread.detail.novel.components.ThreadTopBar
import me.thenano.yamibo.yamibo_app.thread.reader.components.CommentBanner
import me.thenano.yamibo.yamibo_app.thread.reader.components.overlay.ReaderBottomBar
import me.thenano.yamibo.yamibo_app.thread.reader.components.overlay.ReaderFloatButtons
import me.thenano.yamibo.yamibo_app.thread.reader.components.post.PostRenderer
import me.thenano.yamibo.yamibo_app.util.shareText
import me.thenano.yamibo.yamibo_app.webview.action.IActionWebView

private sealed interface CommentState {
    data object Loading : CommentState
    data object Success : CommentState
    data class Error(val message: String) : CommentState
}

/**
 * Comment reader screen — shows comments (non-author posts) for a specific author post.
 *
 * Resolves explicit same-thread references across incrementally scanned pages.
 * Unreferenced replies retain chronological placement. Completion requires every
 * full-thread page, since late references can appear after later author posts.
 */
@Composable
internal fun CommentReaderScreen(
    tid: ThreadId,
    postTitle: String,
    oPostId: PostId,
    authorId: UserId,
    targetCommentPid: PostId? = null,
) {
    val colors = YamiboTheme.colors
    val threadRepository = LocalThreadRepository.current
    val novelCache = LocalNovelThreadCacheRepository.current
    val authRepo = LocalAuthRepository.current
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val feedbackController = me.thenano.yamibo.yamibo_app.LocalAppFeedbackController.current
    val platformContext = LocalPlatformContext.current
    val listState = rememberLazyListState()
    me.thenano.yamibo.yamibo_app.navigation.StatusBarScrollToTop { listState.scrollToItem(0) }
    val htmlLinkContext = remember(tid, postTitle, authorId) {
        InAppLinkContext(
            currentTid = tid,
            currentTitle = postTitle,
            currentAuthorId = authorId,
            currentThreadType = ReadHistoryRepository.ThreadEntryType.Novel,
        )
    }

    var state by remember { mutableStateOf<CommentState>(CommentState.Loading) }
    var commentPosts by remember { mutableStateOf<List<Post>>(emptyList()) }
    var isCommentComplete by remember { mutableStateOf(false) }
    var isLoadingMore by remember { mutableStateOf(false) }

    /** Current page in the full-view thread where we last fetched */
    var currentFullPage by remember(tid) { mutableIntStateOf(1) }
    val commentIndex = remember(tid, authorId) { ChapterCommentIndex(tid, authorId) }
    val scanMutex = remember(tid, authorId) { Mutex() }
    var useCachedPages by remember(tid, authorId) { mutableStateOf(true) }
    var targetCommentHandled by remember(tid, oPostId, targetCommentPid) { mutableStateOf(false) }

    fun getFormHash(): FormHash? {
        return authRepo.currentUser()?.formHash
    }

    val handleReply: (PostId) -> Unit = { pid ->
        val replyPageUrl = YamiboRoute.PostReply(tid, pid).build()
        navigator.navigate(
            IActionWebView(
                title = i18n("發表回復"),
                initialUrl = replyPageUrl,
                successCondition = { url -> url.contains("mod=viewthread") && url.contains("tid=") },
            onSuccess = { feedbackController.post(i18n("回復已發表，請刷新頁面查看")) },
            )
        )
    }

    suspend fun publishComments() {
        commentPosts = withContext(Dispatchers.Default) { commentIndex.commentsFor(oPostId) }
        isCommentComplete = commentIndex.complete
        state = CommentState.Success
    }

    suspend fun reloadComments(refresh: Boolean = false) = scanMutex.withLock {
        // Refresh invalidates derived attribution, while valid full pages remain
        // available for offline use. A malformed response never becomes an empty list.
        when (val result = threadRepository.fetchFindPost(tid, oPostId).validatedThreadPage(tid, oPostId)) {
            is YamiboResult.Success -> {
                commentIndex.clear()
                withContext(Dispatchers.Default) { commentIndex.addPage(result.value) }
                useCachedPages = !refresh
                currentFullPage = result.value.pageNav?.currentPage ?: 1
                novelCache.setCachedFullPage(tid, currentFullPage, result.value)
                publishComments()
            }
            else -> state = CommentState.Error(i18n(result.message()))
        }
    }

    suspend fun loadNextCommentPage() = scanMutex.withLock {
        val next = commentIndex.nextPage() ?: return@withLock
        isLoadingMore = true
        try {
            val cached = novelCache.getCachedFullPage(tid, next).takeIf { useCachedPages }
                ?.takeIf { it.isValidThreadPage(tid, page = next) }
            val result = cached?.let { YamiboResult.Success(it) }
                ?: threadRepository.fetchThread(tid, null, next).validatedThreadPage(tid, page = next)
            when (result) {
                is YamiboResult.Success -> {
                    withContext(Dispatchers.Default) { commentIndex.addPage(result.value) }
                    novelCache.setCachedFullPage(tid, next, result.value)
                    publishComments()
                }
                else -> feedbackController.post(i18n("載入失敗: {}", i18n(result.message())))
            }
        } finally {
            isLoadingMore = false
        }
    }

    LaunchedEffect(tid, oPostId) { reloadComments() }

    LaunchedEffect(state, commentPosts, targetCommentPid) {
        val target = targetCommentPid ?: return@LaunchedEffect
        if (targetCommentHandled || state !is CommentState.Success) return@LaunchedEffect
        val index = commentPosts.indexOfFirst { it.pid == target }
        if (index >= 0) {
            listState.animateScrollToItem(index)
            targetCommentHandled = true
        } else if (isCommentComplete) {
            feedbackController.post(i18n("無法精準定位該評論"))
            targetCommentHandled = true
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = colors.creamBackground,
        topBar = {
            Column {
                ThreadTopBar(
                    title = postTitle,
                    onBack = { navigator.pop() }
                )
                Text(
                    i18n("明確引用會歸回對應章節；其餘按發文順序顯示，不代表所討論章節。尚未讀取的頁面可能有更多回覆。"),
                    color = colors.textDark.copy(alpha = 0.65f),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
                CommentBanner(text = i18n("查看完整帖子與所有回覆"), onClick = {
                    navigator.navigate(IThreadReaderScreen(
                        tid = tid, title = postTitle,
                        threadType = ReadHistoryRepository.ThreadEntryType.Normal,
                        targetPid = targetCommentPid ?: oPostId,
                    ))
                })
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
                .background(colors.creamBackground)
        ) {
            when (val current = state) {
                is CommentState.Loading -> ThreadLoadingSkeleton()

                is CommentState.Error -> ThreadErrorContent(
                    message = current.message,
                    onRetry = {
                        state = CommentState.Loading
                        scope.launch {
                            reloadComments()
                        }
                    }
                )

                is CommentState.Success -> {
                    if (commentPosts.isEmpty() && isCommentComplete) {
                        // No comments
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = i18n("暫無評論"),
                                color = colors.textDark.copy(alpha = 0.5f),
                                fontSize = 14.sp
                            )
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 120.dp)
                        ) {
                            itemsIndexed(
                                commentPosts,
                                key = { _, post -> post.pid.value }
                            ) { index, post ->
                                PostRenderer(
                                    post = post,
                                    threadTitle = postTitle,
                                    linkContext = htmlLinkContext,
                                    onLoadRateOptions = { threadRepository.fetchRatePopoutPage(tid, post.pid) },
                                    onLoadRateResults = { threadRepository.fetchRateResults(tid, post.pid) },
                                    onLoadVoters = { optionId, page -> threadRepository.fetchVoters(tid, optionId, page) },
                                    onRate = { score, reason, noticeAuthor ->
                                        val formHash = getFormHash()
                                        if (formHash == null) {
                feedbackController.post(i18n("獲取登入資訊失敗，請重新登入"))
                                            return@PostRenderer
                                        }
                                        scope.launch {
                                            when (val res = threadRepository.ratePost(tid, post.pid, score, reason, formHash, noticeAuthor)) {
                                                is YamiboResult.Success -> feedbackController.post(i18n("評分成功，刷新後更新評分/點評狀態"))
                                                else -> feedbackController.post(i18n("評分失敗: {}", i18n(res.message())))
                                            }
                                        }
                                    },
                                    onComment = { message ->
                                        val formHash = getFormHash()
                                        if (formHash == null) {
                feedbackController.post(i18n("獲取登入資訊失敗，請重新登入"))
                                            return@PostRenderer
                                        }
                                        scope.launch {
                                            when (val res = threadRepository.commentPost(tid, post.pid, message, formHash)) {
                                                is YamiboResult.Success -> feedbackController.post(i18n("點評成功，刷新後更新評分/點評狀態"))
                                                else -> feedbackController.post(i18n("點評失敗: {}", i18n(res.message())))
                                            }
                                        }
                                    },
                                    onReply = { handleReply(post.pid) }
                                )

                                if (index < commentPosts.size - 1) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                        color = colors.brownPrimary.copy(alpha = 0.15f)
                                    )
                                }
                            }

                            // Load more banner (if comments are incomplete)
                            if (!isCommentComplete) {
                                item {
                                    if (isLoadingMore) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 16.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            CircularProgressIndicator(
                                                color = colors.brownPrimary,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                    } else {
                                        CommentBanner(
                                            text = i18n("讀取更多帖子頁面與引用回覆"),
                                            icon = "📖",
                                            onClick = {
                                                scope.launch {
                                                    loadNextCommentPage()
                                                }
                                            }
                                        )
                                    }
                                }
                            }

                            // End marker
                            if (isCommentComplete && commentPosts.isNotEmpty()) {
                                item {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 24.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = i18n("- 評論區結束 -"),
                                            color = colors.textDark.copy(alpha = 0.5f),
                                            fontSize = 12.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Always-visible float buttons (Refresh & Settings)
            ReaderFloatButtons(
                visible = true,
                onRefresh = {
                    scope.launch {
                        state = CommentState.Loading
                        reloadComments(refresh = true)
                    }
                },
                onSettings = {
                    scope.launch {
                        feedbackController.post(i18n("設定功能開發中"))
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 110.dp, end = 16.dp)
            )

            // Always-visible bottom bar (Reply, Favorite, Share)
            ReaderBottomBar(
                visible = true,
                isFavorited = false,
                onReply = {
                    val replyUrl = YamiboRoute.ThreadReply(tid, currentFullPage).build()
                    navigator.navigate(
                        IActionWebView(
                            title = i18n("發表回復"),
                            initialUrl = replyUrl,
                            successCondition = { url -> url.contains("mod=viewthread") && url.contains("tid=") },
                            onSuccess = {
                        feedbackController.post(i18n("回復已發表，請刷新頁面查看"))
                            },
                        )
                    )
                },
                onFavorite = {
                    scope.launch {
                        feedbackController.post(i18n("收藏功能開發中"))
                    }
                },
                onShare = {
                    val url = YamiboRoute.Thread(tid).build()
                    shareText(platformContext, url, postTitle)
                },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}

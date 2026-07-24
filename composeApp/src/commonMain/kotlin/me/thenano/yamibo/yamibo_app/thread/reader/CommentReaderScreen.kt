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
import me.thenano.yamibo.yamibo_app.LocalAuthRepository
import me.thenano.yamibo.yamibo_app.LocalNovelThreadCacheRepository
import me.thenano.yamibo.yamibo_app.LocalThreadRepository
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme
import me.thenano.yamibo.yamibo_app.i18n.i18n
import me.thenano.yamibo.yamibo_app.navigation.LocalNavigator
import me.thenano.yamibo.yamibo_app.repository.ReadHistoryRepository
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
 * Uses fetchFindPost to locate the oPost in the full-view thread, then extracts
 * all user posts between oPost and the next author post as "comments".
 *
 * Supports cross-page lazy loading when comments span multiple pages.
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
    var currentFullPage by remember { mutableIntStateOf(0) }
    var totalFullPages by remember { mutableIntStateOf(1) }
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

    /**
     * Extract comments from a full-view ThreadPage.
     *
     * Scans the page starting from oPostId:
     * 1. Find oPost in the page
     * 2. Collect all posts after oPost until the next author post
     * 3. If oPost is the last author post on the page, comments may be incomplete
     *
     * Also scans the entire page to pre-cache comments for other author posts.
     */
    fun extractAndCacheComments(threadPage: ThreadPage, startFromPost: Boolean = true): Pair<List<Post>, Boolean> {
        val allPosts = threadPage.posts
        val pageNum = threadPage.pageNav?.currentPage ?: 1

        // Cache the full page
        novelCache.setCachedFullPage(tid, pageNum, threadPage)

        // Pre-scan: find all author posts and cache their complete comments
        val authorPostIndices = allPosts.indices.filter { allPosts[it].author.uid == authorId }

        for (i in authorPostIndices.indices) {
            val authorIdx = authorPostIndices[i]
            val authorPost = allPosts[authorIdx]
            val nextAuthorIdx = if (i + 1 < authorPostIndices.size) authorPostIndices[i + 1] else null

            if (nextAuthorIdx != null) {
                // Comments are between this author post and the next
                val comments = allPosts.subList(authorIdx + 1, nextAuthorIdx)
                novelCache.setCachedComments(tid, authorPost.pid, comments)
                novelCache.setCommentComplete(tid, authorPost.pid, true)
            } else {
                // Last author post on this page — comments may extend to next page
                val partialComments = if (authorIdx + 1 < allPosts.size) {
                    allPosts.subList(authorIdx + 1, allPosts.size)
                } else {
                    emptyList()
                }

                val isLastPage = pageNum >= (threadPage.pageNav?.totalPages ?: 1)
                novelCache.setCachedComments(tid, authorPost.pid, partialComments)
                novelCache.setCommentComplete(tid, authorPost.pid, isLastPage)
            }
        }

        // Now extract the target oPost's comments
        if (startFromPost) {
            val oPostIdx = allPosts.indexOfFirst { it.pid == oPostId }
            if (oPostIdx < 0) return Pair(emptyList(), false)

            // Find the next author post after oPost
            val nextAuthorIdx = allPosts.indices
                .firstOrNull { it > oPostIdx && allPosts[it].author.uid == authorId }

            return if (nextAuthorIdx != null) {
                Pair(allPosts.subList(oPostIdx + 1, nextAuthorIdx), true)
            } else {
                // oPost is last author post on page
                val comments = if (oPostIdx + 1 < allPosts.size) {
                    allPosts.subList(oPostIdx + 1, allPosts.size)
                } else {
                    emptyList()
                }
                val isLastPage = pageNum >= (threadPage.pageNav?.totalPages ?: 1)
                Pair(comments, isLastPage)
            }
        }

        return Pair(emptyList(), false)
    }

    /**
     * Continue loading comments from the next page.
     *
     * Fetches the next full-view page and collects user posts until
     * the next author post is found.
     */
    fun extractContinuationComments(threadPage: ThreadPage): Pair<List<Post>, Boolean> {
        val allPosts = threadPage.posts
        val pageNum = threadPage.pageNav?.currentPage ?: 1

        // Cache the full page
        novelCache.setCachedFullPage(tid, pageNum, threadPage)

        // Find the first author post on this page
        val firstAuthorIdx = allPosts.indexOfFirst { it.author.uid == authorId }

        val continuationComments = if (firstAuthorIdx > 0) {
            allPosts.subList(0, firstAuthorIdx)
        } else if (firstAuthorIdx < 0) {
            // No author post on this page — all posts are comments
            allPosts
        } else {
            // Author post is at index 0 — no continuation comments
            emptyList()
        }

        val isComplete = firstAuthorIdx >= 0 ||
            pageNum >= (threadPage.pageNav?.totalPages ?: 1)

        // Also pre-scan for other author posts on this page
        extractAndCacheComments(threadPage, startFromPost = false)

        return Pair(continuationComments, isComplete)
    }

    suspend fun reloadComments() {
        when (val result = threadRepository.fetchFindPost(tid, oPostId)) {
            is YamiboResult.Success -> {
                currentFullPage = result.value.pageNav?.currentPage ?: 1
                totalFullPages = result.value.pageNav?.totalPages ?: 1
                val (comments, complete) = extractAndCacheComments(result.value)
                commentPosts = comments
                isCommentComplete = complete
                state = CommentState.Success
            }

            else -> state = CommentState.Error(i18n(result.message()))
        }
    }

    // Initial load
    LaunchedEffect(tid, oPostId) {
        // Check cache first
        val cachedComments = novelCache.getCachedComments(tid, oPostId)
        if (cachedComments != null) {
            commentPosts = cachedComments
            isCommentComplete = novelCache.isCommentComplete(tid, oPostId)
            state = CommentState.Success

            // Find the page for continuation loading if needed
            if (!isCommentComplete) {
                // We need to figure out which page we were on
                // Try to find the page from full-page cache
                for (page in 1..100) {
                    val cached = novelCache.getCachedFullPage(tid, page)
                    if (cached != null && cached.posts.any { it.pid == oPostId }) {
                        currentFullPage = page
                        totalFullPages = cached.pageNav?.totalPages ?: 1
                        break
                    }
                }
            }
            return@LaunchedEffect
        }

        reloadComments()
    }

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
            ThreadTopBar(
                title = postTitle,
                onBack = { navigator.pop() }
            )
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
                                            text = i18n("載入更多評論"),
                                            icon = "📖",
                                            onClick = {
                                                scope.launch {
                                                    isLoadingMore = true
                                                    val nextPage = currentFullPage + 1
                                                    if (nextPage > totalFullPages) {
                                                        isCommentComplete = true
                                                        novelCache.setCommentComplete(tid, oPostId, true)
                                                        isLoadingMore = false
                                                        return@launch
                                                    }

                                                    // Check full page cache first
                                                    val cachedPage = novelCache.getCachedFullPage(tid, nextPage)
                                                    val threadPage = cachedPage
                                                        ?: when (val result =
                                                            threadRepository.fetchThread(tid, null, nextPage)) {
                                                            is YamiboResult.Success -> result.value
                                                            else -> {
                                                                feedbackController.post(i18n("載入失敗: {}", i18n(result.message())))
                                                                isLoadingMore = false
                                                                return@launch
                                                            }
                                                        }

                                                    currentFullPage = nextPage
                                                    totalFullPages = threadPage.pageNav?.totalPages ?: totalFullPages

                                                    val (moreComments, complete) =
                                                        extractContinuationComments(threadPage)
                                                    commentPosts = commentPosts + moreComments
                                                    isCommentComplete = complete

                                                    // Update cache
                                                    novelCache.setCachedComments(tid, oPostId, commentPosts)
                                                    novelCache.setCommentComplete(tid, oPostId, complete)

                                                    isLoadingMore = false
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
                        reloadComments()
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

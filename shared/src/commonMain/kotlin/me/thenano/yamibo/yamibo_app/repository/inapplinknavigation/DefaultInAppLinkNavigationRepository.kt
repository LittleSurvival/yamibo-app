package me.thenano.yamibo.yamibo_app.repository.inapplinknavigation

import io.github.littlesurvival.YamiboForum
import io.github.littlesurvival.core.YamiboResult
import io.github.littlesurvival.dto.page.Post
import io.github.littlesurvival.dto.page.ThreadPage
import io.github.littlesurvival.dto.value.BlogId
import io.github.littlesurvival.dto.value.ForumId
import io.github.littlesurvival.dto.value.PostId
import io.github.littlesurvival.dto.value.TagId
import io.github.littlesurvival.dto.value.ThreadId
import io.github.littlesurvival.dto.value.UserId
import me.thenano.yamibo.yamibo_app.i18n.i18n
import me.thenano.yamibo.yamibo_app.repository.InAppLinkNavigationRepository
import me.thenano.yamibo.yamibo_app.repository.NovelPrePostCommentsCacheRepository
import me.thenano.yamibo.yamibo_app.repository.ReadHistoryRepository
import me.thenano.yamibo.yamibo_app.repository.ThreadRepository

class DefaultInAppLinkNavigationRepository(
    private val threadRepository: ThreadRepository,
    private val novelCacheRepository: NovelPrePostCommentsCacheRepository,
) : InAppLinkNavigationRepository {
    override suspend fun resolve(
        url: String,
        context: InAppLinkContext,
        onProgress: (String) -> Unit,
    ): InAppLinkResolveResult {
        onProgress(i18n("解析連結"))
        val normalized = normalizeYamiboUrl(url)
        if (!isYamiboUrl(normalized)) {
            return InAppLinkResolveResult.Resolved(InAppLinkTarget.WebOnlyTarget(normalized))
        }

        val pathAndQuery = stripYamiboHost(normalized)
        return when {
            isFindPostUrl(pathAndQuery) -> resolveFindPost(pathAndQuery, normalized, context, onProgress)
            isForumUrl(pathAndQuery) -> resolveForum(pathAndQuery, normalized)
            isThreadUrl(pathAndQuery) -> resolveThread(pathAndQuery, normalized, context, onProgress)
            isUserSpaceUrl(pathAndQuery) -> resolveUserSpace(pathAndQuery, normalized)
            isBlogUrl(pathAndQuery) -> resolveBlog(pathAndQuery, normalized)
            isTagUrl(pathAndQuery) -> resolveTag(pathAndQuery, normalized)
            else -> InAppLinkResolveResult.Resolved(InAppLinkTarget.WebOnlyTarget(normalized))
        }
    }

    private suspend fun resolveFindPost(
        pathAndQuery: String,
        fullUrl: String,
        context: InAppLinkContext,
        onProgress: (String) -> Unit,
    ): InAppLinkResolveResult {
        onProgress(i18n("讀取 findpost 定位頁"))
        val tid = extractInt(pathAndQuery, "ptid", "tid")?.let(::ThreadId)
            ?: context.currentTid
            ?: return unsupported(fullUrl, "missing tid")
        val pid = extractInt(pathAndQuery, "pid")?.let(::PostId)
            ?: return unsupported(fullUrl, "missing pid")

        val fullPage = when (val result = threadRepository.fetchFindPost(tid, pid)) {
            is YamiboResult.Success -> result.value
            else -> return InAppLinkResolveResult.Failed(InAppLinkTarget.WebOnlyTarget(fullUrl), result.message())
        }

        val currentPage = fullPage.resolvedCurrentPage()
        val title = fullPage.thread.title.ifBlank { context.currentTitle ?: "Thread ${tid.value}" }
        val forumId = fullPage.thread.forum.fid
        onProgress(i18n("判斷討論區類型"))

        if (!YamiboForum.isNovelForum(forumId)) {
            threadRepository.setCachedThread(tid, null, currentPage, fullPage)
            return InAppLinkResolveResult.Resolved(
                InAppLinkTarget.ThreadReaderTarget(
                    tid = tid,
                    title = title,
                    threadType = ReadHistoryRepository.ThreadEntryType.Normal,
                    initialPage = currentPage,
                    targetPid = pid,
                ),
            )
        }

        novelCacheRepository.setCachedFullPage(tid, currentPage, fullPage)
        onProgress(i18n("取得小說作者資訊"))
        val authorId = context.currentAuthorId ?: findNovelAuthorId(tid, currentPage, fullPage)
        ?: return InAppLinkResolveResult.Failed(InAppLinkTarget.WebOnlyTarget(fullUrl), "missing author id")
        val targetPost = fullPage.posts.firstOrNull { it.pid == pid }
            ?: return InAppLinkResolveResult.Failed(
                InAppLinkTarget.NovelDetailTarget(tid, title, authorId, i18n("無法精準定位，已開啟小說頁")),
                "target post not found",
            )

        return if (targetPost.author.uid == authorId) {
            resolveNovelAuthorPost(tid, title, authorId, pid, onProgress)
        } else {
            resolveNovelCommentPost(tid, title, authorId, pid, currentPage, fullPage, onProgress)
        }
    }

    private suspend fun resolveNovelAuthorPost(
        tid: ThreadId,
        title: String,
        authorId: UserId,
        pid: PostId,
        onProgress: (String) -> Unit,
    ): InAppLinkResolveResult {
        onProgress(i18n("尋找作者正文位置"))
        val firstReverse = when (val result = threadRepository.fetchThread(tid, authorId, page = 1, reverse = true)) {
            is YamiboResult.Success -> result.value
            else -> return InAppLinkResolveResult.Failed(
                InAppLinkTarget.NovelDetailTarget(tid, title, authorId, i18n("無法精準定位，已開啟小說頁")),
                result.message(),
            )
        }
        val totalPages = firstReverse.pageNav?.totalPages ?: 1
        findPostInPage(firstReverse, pid)?.let {
            return resolveNovelForwardAuthorPage(tid, title, authorId, pid, totalPages, totalPages, onProgress)
        }

        var left = 2
        var right = totalPages
        var fetches = 0
        while (left <= right && fetches < AUTHOR_PAGE_SEARCH_LIMIT) {
            val reversePage = (left + right) / 2
            fetches++
            onProgress(i18n("搜尋作者頁 {} / {}", reversePage, totalPages))
            val page = when (val result = threadRepository.fetchThread(tid, authorId, page = reversePage, reverse = true)) {
                is YamiboResult.Success -> result.value
                else -> break
            }
            val range = page.pidRange()
            if (findPostInPage(page, pid) != null) {
                val forwardPage = totalPages - reversePage + 1
                return resolveNovelForwardAuthorPage(
                    tid = tid,
                    title = title,
                    authorId = authorId,
                    pid = pid,
                    totalPages = totalPages,
                    estimatedForwardPage = forwardPage.coerceAtLeast(1),
                    onProgress = onProgress,
                )
            }
            if (range == null) break
            if (pid.value < range.first) {
                left = reversePage + 1
            } else {
                right = reversePage - 1
            }
        }

        return InAppLinkResolveResult.Failed(
            InAppLinkTarget.NovelDetailTarget(tid, title, authorId, i18n("無法精準定位，已開啟小說頁")),
            "author post search limit exceeded",
        )
    }

    private suspend fun resolveNovelForwardAuthorPage(
        tid: ThreadId,
        title: String,
        authorId: UserId,
        pid: PostId,
        totalPages: Int,
        estimatedForwardPage: Int,
        onProgress: (String) -> Unit,
    ): InAppLinkResolveResult {
        val startPage = estimatedForwardPage.coerceIn(1, totalPages.coerceAtLeast(1))
        val candidates = buildList {
            add(startPage)
            for (distance in 1..AUTHOR_FORWARD_VERIFY_RADIUS) {
                add(startPage - distance)
                add(startPage + distance)
            }
        }.filter { it in 1..totalPages }.distinct()

        candidates.forEach { page ->
            onProgress(i18n("確認作者頁 {} / {}", page, totalPages))
            val threadPage = when (val result = threadRepository.fetchThread(tid, authorId, page = page, reverse = false)) {
                is YamiboResult.Success -> result.value
                else -> return@forEach
            }
            if (findPostInPage(threadPage, pid) != null) {
                return InAppLinkResolveResult.Resolved(
                    InAppLinkTarget.ThreadReaderTarget(
                        tid = tid,
                        title = title,
                        threadType = ReadHistoryRepository.ThreadEntryType.Novel,
                        authorId = authorId,
                        initialPage = page,
                        targetPid = pid,
                    ),
                )
            }
        }

        return InAppLinkResolveResult.Failed(
            InAppLinkTarget.NovelDetailTarget(tid, title, authorId, i18n("無法精準定位，已開啟小說頁")),
            "author forward page verification failed",
        )
    }

    private suspend fun resolveNovelCommentPost(
        tid: ThreadId,
        title: String,
        authorId: UserId,
        targetPid: PostId,
        currentPage: Int,
        currentFullPage: ThreadPage,
        onProgress: (String) -> Unit,
    ): InAppLinkResolveResult {
        onProgress(i18n("尋找評論所屬正文"))
        findNearestPreviousAuthorPost(currentFullPage, authorId, targetPid)?.let { oPost ->
            val comments = currentFullPage.postsAfter(oPost.pid, untilNextAuthorId = authorId)
            novelCacheRepository.setCachedComments(tid, oPost.pid, comments)
            novelCacheRepository.setCommentComplete(tid, oPost.pid, currentFullPage.hasNextAuthorAfter(oPost.pid, authorId))
            return InAppLinkResolveResult.Resolved(
                InAppLinkTarget.CommentReaderTarget(tid, oPost.title.ifBlank { title }, oPost.pid, authorId, targetPid),
            )
        }

        var pageToScan = currentPage - 1
        var scanned = 0
        while (pageToScan >= 1 && scanned < COMMENT_BACK_SCAN_LIMIT) {
            scanned++
            onProgress(i18n("向前掃描評論頁 {}", pageToScan))
            val page = novelCacheRepository.getCachedFullPage(tid, pageToScan)
                ?: when (val result = threadRepository.fetchThread(tid, authorId = null, page = pageToScan)) {
                    is YamiboResult.Success -> result.value.also {
                        novelCacheRepository.setCachedFullPage(tid, it.resolvedCurrentPage(), it)
                    }
                    else -> break
                }
            page.posts.lastOrNull { it.author.uid == authorId }?.let { oPost ->
                val comments = page.postsAfter(oPost.pid, untilNextAuthorId = authorId) +
                    currentFullPage.posts.takeWhile { it.author.uid != authorId }
                novelCacheRepository.setCachedComments(tid, oPost.pid, comments)
                novelCacheRepository.setCommentComplete(tid, oPost.pid, currentFullPage.posts.any { it.author.uid == authorId })
                return InAppLinkResolveResult.Resolved(
                    InAppLinkTarget.CommentReaderTarget(tid, oPost.title.ifBlank { title }, oPost.pid, authorId, targetPid),
                )
            }
            pageToScan--
        }

        return InAppLinkResolveResult.Failed(
            InAppLinkTarget.NovelDetailTarget(tid, title, authorId, i18n("無法精準定位，已開啟小說頁")),
            "nearest author post not found",
        )
    }

    private suspend fun resolveThread(
        pathAndQuery: String,
        fullUrl: String,
        context: InAppLinkContext,
        onProgress: (String) -> Unit,
    ): InAppLinkResolveResult {
        val tid = extractThreadId(pathAndQuery)?.let(::ThreadId)
            ?: return unsupported(fullUrl, "missing tid")
        val pid = extractInt(pathAndQuery, "pid")?.let(::PostId)
        if (pid != null) {
            return resolveFindPost(pathAndQuery, fullUrl, context.copy(currentTid = tid), onProgress)
        }

        val page = extractInt(pathAndQuery, "page") ?: extractDashPage(pathAndQuery) ?: 1
        context.currentFid?.let { fid ->
            if (YamiboForum.isNovelForum(fid) || context.currentThreadType == ReadHistoryRepository.ThreadEntryType.Novel) {
                return InAppLinkResolveResult.Resolved(
                    InAppLinkTarget.NovelDetailTarget(tid, context.currentTitle ?: "Thread ${tid.value}", context.currentAuthorId),
                )
            }
        }

        onProgress(i18n("讀取帖子首頁"))
        val threadPage = when (val result = threadRepository.fetchThread(tid, page = page)) {
            is YamiboResult.Success -> result.value
            else -> return InAppLinkResolveResult.Failed(InAppLinkTarget.WebOnlyTarget(fullUrl), result.message())
        }
        val isNovel = YamiboForum.isNovelForum(threadPage.thread.forum.fid)
        return if (isNovel) {
            val authorId = threadPage.posts.firstOrNull()?.author?.uid
            InAppLinkResolveResult.Resolved(
                InAppLinkTarget.NovelDetailTarget(tid, threadPage.thread.title, authorId),
            )
        } else {
            InAppLinkResolveResult.Resolved(
                InAppLinkTarget.ThreadReaderTarget(
                    tid = tid,
                    title = threadPage.thread.title,
                    threadType = ReadHistoryRepository.ThreadEntryType.Normal,
                    initialPage = threadPage.resolvedCurrentPage().takeIf { it > 0 } ?: page,
                ),
            )
        }
    }

    private fun resolveForum(pathAndQuery: String, fullUrl: String): InAppLinkResolveResult {
        val fid = extractForumId(pathAndQuery)?.let(::ForumId)
            ?: return unsupported(fullUrl, "missing fid")
        return InAppLinkResolveResult.Resolved(
            InAppLinkTarget.ForumTarget(fid, YamiboForum.toForumName(fid), extractInt(pathAndQuery, "page") ?: extractDashPage(pathAndQuery)),
        )
    }

    private fun resolveUserSpace(pathAndQuery: String, fullUrl: String): InAppLinkResolveResult {
        val uid = extractInt(pathAndQuery, "uid")
            ?: Regex("""space-uid-(\d+)""").find(pathAndQuery)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: return unsupported(fullUrl, "missing uid")
        return InAppLinkResolveResult.Resolved(InAppLinkTarget.UserSpaceTarget(UserId(uid)))
    }

    private fun resolveBlog(pathAndQuery: String, fullUrl: String): InAppLinkResolveResult {
        val blogId = extractInt(pathAndQuery, "id", "blogid")
            ?: Regex("""blog-(\d+)-(\d+)""").find(pathAndQuery)?.groupValues?.getOrNull(2)?.toIntOrNull()
            ?: return unsupported(fullUrl, "missing blog id")
        val userId = extractInt(pathAndQuery, "uid")
            ?: Regex("""blog-(\d+)-(\d+)""").find(pathAndQuery)?.groupValues?.getOrNull(1)?.toIntOrNull()
        return InAppLinkResolveResult.Resolved(
            InAppLinkTarget.BlogReaderTarget(BlogId(blogId), userId?.let(::UserId)),
        )
    }

    private fun resolveTag(pathAndQuery: String, fullUrl: String): InAppLinkResolveResult {
        val tagId = extractInt(pathAndQuery, "id") ?: return unsupported(fullUrl, "missing tag id")
        return InAppLinkResolveResult.Resolved(
            InAppLinkTarget.TagDetailTarget(TagId(tagId), "Tag $tagId", extractInt(pathAndQuery, "page")),
        )
    }

    private suspend fun findNovelAuthorId(tid: ThreadId, currentPage: Int, currentFullPage: ThreadPage): UserId? {
        if (currentPage == 1) return currentFullPage.posts.firstOrNull()?.author?.uid
        val firstPage = novelCacheRepository.getCachedFullPage(tid, 1)
            ?: when (val result = threadRepository.fetchThread(tid, authorId = null, page = 1)) {
                is YamiboResult.Success -> result.value.also { novelCacheRepository.setCachedFullPage(tid, 1, it) }
                else -> return null
            }
        return firstPage.posts.firstOrNull()?.author?.uid
    }

    private fun unsupported(url: String, reason: String): InAppLinkResolveResult {
        return InAppLinkResolveResult.Failed(InAppLinkTarget.UnsupportedTarget(url), reason)
    }

    private companion object {
        const val AUTHOR_PAGE_SEARCH_LIMIT = 6
        const val AUTHOR_FORWARD_VERIFY_RADIUS = 2
        const val COMMENT_BACK_SCAN_LIMIT = 2

        fun isYamiboUrl(url: String): Boolean {
            return url.startsWith("https://bbs.yamibo.com/") ||
                url.startsWith("http://bbs.yamibo.com/") ||
                url.startsWith("https://yamibo.com/") ||
                url.startsWith("http://yamibo.com/")
        }

        fun stripYamiboHost(url: String): String {
            return url
                .removePrefix("https://bbs.yamibo.com/")
                .removePrefix("http://bbs.yamibo.com/")
                .removePrefix("https://yamibo.com/")
                .removePrefix("http://yamibo.com/")
        }

        fun isFindPostUrl(path: String): Boolean = path.contains("goto=findpost") || path.contains("findpost")
        fun isForumUrl(path: String): Boolean = path.contains("mod=forumdisplay") || path.startsWith("forum-")
        fun isThreadUrl(path: String): Boolean = path.contains("mod=viewthread") || path.startsWith("thread-")
        fun isUserSpaceUrl(path: String): Boolean = path.contains("mod=space") && !path.contains("do=blog") || path.startsWith("space-uid-")
        fun isBlogUrl(path: String): Boolean = path.contains("do=blog") || path.startsWith("blog-")
        fun isTagUrl(path: String): Boolean = path.contains("misc.php") && path.contains("mod=tag")

        fun extractInt(text: String, vararg names: String): Int? {
            names.forEach { name ->
                Regex("""(?:[?&]|^)$name=(\d+)""").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
            }
            return null
        }

        fun extractForumId(path: String): Int? {
            return extractInt(path, "fid") ?: Regex("""forum-(\d+)""").find(path)?.groupValues?.getOrNull(1)?.toIntOrNull()
        }

        fun extractThreadId(path: String): Int? {
            return extractInt(path, "tid", "ptid") ?: Regex("""thread-(\d+)""").find(path)?.groupValues?.getOrNull(1)?.toIntOrNull()
        }

        fun extractDashPage(path: String): Int? {
            return Regex("""(?:forum|thread)-\d+-(\d+)""").find(path)?.groupValues?.getOrNull(1)?.toIntOrNull()
        }

        fun ThreadPage.resolvedCurrentPage(): Int {
            return pageNav?.currentPage
                ?: pageNav?.prevPageIndex?.plus(1)
                ?: pageNav?.nextPageIndex?.minus(1)
                ?: 1
        }

        fun ThreadPage.pidRange(): IntRange? {
            val values = posts.map { it.pid.value }
            if (values.isEmpty()) return null
            return values.min()..values.max()
        }

        fun findPostInPage(page: ThreadPage, pid: PostId): Post? {
            return page.posts.firstOrNull { it.pid == pid }
        }

        fun findNearestPreviousAuthorPost(page: ThreadPage, authorId: UserId, targetPid: PostId): Post? {
            val targetIndex = page.posts.indexOfFirst { it.pid == targetPid }
            if (targetIndex <= 0) return null
            return page.posts.take(targetIndex).lastOrNull { it.author.uid == authorId }
        }

        fun ThreadPage.postsAfter(oPostId: PostId, untilNextAuthorId: UserId): List<Post> {
            val start = posts.indexOfFirst { it.pid == oPostId }
            if (start < 0 || start + 1 >= posts.size) return emptyList()
            return posts.drop(start + 1).takeWhile { it.author.uid != untilNextAuthorId }
        }

        fun ThreadPage.hasNextAuthorAfter(oPostId: PostId, authorId: UserId): Boolean {
            val start = posts.indexOfFirst { it.pid == oPostId }
            return start >= 0 && posts.drop(start + 1).any { it.author.uid == authorId }
        }
    }
}


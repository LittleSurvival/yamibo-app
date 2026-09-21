package me.thenano.yamibo.yamibo_app.repository.inapplinknavigation

import io.github.littlesurvival.core.YamiboResult
import io.github.littlesurvival.dto.model.ForumSummary
import io.github.littlesurvival.dto.model.PageNav
import io.github.littlesurvival.dto.model.Tags
import io.github.littlesurvival.dto.model.TimeInfo
import io.github.littlesurvival.dto.model.User
import io.github.littlesurvival.dto.page.AddFavoriteResult
import io.github.littlesurvival.dto.page.Post
import io.github.littlesurvival.dto.page.RatePopoutPage
import io.github.littlesurvival.dto.page.RateResultPopoutPage
import io.github.littlesurvival.dto.page.ThreadInfo
import io.github.littlesurvival.dto.page.ThreadPage
import io.github.littlesurvival.dto.page.VotersPopoutScreen
import io.github.littlesurvival.dto.value.FormHash
import io.github.littlesurvival.dto.value.ForumId
import io.github.littlesurvival.dto.value.PollOptionId
import io.github.littlesurvival.dto.value.PostId
import io.github.littlesurvival.dto.value.ThreadId
import io.github.littlesurvival.dto.value.UserId
import kotlinx.coroutines.runBlocking
import me.thenano.yamibo.yamibo_app.repository.NovelPrePostCommentsCacheRepository
import me.thenano.yamibo.yamibo_app.repository.ReadHistoryRepository
import me.thenano.yamibo.yamibo_app.repository.ThreadRepository
import kotlin.test.Test
import kotlin.test.assertEquals

class DefaultInAppLinkNavigationRepositoryTest {
    @Test
    fun sameThreadNovelLinkReusesContextWithoutFetching() = runBlocking {
        val threads = FakeThreadRepository(page(200, "unused", 9))
        val repository = DefaultInAppLinkNavigationRepository(threads, FakeNovelCacheRepository())

        val result = repository.resolve(
            url = "https://bbs.yamibo.com/forum.php?mod=viewthread&tid=100",
            context = sourceContext(),
        ) {}

        val target = (result as InAppLinkResolveResult.Resolved).target as InAppLinkTarget.NovelDetailTarget
        assertEquals(ThreadId(100), target.tid)
        assertEquals("Source title", target.title)
        assertEquals(UserId(7), target.authorId)
        assertEquals(emptyList(), threads.fetchCalls)
    }

    @Test
    fun crossThreadNovelLinkUsesTargetMetadata() = runBlocking {
        val threads = FakeThreadRepository(page(200, "Target title", 9))
        val repository = DefaultInAppLinkNavigationRepository(threads, FakeNovelCacheRepository())

        val result = repository.resolve(
            url = "https://bbs.yamibo.com/forum.php?mod=viewthread&tid=200",
            context = sourceContext(),
        ) {}

        val target = (result as InAppLinkResolveResult.Resolved).target as InAppLinkTarget.NovelDetailTarget
        assertEquals(ThreadId(200), target.tid)
        assertEquals("Target title", target.title)
        assertEquals(UserId(9), target.authorId)
        assertEquals(listOf(FetchCall(200, null, 1, false)), threads.fetchCalls)
    }

    @Test
    fun crossThreadFindPostUsesTargetOwner() = runBlocking {
        val threads = FakeThreadRepository(page(200, "Target title", 9, pid = 22))
        val repository = DefaultInAppLinkNavigationRepository(threads, FakeNovelCacheRepository())

        val result = repository.resolve(
            url = "https://bbs.yamibo.com/forum.php?mod=redirect&goto=findpost&ptid=200&pid=22",
            context = sourceContext(),
        ) {}

        val target = (result as InAppLinkResolveResult.Resolved).target as InAppLinkTarget.ThreadReaderTarget
        assertEquals(ThreadId(200), target.tid)
        assertEquals("Target title", target.title)
        assertEquals(UserId(9), target.authorId)
        assertEquals(PostId(22), target.targetPid)
        assertEquals(9, threads.fetchCalls.last().authorId)
    }

    @Test
    fun staticPostLinkUsesTargetIdWithoutChangingSourceContext() = runBlocking {
        val threads = FakeThreadRepository(page(200, "Target title", 9, pid = 22))
        val repository = DefaultInAppLinkNavigationRepository(threads, FakeNovelCacheRepository())

        val result = repository.resolve("https://bbs.yamibo.com/thread-200-1-1.html?pid=22", sourceContext()) {}

        val target = (result as InAppLinkResolveResult.Resolved).target as InAppLinkTarget.ThreadReaderTarget
        assertEquals(listOf(200 to 22), threads.findPostCalls)
        assertEquals(ThreadId(200), target.tid)
        assertEquals(UserId(9), target.authorId)
    }

    @Test
    fun laterNovelPageFindsOwnerOnFirstPageRatherThanUsingFirstReply() = runBlocking {
        val firstPage = page(200, "Target title", 9)
        val laterPage = page(200, "Target title", 42).copy(pageNav = PageNav(currentPage = 2, totalPages = 2))
        val threads = FakeThreadRepository(laterPage, firstPage)
        val repository = DefaultInAppLinkNavigationRepository(threads, FakeNovelCacheRepository())

        val result = repository.resolve("https://bbs.yamibo.com/thread-200-2-1.html", sourceContext()) {}

        val target = (result as InAppLinkResolveResult.Resolved).target as InAppLinkTarget.NovelDetailTarget
        assertEquals(UserId(9), target.authorId)
        assertEquals(listOf(FetchCall(200, null, 2, false), FetchCall(200, null, 1, false)), threads.fetchCalls)
    }

    @Test
    fun unavailableOwnerDoesNotFallBackToReplyOrSourceAuthor() = runBlocking {
        val laterPage = page(200, "Target title", 42).copy(pageNav = PageNav(currentPage = 2, totalPages = 2))
        val threads = FakeThreadRepository(laterPage, failFirstPage = true)
        val repository = DefaultInAppLinkNavigationRepository(threads, FakeNovelCacheRepository())

        val result = repository.resolve("https://bbs.yamibo.com/forum.php?mod=viewthread&tid=200&page=2", sourceContext()) {}

        val target = (result as InAppLinkResolveResult.Resolved).target as InAppLinkTarget.NovelDetailTarget
        assertEquals(null, target.authorId)
    }

    private fun sourceContext() = InAppLinkContext(
        currentTid = ThreadId(100),
        currentTitle = "Source title",
        currentFid = ForumId(49),
        currentAuthorId = UserId(7),
        currentThreadType = ReadHistoryRepository.ThreadEntryType.Novel,
    )

    private fun page(tid: Int, title: String, ownerId: Int, pid: Int = 1): ThreadPage {
        val owner = User(UserId(ownerId), "owner-$ownerId")
        return ThreadPage(
            thread = ThreadInfo(
                tid = ThreadId(tid),
                title = title,
                forum = ForumSummary(ForumId(49), "文學區", "forum.php?fid=49"),
            ),
            posts = listOf(
                Post(
                    pid = PostId(pid),
                    floor = 1,
                    title = "chapter",
                    author = owner,
                    timeCreate = TimeInfo("2026-01-01", null, 0),
                    contentHtml = "",
                    tags = Tags(),
                    poll = null,
                ),
            ),
            pageNav = PageNav(currentPage = 1, totalPages = 1),
        )
    }

    private data class FetchCall(val tid: Int, val authorId: Int?, val page: Int, val reverse: Boolean)

    private class FakeThreadRepository(
        private val targetPage: ThreadPage,
        private val firstPage: ThreadPage = targetPage,
        private val failFirstPage: Boolean = false,
    ) : ThreadRepository {
        val fetchCalls = mutableListOf<FetchCall>()
        val findPostCalls = mutableListOf<Pair<Int, Int>>()

        override suspend fun fetchThread(tid: ThreadId, authorId: UserId?, page: Int, reverse: Boolean): YamiboResult<ThreadPage> {
            fetchCalls += FetchCall(tid.value, authorId?.value, page, reverse)
            return when {
                page == 1 && failFirstPage -> YamiboResult.Failure("offline")
                page == 1 -> YamiboResult.Success(firstPage)
                else -> YamiboResult.Success(targetPage)
            }
        }

        override suspend fun fetchFindPost(tid: ThreadId, postId: PostId, authorId: UserId?): YamiboResult<ThreadPage> {
            findPostCalls += tid.value to postId.value
            return YamiboResult.Success(targetPage)
        }

        override fun getCachedThread(tid: ThreadId, authorId: UserId?, page: Int): ThreadPage? = null
        override fun setCachedThread(tid: ThreadId, authorId: UserId?, page: Int, threadPage: ThreadPage) = Unit
        override fun clearCachedThread(tid: ThreadId) = Unit
        override suspend fun addFavorite(tid: ThreadId, formHash: FormHash): YamiboResult<AddFavoriteResult> = error("unused")
        override suspend fun votePoll(fId: ForumId, tId: ThreadId, pollOptionIds: List<PollOptionId>, formHash: FormHash): YamiboResult<String> = error("unused")
        override suspend fun fetchRatePopoutPage(tId: ThreadId, pId: PostId): YamiboResult<RatePopoutPage> = error("unused")
        override suspend fun fetchRateResults(tId: ThreadId, pId: PostId): YamiboResult<RateResultPopoutPage> = error("unused")
        override suspend fun fetchVoters(tId: ThreadId, pollOptionId: PollOptionId?, page: Int): YamiboResult<VotersPopoutScreen> = error("unused")
        override suspend fun ratePost(tId: ThreadId, pId: PostId, score: Int, reason: String, formHash: FormHash, noticeAuthor: Boolean): YamiboResult<String> = error("unused")
        override suspend fun commentPost(tId: ThreadId, pId: PostId, message: String, formHash: FormHash): YamiboResult<String> = error("unused")
    }

    private class FakeNovelCacheRepository : NovelPrePostCommentsCacheRepository {
        override fun getCachedFullPage(tid: ThreadId, page: Int): ThreadPage? = null
        override fun setCachedFullPage(tid: ThreadId, page: Int, threadPage: ThreadPage) = Unit
        override fun getCachedComments(tid: ThreadId, postId: PostId): List<Post>? = null
        override fun setCachedComments(tid: ThreadId, postId: PostId, comments: List<Post>) = Unit
        override fun isCommentComplete(tid: ThreadId, postId: PostId): Boolean = false
        override fun setCommentComplete(tid: ThreadId, postId: PostId, complete: Boolean) = Unit
        override fun clearCache(tid: ThreadId) = Unit
    }
}

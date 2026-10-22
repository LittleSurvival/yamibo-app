package me.thenano.yamibo.yamibo_app.repository

import me.thenano.yamibo.yamibo_app.repository.thread.fetchReaderStartPage
import me.thenano.yamibo.yamibo_app.repository.thread.isValidThreadPage
import me.thenano.yamibo.yamibo_app.repository.thread.validatedThreadPage

import io.github.littlesurvival.core.YamiboResult
import io.github.littlesurvival.dto.model.*
import io.github.littlesurvival.dto.page.*
import io.github.littlesurvival.dto.value.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class ReaderAuthorPaginationTest {
    private val tid = ThreadId(1348)
    private val author = UserId(1)
    private fun post(id: Int, uid: UserId = author) = Post(
        pid = PostId(id), floor = id, title = "post $id", author = User(uid, "user"),
        timeCreate = TimeInfo("2026-10-22", null, 0), contentHtml = "body", tags = Tags(), poll = null,
    )
    private fun page(number: Int, total: Int, vararg posts: Post) = ThreadPage(
        ThreadInfo(tid, "novel", ForumSummary(ForumId(49), "novels", "forum.php?fid=49")),
        posts.toList(), PageNav(currentPage = number, totalPages = total),
    )

    @Test fun immersiveChapterKeepsAuthorPageWhenFullThreadHasThreePages(): Unit = runBlocking {
        val filtered = page(1, 1, post(1), post(10))
        val full = page(2, 3, post(10), post(11, UserId(2)))
        val repository = Stub(YamiboResult.Success(filtered), YamiboResult.Success(full))
        repeat(2) { // Entry and re-entry after forum mode use the same canonical author view.
            val result = repository.fetchReaderStartPage(tid, PostId(10), author, 1)
            assertEquals(filtered, assertIs<YamiboResult.Success<ThreadPage>>(result).value)
        }
        assertEquals(listOf<Pair<Int, UserId?>>(1 to author, 1 to author), repository.pageRequests)
        assertEquals(0, repository.findRequests)
    }

    @Test fun catalogChapterBeyondFirstAuthorPageKeepsItsOwnPageNumber(): Unit = runBlocking {
        val canonical = page(2, 2, post(100))
        val repository = Stub(YamiboResult.Success(canonical))
        assertEquals(canonical, assertIs<YamiboResult.Success<ThreadPage>>(
            repository.fetchReaderStartPage(tid, PostId(100), author, 2),
        ).value)
        assertEquals(listOf<Pair<Int, UserId?>>(2 to author), repository.pageRequests)
    }

    @Test fun forumEntryStillUsesFullThreadFindPost(): Unit = runBlocking {
        val full = page(3, 3, post(22, UserId(2)))
        val repository = Stub(located = YamiboResult.Success(full))
        assertEquals(full, assertIs<YamiboResult.Success<ThreadPage>>(
            repository.fetchReaderStartPage(tid, PostId(22), null, 1),
        ).value)
        assertEquals(1, repository.findRequests)
        assertTrue(repository.pageRequests.isEmpty())
    }

    @Test fun mixedAuthorSnapshotCannotEnterAuthorCacheOrOfflineReader() {
        val mixed = page(1, 3, post(1), post(2, UserId(2)))
        assertFalse(mixed.isValidThreadPage(tid, page = 1, authorId = author))
        assertTrue(mixed.isValidThreadPage(tid, page = 1))
        assertIs<YamiboResult.Failure>(YamiboResult.Success(mixed)
            .validatedThreadPage(tid, page = 1, authorId = author))
    }

    @Test fun serverIgnoringAuthorFilterRemainsFailure(): Unit = runBlocking {
        val repository = Stub(YamiboResult.Success(page(1, 3, post(1), post(2, UserId(2)))))
        assertIs<YamiboResult.Failure>(repository.fetchReaderStartPage(tid, PostId(1), author, 1))
    }

    @Test fun wrongPageIsNotSilentlyRelabeledAsCatalogPage(): Unit = runBlocking {
        val repository = Stub(YamiboResult.Success(page(1, 2, post(10))))
        assertIs<YamiboResult.Failure>(repository.fetchReaderStartPage(tid, PostId(10), author, 2))
    }

    @Test fun singleAuthorPageWithoutNavigationIsValid() {
        assertTrue(page(1, 1, post(1)).copy(pageNav = null)
            .isValidThreadPage(tid, page = 1, authorId = author))
    }

    @Test fun networkFailureIsPreservedWithoutFindPostFallback(): Unit = runBlocking {
        val failure = YamiboResult.Failure("login or network failure")
        val repository = Stub(failure)
        assertSame(failure, repository.fetchReaderStartPage(tid, PostId(1), author, 1))
        assertEquals(0, repository.findRequests)
    }

    @Test fun cancellationIsNotConvertedIntoContentFailure(): Unit = runBlocking {
        val repository = Stub(cancel = true)
        assertFailsWith<CancellationException> { repository.fetchReaderStartPage(tid, PostId(1), author, 1) }
        Unit
    }

    private class Stub(
        val canonical: YamiboResult<ThreadPage> = YamiboResult.Failure("unused canonical"),
        val located: YamiboResult<ThreadPage> = YamiboResult.Failure("unused locator"),
        val cancel: Boolean = false,
    ) : ThreadRepository {
        val pageRequests = mutableListOf<Pair<Int, UserId?>>()
        var findRequests = 0
        override suspend fun fetchThread(tid: ThreadId, authorId: UserId?, page: Int, reverse: Boolean): YamiboResult<ThreadPage> {
            if (cancel) throw CancellationException("leave reader")
            pageRequests += page to authorId
            return canonical
        }
        override suspend fun fetchFindPost(tid: ThreadId, postId: PostId, authorId: UserId?): YamiboResult<ThreadPage> {
            assertNull(authorId)
            findRequests++
            return located
        }
        override fun getCachedThread(tid: ThreadId, authorId: UserId?, page: Int): ThreadPage? = null
        override fun setCachedThread(tid: ThreadId, authorId: UserId?, page: Int, threadPage: ThreadPage) = Unit
        override fun clearCachedThread(tid: ThreadId) = Unit
        override suspend fun addFavorite(tid: ThreadId, formHash: FormHash) = error("unused")
        override suspend fun votePoll(fId: ForumId, tId: ThreadId, pollOptionIds: List<PollOptionId>, formHash: FormHash) = error("unused")
        override suspend fun fetchRatePopoutPage(tId: ThreadId, pId: PostId) = error("unused")
        override suspend fun fetchRateResults(tId: ThreadId, pId: PostId) = error("unused")
        override suspend fun fetchVoters(tId: ThreadId, pollOptionId: PollOptionId?, page: Int) = error("unused")
        override suspend fun ratePost(tId: ThreadId, pId: PostId, score: Int, reason: String, formHash: FormHash, noticeAuthor: Boolean) = error("unused")
        override suspend fun commentPost(tId: ThreadId, pId: PostId, message: String, formHash: FormHash) = error("unused")
    }
}

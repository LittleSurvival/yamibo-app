package me.thenano.yamibo.yamibo_app.repository

import me.thenano.yamibo.yamibo_app.repository.thread.validatedThreadPage
import me.thenano.yamibo.yamibo_app.repository.thread.isValidThreadPage

import me.thenano.yamibo.yamibo_app.util.auth.setNativeCookies

import io.github.littlesurvival.YamiboClient
import io.github.littlesurvival.core.YamiboResult
import io.github.littlesurvival.dto.page.RatePopoutPage
import io.github.littlesurvival.dto.page.RateResultPopoutPage
import io.github.littlesurvival.dto.page.ThreadPage
import io.github.littlesurvival.dto.page.AddFavoriteResult
import io.github.littlesurvival.dto.page.VotersPopoutScreen
import io.github.littlesurvival.dto.value.FormHash
import io.github.littlesurvival.dto.value.ForumId
import io.github.littlesurvival.dto.value.PollOptionId
import io.github.littlesurvival.dto.value.PostId
import io.github.littlesurvival.dto.value.ThreadId
import io.github.littlesurvival.dto.value.UserId
import me.thenano.yamibo.yamibo_app.store.auth.CookieStore
import me.thenano.yamibo.yamibo_app.repository.thread.ThreadPageReads

import me.thenano.yamibo.yamibo_app.core.cache.DiskCacheFactory

class IOSThreadRepository(
    private val cookieStore: CookieStore,
    private val yamiboClient: YamiboClient,
    diskCacheFactory: DiskCacheFactory
) : ThreadRepository {
    private val pages = ThreadPageReads(diskCacheFactory, cookieStore)

    override suspend fun fetchThread(
        tid: ThreadId, authorId: UserId?, page: Int, reverse: Boolean,
    ): YamiboResult<ThreadPage> = pages.fetch(tid, authorId, page, reverse) { session ->
        yamiboClient.setNativeCookies(session)
        yamiboClient.fetchThreadById(tid, authorId, reverse, page)
    }

    override suspend fun fetchFindPost(
        tid: ThreadId,
        postId: PostId,
        authorId: UserId?,
    ): YamiboResult<ThreadPage> {
        yamiboClient.setNativeCookies(cookieStore.load() ?: "")
        val located = yamiboClient.fetchFindPost(threadId = tid, authorId = authorId, postId = postId)
        if (located !is YamiboResult.Success || located.value.isValidThreadPage(tid, postId)) {
            return located
        }
        // A known page can be fetched once via the explicit mobile canonical route.
        // Never guess a page or turn login/WAF/permission failures into retries.
        val page = located.value.pageNav?.currentPage?.takeIf { it > 0 }
            ?: return located.validatedThreadPage(tid, postId)
        return yamiboClient.fetchThreadById(tid, authorId, page = page)
            .validatedThreadPage(tid, postId, page)
    }

    override suspend fun addFavorite(tid: ThreadId, formHash: FormHash): YamiboResult<AddFavoriteResult> {
        yamiboClient.setNativeCookies(cookieStore.load() ?: "")
        return yamiboClient.fetchAddFavorite(tid, formHash)
    }

    override suspend fun votePoll(fId: ForumId, tId: ThreadId, pollOptionIds: List<PollOptionId>, formHash: FormHash): YamiboResult<String> {
        yamiboClient.setNativeCookies(cookieStore.load() ?: "")
        return yamiboClient.votePoll(fId, tId, pollOptionIds, formHash)
    }

    override suspend fun fetchRatePopoutPage(tId: ThreadId, pId: PostId): YamiboResult<RatePopoutPage> {
        yamiboClient.setNativeCookies(cookieStore.load() ?: "")
        return yamiboClient.fetchRatePopoutPage(tId, pId)
    }

    override suspend fun fetchRateResults(tId: ThreadId, pId: PostId): YamiboResult<RateResultPopoutPage> {
        yamiboClient.setNativeCookies(cookieStore.load() ?: "")
        return yamiboClient.fetchRateResultPopoutPage(tId, pId)
    }

    override suspend fun fetchVoters(tId: ThreadId, pollOptionId: PollOptionId?, page: Int): YamiboResult<VotersPopoutScreen> {
        yamiboClient.setNativeCookies(cookieStore.load() ?: "")
        return yamiboClient.fetchViewVoters(tId, pollOptionId, page)
    }

    override suspend fun ratePost(tId: ThreadId, pId: PostId, score: Int, reason: String, formHash: FormHash, noticeAuthor: Boolean): YamiboResult<String> {
        yamiboClient.setNativeCookies(cookieStore.load() ?: "")
        return yamiboClient.fetchRatePost(tId, pId, score, reason, formHash, noticeAuthor)
    }

    override suspend fun commentPost(tId: ThreadId, pId: PostId, message: String, formHash: FormHash): YamiboResult<String> {
        yamiboClient.setNativeCookies(cookieStore.load() ?: "")
        return yamiboClient.fetchCommentPost(tId, pId, message, formHash)
    }

    override fun knownPageForPost(tid: ThreadId, author: UserId?, pid: PostId): Int? = pages.pageOf(tid, author, pid)

    override fun cachedThreadPages(tid: ThreadId, authorId: UserId?) = pages.cachedPages(tid, authorId)
    override fun getCachedThread(tid: ThreadId, authorId: UserId?, page: Int) = pages.get(tid, authorId, page)

    override fun setCachedThread(tid: ThreadId, authorId: UserId?, page: Int, threadPage: ThreadPage) =
        pages.set(tid, authorId, page, threadPage)

    override fun clearCachedThread(tid: ThreadId) = pages.clear(tid)
}

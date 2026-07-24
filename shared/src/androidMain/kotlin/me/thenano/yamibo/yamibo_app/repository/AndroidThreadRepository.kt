package me.thenano.yamibo.yamibo_app.repository

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
import kotlin.time.Duration.Companion.hours

import me.thenano.yamibo.yamibo_app.core.cache.DiskCacheFactory

class AndroidThreadRepository(
    private val cookieStore: CookieStore,
    private val yamiboClient: YamiboClient,
    diskCacheFactory: DiskCacheFactory
) : ThreadRepository {

    private val threadCache = diskCacheFactory.create<ThreadPage>("thread_page", maxSize = 50, expiration = 24.hours)

    override suspend fun fetchThread(
        tid: ThreadId,
        authorId: UserId?,
        page: Int,
        reverse: Boolean,
    ): YamiboResult<ThreadPage> {
        yamiboClient.setCookie(cookieStore.load() ?: "")
        val result = yamiboClient.fetchThreadById(tid, authorId, reverse, page)

        if (result is YamiboResult.Success) {
            if (!reverse) {
                val key = ThreadRepository.ThreadCacheKey(tid.value, page, authorId?.value).toCacheKey()
                threadCache.set(key, result.value)
            }
        }
        return result
    }

    override suspend fun fetchFindPost(
        tid: ThreadId,
        postId: PostId,
        authorId: UserId?,
    ): YamiboResult<ThreadPage> {
        yamiboClient.setCookie(cookieStore.load() ?: "")
        return yamiboClient.fetchFindPost(threadId = tid, authorId = authorId, postId = postId)
    }

    override suspend fun addFavorite(tid: ThreadId, formHash: FormHash): YamiboResult<AddFavoriteResult> {
        yamiboClient.setCookie(cookieStore.load() ?: "")
        return yamiboClient.fetchAddFavorite(tid, formHash)
    }

    override suspend fun votePoll(fId: ForumId, tId: ThreadId, pollOptionIds: List<PollOptionId>, formHash: FormHash): YamiboResult<String> {
        yamiboClient.setCookie(cookieStore.load() ?: "")
        return yamiboClient.votePoll(fId, tId, pollOptionIds, formHash)
    }

    override suspend fun fetchRatePopoutPage(tId: ThreadId, pId: PostId): YamiboResult<RatePopoutPage> {
        yamiboClient.setCookie(cookieStore.load() ?: "")
        return yamiboClient.fetchRatePopoutPage(tId, pId)
    }

    override suspend fun fetchRateResults(tId: ThreadId, pId: PostId): YamiboResult<RateResultPopoutPage> {
        yamiboClient.setCookie(cookieStore.load() ?: "")
        return yamiboClient.fetchRateResultPopoutPage(tId, pId)
    }

    override suspend fun fetchVoters(tId: ThreadId, pollOptionId: PollOptionId?, page: Int): YamiboResult<VotersPopoutScreen> {
        yamiboClient.setCookie(cookieStore.load() ?: "")
        return yamiboClient.fetchViewVoters(tId, pollOptionId, page)
    }

    override suspend fun ratePost(tId: ThreadId, pId: PostId, score: Int, reason: String, formHash: FormHash, noticeAuthor: Boolean): YamiboResult<String> {
        yamiboClient.setCookie(cookieStore.load() ?: "")
        return yamiboClient.fetchRatePost(tId, pId, score, reason, formHash, noticeAuthor)
    }

    override suspend fun commentPost(tId: ThreadId, pId: PostId, message: String, formHash: FormHash): YamiboResult<String> {
        yamiboClient.setCookie(cookieStore.load() ?: "")
        return yamiboClient.fetchCommentPost(tId, pId, message, formHash)
    }

    override fun getCachedThread(tid: ThreadId, authorId: UserId?, page: Int): ThreadPage? {
        val key = ThreadRepository.ThreadCacheKey(tid.value, page, authorId?.value).toCacheKey()
        return threadCache.get(key)
    }

    override fun setCachedThread(tid: ThreadId, authorId: UserId?, page: Int, threadPage: ThreadPage) {
        val key = ThreadRepository.ThreadCacheKey(tid.value, page, authorId?.value).toCacheKey()
        threadCache.set(key, threadPage)
    }

    override fun clearCachedThread(tid: ThreadId) {
        threadCache.removeByPrefix(ThreadRepository.ThreadCacheKey.keyPrefix(tid.value))
    }
}

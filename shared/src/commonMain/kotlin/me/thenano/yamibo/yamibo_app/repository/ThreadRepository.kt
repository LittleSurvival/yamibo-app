package me.thenano.yamibo.yamibo_app.repository

import io.github.littlesurvival.core.YamiboResult
import io.github.littlesurvival.dto.page.RatePopoutPage
import io.github.littlesurvival.dto.page.RateResultPopoutPage
import io.github.littlesurvival.dto.page.AddFavoriteResult
import io.github.littlesurvival.dto.page.ThreadPage
import io.github.littlesurvival.dto.page.VotersPopoutScreen
import io.github.littlesurvival.dto.value.FormHash
import io.github.littlesurvival.dto.value.ForumId
import io.github.littlesurvival.dto.value.PollOptionId
import io.github.littlesurvival.dto.value.PostId
import io.github.littlesurvival.dto.value.ThreadId
import io.github.littlesurvival.dto.value.UserId

interface ThreadRepository {
    data class ThreadCacheKey(val tid: Int, val page: Int, val authorId: Int? = null) {
        fun toCacheKey(): String = "${tid}_${page}_${authorId ?: "all"}"

        companion object {
            fun keyPrefix(tid: Int): String = "${tid}_"
        }
    }

    suspend fun fetchThread(
        tid: ThreadId,
        authorId: UserId? = null,
        page: Int = 1,
        reverse: Boolean = false,
    ): YamiboResult<ThreadPage>

    /** Locate a specific post in the full-view thread page */
    suspend fun fetchFindPost(
        tid: ThreadId,
        postId: PostId,
        authorId: UserId? = null,
    ): YamiboResult<ThreadPage>

    suspend fun addFavorite(tid: ThreadId, formHash: FormHash): YamiboResult<AddFavoriteResult>
    
    suspend fun votePoll(fId: ForumId, tId: ThreadId, pollOptionIds: List<PollOptionId>, formHash: FormHash): YamiboResult<String>
    suspend fun fetchRatePopoutPage(tId: ThreadId, pId: PostId): YamiboResult<RatePopoutPage>
    suspend fun fetchRateResults(tId: ThreadId, pId: PostId): YamiboResult<RateResultPopoutPage>
    suspend fun fetchVoters(tId: ThreadId, pollOptionId: PollOptionId? = null, page: Int = 1): YamiboResult<VotersPopoutScreen>
    suspend fun ratePost(tId: ThreadId, pId: PostId, score: Int, reason: String, formHash: FormHash, noticeAuthor: Boolean = false): YamiboResult<String>
    suspend fun commentPost(tId: ThreadId, pId: PostId, message: String, formHash: FormHash): YamiboResult<String>

    fun getCachedThread(tid: ThreadId, authorId: UserId? = null, page: Int = 1): ThreadPage?
    fun setCachedThread(tid: ThreadId, authorId: UserId? = null, page: Int, threadPage: ThreadPage)
    fun clearCachedThread(tid: ThreadId)
}

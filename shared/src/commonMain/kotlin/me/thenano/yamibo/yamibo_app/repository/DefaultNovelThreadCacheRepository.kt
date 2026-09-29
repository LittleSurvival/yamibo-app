package me.thenano.yamibo.yamibo_app.repository

import io.github.littlesurvival.dto.page.Post
import io.github.littlesurvival.dto.page.ThreadPage
import io.github.littlesurvival.dto.value.PostId
import io.github.littlesurvival.dto.value.ThreadId
import kotlin.time.Duration.Companion.hours

import me.thenano.yamibo.yamibo_app.core.cache.DiskCacheFactory

class DefaultNovelThreadCacheRepository(
    diskCacheFactory: DiskCacheFactory
) : NovelPrePostCommentsCacheRepository {

    private val fullPageCache = diskCacheFactory.create<ThreadPage>("novel_full_page", maxSize = 10, expiration = 24.hours)
    private val commentCache = diskCacheFactory.create<List<Post>>("novel_comments", maxSize = 50, expiration = 24.hours)
    private val commentCompleteFlags = diskCacheFactory.create<Boolean>("novel_comment_complete", maxSize = 50, expiration = 24.hours)

    override fun getCachedFullPage(tid: ThreadId, page: Int): ThreadPage? =
        fullPageCache.get("${tid.value}_$page")

    override fun setCachedFullPage(tid: ThreadId, page: Int, threadPage: ThreadPage) {
        fullPageCache.set("${tid.value}_$page", threadPage)
    }

    override fun getCachedComments(tid: ThreadId, postId: PostId): List<Post>? =
        commentCache.get("${tid.value}_${postId.value}")

    override fun setCachedComments(tid: ThreadId, postId: PostId, comments: List<Post>) {
        commentCache.set("${tid.value}_${postId.value}", comments)
    }

    override fun isCommentComplete(tid: ThreadId, postId: PostId): Boolean =
        commentCompleteFlags.get("${tid.value}_${postId.value}") ?: false

    override fun setCommentComplete(tid: ThreadId, postId: PostId, complete: Boolean) {
        commentCompleteFlags.set("${tid.value}_${postId.value}", complete)
    }

    override fun clearCache(tid: ThreadId) {
        fullPageCache.clear()
        commentCache.clear()
        commentCompleteFlags.clear()
    }
}

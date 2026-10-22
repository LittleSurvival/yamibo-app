package me.thenano.yamibo.yamibo_app.repository

import io.github.littlesurvival.core.YamiboResult
import io.github.littlesurvival.dto.page.ThreadPage
import io.github.littlesurvival.dto.value.PostId
import io.github.littlesurvival.dto.value.ThreadId
import io.github.littlesurvival.dto.value.UserId

/** A parser's successful empty snapshot is not evidence of an empty discussion. */
fun ThreadPage.isValidThreadPage(
    tid: ThreadId,
    target: PostId? = null,
    page: Int? = null,
    authorId: UserId? = null,
): Boolean {
    val nav = pageNav
    val currentPage = nav?.currentPage ?: 1
    val totalPages = nav?.totalPages ?: currentPage
    return thread.tid == tid && posts.isNotEmpty() && posts.all { it.pid.value > 0 } &&
        (target == null || posts.any { it.pid == target }) &&
        (authorId == null || posts.all { it.author.uid == authorId }) &&
        currentPage > 0 && totalPages >= currentPage &&
        (page == null || currentPage == page)
}

fun YamiboResult<ThreadPage>.validatedThreadPage(
    tid: ThreadId,
    target: PostId? = null,
    page: Int? = null,
    authorId: UserId? = null,
): YamiboResult<ThreadPage> = when {
    this !is YamiboResult.Success -> this
    value.isValidThreadPage(tid, target, page, authorId) -> this
    else -> YamiboResult.Failure("[THREAD_INVALID_CONTENT] 帖子內容不完整或版本不符，請重新載入")
}

/** findpost uses full-thread pagination; fromuid is not an author filter. */
suspend fun ThreadRepository.fetchReaderStartPage(
    tid: ThreadId,
    target: PostId,
    authorId: UserId?,
    initialPage: Int,
): YamiboResult<ThreadPage> = if (authorId != null) {
    // Catalog/history page numbers belong to the author-only view. Reusing a
    // full-thread redirect here poisons that cache and can request a nonexistent page.
    fetchThread(tid, authorId, initialPage)
        .validatedThreadPage(tid, page = initialPage, authorId = authorId)
} else {
    fetchFindPost(tid, target).validatedThreadPage(tid, target)
}

package me.thenano.yamibo.yamibo_app.repository

import io.github.littlesurvival.core.YamiboResult
import io.github.littlesurvival.dto.page.ThreadPage
import io.github.littlesurvival.dto.value.PostId
import io.github.littlesurvival.dto.value.ThreadId

/** A parser's successful empty snapshot is not evidence of an empty discussion. */
fun ThreadPage.isValidThreadPage(tid: ThreadId, target: PostId? = null, page: Int? = null): Boolean {
    val nav = pageNav
    return thread.tid == tid && posts.isNotEmpty() && posts.all { it.pid.value > 0 } &&
        (target == null || posts.any { it.pid == target }) &&
        (nav == null || nav.currentPage > 0 && nav.totalPages >= nav.currentPage) &&
        (page == null || (nav?.currentPage ?: 1) == page)
}

fun YamiboResult<ThreadPage>.validatedThreadPage(
    tid: ThreadId,
    target: PostId? = null,
    page: Int? = null,
): YamiboResult<ThreadPage> = when {
    this !is YamiboResult.Success -> this
    value.isValidThreadPage(tid, target, page) -> this
    else -> YamiboResult.Failure("[THREAD_INVALID_CONTENT] 帖子內容不完整或版本不符，請重新載入")
}

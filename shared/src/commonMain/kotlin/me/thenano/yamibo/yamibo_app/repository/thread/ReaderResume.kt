package me.thenano.yamibo.yamibo_app.repository.thread

import io.github.littlesurvival.core.YamiboResult
import io.github.littlesurvival.dto.page.ThreadPage
import io.github.littlesurvival.dto.value.PostId
import io.github.littlesurvival.dto.value.ThreadId
import io.github.littlesurvival.dto.value.UserId
import me.thenano.yamibo.yamibo_app.repository.DownloadRepository
import me.thenano.yamibo.yamibo_app.repository.ReadHistoryRepository
import me.thenano.yamibo.yamibo_app.repository.ReadHistoryRepository.ThreadEntryType
import me.thenano.yamibo.yamibo_app.repository.ReadHistoryRepository.ThreadReadingHistory
import me.thenano.yamibo.yamibo_app.repository.ThreadRepository

/** A remote or downloaded snapshot can satisfy the first render without a second page fetch. */
data class TextReaderStart(
    val history: ThreadReadingHistory?,
    val snapshot: ThreadPage? = null,
    val preserveUntilMoved: Boolean = false,
)

suspend fun ReadHistoryRepository.textReaderStart(
    tid: ThreadId,
    type: ThreadEntryType,
    author: UserId?,
    threads: ThreadRepository,
    downloads: DownloadRepository,
): TextReaderStart {
    val own = getPosition(tid, type, author)
    val recent = getLatestTextPosition(tid)
    val image = getLatestImagePosition(tid)?.takeIf { it.anchor != null }
    val fromImage = image != null && image.lastVisitTime > (recent?.lastVisitTime ?: 0L)
    val imageAnchor = image?.anchor.takeIf { fromImage }
    if (recent == null && imageAnchor == null) return TextReaderStart(own)
    if (!fromImage && recent == own) return TextReaderStart(own)
    if ((image?.lastVisitTime.takeIf { fromImage } ?: recent?.lastVisitTime ?: 0L) <= (own?.lastVisitTime ?: 0L)) {
        return TextReaderStart(own)
    }

    val pid = imageAnchor?.floorPid ?: recent?.postId ?: return TextReaderStart(own)
    val sourceScope = if (fromImage) imageAnchor?.scopeAuthor else recent?.authorId
    val floorAuthor = if (fromImage) imageAnchor?.scopeAuthor else recent?.floorAuthor ?: sourceScope
    if (author != null && floorAuthor != null && author != floorAuthor) return TextReaderStart(own, preserveUntilMoved = true)
    val pageHint = if (author == sourceScope) imageAnchor?.forumPage ?: recent?.page ?: 1
        else own?.takeIf { it.postId == pid }?.page ?: 1
    val located = threads.locateReaderFloor(tid, pid, author, pageHint, downloads)
        ?: return TextReaderStart(own, preserveUntilMoved = true)
    val post = located.posts.first { it.pid == pid }
    val page = located.pageNav?.currentPage ?: 1
    val history = if (fromImage) {
        ThreadReadingHistory(
            threadType = type, threadName = located.thread.title, threadId = tid, threadCover = own?.threadCover,
            lastUpdatedTime = own?.lastUpdatedTime, forumName = located.thread.forum.name, forumId = located.thread.forum.fid,
            authorId = author, page = page, postId = pid, postTitle = post.title, anchorPostId = pid.value.toLong(),
            lastVisitTime = checkNotNull(image).lastVisitTime, floorAuthor = post.author.uid,
            imageSource = imageAnchor?.sourceId, imageOccurrence = imageAnchor?.occurrence,
        )
    } else {
        checkNotNull(recent).copy(threadType = type, authorId = author, page = page,
            firstVisibleItemIndex = null, firstVisibleItemOffset = null)
    }
    return TextReaderStart(history, located, preserveUntilMoved = true)
}

private suspend fun ThreadRepository.locateReaderFloor(
    tid: ThreadId, pid: PostId, author: UserId?, hint: Int, downloads: DownloadRepository,
): ThreadPage? {
    val result = fetchReaderStartPage(tid, pid, author, hint, downloads)
    val first = (result as? YamiboResult.Success)?.value ?: return null
    if (first.posts.any { it.pid == pid }) return first
    if (author == null) return null
    // findpost returns all-floor pagination. Search only this author's pages when no verified map exists.
    for (page in 1..(first.pageNav?.totalPages ?: 1)) {
        if (page == (first.pageNav?.currentPage ?: 1)) continue
        val local = downloads.getDownloadedReaderPage(tid, author, page)?.page ?: getCachedThread(tid, author, page)
        val snapshot = local ?: (fetchThread(tid, author, page) as? YamiboResult.Success)?.value ?: return null
        if (snapshot.isValidThreadPage(tid, pid, page, author)) return snapshot
    }
    return null
}

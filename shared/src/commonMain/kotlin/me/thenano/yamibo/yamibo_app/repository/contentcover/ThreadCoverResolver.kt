package me.thenano.yamibo.yamibo_app.repository.contentcover

import io.github.littlesurvival.core.YamiboResult
import io.github.littlesurvival.dto.page.Post
import io.github.littlesurvival.dto.page.ThreadPage
import io.github.littlesurvival.dto.value.ThreadId
import io.github.littlesurvival.dto.value.UserId
import me.thenano.yamibo.yamibo_app.repository.ThreadRepository

class ThreadCoverResolver(
    private val threadRepository: ThreadRepository,
) {
    suspend fun resolve(tid: ThreadId): String? {
        val firstPage = loadPage(tid, authorId = null, page = 1) ?: return null
        val owner = firstPage.posts.firstOrNull { it.floor == 1 }?.author ?: return null
        findThreadCoverCandidate(firstPage, owner.uid, owner.name)?.let { return it }

        val totalPages = firstPage.pageNav?.totalPages ?: 1
        for (page in 2..totalPages) {
            val cached = threadRepository.getCachedThread(tid, null, page) ?: continue
            findThreadCoverCandidate(cached, owner.uid, owner.name)?.let { return it }
        }

        val validUid = owner.uid.takeIf { it.value > 0 }
        var page = 1
        var scanTotal = totalPages
        while (page <= scanTotal) {
            val result = loadPage(tid, validUid, page) ?: return null
            scanTotal = result.pageNav?.totalPages ?: scanTotal
            findThreadCoverCandidate(result, owner.uid, owner.name)?.let { return it }
            page += 1
        }
        return null
    }

    private suspend fun loadPage(tid: ThreadId, authorId: UserId?, page: Int): ThreadPage? {
        threadRepository.getCachedThread(tid, authorId, page)?.let { return it }
        return when (val result = threadRepository.fetchThread(tid, authorId, page)) {
            is YamiboResult.Success -> result.value
            else -> null
        }
    }

}

fun findThreadCoverCandidate(page: ThreadPage): String? {
    val owner = page.posts.firstOrNull { it.floor == 1 }?.author ?: return null
    return findThreadCoverCandidate(page, owner.uid, owner.name)
}

private fun findThreadCoverCandidate(page: ThreadPage, ownerId: UserId, ownerName: String): String? =
    page.posts
        .asSequence()
        .sortedBy(Post::floor)
        .filter { post ->
            if (ownerId.value > 0) post.author.uid == ownerId
            else ownerName.isNotBlank() && post.author.name == ownerName
        }
        .flatMap { it.images.asSequence() }.firstNotNullOfOrNull { normalizeCoverUrl(it.url) }

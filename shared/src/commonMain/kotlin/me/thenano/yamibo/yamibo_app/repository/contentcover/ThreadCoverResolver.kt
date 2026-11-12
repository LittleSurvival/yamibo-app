package me.thenano.yamibo.yamibo_app.repository.contentcover

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import io.github.littlesurvival.dto.page.Post
import io.github.littlesurvival.dto.page.ThreadPage
import io.github.littlesurvival.dto.value.ThreadId
import io.github.littlesurvival.dto.value.UserId
import me.thenano.yamibo.yamibo_app.repository.ThreadRepository

class ThreadCoverResolver(
    private val threadRepository: ThreadRepository,
) {
    suspend fun resolve(tid: ThreadId): String? = withContext(Dispatchers.IO) {
        // Cover lookup must not fetch extra thread pages just to decorate the reader.
        val firstPage = threadRepository.getCachedThread(tid, authorId = null, page = 1) ?: return@withContext null
        val owner = firstPage.posts.firstOrNull { it.floor == 1 }?.author ?: return@withContext null
        findThreadCoverCandidate(firstPage, owner.uid, owner.name)?.let { return@withContext it }

        val totalPages = firstPage.pageNav?.totalPages ?: 1
        for (page in 2..totalPages) {
            val cached = threadRepository.getCachedThread(tid, null, page) ?: continue
            findThreadCoverCandidate(cached, owner.uid, owner.name)?.let { return@withContext it }
        }

        val validUid = owner.uid.takeIf { it.value > 0 }
        var page = 1
        var scanTotal = totalPages
        while (page <= scanTotal) {
            val result = threadRepository.getCachedThread(tid, validUid, page) ?: return@withContext null
            scanTotal = result.pageNav?.totalPages ?: scanTotal
            findThreadCoverCandidate(result, owner.uid, owner.name)?.let { return@withContext it }
            page += 1
        }
        null
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

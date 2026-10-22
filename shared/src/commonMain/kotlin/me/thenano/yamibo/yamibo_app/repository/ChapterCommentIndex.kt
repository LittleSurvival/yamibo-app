package me.thenano.yamibo.yamibo_app.repository

import com.fleeksoft.ksoup.Ksoup
import io.github.littlesurvival.YamiboRoute
import io.github.littlesurvival.dto.page.Post
import io.github.littlesurvival.dto.page.ThreadPage
import io.github.littlesurvival.dto.value.PostId
import io.github.littlesurvival.dto.value.ThreadId
import io.github.littlesurvival.dto.value.UserId
import io.ktor.http.Url

/** Incremental, screen-owned index. Each explicit load adds one full-thread page. */
class ChapterCommentIndex(private val tid: ThreadId, private val authorId: UserId) {
    private data class Entry(val post: Post, val references: Set<PostId>, val ambiguous: Boolean)
    private val pages = mutableMapOf<Int, List<Entry>>()
    var totalPages: Int = 1
        private set

    fun addPage(page: ThreadPage): Boolean {
        if (!page.isValidThreadPage(tid)) return false
        val number = page.pageNav?.currentPage ?: 1
        totalPages = maxOf(totalPages, number, page.pageNav?.totalPages ?: number)
        pages[number] = page.posts.map { post ->
            val references = explicitChapterReferences(post.contentHtml, tid)
            Entry(post, references.posts, references.ambiguous)
        }
        return true
    }

    fun clear() {
        pages.clear()
        totalPages = 1
    }

    fun nextPage(): Int? = (1..totalPages).firstOrNull { it !in pages }
    val complete: Boolean get() = nextPage() == null

    fun commentsFor(chapter: PostId): List<Post> {
        val entries = pages.entries.sortedBy { it.key }.flatMap { it.value }
            .distinctBy { it.post.pid }.sortedBy { it.post.floor }
        val byId = entries.associateBy { it.post.pid }
        val chronological = mutableMapOf<PostId, PostId?>()
        var previousAuthor: PostId? = null
        for (entry in entries) {
            if (entry.post.author.uid == authorId) previousAuthor = entry.post.pid
            else chronological[entry.post.pid] = previousAuthor
        }

        // Resolve only explicit evidence. An unreferenced intermediary must not turn
        // a guess into a confirmed chapter; cycles/missing/ambiguous targets fall back.
        val memo = mutableMapOf<PostId, PostId?>()
        fun resolve(id: PostId, path: Set<PostId>): PostId? {
            if (id in memo) return memo[id]
            if (path.size >= 32 || id in path) return null
            val entry = byId[id] ?: return null
            if (entry.post.author.uid == authorId) return id
            if (entry.ambiguous || entry.references.isEmpty()) return null
            val targets = entry.references.map { resolve(it, path + id) }
            val result = targets.firstOrNull()?.takeIf { candidate -> targets.all { it == candidate } }
            memo[id] = result
            return result
        }
        return entries.filter { entry ->
            entry.post.author.uid != authorId &&
                (resolve(entry.post.pid, emptySet()) ?: chronological[entry.post.pid]) == chapter
        }.map { it.post }
    }
}

internal data class ChapterReferences(val posts: Set<PostId>, val ambiguous: Boolean)

/** Recognize Discuz reference links only; prose and unrelated content links are not evidence. */
internal fun explicitChapterReferences(html: String, tid: ThreadId): ChapterReferences {
    val document = Ksoup.parseBodyFragment(html)
    val forum = Url(YamiboRoute.Domain.build())
    val posts = mutableSetOf<PostId>()
    var ambiguous = false
    for (link in document.select("a[href]")) {
        val href = link.attr("href").trim()
        val inQuote = link.closest(".quote, blockquote") != null
        val url = runCatching {
            Url(when {
                href.startsWith("//") -> "${forum.protocol.name}:$href"
                href.startsWith("/") -> "${forum.protocol.name}://${forum.host}$href"
                href.startsWith("?") -> "${forum.protocol.name}://${forum.host}/forum.php$href"
                ":" !in href.substringBefore('/') -> "${forum.protocol.name}://${forum.host}/$href"
                else -> href
            })
        }.getOrNull() ?: continue
        if (!inQuote && url.parameters["goto"] != "findpost") continue
        if (url.host != forum.host || url.port != forum.port ||
            url.protocol.name !in setOf("https", "http")) {
            ambiguous = true
            continue
        }
        val targetThread = url.parameters["ptid"] ?: url.parameters["tid"]
        if (targetThread != null && targetThread.toIntOrNull() != tid.value) {
            ambiguous = true
            continue
        }
        val pid = url.parameters["pid"]?.toIntOrNull()
            ?: url.fragment.removePrefix("pid").toIntOrNull()
        if (pid != null && pid > 0) posts += PostId(pid)
    }
    return ChapterReferences(posts, ambiguous)
}

package me.thenano.yamibo.yamibo_app.repository.thread

import io.github.littlesurvival.core.YamiboResult
import io.github.littlesurvival.dto.model.*
import io.github.littlesurvival.dto.page.Post
import io.github.littlesurvival.dto.page.ThreadPage
import io.github.littlesurvival.dto.page.ThreadInfo
import io.github.littlesurvival.dto.value.*
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import me.thenano.yamibo.yamibo_app.repository.*
import me.thenano.yamibo.yamibo_app.repository.ReadHistoryRepository.*

class ReaderResumeTest {
    private inline fun <reified T> mock(crossinline handle: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            handle(method.name.substringBefore('-'), args ?: emptyArray())
        } as T
    private val tid = ThreadId(140)
    private val pid = PostId(1402)
    private val author = UserId(7)
    private val source = "https://example.invalid/marked.jpg"
    private val scope = ImageAnchor(pid, readerImageSourceId(source), 1, 1, author)
    private val novel = ThreadReadingHistory(ThreadEntryType.Novel, "title", tid, null, null, null, null,
        author, 1, pid, "", 1402, anchorBlockId = "paragraph", lastVisitTime = 20, floorAuthor = author)
    private val comic = ImageReadingHistory(PostId(1401), tid, 3, 6, lastVisitTime = 30, anchor = scope)
    private val forum = novel.copy(threadType = ThreadEntryType.Normal, authorId = null, page = 2,
        lastVisitTime = 10, anchorBlockId = "old")
    private val page = ThreadPage(
        thread = ThreadInfo(tid, "title", ForumSummary(ForumId(30), "forum", "")),
        posts = listOf(Post(pid = pid, floor = 2, title = "", author = User(author, "user"),
            timeCreate = TimeInfo("", null, 0), contentHtml = "<img>", images = emptyList(),
            tags = Tags(), poll = null)),
    )
    private fun history(own: ThreadReadingHistory?, recent: ThreadReadingHistory?, image: ImageReadingHistory?) =
        mock<ReadHistoryRepository> { name, _ -> when(name) {
            "getPosition" -> own
            "getLatestTextPosition" -> recent
            "getLatestImagePosition" -> image
            else -> error(name)
        } }

    @Test fun sameModeReadsOnlyHistoryBeforeOpeningKnownPage() = runBlocking {
        val start = history(forum, forum, null).textReaderStart(tid, ThreadEntryType.Normal, null,
            mock { name, _ -> error(name) }, mock { name, _ -> error(name) })
        assertEquals(2, start.history?.page)
        assertNull(start.snapshot)
    }

    @Test fun foreignCommentCannotReplaceTheNovelParagraph() = runBlocking {
        val other = forum.copy(postId = PostId(1500), floorAuthor = UserId(8), lastVisitTime = 40)
        val start = history(novel, other, comic).textReaderStart(tid, ThreadEntryType.Novel, author,
            mock { name, _ -> error(name) }, mock { name, _ -> error(name) })
        assertEquals(novel, start.history)
        assertTrue(start.preserveUntilMoved)
    }

    @Test fun newerImageRestoresTheExactOccurrenceWithoutHttpOnLocalHit() = runBlocking {
        val start = history(novel, novel, comic).textReaderStart(tid, ThreadEntryType.Novel, author,
            mock { name, _ -> if (name == "knownPageForPost") 1 else error(name) },
            mock { name, _ -> if (name == "getDownloadedPage") page else error(name) })
        assertEquals(scope.sourceId, start.history?.imageSource)
        assertEquals(1, start.history?.imageOccurrence)
        assertEquals(pid, start.history?.postId)
        assertSame(page, start.snapshot)
    }

    @Test fun authorPageIsLocatedByPidInTheFullViewAndBodyIsReused() = runBlocking {
        var findCalls = 0
        val start = history(forum, novel, null).textReaderStart(tid, ThreadEntryType.Normal, null,
            mock { name, _ -> when(name) {
                "knownPageForPost", "getCachedThread", "setCachedThread" -> null
                "fetchFindPost" -> { findCalls++; YamiboResult.Success(page) }
                else -> error("unexpected second read: " + name)
            } }, mock { name, _ -> if (name == "getDownloadedPage") null else error(name) })
        assertEquals(1, findCalls)
        assertSame(page, start.snapshot)
        assertEquals("paragraph", start.history?.anchorBlockId)
        assertNull(start.history?.authorId)
    }

    @Test fun imageFallbackStaysOnTheSameFloorOrReturnsNoMatch() {
        val images = listOf(ReaderImage(PostId(1401), source, 0), ReaderImage(pid, source, 0), ReaderImage(pid, source, 1))
        assertEquals(2, images.positionOf(scope))
        assertEquals(1, images.positionOf(scope.copy(sourceId = "gone")))
        assertNull(images.positionOf(scope.copy(floorPid = PostId(1403))))
    }
}

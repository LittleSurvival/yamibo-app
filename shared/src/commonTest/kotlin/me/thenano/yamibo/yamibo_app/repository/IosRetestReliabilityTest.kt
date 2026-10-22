package me.thenano.yamibo.yamibo_app.repository

import io.github.littlesurvival.core.YamiboResult
import io.github.littlesurvival.dto.model.*
import io.github.littlesurvival.dto.page.*
import io.github.littlesurvival.dto.value.*
import me.thenano.yamibo.yamibo_app.util.auth.*
import kotlin.test.*

class IosRetestReliabilityTest {
    private val tid = ThreadId(42)
    private val author = UserId(7)
    private fun post(id: Int, owner: Boolean = false, html: String = "") = Post(
        pid = PostId(id), floor = id, title = "post $id",
        author = User(if (owner) author else UserId(9), "user"),
        timeCreate = TimeInfo("2026-10-22", null, 0),
        contentHtml = html, tags = Tags(), poll = null,
    )
    private fun page(number: Int = 1, total: Int = 1, vararg posts: Post) = ThreadPage(
        thread = ThreadInfo(tid, "thread", ForumSummary(ForumId(49), "novel", "forum.php?fid=49")),
        posts = posts.toList(), pageNav = PageNav(currentPage = number, totalPages = total),
    )
    private fun quote(pid: Int, thread: Int = 42) =
        "<div class='quote'><blockquote><a href='forum.php?mod=redirect&amp;goto=findpost&amp;ptid=$thread&amp;pid=$pid'>引用</a></blockquote></div>"
    private fun ids(index: ChapterCommentIndex, chapter: Int) = index.commentsFor(PostId(chapter)).map { it.pid.value }

    @Test fun nativePolicyRemovesOnlyConfiguredPreference() {
        val input = "EeqY_2132_auth=a=b; EeqY_2132_saltkey=s; EeqY_2132_mobile=no; nox_jst_v1=n; other_mobile=keep"
        assertEquals("EeqY_2132_auth=a=b; EeqY_2132_saltkey=s; nox_jst_v1=n; other_mobile=keep", nativeYamiboCookieHeader(input))
        assertContains(input, "EeqY_2132_mobile=no")
        assertEquals("hCkv_2132_auth=a", nativeYamiboCookieHeader("hCkv_2132_mobile=no; hCkv_2132_auth=a", "hCkv_2132_"))
    }
    @Test fun hydrationAndRepeatedNativeImportsHaveSamePolicy() {
        val raw = "EeqY_2132_mobile=no; EeqY_2132_auth=a; nox_jst_v1=stale"
        val hydrated = nativeYamiboCookieHeader(composeYamiboClientCookieHeader(raw, "nox_jst_v1=fresh"))
        assertEquals("EeqY_2132_auth=a; nox_jst_v1=fresh", hydrated)
        assertEquals(hydrated, nativeYamiboCookieHeader(hydrated))
        assertEquals("", nativeYamiboCookieHeader(null))
    }
    @Test fun emptyAndWrongThreadCannotBecomeSuccess() {
        assertFalse(page().isValidThreadPage(tid))
        val valid = page(posts = arrayOf(post(1, true)))
        assertTrue(valid.isValidThreadPage(tid, PostId(1)))
        assertFalse(valid.isValidThreadPage(ThreadId(99)))
        assertFalse(valid.isValidThreadPage(tid, PostId(2)))
        assertIs<YamiboResult.Failure>(YamiboResult.Success(page()).validatedThreadPage(tid))
    }
    @Test fun paginationAndTargetAreValidated() {
        val valid = page(2, 3, post(1, true))
        assertTrue(valid.isValidThreadPage(tid, PostId(1), 2))
        assertFalse(valid.isValidThreadPage(tid, page = 1))
        assertFalse(page(3, 2, post(1)).isValidThreadPage(tid))
        assertTrue(valid.copy(pageNav = null).isValidThreadPage(tid, page = 1))
    }
    @Test fun authenticationAndPermissionErrorsArePreserved() {
        assertSame(YamiboResult.NotLoggedIn, YamiboResult.NotLoggedIn.validatedThreadPage(tid))
        val denied = YamiboResult.NoPermission("denied")
        assertSame(denied, denied.validatedThreadPage(tid))
        val failed = YamiboResult.Failure("network")
        assertSame(failed, failed.validatedThreadPage(tid))
    }
    @Test fun sixPostSampleMovesOnlyExplicitLateReference() {
        val index = ChapterCommentIndex(tid, author)
        index.addPage(page(1, 1, post(1, true), post(2), post(3, true), post(4, html = "討論第一章"), post(5, html = quote(1)), post(6)))
        assertEquals(listOf(2, 5), ids(index, 1))
        assertEquals(listOf(4, 6), ids(index, 3))
        assertTrue(index.complete)
    }
    @Test fun nextAuthorDoesNotCompletePartialScan() {
        val index = ChapterCommentIndex(tid, author)
        index.addPage(page(1, 2, post(1, true), post(2), post(3, true)))
        assertFalse(index.complete)
        assertEquals(2, index.nextPage())
        index.addPage(page(2, 2, post(4, html = quote(1))))
        assertEquals(listOf(2, 4), ids(index, 1))
        assertTrue(index.complete)
    }
    @Test fun sparsePagesCanResumeAndResolveEarlierReferences() {
        val index = ChapterCommentIndex(tid, author)
        index.addPage(page(2, 3, post(3, true), post(4, html = quote(1))))
        assertEquals(listOf(4), ids(index, 3))
        assertEquals(1, index.nextPage())
        index.addPage(page(1, 3, post(1, true), post(2)))
        assertEquals(listOf(2, 4), ids(index, 1))
        assertEquals(3, index.nextPage())
        index.addPage(page(3, 3, post(5)))
        assertTrue(index.complete)
    }
    @Test fun explicitReplyChainsInheritUniqueChapter() {
        val index = ChapterCommentIndex(tid, author)
        index.addPage(page(1, 1, post(1, true), post(2, html = quote(1)), post(3, true), post(4, html = quote(2))))
        assertEquals(listOf(2, 4), ids(index, 1))
    }
    @Test fun ambiguousMissingAndCyclicReferencesFallBack() {
        val index = ChapterCommentIndex(tid, author)
        index.addPage(page(1, 1, post(1, true), post(2), post(3, true),
            post(4, html = quote(1) + quote(3)), post(5, html = quote(999)),
            post(6, html = quote(7)), post(7, html = quote(6)), post(8, html = quote(2))))
        assertEquals(listOf(4, 5, 6, 7, 8), ids(index, 3))
    }
    @Test fun crossThreadAndExternalReferencesCannotHijackOwnership() {
        val index = ChapterCommentIndex(tid, author)
        index.addPage(page(1, 1, post(1, true), post(3, true),
            post(4, html = quote(1, 99)),
            post(5, html = "<blockquote><a href='https://example.org/forum.php?goto=findpost&pid=1'>引用</a></blockquote>")))
        assertEquals(listOf(4, 5), ids(index, 3))
    }
    @Test fun reloadingPageDeduplicatesAndRefreshClearsDerivedIndex() {
        val index = ChapterCommentIndex(tid, author)
        val original = page(1, 1, post(1, true), post(2))
        index.addPage(original)
        index.addPage(original)
        assertEquals(listOf(2), ids(index, 1))
        index.clear()
        assertFalse(index.complete)
        index.addPage(page(1, 1, post(1, true)))
        assertEquals(emptyList(), ids(index, 1))
    }
    @Test fun invalidSnapshotDoesNotAdvanceScan() {
        val index = ChapterCommentIndex(tid, author)
        assertFalse(index.addPage(page()))
        assertEquals(1, index.nextPage())
        assertFalse(index.complete)
    }
}

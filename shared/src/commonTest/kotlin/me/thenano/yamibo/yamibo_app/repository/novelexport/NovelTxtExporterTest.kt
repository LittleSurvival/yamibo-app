package me.thenano.yamibo.yamibo_app.repository.novelexport

import io.github.littlesurvival.dto.model.ForumSummary
import io.github.littlesurvival.dto.model.PageNav
import io.github.littlesurvival.dto.model.Tags
import io.github.littlesurvival.dto.model.TimeInfo
import io.github.littlesurvival.dto.model.User
import io.github.littlesurvival.dto.page.Post
import io.github.littlesurvival.dto.page.ThreadInfo
import io.github.littlesurvival.dto.page.ThreadPage
import io.github.littlesurvival.dto.value.ForumId
import io.github.littlesurvival.dto.value.PostId
import io.github.littlesurvival.dto.value.ThreadId
import io.github.littlesurvival.dto.value.UserId
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NovelTxtExporterTest {

    private val exporter = NovelTxtExporter()

    @Test
    fun htmlToPlainTextKeepsParagraphBreaks() {
        val text = exporter.htmlToPlainText("<p>第一段</p><p>第二段</p>")
        assertEquals("第一段\n\n第二段", text)
    }

    @Test
    fun htmlToPlainTextConvertsBrToNewline() {
        val text = exporter.htmlToPlainText("第一行<br>第二行")
        assertEquals("第一行\n第二行", text)
    }

    @Test
    fun htmlToPlainTextStripsTagsAndKeepsText() {
        val text = exporter.htmlToPlainText("<div><b>粗</b>体<i>斜</i></div>")
        assertEquals("粗体斜", text)
    }

    @Test
    fun buildNovelTxtMergesAllPagesByChapter() = runBlocking {
        val page1 = threadPage(
            title = "测试小说",
            posts = listOf(
                post(1, 1, "第一章 开始", "<p>第一页内容</p>"),
                post(2, 2, "第二章 继续", "<p>第一页第二章</p>"),
            ),
            totalPages = 2,
        )
        val page2 = threadPage(
            title = "测试小说",
            posts = listOf(
                post(3, 3, "第三章 转折", "<p>第二页内容</p>"),
                post(4, 4, "第四章 结局", "<p>第二页第四章</p>"),
            ),
            totalPages = 2,
        )

        val progress = mutableListOf<Pair<Int, Int>>()
        val result = assertIs<NovelTxtExportResult.Success>(
            exporter.buildNovelTxt(
                firstPage = page1,
                loadPage = { page -> if (page == 2) page2 else null },
                onProgress = { fetched, total -> progress += fetched to total },
            ),
        )

        assertEquals("测试小说", result.title)
        val content = result.content
        assertTrue(content.startsWith("测试小说"))
        val chapterIndexes = listOf("第一章 开始", "第二章 继续", "第三章 转折", "第四章 结局").map { content.indexOf(it) }
        assertEquals(chapterIndexes.sorted(), chapterIndexes, "chapters should appear in display order")
        assertTrue(content.indexOf("第一页内容") < content.indexOf("第二页内容"))
        assertEquals(listOf(1 to 2, 2 to 2), progress)
    }

    @Test
    fun buildNovelTxtFailsWhenPageFetchFails() = runBlocking {
        val page1 = threadPage(
            title = "测试小说",
            posts = listOf(post(1, 1, "第一章", "<p>内容</p>")),
            totalPages = 3,
        )

        val result = exporter.buildNovelTxt(
            firstPage = page1,
            loadPage = { page -> if (page == 2) null else threadPage("测试小说", emptyList()) },
        )

        val failure = assertIs<NovelTxtExportResult.Failure>(result)
        assertTrue("2" in failure.message)
    }

    @Test
    fun postWithoutTitleFallsBackToFloorNumber() {
        val txt = exporter.buildTxt(
            title = "无题小说",
            posts = listOf(post(1, 7, "", "<p>正文</p>")),
        )
        assertTrue("第 7 楼" in txt)
        assertTrue("正文" in txt)
    }

    @Test
    fun sanitizeFileNameReplacesInvalidChars() {
        assertEquals("a_b_c.txt", exporter.sanitizeFileName("a/b\\c"))
        assertEquals("novel.txt", exporter.sanitizeFileName("   "))
    }

    private fun threadPage(
        title: String,
        posts: List<Post>,
        totalPages: Int = 1,
    ): ThreadPage {
        val forum = ForumSummary(fid = ForumId(49), name = "文学区", url = "forumdisplay")
        return ThreadPage(
            thread = ThreadInfo(tid = ThreadId(1), title = title, forum = forum),
            posts = posts,
            pageNav = PageNav(currentPage = 1, totalPages = totalPages),
        )
    }

    private fun post(pid: Int, floor: Int, title: String, contentHtml: String): Post = Post(
        pid = PostId(pid),
        floor = floor,
        title = title,
        author = User(uid = UserId(1), name = "author"),
        timeCreate = TimeInfo(text = "2026-1-1 00:00", epoch = 0),
        contentHtml = contentHtml,
        tags = Tags(emptyList()),
        poll = null,
    )
}

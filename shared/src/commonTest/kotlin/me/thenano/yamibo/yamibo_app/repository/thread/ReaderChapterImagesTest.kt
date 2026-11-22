package me.thenano.yamibo.yamibo_app.repository.thread

import io.github.littlesurvival.dto.model.ForumSummary
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
import kotlin.test.Test
import kotlin.test.assertEquals

class ReaderChapterImagesTest {
    @Test
    fun allAuthorFloorsKeepTheirOwnPidAndRepeatedImageOccurrence() {
        val cover = "https://img.example/cover.jpg"
        val target = "https://img.example/target.jpg"
        fun post(pid: Int, uid: Int, vararg urls: String) = Post(
            pid = PostId(pid), floor = pid - 1400, title = "", author = User(UserId(uid), "reader"),
            timeCreate = TimeInfo("2026-01-01", null, 0), contentHtml = urls.joinToString("") { "<img file='$it'>" },
            images = emptyList(), tags = Tags(), poll = null,
        )
        val page = ThreadPage(
            thread = ThreadInfo(ThreadId(140), "test", ForumSummary(ForumId(30), "manga", "")),
            posts = listOf(post(1401, 7, cover), post(1500, 8, cover),
                post(1402, 7, cover, cover, target), post(1403, 7, target)),
        )
        val expected = listOf(ReaderImage(PostId(1401), cover, 0), ReaderImage(PostId(1402), cover, 0),
            ReaderImage(PostId(1402), cover, 1), ReaderImage(PostId(1402), target, 0),
            ReaderImage(PostId(1403), target, 0))
        assertEquals(expected, page.readerImages(null))
        assertEquals(expected, page.readerImages(UserId(7)))
        assertEquals(listOf(ReaderImage(PostId(1500), cover, 0)), page.readerImages(UserId(8)))
    }
}

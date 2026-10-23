package me.thenano.yamibo.yamibo_app.qa

import io.github.littlesurvival.dto.model.ThreadSummary
import io.github.littlesurvival.dto.model.User
import io.github.littlesurvival.dto.value.*
import me.thenano.yamibo.yamibo_app.IMainScreen
import me.thenano.yamibo.yamibo_app.navigation.RestorableNavigatable
import me.thenano.yamibo.yamibo_app.profile.download.IDownloadQueueScreen
import me.thenano.yamibo.yamibo_app.repository.ReadHistoryRepository
import me.thenano.yamibo.yamibo_app.thread.reader.IImageReaderScreen
import me.thenano.yamibo.yamibo_app.thread.reader.IThreadReaderScreen

fun downloadedReaderQaStart(mode: String?): RestorableNavigatable = when (mode) {
    "novel" -> IThreadReaderScreen(ThreadId(42), "QA NOVEL", ReadHistoryRepository.ThreadEntryType.Novel, UserId(7), targetPid = PostId(42))
    "thread" -> IThreadReaderScreen(ThreadId(42), "QA NOVEL", targetPid = PostId(42))
    "management" -> IDownloadQueueScreen()
    "manga", "tag", "rss" -> {
        val threads = listOf(ThreadSummary(ThreadId(43), "QA MANGA", ForumId(1), hasPoll = false,
            url = "forum.php?tid=43", author = User(UserId(7), "QA Author")))
        IImageReaderScreen(ThreadId(43), null, ForumId(1), "QA MANGA", authorId = UserId(7),
            tagId = if (mode == "tag") TagId(8) else null,
            tagName = if (mode == "tag") "QA TAG" else null,
            tagPage = if (mode == "tag") 1 else null,
            tagTotalPages = if (mode == "tag") 1 else null,
            tagThreads = if (mode == "tag") threads else null,
            rssSubscriptionId = if (mode == "rss") 1 else null,
            rssTitle = if (mode == "rss") "QA RSS" else null,
            rssQuery = if (mode == "rss") "qa" else null,
            rssPage = if (mode == "rss") 1 else null,
            rssTotalPages = if (mode == "rss") 1 else null,
            rssThreads = if (mode == "rss") threads else null)
    }
    else -> IMainScreen()
}

package me.thenano.yamibo.yamibo_app.forum

import androidx.compose.runtime.Composable
import io.github.littlesurvival.dto.model.ThreadSummary
import io.github.littlesurvival.dto.value.ForumId
import me.thenano.yamibo.yamibo_app.LocalAppSettingsRepository
import me.thenano.yamibo.yamibo_app.components.controls.YamiboSingleSelectDialog
import me.thenano.yamibo.yamibo_app.i18n.i18n
import me.thenano.yamibo.yamibo_app.navigation.Navigatable
import me.thenano.yamibo.yamibo_app.repository.settings.ForumReadingMode
import me.thenano.yamibo.yamibo_app.thread.detail.novel.INovelThreadDetailScreen
import me.thenano.yamibo.yamibo_app.thread.reader.IImageReaderScreen
import me.thenano.yamibo.yamibo_app.thread.reader.IThreadReaderScreen
import me.thenano.yamibo.yamibo_app.util.state

internal fun ForumReadingMode.label() = when (this) {
    ForumReadingMode.SmartComic -> i18n("智能漫畫")
    ForumReadingMode.Novel -> i18n("小說閱讀")
    ForumReadingMode.Forum -> i18n("論壇模式")
}

@Composable
internal fun ForumReadingDialog(fid: ForumId, onDismiss: () -> Unit) {
    val setting = LocalAppSettingsRepository.current.forumReadingMode(fid)
    YamiboSingleSelectDialog(
        title = i18n("板塊閱讀模式"), options = ForumReadingMode.entries,
        selected = setting.state(), onDismiss = onDismiss, onSelect = { setting.setValue(it) },
        label = { it.label() }, dismissOnSelect = true, footer = null,
    )
}

internal fun forumThreadDestination(
    mode: ForumReadingMode, fid: ForumId, thread: ThreadSummary, neighbors: List<ThreadSummary>,
): Navigatable = when (mode) {
    ForumReadingMode.Novel -> INovelThreadDetailScreen(thread.tid, thread.title, thread.author?.uid)
    ForumReadingMode.Forum -> IThreadReaderScreen(tid = thread.tid, title = thread.title)
    ForumReadingMode.SmartComic -> IImageReaderScreen(
        tid = thread.tid, postId = null, fid = fid, threadTitle = thread.title, authorId = thread.author?.uid,
        loadHistory = true, smartSeed = thread, smartNeighbors = neighbors,
    )
}

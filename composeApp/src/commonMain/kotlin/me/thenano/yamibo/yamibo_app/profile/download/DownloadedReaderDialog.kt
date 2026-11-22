package me.thenano.yamibo.yamibo_app.profile.download

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import io.github.littlesurvival.dto.model.ThreadSummary
import io.github.littlesurvival.dto.page.ThreadPage
import io.github.littlesurvival.dto.value.*
import kotlinx.coroutines.CancellationException
import me.thenano.yamibo.yamibo_app.*
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme
import me.thenano.yamibo.yamibo_app.i18n.i18n
import me.thenano.yamibo.yamibo_app.navigation.LocalNavigator
import me.thenano.yamibo.yamibo_app.repository.ReadHistoryRepository.ThreadEntryType
import me.thenano.yamibo.yamibo_app.repository.download.*
import me.thenano.yamibo.yamibo_app.repository.forum.SmartComicCatalog
import me.thenano.yamibo.yamibo_app.thread.reader.IImageReaderScreen
import me.thenano.yamibo.yamibo_app.thread.reader.IThreadReaderScreen

@Composable
internal fun DownloadedReaderDialog(item: DownloadedContentItem, onDismiss: () -> Unit) {
    val downloads = LocalDownloadRepository.current
    val catalog = LocalForumRepository.current.smartComics
    val auth = LocalAuthRepository.current
    val navigator = LocalNavigator.current
    val colors = YamiboTheme.colors
    val key = item.key
    val pageKey = when (key) {
        is ThreadPageDownloadKey -> key
        is TagMangaChapterDownloadKey -> ThreadPageDownloadKey(key.tid, 1, key.authorId)
        is RssMangaChapterDownloadKey -> ThreadPageDownloadKey(key.tid, 1, key.authorId)
    }
    val tid = ThreadId(pageKey.tid)
    var page by remember(key) { mutableStateOf<ThreadPage?>(null) }
    var work by remember(key) { mutableStateOf<SmartComicCatalog.SavedWork?>(null) }
    var imageOnly by remember(key) { mutableStateOf(false) }
    var ready by remember(key) { mutableStateOf(false) }
    var error by remember(key) { mutableStateOf<String?>(null) }
    LaunchedEffect(key) {
        try {
            page = downloads.getDownloadedPage(pageKey)
            val fid = page?.thread?.forum?.fid
            if (fid != null) work = auth.currentUser()?.uid?.let { catalog.savedWork(it, fid, tid) }
            if (page == null) imageOnly = when (key) {
                is TagMangaChapterDownloadKey -> !downloads.getTagMangaChapterImages(key).isNullOrEmpty()
                is RssMangaChapterDownloadKey -> !downloads.getRssMangaChapterImages(key).isNullOrEmpty()
                else -> false
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            error = e.message
        } finally {
            ready = true
        }
    }
    val chapter = work?.members?.firstOrNull { it.tid == tid }
    val author = chapter?.author ?: page?.posts?.firstOrNull { it.floor == 1 || it.author.uid.value == pageKey.authorId }?.author
    val seed = chapter ?: page?.let { ThreadSummary(tid, it.thread.title, it.thread.forum.fid, hasPoll = false, url = "", author = author) }
    fun openComic(target: ThreadSummary) {
        onDismiss()
        navigator.navigate(IImageReaderScreen(target.tid, null, target.fid ?: page?.thread?.forum?.fid,
            target.title, authorId = target.author?.uid, smartSeed = target, loadHistory = true,
            offlineOnly = true, offlinePage = pageKey.takeIf { target.tid == tid }))
    }
    val actions = ButtonDefaults.textButtonColors(contentColor = colors.brownPrimary, disabledContentColor = colors.textDark.copy(alpha = 0.45f))
    AlertDialog(onDismissRequest = onDismiss, containerColor = colors.creamSurface, titleContentColor = colors.textStrong,
        textContentColor = colors.textDark, title = { Text(i18n("開啟下載內容")) }, text = {
            Column(Modifier.fillMaxWidth()) {
                val current = page
                Text(current?.thread?.title ?: item.title)
                if (!ready) CircularProgressIndicator(color = colors.brownPrimary)
                if (current != null) {
                    TextButton({ onDismiss(); navigator.navigate(IThreadReaderScreen(tid, current.thread.title,
                        threadType = ThreadEntryType.Normal, authorId = pageKey.authorId?.let(::UserId), initialPage = pageKey.page,
                        offlineOnly = true, offlinePage = pageKey)) }, colors = actions) { Text(i18n("論壇模式")) }
                    TextButton({ onDismiss(); navigator.navigate(IThreadReaderScreen(tid, current.thread.title,
                        threadType = ThreadEntryType.Novel, authorId = author?.uid, initialPage = pageKey.page,
                        offlineOnly = true, offlinePage = pageKey)) }, enabled = author != null, colors = actions) { Text(i18n("小說閱讀")) }
                    if (seed != null && author != null) TextButton({ openComic(seed) }, colors = actions) { Text(i18n("智能漫畫")) }
                    val last = work?.members?.firstOrNull { it.tid == work?.lastTid && it.tid != tid }
                    if (last != null) TextButton({ openComic(last) }, colors = actions) { Text(i18n("繼續這部作品")) }
                } else if (imageOnly) {
                    Text(i18n("缺少正文，僅可閱讀已下載圖片"))
                    TextButton({
                        onDismiss()
                        navigator.navigate(IImageReaderScreen(tid, null, null, item.title, loadHistory = true, offlineOnly = true,
                            tagId = (key as? TagMangaChapterDownloadKey)?.tagId?.let(::TagId),
                            rssSubscriptionId = (key as? RssMangaChapterDownloadKey)?.subscriptionId,
                            authorId = pageKey.authorId?.let(::UserId)))
                    }, colors = actions) { Text(i18n("開始閱讀")) }
                } else if (ready) Text(error ?: i18n("這一頁尚未下載"))
            }
        }, confirmButton = { TextButton(onDismiss, colors = actions) { Text(i18n("關閉")) } })
}

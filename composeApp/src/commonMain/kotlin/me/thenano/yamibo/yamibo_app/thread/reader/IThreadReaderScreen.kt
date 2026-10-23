package me.thenano.yamibo.yamibo_app.thread.reader

import androidx.compose.runtime.*
import me.thenano.yamibo.yamibo_app.util.state
import io.github.littlesurvival.dto.value.PostId
import io.github.littlesurvival.dto.value.TagId
import io.github.littlesurvival.dto.value.ThreadId
import io.github.littlesurvival.dto.value.UserId
import kotlinx.serialization.Serializable
import me.thenano.yamibo.yamibo_app.navigation.RestorableNavigatable
import me.thenano.yamibo.yamibo_app.navigation.RestorableScreenEntry
import me.thenano.yamibo.yamibo_app.navigation.RestorableScreenSnapshot
import me.thenano.yamibo.yamibo_app.navigation.TypedRestorableNavigatableDecoder
import me.thenano.yamibo.yamibo_app.navigation.decodeRestorePayload
import me.thenano.yamibo.yamibo_app.navigation.restoreSnapshot
import me.thenano.yamibo.yamibo_app.repository.ContentCoverRepository
import me.thenano.yamibo.yamibo_app.repository.ReadHistoryRepository
import me.thenano.yamibo.yamibo_app.LocalDownloadRepository
import me.thenano.yamibo.yamibo_app.repository.download.ThreadPageDownloadKey
import me.thenano.yamibo.yamibo_app.repository.thread.isValidThreadPage

@Serializable
private data class ThreadReaderRestorePayload(
    val tid: Int,
    val title: String,
    val threadTypeName: String = ReadHistoryRepository.ThreadEntryType.Normal.name,
    val authorId: Int? = null,
    val initialPage: Int = 1,
    val targetPid: Int? = null,
    val catalogCoverTargetTypeName: String? = null,
    val catalogCoverTargetId: Long? = null,
    val catalogTagId: Int? = null,
    val catalogTagName: String? = null,
    val catalogTagPage: Int? = null,
    val catalogRssSubscriptionId: Long? = null,
    val catalogRssTitle: String? = null,
    val catalogRssQuery: String? = null,
    val catalogRssPage: Int? = null,
)

/** Navigatable screen for reading a novel thread in continuous mode. */
@RestorableScreenEntry
class IThreadReaderScreen(
    val tid: ThreadId,
    val title: String,
    val threadType: ReadHistoryRepository.ThreadEntryType = ReadHistoryRepository.ThreadEntryType.Normal,
    val authorId: UserId? = null,
    val initialPage: Int = 1,
    val targetPid: PostId? = null,
    val catalogCoverTargetType: ContentCoverRepository.TargetType? = null,
    val catalogCoverTargetId: Long? = null,
    val catalogTagId: TagId? = null,
    val catalogTagName: String? = null,
    val catalogTagPage: Int? = null,
    val catalogRssSubscriptionId: Long? = null,
    val catalogRssTitle: String? = null,
    val catalogRssQuery: String? = null,
    val catalogRssPage: Int? = null,
) : RestorableNavigatable {
    override val id = buildId(
        tid.value,
        threadType.name,
        authorId?.value ?: "all",
        catalogCoverTargetType?.name ?: "none",
        catalogCoverTargetId ?: "none",
    )
    override val restoreDecoder = Decoder

    override fun toRestoreSnapshot(): RestorableScreenSnapshot = restoreSnapshot(
        decoder = restoreDecoder,
        payload = ThreadReaderRestorePayload(
            tid = tid.value,
            title = title,
            threadTypeName = threadType.name,
            authorId = authorId?.value,
            initialPage = initialPage,
            targetPid = targetPid?.value,
            catalogCoverTargetTypeName = catalogCoverTargetType?.name,
            catalogCoverTargetId = catalogCoverTargetId,
            catalogTagId = catalogTagId?.value,
            catalogTagName = catalogTagName,
            catalogTagPage = catalogTagPage,
            catalogRssSubscriptionId = catalogRssSubscriptionId,
            catalogRssTitle = catalogRssTitle,
            catalogRssQuery = catalogRssQuery,
            catalogRssPage = catalogRssPage,
        ),
    )

    @Composable
    override fun Content() {
        val isNovel = threadType == ReadHistoryRepository.ThreadEntryType.Novel
        val forumMode = isNovel &&
            me.thenano.yamibo.yamibo_app.LocalNovelReaderSettingsRepository.current.forumReadingMode.state()
        val repository = me.thenano.yamibo.yamibo_app.LocalThreadRepository.current
        val downloads = LocalDownloadRepository.current
        var resolvedAuthor by remember(tid, authorId) { mutableStateOf(authorId) }
        var authorLookupFailed by remember(tid) { mutableStateOf(false) }
        var authorRetry by remember(tid) { mutableIntStateOf(0) }
        LaunchedEffect(tid, isNovel, authorId, authorRetry) {
            if (!isNovel || resolvedAuthor != null) return@LaunchedEffect
            authorLookupFailed = false
            try {
                val page = repository.getCachedThread(tid, null, 1)
                    ?: downloads.getDownloadedPage(ThreadPageDownloadKey(tid.value, 1))
                        ?.takeIf { it.isValidThreadPage(tid, page = 1) }
                    ?: (repository.fetchThread(tid, null, 1) as? io.github.littlesurvival.core.YamiboResult.Success)?.value
                resolvedAuthor = page?.posts?.firstOrNull { it.floor == 1 }?.author?.uid
                authorLookupFailed = resolvedAuthor == null
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                authorLookupFailed = true
            }
        }
        if (isNovel && !forumMode && resolvedAuthor == null) {
            if (authorLookupFailed) {
                me.thenano.yamibo.yamibo_app.thread.detail.novel.components.ThreadErrorContent(
                    message = me.thenano.yamibo.yamibo_app.i18n.i18n("載入失敗，請重試"),
                    onRetry = { authorRetry++ },
                )
            } else me.thenano.yamibo.yamibo_app.thread.detail.novel.components.ThreadLoadingSkeleton()
            return
        }
        val effectiveAuthor = if (forumMode) null else resolvedAuthor
        key(forumMode, effectiveAuthor) {
            ThreadReaderScreen(
                tid = tid,
                title = title,
                threadType = threadType,
                authorId = effectiveAuthor,
                initialPage = if (isNovel && (forumMode || authorId == null)) 1 else initialPage,
                targetPid = targetPid,
                catalogCoverTargetType = if (forumMode) null else catalogCoverTargetType,
                catalogCoverTargetId = if (forumMode) null else catalogCoverTargetId,
                catalogTagId = if (forumMode) null else catalogTagId,
                catalogTagName = if (forumMode) null else catalogTagName,
                catalogTagPage = if (forumMode) null else catalogTagPage,
                catalogRssSubscriptionId = if (forumMode) null else catalogRssSubscriptionId,
                catalogRssTitle = if (forumMode) null else catalogRssTitle,
                catalogRssQuery = if (forumMode) null else catalogRssQuery,
                catalogRssPage = if (forumMode) null else catalogRssPage,
            )
        }
    }

    companion object Decoder : TypedRestorableNavigatableDecoder<IThreadReaderScreen>(IThreadReaderScreen::class) {
        override fun decode(payload: String): RestorableNavigatable {
            val data = decodeRestorePayload<ThreadReaderRestorePayload>(payload)
            return IThreadReaderScreen(
                tid = ThreadId(data.tid),
                title = data.title,
                threadType = ReadHistoryRepository.ThreadEntryType.valueOf(data.threadTypeName),
                authorId = data.authorId?.let(::UserId),
                initialPage = data.initialPage,
                targetPid = data.targetPid?.let(::PostId),
                catalogCoverTargetType = data.catalogCoverTargetTypeName
                    ?.let(ContentCoverRepository.TargetType::valueOf),
                catalogCoverTargetId = data.catalogCoverTargetId,
                catalogTagId = data.catalogTagId?.let(::TagId),
                catalogTagName = data.catalogTagName,
                catalogTagPage = data.catalogTagPage,
                catalogRssSubscriptionId = data.catalogRssSubscriptionId,
                catalogRssTitle = data.catalogRssTitle,
                catalogRssQuery = data.catalogRssQuery,
                catalogRssPage = data.catalogRssPage,
            )
        }
    }
}

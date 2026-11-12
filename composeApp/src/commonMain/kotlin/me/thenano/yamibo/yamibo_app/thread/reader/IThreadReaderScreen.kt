package me.thenano.yamibo.yamibo_app.thread.reader

import androidx.compose.runtime.Composable
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
        ThreadReaderScreen(
            tid = tid,
            title = title,
            threadType = threadType,
            authorId = authorId,
            initialPage = initialPage,
            targetPid = targetPid,
            catalogCoverTargetType = catalogCoverTargetType,
            catalogCoverTargetId = catalogCoverTargetId,
            catalogTagId = catalogTagId,
            catalogTagName = catalogTagName,
            catalogTagPage = catalogTagPage,
            catalogRssSubscriptionId = catalogRssSubscriptionId,
            catalogRssTitle = catalogRssTitle,
            catalogRssQuery = catalogRssQuery,
            catalogRssPage = catalogRssPage,
        )
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

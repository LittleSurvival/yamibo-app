package me.thenano.yamibo.yamibo_app.thread.reader

import androidx.compose.runtime.Composable
import io.github.littlesurvival.dto.model.ThreadSummary
import io.github.littlesurvival.dto.value.ForumId
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

@Serializable
private data class ImageReaderRestorePayload(
    val tid: Int,
    val postId: Int? = null,
    val fid: Int? = null,
    val threadTitle: String,
    val initialPage: Int = 1,
    val loadHistory: Boolean = false,
    val tagId: Int? = null,
    val tagName: String? = null,
    val authorId: Int? = null,
    val tagPage: Int? = null,
    val tagTotalPages: Int? = null,
    val rssSubscriptionId: Long? = null,
    val rssTitle: String? = null,
    val rssQuery: String? = null,
    val rssPage: Int? = null,
    val rssTotalPages: Int? = null,
)
@RestorableScreenEntry
class IImageReaderScreen(
    val tid: ThreadId,
    val postId: PostId?,
    val fid: ForumId?,
    val threadTitle: String,
    private val imageList: List<String> = emptyList(),
    val initialPage: Int = 1,
    val loadHistory: Boolean = false,
    
    // Tag Manga Mode Fields:
    val tagId: TagId? = null,
    val tagName: String? = null,
    val authorId: UserId? = null,
    private val tagThreads: List<ThreadSummary>? = null,
    val tagPage: Int? = null,
    val tagTotalPages: Int? = null,

    // RSS Search Catalog Mode Fields:
    val rssSubscriptionId: Long? = null,
    val rssTitle: String? = null,
    val rssQuery: String? = null,
    private val rssThreads: List<ThreadSummary>? = null,
    val rssPage: Int? = null,
    val rssTotalPages: Int? = null,
) : RestorableNavigatable {
    override val id = buildId(tid.value, postId?.value, tagId?.value, rssSubscriptionId)
    override val restoreDecoder = Decoder

    override fun toRestoreSnapshot(): RestorableScreenSnapshot = restoreSnapshot(
        decoder = restoreDecoder,
        payload = ImageReaderRestorePayload(
            tid = tid.value,
            postId = postId?.value,
            fid = fid?.value,
            threadTitle = threadTitle,
            initialPage = initialPage,
            loadHistory = loadHistory,
            tagId = tagId?.value,
            tagName = tagName,
            authorId = authorId?.value,
            tagPage = tagPage,
            tagTotalPages = tagTotalPages,
            rssSubscriptionId = rssSubscriptionId,
            rssTitle = rssTitle,
            rssQuery = rssQuery,
            rssPage = rssPage,
            rssTotalPages = rssTotalPages,
        ),
    )

    @Composable
    override fun Content() {
        ImagesReaderScreen(
            tid = tid,
            postId = postId,
            fid = fid,
            threadTitle = threadTitle,
            imageList = imageList,
            initialPage = initialPage,
            loadHistory = loadHistory,
            tagId = tagId,
            tagName = tagName,
            authorId = authorId,
            tagThreads = tagThreads,
            tagPage = tagPage,
            tagTotalPages = tagTotalPages,
            rssSubscriptionId = rssSubscriptionId,
            rssTitle = rssTitle,
            rssQuery = rssQuery,
            rssThreads = rssThreads,
            rssPage = rssPage,
            rssTotalPages = rssTotalPages,
        )
    }

    companion object Decoder : TypedRestorableNavigatableDecoder<IImageReaderScreen>(IImageReaderScreen::class) {
        override fun decode(payload: String): RestorableNavigatable {
            val data = decodeRestorePayload<ImageReaderRestorePayload>(payload)
            return IImageReaderScreen(
                tid = ThreadId(data.tid),
                postId = data.postId?.let(::PostId),
                fid = data.fid?.let(::ForumId),
                threadTitle = data.threadTitle,
                imageList = emptyList(),
                initialPage = data.initialPage,
                loadHistory = data.loadHistory,
                tagId = data.tagId?.let(::TagId),
                tagName = data.tagName,
                authorId = data.authorId?.let(::UserId),
                tagThreads = null,
                tagPage = data.tagPage,
                tagTotalPages = data.tagTotalPages,
                rssSubscriptionId = data.rssSubscriptionId,
                rssTitle = data.rssTitle,
                rssQuery = data.rssQuery,
                rssThreads = null,
                rssPage = data.rssPage,
                rssTotalPages = data.rssTotalPages,
            )
        }
    }
}

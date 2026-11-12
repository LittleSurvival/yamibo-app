package me.thenano.yamibo.yamibo_app.thread.detail.tag

import androidx.compose.runtime.Composable
import io.github.littlesurvival.dto.value.TagId
import kotlinx.serialization.Serializable
import me.thenano.yamibo.yamibo_app.navigation.RestorableNavigatable
import me.thenano.yamibo.yamibo_app.navigation.RestorableScreenEntry
import me.thenano.yamibo.yamibo_app.navigation.RestorableScreenSnapshot
import me.thenano.yamibo.yamibo_app.navigation.TypedRestorableNavigatableDecoder
import me.thenano.yamibo.yamibo_app.navigation.decodeRestorePayload
import me.thenano.yamibo.yamibo_app.navigation.restoreSnapshot

@Serializable
private data class TagDetailRestorePayload(
    val tagId: Int,
    val title: String,
    val page: Int? = null,
)
@RestorableScreenEntry
class ITagDetailScreen(
    val tagId: TagId,
    val title: String,
    val page: Int? = null
) : RestorableNavigatable {
    override val id = buildId(tagId.value, page)
    override val restoreDecoder = Decoder

    override fun toRestoreSnapshot(): RestorableScreenSnapshot = restoreSnapshot(
        decoder = restoreDecoder,
        payload = TagDetailRestorePayload(
            tagId = tagId.value,
            title = title,
            page = page,
        ),
    )

    @Composable
    override fun Content() {
        TagDetailScreen(tagId = tagId, tagName = title, initialPage = page)
    }

    companion object Decoder : TypedRestorableNavigatableDecoder<ITagDetailScreen>(ITagDetailScreen::class) {
        override fun decode(payload: String): RestorableNavigatable {
            val data = decodeRestorePayload<TagDetailRestorePayload>(payload)
            return ITagDetailScreen(
                tagId = TagId(data.tagId),
                title = data.title,
                page = data.page,
            )
        }
    }
}

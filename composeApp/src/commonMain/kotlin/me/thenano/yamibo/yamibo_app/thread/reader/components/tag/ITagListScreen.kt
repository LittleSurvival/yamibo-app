package me.thenano.yamibo.yamibo_app.thread.reader.components.tag

import androidx.compose.runtime.Composable
import io.github.littlesurvival.dto.model.TagValue
import io.github.littlesurvival.dto.value.ThreadId
import kotlinx.serialization.Serializable
import me.thenano.yamibo.yamibo_app.navigation.RestorableNavigatable
import me.thenano.yamibo.yamibo_app.navigation.RestorableScreenEntry
import me.thenano.yamibo.yamibo_app.navigation.RestorableScreenSnapshot
import me.thenano.yamibo.yamibo_app.navigation.TypedRestorableNavigatableDecoder
import me.thenano.yamibo.yamibo_app.navigation.decodeRestorePayload
import me.thenano.yamibo.yamibo_app.navigation.restoreSnapshot

@Serializable
private data class TagListRestorePayload(
    val tid: Int,
)

/** Entry point for the tag list screen */
@RestorableScreenEntry
class ITagListScreen(
    val tid: ThreadId,
    private val initialTags: List<TagValue> = emptyList()
) : RestorableNavigatable {
    override val id = buildId(tid.value)
    override val restoreDecoder = Decoder

    override fun toRestoreSnapshot(): RestorableScreenSnapshot = restoreSnapshot(
        decoder = restoreDecoder,
        payload = TagListRestorePayload(tid = tid.value),
    )

    @Composable
    override fun Content() {
        TagListScreen(tid = tid, initialTags = initialTags)
    }

    companion object Decoder : TypedRestorableNavigatableDecoder<ITagListScreen>(ITagListScreen::class) {
        override fun decode(payload: String): RestorableNavigatable {
            val data = decodeRestorePayload<TagListRestorePayload>(payload)
            return ITagListScreen(
                tid = ThreadId(data.tid),
                initialTags = emptyList(),
            )
        }
    }
}

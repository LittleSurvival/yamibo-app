package me.thenano.yamibo.yamibo_app.forum

import androidx.compose.runtime.Composable
import io.github.littlesurvival.dto.value.ForumId
import kotlinx.serialization.Serializable
import me.thenano.yamibo.yamibo_app.navigation.RestorableNavigatable
import me.thenano.yamibo.yamibo_app.navigation.RestorableScreenEntry
import me.thenano.yamibo.yamibo_app.navigation.RestorableScreenSnapshot
import me.thenano.yamibo.yamibo_app.navigation.TypedRestorableNavigatableDecoder
import me.thenano.yamibo.yamibo_app.navigation.decodeRestorePayload
import me.thenano.yamibo.yamibo_app.navigation.restoreSnapshot

@Serializable
private data class ForumScreenRestorePayload(
    val fid: Int,
    val name: String,
)

/** Navigatable screen for a specific forum page. */
@RestorableScreenEntry
class IForumScreen(
    val fid: ForumId,
    val name: String
) : RestorableNavigatable {
    override val id = buildId(fid.value)
    override val restoreDecoder = Decoder

    override fun toRestoreSnapshot(): RestorableScreenSnapshot = restoreSnapshot(
        decoder = restoreDecoder,
        payload = ForumScreenRestorePayload(fid = fid.value, name = name),
    )

    @Composable
    override fun Content() {
        ForumPageScreen(fid = fid, name = name, screenId = id)
    }

    companion object Decoder : TypedRestorableNavigatableDecoder<IForumScreen>(IForumScreen::class) {
        override fun decode(payload: String): RestorableNavigatable {
            val data = decodeRestorePayload<ForumScreenRestorePayload>(payload)
            return IForumScreen(
                fid = ForumId(data.fid),
                name = data.name,
            )
        }
    }
}

package me.thenano.yamibo.yamibo_app.forum.search

import androidx.compose.runtime.Composable
import io.github.littlesurvival.dto.value.ForumId
import kotlinx.serialization.Serializable
import me.thenano.yamibo.yamibo_app.navigation.*

@Serializable
private data class SearchScreenRestorePayload(val fid: Int?)

@RestorableScreenEntry
class ISearchScreen(private val fid: ForumId? = null) : RestorableNavigatable {
    override val id = buildId(fid?.value ?: "all")
    override val restoreDecoder = Decoder

    override fun toRestoreSnapshot(): RestorableScreenSnapshot = restoreSnapshot(
        decoder = restoreDecoder,
        payload = SearchScreenRestorePayload(fid = fid?.value),
    )

    @Composable
    override fun Content() {
        SearchScreen(fid = fid, screenId = id)
    }

    companion object Decoder : TypedRestorableNavigatableDecoder<ISearchScreen>(ISearchScreen::class) {
        override fun decode(payload: String): RestorableNavigatable {
            val data = decodeRestorePayload<SearchScreenRestorePayload>(payload)
            return ISearchScreen(fid = data.fid?.let(::ForumId))
        }
    }
}

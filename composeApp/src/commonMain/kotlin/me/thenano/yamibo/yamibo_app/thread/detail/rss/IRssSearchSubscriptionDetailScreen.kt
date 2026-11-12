package me.thenano.yamibo.yamibo_app.thread.detail.rss

import androidx.compose.runtime.Composable
import kotlinx.serialization.Serializable
import me.thenano.yamibo.yamibo_app.navigation.RestorableNavigatable
import me.thenano.yamibo.yamibo_app.navigation.RestorableScreenEntry
import me.thenano.yamibo.yamibo_app.navigation.RestorableScreenSnapshot
import me.thenano.yamibo.yamibo_app.navigation.TypedRestorableNavigatableDecoder
import me.thenano.yamibo.yamibo_app.navigation.decodeRestorePayload
import me.thenano.yamibo.yamibo_app.navigation.restoreSnapshot

@Serializable
private data class RssSearchSubscriptionDetailRestorePayload(
    val subscriptionId: Long,
    val page: Int? = null,
)

@RestorableScreenEntry
class IRssSearchSubscriptionDetailScreen(
    private val subscriptionId: Long,
    private val page: Int? = null,
) : RestorableNavigatable {
    override val id = buildId(subscriptionId, page)
    override val restoreDecoder = Decoder

    override fun toRestoreSnapshot(): RestorableScreenSnapshot = restoreSnapshot(
        decoder = restoreDecoder,
        payload = RssSearchSubscriptionDetailRestorePayload(subscriptionId, page),
    )

    @Composable
    override fun Content() {
        RssSearchSubscriptionDetailScreen(subscriptionId = subscriptionId, initialPage = page)
    }

    companion object Decoder : TypedRestorableNavigatableDecoder<IRssSearchSubscriptionDetailScreen>(IRssSearchSubscriptionDetailScreen::class) {
        override fun decode(payload: String): RestorableNavigatable {
            val data = decodeRestorePayload<RssSearchSubscriptionDetailRestorePayload>(payload)
            return IRssSearchSubscriptionDetailScreen(data.subscriptionId, data.page)
        }
    }
}

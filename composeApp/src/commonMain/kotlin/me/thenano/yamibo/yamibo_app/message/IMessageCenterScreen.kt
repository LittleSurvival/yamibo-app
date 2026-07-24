package me.thenano.yamibo.yamibo_app.message

import androidx.compose.runtime.Composable
import kotlinx.serialization.Serializable
import me.thenano.yamibo.yamibo_app.Logger
import me.thenano.yamibo.yamibo_app.navigation.RestorableNavigatable
import me.thenano.yamibo.yamibo_app.navigation.RestorableScreenEntry
import me.thenano.yamibo.yamibo_app.navigation.RestorableScreenSnapshot
import me.thenano.yamibo.yamibo_app.navigation.TypedRestorableNavigatableDecoder
import me.thenano.yamibo.yamibo_app.navigation.decodeRestorePayload
import me.thenano.yamibo.yamibo_app.navigation.restoreSnapshot

@Serializable
private data class MessageCenterRestorePayload(
    val initialTabName: String = MessageCenterTab.PrivateMessages.name,
    val mainTabTopBar: Boolean = false,
)
@RestorableScreenEntry
class IMessageCenterScreen(
    val initialTab: MessageCenterTab = MessageCenterTab.PrivateMessages,
    val mainTabTopBar: Boolean = false,
    private val onPrivateMessageUnreadChange: (Boolean) -> Unit = {},
) : RestorableNavigatable {
    override val id = buildId(initialTab.name, mainTabTopBar.toString())
    override val restoreDecoder = Decoder

    override fun toRestoreSnapshot(): RestorableScreenSnapshot = restoreSnapshot(
        decoder = restoreDecoder,
        payload = MessageCenterRestorePayload(
            initialTabName = initialTab.name,
            mainTabTopBar = mainTabTopBar,
        ),
    )

    @Composable
    override fun Content() {
        MessageCenterScreen(
            initialTab = initialTab,
            mainTabTopBar = mainTabTopBar,
            onPrivateMessageUnreadChange = onPrivateMessageUnreadChange,
        )
    }

    companion object Decoder : TypedRestorableNavigatableDecoder<IMessageCenterScreen>(IMessageCenterScreen::class) {
        override fun decode(payload: String): RestorableNavigatable {
            val data = decodeRestorePayload<MessageCenterRestorePayload>(payload)
            val initialTab = try {
                MessageCenterTab.valueOf(data.initialTabName)
            } catch (error: Exception) {
                Logger.d("IMessageCenterScreen", "Failed to restore message center tab name=${data.initialTabName}", error)
                MessageCenterTab.PrivateMessages
            }
            return IMessageCenterScreen(
                initialTab = initialTab,
                mainTabTopBar = data.mainTabTopBar,
            )
        }
    }
}

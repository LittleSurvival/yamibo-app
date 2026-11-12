package me.thenano.yamibo.yamibo_app.profile.download

import androidx.compose.runtime.Composable
import me.thenano.yamibo.yamibo_app.navigation.RestorableNavigatable
import me.thenano.yamibo.yamibo_app.navigation.RestorableScreenEntry
import me.thenano.yamibo.yamibo_app.navigation.RestorableScreenSnapshot
import me.thenano.yamibo.yamibo_app.navigation.TypedRestorableNavigatableDecoder
import me.thenano.yamibo.yamibo_app.navigation.emptyRestoreSnapshot

@RestorableScreenEntry
class IDownloadQueueScreen : RestorableNavigatable {
    override val id = buildId("download_queue")
    override val restoreDecoder = Decoder

    override fun toRestoreSnapshot(): RestorableScreenSnapshot = emptyRestoreSnapshot(restoreDecoder)

    @Composable
    override fun Content() {
        DownloadQueueScreen(screenId = id)
    }

    companion object Decoder : TypedRestorableNavigatableDecoder<IDownloadQueueScreen>(IDownloadQueueScreen::class) {
        override fun decode(payload: String): RestorableNavigatable = IDownloadQueueScreen()
    }
}

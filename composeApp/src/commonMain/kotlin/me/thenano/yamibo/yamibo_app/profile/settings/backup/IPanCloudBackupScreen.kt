package me.thenano.yamibo.yamibo_app.profile.settings.backup

import androidx.compose.runtime.Composable
import me.thenano.yamibo.yamibo_app.navigation.RestorableNavigatable
import me.thenano.yamibo.yamibo_app.navigation.RestorableScreenEntry
import me.thenano.yamibo.yamibo_app.navigation.RestorableScreenSnapshot
import me.thenano.yamibo.yamibo_app.navigation.TypedRestorableNavigatableDecoder
import me.thenano.yamibo.yamibo_app.navigation.emptyRestoreSnapshot

@RestorableScreenEntry
class IPanCloudBackupScreen : RestorableNavigatable {
    override val id = buildId("pan_cloud_backup")
    override val restoreDecoder = Decoder

    override fun toRestoreSnapshot(): RestorableScreenSnapshot = emptyRestoreSnapshot(restoreDecoder)

    @Composable
    override fun Content() {
        PanCloudBackupScreen()
    }

    companion object Decoder : TypedRestorableNavigatableDecoder<IPanCloudBackupScreen>(IPanCloudBackupScreen::class) {
        override fun decode(payload: String): RestorableNavigatable = IPanCloudBackupScreen()
    }
}

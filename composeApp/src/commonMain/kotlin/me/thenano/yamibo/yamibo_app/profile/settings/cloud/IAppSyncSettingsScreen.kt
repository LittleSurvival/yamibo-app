package me.thenano.yamibo.yamibo_app.profile.settings.cloud

import androidx.compose.runtime.Composable
import me.thenano.yamibo.yamibo_app.navigation.RestorableNavigatable
import me.thenano.yamibo.yamibo_app.navigation.RestorableScreenEntry
import me.thenano.yamibo.yamibo_app.navigation.RestorableScreenSnapshot
import me.thenano.yamibo.yamibo_app.navigation.TypedRestorableNavigatableDecoder
import me.thenano.yamibo.yamibo_app.navigation.emptyRestoreSnapshot

@RestorableScreenEntry
class IAppSyncSettingsScreen : RestorableNavigatable {
    override val id = buildId("app_sync_settings")
    override val restoreDecoder = Decoder

    override fun toRestoreSnapshot(): RestorableScreenSnapshot = emptyRestoreSnapshot(restoreDecoder)

    @Composable
    override fun Content() {
        AppSyncSettingsScreen()
    }

    companion object Decoder :
        TypedRestorableNavigatableDecoder<IAppSyncSettingsScreen>(IAppSyncSettingsScreen::class) {
        override fun decode(payload: String): RestorableNavigatable = IAppSyncSettingsScreen()
    }
}

package me.thenano.yamibo.yamibo_app.profile.settings.bound

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import me.thenano.yamibo.yamibo_app.util.chooseDesktopFile
import me.thenano.yamibo.yamibo_app.i18n.i18n

@Composable
actual fun FontFilePickerButton(enabled: Boolean, onPicked: (sourceUri: String, displayName: String?) -> Unit, onUnavailable: () -> Unit) {
    TextButton(enabled = enabled, onClick = {
        try { chooseDesktopFile()?.let { onPicked(it.toURI().toString(), it.name) } }
        catch (_: Exception) { onUnavailable() }
    }) { Text(i18n("載入字體")) }
}

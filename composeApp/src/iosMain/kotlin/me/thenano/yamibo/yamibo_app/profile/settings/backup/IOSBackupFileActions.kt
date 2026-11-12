package me.thenano.yamibo.yamibo_app.profile.settings.backup

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
actual fun rememberBackupFileActions(
    onFolderSelected: (String) -> Unit,
    onBackupPicked: (String) -> Unit,
): BackupFileActions {
    return remember {
        BackupFileActions(
            canSelectFolder = false,
            selectFolder = {},
            pickBackupFile = {},
        )
    }
}

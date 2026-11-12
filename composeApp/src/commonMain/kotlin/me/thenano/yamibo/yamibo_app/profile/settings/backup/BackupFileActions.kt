package me.thenano.yamibo.yamibo_app.profile.settings.backup

import androidx.compose.runtime.Composable

class BackupFileActions(
    val canSelectFolder: Boolean,
    val selectFolder: () -> Unit,
    val pickBackupFile: () -> Unit,
)

@Composable
expect fun rememberBackupFileActions(
    onFolderSelected: (String) -> Unit,
    onBackupPicked: (String) -> Unit,
): BackupFileActions

package me.thenano.yamibo.yamibo_app.profile.settings.backup

import androidx.compose.runtime.Composable
import me.thenano.yamibo.yamibo_app.util.chooseDesktopFile

@Composable
actual fun rememberBackupFileActions(onFolderSelected: (String) -> Unit, onBackupPicked: (String) -> Unit) =
    BackupFileActions(
        selectFolder = { chooseDesktopFile(directory = true)?.let { onFolderSelected(it.toURI().toString()) } },
        pickBackupFile = { chooseDesktopFile()?.let { onBackupPicked(it.toURI().toString()) } },
    )

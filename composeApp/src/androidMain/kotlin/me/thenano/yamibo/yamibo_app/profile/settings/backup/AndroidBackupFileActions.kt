package me.thenano.yamibo.yamibo_app.profile.settings.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable

@Composable
actual fun rememberBackupFileActions(
    onFolderSelected: (String) -> Unit,
    onBackupPicked: (String) -> Unit,
): BackupFileActions {
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { onFolderSelected(it.toString()) }
    }
    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { onBackupPicked(it.toString()) }
    }
    return BackupFileActions(
        canSelectFolder = true,
        selectFolder = { folderLauncher.launch(null) },
        pickBackupFile = { backupLauncher.launch(arrayOf("*/*")) },
    )
}

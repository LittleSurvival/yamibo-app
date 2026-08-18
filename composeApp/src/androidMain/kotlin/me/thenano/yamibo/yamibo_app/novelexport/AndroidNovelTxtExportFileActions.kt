package me.thenano.yamibo.yamibo_app.novelexport

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import me.thenano.yamibo.yamibo_app.Logger

@Composable
actual fun rememberNovelTxtExportFileActions(
    onExported: (fileName: String) -> Unit,
    onExportFailed: (message: String) -> Unit,
): NovelTxtExportFileActions {
    val context = LocalContext.current
    val pendingExport = remember { mutableStateOf<Pair<String, String>?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        val export = pendingExport.value
        pendingExport.value = null
        if (uri == null || export == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { output ->
                output.write(export.second.toByteArray(Charsets.UTF_8))
            } ?: error("无法打开导出文件")
        }.onSuccess {
            onExported(export.first)
        }.onFailure { error ->
            Logger.e("NovelTxtExportFileActions", "Failed to export txt name=${export.first}", error)
            onExportFailed(error.message ?: "导出TXT失败")
        }
    }

    return NovelTxtExportFileActions(
        exportTxt = { fileName, text ->
            pendingExport.value = fileName to text
            exportLauncher.launch(fileName)
        },
    )
}

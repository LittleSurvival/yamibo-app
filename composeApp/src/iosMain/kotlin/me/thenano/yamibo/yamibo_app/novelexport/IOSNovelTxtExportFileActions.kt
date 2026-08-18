package me.thenano.yamibo.yamibo_app.novelexport

import androidx.compose.runtime.Composable

@Composable
actual fun rememberNovelTxtExportFileActions(
    onExported: (fileName: String) -> Unit,
    onExportFailed: (message: String) -> Unit,
): NovelTxtExportFileActions {
    return NovelTxtExportFileActions(
        exportTxt = { _, _ -> onExportFailed("iOS 暂不支持导出TXT文件") },
    )
}

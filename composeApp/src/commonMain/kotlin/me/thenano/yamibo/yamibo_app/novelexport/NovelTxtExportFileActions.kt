package me.thenano.yamibo.yamibo_app.novelexport

import androidx.compose.runtime.Composable

class NovelTxtExportFileActions(
    val exportTxt: (fileName: String, text: String) -> Unit,
)

@Composable
expect fun rememberNovelTxtExportFileActions(
    onExported: (fileName: String) -> Unit,
    onExportFailed: (message: String) -> Unit,
): NovelTxtExportFileActions

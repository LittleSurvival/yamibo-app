package me.thenano.yamibo.yamibo_app.favorite

import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.thenano.yamibo.yamibo_app.util.chooseDesktopFile
import me.thenano.yamibo.yamibo_app.util.writeDesktopFile

@Composable
actual fun rememberFavoriteShareFileActions(onExported: (String) -> Unit, onExportFailed: (String) -> Unit,
    onImportPicked: (String) -> Unit, onImportFailed: (String) -> Unit): FavoriteShareFileActions {
    val scope = rememberCoroutineScope()
    val export: (String, String) -> Unit = { name, text ->
        scope.launch {
            try {
                val file = chooseDesktopFile(save = true, name = name)
                if (file != null) {
                    withContext(Dispatchers.IO) { writeDesktopFile(file, text.toByteArray(Charsets.UTF_8)) }
                    onExported(file.absolutePath)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { onExportFailed("無法匯出收藏檔案") }
        }
    }
    return FavoriteShareFileActions(exportJson = export, shareJson = export, pickJson = {
        scope.launch {
            try {
                val file = chooseDesktopFile()
                if (file != null) onImportPicked(withContext(Dispatchers.IO) { file.readText(Charsets.UTF_8) })
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { onImportFailed("無法讀取收藏檔案") }
        }
    })
}

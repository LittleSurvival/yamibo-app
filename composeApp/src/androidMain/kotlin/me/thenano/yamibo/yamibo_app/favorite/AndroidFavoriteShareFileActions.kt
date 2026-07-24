package me.thenano.yamibo.yamibo_app.favorite

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import me.thenano.yamibo.yamibo_app.Logger
import me.thenano.yamibo.yamibo_app.i18n.i18n
import java.io.File

@Composable
actual fun rememberFavoriteShareFileActions(
    onExported: (String) -> Unit,
    onExportFailed: (String) -> Unit,
    onImportPicked: (String) -> Unit,
    onImportFailed: (String) -> Unit,
): FavoriteShareFileActions {
    val context = LocalContext.current
    val pendingExport = remember { mutableStateOf<Pair<String, String>?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val export = pendingExport.value
        pendingExport.value = null
        if (uri == null || export == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { output ->
                output.write(export.second.toByteArray(Charsets.UTF_8))
            } ?: error("無法開啟輸出檔案")
        }.onSuccess {
            onExported(export.first)
        }.onFailure { error ->
            Logger.e("FavoriteShareFileActions", "Failed to export favorite share file name=${export.first}", error)
            onExportFailed(error.message ?: "匯出收藏夾失敗")
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                input.readBytes().toString(Charsets.UTF_8)
            } ?: error("無法讀取檔案")
        }.onSuccess(onImportPicked)
            .onFailure { error ->
                Logger.e("FavoriteShareFileActions", "Failed to import favorite share file", error)
                onImportFailed(error.message ?: "讀取收藏分享檔案失敗")
            }
    }
    return FavoriteShareFileActions(
        exportJson = { fileName, jsonText ->
            pendingExport.value = fileName to jsonText
            exportLauncher.launch(fileName)
        },
        shareJson = { fileName, jsonText ->
            runCatching {
                val safeFileName = fileName
                    .replace(Regex("""[\\/:*?"<>|]"""), "_")
                    .takeIf { it.endsWith(".json", ignoreCase = true) }
                    ?: "yamibo-favorites.json"
                val shareDir = File(context.cacheDir, "favorite_share").apply { mkdirs() }
                val shareFile = File(shareDir, safeFileName).apply {
                    writeText(jsonText, Charsets.UTF_8)
                }
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    shareFile,
                )
                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, safeFileName)
                    putExtra(Intent.EXTRA_TITLE, safeFileName)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(sendIntent, i18n("分享收藏夾")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(chooser)
            }.onFailure { error ->
                Logger.e("FavoriteShareFileActions", "Failed to share favorite file name=$fileName", error)
                val message = when (error) {
                    is ActivityNotFoundException -> i18n("找不到可分享檔案的 App")
                    else -> error.message ?: i18n("匯出收藏夾失敗")
                }
                onExportFailed(message)
            }
        },
        pickJson = {
            importLauncher.launch(arrayOf("*/*"))
        },
    )
}

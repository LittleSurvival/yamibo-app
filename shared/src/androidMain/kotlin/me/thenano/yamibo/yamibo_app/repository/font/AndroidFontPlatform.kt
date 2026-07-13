package me.thenano.yamibo.yamibo_app.repository.font

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import me.thenano.yamibo.yamibo_app.Logger

class AndroidFontPlatform(private val context: Context) : FontPlatform {
    override val supportsFontLoading: Boolean = true
    override val unavailableMessage: String? = null

    override suspend fun importFont(sourceUri: String, displayName: String?, id: String): FontImportResult {
        return try {
            val uri = Uri.parse(sourceUri)
            val sourceName = displayName
                ?.takeIf { it.isNotBlank() }
                ?: queryDisplayName(uri)
                ?: "font"
            val extension = sourceName.substringAfterLast('.', "").lowercase()
            if (extension != "ttf" && extension != "otf") {
                return FontImportResult.Failure("Only .ttf and .otf font files are supported.")
            }

            val fontsDir = File(context.filesDir, "fonts").apply { mkdirs() }
            val safeFileName = "${id}_${sourceName.sanitizeFileName()}"
            val target = File(fontsDir, safeFileName)

            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return FontImportResult.Failure("Unable to open selected font file.")

            FontImportResult.Success(
                name = sourceName.substringBeforeLast('.').ifBlank { sourceName },
                fileName = sourceName,
                platformPath = target.absolutePath,
            )
        } catch (error: Throwable) {
            Logger.e("AndroidFontPlatform", "Failed to import font id=$id", error)
            FontImportResult.Failure(error.message ?: "Unable to import selected font file.")
        }
    }

    override fun deleteFont(font: LoadedFont): Boolean =
        runCatching { File(font.platformPath).delete() }
            .onFailure { Logger.w("AndroidFontPlatform", "Failed to delete font id=${font.id}", it) }
            .getOrDefault(false)

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) cursor.getString(nameIndex) else null
        }
    }
        .onFailure { Logger.d("AndroidFontPlatform", "Failed to query font display name", it) }
        .getOrNull()

    private fun String.sanitizeFileName(): String =
        replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "font" }
}

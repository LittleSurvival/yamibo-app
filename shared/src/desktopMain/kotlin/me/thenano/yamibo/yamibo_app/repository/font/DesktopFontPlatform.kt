package me.thenano.yamibo.yamibo_app.repository.font

import java.awt.Font
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.thenano.yamibo.yamibo_app.desktop.DesktopDirectories

class DesktopFontPlatform(
    private val fontsDirectory: Path = DesktopDirectories.dataRoot().resolve("fonts"),
) : FontPlatform {
    override val supportsFontLoading = true
    override val unavailableMessage: String? = null

    override suspend fun importFont(sourceUri: String, displayName: String?, id: String): FontImportResult =
        withContext(Dispatchers.IO) {
            try {
                require(id.matches(Regex("[A-Za-z0-9_-]+"))) { "字型識別碼無效" }
                val source = if (sourceUri.startsWith("file:", ignoreCase = true)) {
                    Path.of(URI(sourceUri))
                } else Path.of(sourceUri)
                val name = displayName?.takeIf(String::isNotBlank) ?: source.fileName.toString()
                val extension = name.substringAfterLast('.', "").lowercase()
                require(extension in setOf("ttf", "otf")) { "只支援 .ttf 與 .otf 字型" }
                val root = Files.createDirectories(fontsDirectory).toRealPath()
                val target = root.resolve("$id.$extension")
                require(!Files.exists(target, NOFOLLOW_LINKS)) { "字型已存在" }
                val temporary = Files.createTempFile(root, ".font-", ".tmp")
                try {
                    Files.newInputStream(source).use { input ->
                        Files.newOutputStream(temporary).use { output -> input.copyTo(output) }
                    }
                    // Validate actual font data before persisting a registry entry.
                    Files.newInputStream(temporary).use { Font.createFont(Font.TRUETYPE_FONT, it) }
                    Files.move(temporary, target)
                    FontImportResult.Success(
                        name = name.substringBeforeLast('.').ifBlank { name },
                        fileName = name,
                        platformPath = target.toString(),
                    )
                } finally {
                    Files.deleteIfExists(temporary)
                }
            } catch (error: Exception) {
                FontImportResult.Failure(error.message ?: "無法匯入字型")
            }
        }

    override fun deleteFont(font: LoadedFont): Boolean = runCatching {
        val root = fontsDirectory.toRealPath()
        val target = Path.of(font.platformPath).toAbsolutePath().normalize()
        require(target.parent == root && Files.isRegularFile(target, NOFOLLOW_LINKS))
        Files.delete(target)
        true
    }.getOrDefault(false)
}

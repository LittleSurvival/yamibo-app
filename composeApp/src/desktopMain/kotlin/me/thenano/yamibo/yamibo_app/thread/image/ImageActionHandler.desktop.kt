package me.thenano.yamibo.yamibo_app.thread.image

import coil3.PlatformContext
import java.awt.Toolkit
import java.awt.datatransfer.*
import java.io.ByteArrayInputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.thenano.yamibo.yamibo_app.repository.download.DownloadImageFetcher
import me.thenano.yamibo.yamibo_app.util.chooseDesktopFile
import me.thenano.yamibo.yamibo_app.util.writeDesktopFile
import org.jetbrains.skia.Image
import org.jetbrains.skia.EncodedImageFormat
import androidx.compose.ui.window.DialogProperties

actual fun imageContextMenuDialogProperties() = DialogProperties(usePlatformDefaultWidth = false)

private suspend fun imageBytes(url: String, cookie: String): ByteArray = withContext(Dispatchers.IO) {
    if (url.startsWith("file:", true)) return@withContext Files.readAllBytes(Path.of(URI(url)))
    val fetcher = DownloadImageFetcher { cookie }
    try { fetcher.fetch(url) } finally { fetcher.close() }
}

internal fun decodeDesktopClipboardImage(bytes: ByteArray): java.awt.image.BufferedImage =
    Image.makeFromEncoded(bytes).use { decoded ->
        checkNotNull(decoded.encodeToData(EncodedImageFormat.PNG)).use { png ->
            checkNotNull(ImageIO.read(ByteArrayInputStream(png.bytes)))
        }
    }

actual suspend fun copyImageToClipboard(context: PlatformContext, url: String, cookie: String, referer: String): ImageActionResult =
    try {
        val bytes = imageBytes(url, cookie)
        val image = withContext(Dispatchers.IO) { decodeDesktopClipboardImage(bytes) }
        withContext(Dispatchers.Main) {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(object : Transferable {
                override fun getTransferDataFlavors() = arrayOf(DataFlavor.imageFlavor)
                override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.imageFlavor
                override fun getTransferData(flavor: DataFlavor): Any {
                    if (!isDataFlavorSupported(flavor)) throw UnsupportedFlavorException(flavor)
                    return image
                }
            }, null)
        }
        ImageActionResult(successMessage = "已複製圖片")
    } catch (error: CancellationException) { throw error }
    catch (error: Exception) { ImageActionResult(errorMessage = error.message ?: "無法複製圖片") }

actual suspend fun shareImageToApp(context: PlatformContext, url: String, cookie: String, referer: String): ImageActionResult =
    saveImageToGallery(context, url, cookie, referer)

actual suspend fun saveImageToGallery(context: PlatformContext, url: String, cookie: String, referer: String): ImageActionResult =
    try {
        val name = runCatching { URI(url).path.substringAfterLast('/').takeIf { it.isNotBlank() } }.getOrNull() ?: "image"
        val file = withContext(Dispatchers.Main) { chooseDesktopFile(save = true, name = name) }
        if (file == null) ImageActionResult()
        else {
            val bytes = imageBytes(url, cookie)
            withContext(Dispatchers.IO) { writeDesktopFile(file, bytes) }
            ImageActionResult(successMessage = "圖片已儲存，可從檔案管理員分享")
        }
    } catch (error: CancellationException) { throw error }
    catch (error: Exception) { ImageActionResult(errorMessage = error.message ?: "無法儲存圖片") }

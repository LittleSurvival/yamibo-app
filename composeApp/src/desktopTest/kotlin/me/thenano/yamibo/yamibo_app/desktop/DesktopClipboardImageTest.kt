package me.thenano.yamibo.yamibo_app.desktop

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import org.jetbrains.skia.Image
import org.jetbrains.skia.EncodedImageFormat
import me.thenano.yamibo.yamibo_app.thread.image.decodeDesktopClipboardImage
import kotlin.test.*

class DesktopClipboardImageTest {
    @Test fun clipboardDecoderSupportsPngAndWebPWithoutAnImageIoPlugin() {
        val png = ByteArrayOutputStream().apply {
            ImageIO.write(BufferedImage(3, 2, BufferedImage.TYPE_INT_ARGB).apply {
                setRGB(0, 0, 0xFFFF0000.toInt())
            }, "png", this)
        }.toByteArray()
        val webp = Image.makeFromEncoded(png).use { image ->
            checkNotNull(image.encodeToData(EncodedImageFormat.WEBP, 100)).use { it.bytes }
        }
        for (bytes in listOf(png, webp)) {
            val decoded = decodeDesktopClipboardImage(bytes)
            assertEquals(3, decoded.width)
            assertEquals(2, decoded.height)
        }
    }
}

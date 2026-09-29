package me.thenano.yamibo.yamibo_app.thread.image

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.metadata.IIOMetadataNode
import javax.imageio.stream.MemoryCacheImageOutputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.compose.AsyncImagePainter
import coil3.compose.asPainter
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.crossfade
import coil3.request.crossfadeMillis
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Color
import org.jetbrains.skia.Image
import kotlin.test.*

class DesktopImageAnimationTest {
    @Test fun largeAnimationDecodesAtFullSizeAndCancelsWithoutFurtherDelivery() = runBlocking {
        val frames = listOf(java.awt.Color.RED, java.awt.Color.BLUE).map { color ->
            BufferedImage(1536, 2048, BufferedImage.TYPE_INT_RGB).apply {
                createGraphics().let { graphics ->
                    try { graphics.color = color; graphics.fillRect(0, 0, width, height) }
                    finally { graphics.dispose() }
                }
            }
        }
        val encoded = gif(0, frames)
        frames.forEach { it.flush() }
        val image = assertNotNull(decodeDesktopAnimation(encoded))
        assertEquals(1536, image.width)
        assertEquals(2048, image.height)
        assertEquals(encoded.size.toLong() + 1536L * 2048 * 4, image.size)
        val delivered = java.util.concurrent.CopyOnWriteArrayList<Int>()
        val job = launch {
            playDesktopAnimation(image) {
                assertEquals(1536, it.width)
                assertEquals(2048, it.height)
                delivered += color(it)
            }
        }
        try {
            withTimeout(15_000) { while (delivered.size < 6) delay(10) }
        } finally { job.cancelAndJoin() }
        val stoppedAt = delivered.size
        delay(150)
        assertEquals(stoppedAt, delivered.size)
        assertEquals(listOf(Color.RED, Color.BLUE, Color.RED, Color.BLUE, Color.RED, Color.BLUE), delivered.take(6))
    }

    @Test fun hiddenWindowSuspendsAnimationAndShowingItRestartsPlayback() = runBlocking {
        val visible = MutableStateFlow(false)
        val painter = DesktopAnimatedImagePainter(assertNotNull(decodeDesktopAnimation(gif(0))), visible)
        withContext(Dispatchers.Main.immediate) {
            try {
                painter.onRemembered()
                delay(120)
                assertEquals(Color.RED, paintColor(painter))
                visible.value = true
                withTimeout(5_000) { while (paintColor(painter) != Color.BLUE) delay(5) }
                visible.value = false
                delay(120)
                assertEquals(Color.RED, paintColor(painter))
                visible.value = true
                withTimeout(5_000) { while (paintColor(painter) != Color.BLUE) delay(5) }
            } finally { painter.onForgotten() }
        }
    }
    @Test fun animatedWebpUsesCoilDecoderAndStopsAfterOneLoop() = runBlocking {
        val loader = ImageLoader.Builder(PlatformContext.INSTANCE)
            .components { addPlatformImageDecoders() }.build()
        try {
            val (bytes, expectedColors) = animatedWebp()
            val result = assertIs<SuccessResult>(loader.execute(ImageRequest.Builder(PlatformContext.INSTANCE)
                .data(bytes).build()))
            val image = assertIs<DesktopAnimatedImage>(result.image)
            assertEquals(listOf(40, 60), image.durations)
            assertEquals(0, image.repetitions)
            val frames = mutableListOf<Int>()
            withTimeout(5_000) { playDesktopAnimation(image) { frames += color(it) } }
            assertNotEquals(expectedColors[0], expectedColors[1])
            assertEquals(expectedColors, frames)
        } finally { loader.shutdown() }
    }

    // Minimal WebP animation assembled per the official RIFF container specification:
    // https://developers.google.com/speed/webp/docs/riff_container
    private fun animatedWebp(): Pair<ByteArray, List<Int>> {
        fun le(value: Int, count: Int) = ByteArray(count) { (value ushr (8 * it)).toByte() }
        fun chunk(id: String, payload: ByteArray) = id.encodeToByteArray() + le(payload.size, 4) +
            payload + ByteArray(payload.size % 2)
        val header = chunk("VP8X", byteArrayOf(2, 0, 0, 0) + le(1, 3) + le(1, 3))
        val animation = chunk("ANIM", ByteArray(4) + le(1, 2))
        val expectedColors = mutableListOf<Int>()
        val frames = listOf(Color.RED, Color.BLUE).mapIndexed { index, color ->
            val encoded = Bitmap().use { bitmap ->
                check(bitmap.allocN32Pixels(2, 2))
                bitmap.erase(color)
                Image.makeFromBitmap(bitmap).use { image ->
                    image.encodeToData(org.jetbrains.skia.EncodedImageFormat.WEBP, 100)!!.use { it.bytes }
                }
            }
            assertEquals("VP8 ", encoded.copyOfRange(12, 16).decodeToString())
            // Compare with the independently decoded still frame, accounting for lossy encoding.
            Image.makeFromEncoded(encoded).use { expectedColors += color(it) }
            val frameHeader = ByteArray(6) + le(1, 3) + le(1, 3) + le(40 + index * 20, 3) + byteArrayOf(2)
            chunk("ANMF", frameHeader + encoded.copyOfRange(12, encoded.size))
        }
        val contents = "WEBP".encodeToByteArray() + header + animation + frames[0] + frames[1]
        return ("RIFF".encodeToByteArray() + le(contents.size, 4) + contents) to expectedColors
    }

    @Test fun transparentFramesRestorePreviousContentBeforeNextFrame() = runBlocking {
        val red = BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB).apply {
            for (y in 0..1) for (x in 0..1) setRGB(x, y, java.awt.Color.RED.rgb)
        }
        val blueOverlay = BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB).apply {
            setRGB(0, 0, java.awt.Color.BLUE.rgb)
        }
        val greenOverlay = BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB).apply {
            setRGB(1, 0, java.awt.Color.GREEN.rgb)
        }
        val image = assertNotNull(decodeDesktopAnimation(gif(1, listOf(red, blueOverlay, greenOverlay),
            listOf("none", "restoreToPrevious", "none"))))
        val pixels = mutableListOf<Pair<Int, Int>>()
        withTimeout(5_000) {
            playDesktopAnimation(image) { frame ->
                Bitmap.makeFromImage(frame).use { pixels += it.getColor(0, 0) to it.getColor(1, 0) }
            }
        }
        assertEquals(listOf(Color.RED to Color.RED, Color.BLUE to Color.RED, Color.RED to Color.GREEN),
            pixels.take(3))
    }

    @Test fun coilPipelineUsesAnimatedPainterAndForgettingStopsPlayback() = runBlocking {
        val loader = ImageLoader.Builder(PlatformContext.INSTANCE)
            .components { addPlatformImageDecoders() }.build()
        try {
            val result = assertIs<SuccessResult>(loader.execute(ImageRequest.Builder(PlatformContext.INSTANCE)
                .data(gif(repeats = 0)).crossfade(true).build()))
            assertIs<DesktopAnimatedImage>(result.image)
            val state = platformImageAnimation(AsyncImagePainter.State.Success(
                result.image.asPainter(PlatformContext.INSTANCE), result)) as AsyncImagePainter.State.Success
            assertEquals(0, state.result.request.crossfadeMillis)
            val painter = assertIs<DesktopAnimatedImagePainter>(state.painter)
            withContext(Dispatchers.Main.immediate) {
                try {
                    painter.onRemembered()
                    withTimeout(5_000) { while (paintColor(painter) != Color.BLUE) delay(5) }
                    painter.onForgotten()
                    delay(150)
                    assertEquals(Color.RED, paintColor(painter))
                    painter.onRemembered()
                    withTimeout(5_000) { while (paintColor(painter) != Color.BLUE) delay(5) }
                } finally { painter.onAbandoned() }
            }
        } finally { loader.shutdown() }
    }

    private fun paintColor(painter: DesktopAnimatedImagePainter): Int {
        val bitmap = ImageBitmap(2, 2)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(2f, 2f)) {
            with(painter) { draw(size) }
        }
        return bitmap.toPixelMap()[0, 0].toArgb()
    }

    @Test fun finiteAnimationDecodesEveryFrameAndHonorsRepeatCount() = runBlocking {
        val image = assertNotNull(decodeDesktopAnimation(gif(repeats = 1)))
        assertEquals(2, image.width)
        assertEquals(2, image.height)
        assertEquals(listOf(30, 30), image.durations)
        assertEquals(Color.RED, color(image.poster))
        val frames = mutableListOf<Int>()
        withTimeout(5_000) { playDesktopAnimation(image) { frames += color(it) } }
        assertEquals(listOf(Color.RED, Color.BLUE, Color.RED, Color.BLUE), frames)
    }

    @Test fun infiniteAnimationStopsOnCancellation() = runBlocking {
        val image = assertNotNull(decodeDesktopAnimation(gif(repeats = 0)))
        assertEquals(-1, image.repetitions)
        val colors = java.util.concurrent.CopyOnWriteArrayList<Int>()
        val job = launch { playDesktopAnimation(image) { colors += color(it) } }
        withTimeout(5_000) { while (colors.size < 3) delay(10) }
        job.cancelAndJoin()
        val stoppedAt = colors.size
        delay(100)
        assertEquals(stoppedAt, colors.size)
        assertEquals(listOf(Color.RED, Color.BLUE, Color.RED), colors.take(3))
    }

    @Test fun staticImageDoesNotUseAnimationAndMalformedInputFails() {
        val bytes = ByteArrayOutputStream().apply {
            ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", this)
        }.toByteArray()
        assertNull(decodeDesktopAnimation(bytes))
        assertFailsWith<IllegalArgumentException> { decodeDesktopAnimation("invalid".encodeToByteArray()) }
    }

    private fun color(image: Image): Int = Bitmap.makeFromImage(image).use { it.getColor(0, 0) }

    // Synthetic two-frame GIF, no network, files or application credentials.
    private fun gif(repeats: Int, frames: List<BufferedImage> = listOf(java.awt.Color.RED.rgb, java.awt.Color.BLUE.rgb).map { color ->
        BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB).apply {
            for (y in 0..1) for (x in 0..1) setRGB(x, y, color)
        }
    }, disposal: List<String> = List(frames.size) { "none" }): ByteArray {
        val bytes = ByteArrayOutputStream()
        val writer = ImageIO.getImageWritersByFormatName("gif").next()
        try {
            MemoryCacheImageOutputStream(bytes).use { output ->
                writer.output = output
                writer.prepareWriteSequence(null)
                frames.forEachIndexed { index, bitmap ->
                    val metadata = writer.getDefaultImageMetadata(javax.imageio.ImageTypeSpecifier(bitmap), null)
                    val root = metadata.getAsTree("javax_imageio_gif_image_1.0") as IIOMetadataNode
                    val control = root.getElementsByTagName("GraphicControlExtension").item(0) as IIOMetadataNode
                    control.setAttribute("delayTime", "3")
                    control.setAttribute("disposalMethod", disposal[index])
                    if (index == 0) {
                        val extension = IIOMetadataNode("ApplicationExtension").apply {
                            setAttribute("applicationID", "NETSCAPE")
                            setAttribute("authenticationCode", "2.0")
                            userObject = byteArrayOf(1, repeats.toByte(), (repeats shr 8).toByte())
                        }
                        root.appendChild(IIOMetadataNode("ApplicationExtensions").apply { appendChild(extension) })
                    }
                    metadata.setFromTree("javax_imageio_gif_image_1.0", root)
                    writer.writeToSequence(IIOImage(bitmap, null, metadata), null)
                }
                writer.endWriteSequence()
            }
        } finally { writer.dispose() }
        return bytes.toByteArray()
    }
}

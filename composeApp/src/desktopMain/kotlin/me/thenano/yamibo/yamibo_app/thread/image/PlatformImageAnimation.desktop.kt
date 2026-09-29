package me.thenano.yamibo.yamibo_app.thread.image

import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.skiaCanvas
import coil3.ComponentRegistry
import coil3.ImageLoader
import coil3.compose.AsyncImagePainter
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.decode.SkiaImageDecoder
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.request.crossfade
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import me.thenano.yamibo.yamibo_app.Logger
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect

internal actual fun ComponentRegistry.Builder.addPlatformImageDecoders() {
    add(DesktopAnimatedImageDecoderFactory())
}

internal actual fun platformImageAnimation(state: AsyncImagePainter.State): AsyncImagePainter.State {
    val image = (state as? AsyncImagePainter.State.Success)?.result?.image as? DesktopAnimatedImage
        ?: return state
    // Coil 3.5's non-Android crossfade wrapper does not forward RememberObserver.onForgotten.
    // Keep the animated painter directly owned by AsyncImagePainter so removal stops playback.
    return state.copy(
        painter = DesktopAnimatedImagePainter(image),
        result = state.result.copy(request = state.result.request.newBuilder().crossfade(false).build()),
    )
}

/** Immutable cached source; each visible painter owns its playback, never all decoded frames. */
internal class DesktopAnimatedImage(
    val encoded: ByteArray,
    val poster: Image,
    val durations: List<Int>,
    val repetitions: Int,
) : coil3.Image {
    override val width get() = poster.width
    override val height get() = poster.height
    override val size get() = encoded.size.toLong() + width.toLong() * height * 4
    override val shareable = true
    override fun draw(canvas: coil3.Canvas) { canvas.drawImage(poster, 0f, 0f) }
}

internal class DesktopAnimatedImageDecoderFactory : Decoder.Factory {
    override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? {
        val peek = result.source.source().peek()
        if (!peek.request(12)) return null
        val header = peek.readByteArray(12)
        val gif = header.copyOfRange(0, 6).decodeToString() in listOf("GIF87a", "GIF89a")
        val webp = header.copyOfRange(0, 4).decodeToString() == "RIFF" &&
            header.copyOfRange(8, 12).decodeToString() == "WEBP"
        if (!gif && !webp) return null
        return object : Decoder {
            override suspend fun decode(): DecodeResult {
                val bytes = result.source.source().peek().readByteArray()
                val image = decodeDesktopAnimation(bytes)
                    ?: return SkiaImageDecoder(result.source, options).decode()
                return DecodeResult(image, isSampled = false)
            }
        }
    }
}

internal fun decodeDesktopAnimation(bytes: ByteArray): DesktopAnimatedImage? =
    Data.makeFromBytes(bytes).use { data ->
        Codec.makeFromData(data).use { codec ->
            if (codec.frameCount <= 1) return null
            val durations = codec.framesInfo.map { it.duration.coerceAtLeast(20) }
            codec.readPixels().use { bitmap ->
                DesktopAnimatedImage(bytes, Image.makeFromBitmap(bitmap), durations, codec.repetitionCount)
            }
        }
    }

// This application permits one main window/process; its visibility owns rendering work only.
internal val desktopImageAnimationVisible = MutableStateFlow(true)

internal class DesktopAnimatedImagePainter(
    private val image: DesktopAnimatedImage,
    private val visible: StateFlow<Boolean> = desktopImageAnimationVisible,
) : Painter(), RememberObserver {
    private var frame by mutableStateOf(image.poster)
    private var playback: Job? = null
    override val intrinsicSize = Size(image.width.toFloat(), image.height.toFloat())

    override fun DrawScope.onDraw() {
        drawContext.canvas.skiaCanvas.drawImageRect(frame, Rect.makeWH(size.width, size.height))
    }

    override fun onRemembered() {
        if (playback != null) return
        // AsyncImagePainter forwards its remember/forget lifecycle to this painter.
        playback = CoroutineScope(Dispatchers.Main.immediate).launch {
            visible.collectLatest { showing ->
                if (!showing) {
                    frame = image.poster
                } else try {
                    playDesktopAnimation(image) { frame = it }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    // A corrupt later frame must not terminate the reader's composition.
                    Logger.w("DesktopImage", "Animated image playback failed", error)
                }
            }
        }
    }

    override fun onForgotten() {
        playback?.cancel()
        playback = null
        frame = image.poster
    }
    override fun onAbandoned() = onForgotten()
}

/** Skia resolves frame dependencies/disposal; only a working bitmap and the displayed frame stay live. */
internal suspend fun playDesktopAnimation(image: DesktopAnimatedImage, display: (Image) -> Unit) {
    withContext(Dispatchers.Default) {
        Data.makeFromBytes(image.encoded).use { data ->
            Codec.makeFromData(data).use { codec ->
                Bitmap().use { bitmap ->
                    check(bitmap.allocPixels(codec.imageInfo))
                    var iteration = 0L
                    while (image.repetitions < 0 || iteration <= image.repetitions) {
                        for (index in image.durations.indices) {
                            ensureActive()
                            val started = System.nanoTime()
                            codec.readPixels(bitmap, index)
                            val decoded = Image.makeFromBitmap(bitmap)
                            withContext(Dispatchers.Main.immediate) { display(decoded) }
                            val elapsed = (System.nanoTime() - started) / 1_000_000
                            delay((image.durations[index] - elapsed).coerceAtLeast(1))
                        }
                        iteration++
                    }
                }
            }
        }
    }
}

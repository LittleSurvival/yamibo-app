package me.thenano.yamibo.yamibo_app.thread.reader.components.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.channels.Channel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme
import kotlin.math.roundToInt

data class ReaderPageProgress(
    val page: Int,
    val totalPages: Int,
    val fraction: Float,
)

@Composable
fun ReaderPageProgressSlideBar(
    progress: ReaderPageProgress?,
    modifier: Modifier = Modifier,
    onSeek: suspend (Float) -> Unit,
    thumbColor: androidx.compose.ui.graphics.Color? = null,
) {
    val colors = YamiboTheme.colors
    val value = progress?.fraction?.coerceIn(0f, 1f) ?: return
    val requests = remember { Channel<Float>(Channel.CONFLATED) }
    val latestSeek by rememberUpdatedState(onSeek)
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(requests) {
        for (fraction in requests) {
            latestSeek(fraction)
            withFrameNanos { }
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical))
            .width(32.dp)
            .fillMaxHeight()
            .pointerInput(Unit) {
                detectTapGestures { point ->
                    requests.trySend((point.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f))
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { point ->
                        dragFraction = (point.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                        requests.trySend(dragFraction!!)
                    },
                    onDragEnd = { dragFraction = null },
                    onDragCancel = { dragFraction = null },
                ) { change, _ ->
                    change.consume()
                    dragFraction = (change.position.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                    requests.trySend(dragFraction!!)
                }
            },
    ) {
        val thumbHeight = 24.dp
        val travel = (maxHeight - thumbHeight).coerceAtLeast(0.dp)
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(y = travel * (dragFraction ?: value))
                .width(3.dp)
                .height(thumbHeight)
                .background(thumbColor ?: colors.textDark.copy(alpha = 0.38f)),
        )
    }
}

@Composable
fun ReaderPageProgressHint(
    progress: ReaderPageProgress?,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = YamiboTheme.colors
    val value = progress?.fraction?.coerceIn(0f, 1f) ?: return
    if (!visible) return

    Text(
        text = "${progress.page}/${progress.totalPages} ${(value * 100f).roundToInt()}%",
        color = colors.textDark.copy(alpha = 0.46f),
        fontSize = 9.sp,
        lineHeight = 10.sp,
        modifier = modifier
            .background(colors.creamBackground.copy(alpha = 0.62f))
            .padding(start = 3.dp, top = 1.dp, bottom = 1.dp),
    )
}

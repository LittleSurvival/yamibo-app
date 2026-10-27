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
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.channels.Channel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme
import me.thenano.yamibo.yamibo_app.navigation.NativeNavigationBridge
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
    val latestFraction by rememberUpdatedState(value)
    val glowAlpha by animateFloatAsState(
        targetValue = if (dragFraction != null) 1f else 0f,
        animationSpec = tween(180),
        label = "readerSideSliderGlow",
    )
    val activeColor = colors.orangeAccent
    val thumbHeightPx = with(LocalDensity.current) { 24.dp.toPx() }
    val trackInsets = WindowInsets.safeDrawing.union(
        WindowInsets(top = NativeNavigationBridge.readerTrackTopInset.dp),
    ).only(WindowInsetsSides.Vertical)
    LaunchedEffect(requests) {
        for (fraction in requests) {
            latestSeek(fraction)
            withFrameNanos { }
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .windowInsetsPadding(trackInsets)
            .width(32.dp)
            .fillMaxHeight()
            .pointerInput(thumbHeightPx) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    // Moving before the timeout cancels arming, even during a slow edge swipe.
                    val cancelled = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        do {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                            val keepWaiting = event.changes.size == 1 && change != null &&
                                change.pressed && !change.isConsumed &&
                                (change.position - down.position).getDistance() <= viewConfiguration.touchSlop
                        } while (keepWaiting)
                        true
                    }
                    if (cancelled == true) return@awaitEachGesture
                    try {
                        // Arm without seeking; only movement after the long press changes progress.
                        dragFraction = latestFraction
                        if (drag(down.id) { change ->
                            change.consume()
                            dragFraction = readerTrackFraction(change.position.y, size.height.toFloat(), thumbHeightPx)
                            requests.trySend(dragFraction!!)
                        }) {
                            currentEvent.changes.forEach { it.consume() }
                        }
                    } finally {
                        dragFraction = null
                    }
                }
            }
            .drawBehind {
                if (glowAlpha > 0f) {
                    drawRect(
                        brush = Brush.horizontalGradient(
                            listOf(Color.Transparent, activeColor.copy(alpha = 0.32f * glowAlpha)),
                        ),
                    )
                }
            },
    ) {
        val thumbHeight = 24.dp.coerceAtMost(maxHeight).coerceAtLeast(0.dp)
        val travel = (maxHeight - thumbHeight).coerceAtLeast(0.dp)
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(y = travel * (dragFraction ?: value))
                .width((3 + 2 * glowAlpha).dp)
                .height(thumbHeight)
                .background(
                    lerp(
                        thumbColor ?: colors.textDark.copy(alpha = 0.38f),
                        activeColor,
                        glowAlpha,
                    ),
                ),
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

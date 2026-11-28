package me.thenano.yamibo.yamibo_app.thread.detail.novel.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.thenano.yamibo.yamibo_app.components.feedback.YamiboDetailedErrorContent
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme

/** Loading skeleton with shimmer */
@Composable
internal fun ThreadLoadingSkeleton() {
    val colors = YamiboTheme.colors
    val shimmerColor = colors.brownPrimary.copy(alpha = 0.12f)

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
        val shimmerAnim = rememberInfiniteTransition(label = "thread_shimmer")
        val shimmerX = shimmerAnim.animateFloat(
            initialValue = -widthPx,
            targetValue = widthPx * 2f,
            animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)),
            label = "thread_shimmer_x"
        )

        Column(
            modifier = Modifier.fillMaxSize().background(colors.creamBackground)
                .verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Spacer(Modifier.fillMaxWidth().height(180.dp).shimmer(shimmerX, shimmerColor, 20.dp))
            Spacer(Modifier.fillMaxWidth().height(120.dp).shimmer(shimmerX, shimmerColor, 16.dp))
            repeat(4) {
                Spacer(Modifier.fillMaxWidth().height(48.dp).shimmer(shimmerX, shimmerColor, 12.dp))
            }
        }
    }
}

/** Shimmer modifier */
private fun Modifier.shimmer(translateX: State<Float>, baseColor: Color, radius: Dp): Modifier =
    drawWithCache {
        val corner = CornerRadius(radius.toPx())
        val brush =
            Brush.linearGradient(
                colors =
                    listOf(
                        baseColor.copy(alpha = 0.25f),
                        baseColor.copy(alpha = 0.50f),
                        baseColor.copy(alpha = 0.25f),
                    ),
                start = Offset.Zero,
                end = Offset(size.width, size.height)
            )
        onDrawBehind {
            val x = translateX.value
            translate(left = x) {
                drawRoundRect(brush, topLeft = Offset(-x, 0f), size = size, cornerRadius = corner)
            }
        }
    }

/** Error content */
@Composable
internal fun ThreadErrorContent(message: String, onRetry: () -> Unit) {
    val colors = YamiboTheme.colors
    YamiboDetailedErrorContent(
        message = message,
        onRetry = onRetry,
        titleColor = colors.textStrong,
        retryContentColor = Color.White,
        retryHorizontalPadding = 24.dp,
    )
}

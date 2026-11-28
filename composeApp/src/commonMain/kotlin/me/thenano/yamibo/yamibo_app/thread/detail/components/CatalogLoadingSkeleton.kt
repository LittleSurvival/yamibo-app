package me.thenano.yamibo.yamibo_app.thread.detail.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import me.thenano.yamibo.yamibo_app.components.feedback.shimmer
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme

/** Loading skeleton */
@Composable
fun CatalogLoadingSkeleton() {
    val colors = YamiboTheme.colors
    val shimmerColor = colors.brownLight

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
        val shimmerAnim = rememberInfiniteTransition(label = "tag_shimmer")
        val shimmerX = shimmerAnim.animateFloat(
            initialValue = -widthPx,
            targetValue = widthPx * 2f,
            animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)),
            label = "tag_shimmer_x"
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize().background(colors.creamBackground),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header skeleton
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .shimmer(shimmerColor, shimmerX)
                )
            }
            // Threads skeletons
            items(6) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .shimmer(shimmerColor, shimmerX)
                )
            }
        }
    }
}

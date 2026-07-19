package me.thenano.yamibo.yamibo_app.components.controls

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme
import kotlin.math.roundToInt

@Composable
fun YamiboVerticalScrollbar(
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
) {
    val colors = YamiboTheme.colors
    if (scrollState.maxValue > 0) {
        val density = LocalDensity.current
        val viewportHeight = with(density) { scrollState.viewportSize.toDp() }
        if (viewportHeight > 0.dp) {
            val maxScroll = with(density) { scrollState.maxValue.toDp() }
            val totalHeight = viewportHeight + maxScroll
            val thumbHeight = (viewportHeight * (viewportHeight / totalHeight)).coerceAtLeast(24.dp)
            val thumbTravelPx = with(density) { (viewportHeight - thumbHeight).roundToPx() }
            val maxScrollValue = scrollState.maxValue

            Box(
                modifier = modifier
                    .fillMaxHeight()
                    .width(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .offset {
                            val scrollFraction = scrollState.value.toFloat() / maxScrollValue
                            IntOffset(
                                x = 0,
                                y = (thumbTravelPx * scrollFraction).roundToInt(),
                            )
                        }
                        .width(4.dp)
                        .height(thumbHeight)
                        .background(
                            color = colors.brownPrimary.copy(alpha = 0.4f),
                            shape = RoundedCornerShape(2.dp)
                        )
                )
            }
        }
    }
}

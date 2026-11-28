package me.thenano.yamibo.yamibo_app.components.feedback

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme

/**
 * Centered loading state for Yamibo screens.
 *
 * Use this in page-level state switches when data is being fetched, including
 * ForumPage, UserSpace, BlogReader, PrivateMessage, search sheets, and history.
 * It intentionally only renders a spinner so feature pages can decide their own
 * skeletons when they need richer placeholders.
 */
@Composable
fun YamiboLoadingContent(modifier: Modifier = Modifier) {
    val colors = YamiboTheme.colors
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = colors.brownDeep)
    }
}

internal fun Modifier.shimmer(baseColor: Color, translateX: State<Float>? = null): Modifier =
    drawWithCache {
        val edge = baseColor.copy(alpha = 0.25f)
        val colors = buildList {
            add(edge)
            add(baseColor.copy(alpha = 0.50f))
            add(edge)
        }
        val brush = Brush.linearGradient(colors, start = Offset.Zero, end = Offset(size.width, size.height))
        onDrawBehind {
            val x = translateX?.value ?: 0f
            translate(left = x) {
                drawRect(brush, topLeft = Offset(-x, 0f), size = size)
            }
        }
    }

package me.thenano.yamibo.yamibo_app.thread.reader.components.manga

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import kotlin.math.min

/** Bounds for a centered ContentScale.Fit image, excluding its letterbox margins. */
internal fun imageZoomBounds(viewport: IntSize, image: Size?, scale: Float): Offset {
    if (viewport.width <= 0 || viewport.height <= 0 || !scale.isFinite() || scale <= 1f) return Offset.Zero
    val width = viewport.width.toFloat()
    val height = viewport.height.toFloat()
    val valid = image?.takeIf { it.width.isFinite() && it.height.isFinite() && it.width > 0f && it.height > 0f }
    val fit = valid?.let { min(width / it.width, height / it.height) }
    val drawnWidth = if (valid != null && fit != null) valid.width * fit else width
    val drawnHeight = if (valid != null && fit != null) valid.height * fit else height
    return Offset(((drawnWidth * scale - width) / 2f).coerceAtLeast(0f),
        ((drawnHeight * scale - height) / 2f).coerceAtLeast(0f))
}

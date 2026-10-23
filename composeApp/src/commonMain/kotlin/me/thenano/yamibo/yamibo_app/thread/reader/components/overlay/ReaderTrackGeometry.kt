package me.thenano.yamibo.yamibo_app.thread.reader.components.overlay

/** Map the thumb center onto its actual travel, including both endpoints. */
internal fun readerTrackFraction(pointerY: Float, trackHeight: Float, desiredThumbHeight: Float): Float {
    val height = trackHeight.coerceAtLeast(0f)
    val thumb = desiredThumbHeight.coerceIn(0f, height)
    val travel = height - thumb
    if (travel <= 0f) return 0f
    return ((pointerY - thumb / 2f) / travel).coerceIn(0f, 1f)
}

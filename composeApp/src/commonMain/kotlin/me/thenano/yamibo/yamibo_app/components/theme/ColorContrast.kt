package me.thenano.yamibo.yamibo_app.components.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.pow

internal fun Color.contrastAgainst(other: Color): Double {
    val lighter = maxOf(relativeLuminance(), other.relativeLuminance())
    val darker = minOf(relativeLuminance(), other.relativeLuminance())
    return (lighter + 0.05) / (darker + 0.05)
}

private fun Color.relativeLuminance(): Double {
    fun channel(value: Float): Double {
        val normalized = value.toDouble()
        return if (normalized <= 0.03928) {
            normalized / 12.92
        } else {
            ((normalized + 0.055) / 1.055).pow(2.4)
        }
    }
    return 0.2126 * channel(red) + 0.7152 * channel(green) + 0.0722 * channel(blue)
}

package me.thenano.yamibo.yamibo_app.components.font

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import java.io.File
import me.thenano.yamibo.yamibo_app.Logger
import me.thenano.yamibo.yamibo_app.repository.font.LoadedFont

internal actual fun platformFontFamily(font: LoadedFont): FontFamily? {
    val file = File(font.platformPath)
    if (!file.exists()) return null
    return runCatching { FontFamily(Font(file)) }
        .onFailure { Logger.w("FontFamilyResolver", "Failed to resolve font family id=${font.id}", it) }
        .getOrNull()
}

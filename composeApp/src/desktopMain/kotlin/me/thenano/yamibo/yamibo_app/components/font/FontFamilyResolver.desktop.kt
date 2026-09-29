package me.thenano.yamibo.yamibo_app.components.font

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import me.thenano.yamibo.yamibo_app.repository.font.LoadedFont
import java.io.File

internal actual fun platformFontFamily(font: LoadedFont): FontFamily? =
    runCatching { FontFamily(Font(File(font.platformPath))) }.getOrNull()

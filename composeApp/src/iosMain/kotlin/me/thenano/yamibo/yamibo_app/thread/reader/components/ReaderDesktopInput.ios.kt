package me.thenano.yamibo.yamibo_app.thread.reader.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
internal actual fun readerDesktopInput(
    enabled: Boolean,
    paged: Boolean,
    rightToLeft: Boolean,
    onMove: (Int) -> Unit,
): Modifier = Modifier

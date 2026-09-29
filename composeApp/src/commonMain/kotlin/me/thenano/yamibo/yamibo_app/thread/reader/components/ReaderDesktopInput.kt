package me.thenano.yamibo.yamibo_app.thread.reader.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Desktop paging uses the same reader transitions as touch; mobile input is unchanged. */
@Composable
internal expect fun readerDesktopInput(
    enabled: Boolean,
    paged: Boolean,
    rightToLeft: Boolean,
    onMove: (Int) -> Unit,
): Modifier

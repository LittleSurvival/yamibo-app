package me.thenano.yamibo.yamibo_app.thread.reader.components

import androidx.compose.foundation.focusable
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import kotlin.math.abs

internal fun desktopReaderKeyDelta(key: Key, rightToLeft: Boolean, shift: Boolean, modified: Boolean): Int? {
    if (modified) return null
    return when (key) {
        Key.PageDown, Key.DirectionDown -> 1
        Key.PageUp, Key.DirectionUp -> -1
        Key.DirectionRight -> if (rightToLeft) -1 else 1
        Key.DirectionLeft -> if (rightToLeft) 1 else -1
        Key.Spacebar -> if (shift) -1 else 1
        else -> null
    }
}

@Composable
@OptIn(ExperimentalComposeUiApi::class)
internal actual fun readerDesktopInput(enabled: Boolean, paged: Boolean, rightToLeft: Boolean,
    onMove: (Int) -> Unit): Modifier {
    val requester = remember { FocusRequester() }
    var readerFocused by remember { mutableStateOf(false) }
    val move by rememberUpdatedState(onMove)
    var wheel by remember { mutableStateOf(0f) }
    var lastWheelPage by remember { mutableStateOf(0L) }
    LaunchedEffect(enabled) {
        wheel = 0f
        if (enabled) requester.requestFocus()
    }
    return Modifier
        .focusRequester(requester)
        .onFocusChanged { readerFocused = it.isFocused }
        .onKeyEvent { event ->
            // Never intercept descendant editor/selection focus or IME shortcuts.
            if (!enabled || !readerFocused || event.type != KeyEventType.KeyUp) false
            else desktopReaderKeyDelta(event.key, rightToLeft, event.isShiftPressed,
                event.isCtrlPressed || event.isAltPressed || event.isMetaPressed)?.let {
                move(it); true
            } ?: false
        }
        .focusable(enabled)
        .onPointerEvent(PointerEventType.Scroll) { event ->
            if (enabled && paged && !event.keyboardModifiers.isCtrlPressed &&
                !event.keyboardModifiers.isAltPressed && !event.keyboardModifiers.isMetaPressed) {
                val delta = event.changes.sumOf { it.scrollDelta.y.toDouble() }.toFloat()
                if (delta != 0f) {
                    val now = System.nanoTime()
                    if (now - lastWheelPage >= 220_000_000L) {
                        wheel += delta
                        if (abs(wheel) >= 1f) {
                            move(if (wheel > 0f) 1 else -1)
                            wheel = 0f
                            lastWheelPage = now
                        }
                    }
                    event.changes.forEach { it.consume() }
                }
            }
        }
}

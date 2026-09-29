package me.thenano.yamibo.yamibo_app.thread.reader.components

import androidx.compose.ui.input.key.Key
import kotlin.test.*

class DesktopReaderInputTest {
    @Test fun horizontalArrowsFollowReadingDirectionButPageKeysDoNot() {
        assertEquals(1, desktopReaderKeyDelta(Key.DirectionRight, false, false, false))
        assertEquals(-1, desktopReaderKeyDelta(Key.DirectionRight, true, false, false))
        assertEquals(1, desktopReaderKeyDelta(Key.DirectionLeft, true, false, false))
        assertEquals(1, desktopReaderKeyDelta(Key.PageDown, true, false, false))
        assertEquals(-1, desktopReaderKeyDelta(Key.PageUp, false, false, false))
        assertEquals(1, desktopReaderKeyDelta(Key.Spacebar, false, false, false))
        assertEquals(-1, desktopReaderKeyDelta(Key.Spacebar, false, true, false))
    }
    @Test fun editorAndSystemShortcutsAreNotReaderCommands() {
        assertNull(desktopReaderKeyDelta(Key.A, false, false, false))
        assertNull(desktopReaderKeyDelta(Key.DirectionLeft, false, false, true))
        assertNull(desktopReaderKeyDelta(Key.Spacebar, false, false, true))
        assertNull(desktopReaderKeyDelta(Key.Enter, false, false, false))
    }
}

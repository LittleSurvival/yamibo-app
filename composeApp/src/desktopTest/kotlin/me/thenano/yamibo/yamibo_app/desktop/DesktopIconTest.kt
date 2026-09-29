package me.thenano.yamibo.yamibo_app.desktop

import kotlin.test.Test
import kotlin.test.assertTrue

class DesktopIconTest {
    @Test
    fun bundledApplicationIconIsReadable() {
        assertTrue(DesktopIcon.image.width >= 128)
        assertTrue(DesktopIcon.image.height >= 128)
    }
}

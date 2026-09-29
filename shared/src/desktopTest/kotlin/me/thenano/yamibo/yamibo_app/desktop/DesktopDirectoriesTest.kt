package me.thenano.yamibo.yamibo_app.desktop

import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopDirectoriesTest {
    @Test
    fun platformDefaultsRemainUnderUserHome() {
        val home = Paths.get("test-user").toAbsolutePath()
        assertEquals(home.resolve("AppData/Local/yamibo-app"), DesktopDirectories.dataRoot("Windows 11", home.toString(), emptyMap()))
        assertEquals(home.resolve("Library/Application Support/yamibo-app"), DesktopDirectories.dataRoot("Mac OS X", home.toString(), emptyMap()))
        assertEquals(home.resolve(".local/share/yamibo-app"), DesktopDirectories.dataRoot("Linux", home.toString(), mapOf("XDG_DATA_HOME" to "relative")))
    }
}

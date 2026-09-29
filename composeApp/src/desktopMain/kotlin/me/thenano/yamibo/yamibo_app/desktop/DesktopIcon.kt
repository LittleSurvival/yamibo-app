package me.thenano.yamibo.yamibo_app.desktop

import java.awt.Taskbar
import java.awt.Window
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/** Shared by the main window and tray, using the existing Android artwork. */
internal object DesktopIcon {
    val image: BufferedImage by lazy {
        checkNotNull(DesktopIcon::class.java.getResourceAsStream("/yamibo-icon.png")) {
            "Missing desktop application icon"
        }.use { checkNotNull(ImageIO.read(it)) { "Invalid desktop application icon" } }
    }

    fun applyTo(window: Window) {
        window.setIconImage(image)
        // macOS uses the process-level Dock icon instead of the window icon.
        if (Taskbar.isTaskbarSupported()) {
            val taskbar = Taskbar.getTaskbar()
            if (taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) {
                taskbar.iconImage = image
            }
        }
    }
}

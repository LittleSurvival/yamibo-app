package me.thenano.yamibo.yamibo_app.util

import coil3.PlatformContext
import javax.swing.*
import java.awt.Window

actual fun showToast(context: PlatformContext, message: String) {
    SwingUtilities.invokeLater {
        val owner = Window.getWindows().firstOrNull { it.isActive }
        val toast = JWindow(owner).apply {
            add(JLabel(message).apply { border = BorderFactory.createEmptyBorder(12, 18, 12, 18) })
            pack()
            setLocationRelativeTo(owner)
            isVisible = true
        }
        Timer(2500) { toast.dispose() }.apply { isRepeats = false; start() }
    }
}

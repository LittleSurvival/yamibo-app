package me.thenano.yamibo.yamibo_app.util

import java.io.File
import java.awt.KeyboardFocusManager
import java.nio.file.Files
import java.nio.file.StandardCopyOption.*
import javax.swing.JFileChooser
import javax.swing.JOptionPane

internal fun chooseDesktopFile(save: Boolean = false, directory: Boolean = false, name: String? = null): File? {
    val owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
    if (directory && System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        return try {
            chooseWindowsFolder(owner)
        } catch (_: Exception) {
            JOptionPane.showMessageDialog(owner, "無法開啟系統資料夾選擇器，請稍後再試。", "百合會論壇",
                JOptionPane.ERROR_MESSAGE)
            null
        }
    }
    val chooser = JFileChooser().apply {
        fileSelectionMode = if (directory) JFileChooser.DIRECTORIES_ONLY else JFileChooser.FILES_ONLY
        name?.let { selectedFile = File(it) }
    }
    val result = if (save) chooser.showSaveDialog(owner) else chooser.showOpenDialog(owner)
    if (result != JFileChooser.APPROVE_OPTION) return null
    val file = chooser.selectedFile
    if (save && file.exists() && JOptionPane.showConfirmDialog(owner, "檔案已存在，要取代嗎？", "百合會論壇",
            JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return null
    return file
}

internal fun writeDesktopFile(file: File, bytes: ByteArray) {
    val target = file.toPath().toAbsolutePath()
    val temp = Files.createTempFile(target.parent, ".yamibo-", ".tmp")
    try {
        Files.write(temp, bytes)
        try { Files.move(temp, target, ATOMIC_MOVE, REPLACE_EXISTING) }
        catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temp, target, REPLACE_EXISTING) }
    } finally { Files.deleteIfExists(temp) }
}

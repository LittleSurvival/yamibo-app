package me.thenano.yamibo.yamibo_app.util

import coil3.PlatformContext
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

actual fun shareText(context: PlatformContext, text: String, title: String?) {
    try {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        showToast(context, "已複製，可貼上分享")
    } catch (_: Exception) { showToast(context, "無法存取剪貼簿") }
}

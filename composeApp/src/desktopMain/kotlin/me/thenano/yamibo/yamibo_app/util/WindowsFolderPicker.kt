package me.thenano.yamibo.yamibo_app.util

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.COM.COMUtils
import com.sun.jna.platform.win32.COM.Unknown
import com.sun.jna.platform.win32.Guid.CLSID
import com.sun.jna.platform.win32.Guid.GUID
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinNT.HRESULT
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import java.awt.EventQueue
import java.awt.Toolkit
import java.awt.Window
import java.io.File

/** Windows Common Item Dialog in folder mode; COM objects stay on their own STA thread. */
internal fun chooseWindowsFolder(owner: Window?): File? {
    check(EventQueue.isDispatchThread())
    val handle = owner?.takeIf { it.isDisplayable }?.let { HWND(Native.getComponentPointer(it)) }
    val loop = Toolkit.getDefaultToolkit().systemEventQueue.createSecondaryLoop()
    var result: Result<File?>? = null
    // The secondary event loop keeps Compose/Swing painting while the modal native dialog is open.
    val wasEnabled = owner?.isEnabled == true
    owner?.isEnabled = false
    try {
        Thread({
            val picked = runCatching { showWindowsFolderDialog(handle) }
            EventQueue.invokeLater {
                result = picked
                loop.exit()
            }
        }, "yamibo-folder-picker").apply { isDaemon = true }.start()
        check(loop.enter()) { "Cannot start folder dialog event loop" }
        return requireNotNull(result).getOrThrow()
    } finally {
        owner?.isEnabled = wasEnabled
        owner?.toFront()
    }
}

private fun showWindowsFolderDialog(owner: HWND?): File? {
    val ole = Ole32.INSTANCE
    COMUtils.checkRC(ole.CoInitializeEx(null, Ole32.COINIT_APARTMENTTHREADED or Ole32.COINIT_DISABLE_OLE1DDE))
    try {
        val output = PointerByReference()
        COMUtils.checkRC(ole.CoCreateInstance(
            CLSID("{DC1C5A9C-E88A-4DDE-A5A1-60F82A20AEF7}"), null, 1,
            GUID("{D57C7288-D4AD-4768-BE02-9D969532D960}"), output))
        val dialog = ShellInterface(output.value)
        try {
            val options = IntByReference()
            dialog.checked(10, options) // IFileDialog.GetOptions
            dialog.checked(9, options.value or 0x20 or 0x40 or 0x800) // PICKFOLDERS | FORCEFILESYSTEM | PATHMUSTEXIST
            dialog.checked(17, WString("選擇下載與備份資料夾"))
            val shown = dialog.call(3, owner) // IModalWindow.Show
            if (shown.toInt() == 0x800704C7.toInt()) return null // User cancellation, not an error.
            COMUtils.checkRC(shown)
            val selected = PointerByReference()
            dialog.checked(20, selected) // IFileDialog.GetResult
            val item = ShellInterface(selected.value)
            try {
                val path = PointerByReference()
                item.checked(5, 0x80058000.toInt(), path) // IShellItem.GetDisplayName(SIGDN_FILESYSPATH)
                return try { File(path.value.getWideString(0)) }
                finally { ole.CoTaskMemFree(path.value) }
            } finally { item.Release() }
        } finally { dialog.Release() }
    } finally { ole.CoUninitialize() }
}

/** Vtable slots follow shobjidl_core.h; Unknown owns only the reference released by the caller. */
private class ShellInterface(pointer: Pointer) : Unknown(pointer) {
    fun call(slot: Int, vararg args: Any?): HRESULT =
        _invokeNativeObject(slot, arrayOf(pointer, *args), HRESULT::class.java) as HRESULT
    fun checked(slot: Int, vararg args: Any?) = COMUtils.checkRC(call(slot, *args))
}

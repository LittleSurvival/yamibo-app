package me.thenano.yamibo.yamibo_app

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.key.*
import androidx.compose.ui.window.*
import java.awt.*
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption.*
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import me.thenano.yamibo.yamibo_app.desktop.*
import me.thenano.yamibo.yamibo_app.notification.requestOpenMessageCenterFromNotification
import me.thenano.yamibo.yamibo_app.repository.notification.MessageNotificationGateway
import me.thenano.yamibo.yamibo_app.store.DesktopCookieStore
import me.thenano.yamibo.yamibo_app.store.DesktopSecretStore
import me.thenano.yamibo.yamibo_app.store.settings.DesktopSettingsStore
import me.thenano.yamibo.yamibo_app.webview.DesktopBrowserRuntime

fun main() {
    val root = try { DesktopDirectories.ensureDataRoot() } catch (_: Exception) {
        JOptionPane.showMessageDialog(null, "無法開啟桌面資料目錄，請檢查磁碟權限。", "Yamibo", JOptionPane.ERROR_MESSAGE)
        return
    }
    val instanceChannel = try { FileChannel.open(root.resolve("desktop.lock"), CREATE, WRITE) }
    catch (_: Exception) {
        JOptionPane.showMessageDialog(null, "無法開啟程序鎖定檔，請檢查桌面資料目錄的權限。", "Yamibo", JOptionPane.ERROR_MESSAGE)
        return
    }
    instanceChannel.use { channel ->
        val lock = try { channel.tryLock() } catch (_: Exception) {
            JOptionPane.showMessageDialog(null, "無法取得程序鎖定，請確認沒有其他 Yamibo 程序使用此資料目錄。", "Yamibo", JOptionPane.ERROR_MESSAGE)
            return
        }
        if (lock == null) {
            JOptionPane.showMessageDialog(null, "Yamibo 已在執行，請從工作列或系統匣開啟。", "Yamibo", JOptionPane.INFORMATION_MESSAGE)
            return
        }
        lock.use {
            val settings = try {
                DesktopCookieStore(DesktopSecretStore()).load()
                DesktopSettingsStore(root.resolve("settings.properties"))
            } catch (_: Exception) {
                JOptionPane.showMessageDialog(null, "無法讀取設定或安全憑證。請解鎖系統金鑰儲存並重試；既有資料未被重設。", "Yamibo", JOptionPane.ERROR_MESSAGE)
                return
            }
            application {
                val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main) }
                var visible by remember { mutableStateOf(true) }
                var focused by remember { mutableStateOf(false) }
                var exiting by remember { mutableStateOf(false) }
                var tray by remember { mutableStateOf<TrayIcon?>(null) }
                val state = rememberWindowState(
                    width = settings.getInt("desktop.width", 1100).coerceIn(480, 2000).dp,
                    height = settings.getInt("desktop.height", 800).coerceIn(480, 1600).dp,
                    placement = if (settings.getBoolean("desktop.maximized", false)) WindowPlacement.Maximized else WindowPlacement.Floating,
                )
                LaunchedEffect(state) {
                    var writeFailureReported = false
                    snapshotFlow { state.size to state.placement }.collectLatest { (size, placement) ->
                        delay(500)
                        try { withContext(Dispatchers.IO) {
                            settings.putBoolean("desktop.maximized", placement == WindowPlacement.Maximized)
                            if (placement == WindowPlacement.Floating) {
                                settings.putInt("desktop.width", size.width.value.toInt())
                                settings.putInt("desktop.height", size.height.value.toInt())
                            }
                        } } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) {
                            if (!writeFailureReported) {
                                writeFailureReported = true
                                JOptionPane.showMessageDialog(null, "無法儲存視窗設定，請檢查磁碟空間與權限。程式仍可繼續使用。", "Yamibo", JOptionPane.WARNING_MESSAGE)
                            }
                        }
                    }
                }
                fun exit() {
                    if (exiting) return
                    exiting = true
                    CoroutineScope(Dispatchers.Main).launch {
                        try {
                            scope.coroutineContext[Job]?.cancelAndJoin()
                            DesktopBrowserRuntime.close()
                        } finally { exitApplication() }
                    }
                }
                fun showWindow() { visible = true; state.isMinimized = false }
                fun notify(title: String, body: String) {
                    SwingUtilities.invokeLater { tray?.displayMessage(title, body, TrayIcon.MessageType.INFO) }
                }
                DisposableEffect(Unit) {
                    val icon = if (SystemTray.isSupported()) try {
                        TrayIcon(DesktopIcon.image, "Yamibo").apply {
                            isImageAutoSize = true
                            popupMenu = PopupMenu().apply {
                                add(MenuItem("開啟 Yamibo").apply { addActionListener { showWindow() } })
                                add(MenuItem("訊息中心").apply { addActionListener {
                                    showWindow()
                                    requestOpenMessageCenterFromNotification()
                                } })
                                addSeparator()
                                add(MenuItem("退出").apply { addActionListener { exit() } })
                            }
                            addActionListener { showWindow() }
                            SystemTray.getSystemTray().add(this)
                        }
                    } catch (_: Exception) { null } else null
                    tray = icon
                    onDispose {
                        icon?.let { SystemTray.getSystemTray().remove(it) }
                        scope.cancel()
                    }
                }
                val gateway = remember {
                    object : MessageNotificationGateway {
                        override suspend fun showMessageNotification(): Boolean = withContext(Dispatchers.Main) {
                            if (tray == null) return@withContext false
                            notify("百合會新訊息", "你有新訊息，請開啟訊息中心查看。")
                            true
                        }
                        override suspend fun dismissMessageNotification() {
                            // AWT balloons expire under OS control, not as retained notification records.
                        }
                    }
                }
                SideEffect {
                    me.thenano.yamibo.yamibo_app.thread.image.desktopImageAnimationVisible.value =
                        visible && !state.isMinimized && !exiting
                }
                Window(
                    onCloseRequest = { if (tray != null) visible = false else state.isMinimized = true },
                    state = state, visible = visible, title = "百合會論壇", resizable = true,
                    onPreviewKeyEvent = { event ->
                        if (event.type == KeyEventType.KeyUp && event.key == Key.Q &&
                            (event.isCtrlPressed || event.isMetaPressed) && event.isShiftPressed) {
                            exit()
                            true
                        } else false
                    },
                ) {
                    DisposableEffect(window) {
                        DesktopIcon.applyTo(window)
                        window.minimumSize = Dimension(480, 480)
                        val listener = object : WindowAdapter() {
                            override fun windowGainedFocus(e: WindowEvent) { focused = true }
                            override fun windowLostFocus(e: WindowEvent) { focused = false }
                        }
                        window.addWindowFocusListener(listener)
                        onDispose { window.removeWindowFocusListener(listener) }
                    }
                    DesktopAppContent(scope, visible && focused && !exiting, visible, tray != null, ::notify, gateway, settings)
                }
            }
        }
    }
}

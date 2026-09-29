package me.thenano.yamibo.yamibo_app.webview

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.friwi.jcefmaven.CefAppBuilder
import me.friwi.jcefmaven.MavenCefAppHandlerAdapter
import me.thenano.yamibo.yamibo_app.desktop.DesktopDirectories
import org.cef.CefApp
import org.cef.CefSettings.LogSeverity

/** One native runtime, independent ephemeral contexts per browser. No browser profile is persisted. */
internal object DesktopBrowserRuntime {
    private val lock = Mutex()
    private var app: CefApp? = null

    suspend fun get(): CefApp = lock.withLock {
        app ?: withContext(Dispatchers.IO) {
            CefAppBuilder().apply {
                setInstallDir(DesktopDirectories.ensureDataRoot().resolve("chromium-146.0.10").toFile())
                cefSettings.windowless_rendering_enabled = false
                cefSettings.log_severity = LogSeverity.LOGSEVERITY_DISABLE
                // Use the builder handler for macOS initialization; do not call CefApp.addAppHandler.
                setAppHandler(object : MavenCefAppHandlerAdapter() {})
            }.build()
        }.also { app = it }
    }

    fun close() {
        app?.dispose()
        app = null
    }
}

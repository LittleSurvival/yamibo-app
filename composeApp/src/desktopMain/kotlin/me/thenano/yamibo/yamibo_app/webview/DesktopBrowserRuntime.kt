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
    private const val BundleName = "chromium-146.0.10"
    fun bundledDirectory(): java.io.File? = System.getProperty("compose.application.resources.dir")
        ?.let { java.io.File(it, BundleName) }

    fun prepareBundle(directory: java.io.File) {
        CefAppBuilder().apply { setInstallDir(directory) }.install()
    }
    private val lock = Mutex()
    private var app: CefApp? = null

    suspend fun get(): CefApp = lock.withLock {
        app ?: withContext(Dispatchers.IO) {
            CefAppBuilder().apply {
                val bundled = bundledDirectory()
                if (bundled != null) {
                    check(bundled.isDirectory) { "安裝包缺少瀏覽器元件，請重新安裝。" }
                    setInstallDir(bundled)
                    setSkipInstallation(true)
                } else setInstallDir(DesktopDirectories.ensureDataRoot().resolve(BundleName).toFile())
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

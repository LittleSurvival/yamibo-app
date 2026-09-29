package me.thenano.yamibo.yamibo_app.webview

import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlinx.coroutines.future.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import me.thenano.yamibo.yamibo_app.util.auth.parseCookieStringToMap
import org.cef.CefApp
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.browser.CefRequestContext
import org.cef.handler.*
import org.cef.network.CefRequest

/** Never shares the global cookie manager. CDP reads HttpOnly cookies within this browser's context. */
internal class DesktopBrowser(
    app: CefApp,
    private val trustedOnly: Boolean,
    private val onTitle: (String) -> Unit = {},
    private val onUrl: (String) -> Unit = {},
    private val onLoading: (Boolean) -> Unit = {},
    private val onFinished: (String) -> Unit = {},
    private val onError: (String?, String) -> Unit = { _, _ -> },
    private val overrideNavigation: (String) -> Boolean = { false },
) : AutoCloseable {
    private val client = app.createClient()
    private val context = CefRequestContext.createContext(null)
    private val created = java.util.concurrent.CompletableFuture<Unit>()
    @Volatile private var closed = false
    val browser: CefBrowser
    val component: JPanel

    init {
        client.addLifeSpanHandler(object : CefLifeSpanHandlerAdapter() {
            override fun onAfterCreated(browser: CefBrowser) { created.complete(Unit) }
            override fun onBeforePopup(browser: CefBrowser, frame: CefFrame, targetUrl: String, name: String): Boolean {
                if (allowed(targetUrl) && !shouldOverride(targetUrl)) browser.loadURL(targetUrl)
                return true
            }
            override fun onBeforeClose(browser: CefBrowser) {
                context.dispose()
                client.dispose()
            }
        })
        client.addRequestHandler(object : CefRequestHandlerAdapter() {
            override fun onBeforeBrowse(browser: CefBrowser, frame: CefFrame, request: CefRequest,
                userGesture: Boolean, redirect: Boolean): Boolean {
                if (request.url == "about:blank") return false
                if (!isAllowedDesktopBrowserNavigation(request.url, trustedOnly, frame.isMain)) return true
                return frame.isMain && shouldOverride(request.url)
            }
            override fun onOpenURLFromTab(browser: CefBrowser, frame: CefFrame, targetUrl: String, userGesture: Boolean): Boolean {
                if (allowed(targetUrl) && !shouldOverride(targetUrl)) browser.loadURL(targetUrl)
                return true
            }
        })
        client.addDisplayHandler(object : CefDisplayHandlerAdapter() {
            override fun onTitleChange(browser: CefBrowser, title: String) = deliver { onTitle(title) }
            override fun onAddressChange(browser: CefBrowser, frame: CefFrame, url: String) {
                if (frame.isMain && url != "about:blank") deliver { onUrl(url) }
            }
            // Web content may contain credentials; do not send its console messages to native logs.
            override fun onConsoleMessage(browser: CefBrowser, level: org.cef.CefSettings.LogSeverity,
                message: String, source: String, line: Int): Boolean = true
        })
        client.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadingStateChange(browser: CefBrowser, loading: Boolean, back: Boolean, forward: Boolean) =
                deliver { onLoading(loading) }
            override fun onLoadEnd(browser: CefBrowser, frame: CefFrame, status: Int) {
                val url = frame.url
                if (frame.isMain && url != "about:blank") deliver { onFinished(url) }
            }
            override fun onLoadError(browser: CefBrowser, frame: CefFrame, code: CefLoadHandler.ErrorCode,
                text: String, url: String) {
                if (frame.isMain && code != CefLoadHandler.ErrorCode.ERR_ABORTED) {
                    deliver { onError(url, "網頁載入失敗：${code.name}") }
                }
            }
        })
        browser = client.createBrowser("about:blank", false, false, context)
        component = JPanel(BorderLayout()).apply { add(browser.uiComponent, BorderLayout.CENTER) }
        browser.createImmediately()
    }

    suspend fun navigate(url: String, cookieHeader: String = "", userAgent: String? = desktopBrowserUserAgent(url)) {
        require(allowed(url)) { "不支援的網頁位址" }
        withTimeout(30_000) { created.await() }
        userAgent?.let { command("Network.setUserAgentOverride", buildJsonObject { put("userAgent", it) }) }
        if (cookieHeader.isNotBlank()) {
            require(isTrustedDesktopBrowserUrl(url)) { "登入資料僅限百合會網站" }
            val cookies = parseCookieStringToMap(cookieHeader)
            command("Network.setCookies", buildJsonObject {
                putJsonArray("cookies") {
                    cookies.forEach { (name, value) -> add(buildJsonObject {
                        put("name", name); put("value", value); put("url", "https://bbs.yamibo.com/")
                        put("path", "/"); put("secure", true)
                        put("httpOnly", name != "nox_jst_v1")
                    }) }
                }
            })
        }
        if (!closed) browser.loadURL(url)
    }

    suspend fun cookies(): String {
        val result = command("Network.getCookies", buildJsonObject {
            putJsonArray("urls") { add("https://bbs.yamibo.com/") }
        })
        return result["cookies"]?.jsonArray.orEmpty().mapNotNull { item ->
            val cookie = item.jsonObject
            val domain = cookie["domain"]?.jsonPrimitive?.content?.removePrefix(".")
            if (domain != "bbs.yamibo.com" && domain != "yamibo.com") null
            else "${cookie.getValue("name").jsonPrimitive.content}=${cookie.getValue("value").jsonPrimitive.content}"
        }.joinToString("; ")
    }

    fun captureHtml(callback: (String, String) -> Unit) {
        val url = browser.url
        if (!isTrustedDesktopBrowserUrl(url)) return
        browser.getSource { html -> deliver { if (browser.url == url) callback(url, html) } }
    }

    private suspend fun command(method: String, params: JsonObject): JsonObject = withTimeout(10_000) {
        check(!closed)
        withContext(Dispatchers.Main) {
            val devTools = requireNotNull(browser.devToolsClient)
            Json.parseToJsonElement(devTools.executeDevToolsMethod(method, params.toString()).await()).jsonObject
        }
    }

    private fun allowed(url: String) = if (trustedOnly) isTrustedDesktopBrowserUrl(url) else isDesktopWebUrl(url)
    private fun shouldOverride(url: String): Boolean {
        if (closed) return true
        if (SwingUtilities.isEventDispatchThread()) return overrideNavigation(url)
        var blocked = true
        SwingUtilities.invokeAndWait { if (!closed) blocked = overrideNavigation(url) }
        return blocked
    }
    private fun deliver(action: () -> Unit) { SwingUtilities.invokeLater { if (!closed) action() } }

    override fun close() {
        if (closed) return
        closed = true
        created.cancel(false)
        browser.close(true)
    }
}

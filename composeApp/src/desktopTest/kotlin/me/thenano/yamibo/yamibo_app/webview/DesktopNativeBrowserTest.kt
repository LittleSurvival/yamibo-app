package me.thenano.yamibo.yamibo_app.webview

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import javax.swing.JFrame
import kotlinx.coroutines.*
import kotlinx.coroutines.future.await
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Explicit opt-in: starts native Chromium and real windows. Uses local synthetic content only. */
class DesktopNativeBrowserTest {
    @Test fun chromiumRendersAndIsolatesHttpOnlyCookies() = runBlocking {
        assumeTrue(java.lang.Boolean.getBoolean("yamibo.test.nativeBrowser"))
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requestedUserAgent = CompletableDeferred<String>()
        server.createContext("/") { exchange ->
            requestedUserAgent.complete(exchange.requestHeaders.getFirst("User-Agent").orEmpty())
            val bytes = "<html><head><meta charset='utf-8'><title>Yamibo 桌面瀏覽器驗證</title></head><body><h1>桌面瀏覽器正常</h1><p>獨立 Cookie context 測試</p></body></html>".toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val url = "http://127.0.0.1:${server.address.port}/"
        val windows = mutableListOf<JFrame>()
        val browsers = mutableListOf<DesktopBrowser>()
        try {
            val runtime = DesktopBrowserRuntime.get()
            val loaded = CompletableDeferred<Unit>()
            withContext(Dispatchers.Main) {
                val first = DesktopBrowser(runtime, false, onFinished = { loaded.complete(Unit) })
                browsers += first
                windows += JFrame("Yamibo native browser test").apply {
                    me.thenano.yamibo.yamibo_app.desktop.DesktopIcon.applyTo(this)
                    contentPane.add(first.component); setSize(720, 500); isVisible = true
                }
                first.navigate(url, userAgent = desktopBrowserUserAgent("https://bbs.yamibo.com/plugin.php?id=zqlj_sign"))
            }
            withTimeout(30_000) { loaded.await() }
            assertEquals(desktopBrowserUserAgent("https://bbs.yamibo.com/plugin.php?id=zqlj_sign"),
                withTimeout(10_000) { requestedUserAgent.await() })
            val first = browsers.first().browser
            val navigatorAgent = withContext(Dispatchers.Main) {
                withTimeout(10_000) { first.devToolsClient.executeDevToolsMethod("Runtime.evaluate",
                    """{"expression":"navigator.userAgent","returnByValue":true}""").await() }
            }
            assertEquals(desktopBrowserUserAgent("https://bbs.yamibo.com/plugin.php?id=zqlj_sign"),
                Json.parseToJsonElement(navigatorAgent).jsonObject.getValue("result").jsonObject.getValue("value").jsonPrimitive.content)
            val source = CompletableDeferred<String>()
            first.getSource { source.complete(it) }
            assertTrue(withTimeout(10_000) { source.await() }.contains("桌面瀏覽器正常"))
            val cookies = buildJsonObject { putJsonArray("cookies") { add(buildJsonObject {
                put("name", "synthetic"); put("value", "test-only"); put("url", url); put("httpOnly", true)
            }) } }
            withContext(Dispatchers.Main) {
                withTimeout(10_000) { first.devToolsClient.executeDevToolsMethod("Network.setCookies", cookies.toString()).await() }
            }
            val params = buildJsonObject { putJsonArray("urls") { add(url) } }.toString()
            val ownCookies = withContext(Dispatchers.Main) {
                withTimeout(10_000) { first.devToolsClient.executeDevToolsMethod("Network.getCookies", params).await() }
            }
            assertTrue(Json.parseToJsonElement(ownCookies).jsonObject.getValue("cookies").jsonArray.any {
                it.jsonObject["name"]?.jsonPrimitive?.content == "synthetic" && it.jsonObject["httpOnly"]?.jsonPrimitive?.boolean == true
            })
            withContext(Dispatchers.Main) {
                val second = DesktopBrowser(runtime, false)
                browsers += second
                windows += JFrame("Yamibo isolated browser test").apply {
                    me.thenano.yamibo.yamibo_app.desktop.DesktopIcon.applyTo(this)
                    contentPane.add(second.component); setSize(400, 300); isVisible = true
                }
                second.navigate(url)
            }
            val otherCookies = withContext(Dispatchers.Main) { withTimeout(10_000) { browsers.last().browser.devToolsClient
                .executeDevToolsMethod("Network.getCookies", params).await() } }
            assertEquals(0, Json.parseToJsonElement(otherCookies).jsonObject.getValue("cookies").jsonArray.size)
        } finally {
            withContext(NonCancellable + Dispatchers.Main) {
                browsers.forEach { it.close() }
                windows.forEach { it.dispose() }
            }
            server.stop(0)
            DesktopBrowserRuntime.close()
        }
    }
}

package me.thenano.yamibo.yamibo_app.webview

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import me.thenano.yamibo.yamibo_app.LocalAuthRepository
import me.thenano.yamibo.yamibo_app.Logger
import org.json.JSONArray
import java.io.ByteArrayInputStream

@SuppressLint("SetJavaScriptEnabled")
@Composable
actual fun PlatformWebViewContent(
    url: String,
    syncAuthCookies: Boolean,
    captureHtml: Boolean,
    onTitleChanged: (String) -> Unit,
    onUrlChanged: (String) -> Unit,
    onLoadingChanged: (Boolean) -> Unit,
    onBack: (() -> Unit) -> Unit,
    onForward: (() -> Unit) -> Unit,
    onReload: (() -> Unit) -> Unit,
    onPageFinished: (String) -> Unit,
    onHtmlAvailable: (url: String, html: String) -> Unit,
    onLoadError: (url: String?, description: String) -> Unit,
    shouldOverrideUrlLoading: (String) -> Boolean,
) {
    val authRepo = LocalAuthRepository.current
    val cookies = authRepo.cookieStore.load() ?: ""

    var webViewInstance by remember { mutableStateOf<WebView?>(null) }

    RegisterPlatformWebViewNavigation(
        controllerKey = webViewInstance,
        canGoBack = { webViewInstance?.canGoBack() == true },
        goBack = { webViewInstance?.goBack() },
        canGoForward = { webViewInstance?.canGoForward() == true },
        goForward = { webViewInstance?.goForward() },
        reload = { webViewInstance?.reload() },
        onBack = onBack,
        onForward = onForward,
        onReload = onReload,
    )

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                webViewInstance = this
                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        onLoadingChanged(true)
                    }

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val targetUrl = request?.url?.toString().orEmpty()
                        if (
                            request != null &&
                            shouldBlockThirdPartyRequest(targetUrl, request.isForMainFrame)
                        ) {
                            Logger.d("WebView", "Blocked unreachable third-party: $targetUrl")
                            return WebResourceResponse(
                                "text/javascript",
                                "UTF-8",
                                ByteArrayInputStream(ByteArray(0)),
                            )
                        }
                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        onLoadingChanged(false)
                        val currentUrl = url ?: return
                        onUrlChanged(currentUrl)
                        if (syncAuthCookies) {
                            authRepo.syncCookieFromWebView()
                        }
                        onPageFinished(currentUrl)
                        if (captureHtml) {
                            evaluateJavascript("(function(){return document.documentElement.outerHTML;})()") { value ->
                                val html = decodeEvaluatedHtml(value)
                                if (html.isNotBlank()) {
                                    onHtmlAvailable(currentUrl, html)
                                }
                            }
                        }
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?
                    ) {
                        val description = error?.description?.toString().orEmpty()
                        Logger.e("WebView", description)
                        if (request?.isForMainFrame == true) {
                            onLoadingChanged(false)
                            onLoadError(request.url?.toString(), description)
                        }
                    }

                    override fun onReceivedHttpError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        errorResponse: WebResourceResponse?
                    ) {
                        super.onReceivedHttpError(view, request, errorResponse)
                        val statusCode = errorResponse?.statusCode ?: return
                        Logger.e("WebView", "HTTP $statusCode ${request?.url}")
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        val targetUrl = request?.url?.toString() ?: return false
                        return shouldOverrideUrlLoading(targetUrl)
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onReceivedTitle(view: WebView?, title: String?) {
                        super.onReceivedTitle(view, title)
                        title?.let { onTitleChanged(it) }
                    }
                }
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    @Suppress("DEPRECATION")
                    databaseEnabled = true
                    loadsImagesAutomatically = true
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    builtInZoomControls = true
                    displayZoomControls = false
                    javaScriptCanOpenWindowsAutomatically = true

                    cacheMode = WebSettings.LOAD_DEFAULT
                    userAgentString =
                        "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

                    setSupportZoom(true)
                }

                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(this, true)

                if (syncAuthCookies && cookies.isNotEmpty()) {
                    cookies.split(";").forEach {
                        cookieManager.setCookie(url, it.trim())
                    }
                    cookieManager.flush()
                }

                loadUrl(url)
            }
        },
        update = {
            webViewInstance = it
        }
    )
}

private fun decodeEvaluatedHtml(value: String?): String {
    if (value.isNullOrBlank() || value == "null") return ""
    return runCatching { JSONArray("[$value]").getString(0) }
        .onFailure { Logger.d("PlatformWebView", "Failed to decode evaluated HTML", it) }
        .getOrElse { value }
}

/**
 * Third-party hosts that are unreachable from the app's network (e.g. mainland China)
 * and would otherwise stall page load with a long connection timeout.
 *
 * The login page embeds Google Analytics (googletagmanager.com) which never resolves
 * from the app's network; WebView waits for its timeout before firing onPageFinished,
 * keeping the loading overlay visible for ~15s+. Intercepting these hosts with an empty
 * response lets the page finish immediately.
 */
internal fun shouldBlockThirdPartyRequest(
    targetUrl: String,
    isForMainFrame: Boolean,
): Boolean = !isForMainFrame && targetUrl.isBlockedThirdPartyUrl()

private fun String.isBlockedThirdPartyUrl(): Boolean {
    val host = substringAfter("://").substringBefore("/").lowercase()
    return host.endsWith("googletagmanager.com") ||
        host.endsWith("google-analytics.com") ||
        host.endsWith("googleadservices.com") ||
        host.endsWith("googlesyndication.com") ||
        host.endsWith("doubleclick.net") ||
        host.endsWith("gtagjs.com")
}

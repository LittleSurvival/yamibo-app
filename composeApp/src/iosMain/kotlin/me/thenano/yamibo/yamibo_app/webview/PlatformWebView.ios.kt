package me.thenano.yamibo.yamibo_app.webview

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import me.thenano.yamibo.yamibo_app.LocalAuthRepository
import me.thenano.yamibo.yamibo_app.network.WKWebViewCookieBridge
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKWebView
import platform.darwin.NSObject

class PlatformNavigationDelegate(
    private val syncAuthCookies: Boolean,
    private val captureHtml: Boolean,
    private val onPageFinished: (String) -> Unit,
    private val onLoadingChanged: (Boolean) -> Unit,
    private val onUrlChanged: (String) -> Unit,
    private val onTitleChanged: (String) -> Unit,
    private val onHtmlAvailable: (String, String) -> Unit,
    private val onLoadError: (String?, String) -> Unit,
    private val authCookieSync: () -> Unit,
) : NSObject(), WKNavigationDelegateProtocol {

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didStartProvisionalNavigation: WKNavigation?) {
        onLoadingChanged(true)
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) {
        onLoadingChanged(false)
        val currentUrl = webView.URL?.absoluteString ?: return
        onUrlChanged(currentUrl)
        webView.title?.let { onTitleChanged(it) }

        val notifyPageFinished = {
            onPageFinished(currentUrl)
            if (captureHtml) {
                webView.evaluateJavaScript("document.documentElement.outerHTML") { result, _ ->
                    val html = result as? String ?: return@evaluateJavaScript
                    if (html.isNotBlank()) {
                        onHtmlAvailable(currentUrl, html)
                    }
                }
            }
        }

        if (syncAuthCookies) {
            // Higher-level completion handlers can immediately start native requests.
            // Bridge and import the WKWebView cookies first so those requests observe
            // the same authenticated session as the page that just finished loading.
            WKWebViewCookieBridge.bridgeToNSHTTPCookieStorage(
                store = webView.configuration.websiteDataStore.httpCookieStore,
            ) {
                authCookieSync()
                notifyPageFinished()
            }
        } else {
            notifyPageFinished()
        }
    }

    @ObjCSignatureOverride
    override fun webView(
        webView: WKWebView,
        didFailNavigation: WKNavigation?,
        withError: platform.Foundation.NSError
    ) {
        onLoadingChanged(false)
        onLoadError(webView.URL?.absoluteString, withError.localizedDescription)
    }

    @ObjCSignatureOverride
    override fun webView(
        webView: WKWebView,
        didFailProvisionalNavigation: WKNavigation?,
        withError: platform.Foundation.NSError
    ) {
        onLoadingChanged(false)
        onLoadError(webView.URL?.absoluteString, withError.localizedDescription)
    }
}

@OptIn(ExperimentalForeignApi::class)
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
    var webViewInstance by remember { mutableStateOf<WKWebView?>(null) }

    val delegate = remember {
        PlatformNavigationDelegate(
            syncAuthCookies = syncAuthCookies,
            captureHtml = captureHtml,
            onPageFinished = onPageFinished,
            onLoadingChanged = onLoadingChanged,
            onUrlChanged = onUrlChanged,
            onTitleChanged = onTitleChanged,
            onHtmlAvailable = onHtmlAvailable,
            onLoadError = onLoadError,
            authCookieSync = { authRepo.syncCookieFromWebView() },
        )
    }

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
    
    UIKitView(
        modifier = Modifier.fillMaxSize(),
        factory = {
            WKWebView().apply {
                webViewInstance = this
                this.navigationDelegate = delegate
                loadRequest(NSURLRequest(NSURL(string = url)))
            }
        },
        update = {
            webViewInstance = it
        }
    )
}

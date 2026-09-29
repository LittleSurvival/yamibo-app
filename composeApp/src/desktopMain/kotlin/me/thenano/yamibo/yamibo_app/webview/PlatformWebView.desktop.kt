package me.thenano.yamibo.yamibo_app.webview

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.thenano.yamibo.yamibo_app.LocalAuthRepository
import me.thenano.yamibo.yamibo_app.repository.DesktopAuthRepository

@Composable
actual fun PlatformWebViewContent(url: String, syncAuthCookies: Boolean, captureHtml: Boolean,
    onTitleChanged: (String) -> Unit, onUrlChanged: (String) -> Unit, onLoadingChanged: (Boolean) -> Unit,
    onBack: (() -> Unit) -> Unit, onForward: (() -> Unit) -> Unit, onReload: (() -> Unit) -> Unit,
    onPageFinished: (String) -> Unit, onHtmlAvailable: (url: String, html: String) -> Unit,
    onLoadError: (url: String?, description: String) -> Unit, shouldOverrideUrlLoading: (String) -> Boolean) {
    val auth = LocalAuthRepository.current as DesktopAuthRepository
    val generation = auth.browserGeneration.collectAsState().value
    val scope = rememberCoroutineScope()
    var controller by remember(url, generation) { mutableStateOf<DesktopBrowser?>(null) }
    var failure by remember(url, generation) { mutableStateOf<String?>(null) }
    val titleCallback = rememberUpdatedState(onTitleChanged)
    val urlCallback = rememberUpdatedState(onUrlChanged)
    val loadingCallback = rememberUpdatedState(onLoadingChanged)
    val finishedCallback = rememberUpdatedState(onPageFinished)
    val htmlCallback = rememberUpdatedState(onHtmlAvailable)
    val errorCallback = rememberUpdatedState(onLoadError)
    val navigationCallback = rememberUpdatedState(shouldOverrideUrlLoading)
    val exchangeCookies = syncAuthCookies && isTrustedDesktopBrowserUrl(url)
    suspend fun sync(browser: DesktopBrowser) {
        if (exchangeCookies) auth.acceptBrowserCookies(generation, browser.cookies())
    }
    DisposableEffect(controller, generation) {
        val current = controller
        val syncRequest: () -> Unit = {
            if (current != null) scope.launch {
                try { sync(current) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { errorCallback.value(url, "無法同步瀏覽器登入資料") }
            }
        }
        if (exchangeCookies) auth.requestBrowserSync = syncRequest
        onDispose { if (auth.requestBrowserSync === syncRequest) auth.requestBrowserSync = null }
    }
    LaunchedEffect(url, generation) {
        var browser: DesktopBrowser? = null
        try {
            loadingCallback.value(true)
            val runtime = DesktopBrowserRuntime.get()
            browser = DesktopBrowser(runtime, trustedOnly = exchangeCookies,
                onTitle = { titleCallback.value(it) }, onUrl = { urlCallback.value(it) },
                onLoading = { loadingCallback.value(it) },
                onFinished = { finished ->
                    val current = controller
                    if (current != null) scope.launch {
                        try {
                            sync(current)
                            if (auth.browserGeneration.value == generation) {
                                failure = null
                                finishedCallback.value(finished)
                                if (captureHtml) current.captureHtml { page, html -> htmlCallback.value(page, html) }
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { errorCallback.value(finished, "無法同步瀏覽器登入資料") }
                    }
                }, onError = { page, message -> failure = message; errorCallback.value(page, message) },
                overrideNavigation = { navigationCallback.value(it) })
            controller = browser
            browser.navigate(url, if (exchangeCookies) auth.cookieStore.load().orEmpty() else "")
            while (true) {
                sync(browser)
                delay(500)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            failure = "瀏覽器無法啟動或同步登入資料，請檢查網路與系統憑證儲存後重試"
            errorCallback.value(url, failure!!)
            loadingCallback.value(false)
        } finally { browser?.close(); controller = null }
    }
    RegisterPlatformWebViewNavigation(controller,
        canGoBack = { controller?.browser?.canGoBack() == true }, goBack = { controller?.browser?.goBack() },
        canGoForward = { controller?.browser?.canGoForward() == true }, goForward = { controller?.browser?.goForward() },
        reload = { failure = null; controller?.browser?.reload() }, onBack = onBack, onForward = onForward, onReload = onReload)
    val current = controller
    if (failure != null) Text(failure!!)
    else if (current != null) key(current) { SwingPanel(factory = { current.component }, modifier = Modifier.fillMaxSize()) }
}

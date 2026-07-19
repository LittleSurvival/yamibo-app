package me.thenano.yamibo.yamibo_app.webview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import me.thenano.yamibo.yamibo_app.Logger
import me.thenano.yamibo.yamibo_app.profile.LoadingOverlay
import me.thenano.yamibo.yamibo_app.navigation.LocalNavigator
import me.thenano.yamibo.yamibo_app.navigation.Navigatable
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme

/** Platform-specific WebView content implementation */
@Composable
expect fun PlatformWebViewContent(
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
)

@Composable
internal fun RegisterPlatformWebViewNavigation(
    controllerKey: Any?,
    canGoBack: () -> Boolean,
    goBack: () -> Unit,
    canGoForward: () -> Boolean,
    goForward: () -> Unit,
    reload: () -> Unit,
    onBack: (() -> Unit) -> Unit,
    onForward: (() -> Unit) -> Unit,
    onReload: (() -> Unit) -> Unit,
) {
    val navigator = LocalNavigator.current

    LaunchedEffect(controllerKey) {
        onBack {
            if (canGoBack()) goBack()
        }
        onForward {
            if (canGoForward()) goForward()
        }
        onReload(reload)
    }

    DisposableEffect(controllerKey, navigator) {
        val handler: () -> Boolean = {
            if (canGoBack()) {
                goBack()
                true
            } else {
                false
            }
        }
        navigator.backHandlers.add(handler)
        onDispose {
            navigator.backHandlers.remove(handler)
        }
    }
}

@Composable
internal fun PlatformWebViewScreen(
    initialUrl: String,
    initialTitle: String = "WebView",
    showNavigation: Boolean = true,
    useBackIcon: Boolean = false,
    syncAuthCookies: Boolean = true,
    captureHtml: Boolean = false,
    onPageFinished: (String) -> Unit = {},
    onHtmlAvailable: (url: String, html: String) -> Unit = { _, _ -> },
    onLoadError: (url: String?, description: String) -> Unit = { _, _ -> },
    shouldOverrideUrlLoading: (String) -> Boolean = { false },
) {
    val navigator = LocalNavigator.current
    val uriHandler = LocalUriHandler.current
    val colors = YamiboTheme.colors

    var currentTitle by remember(initialTitle) { mutableStateOf(initialTitle) }
    var currentUrl by remember { mutableStateOf(initialUrl) }
    var loading by remember { mutableStateOf(true) }

    var backFunc by remember { mutableStateOf<(() -> Unit)?>(null) }
    var forwardFunc by remember { mutableStateOf<(() -> Unit)?>(null) }
    var reloadFunc by remember { mutableStateOf<(() -> Unit)?>(null) }

    Box(modifier = Modifier.fillMaxSize().background(colors.brownDeep)) {
        Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
            WebViewTopBar(
                title = currentTitle,
                url = currentUrl,
                onCloseClick = { navigator.pop() },
                onBackClick = { backFunc?.invoke() },
                onForwardClick = { forwardFunc?.invoke() },
                onRefreshClick = { reloadFunc?.invoke() },
                onOpenBrowserClick = {
                    try {
                        uriHandler.openUri(currentUrl)
                    } catch (error: Exception) {
                        Logger.w("PlatformWebView", "Failed to open browser url=$currentUrl", error)
                    }
                },
                showNavigation = showNavigation,
                useBackIcon = useBackIcon,
            )
            Box(modifier = Modifier.weight(1f)) {
                PlatformWebViewContent(
                    url = initialUrl,
                    syncAuthCookies = syncAuthCookies,
                    captureHtml = captureHtml,
                    onTitleChanged = { currentTitle = it },
                    onUrlChanged = { currentUrl = it },
                    onLoadingChanged = { loading = it },
                    onBack = { backFunc = it },
                    onForward = { forwardFunc = it },
                    onReload = { reloadFunc = it },
                    onPageFinished = onPageFinished,
                    onHtmlAvailable = onHtmlAvailable,
                    onLoadError = onLoadError,
                    shouldOverrideUrlLoading = shouldOverrideUrlLoading,
                )
                LoadingOverlay(visible = loading)
            }
        }
    }
}

class IPlatformWebView(
    private val link: String = "https://bbs.yamibo.com/",
    private val title: String = "WebView",
    private val showNavigation: Boolean = true,
    private val useBackIcon: Boolean = false,
    private val syncAuthCookies: Boolean = true,
    private val captureHtml: Boolean = false,
    private val onPageFinished: (String) -> Unit = {},
    private val onHtmlAvailable: (url: String, html: String) -> Unit = { _, _ -> },
    private val onLoadError: (url: String?, description: String) -> Unit = { _, _ -> },
    private val shouldOverrideUrlLoading: (String) -> Boolean = { false },
) : Navigatable {
    override val id = buildId(link, title, showNavigation, useBackIcon, syncAuthCookies, captureHtml)

    @Composable
    override fun Content() {
        PlatformWebViewScreen(
            initialUrl = link,
            initialTitle = title,
            showNavigation = showNavigation,
            useBackIcon = useBackIcon,
            syncAuthCookies = syncAuthCookies,
            captureHtml = captureHtml,
            onPageFinished = onPageFinished,
            onHtmlAvailable = onHtmlAvailable,
            onLoadError = onLoadError,
            shouldOverrideUrlLoading = shouldOverrideUrlLoading,
        )
    }
}

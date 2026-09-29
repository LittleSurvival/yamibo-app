package me.thenano.yamibo.yamibo_app.webview

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.focusable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import io.github.littlesurvival.waf.WafBrowserSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import me.thenano.yamibo.yamibo_app.util.auth.parseCookieStringToMap

@Composable
internal fun DesktopWafBrowser(session: WafBrowserSession, modifier: Modifier) {
    var controller by remember(session) { mutableStateOf<DesktopBrowser?>(null) }
    LaunchedEffect(session) {
        var browser: DesktopBrowser? = null
        try {
            browser = DesktopBrowser(DesktopBrowserRuntime.get(), trustedOnly = true,
                onError = { _, _ -> session.reportFailure() })
            controller = browser
            browser.navigate(session.url, session.cookieHeader, session.userAgent)
            while (true) {
                val header = browser.cookies()
                if (!parseCookieStringToMap(header)["nox_jst_v1"].isNullOrBlank()) session.submitCookieHeader(header)
                delay(500)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { session.reportFailure() }
        finally { browser?.close(); controller = null }
    }
    DesktopWafPanel(session::cancel, modifier) {
        val current = controller
        if (current == null) CircularProgressIndicator()
        else key(current) { SwingPanel(factory = { current.component }, modifier = Modifier.weight(1f).fillMaxWidth()) }
    }
}

@Composable
internal fun DesktopWafPanel(
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Surface(modifier.fillMaxSize().focusRequester(focus).focusable().pointerInput(Unit) {
        // Claim the whole overlay hit area, including gaps around the native browser, while
        // allowing its children (notably Cancel) to handle their own pointer events first.
        awaitPointerEventScope {
            while (true) awaitPointerEvent(PointerEventPass.Final).changes.forEach { it.consume() }
        }
    }) {
      Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("網站安全驗證")
            TextButton(onClick = onCancel) { Text("取消") }
        }
        content()
      }
    }
}

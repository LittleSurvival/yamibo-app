package me.thenano.yamibo.yamibo_app.webview

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import me.thenano.yamibo.yamibo_app.thread.reader.components.readerDesktopInput
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class DesktopWafPanelTest {
    @get:Rule val compose = createComposeRule()

    @Test fun overlayClaimsFocusAndPointerButCancelRemainsOperable() {
        var shown by mutableStateOf(false)
        var pages = 0
        var clicks = 0
        var cancellations = 0
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(400.dp).testTag("host")) {
                    Box(Modifier.fillMaxSize().testTag("reader")
                        .then(readerDesktopInput(true, true, false) { pages += it })) {
                        Button(onClick = { clicks++ }, modifier = Modifier.fillMaxSize()) { Text("底層頁面") }
                    }
                    if (shown) DesktopWafPanel({ cancellations++ }, Modifier.testTag("verification")) {}
                }
            }
        }
        compose.onNodeWithTag("reader").assertIsFocused()
        compose.runOnIdle { shown = true }
        compose.onNodeWithTag("verification").assertIsFocused()
        compose.onNodeWithTag("verification").performKeyInput { keyDown(Key.PageDown); keyUp(Key.PageDown) }
        compose.onNodeWithTag("host").performMouseInput { click(center) }
        compose.runOnIdle { assertEquals(0, pages); assertEquals(0, clicks) }
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertEquals(1, cancellations) }
    }
}

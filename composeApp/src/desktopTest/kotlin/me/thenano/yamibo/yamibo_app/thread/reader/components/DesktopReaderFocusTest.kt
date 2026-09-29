package me.thenano.yamibo.yamibo_app.thread.reader.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import me.thenano.yamibo.yamibo_app.thread.image.imageContextMenuInput

@OptIn(ExperimentalTestApi::class)
class DesktopReaderFocusTest {
    @get:Rule val compose = createComposeRule()

    @Test fun imageMenuUsesSecondaryClickAndRespectsOverlayGuard() {
        var opens = 0
        var enabled by mutableStateOf(true)
        compose.setContent {
            Box(Modifier.size(300.dp).testTag("image")
                .imageContextMenuInput(enabled) { opens++ })
        }
        compose.onNodeWithTag("image").performMouseInput { click() }
        compose.runOnIdle { assertEquals(0, opens) }
        compose.onNodeWithTag("image").performMouseInput { click(button = MouseButton.Secondary) }
        compose.runOnIdle { assertEquals(1, opens); enabled = false }
        compose.onNodeWithTag("image").performMouseInput { click(button = MouseButton.Secondary) }
        compose.runOnIdle { assertEquals(1, opens) }
    }

    @Test fun mouseWheelTurnsPagedContentAndStopsWhenPanelDisablesReader() {
        var pages = 0
        var enabled by mutableStateOf(true)
        compose.setContent {
            Box(Modifier.size(300.dp).testTag("reader")
                .then(readerDesktopInput(enabled, true, false) { pages += it }))
        }
        compose.onNodeWithTag("reader").performMouseInput { moveTo(center); scroll(2f) }
        compose.runOnIdle { assertEquals(1, pages); enabled = false }
        compose.onNodeWithTag("reader").performMouseInput { moveTo(center); scroll(2f) }
        compose.runOnIdle { assertEquals(1, pages) }
    }

    @Test fun editorFocusDoesNotBubblePageCommandsToReader() {
        var pages = 0
        var text by mutableStateOf("")
        compose.setContent {
            MaterialTheme {
                Column(Modifier.testTag("reader").then(readerDesktopInput(true, true, false) { pages += it })) {
                    TextField(text, { text = it }, Modifier.testTag("editor"))
                }
            }
        }
        compose.onNodeWithTag("reader").assertIsFocused()
        compose.onNodeWithTag("reader").performKeyInput { keyDown(Key.PageDown); keyUp(Key.PageDown) }
        compose.runOnIdle { assertEquals(1, pages) }
        compose.onNodeWithTag("editor").performClick()
        compose.onNodeWithTag("editor").assertIsFocused()
        compose.onNodeWithTag("editor").performTextInput("中文目錄搜尋")
        compose.onNodeWithTag("editor").performKeyInput { keyDown(Key.PageDown); keyUp(Key.PageDown) }
        compose.runOnIdle {
            assertEquals(1, pages)
            assertEquals("中文目錄搜尋", text)
        }
    }
}

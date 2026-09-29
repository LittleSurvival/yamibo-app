package me.thenano.yamibo.yamibo_app.components.controls

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import kotlin.test.*

@OptIn(ExperimentalTestApi::class, ExperimentalMaterial3Api::class)
class AppScrollingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun disabledRightButtonAndReverseLayoutRespectExistingScrollRules() {
        lateinit var list: LazyListState
        var enabled by mutableStateOf(false)
        var reversed by mutableStateOf(false)
        compose.setContent {
            list = rememberLazyListState()
            AppLazyColumn(state = list, userScrollEnabled = enabled, reverseLayout = reversed,
                modifier = Modifier.size(400.dp).testTag("list")) {
                items(30) { Text("row $it", Modifier.fillMaxWidth().height(80.dp)) }
            }
        }
        compose.onNodeWithTag("list").performMouseInput {
            moveTo(Offset(200f, 350f)); press(); moveTo(Offset(200f, 100f)); release()
        }
        compose.runOnIdle { assertFalse(list.canScrollBackward); enabled = true }
        compose.onNodeWithTag("list").performMouseInput {
            moveTo(Offset(200f, 350f)); press(MouseButton.Secondary)
            moveTo(Offset(200f, 100f)); release(MouseButton.Secondary)
        }
        compose.runOnIdle { assertFalse(list.canScrollBackward); reversed = true }
        compose.onNodeWithTag("list").performMouseInput {
            moveTo(Offset(200f, 50f)); press(); moveTo(Offset(200f, 300f)); release()
        }
        compose.runOnIdle { assertTrue(list.canScrollBackward) }
    }

    @Test fun draggingInsideTextEditorSelectsTextInsteadOfScrollingParent() {
        lateinit var list: LazyListState
        var value by mutableStateOf(TextFieldValue((1..8).joinToString("\n") { "可選取文字 $it abcdefghij" }))
        compose.setContent {
            MaterialTheme {
                list = rememberLazyListState()
                AppLazyColumn(state = list, modifier = Modifier.size(400.dp).testTag("list")) {
                    item { BasicTextField(value, { value = it }, Modifier.fillMaxWidth().height(220.dp).testTag("editor")) }
                    items(20) { Text("row $it", Modifier.fillMaxWidth().height(80.dp)) }
                }
            }
        }
        compose.runOnIdle { runBlocking { list.scrollToItem(0, 20) } }
        compose.onNodeWithTag("editor").performMouseInput {
            moveTo(Offset(80f, 110f)); press(); moveTo(Offset(30f, 40f)); release()
        }
        compose.runOnIdle {
            assertFalse(value.selection.collapsed)
            assertEquals(0, list.firstVisibleItemIndex)
            // Focusing the editor may bring its top into view; the upward selection drag must
            // not scroll the parent forward as a content-pan gesture would.
            assertTrue(list.firstVisibleItemScrollOffset <= 20)
        }
    }

    @Test fun mouseDragScrollsListAndPullProgressTracksDragBeforeRelease() {
        lateinit var list: LazyListState
        lateinit var pull: PullToRefreshState
        var requests = 0
        compose.setContent {
            MaterialTheme {
                list = rememberLazyListState()
                pull = rememberPullToRefreshState()
                AppPullToRefreshBox(false, { requests++ }, Modifier.size(400.dp).testTag("refresh"), pull) {
                    AppLazyColumn(state = list, modifier = Modifier.fillMaxSize().testTag("list")) {
                        items(30) { Text("row $it", Modifier.fillMaxWidth().height(80.dp)) }
                    }
                }
            }
        }
        compose.onNodeWithTag("list").performMouseInput {
            moveTo(Offset(200f, 350f)); press(); moveTo(Offset(200f, 150f)); release()
        }
        compose.runOnIdle { assertTrue(list.canScrollBackward); assertEquals(0, requests) }
        compose.onNodeWithTag("list").performScrollToIndex(0)
        compose.onNodeWithTag("list").performMouseInput {
            moveTo(Offset(200f, 30f)); press(); moveTo(Offset(200f, 90f))
        }
        compose.runOnIdle { assertTrue(pull.distanceFraction > 0f); assertTrue(pull.distanceFraction < 1f); assertEquals(0, requests) }
        compose.onNodeWithTag("list").performMouseInput { release() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, requests); assertEquals(0f, pull.distanceFraction) }
        compose.onNodeWithTag("list").performMouseInput {
            moveTo(Offset(200f, 30f)); press(); moveTo(Offset(200f, 360f))
        }
        compose.runOnIdle { assertTrue(pull.distanceFraction >= 1f); assertEquals(0, requests) }
        compose.onNodeWithTag("list").performMouseInput { release() }
        compose.runOnIdle { assertEquals(1, requests) }
        compose.onNodeWithTag("refresh").performKeyInput { keyDown(Key.F5); keyUp(Key.F5) }
        compose.runOnIdle { assertEquals(2, requests) }
    }

    @Test fun mouseDragScrollsPlainColumnAndStillAllowsButtonClicks() {
        lateinit var state: ScrollState
        var clicks = 0
        compose.setContent {
            MaterialTheme {
                state = rememberScrollState()
                Column(Modifier.size(400.dp).appVerticalScroll(state).testTag("scroll")) {
                    repeat(20) { Button({ clicks++ }, Modifier.fillMaxWidth().height(80.dp)) { Text("button $it") } }
                }
            }
        }
        compose.onNodeWithText("button 0").performMouseInput { click() }
        compose.runOnIdle { assertEquals(1, clicks) }
        compose.onNodeWithTag("scroll").performMouseInput {
            moveTo(Offset(200f, 300f)); press(); moveTo(Offset(200f, 80f)); release()
        }
        compose.runOnIdle { assertTrue(state.value > 0); assertEquals(1, clicks) }
    }
}

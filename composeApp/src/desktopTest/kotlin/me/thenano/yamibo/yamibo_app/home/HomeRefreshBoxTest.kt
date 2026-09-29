package me.thenano.yamibo.yamibo_app.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import me.thenano.yamibo.yamibo_app.components.controls.AppLazyColumn as LazyColumn
import me.thenano.yamibo.yamibo_app.components.controls.AppPullToRefreshBox
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class HomeRefreshBoxTest {
    @get:Rule val compose = createComposeRule()

    @Test fun f5WheelAndMousePullRefreshWithoutBreakingClicksOrNormalScrolling() {
        var requests = 0
        var clicks = 0
        var refreshing by mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                val list = rememberLazyListState()
                AppPullToRefreshBox(refreshing, { requests++; refreshing = true },
                    Modifier.size(400.dp).testTag("refresh")) {
                    LazyColumn(state = list, modifier = Modifier.fillMaxSize().testTag("list")) {
                        items(20) { index ->
                            Button({ clicks++ }, Modifier.fillMaxWidth().height(100.dp)) { Text("row $index") }
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("row 0").performClick()
        compose.runOnIdle { assertEquals(1, clicks) }
        compose.onNodeWithTag("refresh").performKeyInput { keyDown(Key.F5); keyUp(Key.F5) }
        compose.runOnIdle { assertEquals(1, requests) }
        compose.onNodeWithTag("refresh").performKeyInput { keyDown(Key.F5); keyUp(Key.F5) }
        compose.runOnIdle { assertEquals(1, requests); refreshing = false }
        compose.onNodeWithTag("refresh").performMouseInput { moveTo(center); scroll(-3f) }
        compose.runOnIdle { assertEquals(1, requests) }
        compose.onNodeWithTag("list").performScrollToIndex(10)
        compose.onNodeWithTag("refresh").performMouseInput { moveTo(center); scroll(-1f) }
        compose.runOnIdle { assertEquals(1, requests) }
        compose.onNodeWithTag("list").performScrollToIndex(0)
        compose.onNodeWithTag("refresh").performMouseInput {
            moveTo(Offset(200f, 30f)); press(); moveTo(Offset(200f, 360f)); release()
        }
        compose.runOnIdle { assertEquals(2, requests); assertEquals(1, clicks) }
    }
}

package me.thenano.yamibo.yamibo_app.components.controls

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import androidx.compose.foundation.lazy.LazyColumn as NativeLazyColumn
import androidx.compose.foundation.verticalScroll as nativeVerticalScroll

/** Always settle refresh UI, including cancellation before the first dispatcher turn. */
internal fun CoroutineScope.launchRefresh(
    onFinished: () -> Unit,
    onFailure: () -> Unit,
    block: suspend CoroutineScope.() -> Unit,
): Job = launch(start = CoroutineStart.UNDISPATCHED) {
    try {
        ensureActive()
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        onFailure()
    } finally {
        onFinished()
    }
}

@Composable
internal fun AppLazyColumn(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    reverseLayout: Boolean = false,
    verticalArrangement: Arrangement.Vertical = if (reverseLayout) Arrangement.Bottom else Arrangement.Top,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    flingBehavior: FlingBehavior = ScrollableDefaults.flingBehavior(),
    userScrollEnabled: Boolean = true,
    content: LazyListScope.() -> Unit,
) = NativeLazyColumn(
    modifier = modifier.desktopDragScroll(state, userScrollEnabled, reverseLayout, flingBehavior),
    state = state, contentPadding = contentPadding, reverseLayout = reverseLayout,
    verticalArrangement = verticalArrangement, horizontalAlignment = horizontalAlignment,
    flingBehavior = flingBehavior, userScrollEnabled = userScrollEnabled, content = content,
)

@Composable
internal fun Modifier.appVerticalScroll(state: ScrollState, enabled: Boolean = true, reverseScrolling: Boolean = false): Modifier =
    desktopDragScroll(state, enabled, reverseScrolling, ScrollableDefaults.flingBehavior())
        .nativeVerticalScroll(state, enabled = enabled, reverseScrolling = reverseScrolling)

@Composable
internal fun AppPullToRefreshBox(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    state: PullToRefreshState = rememberPullToRefreshState(),
    content: @Composable BoxScope.() -> Unit,
) {
    PullToRefreshBox(isRefreshing, { if (!isRefreshing) onRefresh() },
        modifier = modifier.desktopRefreshShortcut(isRefreshing, onRefresh), state = state, content = content)
}

@Composable
internal expect fun Modifier.desktopDragScroll(state: ScrollableState, enabled: Boolean,
    reverseDirection: Boolean, flingBehavior: FlingBehavior): Modifier

@Composable
internal expect fun Modifier.desktopRefreshShortcut(isRefreshing: Boolean, onRefresh: () -> Unit): Modifier

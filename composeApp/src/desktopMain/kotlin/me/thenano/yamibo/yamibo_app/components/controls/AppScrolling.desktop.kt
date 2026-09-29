package me.thenano.yamibo.yamibo_app.components.controls

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.unit.Velocity

@Composable
internal actual fun Modifier.desktopRefreshShortcut(isRefreshing: Boolean, onRefresh: () -> Unit): Modifier {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    return focusRequester(focus).onPreviewKeyEvent {
        if (it.key == Key.F5 && !it.isAltPressed && !it.isCtrlPressed && !it.isMetaPressed) {
            if (it.type == KeyEventType.KeyUp && !isRefreshing) onRefresh()
            true
        } else false
    }.focusable()
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal actual fun Modifier.desktopDragScroll(state: ScrollableState, enabled: Boolean,
    reverseDirection: Boolean, flingBehavior: FlingBehavior): Modifier {
    val dispatcher = remember { NestedScrollDispatcher() }
    // Native wheel scrolling does not have a release/fling to settle a pull-refresh indicator.
    val wheel = remember { booleanArrayOf(false) }
    val connection = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource) =
                if (wheel[0]) Offset(0f, available.y) else Offset.Zero
        }
    }
    val sign = if (reverseDirection) 1f else -1f
    fun ScrollScope.move(delta: Float, source: NestedScrollSource): Float {
        val before = dispatcher.dispatchPreScroll(Offset(0f, delta), source).y
        val available = delta - before
        val consumed = scrollBy(available * sign) * sign
        val after = dispatcher.dispatchPostScroll(Offset(0f, consumed), Offset(0f, available - consumed), source).y
        return before + consumed + after
    }
    val drag = remember(state, reverseDirection) {
        object : DraggableState {
            override suspend fun drag(dragPriority: MutatePriority, block: suspend DragScope.() -> Unit) {
                state.scroll(dragPriority) {
                    val scrolling = this
                    block(object : DragScope {
                        override fun dragBy(pixels: Float) { scrolling.move(pixels, NestedScrollSource.UserInput) }
                    })
                }
            }
            override fun dispatchRawDelta(delta: Float) { state.dispatchRawDelta(delta * sign) }
        }
    }
    return this
        .onPointerEvent(PointerEventType.Scroll, PointerEventPass.Initial) { wheel[0] = true }
        .onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) { wheel[0] = false }
        .nestedScroll(connection, dispatcher)
        .draggable(drag, Orientation.Vertical, enabled = enabled, onDragStopped = { velocity ->
            val before = dispatcher.dispatchPreFling(Velocity(0f, velocity)).y
            val available = velocity - before
            var remaining = available
            state.scroll {
                val scrolling = this
                val flingScope = object : ScrollScope {
                    override fun scrollBy(pixels: Float): Float =
                        scrolling.move(pixels * sign, NestedScrollSource.SideEffect) * sign
                }
                remaining = with(flingBehavior) { flingScope.performFling(available * sign) } * sign
            }
            dispatcher.dispatchPostFling(Velocity(0f, available - remaining), Velocity(0f, remaining))
        })
}

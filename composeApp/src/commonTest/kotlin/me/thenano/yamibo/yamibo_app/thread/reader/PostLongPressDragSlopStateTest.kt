package me.thenano.yamibo.yamibo_app.thread.reader

import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PostLongPressDragSlopStateTest {
    private val viewConfiguration = object : ViewConfiguration {
        override val longPressTimeoutMillis = 500L
        override val doubleTapTimeoutMillis = 300L
        override val doubleTapMinTimeMillis = 40L
        override val touchSlop = 24f
        override val minimumTouchTargetSize = DpSize(48.dp, 48.dp)
    }

    @Test
    fun stationaryLongPressReducesProvidedScrollSlop() {
        val state = PostLongPressDragSlopState()

        state.onDown(uptimeMillis = 1_000L)
        state.onInitialPointerEvent(
            uptimeMillis = 1_500L,
            distanceFromDown = 1f,
            pressed = true,
            viewConfiguration = viewConfiguration,
        )

        assertTrue(state.useReducedScrollSlop)
        assertEquals(POST_LONG_PRESS_SCROLL_SLOP_PX, state.scrollTouchSlop(viewConfiguration.touchSlop))
        assertEquals(24f, viewConfiguration.touchSlop)
    }

    @Test
    fun firstMoveAfterStationaryHoldUsesReducedSlopEvenWhenItIsLarge() {
        val state = PostLongPressDragSlopState()

        state.onDown(uptimeMillis = 1_000L)
        state.onInitialPointerEvent(1_600L, 20f, true, viewConfiguration)

        assertTrue(state.useReducedScrollSlop)
        assertEquals(POST_LONG_PRESS_SCROLL_SLOP_PX, state.scrollTouchSlop(viewConfiguration.touchSlop))
    }

    @Test
    fun ordinaryDragKeepsPlatformSlopForRestOfGesture() {
        val state = PostLongPressDragSlopState()

        state.onDown(uptimeMillis = 1_000L)
        state.onInitialPointerEvent(
            uptimeMillis = 1_100L,
            distanceFromDown = 24f,
            pressed = true,
            viewConfiguration = viewConfiguration,
        )
        state.onInitialPointerEvent(
            uptimeMillis = 1_600L,
            distanceFromDown = 30f,
            pressed = true,
            viewConfiguration = viewConfiguration,
        )

        assertFalse(state.useReducedScrollSlop)
        assertEquals(24f, state.scrollTouchSlop(viewConfiguration.touchSlop))
    }

    @Test
    fun preTimeoutMovementIsNotReinterpretedAgainstReducedSlop() {
        val state = PostLongPressDragSlopState()

        state.onDown(uptimeMillis = 1_000L)
        state.onInitialPointerEvent(1_300L, 10f, true, viewConfiguration)
        state.onInitialPointerEvent(1_600L, 11f, true, viewConfiguration)

        assertFalse(state.useReducedScrollSlop)
        assertEquals(24f, state.scrollTouchSlop(viewConfiguration.touchSlop))
    }

    @Test
    fun releaseRestoresPlatformSlop() {
        val state = PostLongPressDragSlopState()
        state.onDown(uptimeMillis = 1_000L)
        state.onInitialPointerEvent(1_500L, 0f, true, viewConfiguration)

        state.onInitialPointerEvent(1_600L, 0f, false, viewConfiguration)

        assertFalse(state.useReducedScrollSlop)
        assertEquals(24f, state.scrollTouchSlop(viewConfiguration.touchSlop))
    }
}

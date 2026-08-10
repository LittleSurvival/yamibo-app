package me.thenano.yamibo.yamibo_app.thread.reader

import androidx.compose.ui.platform.ViewConfiguration
import kotlin.math.max
import kotlin.math.min

internal const val POST_LONG_PRESS_SCROLL_SLOP_PX = 4f

/**
 * Lets the list recognize a deliberate drag promptly after a stationary long press while leaving
 * ordinary gestures on the platform touch slop. The reduced value is scoped to the reader list's
 * composition and is activated only after the platform long-press timeout.
 */
internal class PostLongPressDragSlopState {
    private var downUptimeMillis = 0L
    private var eligible = false
    private var maxDistanceBeforeLongPress = 0f

    var useReducedScrollSlop: Boolean = false
        private set

    fun onDown(uptimeMillis: Long) {
        downUptimeMillis = uptimeMillis
        eligible = true
        maxDistanceBeforeLongPress = 0f
        useReducedScrollSlop = false
    }

    fun onInitialPointerEvent(
        uptimeMillis: Long,
        distanceFromDown: Float,
        pressed: Boolean,
        viewConfiguration: ViewConfiguration,
    ) {
        if (!pressed) {
            reset()
            return
        }
        if (!eligible || useReducedScrollSlop) return

        val heldPastLongPress =
            uptimeMillis - downUptimeMillis >= viewConfiguration.longPressTimeoutMillis
        if (heldPastLongPress) {
            // Do not lower the threshold after meaningful pre-timeout movement: LazyColumn has
            // already accumulated that delta, and accepting it against a smaller slop could make
            // the first scroll frame appear to jump.
            useReducedScrollSlop = maxDistanceBeforeLongPress <= POST_LONG_PRESS_SCROLL_SLOP_PX
            eligible = useReducedScrollSlop
        } else if (distanceFromDown >= viewConfiguration.touchSlop) {
            // This is an ordinary drag which already belongs to LazyColumn's native recognizer.
            eligible = false
        } else {
            maxDistanceBeforeLongPress = max(maxDistanceBeforeLongPress, distanceFromDown)
        }
    }

    fun scrollTouchSlop(regularTouchSlop: Float): Float =
        if (useReducedScrollSlop) {
            min(regularTouchSlop, POST_LONG_PRESS_SCROLL_SLOP_PX)
        } else {
            regularTouchSlop
        }

    private fun reset() {
        eligible = false
        maxDistanceBeforeLongPress = 0f
        useReducedScrollSlop = false
    }
}

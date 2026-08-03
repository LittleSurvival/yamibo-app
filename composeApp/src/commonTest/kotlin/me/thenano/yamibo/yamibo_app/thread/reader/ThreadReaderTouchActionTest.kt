package me.thenano.yamibo.yamibo_app.thread.reader

import me.thenano.yamibo.yamibo_app.repository.settings.ThreadReaderMode
import me.thenano.yamibo.yamibo_app.repository.settings.TouchZoneLayout
import me.thenano.yamibo.yamibo_app.thread.reader.components.thread.SinglePageTapAction
import me.thenano.yamibo.yamibo_app.thread.reader.components.thread.resolveThreadReaderTapAction
import me.thenano.yamibo.yamibo_app.thread.reader.components.thread.singlePageDeltaForTouchAction
import kotlin.test.Test
import kotlin.test.assertEquals

class ThreadReaderTouchActionTest {
    @Test
    fun everyEnabledLayoutAppliesOnlyThreadReversal() {
        val samples = listOf(
            TouchSample(TouchZoneLayout.L_SHAPE, 0.5f, 0.1f, SinglePageTapAction.Prev),
            TouchSample(TouchZoneLayout.KINDLE, 0.1f, 0.5f, SinglePageTapAction.Prev),
            TouchSample(TouchZoneLayout.EDGE, 0.1f, 0.5f, SinglePageTapAction.Next),
            TouchSample(TouchZoneLayout.LEFT_RIGHT, 0.1f, 0.5f, SinglePageTapAction.Prev),
        )

        samples.forEach { sample ->
            val normal = resolveThreadReaderTapAction(
                layout = sample.layout,
                xFraction = sample.x,
                yFraction = sample.y,
                reverseTouchZones = false,
            )
            val reversed = resolveThreadReaderTapAction(
                layout = sample.layout,
                xFraction = sample.x,
                yFraction = sample.y,
                reverseTouchZones = true,
            )

            assertEquals(sample.action, normal, "normal layout=${sample.layout}")
            assertEquals(sample.action.reversed(), reversed, "reversed layout=${sample.layout}")
        }
    }

    @Test
    fun reversalRunsBeforeExistingReadingDirectionConversion() {
        val reversed = resolveThreadReaderTapAction(
            layout = TouchZoneLayout.LEFT_RIGHT,
            xFraction = 0.1f,
            yFraction = 0.5f,
            reverseTouchZones = true,
        )

        assertEquals(SinglePageTapAction.Next, reversed)
        assertEquals(1, singlePageDeltaForTouchAction(ThreadReaderMode.SINGLE_LTR, reversed))
        assertEquals(-1, singlePageDeltaForTouchAction(ThreadReaderMode.SINGLE_RTL, reversed))
        assertEquals(1, singlePageDeltaForTouchAction(ThreadReaderMode.SINGLE_TTB, reversed))
    }

    @Test
    fun menuAndDisabledBehaviorRemainUnchanged() {
        val menu = resolveThreadReaderTapAction(
            layout = TouchZoneLayout.L_SHAPE,
            xFraction = 0.5f,
            yFraction = 0.5f,
            reverseTouchZones = true,
        )
        val disabled = resolveThreadReaderTapAction(
            layout = TouchZoneLayout.DISABLED,
            xFraction = 0.1f,
            yFraction = 0.5f,
            reverseTouchZones = true,
        )

        assertEquals(SinglePageTapAction.Menu, menu)
        assertEquals(SinglePageTapAction.Menu, disabled)
        assertEquals(0, singlePageDeltaForTouchAction(ThreadReaderMode.SINGLE_RTL, menu))
        assertEquals(0, singlePageDeltaForTouchAction(ThreadReaderMode.SINGLE_RTL, disabled))
    }
}

private data class TouchSample(
    val layout: TouchZoneLayout,
    val x: Float,
    val y: Float,
    val action: SinglePageTapAction,
)

private fun SinglePageTapAction.reversed(): SinglePageTapAction = when (this) {
    SinglePageTapAction.Prev -> SinglePageTapAction.Next
    SinglePageTapAction.Next -> SinglePageTapAction.Prev
    SinglePageTapAction.Menu -> SinglePageTapAction.Menu
}

package me.thenano.yamibo.yamibo_app.thread.reader.components.manga

import me.thenano.yamibo.yamibo_app.repository.settings.TouchZoneLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MangaTouchZoneActionTest {
    @Test
    fun reversalAndRtlComposeInOrder() {
        val raw = TouchAction.PREV

        assertEquals(raw, transformMangaTouchAction(raw, reverseTouchZones = false, isRtl = false))
        assertEquals(TouchAction.NEXT, transformMangaTouchAction(raw, reverseTouchZones = true, isRtl = false))
        assertEquals(TouchAction.NEXT, transformMangaTouchAction(raw, reverseTouchZones = false, isRtl = true))
        assertEquals(raw, transformMangaTouchAction(raw, reverseTouchZones = true, isRtl = true))
    }

    @Test
    fun menuAndDisabledResultsNeverChange() {
        assertEquals(
            TouchAction.MENU,
            transformMangaTouchAction(TouchAction.MENU, reverseTouchZones = true, isRtl = true),
        )
        assertNull(transformMangaTouchAction(null, reverseTouchZones = true, isRtl = true))
        assertNull(
            getEffectiveMangaTouchAction(
                layout = TouchZoneLayout.DISABLED,
                xFraction = 0.1f,
                yFraction = 0.1f,
                reverseTouchZones = true,
                isRtl = true,
            )
        )
    }

    @Test
    fun everyEnabledLayoutUsesTheSameEffectiveActionPipeline() {
        val samples = listOf(
            TouchZoneLayout.L_SHAPE to Pair(0.5f, 0.1f),
            TouchZoneLayout.KINDLE to Pair(0.1f, 0.5f),
            TouchZoneLayout.EDGE to Pair(0.1f, 0.5f),
            TouchZoneLayout.LEFT_RIGHT to Pair(0.1f, 0.5f),
        )

        samples.forEach { (layout, position) ->
            val raw = getTouchAction(layout, position.first, position.second)
            assertEquals(
                transformMangaTouchAction(raw, reverseTouchZones = true, isRtl = false),
                getEffectiveMangaTouchAction(
                    layout = layout,
                    xFraction = position.first,
                    yFraction = position.second,
                    reverseTouchZones = true,
                    isRtl = false,
                ),
                "layout=$layout",
            )
        }
    }

    @Test
    fun rawThreadReaderMappingIsUnaffectedByMangaReversal() {
        val raw = getTouchAction(TouchZoneLayout.LEFT_RIGHT, xFraction = 0.1f, yFraction = 0.5f)

        assertEquals(TouchAction.PREV, raw)
        assertEquals(
            TouchAction.NEXT,
            transformMangaTouchAction(raw, reverseTouchZones = true, isRtl = false),
        )
        assertEquals(TouchAction.PREV, getTouchAction(TouchZoneLayout.LEFT_RIGHT, 0.1f, 0.5f))
    }
}

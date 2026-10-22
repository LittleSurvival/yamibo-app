package me.thenano.yamibo.yamibo_app.thread.reader.components.overlay

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.union
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.test.*

class ReaderRetestRegressionTest {
    private val slots = buildReaderPageProgressSlots(listOf(ReaderPageProgressEntryRef("body", 1)))
    private fun sample(offset: Int, size: Int = 10000) = calculateReaderPageProgressSample(
        slots, listOf(ReaderPageProgressVisibleItem(0, -offset, size)),
        viewportStart = 0, viewportEnd = 800, totalPages = 1,
        firstVisibleItemIndex = 0, firstVisibleItemScrollOffset = offset,
    )!!

    @Test fun instantaneousScrollToTopResetsWhileIdle() {
        val stabilizer = ReaderPageProgressStabilizer()
        assertTrue(stabilizer.update(sample(300), ReaderPageProgressDirection.Forward)!!.fraction > 0f)
        repeat(4) {
            assertEquals(0f, stabilizer.update(sample(0), ReaderPageProgressDirection.Idle)!!.fraction)
            stabilizer.update(sample(300), ReaderPageProgressDirection.Forward)
        }
    }

    @Test fun idleImageReflowAwayFromTopDoesNotDecreaseProgress() {
        val stabilizer = ReaderPageProgressStabilizer()
        val before = stabilizer.update(sample(300, 10000), ReaderPageProgressDirection.Forward)!!
        val after = stabilizer.update(sample(300, 20000), ReaderPageProgressDirection.Idle)!!
        assertEquals(before.fraction, after.fraction)
    }

    @Test fun backwardsScrollingStillDecreasesProgressWithinOneEntry() {
        val stabilizer = ReaderPageProgressStabilizer()
        val before = stabilizer.update(sample(800), ReaderPageProgressDirection.Forward)!!
        val after = stabilizer.update(sample(100), ReaderPageProgressDirection.Backward)!!
        assertTrue(after.fraction < before.fraction)
    }

    @Test fun shortPageAtBothBoundariesStartsAtZero() {
        assertEquals(0f, sample(0, 500).progress.fraction)
    }

    @Test fun pageStartWinsEvenWhenViewportCenterShowsAnotherPage() {
        val twoPages = buildReaderPageProgressSlots(listOf(
            ReaderPageProgressEntryRef("first", 1), ReaderPageProgressEntryRef("second", 2),
        ))
        val result = calculateReaderPageProgressSample(twoPages, listOf(
            ReaderPageProgressVisibleItem(0, 0, 100), ReaderPageProgressVisibleItem(1, 100, 2000),
        ), 0, 800, 2, 0, 0)!!
        assertEquals(1, result.progress.page)
        assertEquals(0f, result.progress.fraction)
    }

    @Test fun terminalBoundaryStillReachesOneHundredPercent() {
        assertEquals(1f, sample(9200).progress.fraction)
    }

    @Test fun thumbCentersMapExactlyToEndpointsAndMiddleAcrossWindowSizes() {
        for (height in listOf(120f, 400f, 900f, 1250f)) {
            assertEquals(0f, readerTrackFraction(12f, height, 24f))
            assertEquals(0.5f, readerTrackFraction(height / 2f, height, 24f))
            assertEquals(1f, readerTrackFraction(height - 12f, height, 24f))
            assertEquals(0f, readerTrackFraction(-100f, height, 24f))
            assertEquals(1f, readerTrackFraction(height + 100f, height, 24f))
        }
    }

    @Test fun collapsedTrackHasFiniteProgress() {
        for (height in listOf(0f, 10f, 24f)) {
            assertEquals(0f, readerTrackFraction(12f, height, 24f))
        }
    }

    @Test fun nativeMinimumAndAncestorSafePaddingAreNotAddedTwice() {
        val density = Density(2f)
        val nativeBand = WindowInsets(top = 64.dp)
        val safeDrawing = WindowInsets(top = 24.dp, bottom = 20.dp)
        val combined = safeDrawing.union(nativeBand)
        assertEquals(128, combined.getTop(density))
        assertEquals(40, combined.getBottom(density))
        val remaining = combined.exclude(safeDrawing)
        assertEquals(80, remaining.getTop(density))
        assertEquals(0, remaining.getBottom(density))
        assertEquals(160, WindowInsets(top = 80.dp).union(nativeBand).getTop(density))
    }
}

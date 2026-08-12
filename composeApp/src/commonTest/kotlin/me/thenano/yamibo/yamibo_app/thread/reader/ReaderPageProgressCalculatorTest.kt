package me.thenano.yamibo.yamibo_app.thread.reader

import me.thenano.yamibo.yamibo_app.thread.reader.components.overlay.ReaderPageProgress
import me.thenano.yamibo.yamibo_app.thread.reader.components.overlay.ReaderPageProgressCursor
import me.thenano.yamibo.yamibo_app.thread.reader.components.overlay.ReaderPageProgressDirection
import me.thenano.yamibo.yamibo_app.thread.reader.components.overlay.ReaderPageProgressEntryRef
import me.thenano.yamibo.yamibo_app.thread.reader.components.overlay.ReaderPageProgressPageSnapshot
import me.thenano.yamibo.yamibo_app.thread.reader.components.overlay.ReaderPageProgressSample
import me.thenano.yamibo.yamibo_app.thread.reader.components.overlay.ReaderPageProgressStabilizer
import me.thenano.yamibo.yamibo_app.thread.reader.components.overlay.ReaderPageProgressVisibleItem
import me.thenano.yamibo.yamibo_app.thread.reader.components.overlay.buildReaderPageProgressSlots
import me.thenano.yamibo.yamibo_app.thread.reader.components.overlay.calculateReaderPageProgressSample
import me.thenano.yamibo.yamibo_app.thread.reader.components.overlay.readerPageEntryFraction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReaderPageProgressCalculatorTest {
    @Test
    fun legacyPostOrdinalFormulaCharacterizesImageEntryResetsAndIncompleteEnd() {
        val observedImageSequence = listOf(
            legacyPostOrdinalFraction(relativePostIndex = 0, itemRatio = 0.39f, pagePostCount = 3),
            legacyPostOrdinalFraction(relativePostIndex = 0, itemRatio = 0.78f, pagePostCount = 3),
            legacyPostOrdinalFraction(relativePostIndex = 0, itemRatio = 0.21f, pagePostCount = 3),
            legacyPostOrdinalFraction(relativePostIndex = 0, itemRatio = 0.60f, pagePostCount = 3),
            legacyPostOrdinalFraction(relativePostIndex = 0, itemRatio = 0f, pagePostCount = 3),
        )

        assertEquals(listOf(13, 26, 7, 20, 0), observedImageSequence.map(::percent))
        assertTrue(observedImageSequence.zipWithNext().any { (before, after) -> after < before })
        assertEquals(
            73,
            percent(legacyPostOrdinalFraction(relativePostIndex = 2, itemRatio = 0.19f, pagePostCount = 3)),
        )
    }

    @Test
    fun pageSlotsGiveEverySegmentAndPostOneConsecutivePosition() {
        val slots = buildReaderPageProgressSlots(
            listOf(
                entry("post-1-header"),
                entry("post-1-image-1"),
                entry("post-1-image-2"),
                entry("post-1-footer"),
                entry("post-1-banner"),
                entry("post-1-separator"),
                entry("post-2-whole"),
            )
        )

        val pageSlots = slots.byListIndex.values.toList()
        assertEquals((0..6).toList(), pageSlots.map { it.ordinalInPage })
        assertTrue(pageSlots.all { it.entryCountInPage == 7 })
        assertEquals(
            listOf(
                "post-1-header",
                "post-1-image-1",
                "post-1-image-2",
                "post-1-footer",
                "post-1-banner",
                "post-1-separator",
                "post-2-whole",
            ),
            slots.pages.getValue(1).entryKeys,
        )
    }

    @Test
    fun adjacentPageAppendAndPrependDoNotChangePageSnapshotOrOrdinals() {
        val base = buildReaderPageProgressSlots(listOf(entry("two-a", 2), entry("two-b", 2)))
        val appended = buildReaderPageProgressSlots(
            listOf(entry("two-a", 2), entry("two-b", 2), entry("three", 3))
        )
        val prepended = buildReaderPageProgressSlots(
            listOf(entry("one", 1), entry("two-a", 2), entry("two-b", 2))
        )

        assertEquals(base.pages.getValue(2), appended.pages.getValue(2))
        assertEquals(base.pages.getValue(2), prepended.pages.getValue(2))
        assertEquals(listOf(0, 1), slotsForPage(base, 2).map { it.ordinalInPage })
        assertEquals(listOf(0, 1), slotsForPage(appended, 2).map { it.ordinalInPage })
        assertEquals(listOf(0, 1), slotsForPage(prepended, 2).map { it.ordinalInPage })
    }

    @Test
    fun entryBoundaryHasTheSameFractionOnBothSides() {
        val slots = buildReaderPageProgressSlots(
            listOf(entry("header"), entry("image"), entry("footer"))
        ).byListIndex

        assertEquals(
            readerPageEntryFraction(slots.getValue(0), 1f),
            readerPageEntryFraction(slots.getValue(1), 0f),
            absoluteTolerance = 0.0001f,
        )
        assertEquals(
            readerPageEntryFraction(slots.getValue(1), 1f),
            readerPageEntryFraction(slots.getValue(2), 0f),
            absoluteTolerance = 0.0001f,
        )
    }

    @Test
    fun calculationUsesAllVisibleEntriesAndUnequalEntryHeights() {
        val slots = buildReaderPageProgressSlots(
            listOf(entry("header"), entry("large-image"), entry("footer"))
        )
        val sample = calculateReaderPageProgressSample(
            slots = slots,
            visibleItems = listOf(
                visible(index = 1, offset = -801, size = 1_000),
                visible(index = 2, offset = 199, size = 100),
            ),
            viewportStart = 0,
            viewportEnd = 400,
            totalPages = 1,
            firstVisibleItemIndex = 1,
            firstVisibleItemScrollOffset = 800,
        )

        assertEquals(2, sample?.cursor?.ordinalInPage)
        assertEquals(1f, sample?.progress?.fraction)
    }

    @Test
    fun calculationReportsDeterministicStartEndAndShortPage() {
        val tallPage = buildReaderPageProgressSlots(listOf(entry("only")))
        val start = calculateReaderPageProgressSample(
            slots = tallPage,
            visibleItems = listOf(visible(index = 0, offset = 0, size = 1_000)),
            viewportStart = 0,
            viewportEnd = 400,
            totalPages = 1,
            firstVisibleItemIndex = 0,
            firstVisibleItemScrollOffset = 0,
        )
        val end = calculateReaderPageProgressSample(
            slots = tallPage,
            visibleItems = listOf(visible(index = 0, offset = -600, size = 1_000)),
            viewportStart = 0,
            viewportEnd = 400,
            totalPages = 1,
            firstVisibleItemIndex = 0,
            firstVisibleItemScrollOffset = 600,
        )
        val shortPage = calculateReaderPageProgressSample(
            slots = tallPage,
            visibleItems = listOf(visible(index = 0, offset = 0, size = 200)),
            viewportStart = 0,
            viewportEnd = 400,
            totalPages = 1,
            firstVisibleItemIndex = 0,
            firstVisibleItemScrollOffset = 0,
        )

        assertEquals(0f, start?.progress?.fraction)
        assertEquals(1f, end?.progress?.fraction)
        assertEquals(1f, shortPage?.progress?.fraction)
    }

    @Test
    fun calculationReturnsNullForEmptyOrInvalidLayout() {
        val empty = buildReaderPageProgressSlots(emptyList())
        val one = buildReaderPageProgressSlots(listOf(entry("one")))

        assertNull(
            calculateReaderPageProgressSample(empty, emptyList(), 0, 400, 1, 0, 0)
        )
        assertNull(
            calculateReaderPageProgressSample(one, listOf(visible(0, 0, 100)), 400, 0, 1, 0, 0)
        )
        assertNull(
            calculateReaderPageProgressSample(one, listOf(visible(9, 0, 100)), 0, 400, 1, 9, 0)
        )
    }

    @Test
    fun stabilizerPreventsForwardOrIdleRemeasurementDecreaseAndAllowsReverse() {
        val stabilizer = ReaderPageProgressStabilizer()
        val snapshot = snapshot(page = 1, keys = listOf("image"))

        assertEquals(0.4f, stabilizer.update(sample(snapshot, 0, 0.4f), ReaderPageProgressDirection.Forward)?.fraction)
        assertEquals(0.4f, stabilizer.update(sample(snapshot, 0, 0.2f), ReaderPageProgressDirection.Idle)?.fraction)
        assertEquals(0.4f, stabilizer.update(sample(snapshot, 0, 0.3f), ReaderPageProgressDirection.Forward)?.fraction)
        assertEquals(0.2f, stabilizer.update(sample(snapshot, 0, 0.2f), ReaderPageProgressDirection.Backward)?.fraction)
    }

    @Test
    fun stabilizerAllowsEarlierEntryAndResetsForPageOrSnapshotChange() {
        val stabilizer = ReaderPageProgressStabilizer()
        val pageOne = snapshot(1, listOf("one", "two"))
        val rebuiltPageOne = snapshot(1, listOf("one", "inserted", "two"))
        val pageTwo = snapshot(2, listOf("other"))

        stabilizer.update(sample(pageOne, 1, 0.8f), ReaderPageProgressDirection.Forward)
        assertEquals(0.1f, stabilizer.update(sample(pageOne, 0, 0.1f), ReaderPageProgressDirection.Idle)?.fraction)
        assertEquals(0.05f, stabilizer.update(sample(rebuiltPageOne, 0, 0.05f), ReaderPageProgressDirection.Idle)?.fraction)
        assertEquals(0.2f, stabilizer.update(sample(pageTwo, 0, 0.2f), ReaderPageProgressDirection.Idle)?.fraction)
    }

    @Test
    fun forwardSyntheticSequenceIsMonotonicAndPageBoundaryIsIndependent() {
        val entries = listOf(entry("one-a", 1), entry("one-b", 1), entry("two-a", 2))
        val slots = buildReaderPageProgressSlots(entries)
        val pageOne = listOf(
            readerPageEntryFraction(slots.byListIndex.getValue(0), 0f),
            readerPageEntryFraction(slots.byListIndex.getValue(0), 0.5f),
            readerPageEntryFraction(slots.byListIndex.getValue(0), 1f),
            readerPageEntryFraction(slots.byListIndex.getValue(1), 0f),
            readerPageEntryFraction(slots.byListIndex.getValue(1), 0.5f),
            readerPageEntryFraction(slots.byListIndex.getValue(1), 1f),
        )

        assertEquals(0f, pageOne.first())
        assertEquals(1f, pageOne.last())
        assertTrue(pageOne.zipWithNext().all { (before, after) -> after >= before })
        assertEquals(0f, readerPageEntryFraction(slots.byListIndex.getValue(2), 0f))
        assertEquals(2, slots.byListIndex.getValue(2).forumPage)
    }

    private fun entry(key: String, page: Int = 1) = ReaderPageProgressEntryRef(key, page)

    private fun visible(index: Int, offset: Int, size: Int) =
        ReaderPageProgressVisibleItem(index, offset, size)

    private fun slotsForPage(slots: me.thenano.yamibo.yamibo_app.thread.reader.components.overlay.ReaderPageProgressSlots, page: Int) =
        slots.byListIndex.values.filter { it.forumPage == page }.sortedBy { it.ordinalInPage }

    private fun snapshot(page: Int, keys: List<String>) = ReaderPageProgressPageSnapshot(page, keys)

    private fun sample(
        snapshot: ReaderPageProgressPageSnapshot,
        ordinal: Int,
        fraction: Float,
    ) = ReaderPageProgressSample(
        progress = ReaderPageProgress(snapshot.forumPage, totalPages = 2, fraction = fraction),
        cursor = ReaderPageProgressCursor(snapshot.forumPage, ordinal, fraction),
        pageSnapshot = snapshot,
    )

    private fun legacyPostOrdinalFraction(
        relativePostIndex: Int,
        itemRatio: Float,
        pagePostCount: Int,
    ): Float = ((relativePostIndex + itemRatio) / pagePostCount.toFloat()).coerceIn(0f, 1f)

    private fun percent(fraction: Float): Int = (fraction * 100f).toInt()
}

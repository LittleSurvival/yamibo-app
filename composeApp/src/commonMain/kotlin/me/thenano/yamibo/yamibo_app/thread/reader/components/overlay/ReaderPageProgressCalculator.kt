package me.thenano.yamibo.yamibo_app.thread.reader.components.overlay

internal data class ReaderPageProgressEntryRef(
    val key: String,
    val forumPage: Int,
)

internal data class ReaderPageProgressPageSnapshot(
    val forumPage: Int,
    val entryKeys: List<String>,
)

internal data class ReaderPageProgressSlot(
    val listIndex: Int,
    val entryKey: String,
    val forumPage: Int,
    val ordinalInPage: Int,
    val entryCountInPage: Int,
    val firstListIndexInPage: Int,
    val lastListIndexInPage: Int,
    val pageSnapshot: ReaderPageProgressPageSnapshot,
)

internal data class ReaderPageProgressSlots(
    val byListIndex: Map<Int, ReaderPageProgressSlot>,
    val pages: Map<Int, ReaderPageProgressPageSnapshot>,
)

internal data class ReaderPageProgressVisibleItem(
    val index: Int,
    val offset: Int,
    val size: Int,
)

internal data class ReaderPageProgressCursor(
    val forumPage: Int,
    val ordinalInPage: Int,
    val positionInsideEntry: Float,
)

internal data class ReaderPageProgressSample(
    val progress: ReaderPageProgress,
    val cursor: ReaderPageProgressCursor,
    val pageSnapshot: ReaderPageProgressPageSnapshot,
)

internal enum class ReaderPageProgressDirection {
    Forward,
    Backward,
    Idle,
}

internal fun buildReaderPageProgressSlots(
    entries: List<ReaderPageProgressEntryRef>,
): ReaderPageProgressSlots {
    val indexedByPage = linkedMapOf<Int, MutableList<IndexedValue<ReaderPageProgressEntryRef>>>()
    entries.withIndex().forEach { indexed ->
        indexedByPage.getOrPut(indexed.value.forumPage) { mutableListOf() } += indexed
    }

    val pages = indexedByPage.mapValues { (forumPage, indexedEntries) ->
        ReaderPageProgressPageSnapshot(
            forumPage = forumPage,
            entryKeys = indexedEntries.map { it.value.key },
        )
    }
    val slots = buildMap {
        indexedByPage.forEach { (forumPage, indexedEntries) ->
            val snapshot = pages.getValue(forumPage)
            indexedEntries.forEachIndexed { ordinal, indexed ->
                put(
                    indexed.index,
                    ReaderPageProgressSlot(
                        listIndex = indexed.index,
                        entryKey = indexed.value.key,
                        forumPage = forumPage,
                        ordinalInPage = ordinal,
                        entryCountInPage = indexedEntries.size,
                        firstListIndexInPage = indexedEntries.first().index,
                        lastListIndexInPage = indexedEntries.last().index,
                        pageSnapshot = snapshot,
                    ),
                )
            }
        }
    }
    return ReaderPageProgressSlots(byListIndex = slots, pages = pages)
}

internal fun readerPageEntryFraction(
    slot: ReaderPageProgressSlot,
    positionInsideEntry: Float,
): Float = (
    (slot.ordinalInPage + positionInsideEntry.coerceIn(0f, 1f)) /
        slot.entryCountInPage.coerceAtLeast(1).toFloat()
    ).coerceIn(0f, 1f)

internal fun calculateReaderPageProgressSample(
    slots: ReaderPageProgressSlots,
    visibleItems: List<ReaderPageProgressVisibleItem>,
    viewportStart: Int,
    viewportEnd: Int,
    totalPages: Int,
    firstVisibleItemIndex: Int,
    firstVisibleItemScrollOffset: Int,
): ReaderPageProgressSample? {
    if (viewportEnd <= viewportStart || visibleItems.isEmpty() || slots.byListIndex.isEmpty()) return null

    val viewportCenter = (viewportStart + viewportEnd) / 2
    val selected = visibleItems.asSequence()
        .filter { it.size > 0 && it.index in slots.byListIndex }
        .minByOrNull { item ->
            when {
                viewportCenter < item.offset -> item.offset - viewportCenter
                viewportCenter > item.offset + item.size -> viewportCenter - (item.offset + item.size)
                else -> 0
            }
        }
        ?: return null
    val slot = slots.byListIndex.getValue(selected.index)
    val positionInsideEntry = (
        (viewportCenter - selected.offset).toFloat() / selected.size.coerceAtLeast(1).toFloat()
        ).coerceIn(0f, 1f)

    val firstPageListIndex = slot.firstListIndexInPage
    val lastPageListIndex = slot.lastListIndexInPage
    val finalVisibleItem = visibleItems.firstOrNull { it.index == lastPageListIndex }
    val isAtTerminalBoundary = finalVisibleItem != null &&
        finalVisibleItem.size > 0 &&
        finalVisibleItem.offset + finalVisibleItem.size <= viewportEnd
    val isAtInitialBoundary = firstVisibleItemIndex == firstPageListIndex &&
        firstVisibleItemScrollOffset == 0

    val fraction = when {
        isAtTerminalBoundary -> 1f
        isAtInitialBoundary -> 0f
        else -> readerPageEntryFraction(slot, positionInsideEntry)
    }
    return ReaderPageProgressSample(
        progress = ReaderPageProgress(
            page = slot.forumPage,
            totalPages = totalPages,
            fraction = fraction,
        ),
        cursor = ReaderPageProgressCursor(
            forumPage = slot.forumPage,
            ordinalInPage = slot.ordinalInPage,
            positionInsideEntry = positionInsideEntry,
        ),
        pageSnapshot = slot.pageSnapshot,
    )
}

internal class ReaderPageProgressStabilizer {
    private var previous: ReaderPageProgressSample? = null

    fun update(
        sample: ReaderPageProgressSample?,
        direction: ReaderPageProgressDirection,
    ): ReaderPageProgress? {
        if (sample == null) {
            previous = null
            return null
        }
        val old = previous
        val snapshotChanged = old == null ||
            old.pageSnapshot != sample.pageSnapshot ||
            old.progress.page != sample.progress.page
        val movedToEarlierEntry = old != null &&
            sample.cursor.ordinalInPage < old.cursor.ordinalInPage
        val allowDecrease = direction == ReaderPageProgressDirection.Backward || movedToEarlierEntry
        val fraction = if (!snapshotChanged && !allowDecrease) {
            maxOf(old.progress.fraction, sample.progress.fraction)
        } else {
            sample.progress.fraction
        }
        val stabilized = sample.copy(progress = sample.progress.copy(fraction = fraction))
        previous = stabilized
        return stabilized.progress
    }

    fun clear() {
        previous = null
    }
}

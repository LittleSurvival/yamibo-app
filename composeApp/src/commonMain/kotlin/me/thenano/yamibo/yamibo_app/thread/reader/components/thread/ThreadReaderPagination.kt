package me.thenano.yamibo.yamibo_app.thread.reader.components.thread

import me.thenano.yamibo.yamibo_app.repository.settings.ThreadReaderMode
import me.thenano.yamibo.yamibo_app.repository.settings.TouchZoneLayout
import me.thenano.yamibo.yamibo_app.thread.reader.components.manga.TouchAction
import me.thenano.yamibo.yamibo_app.thread.reader.components.manga.getTouchAction
import me.thenano.yamibo.yamibo_app.thread.reader.components.post.impl.HtmlBlock
import kotlin.math.roundToInt

internal enum class SinglePageTapAction {
    Prev,
    Next,
    Menu,
}

internal fun resolveThreadReaderTapAction(
    layout: TouchZoneLayout,
    xFraction: Float,
    yFraction: Float,
    reverseTouchZones: Boolean,
): SinglePageTapAction? {
    val action = if (layout == TouchZoneLayout.DISABLED) {
        SinglePageTapAction.Menu
    } else {
        when (getTouchAction(layout, xFraction, yFraction)) {
            TouchAction.PREV -> SinglePageTapAction.Prev
            TouchAction.NEXT -> SinglePageTapAction.Next
            TouchAction.MENU -> SinglePageTapAction.Menu
            null -> null
        }
    }
    return if (reverseTouchZones) action.reversePreviousAndNext() else action
}

private fun SinglePageTapAction?.reversePreviousAndNext(): SinglePageTapAction? = when (this) {
    SinglePageTapAction.Prev -> SinglePageTapAction.Next
    SinglePageTapAction.Next -> SinglePageTapAction.Prev
    SinglePageTapAction.Menu, null -> this
}

internal interface TextBoundarySegmenter {
    fun sentenceBoundaries(text: String, locale: String? = null): IntArray
    fun lineBreakBoundaries(text: String, locale: String? = null): IntArray
    fun graphemeBoundaries(text: String, locale: String? = null): IntArray
}

internal object UnicodeFallbackTextBoundarySegmenter : TextBoundarySegmenter {
    override fun sentenceBoundaries(text: String, locale: String?): IntArray =
        buildBoundaryArray(text.length) {
            for (index in text.indices) {
                val char = text[index]
                if (char == '\n') {
                    add(index + 1)
                    continue
                }
                if (char !in sentenceEndChars) continue
                var end = index + 1
                while (end < text.length && text[end] in sentenceClosingChars) end++
                add(end)
            }
        }

    override fun lineBreakBoundaries(text: String, locale: String?): IntArray =
        buildBoundaryArray(text.length) {
            for (index in text.indices) {
                val char = text[index]
                if (char == '\n' || char.isWhitespace() || char in softBreakChars) {
                    add(index + 1)
                }
            }
        }

    override fun graphemeBoundaries(text: String, locale: String?): IntArray =
        buildBoundaryArray(text.length) {
            var index = 0
            while (index < text.length) {
                index += text.graphemeStepAt(index)
                add(index)
            }
        }

    private val sentenceEndChars = setOf('。', '！', '？', '.', '!', '?')
    private val sentenceClosingChars = setOf('」', '』', '）', ')', ']', '】', '》', '"', '\'', '”', '’')
    private val softBreakChars = setOf('，', '、', '；', ';', ',', ':', '：', '。', '！', '？', '.', '!', '?')
}

internal object ThreadReaderTextBoundaries {
    val defaultSegmenter: TextBoundarySegmenter = CachingTextBoundarySegmenter(
        createPlatformTextBoundarySegmenter(UnicodeFallbackTextBoundarySegmenter)
    )
}

internal expect fun createPlatformTextBoundarySegmenter(
    fallback: TextBoundarySegmenter,
): TextBoundarySegmenter

internal class CachingTextBoundarySegmenter(
    private val delegate: TextBoundarySegmenter,
    private val maxEntries: Int = 128,
) : TextBoundarySegmenter {
    private val cache = mutableMapOf<BoundaryCacheKey, CachedBoundaries>()
    private val accessOrder = ArrayDeque<BoundaryCacheKey>()

    init {
        require(maxEntries > 0)
    }

    override fun sentenceBoundaries(text: String, locale: String?): IntArray =
        getOrCreate(text, locale).sentence

    override fun lineBreakBoundaries(text: String, locale: String?): IntArray =
        getOrCreate(text, locale).line

    override fun graphemeBoundaries(text: String, locale: String?): IntArray =
        getOrCreate(text, locale).grapheme

    internal val cacheSize: Int get() = cache.size

    private fun getOrCreate(text: String, locale: String?): CachedBoundaries {
        val key = BoundaryCacheKey(text, locale)
        cache[key]?.let { cached ->
            accessOrder.remove(key)
            accessOrder.addLast(key)
            return cached
        }
        val boundaries = CachedBoundaries(
            sentence = delegate.sentenceBoundaries(text, locale),
            line = delegate.lineBreakBoundaries(text, locale),
            grapheme = delegate.graphemeBoundaries(text, locale),
        )
        cache[key] = boundaries
        accessOrder.addLast(key)
        while (accessOrder.size > maxEntries) {
            cache.remove(accessOrder.removeFirst())
        }
        return boundaries
    }
}

private data class CachedBoundaries(
    val sentence: IntArray,
    val line: IntArray,
    val grapheme: IntArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as CachedBoundaries

        if (!sentence.contentEquals(other.sentence)) return false
        if (!line.contentEquals(other.line)) return false
        if (!grapheme.contentEquals(other.grapheme)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = sentence.contentHashCode()
        result = 31 * result + line.contentHashCode()
        result = 31 * result + grapheme.contentHashCode()
        return result
    }
}

private data class BoundaryCacheKey(
    val text: String,
    val locale: String?,
)

internal data class SafeBreakMap(
    val blockId: String,
    val textLength: Int,
    val paragraphBreaks: IntArray,
    val sentenceBreaks: IntArray,
    val lineBreaks: IntArray,
    val graphemeBreaks: IntArray,
    val forbiddenRanges: List<IntRange>,
    internal val semanticMeasuredBreaks: IntArray = mergeSortedDistinctBoundaries(
        paragraphBreaks,
        sentenceBreaks,
        forbiddenRanges,
    ),
    internal val fallbackMeasuredBreaks: IntArray = mergeSortedDistinctBoundaries(
        lineBreaks,
        graphemeBreaks,
        forbiddenRanges,
    ),
) {
    fun bestBreak(
        start: Int,
        maxEndExclusive: Int,
        preferSentence: Boolean = true,
    ): Int {
        val maxEnd = maxEndExclusive.coerceIn(start + 1, textLength)
        paragraphBreaks.lastValidBoundary(start, maxEnd, forbiddenRanges)?.let { return it }
        if (preferSentence) {
            sentenceBreaks.lastValidBoundary(start, maxEnd, forbiddenRanges)?.let { return it }
        }
        lineBreaks.lastValidBoundary(start, maxEnd, forbiddenRanges)?.let { return it }
        return graphemeBreaks.lastValidBoundary(start, maxEnd, forbiddenRanges) ?: maxEnd
    }

    fun contains(offset: Int): Boolean = offset in 0..textLength

    private fun isInsideForbiddenRange(offset: Int): Boolean =
        forbiddenRanges.any { range -> offset > range.first && offset < range.last }

    internal fun bestBreakReference(
        start: Int,
        maxEndExclusive: Int,
        preferSentence: Boolean = true,
    ): Int {
        val maxEnd = maxEndExclusive.coerceIn(start + 1, textLength)
        val candidates = buildList {
            add(paragraphBreaks)
            if (preferSentence) add(sentenceBreaks)
            add(lineBreaks)
            add(graphemeBreaks)
        }
        candidates.forEach { boundaries ->
            boundaries
                .asSequence()
                .filter { it in (start + 1)..maxEnd }
                .filterNot(::isInsideForbiddenRange)
                .maxOrNull()
                ?.let { return it }
        }
        return graphemeBreaks
            .asSequence()
            .filter { it in (start + 1)..maxEnd }
            .filterNot(::isInsideForbiddenRange)
            .maxOrNull()
            ?: maxEnd
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SafeBreakMap) return false
        return blockId == other.blockId &&
            textLength == other.textLength &&
            paragraphBreaks.contentEquals(other.paragraphBreaks) &&
            sentenceBreaks.contentEquals(other.sentenceBreaks) &&
            lineBreaks.contentEquals(other.lineBreaks) &&
            graphemeBreaks.contentEquals(other.graphemeBreaks) &&
            forbiddenRanges == other.forbiddenRanges &&
            semanticMeasuredBreaks.contentEquals(other.semanticMeasuredBreaks) &&
            fallbackMeasuredBreaks.contentEquals(other.fallbackMeasuredBreaks)
    }

    override fun hashCode(): Int {
        var result = blockId.hashCode()
        result = 31 * result + textLength
        result = 31 * result + paragraphBreaks.contentHashCode()
        result = 31 * result + sentenceBreaks.contentHashCode()
        result = 31 * result + lineBreaks.contentHashCode()
        result = 31 * result + graphemeBreaks.contentHashCode()
        result = 31 * result + forbiddenRanges.hashCode()
        result = 31 * result + semanticMeasuredBreaks.contentHashCode()
        result = 31 * result + fallbackMeasuredBreaks.contentHashCode()
        return result
    }
}

private fun mergeSortedDistinctBoundaries(
    first: IntArray,
    second: IntArray,
    forbiddenRanges: List<IntRange>,
): IntArray = (first.asSequence() + second.asSequence())
    .filterNot { offset -> forbiddenRanges.any { range -> offset > range.first && offset < range.last } }
    .distinct()
    .sorted()
    .toList()
    .toIntArray()

private fun IntArray.lastValidBoundary(
    start: Int,
    maxEnd: Int,
    forbiddenRanges: List<IntRange>,
): Int? {
    var low = 0
    var high = lastIndex
    var bestIndex = -1
    while (low <= high) {
        val middle = (low + high) ushr 1
        if (this[middle] <= maxEnd) {
            bestIndex = middle
            low = middle + 1
        } else {
            high = middle - 1
        }
    }
    while (bestIndex >= 0) {
        val candidate = this[bestIndex]
        if (candidate <= start) return null
        if (forbiddenRanges.none { range -> candidate > range.first && candidate < range.last }) {
            return candidate
        }
        bestIndex--
    }
    return null
}

internal fun buildSafeBreakMap(
    block: HtmlBlock.Text,
    segmenter: TextBoundarySegmenter = ThreadReaderTextBoundaries.defaultSegmenter,
    locale: String? = null,
): SafeBreakMap {
    val text = block.annotatedString.text
    return SafeBreakMap(
        blockId = block.anchorId,
        textLength = text.length,
        paragraphBreaks = paragraphBoundaries(text),
        sentenceBreaks = segmenter.sentenceBoundaries(text, locale).normalizedBoundaries(text.length),
        lineBreaks = segmenter.lineBreakBoundaries(text, locale).normalizedBoundaries(text.length),
        graphemeBreaks = segmenter.graphemeBoundaries(text, locale).normalizedBoundaries(text.length),
        forbiddenRanges = block.rubies.map { it.start until it.end },
    )
}

internal data class ThreadReaderReadingAnchor(
    val postId: Long,
    val blockId: String?,
    val textOffset: Int?,
    val blockRatio: Float?,
    val affinity: AnchorAffinity = AnchorAffinity.Center,
)

internal enum class AnchorAffinity {
    Center,
    End,
}

internal data class ThreadReaderAnchorRange(
    val postId: Long,
    val blockId: String?,
    val startOffset: Int?,
    val endOffset: Int?,
) {
    fun contains(anchor: ThreadReaderReadingAnchor): Boolean {
        if (anchor.postId != postId) return false
        if (anchor.blockId != null && blockId != null && anchor.blockId != blockId) return false
        val offset = anchor.textOffset ?: return anchor.blockId == null || anchor.blockId == blockId
        val start = startOffset ?: return true
        val end = endOffset ?: return true
        return offset in start..<end
    }
}

internal data class ThreadReaderPlannedPage(
    val postId: Long,
    val pageIndexInPost: Int,
    val totalPagesInPost: Int,
    val estimatedHeightPx: Int,
    val anchorRange: ThreadReaderAnchorRange,
    val slices: List<ThreadReaderPageSlice>,
)

internal sealed interface ThreadReaderPageSlice {
    val blockId: String?
    val estimatedHeightPx: Int

    data class Text(
        override val blockId: String,
        val startOffset: Int,
        val endOffset: Int,
        override val estimatedHeightPx: Int,
    ) : ThreadReaderPageSlice

    data class Block(
        override val blockId: String?,
        override val estimatedHeightPx: Int,
        val semanticType: String,
        val nestedSlices: List<ThreadReaderPageSlice> = emptyList(),
        val semanticStart: Int? = null,
        val semanticEnd: Int? = null,
    ) : ThreadReaderPageSlice
}

internal fun ThreadReaderPlannedPage.semanticStableId(): String {
    val semanticRange = slices.joinToString(separator = ";", transform = ThreadReaderPageSlice::stableDescriptor).ifEmpty {
        "anchor:${anchorRange.blockId.orEmpty()}:${anchorRange.startOffset ?: -1}-${anchorRange.endOffset ?: -1}"
    }
    return "$postId|$semanticRange"
}

private fun ThreadReaderPageSlice.stableDescriptor(): String = when (this) {
    is ThreadReaderPageSlice.Text -> "text:$blockId:$startOffset-$endOffset"
    is ThreadReaderPageSlice.Block ->
        "block:${blockId.orEmpty()}:$semanticType:${semanticStart ?: -1}-${semanticEnd ?: -1}:" +
            nestedSlices.joinToString(",", transform = ThreadReaderPageSlice::stableDescriptor)
}

internal fun sliceHtmlBlocksForPage(
    blocks: List<HtmlBlock>,
    slices: List<ThreadReaderPageSlice>,
): List<HtmlBlock> = slices.mapNotNull { slice ->
    blocks.firstNotNullOfOrNull { block -> block.sliceForPage(slice) }
}

private fun HtmlBlock.sliceForPage(slice: ThreadReaderPageSlice): HtmlBlock? = when {
    this is HtmlBlock.Text && slice is ThreadReaderPageSlice.Text && anchorId == slice.blockId -> copy(
        annotatedString = annotatedString.subSequence(slice.startOffset, slice.endOffset),
        rubies = rubies.filter { ruby ->
            ruby.start >= slice.startOffset && ruby.end <= slice.endOffset
        }.map { ruby ->
            ruby.copy(
                start = ruby.start - slice.startOffset,
                end = ruby.end - slice.startOffset,
            )
        },
        anchorId = "${anchorId}:${slice.startOffset}-${slice.endOffset}",
    )
    slice is ThreadReaderPageSlice.Block && anchorId == slice.blockId -> when (this) {
        is HtmlBlock.Quote -> copy(
            contentBlocks = if (slice.nestedSlices.isEmpty()) contentBlocks
            else sliceHtmlBlocksForPage(contentBlocks, slice.nestedSlices)
        )
        is HtmlBlock.Collapse -> copy(
            contentBlocks = if (slice.nestedSlices.isEmpty()) contentBlocks
            else sliceHtmlBlocksForPage(contentBlocks, slice.nestedSlices)
        )
        is HtmlBlock.Locked -> copy(
            contentBlocks = if (slice.nestedSlices.isEmpty()) contentBlocks
            else sliceHtmlBlocksForPage(contentBlocks, slice.nestedSlices)
        )
        is HtmlBlock.Code -> {
            val lines = codeText.split('\n')
            val start = slice.semanticStart?.coerceIn(0, lines.size) ?: 0
            val end = slice.semanticEnd?.coerceIn(start, lines.size) ?: lines.size
            copy(codeText = lines.subList(start, end).joinToString("\n"))
        }
        is HtmlBlock.Table -> {
            val start = slice.semanticStart?.coerceIn(0, rows.size) ?: 0
            val end = slice.semanticEnd?.coerceIn(start, rows.size) ?: rows.size
            copy(rows = rows.subList(start, end))
        }
        else -> this
    }
    else -> null
}

private fun ThreadReaderPageSlice.flatten(): Sequence<ThreadReaderPageSlice> = sequence {
    yield(this@flatten)
    if (this@flatten is ThreadReaderPageSlice.Block) {
        nestedSlices.forEach { yieldAll(it.flatten()) }
    }
}

private fun findPageIndexForAnchor(
    pages: List<ThreadReaderPlannedPage>,
    anchor: ThreadReaderReadingAnchor,
): Int? {
    val matchingTextSlices = pages.flatMapIndexed { pageIndex, page ->
        page.slices.flatMap { it.flatten().toList() }
            .filterIsInstance<ThreadReaderPageSlice.Text>()
            .filter { it.blockId == anchor.blockId }
            .map { pageIndex to it }
    }
    val finalMatchingSlice = matchingTextSlices.lastOrNull()?.second

    pages.forEachIndexed { pageIndex, page ->
        if (page.postId != anchor.postId) return@forEachIndexed
        if (page.anchorRange.contains(anchor)) return pageIndex
        page.slices.asSequence().flatMap { it.flatten() }.forEach { slice ->
            if (slice.blockId != anchor.blockId) return@forEach
            when (slice) {
                is ThreadReaderPageSlice.Text -> {
                    val offset = anchor.textOffset
                    if (
                        offset == null ||
                        (offset >= slice.startOffset && offset < slice.endOffset) ||
                        (slice === finalMatchingSlice && offset == slice.endOffset)
                    ) {
                        return pageIndex
                    }
                }
                is ThreadReaderPageSlice.Block -> if (anchor.textOffset == null) return pageIndex
            }
        }
    }
    return null
}

internal data class SinglePagePlanTransition(
    val pageIndex: Int,
    val stablePageId: String,
)

internal fun resolveSinglePagePlanTransition(
    previousPages: List<ThreadReaderPlannedPage>,
    candidatePages: List<ThreadReaderPlannedPage>,
    previousPageIndex: Int,
    previousStablePageId: String?,
    anchor: ThreadReaderReadingAnchor?,
): SinglePagePlanTransition? {
    if (candidatePages.isEmpty()) return null

    previousStablePageId?.let { stableId ->
        candidatePages.indexOfFirst { it.semanticStableId() == stableId }
            .takeIf { it >= 0 }
            ?.let { index -> return SinglePagePlanTransition(index, stableId) }
    }

    if (anchor != null) {
        findPageIndexForAnchor(candidatePages, anchor)?.let { index ->
            return SinglePagePlanTransition(index, candidatePages[index].semanticStableId())
        }
    }

    val previousPage = previousPages.getOrNull(previousPageIndex)
    val postId = anchor?.postId ?: previousPage?.postId
    val postPages = candidatePages.withIndex().filter { it.value.postId == postId }
    if (postPages.isNotEmpty()) {
        val previousPostPages = previousPages.filter { it.postId == postId }
        val previousPostIndex = previousPostPages.indexOf(previousPage).coerceAtLeast(0)
        val ratio = if (previousPostPages.size > 1) {
            previousPostIndex.toFloat() / previousPostPages.lastIndex
        } else {
            0f
        }
        val target = postPages[(ratio * postPages.lastIndex).roundToInt().coerceIn(0, postPages.lastIndex)]
        return SinglePagePlanTransition(target.index, target.value.semanticStableId())
    }

    val fallbackIndex = previousPageIndex.coerceIn(0, candidatePages.lastIndex)
    return SinglePagePlanTransition(fallbackIndex, candidatePages[fallbackIndex].semanticStableId())
}

internal data class ThreadReaderPaginationInput(
    val postId: Long,
    val blocks: List<HtmlBlock>,
    val viewportHeightPx: Int,
    val estimatedCharsPerLine: Int,
    val estimatedLineHeightPx: Int,
    val verticalPaddingPx: Int = 0,
    val imageHeightFor: (HtmlBlock.Image) -> Int? = { null },
    val contentWidthPx: Int = 1,
    val imageHeightToWidthRatioFor: (HtmlBlock.Image) -> Float? = { null },
    val textHeightFor: ((HtmlBlock.Text, Int, Int) -> Int)? = null,
)

internal enum class ThreadReaderPaginationStrategy {
    Reference,
    Optimized,
}

internal data class ThreadReaderPlanningMetricsSnapshot(
    val normalizedBlockBuilds: Int,
    val safeBreakPreparations: Int,
    val candidateMaterializations: Int,
    val textHeightProbes: Int,
    val textHeightProbeCacheHits: Int,
    val postPlanBuilds: Int,
    val normalizedBlockCacheHits: Int,
    val postPlanCacheHits: Int,
)

internal class ThreadReaderPlanningMetrics {
    internal var normalizedBlockBuilds: Int = 0
    internal var safeBreakPreparations: Int = 0
    internal var candidateMaterializations: Int = 0
    internal var textHeightProbes: Int = 0
    internal var textHeightProbeCacheHits: Int = 0
    internal var postPlanBuilds: Int = 0
    internal var normalizedBlockCacheHits: Int = 0
    internal var postPlanCacheHits: Int = 0

    internal fun snapshot(): ThreadReaderPlanningMetricsSnapshot = ThreadReaderPlanningMetricsSnapshot(
        normalizedBlockBuilds = normalizedBlockBuilds,
        safeBreakPreparations = safeBreakPreparations,
        candidateMaterializations = candidateMaterializations,
        textHeightProbes = textHeightProbes,
        textHeightProbeCacheHits = textHeightProbeCacheHits,
        postPlanBuilds = postPlanBuilds,
        normalizedBlockCacheHits = normalizedBlockCacheHits,
        postPlanCacheHits = postPlanCacheHits,
    )
}

internal data class ThreadReaderPlanDescriptor(
    val pages: List<ThreadReaderPageDescriptor>,
)

internal data class ThreadReaderPageDescriptor(
    val postId: Long,
    val pageIndexInPost: Int,
    val totalPagesInPost: Int,
    val estimatedHeightPx: Int,
    val anchorRange: ThreadReaderAnchorRange,
    val semanticStableId: String,
    val slices: List<ThreadReaderSliceDescriptor>,
)

internal data class ThreadReaderSliceDescriptor(
    val kind: String,
    val blockId: String?,
    val estimatedHeightPx: Int,
    val startOffset: Int?,
    val endOffset: Int?,
    val semanticType: String?,
    val semanticStart: Int?,
    val semanticEnd: Int?,
    val nestedSlices: List<ThreadReaderSliceDescriptor>,
)

internal fun List<ThreadReaderPlannedPage>.planningDescriptor(): ThreadReaderPlanDescriptor =
    ThreadReaderPlanDescriptor(
        pages = map { page ->
            ThreadReaderPageDescriptor(
                postId = page.postId,
                pageIndexInPost = page.pageIndexInPost,
                totalPagesInPost = page.totalPagesInPost,
                estimatedHeightPx = page.estimatedHeightPx,
                anchorRange = page.anchorRange,
                semanticStableId = page.semanticStableId(),
                slices = page.slices.map(ThreadReaderPageSlice::planningDescriptor),
            )
        },
    )

private fun ThreadReaderPageSlice.planningDescriptor(): ThreadReaderSliceDescriptor = when (this) {
    is ThreadReaderPageSlice.Text -> ThreadReaderSliceDescriptor(
        kind = "Text",
        blockId = blockId,
        estimatedHeightPx = estimatedHeightPx,
        startOffset = startOffset,
        endOffset = endOffset,
        semanticType = null,
        semanticStart = null,
        semanticEnd = null,
        nestedSlices = emptyList(),
    )
    is ThreadReaderPageSlice.Block -> ThreadReaderSliceDescriptor(
        kind = "Block",
        blockId = blockId,
        estimatedHeightPx = estimatedHeightPx,
        startOffset = null,
        endOffset = null,
        semanticType = semanticType,
        semanticStart = semanticStart,
        semanticEnd = semanticEnd,
        nestedSlices = nestedSlices.map(ThreadReaderPageSlice::planningDescriptor),
    )
}

internal data class ThreadReaderPlanningMismatch(
    val firstDifferentPageIndex: Int,
    val referencePageCount: Int,
    val optimizedPageCount: Int,
    val referencePage: ThreadReaderPageDescriptor?,
    val optimizedPage: ThreadReaderPageDescriptor?,
)

private data class TextHeightProbeKey(
    val block: HtmlBlock.Text,
    val startOffset: Int,
    val endOffset: Int,
)

private data class PendingTextSlice(
    val block: HtmlBlock.Text,
    val breakMap: SafeBreakMap,
    val startOffset: Int,
    val endOffset: Int,
    val estimatedHeightPx: Int,
) {
    fun toPageSlice(): ThreadReaderPageSlice.Text =
        ThreadReaderPageSlice.Text(
            blockId = block.anchorId,
            startOffset = startOffset,
            endOffset = endOffset,
            estimatedHeightPx = estimatedHeightPx,
        )
}

internal data class ThreadReaderPageProgressState(
    val currentPageIndex: Int,
    val currentEntryKey: String,
    val forumPage: Int,
    val currentPostPageIndex: Int,
    val currentPostPageCount: Int,
    val currentPostId: Long,
)

internal fun buildThreadReaderPageProgressStates(
    pageRefs: List<Pair<String, ThreadReaderPlannedPage>>,
    pageByPostId: Map<Long, Int>,
    initialForumPage: Int,
): Map<Int, ThreadReaderPageProgressState> =
    buildMap {
        pageRefs.withIndex().groupBy { (_, ref) -> ref.second.postId }.forEach { (postId, pages) ->
            pages.forEachIndexed { postPageIndex, indexedRef ->
                val threadPageIndex = indexedRef.index
                val entryKey = indexedRef.value.first
                put(
                    threadPageIndex,
                    ThreadReaderPageProgressState(
                        currentPageIndex = threadPageIndex,
                        currentEntryKey = entryKey,
                        forumPage = pageByPostId[postId] ?: initialForumPage,
                        currentPostPageIndex = postPageIndex,
                        currentPostPageCount = pages.size.coerceAtLeast(1),
                        currentPostId = postId,
                    )
                )
            }
        }
    }

internal fun captureSinglePageViewportAnchor(
    page: ThreadReaderPlannedPage,
    viewportHeightPx: Int,
    topOverlayPx: Int = 0,
    bottomOverlayPx: Int = 0,
): ThreadReaderReadingAnchor {
    val safeViewportHeight = viewportHeightPx.coerceAtLeast(1)
    val contentTop = topOverlayPx.coerceIn(0, safeViewportHeight)
    val contentBottom = (safeViewportHeight - bottomOverlayPx.coerceAtLeast(0))
        .coerceIn(contentTop + 1, safeViewportHeight)
    val focusY = (contentTop + contentBottom) / 2
    val pageHeight = page.estimatedHeightPx.coerceAtLeast(1)
    val targetY = focusY.coerceIn(0, pageHeight)

    var accumulated = 0
    page.slices.forEach { slice ->
        val sliceHeight = slice.estimatedHeightPx.coerceAtLeast(1)
        val sliceEnd = accumulated + sliceHeight
        if (targetY <= sliceEnd) {
            val sliceRatio = ((targetY - accumulated).toFloat() / sliceHeight).coerceIn(0f, 1f)
            return when (slice) {
                is ThreadReaderPageSlice.Text -> {
                    val offset = slice.startOffset +
                        ((slice.endOffset - slice.startOffset) * sliceRatio).toInt()
                    ThreadReaderReadingAnchor(
                        postId = page.postId,
                        blockId = slice.blockId,
                        textOffset = offset.coerceIn(slice.startOffset, slice.endOffset),
                        blockRatio = sliceRatio,
                        affinity = AnchorAffinity.Center,
                    )
                }
                is ThreadReaderPageSlice.Block -> ThreadReaderReadingAnchor(
                    postId = page.postId,
                    blockId = slice.blockId,
                    textOffset = null,
                    blockRatio = sliceRatio,
                    affinity = AnchorAffinity.Center,
                )
            }
        }
        accumulated = sliceEnd
    }

    return ThreadReaderReadingAnchor(
        postId = page.postId,
        blockId = page.anchorRange.blockId,
        textOffset = page.anchorRange.startOffset,
        blockRatio = null,
        affinity = AnchorAffinity.Center,
    )
}

internal fun singlePageDeltaForPhysicalDrag(mode: ThreadReaderMode, physicalDrag: Float): Int =
    when (mode) {
        ThreadReaderMode.SINGLE_LTR -> when {
            physicalDrag < 0f -> 1
            physicalDrag > 0f -> -1
            else -> 0
        }
        ThreadReaderMode.SINGLE_RTL -> when {
            physicalDrag < 0f -> -1
            physicalDrag > 0f -> 1
            else -> 0
        }
        ThreadReaderMode.SINGLE_TTB -> when {
            physicalDrag < 0f -> 1
            physicalDrag > 0f -> -1
            else -> 0
        }
        ThreadReaderMode.SCROLL_CONTINUOUS -> 0
    }

internal fun physicalDragForSinglePageDelta(mode: ThreadReaderMode, delta: Int): Float =
    when (mode) {
        ThreadReaderMode.SINGLE_LTR -> when {
            delta > 0 -> -1f
            delta < 0 -> 1f
            else -> 0f
        }
        ThreadReaderMode.SINGLE_RTL -> when {
            delta > 0 -> 1f
            delta < 0 -> -1f
            else -> 0f
        }
        ThreadReaderMode.SINGLE_TTB -> when {
            delta > 0 -> -1f
            delta < 0 -> 1f
            else -> 0f
        }
        ThreadReaderMode.SCROLL_CONTINUOUS -> 0f
    }

internal fun singlePageDeltaForTouchAction(
    mode: ThreadReaderMode,
    action: SinglePageTapAction?,
): Int {
    val delta = when (action) {
        SinglePageTapAction.Prev -> -1
        SinglePageTapAction.Next -> 1
        else -> 0
    }
    return if (mode == ThreadReaderMode.SINGLE_RTL) -delta else delta
}

internal fun planFixedHeightReaderPages(
    input: ThreadReaderPaginationInput,
    segmenter: TextBoundarySegmenter = ThreadReaderTextBoundaries.defaultSegmenter,
    locale: String? = null,
    strategy: ThreadReaderPaginationStrategy = ThreadReaderPaginationStrategy.Optimized,
    metrics: ThreadReaderPlanningMetrics? = null,
): List<ThreadReaderPlannedPage> {
    metrics?.let { it.postPlanBuilds++ }
    val sourceTextHeightFor = input.textHeightFor
    val probeCache = if (strategy == ThreadReaderPaginationStrategy.Optimized && sourceTextHeightFor != null) {
        mutableMapOf<TextHeightProbeKey, Int>()
    } else {
        null
    }
    val measuredInput = if (sourceTextHeightFor == null) {
        input
    } else {
        input.copy(
            textHeightFor = { block, start, end ->
                val key = TextHeightProbeKey(block, start, end)
                probeCache?.get(key)?.let { cached ->
                    metrics?.let { it.textHeightProbeCacheHits++ }
                    return@copy cached
                }
                metrics?.let { it.textHeightProbes++ }
                sourceTextHeightFor(block, start, end).also { measured ->
                    probeCache?.put(key, measured)
                }
            },
        )
    }
    return planFixedHeightReaderPagesCore(measuredInput, segmenter, locale, strategy, metrics)
}

internal fun planFixedHeightReaderPagesReference(
    input: ThreadReaderPaginationInput,
    segmenter: TextBoundarySegmenter = ThreadReaderTextBoundaries.defaultSegmenter,
    locale: String? = null,
    metrics: ThreadReaderPlanningMetrics? = null,
): List<ThreadReaderPlannedPage> = planFixedHeightReaderPages(
    input = input,
    segmenter = segmenter,
    locale = locale,
    strategy = ThreadReaderPaginationStrategy.Reference,
    metrics = metrics,
)

internal fun planFixedHeightReaderPagesDifferential(
    input: ThreadReaderPaginationInput,
    segmenter: TextBoundarySegmenter = ThreadReaderTextBoundaries.defaultSegmenter,
    locale: String? = null,
    metrics: ThreadReaderPlanningMetrics? = null,
    onMismatch: (ThreadReaderPlanningMismatch) -> Unit = {},
): List<ThreadReaderPlannedPage> {
    val reference = planFixedHeightReaderPagesReference(input, segmenter, locale)
    val optimized = planFixedHeightReaderPages(input, segmenter, locale, metrics = metrics)
    return chooseDifferentialPlan(reference, optimized, onMismatch)
}

internal fun chooseDifferentialPlan(
    reference: List<ThreadReaderPlannedPage>,
    optimized: List<ThreadReaderPlannedPage>,
    onMismatch: (ThreadReaderPlanningMismatch) -> Unit = {},
): List<ThreadReaderPlannedPage> {
    val referenceDescriptor = reference.planningDescriptor()
    val optimizedDescriptor = optimized.planningDescriptor()
    if (referenceDescriptor == optimizedDescriptor) return optimized

    val pageIndex = (0 until maxOf(referenceDescriptor.pages.size, optimizedDescriptor.pages.size))
        .firstOrNull { index -> referenceDescriptor.pages.getOrNull(index) != optimizedDescriptor.pages.getOrNull(index) }
        ?: 0
    onMismatch(
        ThreadReaderPlanningMismatch(
            firstDifferentPageIndex = pageIndex,
            referencePageCount = referenceDescriptor.pages.size,
            optimizedPageCount = optimizedDescriptor.pages.size,
            referencePage = referenceDescriptor.pages.getOrNull(pageIndex),
            optimizedPage = optimizedDescriptor.pages.getOrNull(pageIndex),
        )
    )
    return reference
}

private fun planFixedHeightReaderPagesCore(
    input: ThreadReaderPaginationInput,
    segmenter: TextBoundarySegmenter,
    locale: String?,
    strategy: ThreadReaderPaginationStrategy,
    metrics: ThreadReaderPlanningMetrics?,
): List<ThreadReaderPlannedPage> {
    val maxPageHeight = (input.viewportHeightPx - input.verticalPaddingPx).coerceAtLeast(1)
    val pages = mutableListOf<MutableList<ThreadReaderPageSlice>>()
    var current = mutableListOf<ThreadReaderPageSlice>()
    var currentHeight = 0

    fun flush() {
        if (current.isEmpty()) return
        pages += current
        current = mutableListOf()
        currentHeight = 0
    }

    fun appendSlice(slice: ThreadReaderPageSlice) {
        val height = slice.estimatedHeightPx.coerceAtLeast(1)
        if (current.isNotEmpty() && currentHeight + height > maxPageHeight) flush()
        current += slice
        currentHeight += height
        if (currentHeight >= maxPageHeight) flush()
    }

    fun appendTextSlice(pending: PendingTextSlice) {
        val height = pending.estimatedHeightPx.coerceAtLeast(1)
        if (current.isNotEmpty() && currentHeight + height > maxPageHeight) {
            flush()
        }
        current += pending.toPageSlice()
        currentHeight += height
        if (currentHeight >= maxPageHeight) flush()
    }

    fun appendContainerSlices(
        block: HtmlBlock,
        contentBlocks: List<HtmlBlock>,
        semanticType: String,
    ) {
        val wrapperHeightPx = (input.estimatedLineHeightPx * 2).coerceAtMost(maxPageHeight / 3)
        val childHeightPx = (maxPageHeight - wrapperHeightPx).coerceAtLeast(input.estimatedLineHeightPx)
        val childPages = planFixedHeightReaderPagesCore(
            input = input.copy(
                blocks = contentBlocks,
                viewportHeightPx = childHeightPx,
                verticalPaddingPx = 0,
            ),
            segmenter = segmenter,
            locale = locale,
            strategy = strategy,
            metrics = metrics,
        )
        if (childPages.isEmpty()) {
            appendSlice(
                ThreadReaderPageSlice.Block(
                    blockId = block.anchorId,
                    estimatedHeightPx = wrapperHeightPx,
                    semanticType = semanticType,
                )
            )
            return
        }
        childPages.forEach { childPage ->
            appendSlice(
                ThreadReaderPageSlice.Block(
                    blockId = block.anchorId,
                    estimatedHeightPx = (childPage.estimatedHeightPx + wrapperHeightPx).coerceAtMost(maxPageHeight),
                    semanticType = semanticType,
                    nestedSlices = childPage.slices,
                )
            )
        }
    }

    fun appendCodeSlices(block: HtmlBlock.Code) {
        val wrapperHeightPx = input.estimatedLineHeightPx * 2
        val maxLines = (
            (maxPageHeight - wrapperHeightPx).coerceAtLeast(input.estimatedLineHeightPx) /
                input.estimatedLineHeightPx.coerceAtLeast(1)
            ).coerceAtLeast(1)
        val lines = block.codeText.splitToSequence('\n').toList()
        var startLine = 0
        while (startLine < lines.size) {
            val endLine = (startLine + maxLines).coerceAtMost(lines.size)
            appendSlice(
                ThreadReaderPageSlice.Block(
                    blockId = block.anchorId,
                    estimatedHeightPx = (
                        (endLine - startLine) * input.estimatedLineHeightPx + wrapperHeightPx
                        ).coerceAtMost(maxPageHeight),
                    semanticType = "Code",
                    semanticStart = startLine,
                    semanticEnd = endLine,
                )
            )
            startLine = endLine
        }
    }

    fun appendTableSlices(block: HtmlBlock.Table) {
        val estimatedRowHeight = input.estimatedLineHeightPx * 2
        val rowsPerPage = (maxPageHeight / estimatedRowHeight.coerceAtLeast(1)).coerceAtLeast(1)
        var startRow = 0
        while (startRow < block.rows.size) {
            val endRow = (startRow + rowsPerPage).coerceAtMost(block.rows.size)
            appendSlice(
                ThreadReaderPageSlice.Block(
                    blockId = block.anchorId,
                    estimatedHeightPx = ((endRow - startRow) * estimatedRowHeight).coerceAtMost(maxPageHeight),
                    semanticType = "Table",
                    semanticStart = startRow,
                    semanticEnd = endRow,
                )
            )
            startRow = endRow
        }
    }

    fun nextEstimatedHeight(block: HtmlBlock?): Int =
        when (block) {
            is HtmlBlock.Text -> estimateTextHeight(
                input = input,
                block = block,
                startOffset = 0,
                endOffset = block.annotatedString.text.trim().length.coerceAtMost(input.estimatedCharsPerLine),
            )
            is HtmlBlock.Image -> {
                val ratio = input.imageHeightToWidthRatioFor(block)?.takeIf { it > 0f } ?: 1.35f
                (input.contentWidthPx * ratio).toInt().coerceIn(1, maxPageHeight)
            }
            null -> 0
            else -> estimateAtomicBlockHeight(block, input.estimatedLineHeightPx, maxPageHeight)
        }

    input.blocks.forEachIndexed { blockIndex, block ->
        when (block) {
            is HtmlBlock.Text -> {
                metrics?.let { it.safeBreakPreparations++ }
                val breakMap = buildSafeBreakMap(block, segmenter, locale)
                val firstSliceHeight = estimateTextHeight(
                    input = input,
                    block = block,
                    startOffset = 0,
                    endOffset = block.annotatedString.text.trim().length.coerceAtMost(input.estimatedCharsPerLine),
                )
                if (
                    current.isNotEmpty() &&
                    block.shouldKeepWithNextTextBlock() &&
                    currentHeight + firstSliceHeight + nextEstimatedHeight(input.blocks.getOrNull(blockIndex + 1)) > maxPageHeight
                ) {
                    flush()
                }
                var start = 0
                while (start < breakMap.textLength) {
                    val availableHeight = (maxPageHeight - currentHeight).takeIf { current.isNotEmpty() }
                        ?: maxPageHeight
                    val measuredBreak = breakMap.furthestFittingBreak(
                        start = start,
                        availableHeightPx = availableHeight,
                        input = input,
                        block = block,
                        strategy = strategy,
                        metrics = metrics,
                    )
                    if (current.isNotEmpty() && measuredBreak != null && !measuredBreak.isSemanticBoundary) {
                        flush()
                        continue
                    }
                    val fittingEnd = (measuredBreak?.endOffset ?: (start + 1).coerceAtMost(breakMap.textLength))
                        .avoidShortCjkTrailingRun(
                        text = block.annotatedString.text,
                        start = start,
                        breakMap = breakMap,
                    )
                    val estimatedHeight = estimateTextHeight(
                        input = input,
                        block = block,
                        startOffset = start,
                        endOffset = fittingEnd,
                    )
                    val pendingSlice = PendingTextSlice(
                        block = block,
                        breakMap = breakMap,
                        startOffset = start,
                        endOffset = fittingEnd,
                        estimatedHeightPx = estimatedHeight,
                    ).avoidWidowOrphan(
                        input = input,
                        maxPageHeight = maxPageHeight,
                        currentHeight = currentHeight,
                    )
                    appendTextSlice(pendingSlice)
                    start = pendingSlice.endOffset
                }
            }
            is HtmlBlock.Image -> {
                val cachedHeight = input.imageHeightFor(block)?.takeIf { it > 0 }
                val estimatedHeight = when {
                    block.isEmoticon -> ((cachedHeight ?: (input.estimatedLineHeightPx * 2))).coerceIn(1, maxPageHeight)
                    cachedHeight != null -> cachedHeight.coerceIn(1, maxPageHeight)
                    else -> {
                        val ratio = input.imageHeightToWidthRatioFor(block)?.takeIf { it > 0f } ?: 1.35f
                        (input.contentWidthPx * ratio).toInt().coerceIn(1, maxPageHeight)
                    }
                }
                val isLargeImage = !block.isEmoticon && estimatedHeight >= (maxPageHeight * 0.82f).toInt()
                if (isLargeImage && current.isNotEmpty()) {
                    flush()
                }
                appendSlice(ThreadReaderPageSlice.Block(block.anchorId, estimatedHeight, "Image"))
                if (isLargeImage) {
                    flush()
                }
            }
            is HtmlBlock.Quote -> appendContainerSlices(block, block.contentBlocks, "Quote")
            is HtmlBlock.Collapse -> appendContainerSlices(block, block.contentBlocks, "Collapse")
            is HtmlBlock.Locked -> appendContainerSlices(block, block.contentBlocks, "Locked")
            is HtmlBlock.Table -> appendTableSlices(block)
            is HtmlBlock.Code -> appendCodeSlices(block)
            is HtmlBlock.Attachment,
            is HtmlBlock.Hr -> appendSlice(
                ThreadReaderPageSlice.Block(
                    blockId = block.anchorId,
                    estimatedHeightPx = estimateAtomicBlockHeight(block, input.estimatedLineHeightPx, maxPageHeight),
                    semanticType = block::class.simpleName ?: "Block",
                )
            )
        }
    }
    flush()

    val repackedPages = pages.repackSparseTrailingPages(maxPageHeight)
    val total = repackedPages.size.coerceAtLeast(1)
    return repackedPages.mapIndexed { index, slices ->
        val first = slices.firstOrNull()
        val firstText = first as? ThreadReaderPageSlice.Text
        val lastMatchingText = firstText?.let { anchorText ->
            slices.filterIsInstance<ThreadReaderPageSlice.Text>()
                .takeWhile { it.blockId == anchorText.blockId }
                .lastOrNull()
        }
        ThreadReaderPlannedPage(
            postId = input.postId,
            pageIndexInPost = index,
            totalPagesInPost = total,
            estimatedHeightPx = slices.sumOf { it.estimatedHeightPx },
            anchorRange = ThreadReaderAnchorRange(
                postId = input.postId,
                blockId = first?.blockId,
                startOffset = firstText?.startOffset,
                endOffset = lastMatchingText?.endOffset,
            ),
            slices = slices,
        )
    }
}

private fun SafeBreakMap.bestBreakWithFill(
    start: Int,
    maxEndExclusive: Int,
    minFillChars: Int,
    strategy: ThreadReaderPaginationStrategy,
): Int {
    fun select(preferSentence: Boolean): Int = when (strategy) {
        ThreadReaderPaginationStrategy.Reference -> bestBreakReference(start, maxEndExclusive, preferSentence)
        ThreadReaderPaginationStrategy.Optimized -> bestBreak(start, maxEndExclusive, preferSentence)
    }
    val sentenceEnd = select(preferSentence = true)
    if (sentenceEnd - start >= minFillChars || sentenceEnd >= textLength) return sentenceEnd
    val lineEnd = select(preferSentence = false)
    return when {
        lineEnd > sentenceEnd && lineEnd - start >= minFillChars -> lineEnd
        else -> sentenceEnd
    }
}

private data class FittingTextBreak(
    val endOffset: Int,
    val isSemanticBoundary: Boolean,
)

private fun SafeBreakMap.furthestFittingBreak(
    start: Int,
    availableHeightPx: Int,
    input: ThreadReaderPaginationInput,
    block: HtmlBlock.Text,
    strategy: ThreadReaderPaginationStrategy,
    metrics: ThreadReaderPlanningMetrics?,
): FittingTextBreak? {
    if (start >= textLength || availableHeightPx <= 0) return null
    if (input.textHeightFor == null) {
        val maxChars = estimateFittingChars(
            availableHeightPx = availableHeightPx,
            estimatedCharsPerLine = input.estimatedCharsPerLine,
            estimatedLineHeightPx = input.estimatedLineHeightPx,
        ).coerceAtLeast(1)
        return FittingTextBreak(
            endOffset = bestBreakWithFill(
                start = start,
                maxEndExclusive = start + maxChars,
                minFillChars = (maxChars * 0.72f).toInt().coerceAtLeast(1),
                strategy = strategy,
            ),
            isSemanticBoundary = true,
        )
    }

    fun furthestFittingReference(boundaries: Sequence<Int>): Int? {
        metrics?.let { it.candidateMaterializations++ }
        val candidates = boundaries
            .filter { it in (start + 1)..textLength }
            .filterNot(::isInsideForbiddenRangeForMeasuredSearch)
            .distinct()
            .sorted()
            .toList()
        if (candidates.isEmpty()) return null

        var low = 0
        var high = candidates.lastIndex
        var best: Int? = null
        while (low <= high) {
            val middle = (low + high) ushr 1
            val candidate = candidates[middle]
            if (estimateTextHeight(input, block, start, candidate) <= availableHeightPx) {
                best = candidate
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        return best
    }

    fun furthestFittingOptimized(candidates: IntArray): Int? {
        var first = 0
        var last = candidates.size
        while (first < last) {
            val middle = (first + last) ushr 1
            if (candidates[middle] <= start) first = middle + 1 else last = middle
        }
        if (first >= candidates.size) return null

        var low = first
        var high = candidates.lastIndex
        var best: Int? = null
        while (low <= high) {
            val middle = (low + high) ushr 1
            val candidate = candidates[middle]
            if (estimateTextHeight(input, block, start, candidate) <= availableHeightPx) {
                best = candidate
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        return best
    }

    fun select(referenceBoundaries: Sequence<Int>, optimizedBoundaries: IntArray): Int? =
        when (strategy) {
            ThreadReaderPaginationStrategy.Reference -> furthestFittingReference(referenceBoundaries)
            ThreadReaderPaginationStrategy.Optimized -> furthestFittingOptimized(optimizedBoundaries)
        }

    select(
        paragraphBreaks.asSequence() + sentenceBreaks.asSequence(),
        semanticMeasuredBreaks,
    )?.let {
        return FittingTextBreak(it, isSemanticBoundary = true)
    }
    select(
        lineBreaks.asSequence() + graphemeBreaks.asSequence(),
        fallbackMeasuredBreaks,
    )?.let {
        return FittingTextBreak(it, isSemanticBoundary = false)
    }
    return null
}

private fun SafeBreakMap.isInsideForbiddenRangeForMeasuredSearch(offset: Int): Boolean =
    forbiddenRanges.any { range -> offset > range.first && offset < range.last }

private fun PendingTextSlice.avoidWidowOrphan(
    input: ThreadReaderPaginationInput,
    maxPageHeight: Int,
    currentHeight: Int,
): PendingTextSlice {
    val remainingChars = breakMap.textLength - endOffset
    if (remainingChars <= 0) return this
    if (remainingChars > input.estimatedCharsPerLine / 2) return this
    val expandedHeight = estimateTextHeight(
        input = input,
        block = block,
        startOffset = startOffset,
        endOffset = breakMap.textLength,
    )
    return if (currentHeight + expandedHeight <= maxPageHeight) {
        copy(endOffset = breakMap.textLength, estimatedHeightPx = expandedHeight)
    } else {
        this
    }
}

internal fun Int.avoidShortCjkTrailingRun(
    text: String,
    start: Int,
    breakMap: SafeBreakMap,
): Int {
    val end = coerceIn(start + 1, breakMap.textLength)
    if (end >= breakMap.textLength) return end
    if (!text.getOrNull(end - 1).isCjkReaderChar() || !text.getOrNull(end).isCjkReaderChar()) return end
    val previousBreak = sequenceOf(
        breakMap.paragraphBreaks.asSequence(),
        breakMap.sentenceBreaks.asSequence(),
        breakMap.lineBreaks.asSequence(),
    ).flatten()
        .filter { it in (start + 1)..<end }
        .maxOrNull()
        ?: return end
    val trailingLength = end - previousBreak
    return if (trailingLength in 1..2) previousBreak else end
}

private fun MutableList<MutableList<ThreadReaderPageSlice>>.repackSparseTrailingPages(
    maxPageHeight: Int,
): List<List<ThreadReaderPageSlice>> {
    if (size < 2) return this
    val result = map { it.toMutableList() }.toMutableList()
    var index = 0
    while (index < result.lastIndex) {
        val current = result[index]
        val next = result[index + 1]
        var currentHeight = current.sumOf { it.estimatedHeightPx }
        while (next.isNotEmpty()) {
            val candidate = next.first()
            if (!candidate.canMoveAcrossPageBoundary()) break
            val candidateHeight = candidate.estimatedHeightPx.coerceAtLeast(1)
            if (currentHeight + candidateHeight > maxPageHeight) break
            current += next.removeAt(0)
            currentHeight += candidateHeight
        }
        if (next.isEmpty()) {
            result.removeAt(index + 1)
        } else {
            index += 1
        }
    }
    return result
}

private fun ThreadReaderPageSlice.canMoveAcrossPageBoundary(): Boolean =
    when (this) {
        is ThreadReaderPageSlice.Text -> true
        is ThreadReaderPageSlice.Block -> semanticType == "Image" && estimatedHeightPx < 220
    }

private fun HtmlBlock.Text.shouldKeepWithNextTextBlock(): Boolean {
    val text = annotatedString.text.trim()
    if (text.isEmpty()) return false
    if (text.length > 36) return false
    val hasStrongStyle = annotatedString.spanStyles.any { it.item.fontWeight != null }
    val endsAsSentence = text.lastOrNull() in setOf('。', '！', '？', '.', '!', '?', '」', '』')
    return hasStrongStyle || !endsAsSentence || text.startsWith("#")
}

internal fun shouldInlineFooterOnFinalPage(
    lastPageEstimatedHeightPx: Int,
    footerEstimatedHeightPx: Int,
    viewportHeightPx: Int,
    verticalPaddingPx: Int,
): Boolean {
    val maxPageHeight = (viewportHeightPx - verticalPaddingPx).coerceAtLeast(1)
    return lastPageEstimatedHeightPx.coerceAtLeast(0) +
        footerEstimatedHeightPx.coerceAtLeast(0) <= maxPageHeight
}

internal enum class ThreadReaderMeasuredUnitKind {
    Header,
    HtmlTextSlice,
    Image,
    NavigationBanner,
    Metadata,
    Poll,
    RatingRows,
    CommentRows,
    AttachmentRows,
    ActionRow,
}

internal data class ThreadReaderMeasuredUnit(
    val id: String,
    val kind: ThreadReaderMeasuredUnitKind,
    val heightPx: Int,
    val blockId: String? = id,
    val startOffset: Int? = null,
    val endOffset: Int? = null,
)

internal data class ThreadReaderMeasuredPage(
    val pageIndex: Int,
    val estimatedHeightPx: Int,
    val units: List<ThreadReaderMeasuredUnit>,
)

internal data class ThreadReaderMeasuredPackingResult(
    val pages: List<ThreadReaderMeasuredPage>,
    val rejectedUnits: List<ThreadReaderMeasuredUnit>,
) {
    val hasOverflow: Boolean get() = rejectedUnits.isNotEmpty()
}

internal data class ThreadReaderMeasuredPackingDescriptor(
    val pages: List<ThreadReaderMeasuredPage>,
    val rejectedUnits: List<ThreadReaderMeasuredUnit>,
)

internal fun ThreadReaderMeasuredPackingResult.planningDescriptor(): ThreadReaderMeasuredPackingDescriptor =
    ThreadReaderMeasuredPackingDescriptor(
        pages = pages,
        rejectedUnits = rejectedUnits,
    )

internal fun packMeasuredThreadReaderUnits(
    units: List<ThreadReaderMeasuredUnit>,
    viewportHeightPx: Int,
    verticalPaddingPx: Int,
): List<ThreadReaderMeasuredPage> =
    packMeasuredThreadReaderUnitsResult(
        units = units,
        viewportHeightPx = viewportHeightPx,
        verticalPaddingPx = verticalPaddingPx,
    ).pages

internal fun packMeasuredThreadReaderUnitsResult(
    units: List<ThreadReaderMeasuredUnit>,
    viewportHeightPx: Int,
    verticalPaddingPx: Int,
): ThreadReaderMeasuredPackingResult {
    val maxPageHeight = (viewportHeightPx - verticalPaddingPx).coerceAtLeast(1)
    val pages = mutableListOf<List<ThreadReaderMeasuredUnit>>()
    val rejectedUnits = mutableListOf<ThreadReaderMeasuredUnit>()
    var current = mutableListOf<ThreadReaderMeasuredUnit>()
    var currentHeight = 0

    fun flush() {
        if (current.isEmpty()) return
        pages += current
        current = mutableListOf()
        currentHeight = 0
    }

    units.forEach { unit ->
        val height = unit.heightPx.coerceAtLeast(1)
        if (height > maxPageHeight) {
            rejectedUnits += unit.copy(heightPx = height)
        }
        if (current.isNotEmpty() && currentHeight + height > maxPageHeight) {
            flush()
        }
        current += unit.copy(heightPx = height.coerceAtMost(maxPageHeight))
        currentHeight += height.coerceAtMost(maxPageHeight)
        if (currentHeight >= maxPageHeight) flush()
    }
    flush()

    return ThreadReaderMeasuredPackingResult(
        pages = pages.mapIndexed { index, pageUnits ->
            ThreadReaderMeasuredPage(
                pageIndex = index,
                estimatedHeightPx = pageUnits.sumOf { it.heightPx },
                units = pageUnits,
            )
        },
        rejectedUnits = rejectedUnits,
    )
}

internal fun resolvePageIndexForAnchor(
    pages: List<ThreadReaderPlannedPage>,
    anchor: ThreadReaderReadingAnchor,
): Int? {
    findPageIndexForAnchor(pages, anchor)?.let { return it }
    pages.indexOfFirst { page -> page.postId == anchor.postId && page.anchorRange.blockId == anchor.blockId }
        .takeIf { it >= 0 }
        ?.let { return it }
    if (anchor.blockId?.startsWith("footer-") == true) {
        pages.indexOfLast { page -> page.postId == anchor.postId }
            .takeIf { it >= 0 }
            ?.let { return it }
    }
    return pages.indexOfFirst { it.postId == anchor.postId }.takeIf { it >= 0 }
}

internal fun resolvePageIndexForPersistedAnchor(
    pages: List<ThreadReaderPlannedPage>,
    postId: Long,
    blockId: String?,
    blockRatio: Float?,
    postRatio: Float?,
): Int? {
    val postPages = pages.withIndex().filter { (_, page) -> page.postId == postId }
    if (postPages.isEmpty()) return null

    if (blockId != null) {
        val blockPages = postPages.mapNotNull { indexedPage ->
            val textSlices = indexedPage.value.slices
                .filterIsInstance<ThreadReaderPageSlice.Text>()
                .filter { it.blockId == blockId }
            when {
                textSlices.isNotEmpty() -> PersistedBlockPage(
                    pageIndex = indexedPage.index,
                    startOffset = textSlices.minOf { it.startOffset },
                    endOffset = textSlices.maxOf { it.endOffset },
                )
                indexedPage.value.anchorRange.blockId == blockId -> PersistedBlockPage(
                    pageIndex = indexedPage.index,
                    startOffset = indexedPage.value.anchorRange.startOffset,
                    endOffset = indexedPage.value.anchorRange.endOffset,
                )
                else -> null
            }
        }
        if (blockPages.isNotEmpty()) {
            val normalizedRatio = blockRatio?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
            val maxEndOffset = blockPages.mapNotNull { it.endOffset }.maxOrNull()
            if (normalizedRatio != null && maxEndOffset != null && maxEndOffset > 0) {
                val targetOffset = (maxEndOffset * normalizedRatio).roundToInt().coerceIn(0, maxEndOffset)
                blockPages.firstOrNull { page ->
                    val start = page.startOffset ?: return@firstOrNull false
                    val end = page.endOffset ?: return@firstOrNull false
                    val isFinalBlockPage = page === blockPages.last()
                    targetOffset >= start && (targetOffset < end || isFinalBlockPage && targetOffset == end)
                }?.let { return it.pageIndex }
            }
            return if (blockId.startsWith("footer-")) blockPages.last().pageIndex else blockPages.first().pageIndex
        }
        if (blockId.startsWith("footer-")) return postPages.last().index
    }

    val normalizedPostRatio = postRatio?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
    if (normalizedPostRatio != null && postPages.size > 1) {
        return postPages[(normalizedPostRatio * postPages.lastIndex).roundToInt()].index
    }
    return postPages.first().index
}

private data class PersistedBlockPage(
    val pageIndex: Int,
    val startOffset: Int?,
    val endOffset: Int?,
)

private fun buildBoundaryArray(textLength: Int, block: MutableSet<Int>.() -> Unit): IntArray =
    buildSet {
        add(textLength)
        block()
    }.filter { it in 1..textLength }.sorted().toIntArray()

private fun paragraphBoundaries(text: String): IntArray =
    buildBoundaryArray(text.length) {
        var index = text.indexOf("\n\n")
        while (index >= 0) {
            add((index + 2).coerceAtMost(text.length))
            index = text.indexOf("\n\n", startIndex = index + 2)
        }
        text.forEachIndexed { charIndex, char ->
            if (char == '\n') add(charIndex + 1)
        }
    }

private fun IntArray.normalizedBoundaries(textLength: Int): IntArray =
    (asSequence() + sequenceOf(textLength))
        .filter { it in 1..textLength }
        .distinct()
        .sorted()
        .toList()
        .toIntArray()

private fun estimateFittingChars(
    availableHeightPx: Int,
    estimatedCharsPerLine: Int,
    estimatedLineHeightPx: Int,
): Int {
    val lines = (availableHeightPx / estimatedLineHeightPx.coerceAtLeast(1)).coerceAtLeast(1)
    return lines * estimatedCharsPerLine.coerceAtLeast(1)
}

private fun estimateTextHeight(
    input: ThreadReaderPaginationInput,
    block: HtmlBlock.Text,
    startOffset: Int,
    endOffset: Int,
): Int =
    input.textHeightFor
        ?.invoke(block, startOffset.coerceAtLeast(0), endOffset.coerceIn(startOffset, block.annotatedString.length))
        ?.coerceAtLeast(1)
        ?: estimateTextHeight(
            charCount = endOffset - startOffset,
            estimatedCharsPerLine = input.estimatedCharsPerLine,
            estimatedLineHeightPx = input.estimatedLineHeightPx,
        )

private fun estimateTextHeight(
    charCount: Int,
    estimatedCharsPerLine: Int,
    estimatedLineHeightPx: Int,
): Int {
    val charsPerLine = estimatedCharsPerLine.coerceAtLeast(1)
    val lines = ((charCount + charsPerLine - 1) / charsPerLine).coerceAtLeast(1)
    return lines * estimatedLineHeightPx.coerceAtLeast(1)
}

private fun estimateAtomicBlockHeight(block: HtmlBlock, lineHeightPx: Int, maxPageHeightPx: Int): Int {
    val base = when (block) {
        is HtmlBlock.Code -> estimateTextHeight(block.codeText.length, 42, lineHeightPx)
        is HtmlBlock.Table -> block.rows.size.coerceAtLeast(1) * lineHeightPx * 2
        is HtmlBlock.Attachment -> lineHeightPx * 3
        is HtmlBlock.Quote,
        is HtmlBlock.Collapse,
        is HtmlBlock.Locked -> lineHeightPx * 4
        is HtmlBlock.Hr -> lineHeightPx
        else -> lineHeightPx * 2
    }
    return base.coerceIn(1, maxPageHeightPx)
}

private fun String.graphemeStepAt(index: Int): Int {
    if (index >= length) return 0
    val first = this[index]
    val codeUnitCount = if (first.isHighSurrogate() && index + 1 < length && this[index + 1].isLowSurrogate()) {
        2
    } else {
        1
    }
    var next = index + codeUnitCount
    while (next < length && this[next].category in combiningCategories) next++
    return next - index
}

private val combiningCategories = setOf(
    CharCategory.NON_SPACING_MARK,
    CharCategory.COMBINING_SPACING_MARK,
    CharCategory.ENCLOSING_MARK,
)

private fun Char?.isCjkReaderChar(): Boolean =
    this != null && (
        this in '\u3400'..'\u4DBF' ||
            this in '\u4E00'..'\u9FFF' ||
            this in '\uF900'..'\uFAFF' ||
            this in '\u3040'..'\u30FF' ||
            this in '\uAC00'..'\uD7AF'
        )

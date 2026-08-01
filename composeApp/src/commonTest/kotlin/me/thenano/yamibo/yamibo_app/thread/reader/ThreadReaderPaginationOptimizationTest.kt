package me.thenano.yamibo.yamibo_app.thread.reader

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import me.thenano.yamibo.yamibo_app.thread.reader.components.post.impl.HtmlBlock
import me.thenano.yamibo.yamibo_app.thread.reader.components.thread.SinglePageNormalizedBlocksKey
import me.thenano.yamibo.yamibo_app.thread.reader.components.thread.SinglePagePlanningGenerationKey
import me.thenano.yamibo.yamibo_app.thread.reader.components.thread.SinglePagePostPlanKey
import me.thenano.yamibo.yamibo_app.thread.reader.components.thread.ThreadReaderPaginationInput
import me.thenano.yamibo.yamibo_app.thread.reader.components.thread.ThreadReaderPaginationStrategy
import me.thenano.yamibo.yamibo_app.thread.reader.components.thread.ThreadReaderPlanningMetrics
import me.thenano.yamibo.yamibo_app.thread.reader.components.thread.ThreadReaderSinglePagePlanningCache
import me.thenano.yamibo.yamibo_app.thread.reader.components.thread.buildSafeBreakMap
import me.thenano.yamibo.yamibo_app.thread.reader.components.thread.chooseDifferentialPlan
import me.thenano.yamibo.yamibo_app.thread.reader.components.thread.packMeasuredThreadReaderUnitsResult
import me.thenano.yamibo.yamibo_app.thread.reader.components.thread.planFixedHeightReaderPages
import me.thenano.yamibo.yamibo_app.thread.reader.components.thread.planFixedHeightReaderPagesDifferential
import me.thenano.yamibo.yamibo_app.thread.reader.components.thread.planFixedHeightReaderPagesReference
import me.thenano.yamibo.yamibo_app.thread.reader.components.thread.planningDescriptor
import me.thenano.yamibo.yamibo_app.thread.reader.components.thread.ThreadReaderMeasuredUnit
import me.thenano.yamibo.yamibo_app.thread.reader.components.thread.ThreadReaderMeasuredUnitKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ThreadReaderPaginationOptimizationTest {
    @Test
    fun optimizedPlannerMatchesReferenceForRepresentativeCorpus() {
        representativeInputs().forEach { input ->
            val reference = planFixedHeightReaderPagesReference(input)
            val optimized = planFixedHeightReaderPages(input)

            assertEquals(reference.planningDescriptor(), optimized.planningDescriptor())
        }
    }

    @Test
    fun optimizedBoundaryLookupMatchesReferenceAcrossRanges() {
        val text = "第一段繁體中文。第二段简体中文！English sentence. 日本語です。😀結尾"
        val rubyStart = text.indexOf("日本")
        val block = HtmlBlock.Text(
            annotatedString = AnnotatedString(text),
            rubies = listOf(
                HtmlBlock.RubyText(
                    id = "ruby",
                    start = rubyStart,
                    end = rubyStart + 2,
                    baseText = "日本",
                    rubyText = "にほん",
                )
            ),
            anchorId = "mixed",
        )
        val breakMap = buildSafeBreakMap(block)

        for (start in 0 until text.length) {
            for (end in (start + 1)..text.length) {
                assertEquals(
                    breakMap.bestBreakReference(start, end, preferSentence = true),
                    breakMap.bestBreak(start, end, preferSentence = true),
                    "sentence-preferred mismatch for $start..$end",
                )
                assertEquals(
                    breakMap.bestBreakReference(start, end, preferSentence = false),
                    breakMap.bestBreak(start, end, preferSentence = false),
                    "line-preferred mismatch for $start..$end",
                )
            }
        }
    }

    @Test
    fun optimizedPlannerReusesDuplicateTextHeightProbesWithinOneCall() {
        val text = (1..80).joinToString("") { "第${it}句內容需要安全分頁。" }
        var referenceProbeCount = 0
        var optimizedProbeCount = 0
        val base = ThreadReaderPaginationInput(
            postId = 10,
            blocks = listOf(HtmlBlock.Text(AnnotatedString(text), anchorId = "body")),
            viewportHeightPx = 320,
            estimatedCharsPerLine = 14,
            estimatedLineHeightPx = 24,
            verticalPaddingPx = 32,
            contentWidthPx = 300,
        )
        val referenceMetrics = ThreadReaderPlanningMetrics()
        val reference = planFixedHeightReaderPagesReference(
            base.copy(textHeightFor = { _, start, end ->
                referenceProbeCount++
                measuredHeight(start, end)
            }),
            metrics = referenceMetrics,
        )
        val metrics = ThreadReaderPlanningMetrics()
        val optimized = planFixedHeightReaderPages(
            base.copy(textHeightFor = { _, start, end ->
                optimizedProbeCount++
                measuredHeight(start, end)
            }),
            metrics = metrics,
        )

        assertEquals(reference.planningDescriptor(), optimized.planningDescriptor())
        assertTrue(optimizedProbeCount <= referenceProbeCount)
        assertEquals(optimizedProbeCount, metrics.snapshot().textHeightProbes)
        assertTrue(metrics.snapshot().textHeightProbeCacheHits > 0)
        assertTrue(referenceMetrics.snapshot().candidateMaterializations > 0)
        assertEquals(0, metrics.snapshot().candidateMaterializations)
    }

    @Test
    fun textProbeMemoDoesNotCrossPlanningCalls() {
        val input = representativeInputs().first()
        var firstCount = 0
        var secondCount = 0

        planFixedHeightReaderPages(input.copy(textHeightFor = { _, start, end ->
            firstCount++
            measuredHeight(start, end)
        }))
        planFixedHeightReaderPages(input.copy(textHeightFor = { _, start, end ->
            secondCount++
            measuredHeight(start, end)
        }))

        assertTrue(firstCount > 0)
        assertEquals(firstCount, secondCount)
    }

    @Test
    fun differentialPlannerFallsBackToReferenceOnMismatch() {
        val reference = planFixedHeightReaderPagesReference(representativeInputs().first())
        val optimized = reference.dropLast(1)
        var mismatchPage: Int? = null

        val selected = chooseDifferentialPlan(reference, optimized) { mismatch ->
            mismatchPage = mismatch.firstDifferentPageIndex
        }

        assertEquals(reference, selected)
        assertNotNull(mismatchPage)
    }

    @Test
    fun differentialPlannerReturnsEquivalentOptimizedOutput() {
        var mismatchCount = 0
        val selected = planFixedHeightReaderPagesDifferential(representativeInputs().last()) {
            mismatchCount++
        }

        assertEquals(0, mismatchCount)
        assertTrue(selected.isNotEmpty())
    }

    @Test
    fun measuredPackingDescriptorIncludesPagesAndRejectedUnits() {
        val result = packMeasuredThreadReaderUnitsResult(
            units = listOf(
                ThreadReaderMeasuredUnit("body", ThreadReaderMeasuredUnitKind.HtmlTextSlice, 80),
                ThreadReaderMeasuredUnit("oversized", ThreadReaderMeasuredUnitKind.CommentRows, 400),
            ),
            viewportHeightPx = 240,
            verticalPaddingPx = 40,
        )
        val descriptor = result.planningDescriptor()

        assertEquals(result.pages, descriptor.pages)
        assertEquals(result.rejectedUnits, descriptor.rejectedUnits)
        assertTrue(descriptor.rejectedUnits.isNotEmpty())
    }

    @Test
    fun planningCacheUsesExactContentDespiteHashCollision() {
        assertEquals("FB".hashCode(), "Ea".hashCode())
        val cache = ThreadReaderSinglePagePlanningCache(normalizedBlockCapacity = 4, postPlanCapacity = 4)
        cache.ensureGeneration(generationKey())
        var builds = 0

        val first = cache.normalizedBlocks(SinglePageNormalizedBlocksKey(1, "FB")) {
            builds++
            listOf(HtmlBlock.Text(AnnotatedString("first"), anchorId = "first"))
        }
        val second = cache.normalizedBlocks(SinglePageNormalizedBlocksKey(1, "Ea")) {
            builds++
            listOf(HtmlBlock.Text(AnnotatedString("second"), anchorId = "second"))
        }

        assertEquals(2, builds)
        assertTrue(first != second)
    }

    @Test
    fun planningCacheInvalidatesEveryGenerationInput() {
        val cache = ThreadReaderSinglePagePlanningCache()
        val base = generationKey()
        val variants = listOf(
            base.copy(viewportWidthPx = 701),
            base.copy(readableHeightPx = 1001),
            base.copy(verticalPaddingPx = 49),
            base.copy(density = 3.1f),
            base.copy(fontScale = 1.1f),
            base.copy(layoutDirection = "Rtl"),
            base.copy(contentWidthFraction = 0.91f),
            base.copy(fontSize = 17),
            base.copy(lineSpacing = 1.6f),
            base.copy(readerFontId = "serif"),
            base.copy(textMeasurerIdentity = 2),
            base.copy(localeEngineId = "platform-v2"),
            base.copy(paginationStrategy = ThreadReaderPaginationStrategy.Reference),
            base.copy(convertedContentVersion = 2),
            base.copy(isNovelThread = true),
            base.copy(showRegularFirstPostTagBanner = false),
            base.copy(showNovelFirstPostTagBanner = true),
        )

        assertTrue(cache.ensureGeneration(base))
        assertTrue(!cache.ensureGeneration(base))
        variants.forEach { variant ->
            assertTrue(cache.ensureGeneration(variant))
            assertTrue(cache.ensureGeneration(base))
        }
    }

    @Test
    fun planningCacheReusesWarmPlansAndInvalidatesPostLocalInputs() {
        val cache = ThreadReaderSinglePagePlanningCache()
        val metrics = ThreadReaderPlanningMetrics()
        cache.ensureGeneration(generationKey())
        val pages = planFixedHeightReaderPages(representativeInputs().first())
        val baseKey = postPlanKey()
        var builds = 0

        fun load(key: SinglePagePostPlanKey) = cache.postPlan(key, metrics) {
            builds++
            pages
        }

        assertEquals(pages, load(baseKey))
        assertEquals(pages, load(baseKey))
        load(baseKey.copy(convertedContent = "changed"))
        load(baseKey.copy(imageGeometry = listOf("image" to 2f)))
        load(baseKey.copy(footerMeasurements = listOf("footer" to 120)))

        assertEquals(4, builds)
        assertEquals(1, metrics.snapshot().postPlanCacheHits)
    }

    @Test
    fun loadedForumPageAppendBuildsOnlyTheNewPostPlan() {
        val cache = ThreadReaderSinglePagePlanningCache()
        val metrics = ThreadReaderPlanningMetrics()
        cache.ensureGeneration(generationKey())
        val pages = planFixedHeightReaderPages(representativeInputs().first())
        val firstPost = postPlanKey()
        val appendedPost = firstPost.copy(postId = firstPost.postId + 1, convertedContent = "appended")
        var builds = 0

        fun load(key: SinglePagePostPlanKey) = cache.postPlan(key, metrics) {
            builds++
            pages
        }

        load(firstPost)
        load(firstPost)
        load(appendedPost)
        load(firstPost)

        assertEquals(2, builds)
        assertEquals(2, metrics.snapshot().postPlanCacheHits)
    }

    @Test
    fun planningCacheDoesNotSurviveScreenDisposal() {
        val key = SinglePageNormalizedBlocksKey(1, "same post")
        var builds = 0

        fun newScreenCache(): ThreadReaderSinglePagePlanningCache =
            ThreadReaderSinglePagePlanningCache().also { it.ensureGeneration(generationKey()) }

        newScreenCache().normalizedBlocks(key) {
            builds++
            listOf(HtmlBlock.Text(AnnotatedString("first screen"), anchorId = "first"))
        }
        newScreenCache().normalizedBlocks(key) {
            builds++
            listOf(HtmlBlock.Text(AnnotatedString("second screen"), anchorId = "second"))
        }

        assertEquals(2, builds)
    }

    @Test
    fun planningCacheIsBoundedAndClearableWithLruBehavior() {
        val cache = ThreadReaderSinglePagePlanningCache(normalizedBlockCapacity = 2, postPlanCapacity = 2)
        cache.ensureGeneration(generationKey())
        var builds = 0
        fun load(content: String) = cache.normalizedBlocks(SinglePageNormalizedBlocksKey(1, content)) {
            builds++
            listOf(HtmlBlock.Text(AnnotatedString(content), anchorId = content))
        }

        load("one")
        load("two")
        load("one")
        load("three")
        load("two")

        assertEquals(4, builds)
        assertEquals(2, cache.normalizedBlockCacheSize)
        cache.clear()
        assertEquals(0, cache.normalizedBlockCacheSize)
        assertEquals(0, cache.postPlanCacheSize)
        assertEquals(null, cache.currentGenerationKey)
    }

    private fun representativeInputs(): List<ThreadReaderPaginationInput> {
        val styled = AnnotatedString.Builder().apply {
            pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
            append("繁體中文第一句。简体中文第二句！English sentence. 日本語です。😀")
            pop()
        }.toAnnotatedString()
        val mixed = listOf(
            HtmlBlock.Text(
                annotatedString = styled,
                rubies = listOf(HtmlBlock.RubyText("ruby", 0, 2, "繁體", "はんたい")),
                anchorId = "styled",
            ),
            HtmlBlock.Image("https://example.test/image.png", anchorId = "image"),
            HtmlBlock.Quote(
                listOf(HtmlBlock.Text(AnnotatedString("引用內容。".repeat(20)), anchorId = "quote-text")),
                anchorId = "quote",
            ),
            HtmlBlock.Collapse(
                "collapse",
                listOf(HtmlBlock.Text(AnnotatedString("收合內容。".repeat(16)), anchorId = "collapse-text")),
                anchorId = "collapse",
            ),
            HtmlBlock.Locked(
                5,
                listOf(HtmlBlock.Text(AnnotatedString("鎖定內容。".repeat(14)), anchorId = "locked-text")),
                anchorId = "locked",
            ),
            HtmlBlock.Table(
                rows = listOf(
                    HtmlBlock.TableRow(
                        listOf(
                            HtmlBlock.TableCell(listOf(HtmlBlock.Text(AnnotatedString("表格"), anchorId = "cell"))),
                        )
                    )
                ),
                anchorId = "table",
            ),
            HtmlBlock.Code("println(\"hello\")\n".repeat(30), anchorId = "code"),
            HtmlBlock.Attachment(
                url = "https://example.test/file.zip",
                iconUrl = null,
                fileName = "file.zip",
                uploadInfo = null,
                statInfo = null,
                anchorId = "attachment",
            ),
            HtmlBlock.Hr("hr"),
        )
        val longText = List(160) { index -> "第${index}句長篇內容保留完全一致的安全切點。" }.joinToString("")
        return listOf(
            paginationInput(10, mixed),
            paginationInput(20, listOf(HtmlBlock.Text(AnnotatedString(longText), anchorId = "long"))),
        )
    }

    private fun paginationInput(postId: Long, blocks: List<HtmlBlock>) = ThreadReaderPaginationInput(
        postId = postId,
        blocks = blocks,
        viewportHeightPx = 420,
        estimatedCharsPerLine = 16,
        estimatedLineHeightPx = 24,
        verticalPaddingPx = 36,
        contentWidthPx = 320,
        imageHeightToWidthRatioFor = { 0.5f },
        textHeightFor = { _, start, end -> measuredHeight(start, end) },
    )

    private fun measuredHeight(start: Int, end: Int): Int =
        (((end - start).coerceAtLeast(1) + 11) / 12) * 24 + 8

    private fun generationKey() = SinglePagePlanningGenerationKey(
        viewportWidthPx = 700,
        readableHeightPx = 1_000,
        verticalPaddingPx = 48,
        density = 3f,
        fontScale = 1f,
        layoutDirection = "Ltr",
        contentWidthFraction = 0.9f,
        fontSize = 16,
        lineSpacing = 1.5f,
        readerFontId = "default",
        textMeasurerIdentity = 1,
        localeEngineId = "platform-default-v1",
        paginationStrategy = ThreadReaderPaginationStrategy.Optimized,
        convertedContentVersion = 1,
        isNovelThread = false,
        showRegularFirstPostTagBanner = true,
        showNovelFirstPostTagBanner = false,
    )

    private fun postPlanKey() = SinglePagePostPlanKey(
        postId = 10,
        convertedContent = "content",
        emptyContentAnchorBlockId = "body",
        imageGeometry = listOf("image" to 1f),
        footerMeasurements = listOf("footer" to 100),
    )
}

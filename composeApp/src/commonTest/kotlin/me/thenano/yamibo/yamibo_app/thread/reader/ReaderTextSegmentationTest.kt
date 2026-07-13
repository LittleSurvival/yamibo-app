package me.thenano.yamibo.yamibo_app.thread.reader

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import me.thenano.yamibo.yamibo_app.repository.settings.ThreadReaderMode
import me.thenano.yamibo.yamibo_app.thread.reader.components.post.PostFooterSection
import me.thenano.yamibo.yamibo_app.thread.reader.components.post.impl.HtmlBlock
import me.thenano.yamibo.yamibo_app.thread.reader.components.thread.SinglePageTapAction
import me.thenano.yamibo.yamibo_app.thread.reader.components.thread.CachingTextBoundarySegmenter
import me.thenano.yamibo.yamibo_app.thread.reader.components.thread.ThreadReaderAnchorRange
import me.thenano.yamibo.yamibo_app.thread.reader.components.thread.ThreadReaderMeasuredUnit
import me.thenano.yamibo.yamibo_app.thread.reader.components.thread.ThreadReaderMeasuredUnitKind
import me.thenano.yamibo.yamibo_app.thread.reader.components.thread.ThreadReaderPageSlice
import me.thenano.yamibo.yamibo_app.thread.reader.components.thread.ThreadReaderPaginationInput
import me.thenano.yamibo.yamibo_app.thread.reader.components.thread.ThreadReaderPlannedPage
import me.thenano.yamibo.yamibo_app.thread.reader.components.thread.ThreadReaderReadingAnchor
import me.thenano.yamibo.yamibo_app.thread.reader.components.thread.TextBoundarySegmenter
import me.thenano.yamibo.yamibo_app.thread.reader.components.thread.UnicodeFallbackTextBoundarySegmenter
import me.thenano.yamibo.yamibo_app.thread.reader.components.thread.avoidShortCjkTrailingRun
import me.thenano.yamibo.yamibo_app.thread.reader.components.thread.buildSafeBreakMap
import me.thenano.yamibo.yamibo_app.thread.reader.components.thread.buildThreadReaderPageProgressStates
import me.thenano.yamibo.yamibo_app.thread.reader.components.thread.captureSinglePageViewportAnchor
import me.thenano.yamibo.yamibo_app.thread.reader.components.thread.packMeasuredThreadReaderUnits
import me.thenano.yamibo.yamibo_app.thread.reader.components.thread.packMeasuredThreadReaderUnitsResult
import me.thenano.yamibo.yamibo_app.thread.reader.components.thread.physicalDragForSinglePageDelta
import me.thenano.yamibo.yamibo_app.thread.reader.components.thread.planFixedHeightReaderPages
import me.thenano.yamibo.yamibo_app.thread.reader.components.thread.resolvePageIndexForAnchor
import me.thenano.yamibo.yamibo_app.thread.reader.components.thread.resolvePageIndexForPersistedAnchor
import me.thenano.yamibo.yamibo_app.thread.reader.components.thread.resolveSinglePagePlanTransition
import me.thenano.yamibo.yamibo_app.thread.reader.components.thread.semanticStableId
import me.thenano.yamibo.yamibo_app.thread.reader.components.thread.sliceHtmlBlocksForPage
import me.thenano.yamibo.yamibo_app.thread.reader.components.thread.shouldInlineFooterOnFinalPage
import me.thenano.yamibo.yamibo_app.thread.reader.components.thread.singlePageDeltaForPhysicalDrag
import me.thenano.yamibo.yamibo_app.thread.reader.components.thread.singlePageDeltaForTouchAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ReaderTextSegmentationTest {
    @Test
    fun longAnnotatedTextIsSplitWithoutLosingTextOrStyles() {
        val source = AnnotatedString.Builder().apply {
            pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
            append("段落內容\n".repeat(2_000))
            pop()
        }.toAnnotatedString()
        val block = HtmlBlock.Text(annotatedString = source, anchorId = "source")

        val segments = splitLongReaderTextBlock(block)

        assertTrue(segments.size > 1)
        assertTrue(segments.all { it.annotatedString.length <= MAX_READER_TEXT_SEGMENT_CHARS })
        assertEquals(source.text, segments.joinToString(separator = "") { it.annotatedString.text })
        assertTrue(segments.all { it.annotatedString.spanStyles.isNotEmpty() })
        assertEquals(segments.size, segments.map { it.anchorId }.distinct().size)
    }

    @Test
    fun shortTextKeepsOriginalBlock() {
        val block = HtmlBlock.Text(AnnotatedString("短內容"), anchorId = "source")

        assertEquals(listOf(block), splitLongReaderTextBlock(block))
    }

    @Test
    fun longTextNestedInQuoteIsSplitIntoIndependentQuoteBlocks() {
        val text = "引文內容\n".repeat(2_000)
        val quote = HtmlBlock.Quote(
            contentBlocks = listOf(HtmlBlock.Text(AnnotatedString(text), anchorId = "text")),
            anchorId = "quote",
        )

        val segments = splitLongReaderBlock(quote)

        assertTrue(segments.size > 1)
        assertTrue(segments.all { it is HtmlBlock.Quote })
        assertEquals(
            text,
            segments.joinToString(separator = "") { segment ->
                (segment as HtmlBlock.Quote).contentBlocks.joinToString(separator = "") {
                    (it as HtmlBlock.Text).annotatedString.text
                }
            },
        )
        assertEquals(segments.size, segments.map { it.anchorId }.distinct().size)
    }

    @Test
    fun safeBreakMapPrefersMultilingualSentenceBoundaries() {
        val text = "繁體中文第一句。简体中文第二句！English third sentence. 日本語の第四文です。"
        val block = HtmlBlock.Text(AnnotatedString(text), anchorId = "mixed")
        val breakMap = buildSafeBreakMap(block)

        val firstBreak = breakMap.bestBreak(start = 0, maxEndExclusive = text.indexOf("简体").coerceAtLeast(1))
        assertEquals(text.indexOf('。') + 1, firstBreak)

        val secondStart = firstBreak
        val secondBreak = breakMap.bestBreak(secondStart, text.indexOf("English").coerceAtLeast(secondStart + 1))
        assertEquals(text.indexOf('！') + 1, secondBreak)
    }

    @Test
    fun graphemeFallbackDoesNotSplitSurrogatePairs() {
        val text = "前文😀後文"
        val block = HtmlBlock.Text(AnnotatedString(text), anchorId = "emoji")
        val breakMap = buildSafeBreakMap(block)
        val emojiHighSurrogateIndex = text.indexOf('\uD83D')

        assertTrue(emojiHighSurrogateIndex > 0)
        assertNotEquals(emojiHighSurrogateIndex + 1, breakMap.bestBreak(0, emojiHighSurrogateIndex + 1))
    }

    @Test
    fun plannedPagesResolveSameAnchorAfterLayoutChange() {
        val text = "第一句內容很長但應該保持完整。第二句內容也很長並且會在新的版面移動。第三句作為結尾。"
        val blocks = listOf(HtmlBlock.Text(AnnotatedString(text), anchorId = "body"))
        val widePages = planFixedHeightReaderPages(
            ThreadReaderPaginationInput(
                postId = 10,
                blocks = blocks,
                viewportHeightPx = 120,
                estimatedCharsPerLine = 16,
                estimatedLineHeightPx = 20,
            )
        )
        val narrowPages = planFixedHeightReaderPages(
            ThreadReaderPaginationInput(
                postId = 10,
                blocks = blocks,
                viewportHeightPx = 120,
                estimatedCharsPerLine = 8,
                estimatedLineHeightPx = 20,
            )
        )
        val anchor = ThreadReaderReadingAnchor(
            postId = 10,
            blockId = "body",
            textOffset = text.indexOf("第二句"),
            blockRatio = null,
        )

        val oldPage = resolvePageIndexForAnchor(widePages, anchor)
        val newPage = resolvePageIndexForAnchor(narrowPages, anchor)

        assertTrue(oldPage != null)
        assertTrue(newPage != null)
        assertTrue(narrowPages[newPage].anchorRange.contains(anchor))
    }

    @Test
    fun pageProgressStateIsPostLocalAndKeepsForumPageMapping() {
        val pageRefs = listOf(
            "post-10-page-0" to plannedPage(postId = 10, pageIndex = 0, totalPages = 2),
            "post-10-page-1" to plannedPage(postId = 10, pageIndex = 1, totalPages = 2),
            "post-20-page-0" to plannedPage(postId = 20, pageIndex = 0, totalPages = 1),
        )

        val states = buildThreadReaderPageProgressStates(
            pageRefs = pageRefs,
            pageByPostId = mapOf(10L to 3, 20L to 4),
            initialForumPage = 1,
        )

        assertEquals(0, states.getValue(0).currentPostPageIndex)
        assertEquals(1, states.getValue(1).currentPostPageIndex)
        assertEquals(2, states.getValue(1).currentPostPageCount)
        assertEquals(3, states.getValue(0).forumPage)
        assertEquals(4, states.getValue(2).forumPage)
        assertEquals(0, states.getValue(2).currentPostPageIndex)
        assertEquals(1, states.getValue(2).currentPostPageCount)
    }

    @Test
    fun plannedTextPagesStayWithinReadableViewport() {
        val text = List(40) { index ->
            "第${index}句內容需要被保守切分，避免頁面底部跑到系統導覽列或進度提示下面。"
        }.joinToString("")
        val pages = planFixedHeightReaderPages(
            ThreadReaderPaginationInput(
                postId = 10,
                blocks = listOf(HtmlBlock.Text(AnnotatedString(text), anchorId = "body")),
                viewportHeightPx = 300,
                estimatedCharsPerLine = 12,
                estimatedLineHeightPx = 28,
                verticalPaddingPx = 32,
            )
        )

        assertTrue(pages.size > 1)
        pages.forEach { page ->
            assertTrue(
                page.estimatedHeightPx <= 268,
                "page ${page.pageIndexInPost} estimated height ${page.estimatedHeightPx} exceeds readable viewport"
            )
        }
    }

    @Test
    fun smallImagesShareReaderPageWhenHeightAllows() {
        val pages = planFixedHeightReaderPages(
            ThreadReaderPaginationInput(
                postId = 10,
                blocks = listOf(
                    HtmlBlock.Image(url = "https://example.test/1.png", anchorId = "image-1"),
                    HtmlBlock.Image(url = "https://example.test/2.png", anchorId = "image-2"),
                    HtmlBlock.Image(url = "https://example.test/3.png", anchorId = "image-3"),
                ),
                viewportHeightPx = 360,
                estimatedCharsPerLine = 12,
                estimatedLineHeightPx = 28,
                verticalPaddingPx = 24,
                contentWidthPx = 300,
                imageHeightToWidthRatioFor = { 0.2f },
            )
        )

        assertEquals(1, pages.size)
        assertEquals(3, pages.first().slices.size)
    }

    @Test
    fun textAndSmallImageCanShareReaderPage() {
        val pages = planFixedHeightReaderPages(
            ThreadReaderPaginationInput(
                postId = 10,
                blocks = listOf(
                    HtmlBlock.Text(AnnotatedString("短段落可以和小圖放在同一頁。"), anchorId = "body"),
                    HtmlBlock.Image(url = "https://example.test/1.png", anchorId = "image-1"),
                ),
                viewportHeightPx = 260,
                estimatedCharsPerLine = 16,
                estimatedLineHeightPx = 24,
                verticalPaddingPx = 24,
                contentWidthPx = 220,
                imageHeightToWidthRatioFor = { 0.25f },
            )
        )

        assertEquals(1, pages.size)
        assertTrue(pages.first().slices.any { it is ThreadReaderPageSlice.Text })
        assertTrue(pages.first().slices.any { it is ThreadReaderPageSlice.Block && it.semanticType == "Image" })
    }

    @Test
    fun largeImageDoesNotSharePageWithFollowingText() {
        val pages = planFixedHeightReaderPages(
            ThreadReaderPaginationInput(
                postId = 10,
                blocks = listOf(
                    HtmlBlock.Image(url = "https://example.test/large.png", anchorId = "image-1"),
                    HtmlBlock.Text(AnnotatedString("APP開發相關\n這段文字應該移到圖片後的下一頁。"), anchorId = "after-image"),
                ),
                viewportHeightPx = 640,
                estimatedCharsPerLine = 18,
                estimatedLineHeightPx = 28,
                verticalPaddingPx = 80,
                contentWidthPx = 400,
                imageHeightToWidthRatioFor = { 1.4f },
            )
        )

        assertTrue(pages.size >= 2)
        assertTrue(pages.first().slices.single() is ThreadReaderPageSlice.Block)
        assertTrue(pages[1].slices.any { it.blockId == "after-image" })
    }

    @Test
    fun imageGeometryUsesHeightToWidthRatioAgainstContentWidth() {
        val ratios = mapOf("wide" to 0.25f, "square" to 1f, "tall" to 2f)
        val pages = planFixedHeightReaderPages(
            ThreadReaderPaginationInput(
                postId = 10,
                blocks = ratios.keys.map { id ->
                    HtmlBlock.Image(url = "https://example.test/$id.png", anchorId = id)
                },
                viewportHeightPx = 640,
                estimatedCharsPerLine = 18,
                estimatedLineHeightPx = 28,
                verticalPaddingPx = 64,
                contentWidthPx = 300,
                imageHeightToWidthRatioFor = { ratios.getValue(it.anchorId) },
            )
        )

        val slices = pages.flatMap { it.slices }.associateBy { it.blockId }
        assertEquals(75, slices.getValue("wide").estimatedHeightPx)
        assertEquals(300, slices.getValue("square").estimatedHeightPx)
        assertEquals(576, slices.getValue("tall").estimatedHeightPx)
    }

    @Test
    fun nestedQuoteSlicesRenderAndReuseAvailablePageSpace() {
        val quoteText = "引文第一句。引文第二句。"
        val blocks = listOf(
            HtmlBlock.Text(AnnotatedString("前文。"), anchorId = "intro"),
            HtmlBlock.Quote(
                contentBlocks = listOf(HtmlBlock.Text(AnnotatedString(quoteText), anchorId = "quote-text")),
                anchorId = "quote",
            ),
        )
        val pages = planFixedHeightReaderPages(
            ThreadReaderPaginationInput(
                postId = 10,
                blocks = blocks,
                viewportHeightPx = 240,
                estimatedCharsPerLine = 12,
                estimatedLineHeightPx = 24,
            )
        )

        assertEquals(1, pages.size)
        val rendered = sliceHtmlBlocksForPage(blocks, pages.single().slices)
        assertTrue(rendered.first() is HtmlBlock.Text)
        val renderedQuote = rendered.last() as HtmlBlock.Quote
        assertEquals(quoteText, (renderedQuote.contentBlocks.single() as HtmlBlock.Text).annotatedString.text)
    }

    @Test
    fun oversizedCodeAndTableAreSplitWithoutLosingSemanticUnits() {
        val code = HtmlBlock.Code((0 until 12).joinToString("\n") { "line-$it" }, anchorId = "code")
        val table = HtmlBlock.Table(
            rows = (0 until 7).map { row ->
                HtmlBlock.TableRow(
                    listOf(HtmlBlock.TableCell(listOf(HtmlBlock.Text(AnnotatedString("row-$row")))))
                )
            },
            anchorId = "table",
        )
        val blocks = listOf(code, table)
        val pages = planFixedHeightReaderPages(
            ThreadReaderPaginationInput(
                postId = 10,
                blocks = blocks,
                viewportHeightPx = 144,
                estimatedCharsPerLine = 20,
                estimatedLineHeightPx = 24,
                verticalPaddingPx = 24,
            )
        )

        assertTrue(pages.all { it.estimatedHeightPx <= 120 })
        val rendered = pages.flatMap { sliceHtmlBlocksForPage(blocks, it.slices) }
        assertEquals(code.codeText, rendered.filterIsInstance<HtmlBlock.Code>().joinToString("\n") { it.codeText })
        assertEquals(table.rows, rendered.filterIsInstance<HtmlBlock.Table>().flatMap { it.rows })
    }

    @Test
    fun footerCanInlineOnlyWhenFinalPageHasEnoughSpace() {
        assertTrue(
            shouldInlineFooterOnFinalPage(
                lastPageEstimatedHeightPx = 120,
                footerEstimatedHeightPx = 80,
                viewportHeightPx = 260,
                verticalPaddingPx = 24,
            )
        )
        assertTrue(
            !shouldInlineFooterOnFinalPage(
                lastPageEstimatedHeightPx = 190,
                footerEstimatedHeightPx = 80,
                viewportHeightPx = 260,
                verticalPaddingPx = 24,
            )
        )
    }

    @Test
    fun shortHeadingMovesWithFollowingContentWhenItWouldStrandAtPageBottom() {
        val intro = HtmlBlock.Text(
            AnnotatedString("前一段內容需要先佔掉目前頁面的大部分空間。前一段內容需要先佔掉目前頁面的大部分空間。"),
            anchorId = "intro",
        )
        val heading = HtmlBlock.Text(
            AnnotatedString.Builder().apply {
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                append("APP開發相關")
                pop()
            }.toAnnotatedString(),
            anchorId = "heading",
        )
        val body = HtmlBlock.Text(AnnotatedString("新增：這一段才是標題後面應該一起閱讀的正文。"), anchorId = "body")

        val pages = planFixedHeightReaderPages(
            ThreadReaderPaginationInput(
                postId = 10,
                blocks = listOf(intro, heading, body),
                viewportHeightPx = 150,
                estimatedCharsPerLine = 18,
                estimatedLineHeightPx = 30,
            )
        )

        val headingPageIndex = pages.indexOfFirst { page ->
            page.slices.any { it.blockId == "heading" }
        }
        assertTrue(headingPageIndex >= 0)
        assertTrue(pages[headingPageIndex].slices.any { it.blockId == "body" })
    }

    @Test
    fun shortTrailingSentenceIsKeptWithPreviousSliceWhenItFits() {
        val text = "第一句需要接近一頁但仍有空間。第二句也在同一頁。短尾句。"

        val pages = planFixedHeightReaderPages(
            ThreadReaderPaginationInput(
                postId = 10,
                blocks = listOf(HtmlBlock.Text(AnnotatedString(text), anchorId = "body")),
                viewportHeightPx = 120,
                estimatedCharsPerLine = 12,
                estimatedLineHeightPx = 24,
            )
        )

        assertTrue(pages.last().estimatedHeightPx > 24)
    }

    @Test
    fun cjkWordPrefixIsNotLeftAloneAtPageEnd() {
        val text = "前段內容 閱讀器後續內容"
        val breakMap = buildSafeBreakMap(HtmlBlock.Text(AnnotatedString(text), anchorId = "body"))
        val splitAfterFirstReaderChar = text.indexOf("閱") + 1
        val adjusted = splitAfterFirstReaderChar.avoidShortCjkTrailingRun(
            text = text,
            start = 0,
            breakMap = breakMap,
        )

        assertEquals(text.indexOf("閱讀器"), adjusted)
        assertEquals("閱讀器後續內容", text.substring(adjusted))
    }

    @Test
    fun sparseTextPagesPullForwardFollowingTextWhenHeightAllows() {
        val pages = planFixedHeightReaderPages(
            ThreadReaderPaginationInput(
                postId = 10,
                blocks = listOf(
                    HtmlBlock.Text(AnnotatedString("第一段。第二段。"), anchorId = "a"),
                    HtmlBlock.Text(AnnotatedString("第三段。第四段。"), anchorId = "b"),
                ),
                viewportHeightPx = 180,
                estimatedCharsPerLine = 8,
                estimatedLineHeightPx = 24,
            )
        )

        assertEquals(1, pages.size)
        assertTrue(pages.first().slices.any { it.blockId == "a" })
        assertTrue(pages.first().slices.any { it.blockId == "b" })
    }

    @Test
    fun measuredPaginationExpandsPastConservativeCharacterEstimate() {
        val text = "第一句可以完整放入。第二句也可以完整放入。第三句仍然可以放在同一頁。"
        val pages = planFixedHeightReaderPages(
            ThreadReaderPaginationInput(
                postId = 10,
                blocks = listOf(HtmlBlock.Text(AnnotatedString(text), anchorId = "body")),
                viewportHeightPx = 120,
                estimatedCharsPerLine = 4,
                estimatedLineHeightPx = 24,
                textHeightFor = { _, start, end -> (end - start) * 2 },
            )
        )

        assertEquals(1, pages.size)
        assertEquals(text.length, (pages.single().slices.single() as ThreadReaderPageSlice.Text).endOffset)
    }

    @Test
    fun footerAnchorFallsBackToLastPostPageAfterFooterRegrouping() {
        val pages = listOf(
            plannedPage(postId = 10, pageIndex = 0, totalPages = 3),
            plannedPage(postId = 10, pageIndex = 1, totalPages = 3),
            plannedPage(postId = 10, pageIndex = 2, totalPages = 3),
        )
        val anchor = ThreadReaderReadingAnchor(
            postId = 10,
            blockId = "footer-rates-0-8-actions",
            textOffset = null,
            blockRatio = null,
        )

        assertEquals(2, resolvePageIndexForAnchor(pages, anchor))
    }

    @Test
    fun persistedBlockRatioRestoresLaterPageWithinSameTextBlock() {
        val pages = (0 until 4).map { pageIndex ->
            val start = pageIndex * 100
            ThreadReaderPlannedPage(
                postId = 10,
                pageIndexInPost = pageIndex,
                totalPagesInPost = 4,
                estimatedHeightPx = 100,
                anchorRange = ThreadReaderAnchorRange(10, "body", start, start + 100),
                slices = listOf(
                    ThreadReaderPageSlice.Text("body", start, start + 100, estimatedHeightPx = 100)
                ),
            )
        }

        val restored = resolvePageIndexForPersistedAnchor(
            pages = pages,
            postId = 10,
            blockId = "body",
            blockRatio = 0.62f,
            postRatio = null,
        )

        assertEquals(2, restored)
    }

    @Test
    fun persistedPostRatioRestoresLaterPageWhenBlockAnchorChanged() {
        val pages = (0 until 5).map { pageIndex ->
            plannedPage(postId = 10, pageIndex = pageIndex, totalPages = 5)
        }

        val restored = resolvePageIndexForPersistedAnchor(
            pages = pages,
            postId = 10,
            blockId = "missing-after-repagination",
            blockRatio = null,
            postRatio = 0.75f,
        )

        assertEquals(3, restored)
    }

    @Test
    fun semanticPageIdentityDoesNotDependOnNumericPageIndex() {
        val slices = listOf(ThreadReaderPageSlice.Text("body", 100, 200, estimatedHeightPx = 300))
        val firstPlan = ThreadReaderPlannedPage(
            postId = 10,
            pageIndexInPost = 1,
            totalPagesInPost = 4,
            estimatedHeightPx = 300,
            anchorRange = ThreadReaderAnchorRange(10, "body", 100, 200),
            slices = slices,
        )
        val refinedPlan = firstPlan.copy(pageIndexInPost = 3, totalPagesInPost = 6, estimatedHeightPx = 340)

        assertEquals(firstPlan.semanticStableId(), refinedPlan.semanticStableId())
    }

    @Test
    fun planTransitionPrefersStableSemanticPageAfterEarlierPageInsertion() {
        val previous = (0 until 4).map { pageIndex ->
            plannedPage(postId = 10, pageIndex = pageIndex, totalPages = 4)
        }
        val inserted = plannedPage(postId = 9, pageIndex = 0, totalPages = 1)
        val candidate = listOf(inserted) + previous.mapIndexed { index, page ->
            page.copy(pageIndexInPost = index, totalPagesInPost = 4)
        }

        val transition = resolveSinglePagePlanTransition(
            previousPages = previous,
            candidatePages = candidate,
            previousPageIndex = 2,
            previousStablePageId = previous[2].semanticStableId(),
            anchor = null,
        )

        assertEquals(3, transition?.pageIndex)
        assertEquals(previous[2].semanticStableId(), transition?.stablePageId)
    }

    @Test
    fun missingAnchorDuringSameFrameNavigationKeepsPostLocalProgress() {
        val previous = (0 until 4).map { pageIndex ->
            plannedPage(postId = 10, pageIndex = pageIndex, totalPages = 4)
        }
        val candidate = (0 until 5).map { pageIndex ->
            ThreadReaderPlannedPage(
                postId = 10,
                pageIndexInPost = pageIndex,
                totalPagesInPost = 5,
                estimatedHeightPx = 100,
                anchorRange = ThreadReaderAnchorRange(10, "refined-$pageIndex", null, null),
                slices = listOf(ThreadReaderPageSlice.Block("refined-$pageIndex", 100, "Image")),
            )
        }

        val transition = resolveSinglePagePlanTransition(
            previousPages = previous,
            candidatePages = candidate,
            previousPageIndex = 3,
            previousStablePageId = previous[3].semanticStableId(),
            anchor = ThreadReaderReadingAnchor(10, "missing-after-image-size", null, null),
        )

        assertEquals(4, transition?.pageIndex)
    }

    @Test
    fun lateImageGeometryKeepsCurrentSemanticImagePage() {
        val previous = (0 until 4).map { pageIndex ->
            ThreadReaderPlannedPage(
                postId = 10,
                pageIndexInPost = pageIndex,
                totalPagesInPost = 4,
                estimatedHeightPx = 500,
                anchorRange = ThreadReaderAnchorRange(10, "image-$pageIndex", null, null),
                slices = listOf(ThreadReaderPageSlice.Block("image-$pageIndex", 500, "Image")),
            )
        }
        val candidate = previous.map { page ->
            page.copy(estimatedHeightPx = 640, slices = page.slices.map { slice ->
                (slice as ThreadReaderPageSlice.Block).copy(estimatedHeightPx = 640)
            })
        }

        val transition = resolveSinglePagePlanTransition(
            previousPages = previous,
            candidatePages = candidate,
            previousPageIndex = 3,
            previousStablePageId = previous[3].semanticStableId(),
            anchor = ThreadReaderReadingAnchor(10, "image-3", null, null),
        )

        assertEquals(3, transition?.pageIndex)
    }

    @Test
    fun boundarySegmenterCachesUnchangedTextBlocks() {
        val counting = CountingTextBoundarySegmenter()
        val cached = CachingTextBoundarySegmenter(counting)
        val block = HtmlBlock.Text(AnnotatedString("第一句。第二句。"), anchorId = "body")

        buildSafeBreakMap(block, cached, locale = "zh-Hant")
        buildSafeBreakMap(block, cached, locale = "zh-Hant")

        assertEquals(1, counting.sentenceCalls)
        assertEquals(1, counting.lineBreakCalls)
        assertEquals(1, counting.graphemeCalls)
    }

    @Test
    fun boundarySegmenterCacheEvictsLeastRecentlyUsedText() {
        val cached = CachingTextBoundarySegmenter(CountingTextBoundarySegmenter(), maxEntries = 2)

        repeat(3) { index ->
            buildSafeBreakMap(
                HtmlBlock.Text(AnnotatedString("第${index}句。"), anchorId = "body-$index"),
                cached,
            )
        }

        assertEquals(2, cached.cacheSize)
    }

    @Test
    fun ordinaryTextStyleDoesNotSuppressSentenceBoundary() {
        val styled = AnnotatedString.Builder().apply {
            pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
            append("第一句。第二句。")
            pop()
        }.toAnnotatedString()
        val breakMap = buildSafeBreakMap(HtmlBlock.Text(styled, anchorId = "styled"))

        assertEquals(styled.text.indexOf('。') + 1, breakMap.bestBreak(0, styled.length - 1))
    }

    @Test
    fun exactSliceBoundaryResolvesToFollowingPage() {
        val pages = listOf(
            ThreadReaderPlannedPage(
                postId = 10,
                pageIndexInPost = 0,
                totalPagesInPost = 2,
                estimatedHeightPx = 100,
                anchorRange = ThreadReaderAnchorRange(10, "body", 0, 10),
                slices = listOf(ThreadReaderPageSlice.Text("body", 0, 10, 100)),
            ),
            ThreadReaderPlannedPage(
                postId = 10,
                pageIndexInPost = 1,
                totalPagesInPost = 2,
                estimatedHeightPx = 100,
                anchorRange = ThreadReaderAnchorRange(10, "body", 10, 20),
                slices = listOf(ThreadReaderPageSlice.Text("body", 10, 20, 100)),
            ),
        )

        assertEquals(
            1,
            resolvePageIndexForAnchor(
                pages,
                ThreadReaderReadingAnchor(10, "body", 10, null),
            )
        )
        assertEquals(
            1,
            resolvePageIndexForAnchor(
                pages,
                ThreadReaderReadingAnchor(10, "body", 20, null),
            )
        )
    }

    @Test
    fun viewportAnchorExcludesOverlayControls() {
        val page = ThreadReaderPlannedPage(
            postId = 10,
            pageIndexInPost = 0,
            totalPagesInPost = 1,
            estimatedHeightPx = 1_000,
            anchorRange = ThreadReaderAnchorRange(10, "body", 0, 1_000),
            slices = listOf(
                ThreadReaderPageSlice.Text(
                    blockId = "body",
                    startOffset = 0,
                    endOffset = 1_000,
                    estimatedHeightPx = 1_000,
                )
            ),
        )

        val closedPanelAnchor = captureSinglePageViewportAnchor(page, viewportHeightPx = 1_000)
        val openPanelAnchor = captureSinglePageViewportAnchor(
            page = page,
            viewportHeightPx = 1_000,
            topOverlayPx = 100,
            bottomOverlayPx = 300,
        )

        assertEquals(500, closedPanelAnchor.textOffset)
        assertEquals(400, openPanelAnchor.textOffset)
    }

    @Test
    fun footerSectionAnchorsResolveSingleFooterSection() {
        val anchor = footerSectionAnchor(PostFooterSection.Rates)
        val groupAnchor = footerSectionAnchor(
            setOf(PostFooterSection.Metadata, PostFooterSection.Rates, PostFooterSection.Actions)
        )

        assertEquals(setOf(PostFooterSection.Rates), footerSectionsForAnchorBlockType(anchor))
        assertEquals(
            setOf(PostFooterSection.Metadata, PostFooterSection.Rates, PostFooterSection.Actions),
            footerSectionsForAnchorBlockType(groupAnchor)
        )
        assertEquals(PostFooterSection.All, footerSectionsForAnchorBlockType("Footer"))
    }

    @Test
    fun measuredFooterUnitsDoNotOverflowReadableViewport() {
        val units = listOf(
            ThreadReaderMeasuredUnit(
                id = "download-links",
                kind = ThreadReaderMeasuredUnitKind.HtmlTextSlice,
                heightPx = 180,
            ),
            ThreadReaderMeasuredUnit(
                id = "rating-0-9",
                kind = ThreadReaderMeasuredUnitKind.RatingRows,
                heightPx = 360,
            ),
            ThreadReaderMeasuredUnit(
                id = "rating-10-19",
                kind = ThreadReaderMeasuredUnitKind.RatingRows,
                heightPx = 360,
            ),
            ThreadReaderMeasuredUnit(
                id = "comment-0",
                kind = ThreadReaderMeasuredUnitKind.CommentRows,
                heightPx = 180,
            ),
            ThreadReaderMeasuredUnit(
                id = "actions",
                kind = ThreadReaderMeasuredUnitKind.ActionRow,
                heightPx = 72,
            ),
        )

        val pages = packMeasuredThreadReaderUnits(
            units = units,
            viewportHeightPx = 640,
            verticalPaddingPx = 80,
        )

        assertTrue(pages.size > 1)
        pages.forEach { page ->
            assertTrue(
                page.estimatedHeightPx <= 560,
                "measured page ${page.pageIndex} overflows: ${page.estimatedHeightPx}"
            )
        }
    }

    @Test
    fun measuredPackerReportsOversizedUnitsInsteadOfSilentlyAcceptingThem() {
        val result = packMeasuredThreadReaderUnitsResult(
            units = listOf(
                ThreadReaderMeasuredUnit(
                    id = "oversized-comment",
                    kind = ThreadReaderMeasuredUnitKind.CommentRows,
                    heightPx = 720,
                )
            ),
            viewportHeightPx = 640,
            verticalPaddingPx = 80,
        )

        assertTrue(result.hasOverflow)
        assertEquals("oversized-comment", result.rejectedUnits.single().id)
        assertTrue(result.pages.all { it.estimatedHeightPx <= 560 })
    }

    @Test
    fun physicalDragDirectionMatchesSinglePageModes() {
        assertEquals(1, singlePageDeltaForPhysicalDrag(ThreadReaderMode.SINGLE_LTR, physicalDrag = -120f))
        assertEquals(-1, singlePageDeltaForPhysicalDrag(ThreadReaderMode.SINGLE_LTR, physicalDrag = 120f))
        assertEquals(0, singlePageDeltaForPhysicalDrag(ThreadReaderMode.SINGLE_LTR, physicalDrag = 0f))
        assertEquals(-1, singlePageDeltaForPhysicalDrag(ThreadReaderMode.SINGLE_RTL, physicalDrag = -120f))
        assertEquals(1, singlePageDeltaForPhysicalDrag(ThreadReaderMode.SINGLE_RTL, physicalDrag = 120f))
        assertEquals(0, singlePageDeltaForPhysicalDrag(ThreadReaderMode.SINGLE_RTL, physicalDrag = 0f))
        assertEquals(1, singlePageDeltaForPhysicalDrag(ThreadReaderMode.SINGLE_TTB, physicalDrag = -120f))
        assertEquals(-1, singlePageDeltaForPhysicalDrag(ThreadReaderMode.SINGLE_TTB, physicalDrag = 120f))
        assertEquals(0, singlePageDeltaForPhysicalDrag(ThreadReaderMode.SINGLE_TTB, physicalDrag = 0f))
        assertEquals(0, singlePageDeltaForPhysicalDrag(ThreadReaderMode.SCROLL_CONTINUOUS, physicalDrag = -120f))
    }

    @Test
    fun touchActionDirectionMatchesSinglePageModes() {
        assertEquals(1, singlePageDeltaForTouchAction(ThreadReaderMode.SINGLE_LTR, SinglePageTapAction.Next))
        assertEquals(-1, singlePageDeltaForTouchAction(ThreadReaderMode.SINGLE_LTR, SinglePageTapAction.Prev))
        assertEquals(-1, singlePageDeltaForTouchAction(ThreadReaderMode.SINGLE_RTL, SinglePageTapAction.Next))
        assertEquals(1, singlePageDeltaForTouchAction(ThreadReaderMode.SINGLE_RTL, SinglePageTapAction.Prev))
        assertEquals(1, singlePageDeltaForTouchAction(ThreadReaderMode.SINGLE_TTB, SinglePageTapAction.Next))
        assertEquals(-1, singlePageDeltaForTouchAction(ThreadReaderMode.SINGLE_TTB, SinglePageTapAction.Prev))
        assertEquals(0, singlePageDeltaForTouchAction(ThreadReaderMode.SINGLE_LTR, SinglePageTapAction.Menu))
        assertEquals(0, singlePageDeltaForTouchAction(ThreadReaderMode.SINGLE_LTR, null))
    }

    @Test
    fun physicalDragForDeltaIsInverseOfDragDelta() {
        listOf(
            ThreadReaderMode.SINGLE_LTR,
            ThreadReaderMode.SINGLE_RTL,
            ThreadReaderMode.SINGLE_TTB,
        ).forEach { mode ->
            assertEquals(1, singlePageDeltaForPhysicalDrag(mode, physicalDragForSinglePageDelta(mode, 1)))
            assertEquals(-1, singlePageDeltaForPhysicalDrag(mode, physicalDragForSinglePageDelta(mode, -1)))
            assertEquals(0, physicalDragForSinglePageDelta(mode, 0).toInt())
        }
    }

    private fun plannedPage(postId: Long, pageIndex: Int, totalPages: Int) =
        ThreadReaderPlannedPage(
            postId = postId,
            pageIndexInPost = pageIndex,
            totalPagesInPost = totalPages,
            estimatedHeightPx = 100,
            anchorRange = ThreadReaderAnchorRange(postId, "body-$pageIndex", null, null),
            slices = emptyList(),
        )

    private class CountingTextBoundarySegmenter : TextBoundarySegmenter {
        var sentenceCalls = 0
        var lineBreakCalls = 0
        var graphemeCalls = 0

        override fun sentenceBoundaries(text: String, locale: String?): IntArray {
            sentenceCalls += 1
            return UnicodeFallbackTextBoundarySegmenter.sentenceBoundaries(text, locale)
        }

        override fun lineBreakBoundaries(text: String, locale: String?): IntArray {
            lineBreakCalls += 1
            return UnicodeFallbackTextBoundarySegmenter.lineBreakBoundaries(text, locale)
        }

        override fun graphemeBoundaries(text: String, locale: String?): IntArray {
            graphemeCalls += 1
            return UnicodeFallbackTextBoundarySegmenter.graphemeBoundaries(text, locale)
        }
    }
}

package me.thenano.yamibo.yamibo_app.thread.reader.components.post.impl

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.em
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FirstLineIndentTest {
    @Test
    fun paragraphStartsFollowExplicitLineBreaks() {
        assertEquals(listOf(0, 2, 5), findParagraphStartOffsets("甲\n乙\n\n丙"))
    }

    @Test
    fun configuredWidthReplacesDifferentSourceIndentsWithoutAddingBlankLines() {
        val block = HtmlBlock.Text(AnnotatedString("　　第一段\n\n     第二段\n\t第三段"))
        listOf(0f, 1f, 2.5f, 4f, 8f).forEach { width ->
            val text = applyFirstLineIndent(block, enabled = true, indentChars = width).annotatedString
            assertEquals("第一段\n\n第二段\n第三段", text.text.replace("\u00a0", ""))
            val spacers = firstLineIndentPlaceholders(text)
            assertEquals(if (width == 0f) 0 else 3, spacers.size)
            assertTrue(spacers.all { it.item.width == width.em && it.item.height == 0.em })
            assertTrue(text.paragraphStyles.isEmpty())
        }
    }

    @Test
    fun disabledSettingPreservesSourceAndContinuationIsNotReindented() {
        val block = HtmlBlock.Text(AnnotatedString("　原文\n 下一段"))
        assertEquals(block, applyFirstLineIndent(block, enabled = false, indentChars = 4f))
        val continuation = block.copy(paragraphStartOffsets = listOf(4))
        val result = applyFirstLineIndent(continuation, true, 2.5f)
        assertEquals("　原文\n\u00a0下一段", result.annotatedString.text)
        assertEquals(4, firstLineIndentPlaceholders(result.annotatedString).single().start)
    }

    @Test
    fun trimmingIndentPreservesLinkAndRubyRanges() {
        val text = AnnotatedString.Builder().apply {
            append("　　")
            pushStringAnnotation("URL", "https://example.test")
            append("漢字")
            pop()
        }.toAnnotatedString()
        val block = HtmlBlock.Text(text, rubies = listOf(HtmlBlock.RubyText("ruby", 2, 4, "漢字", "かんじ")))
        val result = applyFirstLineIndent(block, true, 2.5f)
        assertEquals("\u00a0漢字", result.annotatedString.text)
        assertEquals(1, result.annotatedString.getStringAnnotations("URL", 0, 3).single().start)
        assertEquals(1, result.rubies.single().start)
        assertEquals(3, result.rubies.single().end)
        val inline = buildRubyInlineLayout(result.annotatedString, result.rubies)
        assertEquals(result.annotatedString.paragraphStyles, inline.text.paragraphStyles)
        assertEquals(firstLineIndentPlaceholders(result.annotatedString), firstLineIndentPlaceholders(inline.text))
        assertEquals("https://example.test", inline.text.getStringAnnotations("URL", 0, 2).single().item)
    }

    @Test
    fun slicedContinuationOnlyKeepsParagraphStartsInsideSlice() {
        assertEquals(listOf(2), listOf(0, 3).sliceParagraphStartOffsets(start = 1, end = 5))
    }
}

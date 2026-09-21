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
    fun enabledIndentAddsTwoEmToEachParagraphStart() {
        val text = AnnotatedString("第一段\n第二段")

        val indented = applyFirstLineIndent(text, findParagraphStartOffsets(text.text), enabled = true)

        assertEquals(2, indented.paragraphStyles.size)
        assertTrue(indented.paragraphStyles.all { it.item.textIndent?.firstLine == 2.em })
    }

    @Test
    fun existingWhitespaceIndentIsNotDoubled() {
        val text = AnnotatedString("　已縮排段落")

        val indented = applyFirstLineIndent(text, listOf(0), enabled = true)

        assertTrue(indented.paragraphStyles.isEmpty())
    }

    @Test
    fun slicedContinuationOnlyKeepsParagraphStartsInsideSlice() {
        assertEquals(listOf(2), listOf(0, 3).sliceParagraphStartOffsets(start = 1, end = 5))
    }
}

package me.thenano.yamibo.yamibo_app.thread.reader.components.post.impl

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BlankTextLineHitTest {
    @Test
    fun emptyAndWhitespaceOnlyLinesPassThroughSelection() {
        val text = "first\n \t\u00A0\u3000\nthird"

        assertTrue(text.isVisuallyBlankTextLine(start = 6, end = 11))
        assertTrue(text.isVisuallyBlankTextLine(start = 6, end = 6))
    }

    @Test
    fun invisibleSpacingCharactersPassThroughSelection() {
        val text = "\u200B\u2060"

        assertTrue(text.isVisuallyBlankTextLine(start = 0, end = text.length))
    }

    @Test
    fun glyphBearingLinesRemainSelectable() {
        val text = "  visible text  \n"

        assertFalse(text.isVisuallyBlankTextLine(start = 0, end = text.length))
    }

    @Test
    fun lineBoundsAreSafelyClamped() {
        val text = "word"

        assertFalse(text.isVisuallyBlankTextLine(start = -10, end = 99))
        assertTrue(text.isVisuallyBlankTextLine(start = 99, end = 120))
    }
}

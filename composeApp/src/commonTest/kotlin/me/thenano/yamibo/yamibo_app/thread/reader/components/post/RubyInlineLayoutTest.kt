package me.thenano.yamibo.yamibo_app.thread.reader.components.post

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import me.thenano.yamibo.yamibo_app.thread.reader.components.post.impl.HtmlBlock
import me.thenano.yamibo.yamibo_app.thread.reader.components.post.impl.buildRubyInlineLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RubyInlineLayoutTest {
    @Test
    fun validRubyKeepsTextLengthOffsetsAndStyledBaseText() {
        val source = buildAnnotatedString {
            append("前")
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("文字") }
            append("後")
        }
        val layout = buildRubyInlineLayout(
            text = source,
            rubies = listOf(ruby(id = "styled", start = 1, end = 3, annotation = "もじ")),
        )

        assertEquals(source.text, layout.text.text)
        assertEquals(source.length, layout.text.length)
        assertEquals(1 to 3, layout.contents.single().sourceStart to layout.contents.single().sourceEnd)
        assertTrue(layout.contents.single().baseText.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
        assertTrue(
            layout.text.getStringAnnotations(0, layout.text.length)
                .any { it.item == layout.contents.single().id && it.start == 1 && it.end == 3 },
        )
    }

    @Test
    fun consecutiveRubiesAreAcceptedInSourceOrder() {
        val layout = buildRubyInlineLayout(
            text = AnnotatedString("性騷擾"),
            rubies = listOf(
                ruby(id = "second", start = 1, end = 3, annotation = "Harassment"),
                ruby(id = "first", start = 0, end = 1, annotation = "Sexual"),
            ),
        )

        assertEquals("性騷擾", layout.text.text)
        assertEquals(listOf(0 to 1, 1 to 3), layout.contents.map { it.sourceStart to it.sourceEnd })
        assertEquals(listOf("Sexual", "Harassment"), layout.contents.map { it.rubyText })
    }

    @Test
    fun malformedAndOverlappingRubiesLeaveSourceTextVisible() {
        val source = AnnotatedString("前文字後")
        val layout = buildRubyInlineLayout(
            text = source,
            rubies = listOf(
                ruby(id = "valid", start = 1, end = 3),
                ruby(id = "overlap", start = 2, end = 4),
                ruby(id = "negative", start = -1, end = 1),
                ruby(id = "empty", start = 3, end = 3),
                ruby(id = "past-end", start = 3, end = 8),
                ruby(id = "blank-annotation", start = 3, end = 4, annotation = " "),
            ),
        )

        assertEquals(source.text, layout.text.text)
        assertEquals(source.length, layout.text.length)
        assertEquals(listOf(1 to 3), layout.contents.map { it.sourceStart to it.sourceEnd })
    }

    private fun ruby(
        id: String,
        start: Int,
        end: Int,
        annotation: String = "ruby",
    ) = HtmlBlock.RubyText(
        id = id,
        start = start,
        end = end,
        baseText = "unused",
        rubyText = annotation,
    )
}

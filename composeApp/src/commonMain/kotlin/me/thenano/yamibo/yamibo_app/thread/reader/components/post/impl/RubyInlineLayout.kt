package me.thenano.yamibo.yamibo_app.thread.reader.components.post.impl

import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.AnnotatedString

internal data class RubyInlineContent(
    val id: String,
    val baseText: AnnotatedString,
    val rubyText: String,
    val sourceStart: Int,
    val sourceEnd: Int,
)

internal data class RubyInlineLayout(
    val text: AnnotatedString,
    val contents: List<RubyInlineContent>,
)

internal fun buildRubyInlineLayout(
    text: AnnotatedString,
    rubies: List<HtmlBlock.RubyText>,
): RubyInlineLayout {
    val builder = AnnotatedString.Builder(text)
    val contents = mutableListOf<RubyInlineContent>()
    var cursor = 0

    rubies.sortedWith(compareBy<HtmlBlock.RubyText> { it.start }.thenBy { it.end }).forEach { ruby ->
        val start = ruby.start
        val end = ruby.end
        if (
            start < cursor ||
            start < 0 ||
            end > text.length ||
            start >= end ||
            ruby.rubyText.isBlank()
        ) {
            return@forEach
        }

        val baseText = text.subSequence(start, end)
        if (baseText.text.isBlank()) return@forEach

        val inlineId = "ruby-inline-${contents.size}-${ruby.id}-$start-$end"
        // Add the inline annotation without slicing paragraph styles at ruby boundaries.
        val inline = AnnotatedString.Builder().apply {
            appendInlineContent(inlineId, baseText.text)
        }.toAnnotatedString()
        inline.getStringAnnotations(0, inline.length).forEach {
            builder.addStringAnnotation(it.tag, it.item, start + it.start, start + it.end)
        }
        contents += RubyInlineContent(
            id = inlineId,
            baseText = baseText,
            rubyText = ruby.rubyText,
            sourceStart = start,
            sourceEnd = end,
        )
        cursor = end
    }

    return RubyInlineLayout(
        text = builder.toAnnotatedString(),
        contents = contents,
    )
}

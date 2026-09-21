package me.thenano.yamibo.yamibo_app.thread.reader.components.post.impl

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign

sealed class HtmlBlock {
    /** Stable content-hash ID for position tracking */
    abstract val anchorId: String

    data class Text(
        val annotatedString: AnnotatedString,
        val textAlign: TextAlign = TextAlign.Start,
        val rubies: List<RubyText> = emptyList(),
        val paragraphStartOffsets: List<Int> = findParagraphStartOffsets(annotatedString.text),
        override val anchorId: String = ""
    ) : HtmlBlock()
    data class RubyText(
        val id: String,
        val start: Int,
        val end: Int,
        val baseText: String,
        val rubyText: String,
    )
    data class Image(
        val url: String,
        val alt: String? = null,
        val linkAddress: String? = null,
        val isEmoticon: Boolean = false,
        override val anchorId: String = "",
    ) : HtmlBlock()
    data class Attachment(
        val url: String,
        val iconUrl: String?,
        val fileName: String,
        val uploadInfo: String?,
        val statInfo: String?,
        override val anchorId: String = ""
    ) : HtmlBlock()
    data class Collapse(val title: String?, val contentBlocks: List<HtmlBlock>, override val anchorId: String = "") : HtmlBlock()
    data class Locked(val cost: Int, val contentBlocks: List<HtmlBlock>, override val anchorId: String = "") : HtmlBlock()
    data class Quote(val contentBlocks: List<HtmlBlock>, override val anchorId: String = "") : HtmlBlock()
    data class Code(val codeText: String, override val anchorId: String = "") : HtmlBlock()
    data class Hr(override val anchorId: String = "") : HtmlBlock()

    /** Table: rows of cells, each cell containing parsed HtmlBlocks */
    data class Table(val rows: List<TableRow>, override val anchorId: String = "") : HtmlBlock()
    data class TableRow(val cells: List<TableCell>)
    data class TableCell(val blocks: List<HtmlBlock>, val isHeader: Boolean = false)
}

internal fun findParagraphStartOffsets(text: String): List<Int> = buildList {
    if (text.isNotEmpty() && text.first() != '\n') add(0)
    text.forEachIndexed { index, char ->
        val next = index + 1
        if (char == '\n' && next < text.length && text[next] != '\n') add(next)
    }
}

internal fun List<Int>.sliceParagraphStartOffsets(start: Int, end: Int): List<Int> =
    asSequence()
        .filter { it in start until end }
        .map { it - start }
        .toList()

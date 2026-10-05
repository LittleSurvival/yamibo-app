package me.thenano.yamibo.yamibo_app.thread.reader

import io.github.littlesurvival.dto.page.Post
import me.thenano.yamibo.yamibo_app.thread.reader.components.post.impl.HtmlBlock
import me.thenano.yamibo.yamibo_app.thread.reader.components.post.impl.HtmlParser
import me.thenano.yamibo.yamibo_app.util.normalizeImageUrl

internal fun Post.readerImageUrls(): List<String> = readerImageUrls(contentHtml, images.map { it.url })

/** Use the same visible HTML and lazy-image sources as the text reader. */
internal fun readerImageUrls(html: String, fallbackUrls: List<String> = emptyList()): List<String> {
    fun isContentImage(url: String): Boolean {
        val source = url.lowercase()
        return source.isNotBlank() && !source.contains("none.gif") &&
            !source.contains("static/image/") && !source.contains("/smiley/")
    }

    if (html.isBlank()) {
        return fallbackUrls.filter(::isContentImage).map(::normalizeImageUrl)
    }

    return buildList {
        fun collect(blocks: List<HtmlBlock>) {
            blocks.forEach { block ->
                when (block) {
                    is HtmlBlock.Image -> if (!block.isEmoticon && isContentImage(block.url)) {
                        add(normalizeImageUrl(block.url))
                    }
                    is HtmlBlock.Quote -> collect(block.contentBlocks)
                    is HtmlBlock.Collapse -> collect(block.contentBlocks)
                    is HtmlBlock.Locked -> collect(block.contentBlocks)
                    is HtmlBlock.Table -> block.rows.forEach { row ->
                        row.cells.forEach { collect(it.blocks) }
                    }
                    else -> Unit
                }
            }
        }
        collect(HtmlParser.parseHtml(html))
    }
}

package me.thenano.yamibo.yamibo_app.repository.novelexport

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element
import com.fleeksoft.ksoup.nodes.Node
import com.fleeksoft.ksoup.nodes.TextNode
import io.github.littlesurvival.dto.page.Post
import io.github.littlesurvival.dto.page.ThreadPage

sealed interface NovelTxtExportResult {
    data class Success(val title: String, val content: String) : NovelTxtExportResult
    data class Failure(val message: String) : NovelTxtExportResult
}

/**
 * Builds a single .txt file from a forum novel thread.
 *
 * The novel is read page by page (each page is a forum thread page), then all posts
 * (chapters) are merged in display order into one text file.
 */
class NovelTxtExporter {

    /**
     * Fetches every page through [loadPage] and merges all posts into one txt string.
     *
     * [firstPage] is the already-loaded page 1; [loadPage] is invoked for pages 2..total.
     * Returning null from [loadPage] aborts the export with [NovelTxtExportResult.Failure].
     */
    suspend fun buildNovelTxt(
        firstPage: ThreadPage,
        loadPage: suspend (page: Int) -> ThreadPage?,
        onProgress: (fetchedPages: Int, totalPages: Int) -> Unit = { _, _ -> },
    ): NovelTxtExportResult {
        val totalPages = (firstPage.pageNav?.totalPages ?: 1).coerceAtLeast(1)
        val pages = mutableListOf(firstPage)
        onProgress(1, totalPages)

        for (page in 2..totalPages) {
            val threadPage = loadPage(page)
                ?: return NovelTxtExportResult.Failure("第 $page 页加载失败")
            pages += threadPage
            onProgress(page, totalPages)
        }

        val title = firstPage.thread.title
        val content = buildTxt(title = title, posts = pages.flatMap { it.posts })
        return NovelTxtExportResult.Success(title = title, content = content)
    }

    internal fun buildTxt(title: String, posts: List<Post>): String = buildString {
        appendLine(title)
        posts.forEach { post ->
            val chapterTitle = post.title.ifBlank { "第 ${post.floor} 楼" }
            appendLine()
            appendLine(chapterTitle)
            appendLine()
            appendLine(htmlToPlainText(post.contentHtml))
        }
    }

    internal fun htmlToPlainText(html: String): String {
        val document = Ksoup.parse(html)
        val body = document.body()
        val builder = StringBuilder()

        fun appendLineBreak() {
            if (builder.isEmpty()) return
            if (builder.last() != '\n') builder.append('\n')
        }

        fun appendParagraphBreak() {
            if (builder.isEmpty()) return
            while (builder.isNotEmpty() && builder.last() == '\n') {
                builder.deleteCharAt(builder.length - 1)
            }
            if (builder.isNotEmpty()) builder.append("\n\n")
        }

        fun render(node: Node) {
            when (node) {
                is TextNode -> builder.append(node.getWholeText())
                is Element -> {
                    val tag = node.tagName().lowercase()
                    when {
                        tag == "br" -> appendLineBreak()
                        tag == "img" -> {
                            val alt = node.attr("alt").takeIf { it.isNotBlank() }
                            if (alt != null) builder.append("[图片: $alt]") else builder.append("[图片]")
                        }
                        tag == "script" || tag == "style" || tag == "head" -> Unit
                        tag in BLOCK_TAGS -> {
                            appendLineBreak()
                            node.childNodes().forEach { render(it) }
                            appendParagraphBreak()
                        }
                        else -> node.childNodes().forEach { render(it) }
                    }
                }
                else -> node.childNodes().forEach { render(it) }
            }
        }

        render(body)

        return builder.toString()
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .lineSequence()
            .joinToString("\n") { it.trimEnd() }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    fun sanitizeFileName(title: String): String {
        val sanitized = title
            .replace(Regex("""[\\/:*?"<>|\n\r\t]"""), "_")
            .trim()
            .take(80)
        return sanitized.ifBlank { "novel" } + ".txt"
    }

    private companion object {
        val BLOCK_TAGS = setOf(
            "p", "div", "section", "article", "main", "header", "footer", "aside",
            "ul", "ol", "li", "dl", "dt", "dd",
            "h1", "h2", "h3", "h4", "h5", "h6",
            "blockquote", "pre", "table", "thead", "tbody", "tfoot", "tr", "td", "th",
            "hr", "center", "fieldset", "figure", "figcaption",
        )
    }
}

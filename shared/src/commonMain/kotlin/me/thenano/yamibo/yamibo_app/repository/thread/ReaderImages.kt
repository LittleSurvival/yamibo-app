package me.thenano.yamibo.yamibo_app.repository.thread

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Document
import com.fleeksoft.ksoup.nodes.Element
import io.github.littlesurvival.dto.page.Post
import io.github.littlesurvival.dto.page.ThreadPage
import io.github.littlesurvival.dto.value.PostId
import io.github.littlesurvival.dto.value.UserId
import me.thenano.yamibo.yamibo_app.repository.download.normalizeDownloadImageUrl

/** A displayed occurrence. A repeated URL may belong to another floor or occur twice on one floor. */
data class ReaderImage(val postId: PostId, val url: String, val occurrence: Int)

fun ThreadPage.readerImages(authorId: UserId?): List<ReaderImage> {
    val author = authorId ?: posts.firstOrNull()?.author?.uid ?: return emptyList()
    return buildList {
        for (post in posts) {
            if (post.author.uid != author) continue
            val seen = mutableMapOf<String, Int>()
            for (url in post.readerImageUrls()) {
                val occurrence = seen[url] ?: 0
                add(ReaderImage(post.pid, url, occurrence))
                seen[url] = occurrence + 1
            }
        }
    }
}

fun Post.readerImageUrls() = readerImageUrls(contentHtml, images.map { it.url })

fun Element.readerImageAttribute(): String =
    when {
        attr("file").isNotBlank() -> "file"
        attr("zoomfile").isNotBlank() -> "zoomfile"
        else -> "src"
    }

fun visibleReaderHtml(html: String): Document = Ksoup.parseBodyFragment(html.replace("\r", "")).apply {
    select(".jammer").remove()
    select("[style]").filter { it.attr("style").lowercase().replace(" ", "").contains("display:none") }
        .forEach(Element::remove)
}

fun readerImageUrls(html: String, fallback: List<String> = emptyList()): List<String> {
    fun visibleSource(raw: String): String? {
        if (raw.isBlank() || raw.contains("none.gif", true) || raw.contains("static/image/", true) ||
            raw.contains("/smiley/", true)) return null
        return normalizeDownloadImageUrl(raw)
    }
    if (html.isBlank()) return fallback.mapNotNull(::visibleSource)
    if (!html.contains("<img", ignoreCase = true)) return emptyList()
    val document = visibleReaderHtml(html)
    // The text reader renders these as plain text, ruby annotations, or an attachment control.
    document.select("div.blockcode, rt, rp, div.showcollapse_box .showcollapse_title, a[data-cfemail]").remove()
    document.select("ul.post_attlist").forEach { list ->
        val link = list.selectFirst("a[href]")
        if (link != null && link.attr("href").isNotBlank() && link.text().isNotBlank()) list.remove()
    }
    return document.select("img").mapNotNull { visibleSource(it.attr(it.readerImageAttribute())) }
}

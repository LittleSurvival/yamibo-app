package me.thenano.yamibo.yamibo_app.thread.reader.components.post

import me.thenano.yamibo.yamibo_app.thread.reader.components.post.impl.HtmlBlock
import me.thenano.yamibo.yamibo_app.thread.reader.components.post.impl.HtmlParser
import kotlin.test.Test
import kotlin.test.assertEquals

class HtmlParserCloudflareEmailTest {
    @Test
    fun cloudflareProtectedEmailIsDecodedIntoTextAndMailtoLink() {
        val blocks = HtmlParser.parseHtml(
            """
            聯絡：<a href="/cdn-cgi/l/email-protection" class="__cf_email__" data-cfemail="8be4faece1fbbffefecbeae5e4e5eaefeff2a5e8e4e6">[email&nbsp;protected]</a>
            """.trimIndent(),
        )

        val text = blocks.filterIsInstance<HtmlBlock.Text>().single().annotatedString

        assertEquals("聯絡：oqgjp4uu@anonaddy.com", text.text)
        assertEquals(
            "mailto:oqgjp4uu@anonaddy.com",
            text.getStringAnnotations(tag = "URL", start = 0, end = text.length).single().item,
        )
    }

    @Test
    fun malformedCloudflareDataFallsBackToOriginalAnchor() {
        val blocks = HtmlParser.parseHtml(
            "<a href=\"/cdn-cgi/l/email-protection\" data-cfemail=\"not-hex\">[email&nbsp;protected]</a>",
        )

        val text = blocks.filterIsInstance<HtmlBlock.Text>().single().annotatedString

        assertEquals("[email\u3000protected]", text.text)
        assertEquals(
            "/cdn-cgi/l/email-protection",
            text.getStringAnnotations(tag = "URL", start = 0, end = text.length).single().item,
        )
    }
}

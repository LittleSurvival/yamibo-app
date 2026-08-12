package me.thenano.yamibo.yamibo_app.thread.reader.components.post

import me.thenano.yamibo.yamibo_app.thread.reader.components.post.impl.HtmlBlock
import me.thenano.yamibo.yamibo_app.thread.reader.components.post.impl.HtmlParser
import me.thenano.yamibo.yamibo_app.util.normalizeImageUrl
import kotlin.test.Test
import kotlin.test.assertEquals

class HtmlParserImageTest {
    private val inlinePng = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAAB"

    @Test
    fun rawInlineImageReachesRequestPathWithoutNetworkPrefix() {
        val image = HtmlParser.parseHtml("<img src=\"$inlinePng\">")
            .filterIsInstance<HtmlBlock.Image>()
            .single()

        assertEquals(inlinePng, image.url)
        assertEquals(inlinePng, normalizeImageUrl(image.url))
    }

    @Test
    fun forumHttpPrefixedInlineImageIsRepairedForRequestPath() {
        val image = HtmlParser.parseHtml("<img src=\"http://$inlinePng\">")
            .filterIsInstance<HtmlBlock.Image>()
            .single()

        assertEquals("http://$inlinePng", image.url)
        assertEquals(inlinePng, normalizeImageUrl(image.url))
    }
}

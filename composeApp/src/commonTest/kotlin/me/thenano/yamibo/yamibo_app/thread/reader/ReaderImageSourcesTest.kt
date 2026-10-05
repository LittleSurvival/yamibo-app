package me.thenano.yamibo.yamibo_app.thread.reader

import me.thenano.yamibo.yamibo_app.util.normalizeImageUrl
import kotlin.test.Test
import kotlin.test.assertEquals

class ReaderImageSourcesTest {
    @Test
    fun hiddenLoadingImageDoesNotPrecedeTheFirstVisibleImage() {
        val html = """
            <span style="display: none"><img src="https://images.example/loading.gif"></span>
            <font class="jammer"><img src="https://images.example/jammer.gif"></font>
            <img src="/static/image/common/none.gif" file="/data/attachment/first.jpg">
            <img src="https://images.example/second.jpg">
        """.trimIndent()

        assertEquals(
            listOf(normalizeImageUrl("/data/attachment/first.jpg"), "https://images.example/second.jpg"),
            readerImageUrls(html),
        )
    }

    @Test
    fun prefersTheSameLazyImageSourceAsTheTextReader() {
        assertEquals(
            listOf("https://images.example/file.jpg", "https://images.example/zoom.jpg"),
            readerImageUrls("""
                <img file="https://images.example/file.jpg" zoomfile="https://images.example/unused.jpg" src="placeholder.gif">
                <img zoomfile="https://images.example/zoom.jpg" src="placeholder.gif">
            """.trimIndent()),
        )
    }

    @Test
    fun excludesPlaceholdersAndEmoticonsWithoutDroppingRepeatedContent() {
        assertEquals(
            listOf("https://images.example/page.jpg", "https://images.example/page.jpg"),
            readerImageUrls("""
                <img src="none.gif"><img src="/static/image/common/icon.gif">
                <img src="/smiley/happy.gif"><img src="">
                <img src="https://images.example/page.jpg"><img src="https://images.example/page.jpg">
            """.trimIndent()),
        )
    }

    @Test
    fun preservesInlineAndLocalUrisAndClickedImageIndex() {
        val inline = "data:image/png;base64,aGVsbG8="
        val sources = readerImageUrls("""
            <img src="$inline"><img src="file:///download/page.jpg"><img src="content://images/2">
        """.trimIndent())

        assertEquals(listOf(inline, "file:///download/page.jpg", "content://images/2"), sources)
        assertEquals(2, sources.indexOf(normalizeImageUrl("content://images/2")))
    }

    @Test
    fun emptyVisibleContentDoesNotReintroduceHiddenImagesFromApiList() {
        assertEquals(
            emptyList(),
            readerImageUrls(
                "<div style='display:none'><img src='https://images.example/hidden.jpg'></div>",
                listOf("https://images.example/hidden.jpg"),
            ),
        )
    }

    @Test
    fun missingHtmlFallsBackToFilteredNormalizedApiSources() {
        assertEquals(
            listOf(normalizeImageUrl("/data/attachment/page.jpg")),
            readerImageUrls("", listOf("none.gif", "", "/data/attachment/page.jpg")),
        )
    }
}

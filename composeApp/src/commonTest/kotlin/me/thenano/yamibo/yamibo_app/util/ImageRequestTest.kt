package me.thenano.yamibo.yamibo_app.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ImageRequestTest {
    private val inlinePng = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAAB"

    @Test
    fun preservesLocalImageSchemes() {
        assertEquals("content://downloads/image.jpg", normalizeImageUrl("content://downloads/image.jpg"))
        assertEquals("file:///tmp/image.jpg", normalizeImageUrl("file:///tmp/image.jpg"))
        assertEquals(
            "content://downloads/image.jpg",
            normalizeImageUrl("https://bbs.yamibo.com/content://downloads/image.jpg"),
        )
    }
    @Test
    fun normalizesRelativeAttachmentUrl() {
        assertEquals(
            "https://bbs.yamibo.com/data/attachment/forum/example.png",
            normalizeImageUrl("data/attachment/forum/example.png"),
        )
        assertEquals(
            "https://bbs.yamibo.com/data/attachment/forum/example.png",
            normalizeImageUrl("/data/attachment/forum/example.png"),
        )
    }

    @Test
    fun preservesAbsoluteUrl() {
        assertEquals(
            "https://example.com/image.png",
            normalizeImageUrl("https://example.com/image.png"),
        )
    }

    @Test
    fun preservesInlineDataImageAndRepairsKnownHttpPrefixes() {
        assertEquals(inlinePng, normalizeImageUrl(inlinePng))
        assertEquals(inlinePng, normalizeImageUrl("http://$inlinePng"))
        assertEquals(inlinePng, normalizeImageUrl("https://$inlinePng"))
        assertEquals(inlinePng, normalizeImageUrl("https://bbs.yamibo.com/$inlinePng"))
    }

    @Test
    fun keepsMalformedOrUnsupportedDataUrisLocal() {
        val malformedImage = "data:image/png;base64,%%%"
        val unsupportedData = "data:text/plain;base64,SGVsbG8="

        assertEquals(malformedImage, normalizeImageUrl(malformedImage))
        assertEquals(unsupportedData, normalizeImageUrl(unsupportedData))
    }

    @Test
    fun doesNotRewriteGenuineHttpUrlsContainingDataText() {
        val url = "https://example.com/image.png?label=data:image/png"

        assertEquals(url, normalizeImageUrl(url))
    }

    @Test
    fun inlineImageHeadersDoNotContainYamiboNetworkMetadata() {
        val headers = buildImageNetworkHeaders(
            normalizedUrl = inlinePng,
            cookie = "auth-cookie",
            referer = "https://bbs.yamibo.com/thread",
        )

        assertNull(headers["Cookie"])
        assertNull(headers["Referer"])
    }

    @Test
    fun yamiboImageHeadersRemainAuthenticated() {
        val headers = buildImageNetworkHeaders(
            normalizedUrl = "https://bbs.yamibo.com/data/attachment/forum/example.png",
            cookie = "auth-cookie",
            referer = "https://bbs.yamibo.com/thread",
        )

        assertEquals("auth-cookie", headers["Cookie"])
        assertEquals("https://bbs.yamibo.com/thread", headers["Referer"])
    }

    @Test
    fun inlineDiagnosticsExposeMetadataButNotPayload() {
        val summary = imageSourceForDiagnostics(inlinePng)
        val error = imageErrorForDiagnostics("Failed to parse url: $inlinePng", inlinePng)

        assertEquals("data:image/png;base64,<inline data: 32 chars>", summary)
        assertEquals("Failed to parse url: $summary", error)
        assertFalse(summary.contains("iVBOR"))
        assertFalse(error.contains("iVBOR"))
    }

    @Test
    fun malformedInlineReloadIdentityStaysLocalAndBounded() {
        val malformed = "http://data:image/png;base64,%%%"
        val normalized = normalizeImageUrl(malformed)
        val headers = buildImageNetworkHeaders(normalized, "auth-cookie", "https://bbs.yamibo.com/thread")

        assertEquals("data:image/png;base64,%%%", normalized)
        assertEquals("data:image/png;base64,<inline data: 3 chars>", imageSourceForDiagnostics(normalized))
        assertNull(headers["Cookie"])
        assertNull(headers["Referer"])
    }
}

package me.thenano.yamibo.yamibo_app.repository.download

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class DownloadImageFetcherTest {
    @Test fun forumCookieIsRemovedWhenImageRedirectsToAnotherOrigin() = runBlocking {
        val requests = mutableListOf<Pair<String, String?>>()
        val client = HttpClient(MockEngine { request ->
            requests += request.url.toString() to request.headers[HttpHeaders.Cookie]
            if (requests.size == 1) respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://images.example/cover.png"))
            else respond(byteArrayOf(1, 2, 3), HttpStatusCode.OK)
        })
        val fetcher = DownloadImageFetcher(client) { "test-auth-cookie" }
        try {
            assertContentEquals(byteArrayOf(1, 2, 3), fetcher.fetch("https://bbs.yamibo.com/cover.png"))
            assertEquals("test-auth-cookie", requests[0].second)
            assertEquals("https://images.example/cover.png", requests[1].first)
            assertNull(requests[1].second)
        } finally { fetcher.close() }
    }

    @Test fun externalProtocolRelativeImagesAndPlainHttpNeverReceiveCookies() = runBlocking<Unit> {
        val client = HttpClient(MockEngine { request ->
            assertNull(request.headers[HttpHeaders.Cookie])
            respond(byteArrayOf(1), HttpStatusCode.OK)
        })
        val fetcher = DownloadImageFetcher(client) { error("Cookie provider must not be consulted for an untrusted origin") }
        try {
            fetcher.fetch("//images.example/cover.png")
            fetcher.fetch("http://bbs.yamibo.com/cover.png")
            fetcher.fetch("https://bbs.yamibo.com:8443/cover.png")
        } finally { fetcher.close() }
    }

    @Test fun relativeRedirectKeepsPathResolutionAndLoopsAreBounded() = runBlocking {
        var count = 0
        val urls = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            count++
            urls += request.url.encodedPath
            respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "next.png"))
        })
        val fetcher = DownloadImageFetcher(client) { "" }
        try {
            assertFailsWith<IllegalStateException> { fetcher.fetch("https://bbs.yamibo.com/images/cover.png") }
            assertEquals(6, count)
            assertEquals("/images/next.png", urls[1])
        } finally { fetcher.close() }
    }
}

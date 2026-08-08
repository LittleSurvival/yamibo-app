package me.thenano.yamibo.yamibo_app.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class WafCookieHandshakeTest {
    @Test
    fun storesOnlyWafCookieAndReplaysItThroughSelfRedirect() = runBlocking {
        val requestCookies = mutableListOf<String?>()
        var imageRequestCount = 0
        val engine = MockEngine { request ->
            requestCookies += request.headers[HttpHeaders.Cookie]
            when (request.url.encodedPath) {
                "/image.jpg" -> if (imageRequestCount++ == 0) {
                    respond(
                        content = "",
                        status = HttpStatusCode.Found,
                        headers = headersOf(
                            HttpHeaders.Location to listOf(request.url.toString()),
                            HttpHeaders.SetCookie to listOf(
                                "abymg_id=waf-cookie; Max-Age=86400; Domain=.yamibo.com; Path=/",
                                "tracking=must-not-leak; Max-Age=86400; Domain=.yamibo.com; Path=/",
                            ),
                        ),
                    )
                } else {
                    respond(content = "image")
                }

                "/other.jpg" -> respond(content = "other")
                "/external.jpg" -> respond(content = "external")
                else -> error("Unexpected request path: ${request.url.encodedPath}")
            }
        }
        val client = HttpClient(engine) {
            install(WafCookieHandshake)
        }

        try {
            val image = client.get("https://bbs.yamibo.com/image.jpg") {
                header(HttpHeaders.Cookie, "EeqY_2132_auth=auth-cookie")
            }
            val other = client.get("https://bbs.yamibo.com/other.jpg")
            val external = client.get("https://cdn.example.com/external.jpg")

            assertEquals("image", image.bodyAsText())
            assertEquals("other", other.bodyAsText())
            assertEquals("external", external.bodyAsText())
            assertEquals(
                listOf<String?>(
                    "EeqY_2132_auth=auth-cookie",
                    "EeqY_2132_auth=auth-cookie; abymg_id=waf-cookie",
                    "abymg_id=waf-cookie",
                    null,
                ),
                requestCookies,
            )
        } finally {
            client.close()
        }
    }
}

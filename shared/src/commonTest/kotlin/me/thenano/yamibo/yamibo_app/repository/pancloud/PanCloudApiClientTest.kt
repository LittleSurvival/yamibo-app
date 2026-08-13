package me.thenano.yamibo.yamibo_app.repository.pancloud

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PanCloudApiClientTest {

    private fun client(engine: MockEngine): HttpClient = HttpClient(engine)

    private fun api(engine: MockEngine): PanCloudApiClient =
        PanCloudApiClient(client(engine), baseUrl = "https://pan.test/api")

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun registerReturnsAuthResult() = runBlocking {
        val engine = MockEngine { request ->
            assertEquals("/api/auth/register", request.url.encodedPath)
            assertEquals(HttpMethod.Post, request.method)
            val body = (request.body as TextContent).text
            assertTrue(body.contains("\"username\":\"alice\""))
            assertTrue(body.contains("\"password\":\"password123\""))
            respond(
                """{"success":true,"data":{"user":{"id":1,"username":"alice","email":null},"access_token":"at","refresh_token":"rt","expires_in":900}}""",
                HttpStatusCode.Created,
                jsonHeaders,
            )
        }
        val result = api(engine).register("alice", "password123")
        assertEquals("at", result.accessToken)
        assertEquals("rt", result.refreshToken)
        assertEquals(900, result.expiresIn)
        assertEquals(1L, result.user?.id)
    }

    @Test
    fun registerConflictThrowsBusinessException() = runBlocking {
        val engine = MockEngine {
            respond("""{"success":false,"error":"用户名已存在"}""", HttpStatusCode.Conflict, jsonHeaders)
        }
        val exception = assertFailsWith<PanCloudApiException> {
            api(engine).register("alice", "password123")
        }
        assertEquals(409, exception.statusCode)
        assertTrue(exception.message.contains("用户名已存在"))
    }

    @Test
    fun unauthorizedTriggersRefreshAndRetriesOnce() = runBlocking {
        var calls = 0
        var refreshed = false
        val engine = MockEngine { request ->
            calls++
            when {
                request.url.encodedPath == "/api/auth/me" &&
                    request.headers[HttpHeaders.Authorization] == "Bearer new-token" -> {
                    respond("""{"success":true,"data":{"id":7,"username":"alice"}}""", HttpStatusCode.OK, jsonHeaders)
                }
                else -> respond(
                    """{"success":false,"error":"Unauthorized: missing token"}""",
                    HttpStatusCode.Unauthorized,
                    jsonHeaders,
                )
            }
        }
        val api = api(engine)
        api.accessToken = "expired-token"
        api.onUnauthorized = {
            refreshed = true
            api.accessToken = "new-token"
            true
        }

        val user = api.me()

        assertEquals(7L, user.id)
        assertTrue(refreshed)
        assertEquals(2, calls)
    }

    @Test
    fun listFilesParsesEntriesAndSendsQuery() = runBlocking {
        val engine = MockEngine { request ->
            assertEquals("/api/files", request.url.encodedPath)
            assertEquals("folder-1", request.url.parameters["parent_id"])
            respond(
                """{"success":true,"data":[{"id":"f1","name":"a.pdf","type":"file","size":10,"is_chunks":false}]}""",
                HttpStatusCode.OK,
                jsonHeaders,
            )
        }
        val entries = api(engine).listFiles(parentId = "folder-1")
        assertEquals(1, entries.size)
        assertEquals("a.pdf", entries[0].name)
        assertEquals(false, entries[0].isChunks)
    }

    @Test
    fun completeUploadSendsOrderedChunkRefs() = runBlocking {
        val engine = MockEngine { request ->
            assertEquals("/api/files/upload/complete", request.url.encodedPath)
            val body = (request.body as TextContent).text
            assertTrue(body.contains("\"total_size\":20971520"))
            val indexOfZero = body.indexOf("\"index\":0")
            val indexOfOne = body.indexOf("\"index\":1")
            assertTrue(indexOfZero >= 0 && indexOfOne >= 0 && indexOfZero < indexOfOne)
            respond("""{"success":true,"data":{"file_id":"uuid-file","is_chunks":true}}""", HttpStatusCode.OK, jsonHeaders)
        }
        val result = api(engine).completeUpload(
            filename = "movie.mp4",
            totalSize = 20L * 1024 * 1024,
            parentId = null,
            chunks = listOf(
                PanCloudChunkRef(0, "tg-0", 10L * 1024 * 1024),
                PanCloudChunkRef(1, "tg-1", 10L * 1024 * 1024),
            ),
        )
        assertEquals("uuid-file", result.fileId)
        assertEquals(true, result.isChunks)
    }

    @Test
    fun downloadReturnsRawBytes() = runBlocking {
        val engine = MockEngine { request ->
            assertEquals("/api/files/uuid-file/download", request.url.encodedPath)
            respond("file-content", HttpStatusCode.OK)
        }
        val bytes = api(engine).downloadFile("uuid-file")
        assertEquals("file-content", bytes.decodeToString())
    }

    @Test
    fun downloadFailureThrowsBusinessException() = runBlocking {
        val engine = MockEngine {
            respond("""{"success":false,"error":"分享已过期"}""", HttpStatusCode.Gone, jsonHeaders)
        }
        val exception = assertFailsWith<PanCloudApiException> {
            api(engine).downloadFile("uuid-file")
        }
        assertEquals(410, exception.statusCode)
        assertTrue(exception.message.contains("分享已过期"))
    }
}

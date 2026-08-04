package me.thenano.yamibo.yamibo_app.repository.appsync

import io.github.littlesurvival.YamiboClient
import io.github.littlesurvival.dto.value.BlogClassId
import io.github.littlesurvival.dto.value.BlogId
import io.github.littlesurvival.dto.value.FormHash
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncCloudConfigDefaults
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncCloudResult
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncBlogClassSelection
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncBlogDeleteRequest
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncBlogWriteRequest
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncPostAcknowledgement
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.YamiboAppSyncBlogProvider
import me.thenano.yamibo.yamibo_app.store.auth.CookieStore

class YamiboAppSyncBlogProviderTest {
    @Test
    fun submitBlogMatchesCapturedMultipartFormContract() = runBlocking {
        var capturedMethod: HttpMethod? = null
        var capturedUrl = ""
        var capturedCookie: String? = null
        var capturedContentType: String? = null
        var capturedBody = ""
        val engine = MockEngine { request ->
            capturedMethod = request.method
            capturedUrl = request.url.toString()
            capturedCookie = request.headers[HttpHeaders.Cookie]
            capturedContentType = request.body.contentType?.toString()
            capturedBody = request.body.toByteArray().decodeToString()
            respond(
                content = successResponse(blogId = 77),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/html; charset=utf-8"),
            )
        }
        val provider = provider(HttpClient(engine))

        val result = assertIs<AppSyncCloudResult.VerifiedSuccess<AppSyncPostAcknowledgement>>(
            provider.submitBlog(
                AppSyncBlogWriteRequest(
                    blogId = BlogId(77),
                    title = AppSyncCloudConfigDefaults.BLOG_NAME,
                    message = "config-body",
                    classSelection = AppSyncBlogClassSelection.Existing(BlogClassId(4568)),
                    formHash = FORM_HASH,
                ),
            ),
        )

        assertEquals(HttpMethod.Post, capturedMethod)
        assertTrue(capturedUrl.contains("mod=spacecp"))
        assertTrue(capturedUrl.contains("ac=blog"))
        assertTrue(capturedUrl.contains("blogid=77"))
        assertEquals(TEST_COOKIE, capturedCookie)
        assertTrue(capturedContentType.orEmpty().startsWith("multipart/form-data; boundary="))
        assertMultipartField(capturedBody, "subject", AppSyncCloudConfigDefaults.BLOG_NAME)
        assertMultipartField(capturedBody, "message", "config-body")
        assertMultipartField(capturedBody, "classid", "4568")
        assertMultipartField(capturedBody, "friend", "3")
        assertMultipartField(capturedBody, "blogsubmit", "true")
        assertMultipartField(capturedBody, "formhash", FORM_HASH.value)
        assertEquals(listOf(BlogId(77)), result.value.candidateBlogIds)
    }

    @Test
    fun createBlogUsesNewClassFormValueAndEmptyBlogId() = runBlocking {
        var capturedUrl = ""
        var capturedBody = ""
        val engine = MockEngine { request ->
            capturedUrl = request.url.toString()
            capturedBody = request.body.toByteArray().decodeToString()
            respond(successResponse(78), HttpStatusCode.OK)
        }
        val provider = provider(HttpClient(engine))

        assertIs<AppSyncCloudResult.VerifiedSuccess<AppSyncPostAcknowledgement>>(
            provider.submitBlog(
                AppSyncBlogWriteRequest(
                    blogId = null,
                    title = AppSyncCloudConfigDefaults.BLOG_NAME,
                    message = "config-body",
                    classSelection = AppSyncBlogClassSelection.Create(
                        AppSyncCloudConfigDefaults.BLOG_CLASS_NAME,
                    ),
                    formHash = FORM_HASH,
                ),
            ),
        )

        assertTrue(capturedUrl.contains("blogid="))
        assertMultipartField(
            capturedBody,
            "classid",
            "new:${AppSyncCloudConfigDefaults.BLOG_CLASS_NAME}",
        )
    }

    @Test
    fun deleteBlogMatchesCapturedUrlEncodedContract() = runBlocking {
        var capturedUrl = ""
        var capturedReferer: String? = null
        var capturedContentType: String? = null
        var capturedBody = ""
        val engine = MockEngine { request ->
            capturedUrl = request.url.toString()
            capturedReferer = request.headers["Referer"]
            capturedContentType = request.body.contentType?.toString()
            capturedBody = request.body.toByteArray().decodeToString()
            respond(successResponse(79), HttpStatusCode.OK)
        }
        val provider = provider(HttpClient(engine))

        assertIs<AppSyncCloudResult.VerifiedSuccess<AppSyncPostAcknowledgement>>(
            provider.deleteBlog(
                AppSyncBlogDeleteRequest(
                    blogId = BlogId(79),
                    formHash = FORM_HASH,
                ),
            ),
        )

        assertTrue(capturedUrl.contains("op=delete"))
        assertTrue(capturedUrl.contains("blogid=79"))
        assertEquals("https://bbs.yamibo.com/", capturedReferer)
        assertTrue(capturedContentType.orEmpty().startsWith("application/x-www-form-urlencoded"))
        assertTrue(capturedBody.contains("deletesubmit=true"))
        assertTrue(capturedBody.contains("btnsubmit=true"))
        assertTrue(capturedBody.contains("formhash=${FORM_HASH.value}"))
        assertTrue(capturedBody.contains("referer="))
    }

    @Test
    fun unexpectedTransportExceptionIsNotMisreportedAsNetworkFailure() = runBlocking {
        val engine = MockEngine {
            throw IllegalStateException("unexpected")
        }
        val provider = provider(HttpClient(engine))

        assertIs<AppSyncCloudResult.UnknownFailed>(
            provider.submitBlog(
                AppSyncBlogWriteRequest(
                    blogId = null,
                    title = AppSyncCloudConfigDefaults.BLOG_NAME,
                    message = "config-body",
                    classSelection = AppSyncBlogClassSelection.Create(
                        AppSyncCloudConfigDefaults.BLOG_CLASS_NAME,
                    ),
                    formHash = FORM_HASH,
                ),
            ),
        )
        Unit
    }

    private fun provider(httpClient: HttpClient): YamiboAppSyncBlogProvider =
        YamiboAppSyncBlogProvider(
            cookieStore = FakeCookieStore(TEST_COOKIE),
            yamiboClient = YamiboClient(),
            httpClient = httpClient,
        )

    private fun assertMultipartField(body: String, name: String, value: String) {
        assertTrue(
            body.contains("name=\"$name\"") && body.contains("\r\n\r\n$value\r\n"),
            "Multipart body did not contain $name",
        )
    }

    private fun successResponse(blogId: Int): String = """
        <root><![CDATA[
          <div id="messagetext"><p>
            操作成功
            <script>succeedhandle_blog('home.php?mod=space&do=blog&id=$blogId')</script>
          </p></div>
        ]]></root>
    """.trimIndent()

    companion object {
        private const val TEST_COOKIE = "session=test"
        private val FORM_HASH = FormHash("testhash")
    }
}

private class FakeCookieStore(
    private val cookie: String,
) : CookieStore {
    override fun save(value: String) = Unit
    override fun load(): String = cookie
    override fun clear() = Unit
}

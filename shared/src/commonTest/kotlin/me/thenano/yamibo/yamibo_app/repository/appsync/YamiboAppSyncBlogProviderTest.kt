package me.thenano.yamibo.yamibo_app.repository.appsync

import io.github.littlesurvival.YamiboClient
import io.github.littlesurvival.core.YamiboResult
import io.github.littlesurvival.dto.model.BlogClassSelection
import io.github.littlesurvival.dto.model.BlogMutationResponse
import io.github.littlesurvival.dto.value.BlogClassId
import io.github.littlesurvival.dto.value.BlogId
import io.github.littlesurvival.dto.value.FormHash
import kotlinx.coroutines.runBlocking
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncCloudConfigDefaults
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncCloudResult
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncBlogClassSelection
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncBlogDeleteRequest
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncBlogWriteRequest
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncPostAcknowledgement
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.YamiboAppSyncBlogProvider
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.YamiboBlogMutationApi
import me.thenano.yamibo.yamibo_app.store.auth.CookieStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class YamiboAppSyncBlogProviderTest {
    @Test
    fun updateBlogDelegatesToYamiboClientApiAndParsesAcknowledgement() = runBlocking {
        val mutationApi = FakeYamiboBlogMutationApi(successResult(blogId = 77))
        val provider = provider(mutationApi)

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

        val call = assertIs<MutationCall.Update>(mutationApi.lastCall)
        assertEquals(BlogId(77), call.blogId)
        assertEquals(AppSyncCloudConfigDefaults.BLOG_NAME, call.title)
        assertEquals("config-body", call.message)
        assertEquals(BlogClassSelection.Existing(BlogClassId(4568)), call.classSelection)
        assertEquals(FORM_HASH, call.formHash)
        assertEquals(listOf(BlogId(77)), result.value.candidateBlogIds)
    }

    @Test
    fun createBlogDelegatesToAddWithNewClassSelection() = runBlocking {
        val mutationApi = FakeYamiboBlogMutationApi(successResult(blogId = 78))
        val provider = provider(mutationApi)

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

        val call = assertIs<MutationCall.Add>(mutationApi.lastCall)
        assertEquals(
            BlogClassSelection.Create(AppSyncCloudConfigDefaults.BLOG_CLASS_NAME),
            call.classSelection,
        )
    }

    @Test
    fun deleteBlogDelegatesToYamiboClientApi() = runBlocking {
        val mutationApi = FakeYamiboBlogMutationApi(successResult(blogId = 79))
        val provider = provider(mutationApi)

        val result = assertIs<AppSyncCloudResult.VerifiedSuccess<AppSyncPostAcknowledgement>>(
            provider.deleteBlog(
                AppSyncBlogDeleteRequest(
                    blogId = BlogId(79),
                    formHash = FORM_HASH,
                ),
            ),
        )

        assertEquals(MutationCall.Delete(BlogId(79), FORM_HASH), mutationApi.lastCall)
        assertEquals(listOf(BlogId(79)), result.value.candidateBlogIds)
    }

    @Test
    fun wafChallengeIsNotMisreportedAsLogout() = runBlocking {
        val mutationApi = FakeYamiboBlogMutationApi(
            YamiboResult.WafChallenge(
                statusCode = 405,
                url = "https://bbs.yamibo.com/home.php",
            ),
        )
        val provider = provider(mutationApi)

        assertIs<AppSyncCloudResult.NetworkFailed>(
            provider.submitBlog(createRequest()),
        )
        Unit
    }

    @Test
    fun unexpectedMutationExceptionIsNotMisreportedAsNetworkFailure() = runBlocking {
        val mutationApi = FakeYamiboBlogMutationApi(
            result = successResult(blogId = 80),
            throwable = IllegalStateException("unexpected"),
        )
        val provider = provider(mutationApi)

        assertIs<AppSyncCloudResult.UnknownFailed>(
            provider.submitBlog(createRequest()),
        )
        Unit
    }

    private fun provider(mutationApi: YamiboBlogMutationApi): YamiboAppSyncBlogProvider =
        YamiboAppSyncBlogProvider(
            cookieStore = FakeCookieStore(TEST_COOKIE),
            yamiboClient = YamiboClient(),
            mutationApi = mutationApi,
        )

    private fun createRequest(): AppSyncBlogWriteRequest = AppSyncBlogWriteRequest(
        blogId = null,
        title = AppSyncCloudConfigDefaults.BLOG_NAME,
        message = "config-body",
        classSelection = AppSyncBlogClassSelection.Create(
            AppSyncCloudConfigDefaults.BLOG_CLASS_NAME,
        ),
        formHash = FORM_HASH,
    )

    private fun successResult(blogId: Int): YamiboResult<BlogMutationResponse> =
        YamiboResult.Success(
            BlogMutationResponse(
                body = """
                    <root><![CDATA[
                      <div id="messagetext"><p>
                        操作成功
                        <script>succeedhandle_blog('home.php?mod=space&do=blog&id=$blogId')</script>
                      </p></div>
                    ]]></root>
                """.trimIndent(),
                statusCode = 200,
                requestUrl = "https://bbs.yamibo.com/home.php?mod=spacecp&ac=blog",
                finalUrl = "https://bbs.yamibo.com/home.php?mod=space&do=blog&id=$blogId",
                location = null,
            ),
        )

    companion object {
        private const val TEST_COOKIE = "session=test"
        private val FORM_HASH = FormHash("testhash")
    }
}

private class FakeYamiboBlogMutationApi(
    private val result: YamiboResult<BlogMutationResponse>,
    private val throwable: Throwable? = null,
) : YamiboBlogMutationApi {
    var lastCall: MutationCall? = null
        private set

    override suspend fun addBlog(
        title: String,
        message: String,
        classSelection: BlogClassSelection,
        formHash: FormHash,
    ): YamiboResult<BlogMutationResponse> {
        lastCall = MutationCall.Add(title, message, classSelection, formHash)
        throwable?.let { throw it }
        return result
    }

    override suspend fun updateBlog(
        blogId: BlogId,
        title: String,
        message: String,
        classSelection: BlogClassSelection,
        formHash: FormHash,
    ): YamiboResult<BlogMutationResponse> {
        lastCall = MutationCall.Update(blogId, title, message, classSelection, formHash)
        throwable?.let { throw it }
        return result
    }

    override suspend fun deleteBlog(
        blogId: BlogId,
        formHash: FormHash,
    ): YamiboResult<BlogMutationResponse> {
        lastCall = MutationCall.Delete(blogId, formHash)
        throwable?.let { throw it }
        return result
    }
}

private sealed interface MutationCall {
    data class Add(
        val title: String,
        val message: String,
        val classSelection: BlogClassSelection,
        val formHash: FormHash,
    ) : MutationCall

    data class Update(
        val blogId: BlogId,
        val title: String,
        val message: String,
        val classSelection: BlogClassSelection,
        val formHash: FormHash,
    ) : MutationCall

    data class Delete(
        val blogId: BlogId,
        val formHash: FormHash,
    ) : MutationCall
}

private class FakeCookieStore(
    private val cookie: String,
) : CookieStore {
    override fun save(value: String) = Unit
    override fun load(): String = cookie
    override fun clear() = Unit
}

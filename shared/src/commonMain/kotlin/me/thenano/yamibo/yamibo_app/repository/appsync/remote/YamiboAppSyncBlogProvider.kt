package me.thenano.yamibo.yamibo_app.repository.appsync.remote

import io.github.littlesurvival.YamiboClient
import io.github.littlesurvival.core.YamiboResult
import io.github.littlesurvival.dto.model.BlogClassSelection
import io.github.littlesurvival.dto.model.BlogMutationResponse
import io.github.littlesurvival.dto.page.BlogPage
import io.github.littlesurvival.dto.page.UserSpaceBlogPage
import io.github.littlesurvival.dto.value.BlogClassId
import io.github.littlesurvival.dto.value.BlogId
import io.github.littlesurvival.dto.value.FormHash
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.CancellationException
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncCloudResult
import me.thenano.yamibo.yamibo_app.store.auth.CookieStore

class YamiboAppSyncBlogProvider internal constructor(
    private val cookieStore: CookieStore,
    private val yamiboClient: YamiboClient,
    private val mutationApi: YamiboBlogMutationApi,
) : AppSyncBlogProvider {
    constructor(
        cookieStore: CookieStore,
        yamiboClient: YamiboClient,
    ) : this(
        cookieStore = cookieStore,
        yamiboClient = yamiboClient,
        mutationApi = YamiboClientBlogMutationApi(yamiboClient),
    )

    override suspend fun fetchMyBlogs(
        blogClassId: BlogClassId?,
        page: Int,
    ): AppSyncCloudResult<UserSpaceBlogPage> {
        prepareYamiboClient()
        return mapYamiboResult(
            yamiboClient.fetchUserSpaceMyBlogs(
                userId = null,
                blogClassId = blogClassId,
                page = page,
            ),
        )
    }

    override suspend fun fetchBlog(blogId: BlogId): AppSyncCloudResult<BlogPage> {
        prepareYamiboClient()
        return mapYamiboResult(yamiboClient.fetchBlogPage(blogId))
    }

    override suspend fun submitBlog(
        request: AppSyncBlogWriteRequest,
    ): AppSyncCloudResult<AppSyncPostAcknowledgement> = postSafely {
        prepareYamiboClient()
        mapMutationResult(
            if (request.blogId == null) {
                mutationApi.addBlog(
                    title = request.title,
                    message = request.message,
                    classSelection = request.classSelection.toApiSelection(),
                    formHash = request.formHash,
                )
            } else {
                mutationApi.updateBlog(
                    blogId = request.blogId,
                    title = request.title,
                    message = request.message,
                    classSelection = request.classSelection.toApiSelection(),
                    formHash = request.formHash,
                )
            },
        )
    }

    override suspend fun deleteBlog(
        request: AppSyncBlogDeleteRequest,
    ): AppSyncCloudResult<AppSyncPostAcknowledgement> = postSafely {
        prepareYamiboClient()
        mapMutationResult(
            mutationApi.deleteBlog(
                blogId = request.blogId,
                formHash = request.formHash,
            ),
        )
    }

    private fun prepareYamiboClient() {
        yamiboClient.setCookie(cookieStore.load().orEmpty())
    }

    private fun AppSyncBlogClassSelection.toApiSelection(): BlogClassSelection = when (this) {
        is AppSyncBlogClassSelection.Existing -> BlogClassSelection.Existing(classId)
        is AppSyncBlogClassSelection.Create -> BlogClassSelection.Create(className)
    }

    private fun mapMutationResult(
        result: YamiboResult<BlogMutationResponse>,
    ): AppSyncCloudResult<AppSyncPostAcknowledgement> = when (result) {
        is YamiboResult.Success -> {
            val response = result.value
            AppSyncDiscuzResponseParser.parse(
                statusCode = response.statusCode,
                body = response.body,
                identityHintSources = listOfNotNull(
                    response.location,
                    response.finalUrl,
                    response.requestUrl,
                ),
            )
        }

        is YamiboResult.NotLoggedIn -> AppSyncCloudResult.NotLoggedIn
        is YamiboResult.NoPermission -> AppSyncCloudResult.NoPermission(result.reason)
        is YamiboResult.Maintenance -> AppSyncCloudResult.Maintenance
        // AppSync treats this bucket as retryable, so a headless WAF result stays pending.
        is YamiboResult.WafChallenge -> AppSyncCloudResult.NetworkFailed(result.message())
        is YamiboResult.Failure -> mapYamiboFailure(result)
    }

    private suspend fun <T> postSafely(
        request: suspend () -> AppSyncCloudResult<T>,
    ): AppSyncCloudResult<T> = try {
        request()
    } catch (error: CancellationException) {
        throw error
    } catch (error: HttpRequestTimeoutException) {
        AppSyncCloudResult.Timeout(error.message ?: "Yamibo request timed out")
    } catch (error: Exception) {
        val name = error::class.simpleName.orEmpty()
        when {
            name.contains("Timeout", ignoreCase = true) ->
                AppSyncCloudResult.Timeout(error.message ?: name)
            name.contains("IOException", ignoreCase = true) ||
                name.contains("Network", ignoreCase = true) ||
                name.contains("UnresolvedAddress", ignoreCase = true) ||
                name.contains("ConnectException", ignoreCase = true) ->
                AppSyncCloudResult.NetworkFailed(error.message ?: name)
            else -> AppSyncCloudResult.UnknownFailed(error.message ?: name)
        }
    } catch (error: Throwable) {
        AppSyncCloudResult.UnknownFailed(error.message ?: error::class.simpleName.orEmpty())
    }

    private fun <T> mapYamiboResult(result: YamiboResult<T>): AppSyncCloudResult<T> =
        when (result) {
            is YamiboResult.Success -> AppSyncCloudResult.VerifiedSuccess(result.value)
            is YamiboResult.NotLoggedIn -> AppSyncCloudResult.NotLoggedIn
            is YamiboResult.NoPermission -> {
                if (looksNotFound(result.reason)) {
                    AppSyncCloudResult.NotFound
                } else {
                    AppSyncCloudResult.NoPermission(result.reason)
                }
            }
            is YamiboResult.Maintenance -> AppSyncCloudResult.Maintenance
            // AppSync treats this bucket as retryable, so a headless WAF result stays pending.
            is YamiboResult.WafChallenge -> AppSyncCloudResult.NetworkFailed(result.message())
            is YamiboResult.Failure -> mapYamiboFailure(result)
        }

    private fun mapYamiboFailure(
        failure: YamiboResult.Failure,
    ): AppSyncCloudResult<Nothing> {
        val reason = failure.reason
        val safeReason = AppSyncDiscuzResponseParser.safeBodyPreview(reason) ?: "Unknown Yamibo failure"
        if (looksNotFound(reason)) return AppSyncCloudResult.NotFound
        if (reason.contains("非法字符") || reason.contains("登入過期") || reason.contains("登录过期")) {
            return AppSyncCloudResult.NotLoggedIn
        }
        if (reason.startsWith("[Timeout]")) {
            return AppSyncCloudResult.Timeout(safeReason)
        }
        if (reason.startsWith("[Network]")) {
            return AppSyncCloudResult.NetworkFailed(safeReason)
        }
        if (reason.startsWith("[HTTP ")) {
            val statusCode = HTTP_STATUS_REGEX.find(reason)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()
                ?: 0
            val message = AppSyncDiscuzResponseParser.extractMessageText(reason)
            return AppSyncCloudResult.HttpFailed(
                statusCode = statusCode,
                messageText = message,
                bodyPreview = safeReason,
            )
        }
        if (reason.startsWith("[Parse]")) {
            return AppSyncCloudResult.ParseFailed(
                reason = safeReason,
                bodyPreview = safeReason,
            )
        }
        return AppSyncCloudResult.UnknownFailed(safeReason)
    }

    private fun looksNotFound(value: String): Boolean =
        NOT_FOUND_PHRASES.any { phrase -> value.contains(phrase) }

    companion object {
        private val HTTP_STATUS_REGEX = Regex("""^\[HTTP (\d+)]""")
        private val NOT_FOUND_PHRASES = listOf(
            "日志不存在",
            "日誌不存在",
            "指定的日志",
            "指定的日誌",
            "日志已被删除",
            "日誌已被刪除",
        )
    }
}

internal interface YamiboBlogMutationApi {
    suspend fun addBlog(
        title: String,
        message: String,
        classSelection: BlogClassSelection,
        formHash: FormHash,
    ): YamiboResult<BlogMutationResponse>

    suspend fun updateBlog(
        blogId: BlogId,
        title: String,
        message: String,
        classSelection: BlogClassSelection,
        formHash: FormHash,
    ): YamiboResult<BlogMutationResponse>

    suspend fun deleteBlog(
        blogId: BlogId,
        formHash: FormHash,
    ): YamiboResult<BlogMutationResponse>
}

private class YamiboClientBlogMutationApi(
    private val client: YamiboClient,
) : YamiboBlogMutationApi {
    override suspend fun addBlog(
        title: String,
        message: String,
        classSelection: BlogClassSelection,
        formHash: FormHash,
    ): YamiboResult<BlogMutationResponse> =
        client.fetchAddPrivtaeBlog(title, message, classSelection, formHash)

    override suspend fun updateBlog(
        blogId: BlogId,
        title: String,
        message: String,
        classSelection: BlogClassSelection,
        formHash: FormHash,
    ): YamiboResult<BlogMutationResponse> =
        client.fetchUpdateBlog(blogId, title, message, classSelection, formHash)

    override suspend fun deleteBlog(
        blogId: BlogId,
        formHash: FormHash,
    ): YamiboResult<BlogMutationResponse> = client.fetchDeleteBlog(blogId, formHash)
}

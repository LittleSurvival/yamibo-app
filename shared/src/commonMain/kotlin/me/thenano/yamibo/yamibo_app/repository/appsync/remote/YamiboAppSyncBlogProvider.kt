package me.thenano.yamibo.yamibo_app.repository.appsync.remote

import io.github.littlesurvival.YamiboClient
import io.github.littlesurvival.core.YamiboResult
import io.github.littlesurvival.dto.page.BlogPage
import io.github.littlesurvival.dto.page.UserSpaceBlogPage
import io.github.littlesurvival.dto.value.BlogClassId
import io.github.littlesurvival.dto.value.BlogId
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import me.thenano.yamibo.yamibo_app.factory.HttpClientFactory
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncCloudResult
import me.thenano.yamibo.yamibo_app.store.auth.CookieStore

class YamiboAppSyncBlogProvider(
    private val cookieStore: CookieStore,
    private val yamiboClient: YamiboClient,
    private val httpClient: HttpClient = HttpClientFactory.create(),
) : AppSyncBlogProvider {
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
        val url = buildBlogWriteUrl(request.blogId)
        val response = httpClient.post(url) {
            addCommonHeaders(BLOG_FORM_REFERER)
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("subject", request.title)
                        append("savealbumid", "0")
                        append("newalbum", "请输入相册名称")
                        append("view_albumid", "none")
                        append("message", request.message)
                        append("classid", request.classSelection.toFormValue())
                        append("tag", "")
                        append("friend", PRIVATE_VISIBILITY)
                        append("password", "")
                        append("selectgroup", "")
                        append("target_names", "")
                        append("blogsubmit", "true")
                        append("formhash", request.formHash.value)
                    },
                ),
            )
        }
        AppSyncDiscuzResponseParser.parse(
            statusCode = response.status.value,
            body = response.bodyAsText(),
            identityHintSources = listOfNotNull(
                response.headers[HttpHeaders.Location],
                response.call.request.url.toString(),
            ),
        )
    }

    override suspend fun deleteBlog(
        request: AppSyncBlogDeleteRequest,
    ): AppSyncCloudResult<AppSyncPostAcknowledgement> = postSafely {
        val referer = BASE_REFERER
        val response = httpClient.post(buildBlogDeleteUrl(request.blogId)) {
            addCommonHeaders(referer)
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(
                FormDataContent(
                    Parameters.build {
                        append("referer", referer)
                        append("deletesubmit", "true")
                        append("formhash", request.formHash.value)
                        append("btnsubmit", "true")
                    },
                ),
            )
        }
        AppSyncDiscuzResponseParser.parse(
            statusCode = response.status.value,
            body = response.bodyAsText(),
            identityHintSources = listOfNotNull(
                response.headers[HttpHeaders.Location],
                response.call.request.url.toString(),
            ),
        )
    }

    private fun prepareYamiboClient() {
        yamiboClient.setCookie(cookieStore.load().orEmpty())
    }

    private fun AppSyncBlogClassSelection.toFormValue(): String = when (this) {
        is AppSyncBlogClassSelection.Existing -> classId.value.toString()
        is AppSyncBlogClassSelection.Create -> "new:$className"
    }

    private fun io.ktor.client.request.HttpRequestBuilder.addCommonHeaders(referer: String) {
        headers {
            append(HttpHeaders.UserAgent, DESKTOP_USER_AGENT)
            append(HttpHeaders.Origin, ORIGIN)
            append("Referer", referer)
            cookieStore.load()
                ?.replace("\r", "")
                ?.replace("\n", "")
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.let { append(HttpHeaders.Cookie, it) }
        }
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

    private fun buildBlogWriteUrl(blogId: BlogId?): String =
        "$BLOG_CONTROL_URL&blogid=${blogId?.value ?: ""}"

    private fun buildBlogDeleteUrl(blogId: BlogId): String =
        "$BLOG_CONTROL_URL&op=delete&blogid=${blogId.value}"

    companion object {
        private const val ORIGIN = "https://bbs.yamibo.com"
        private const val BASE_REFERER = "$ORIGIN/"
        private const val BLOG_CONTROL_URL =
            "$ORIGIN/home.php?mod=spacecp&ac=blog"
        private const val BLOG_FORM_REFERER =
            "$ORIGIN/home.php?mod=spacecp&ac=blog"
        private const val PRIVATE_VISIBILITY = "3"
        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36"
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

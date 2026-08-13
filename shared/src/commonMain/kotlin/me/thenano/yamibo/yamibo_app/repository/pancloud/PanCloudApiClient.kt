package me.thenano.yamibo.yamibo_app.repository.pancloud

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * 网盘业务异常：统一响应 success=false 或 HTTP 4xx/5xx 时抛出。
 *
 * [statusCode] 为 0 表示响应本身没有业务错误码（例如缺少 data 字段）。
 */
class PanCloudApiException(
    val statusCode: Int,
    override val message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Cloud Nine 网盘 REST 客户端（API.md）。
 *
 * - 统一解析 `{success, data, message, error}` 响应，success=false 抛 [PanCloudApiException]。
 * - [accessToken] 为 Bearer token；[onUnauthorized] 在收到 401 时调用，返回 true 表示已刷新，
 *   随后自动重试一次。
 * - 上传按体积分档（单文件直传 / 分块），下载返回原始字节。
 */
class PanCloudApiClient(
    private val client: HttpClient,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val json: Json = PanCloudJson,
) {
    var accessToken: String? = null

    /** 401 时的刷新钩子，返回 true 表示已成功换取新 [accessToken]。 */
    var onUnauthorized: (suspend () -> Boolean)? = null

    // --- 认证 ---

    suspend fun register(
        username: String,
        password: String,
        email: String? = null,
    ): PanCloudAuthResult = callFor(
        method = HttpMethod.Post,
        path = "/auth/register",
        body = encodeBody(PanCloudRegisterRequest(username, password, email)),
        authenticated = false,
    )

    suspend fun login(username: String, password: String): PanCloudAuthResult = callFor(
        method = HttpMethod.Post,
        path = "/auth/login",
        body = encodeBody(PanCloudLoginRequest(username, password)),
        authenticated = false,
    )

    suspend fun refresh(refreshToken: String): PanCloudAuthResult = callFor(
        method = HttpMethod.Post,
        path = "/auth/refresh",
        body = encodeBody(PanCloudRefreshRequest(refreshToken)),
        authenticated = false,
    )

    suspend fun me(): PanCloudUser = callFor(HttpMethod.Get, "/auth/me")

    suspend fun logout() {
        execute(HttpMethod.Post, "/auth/logout")
    }

    // --- 文件 / 文件夹 ---

    suspend fun listFiles(parentId: String? = null, type: String? = null): List<PanCloudFileEntry> = callFor(
        method = HttpMethod.Get,
        path = "/files",
        query = listOf("parent_id" to parentId, "type" to type),
    )

    suspend fun createFolder(name: String, parentId: String? = null): PanCloudFolder = callFor(
        method = HttpMethod.Post,
        path = "/files/folder",
        body = encodeBody(PanCloudCreateFolderRequest(name, parentId)),
    )

    suspend fun deleteFile(fileId: String) {
        execute(HttpMethod.Delete, "/files/$fileId")
    }

    suspend fun storage(): PanCloudStorage = callFor(HttpMethod.Get, "/user/storage")

    // --- 上传 ---

    suspend fun uploadFile(
        bytes: ByteArray,
        name: String,
        mimeType: String,
        parentId: String?,
    ): PanCloudUploadedFile {
        val response = sendWithAuthRetry(allowRetry = true) {
            client.submitFormWithBinaryData(
                url = "$baseUrl/files/upload",
                formData = formData {
                    append(
                        "file",
                        bytes,
                        Headers.build {
                            append(HttpHeaders.ContentType, mimeType)
                            append(HttpHeaders.ContentDisposition, "filename=\"$name\"")
                        },
                    )
                    parentId?.let { append("parent_id", it) }
                },
            ) { authHeader() }
        }
        return decodeData(response)
    }

    suspend fun uploadChunk(
        chunk: ByteArray,
        index: Int,
        filename: String,
        parentId: String?,
    ): PanCloudChunkResult {
        val response = sendWithAuthRetry(allowRetry = true) {
            client.post("$baseUrl/files/upload/chunk") {
                authHeader()
                header("X-Filename", filename)
                header("X-Chunk-Index", index.toString())
                parentId?.let { header("X-Parent-Id", it) }
                contentType(ContentType.Application.OctetStream)
                setBody(chunk)
            }
        }
        return decodeData(response)
    }

    suspend fun completeUpload(
        filename: String,
        totalSize: Long,
        parentId: String?,
        chunks: List<PanCloudChunkRef>,
    ): PanCloudCompletedFile = callFor(
        method = HttpMethod.Post,
        path = "/files/upload/complete",
        body = encodeBody(
            PanCloudCompleteUploadRequest(
                filename = filename,
                totalSize = totalSize,
                parentId = parentId,
                fileIds = chunks,
            ),
        ),
    )

    // --- 下载 ---

    suspend fun downloadFile(fileId: String): ByteArray {
        val response = sendWithAuthRetry(allowRetry = true) {
            client.get("$baseUrl/files/$fileId/download") { authHeader() }
        }
        throwOnFailure(response)
        return response.bodyAsBytes()
    }

    // --- 内部实现 ---

    private suspend fun execute(
        method: HttpMethod,
        path: String,
        body: String? = null,
        query: List<Pair<String, String?>> = emptyList(),
        authenticated: Boolean = true,
    ): PanCloudResponse {
        val response = sendWithAuthRetry(allowRetry = authenticated) {
            sendJson(method, path, body, query, authenticated)
        }
        return parseResponse(response)
    }

    private suspend inline fun <reified T> callFor(
        method: HttpMethod,
        path: String,
        body: String? = null,
        query: List<Pair<String, String?>> = emptyList(),
        authenticated: Boolean = true,
    ): T {
        val response = execute(method, path, body, query, authenticated)
        val data: JsonElement = response.data
            ?: throw PanCloudApiException(0, "响应缺少 data 字段")
        return json.decodeFromJsonElement(data)
    }

    private suspend fun sendJson(
        method: HttpMethod,
        path: String,
        body: String?,
        query: List<Pair<String, String?>>,
        authenticated: Boolean,
    ): HttpResponse = client.request("$baseUrl$path") {
        this.method = method
        query.forEach { (key, value) -> if (value != null) parameter(key, value) }
        if (authenticated) authHeader()
        if (body != null) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
    }

    /** 发送请求，若收到 401 且 [allowRetry] 为真且 [onUnauthorized] 刷新成功则自动重试一次。 */
    private suspend fun sendWithAuthRetry(
        allowRetry: Boolean,
        block: suspend () -> HttpResponse,
    ): HttpResponse {
        val first = block()
        if (allowRetry && first.status == HttpStatusCode.Unauthorized && onUnauthorized?.invoke() == true) {
            return block()
        }
        return first
    }

    private suspend inline fun <reified T> decodeData(response: HttpResponse): T {
        val parsed = parseResponse(response)
        val data: JsonElement = parsed.data
            ?: throw PanCloudApiException(0, "响应缺少 data 字段")
        return json.decodeFromJsonElement(data)
    }

    private suspend fun parseResponse(response: HttpResponse): PanCloudResponse {
        val text = response.bodyAsText()
        val parsed = runCatching { json.decodeFromString<PanCloudResponse>(text) }
            .getOrElse { throw PanCloudApiException(response.status.value, "响应解析失败", it) }
        if (!parsed.success) {
            throw PanCloudApiException(
                response.status.value,
                parsed.error ?: parsed.message ?: "请求失败",
            )
        }
        return parsed
    }

    /** 下载等二进制接口：失败时返回 JSON，成功时返回字节流。 */
    private suspend fun throwOnFailure(response: HttpResponse) {
        if (response.status.value < 400) return
        val text = response.bodyAsText()
        val parsed = runCatching { json.decodeFromString<PanCloudResponse>(text) }.getOrNull()
        throw PanCloudApiException(
            response.status.value,
            parsed?.error ?: parsed?.message ?: "下载失败",
        )
    }

    private inline fun <reified T> encodeBody(value: T): String = json.encodeToString(value)

    private fun HttpRequestBuilder.authHeader() {
        accessToken?.let { header(HttpHeaders.Authorization, "Bearer $it") }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://pan.muleng.dpdns.org/api"
    }
}

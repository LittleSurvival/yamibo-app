package me.thenano.yamibo.yamibo_app.repository.pancloud

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNamingStrategy

/**
 * Cloud Nine 网盘 API 的 DTO 与共享 JSON 配置。
 *
 * 所有 JSON 字段为 snake_case（见 API.md），统一用 [JsonNamingStrategy.SnakeCase]
 * 映射到 camelCase 属性，避免逐个手写 @SerialName。
 */
@OptIn(ExperimentalSerializationApi::class)
val PanCloudJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    namingStrategy = JsonNamingStrategy.SnakeCase
    isLenient = true
}

/**
 * 统一响应包装（API.md 1.3 节）。
 *
 * [data] 保留为 [JsonElement]，由调用方按具体类型解码，避免泛型序列化带来的
 * 序列化器传递复杂度，也便于统一处理 success=false 的业务错误。
 */
@Serializable
internal data class PanCloudResponse(
    val success: Boolean,
    val data: JsonElement? = null,
    val message: String? = null,
    val error: String? = null,
)

// --- 认证 ---

@Serializable
internal data class PanCloudRegisterRequest(
    val username: String,
    val password: String,
    val email: String? = null,
)

@Serializable
internal data class PanCloudLoginRequest(
    val username: String,
    val password: String,
)

@Serializable
internal data class PanCloudRefreshRequest(
    val refreshToken: String,
)

@Serializable
data class PanCloudUser(
    val id: Long,
    val username: String,
    val email: String? = null,
    val avatarUrl: String? = null,
    val storageUsed: Long? = null,
    val createdAt: String? = null,
)

@Serializable
data class PanCloudAuthResult(
    val user: PanCloudUser,
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Int,
)

// --- 文件 / 文件夹 ---

@Serializable
data class PanCloudFileEntry(
    val id: String,
    val name: String,
    val type: String,
    val size: Long? = null,
    val sizeFormatted: String? = null,
    val mimeType: String? = null,
    val isChunks: Boolean? = null,
    val createdAt: Long? = null,
    val updatedAt: Long? = null,
    val downloadUrl: String? = null,
    val childCount: Long? = null,
    val path: String? = null,
)

@Serializable
internal data class PanCloudCreateFolderRequest(
    val name: String,
    val parentId: String? = null,
)

@Serializable
data class PanCloudFolder(
    val id: String,
    val name: String,
    val parentId: String? = null,
    val createdAt: Long? = null,
    val updatedAt: Long? = null,
    val path: String? = null,
    val childCount: Long? = null,
    val userId: Long? = null,
)

// --- 上传 ---

@Serializable
data class PanCloudUploadedFile(
    val fileId: String,
    val name: String,
    val size: Long,
    val mimeType: String? = null,
    val isChunks: Boolean? = null,
    val downloadUrl: String? = null,
)

@Serializable
data class PanCloudChunkResult(
    val index: Int,
    val fileId: String,
    val messageId: Long? = null,
)

@Serializable
data class PanCloudChunkRef(
    val index: Int,
    val fileId: String,
    val size: Long,
)

@Serializable
internal data class PanCloudCompleteUploadRequest(
    val filename: String,
    val totalSize: Long,
    val parentId: String? = null,
    val fileIds: List<PanCloudChunkRef>,
)

@Serializable
data class PanCloudCompletedFile(
    val fileId: String,
    val isChunks: Boolean? = null,
)

// --- 存储统计 ---

@Serializable
data class PanCloudStorage(
    val used: Long,
    val files: Int,
    val folders: Int,
)

package me.thenano.yamibo.yamibo_app.repository.appsync.model

import io.github.littlesurvival.dto.value.BlogClassId
import io.github.littlesurvival.dto.value.BlogId

object AppSyncCloudConfigDefaults {
    const val BLOG_NAME = "Yamibo App Sync Config - DO NOT EDIT - v1"
    const val BLOG_CLASS_NAME = "Yamibo App Sync"
    const val MARKER = "YAMIBO_APP_SYNC_CONFIG:v1"
    const val ENVELOPE_SCHEMA_VERSION = 1
}

data class AppSyncBlogConfig(
    val blogName: String = AppSyncCloudConfigDefaults.BLOG_NAME,
    val blogId: BlogId,
    val classId: BlogClassId? = null,
    val cloudContentUpdatedAtEpochMillis: Long? = null,
    val validatedAtEpochMillis: Long? = null,
    val schemaVersion: Int? = null,
    val fingerprint: String? = null,
)

data class AppSyncStagedCloudConfig(
    val config: AppSyncBlogConfig,
    val encodedSnapshot: String,
)

sealed interface AppSyncCloudResult<out T> {
    data class VerifiedSuccess<T>(
        val value: T,
        val notices: List<AppSyncCloudNotice> = emptyList(),
    ) : AppSyncCloudResult<T>

    data class AcknowledgedButUnverified(
        val messageText: String?,
        val reason: String,
        val candidateBlogId: BlogId? = null,
    ) : AppSyncCloudResult<Nothing>

    data object NotFound : AppSyncCloudResult<Nothing>
    data object NotLoggedIn : AppSyncCloudResult<Nothing>

    data class NoPermission(
        val reason: String,
    ) : AppSyncCloudResult<Nothing>

    data object Maintenance : AppSyncCloudResult<Nothing>

    data class FormExpired(
        val messageText: String?,
    ) : AppSyncCloudResult<Nothing>

    data class ValidationFailed(
        val reason: String,
        val markerPresent: Boolean,
    ) : AppSyncCloudResult<Nothing>

    data class Conflict(
        val reason: String,
        val candidateBlogIds: List<BlogId> = emptyList(),
    ) : AppSyncCloudResult<Nothing>

    data class HttpFailed(
        val statusCode: Int,
        val messageText: String?,
        val bodyPreview: String?,
    ) : AppSyncCloudResult<Nothing>

    data class NetworkFailed(
        val reason: String,
    ) : AppSyncCloudResult<Nothing>

    data class Timeout(
        val reason: String,
    ) : AppSyncCloudResult<Nothing>

    data class ParseFailed(
        val reason: String,
        val messageText: String? = null,
        val bodyPreview: String? = null,
    ) : AppSyncCloudResult<Nothing>

    data class UnknownFailed(
        val reason: String,
    ) : AppSyncCloudResult<Nothing>
}

sealed interface AppSyncCloudNotice {
    data class DuplicateValidBlogs(
        val selectedBlogId: BlogId,
        val candidateCount: Int,
    ) : AppSyncCloudNotice

    data class DamagedBlogDeleted(
        val blogId: BlogId,
    ) : AppSyncCloudNotice
}

fun interface AppSyncCloudNoticeSink {
    fun emit(notice: AppSyncCloudNotice)
}

object NoOpAppSyncCloudNoticeSink : AppSyncCloudNoticeSink {
    override fun emit(notice: AppSyncCloudNotice) = Unit
}

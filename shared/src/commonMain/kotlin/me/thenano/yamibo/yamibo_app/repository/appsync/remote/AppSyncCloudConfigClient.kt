package me.thenano.yamibo.yamibo_app.repository.appsync.remote

import io.github.littlesurvival.dto.value.BlogId
import io.github.littlesurvival.dto.value.FormHash
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncBlogConfig
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncCloudResult
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncStagedCloudConfig

interface AppSyncCloudConfigClient {
    suspend fun createBlogConfig(
        encodedText: String,
        formHash: FormHash,
    ): AppSyncCloudResult<AppSyncBlogConfig>

    suspend fun findBlogConfig(
        formHash: FormHash,
    ): AppSyncCloudResult<AppSyncBlogConfig>

    /**
     * Authoritative read-only discovery used before a larger cloud operation.
     *
     * It never saves or clears local metadata and never deletes damaged candidates.
     */
    suspend fun inspectBlogConfig(): AppSyncCloudResult<AppSyncBlogConfig>

    /**
     * Verifies and loads a candidate without persisting metadata or deleting remote candidates.
     */
    suspend fun loadBlogConfigReadOnly(blogId: BlogId): AppSyncCloudResult<AppSyncStagedCloudConfig>

    /** Persist an identity that was already verified by a staged read. */
    suspend fun commitVerifiedBlogConfig(config: AppSyncBlogConfig): AppSyncCloudResult<Unit>

    suspend fun loadBlogConfig(blogId: BlogId): AppSyncCloudResult<String>
    suspend fun updateBlogConfig(
        blogId: BlogId,
        encodedText: String,
        formHash: FormHash,
        expectedRemoteFingerprint: String? = null,
    ): AppSyncCloudResult<AppSyncBlogConfig>

    suspend fun deleteBlogConfig(
        blogId: BlogId,
        formHash: FormHash,
    ): AppSyncCloudResult<Unit>
}

package me.thenano.yamibo.yamibo_app.repository.appsync.remote

import io.github.littlesurvival.dto.page.BlogPage
import io.github.littlesurvival.dto.page.UserSpaceBlogPage
import io.github.littlesurvival.dto.value.BlogClassId
import io.github.littlesurvival.dto.value.BlogId
import io.github.littlesurvival.dto.value.FormHash
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncCloudResult

sealed interface AppSyncBlogClassSelection {
    data class Existing(
        val classId: BlogClassId,
    ) : AppSyncBlogClassSelection

    data class Create(
        val className: String,
    ) : AppSyncBlogClassSelection
}

data class AppSyncBlogWriteRequest(
    val blogId: BlogId?,
    val title: String,
    val message: String,
    val classSelection: AppSyncBlogClassSelection,
    val formHash: FormHash,
)

data class AppSyncBlogDeleteRequest(
    val blogId: BlogId,
    val formHash: FormHash,
)

data class AppSyncPostAcknowledgement(
    val messageText: String?,
    val candidateBlogIds: List<BlogId>,
)

interface AppSyncBlogProvider {
    suspend fun fetchMyBlogs(
        blogClassId: BlogClassId? = null,
        page: Int = 1,
    ): AppSyncCloudResult<UserSpaceBlogPage>

    suspend fun fetchBlog(blogId: BlogId): AppSyncCloudResult<BlogPage>

    suspend fun submitBlog(
        request: AppSyncBlogWriteRequest,
    ): AppSyncCloudResult<AppSyncPostAcknowledgement>

    suspend fun deleteBlog(
        request: AppSyncBlogDeleteRequest,
    ): AppSyncCloudResult<AppSyncPostAcknowledgement>
}

package me.thenano.yamibo.yamibo_app.repository.userspace

import io.github.littlesurvival.YamiboClient
import io.github.littlesurvival.core.YamiboResult
import io.github.littlesurvival.dto.page.BlogPage
import io.github.littlesurvival.dto.value.BlogId
import io.github.littlesurvival.dto.value.FormHash
import io.github.littlesurvival.dto.value.UserId
import kotlin.time.Duration.Companion.hours
import me.thenano.yamibo.yamibo_app.core.cache.DiskCacheFactory
import me.thenano.yamibo.yamibo_app.repository.BlogRepository
import me.thenano.yamibo.yamibo_app.store.auth.CookieStore

class BlogRepositoryImpl(
    private val cookieStore: CookieStore,
    private val yamiboClient: YamiboClient,
    diskCacheFactory: DiskCacheFactory
) : BlogRepository {
    private val blogPageCache = diskCacheFactory.create<BlogPage>("blog_page", maxSize = 40, expiration = 12.hours)

    override suspend fun fetchBlogPage(blogId: BlogId, userId: UserId?, page: Int): YamiboResult<BlogPage> {
        yamiboClient.setCookie(cookieStore.load() ?: "")
        return yamiboClient.fetchBlogPage(blogId, userId, page)
            .cacheSuccess(blogPageCache, BlogRepository.BlogPageCacheKey(blogId.value, userId?.value, page).toCacheKey())
    }

    override suspend fun postBlogComment(
        blogId: BlogId,
        userId: UserId,
        message: String,
        formHash: FormHash
    ): YamiboResult<String> {
        yamiboClient.setCookie(cookieStore.load() ?: "")
        return yamiboClient.fetchBlogComment(blogId, userId, message, formHash)
    }

    override fun getCachedBlogPage(blogId: BlogId, userId: UserId?, page: Int): BlogPage? =
        blogPageCache.get(BlogRepository.BlogPageCacheKey(blogId.value, userId?.value, page).toCacheKey())

    override fun clearCachedBlog(blogId: BlogId) {
        blogPageCache.removeByPrefix(BlogRepository.BlogPageCacheKey.keyPrefix(blogId.value))
    }
}

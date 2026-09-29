package me.thenano.yamibo.yamibo_app.repository

import io.github.littlesurvival.YamiboClient
import io.github.littlesurvival.core.YamiboResult
import io.github.littlesurvival.dto.model.Tags
import io.github.littlesurvival.dto.page.TagPage
import io.github.littlesurvival.dto.value.TagId
import io.github.littlesurvival.dto.value.ThreadId
import me.thenano.yamibo.yamibo_app.store.auth.CookieStore
import kotlin.time.Duration.Companion.hours

import me.thenano.yamibo.yamibo_app.core.cache.DiskCacheFactory

class DefaultTagRepository(
    private val cookieStore: CookieStore,
    private val yamiboClient: YamiboClient,
    diskCacheFactory: DiskCacheFactory
) : TagRepository {
    private val tagCache = diskCacheFactory.create<TagPage>("tag_page", maxSize = 10, expiration = 24.hours)

    override suspend fun fetchTagPage(tagId: TagId, page: Int): YamiboResult<TagPage> {
        yamiboClient.setCookie(cookieStore.load() ?: "")
        val result = yamiboClient.fetchTagPageById(tagId, page)
        if (result is YamiboResult.Success) {
            tagCache.set(TagRepository.TagCacheKey(tagId.value, page).toCacheKey(), result.value)
        }
        return result
    }

    override suspend fun fetchExtractTags(tid: ThreadId): YamiboResult<Tags> {
        yamiboClient.setCookie(cookieStore.load() ?: "")
        return yamiboClient.fetchExtractTagsInThreadById(tid)
    }

    override fun getCachedTagPage(tagId: TagId, page: Int): TagPage? =
        tagCache.get(TagRepository.TagCacheKey(tagId.value, page).toCacheKey())

    override fun setCachedTagPage(tagId: TagId, page: Int, tagPage: TagPage) {
        tagCache.set(TagRepository.TagCacheKey(tagId.value, page).toCacheKey(), tagPage)
    }

    override fun clearCachedTagPage(tagId: TagId) {
        tagCache.removeByPrefix(TagRepository.TagCacheKey.keyPrefix(tagId.value))
    }
}

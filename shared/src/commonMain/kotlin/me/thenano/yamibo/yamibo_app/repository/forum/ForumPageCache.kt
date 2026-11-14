package me.thenano.yamibo.yamibo_app.repository.forum

import io.github.littlesurvival.core.YamiboResult
import io.github.littlesurvival.core.flatMapSuccess
import io.github.littlesurvival.dto.page.ForumPage
import io.github.littlesurvival.dto.value.ForumId
import me.thenano.yamibo.yamibo_app.core.cache.DiskCache

internal fun DiskCache<ForumPage>.getValidForumPage(key: String, fid: ForumId): ForumPage? {
    val page = get(key) ?: return null
    if (page.isValidFor(fid)) return page
    remove(key)
    return null
}

internal fun DiskCache<ForumPage>.storeForumResult(
    key: String,
    fid: ForumId,
    result: YamiboResult<ForumPage>,
): YamiboResult<ForumPage> = result.flatMapSuccess { page ->
    if (!page.isValidFor(fid)) return@flatMapSuccess YamiboResult.Failure("版塊資料不完整，請重試")
    set(key, page)
    YamiboResult.Success(page)
}

private fun ForumPage.isValidFor(fid: ForumId): Boolean =
    forum.fid == fid && forum.name.isNotBlank()

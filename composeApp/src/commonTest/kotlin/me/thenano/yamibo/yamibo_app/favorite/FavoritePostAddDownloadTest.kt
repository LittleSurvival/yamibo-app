package me.thenano.yamibo.yamibo_app.favorite

import io.github.littlesurvival.dto.value.TagId
import io.github.littlesurvival.dto.value.ThreadId
import kotlin.test.Test
import kotlin.test.assertEquals
import me.thenano.yamibo.yamibo_app.repository.ReadHistoryRepository

class FavoritePostAddDownloadTest {
    @Test
    fun mapsEachFavoriteTargetToItsExistingDownloadSurface() {
        val thread = FavoriteTargetPayload.Thread(
            tid = ThreadId(1),
            title = "thread",
            threadType = ReadHistoryRepository.ThreadEntryType.Normal,
            authorId = null,
            coverUrl = null,
            lastUpdatedTime = null,
            forumId = null,
            forumName = null,
        )
        val tag = FavoriteTargetPayload.TagManga(TagId(2), "tag", null)
        val rss = FavoriteTargetPayload.RssSearch(3, "rss")

        assertEquals(FavoritePostAddDownloadSurface.ThreadReader, thread.postAddDownloadSurface())
        assertEquals(FavoritePostAddDownloadSurface.TagCatalog, tag.postAddDownloadSurface())
        assertEquals(FavoritePostAddDownloadSurface.RssCatalog, rss.postAddDownloadSurface())
    }
}

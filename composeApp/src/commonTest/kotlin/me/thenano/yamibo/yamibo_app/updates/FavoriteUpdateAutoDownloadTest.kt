package me.thenano.yamibo.yamibo_app.updates

import me.thenano.yamibo.yamibo_app.repository.FavoriteStoreRepository
import me.thenano.yamibo.yamibo_app.repository.FavoriteUpdateRepository
import me.thenano.yamibo.yamibo_app.repository.download.DownloadQueueEntry
import me.thenano.yamibo.yamibo_app.repository.download.DownloadStatus
import me.thenano.yamibo.yamibo_app.repository.download.RssMangaChapterDownloadKey
import me.thenano.yamibo.yamibo_app.repository.download.ThreadPageDownloadKey
import kotlin.test.Test
import kotlin.test.assertEquals

class FavoriteUpdateAutoDownloadTest {
    @Test
    fun threadTargetKeysExcludeTagAndRssEvents() {
        val targets = favoriteUpdateThreadTargetKeys(
            listOf(
                event(1, FavoriteStoreRepository.FavoriteTargetType.ThreadNormal, targetId = 10, authorId = null),
                event(2, FavoriteStoreRepository.FavoriteTargetType.ThreadNovel, targetId = 20, authorId = 3),
                event(3, FavoriteStoreRepository.FavoriteTargetType.TagManga, targetId = 30, authorId = null),
                event(4, FavoriteStoreRepository.FavoriteTargetType.RssSearch, targetId = 40, authorId = null),
            )
        )

        assertEquals(
            setOf(
                UpdateDownloadTargetKey(targetId = 10, authorId = null),
                UpdateDownloadTargetKey(targetId = 20, authorId = 3),
            ),
            targets,
        )
    }

    @Test
    fun autoRefreshEntriesAreScopedToMatchingUpdateAvailableThreadPages() {
        val entries = listOf(
            DownloadQueueEntry(ThreadPageDownloadKey(tid = 10, page = 1), "match page 1", DownloadStatus.UpdateAvailable),
            DownloadQueueEntry(ThreadPageDownloadKey(tid = 10, page = 2), "downloaded page", DownloadStatus.Downloaded),
            DownloadQueueEntry(ThreadPageDownloadKey(tid = 10, page = 1), "duplicate page 1", DownloadStatus.UpdateAvailable),
            DownloadQueueEntry(ThreadPageDownloadKey(tid = 20, page = 1, authorId = 3), "match novel", DownloadStatus.UpdateAvailable),
            DownloadQueueEntry(ThreadPageDownloadKey(tid = 20, page = 1), "wrong author", DownloadStatus.UpdateAvailable),
            DownloadQueueEntry(ThreadPageDownloadKey(tid = 99, page = 1), "wrong thread", DownloadStatus.UpdateAvailable),
            DownloadQueueEntry(RssMangaChapterDownloadKey(subscriptionId = 5, tid = 10), "rss", DownloadStatus.UpdateAvailable),
        )

        val scoped = favoriteUpdateAutoRefreshEntries(
            entries = entries,
            targetKeys = setOf(
                UpdateDownloadTargetKey(targetId = 10, authorId = null),
                UpdateDownloadTargetKey(targetId = 20, authorId = 3),
            ),
        )

        assertEquals(
            listOf(
                ThreadPageDownloadKey(tid = 10, page = 1),
                ThreadPageDownloadKey(tid = 20, page = 1, authorId = 3),
            ),
            scoped.map { it.second },
        )
    }

    private fun event(
        id: Long,
        targetType: FavoriteStoreRepository.FavoriteTargetType,
        targetId: Long,
        authorId: Long?,
    ) = FavoriteUpdateRepository.UpdateEvent(
        id = id,
        targetType = targetType,
        targetId = targetId,
        authorId = authorId,
        fid = null,
        forumName = null,
        title = "event-$id",
        latestPostTitle = null,
        mode = when (targetType) {
            FavoriteStoreRepository.FavoriteTargetType.ThreadNormal -> FavoriteUpdateRepository.TargetMode.NormalThread
            FavoriteStoreRepository.FavoriteTargetType.ThreadNovel -> FavoriteUpdateRepository.TargetMode.NovelThread
            FavoriteStoreRepository.FavoriteTargetType.TagManga -> FavoriteUpdateRepository.TargetMode.TagManga
            FavoriteStoreRepository.FavoriteTargetType.RssSearch -> FavoriteUpdateRepository.TargetMode.RssSearch
        },
        summary = "",
        detailIds = emptyList(),
        coverUrl = null,
        detectedAt = 0,
        readAt = null,
        dismissedAt = null,
        ambiguous = false,
    )
}

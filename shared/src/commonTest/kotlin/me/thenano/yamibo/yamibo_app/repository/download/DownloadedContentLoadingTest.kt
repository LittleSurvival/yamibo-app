package me.thenano.yamibo.yamibo_app.repository.download

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class DownloadedContentLoadingTest {
    private val completed = DownloadQueueEntry(
        key = ThreadPageDownloadKey(42, 1, 7),
        title = "Downloaded chapter",
        status = DownloadStatus.Downloaded,
        updatedAt = 10,
    )
    private val active = DownloadQueueEntry(
        key = TagMangaChapterDownloadKey(8, 50),
        title = "Active chapter",
        status = DownloadStatus.Downloading,
        updatedAt = 11,
    )

    @Test
    fun aThousandImageProgressUpdatesKeepTheSameContentRevision() {
        val expected = downloadedContentRevision(listOf(completed, active))
        repeat(1_000) { index ->
            assertEquals(expected, downloadedContentRevision(listOf(
                completed,
                active.copy(progressCurrent = index, progressTotal = 1_000, updatedAt = 12L + index),
            )))
        }
    }

    @Test
    fun queueStagePauseFailureAndRetryDoNotReloadUnchangedDownloads() {
        val expected = downloadedContentRevision(listOf(completed))
        for (status in listOf(DownloadStatus.Queued, DownloadStatus.Downloading, DownloadStatus.Paused, DownloadStatus.Failed)) {
            for (stage in DownloadStage.entries) {
                assertEquals(expected, downloadedContentRevision(listOf(completed, active.copy(status = status, stage = stage))))
            }
        }
    }

    @Test
    fun completingADownloadChangesTheRevision() {
        assertNotEquals(
            downloadedContentRevision(listOf(completed, active)),
            downloadedContentRevision(listOf(completed, active.copy(status = DownloadStatus.Downloaded))),
        )
    }

    @Test
    fun replacingDownloadedContentChangesTheRevisionEvenWithTheSameKey() {
        assertNotEquals(
            downloadedContentRevision(listOf(completed)),
            downloadedContentRevision(listOf(completed.copy(updatedAt = 20))),
        )
    }

    @Test
    fun deletingDownloadedContentChangesTheRevision() {
        assertNotEquals(downloadedContentRevision(listOf(completed)), downloadedContentRevision(emptyList()))
    }

    @Test
    fun updateAvailableContentRemainsInTheInventoryRevision() {
        assertEquals(
            downloadedContentRevision(listOf(completed)),
            downloadedContentRevision(listOf(completed.copy(status = DownloadStatus.UpdateAvailable))),
        )
    }

    @Test
    fun authorViewsAndQueueOrderingAreHandledIndependently() {
        val allAuthors = completed.copy(key = ThreadPageDownloadKey(42, 1))
        val expected = downloadedContentRevision(listOf(completed, allAuthors))
        assertEquals(2, expected.size)
        assertEquals(expected, downloadedContentRevision(listOf(allAuthors, completed)))
    }

    @Test
    fun summaryCountsAllDownloadTypesFromTheSameLoadedInventory() {
        val summary = summarizeDownloadedContent(listOf(
            group("thread", DownloadedContentGroupType.Thread, 2, 3, 30),
            group("tag", DownloadedContentGroupType.TagManga, 4, 5, 50),
            group("rss", DownloadedContentGroupType.RssManga, 6, 7, 70),
        ))
        assertEquals(DownloadedContentSummary(
            totalItems = 12,
            threadPages = 2,
            tagMangaChapters = 4,
            rssMangaChapters = 6,
            imageCount = 15,
            imageBytes = 150,
            threadImageBytes = 30,
            tagMangaImageBytes = 50,
            rssMangaImageBytes = 70,
        ), summary)
    }

    @Test
    fun emptyInventoryHasZeroSummary() {
        assertEquals(DownloadedContentSummary(), summarizeDownloadedContent(emptyList()))
    }

    private fun group(id: String, type: DownloadedContentGroupType, count: Int, images: Int, bytes: Long) =
        DownloadedContentGroup(id, id, type, count, images, bytes, emptyList())
}

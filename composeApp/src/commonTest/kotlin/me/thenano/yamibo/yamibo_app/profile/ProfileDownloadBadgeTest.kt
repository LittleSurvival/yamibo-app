package me.thenano.yamibo.yamibo_app.profile

import me.thenano.yamibo.yamibo_app.repository.download.DownloadQueueEntry
import me.thenano.yamibo.yamibo_app.repository.download.DownloadStatus
import me.thenano.yamibo.yamibo_app.repository.download.ThreadPageDownloadKey
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProfileDownloadBadgeTest {
    @Test
    fun showsBadgeForActiveAndActionableStatuses() {
        listOf(
            DownloadStatus.Queued,
            DownloadStatus.Downloading,
            DownloadStatus.Failed,
            DownloadStatus.UpdateAvailable,
        ).forEach { status ->
            assertTrue(shouldShowDownloadBadge(listOf(entry(status))), "Expected badge for $status")
        }
    }

    @Test
    fun hidesBadgeForInactiveStatuses() {
        listOf(
            DownloadStatus.NotDownloaded,
            DownloadStatus.Downloaded,
            DownloadStatus.Paused,
        ).forEach { status ->
            assertFalse(shouldShowDownloadBadge(listOf(entry(status))), "Expected no badge for $status")
        }
        assertFalse(shouldShowDownloadBadge(emptyList()))
    }

    private fun entry(status: DownloadStatus) = DownloadQueueEntry(
        key = ThreadPageDownloadKey(tid = 1, page = 1),
        title = "test",
        status = status,
    )
}
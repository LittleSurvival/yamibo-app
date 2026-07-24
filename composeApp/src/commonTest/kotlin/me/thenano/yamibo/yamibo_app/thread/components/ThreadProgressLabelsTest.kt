package me.thenano.yamibo.yamibo_app.thread.components

import me.thenano.yamibo.yamibo_app.repository.ChapterStateRepository
import me.thenano.yamibo.yamibo_app.repository.download.DownloadQueueEntry
import me.thenano.yamibo.yamibo_app.repository.download.DownloadStage
import me.thenano.yamibo.yamibo_app.repository.download.DownloadStatus
import me.thenano.yamibo.yamibo_app.repository.download.ThreadPageDownloadKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ThreadProgressLabelsTest {
    @Test
    fun chapterPageProgressLabelKeepsPageCountRules() {
        assertEquals(
            "3/5",
            chapterEntry(lastPageIndex = 2, totalPages = 5).chapterPageProgressLabel(),
        )
        assertNull(chapterEntry(read = true, lastPageIndex = 2, totalPages = 5).chapterPageProgressLabel())
        assertNull(chapterEntry(lastPageIndex = null, totalPages = 5).chapterPageProgressLabel())
        assertNull(chapterEntry(lastPageIndex = 2, totalPages = 0).chapterPageProgressLabel())
    }

    @Test
    fun chapterPercentProgressLabelKeepsReadPercentRules() {
        assertEquals(
            "已讀 42%",
            chapterEntry(progressPercent = 42).chapterPercentProgressLabel { "已讀 $it%" },
        )
        assertNull(chapterEntry(progressPercent = 0).chapterPercentProgressLabel())
        assertNull(chapterEntry(read = true, progressPercent = 42).chapterPercentProgressLabel())
    }

    @Test
    fun catalogDownloadLabelKeepsStatusAndStageText() {
        assertEquals("等待中", downloadEntry(DownloadStatus.Queued).catalogLabel())
        assertEquals("已下載", downloadEntry(DownloadStatus.Downloaded).catalogLabel())
        assertEquals("下載失敗", downloadEntry(DownloadStatus.Failed).catalogLabel())
        assertEquals("已暫停", downloadEntry(DownloadStatus.Paused).catalogLabel())
        assertEquals("可刷新", downloadEntry(DownloadStatus.UpdateAvailable).catalogLabel())
        assertEquals("未下載", downloadEntry(DownloadStatus.NotDownloaded).catalogLabel())
        assertEquals("準備中", downloadingEntry(DownloadStage.Preparing).catalogLabel())
        assertEquals("正在取得內容", downloadingEntry(DownloadStage.FetchingContent).catalogLabel())
        assertEquals("下載文字", downloadingEntry(DownloadStage.DownloadingText).catalogLabel())
        assertEquals(
            "正在取得內容",
            catalogDownloadLabel(
                entry = downloadingEntry(DownloadStage.DownloadingText),
                labels = testDownloadLabels.copy(downloadingText = "正在取得內容"),
            ),
        )
        assertEquals("下載圖片", downloadingEntry(DownloadStage.DownloadingImages).catalogLabel())
        assertEquals(
            "下載圖片 3/5",
            downloadingEntry(
                stage = DownloadStage.DownloadingImages,
                progressCurrent = 3,
                progressTotal = 5,
            ).catalogLabel(),
        )
        assertEquals("儲存中", downloadingEntry(DownloadStage.Saving).catalogLabel())
        assertEquals("未下載", downloadEntry(DownloadStatus.Downloading).catalogLabel())
    }

    private fun DownloadQueueEntry.catalogLabel(): String = catalogDownloadLabel(this, testDownloadLabels)

    private val testDownloadLabels = CatalogDownloadLabels(
        preparing = "準備中",
        fetchingContent = "正在取得內容",
        downloadingText = "下載文字",
        downloadingImages = "下載圖片",
        saving = "儲存中",
        downloading = "下載中",
        queued = "等待中",
        downloaded = "已下載",
        failed = "下載失敗",
        paused = "已暫停",
        updateAvailable = "可刷新",
        notDownloaded = "未下載",
        downloadingImagesProgress = { current, total -> "下載圖片 $current/$total" },
    )

    private fun chapterEntry(
        read: Boolean = false,
        progressPercent: Int = 0,
        lastPageIndex: Int? = null,
        totalPages: Int? = null,
    ) = ChapterStateRepository.Entry(
        targetType = ChapterStateRepository.TargetType.ThreadPost,
        parentId = 1,
        targetId = 2,
        title = "title",
        read = read,
        progressPercent = progressPercent,
        lastPageIndex = lastPageIndex,
        totalPages = totalPages,
        updatedAt = 0,
    )

    private fun downloadingEntry(
        stage: DownloadStage,
        progressCurrent: Int = 0,
        progressTotal: Int = 0,
    ) = downloadEntry(
        status = DownloadStatus.Downloading,
        stage = stage,
        progressCurrent = progressCurrent,
        progressTotal = progressTotal,
    )

    private fun downloadEntry(
        status: DownloadStatus,
        stage: DownloadStage? = null,
        progressCurrent: Int = 0,
        progressTotal: Int = 0,
    ) = DownloadQueueEntry(
        key = ThreadPageDownloadKey(tid = 1, page = 1),
        title = "title",
        status = status,
        progressCurrent = progressCurrent,
        progressTotal = progressTotal,
        stage = stage,
    )
}

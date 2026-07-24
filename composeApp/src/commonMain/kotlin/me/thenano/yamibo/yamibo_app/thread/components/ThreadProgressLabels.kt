package me.thenano.yamibo.yamibo_app.thread.components

import me.thenano.yamibo.yamibo_app.i18n.i18n
import me.thenano.yamibo.yamibo_app.repository.ChapterStateRepository
import me.thenano.yamibo.yamibo_app.repository.download.DownloadQueueEntry
import me.thenano.yamibo.yamibo_app.repository.download.DownloadStage
import me.thenano.yamibo.yamibo_app.repository.download.DownloadStatus

internal fun ChapterStateRepository.Entry.chapterPageProgressLabel(): String? {
    if (read) return null
    val currentPage = lastPageIndex?.plus(1) ?: return null
    val totalPage = totalPages?.takeIf { it > 0 } ?: return null
    return "$currentPage/$totalPage"
}

internal fun ChapterStateRepository.Entry.chapterPercentProgressLabel(): String? {
    return chapterPercentProgressLabel { percent -> i18n("已讀 {}%", percent) }
}

internal fun ChapterStateRepository.Entry.chapterPercentProgressLabel(
    formatPercent: (Int) -> String,
): String? {
    if (read || progressPercent <= 0) return null
    return formatPercent(progressPercent)
}

internal data class CatalogDownloadLabels(
    val preparing: String,
    val fetchingContent: String,
    val downloadingText: String,
    val downloadingImages: String,
    val saving: String,
    val downloading: String,
    val queued: String,
    val downloaded: String,
    val failed: String,
    val paused: String,
    val updateAvailable: String,
    val notDownloaded: String,
    val downloadingImagesProgress: (Int, Int) -> String,
)

internal fun catalogDownloadLabels(
    downloadingTextLabel: String = i18n("下載文字"),
): CatalogDownloadLabels = CatalogDownloadLabels(
    preparing = i18n("準備中"),
    fetchingContent = i18n("正在取得內容"),
    downloadingText = downloadingTextLabel,
    downloadingImages = i18n("下載圖片"),
    saving = i18n("儲存中"),
    downloading = i18n("下載中"),
    queued = i18n("等待中"),
    downloaded = i18n("已下載"),
    failed = i18n("下載失敗"),
    paused = i18n("已暫停"),
    updateAvailable = i18n("可刷新"),
    notDownloaded = i18n("未下載"),
    downloadingImagesProgress = { current, total -> i18n("下載圖片 {}/{}", current, total) },
)

internal fun catalogDownloadLabel(
    entry: DownloadQueueEntry,
    labels: CatalogDownloadLabels = catalogDownloadLabels(),
): String = when (entry.status) {
    DownloadStatus.Downloading if entry.stage != null -> when (entry.stage) {
        DownloadStage.Preparing -> labels.preparing
        DownloadStage.FetchingContent -> labels.fetchingContent
        DownloadStage.DownloadingText -> labels.downloadingText
        DownloadStage.DownloadingImages -> if (entry.progressTotal > 0) {
            labels.downloadingImagesProgress(entry.progressCurrent, entry.progressTotal)
        } else {
            labels.downloadingImages
        }

        DownloadStage.Saving -> labels.saving
        null -> labels.downloading
    }
    DownloadStatus.Queued -> labels.queued
    DownloadStatus.Downloaded -> labels.downloaded
    DownloadStatus.Failed -> labels.failed
    DownloadStatus.Paused -> labels.paused
    DownloadStatus.UpdateAvailable -> labels.updateAvailable
    else -> labels.notDownloaded
}

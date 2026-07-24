package me.thenano.yamibo.yamibo_app.thread.detail.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.littlesurvival.dto.model.ThreadSummary
import io.github.littlesurvival.dto.page.TagPage
import me.thenano.yamibo.yamibo_app.forum.components.PageNavigation
import me.thenano.yamibo.yamibo_app.i18n.i18n
import me.thenano.yamibo.yamibo_app.repository.ChapterStateRepository
import me.thenano.yamibo.yamibo_app.repository.download.DownloadQueueEntry
import me.thenano.yamibo.yamibo_app.thread.components.chapterPageProgressLabel

/** Tag Detail Content (scrollable body) */
@Composable
fun CatalogDetailContent(
    tagPage: TagPage,
    tagName: String,
    displayTitle: String = "#$tagName",
    badgeText: String = i18n("#標籤漫畫"),
    coverUrl: String?,
    isMangaMode: Boolean,
    onMangaModeChange: (Boolean) -> Unit,
    dynamicCoverEnabled: Boolean,
    onDynamicCoverEnabledChange: (Boolean) -> Unit,
    longStripModeEnabled: Boolean,
    onLongStripModeEnabledChange: (Boolean) -> Unit,
    hasReadingHistory: Boolean,
    readingProgressText: String?,
    onContinueRead: () -> Unit,
    isFavorited: Boolean,
    onFavorite: () -> Unit,
    onFavoriteLongPress: (() -> Unit)? = null,
    onShare: () -> Unit,
    showDownloadAction: Boolean = false,
    onDownload: () -> Unit = {},
    noteContent: String,
    onNoteClick: () -> Unit,
    onPageChange: (Int) -> Unit,
    onThreadClick: (ThreadSummary) -> Unit,
    bookmarkedThreadIds: Set<Long> = emptySet(),
    readThreadIds: Set<Long> = emptySet(),
    downloadEntries: Map<Long, DownloadQueueEntry> = emptyMap(),
    chapterStates: Map<Long, ChapterStateRepository.Entry> = emptyMap(),
    historyThreadId: Long? = null,
    historyThreadCompleted: Boolean = false,
    historyThreadProgressText: String? = null,
    onThreadLongPress: (ThreadSummary) -> Unit = {},
) {
    val listState = rememberLazyListState()
    val currentPage = tagPage.pageNav?.currentPage

    LaunchedEffect(currentPage) {
        if (currentPage != null) {
            listState.scrollToItem(0)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        // Header card
        item {
            CatalogDetailHeaderCard(
                tagName = tagName,
                displayTitle = displayTitle,
                badgeText = badgeText,
                coverUrl = coverUrl,
                isMangaMode = isMangaMode,
                onMangaModeChange = onMangaModeChange,
                dynamicCoverEnabled = dynamicCoverEnabled,
                onDynamicCoverEnabledChange = onDynamicCoverEnabledChange,
                longStripModeEnabled = longStripModeEnabled,
                onLongStripModeEnabledChange = onLongStripModeEnabledChange,
                hasReadingHistory = hasReadingHistory,
                readingProgressText = readingProgressText,
                onContinueRead = onContinueRead,
                isFavorited = isFavorited,
                onFavorite = onFavorite,
                onFavoriteLongPress = onFavoriteLongPress,
                onShare = onShare,
                showDownloadAction = showDownloadAction,
                onDownload = onDownload,
                noteContent = noteContent,
                onNoteClick = onNoteClick,
            )
        }

        if (noteContent.isNotBlank()) {
            item {
                DetailNoteCard(
                    content = noteContent,
                    onEdit = onNoteClick,
                )
            }
        }

        // top page nav
        if (tagPage.pageNav != null) {
            item {
                PageNavigation(
                    pageNav = tagPage.pageNav!!,
                    onPageChange = onPageChange
                )
            }
        }

        // Thread list
        items(tagPage.threadSummaries, key = { it.tid.value }) { thread ->
            val threadId = thread.tid.value.toLong()
            val chapterState = chapterStates[threadId]
            val read = threadId in readThreadIds ||
                chapterState?.read == true ||
                (historyThreadId == threadId && historyThreadCompleted)
            val threadProgressText = if (isMangaMode) {
                when {
                    read -> null
                    chapterState?.hasProgress == true -> chapterState.chapterPageProgressLabel()
                    historyThreadId == threadId -> historyThreadProgressText
                    else -> null
                }
            } else {
                null
            }
            CatalogThreadCard(
                thread = thread,
                onClick = { onThreadClick(thread) },
                bookmarked = threadId in bookmarkedThreadIds,
                read = read,
                readingProgressText = threadProgressText,
                downloadEntry = downloadEntries[threadId],
                onLongPress = { onThreadLongPress(thread) },
            )
        }

        // bottom page nav
        if (tagPage.pageNav != null) {
            item {
                PageNavigation(
                    pageNav = tagPage.pageNav!!,
                    onPageChange = onPageChange
                )
            }
        }
    }
}

fun catalogHistoryProgressLabel(
    pageIndex: Int,
    totalPages: Int,
): String? {
    if (totalPages <= 0) return null
    val clampedPageIndex = pageIndex.coerceIn(0, totalPages - 1)
    val progress = (((clampedPageIndex + 1) * 100f) / totalPages)
        .toInt()
        .coerceIn(1, 100)
    if (progress >= 100) return null
    return "${clampedPageIndex + 1}/$totalPages"
}

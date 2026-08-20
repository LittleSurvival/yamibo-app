package me.thenano.yamibo.yamibo_app.thread.detail.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.littlesurvival.YamiboForum
import io.github.littlesurvival.dto.model.ThreadSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme
import me.thenano.yamibo.yamibo_app.favorite.FavoriteLocationSelection
import me.thenano.yamibo.yamibo_app.favorite.FavoriteMultiPathRemoveDialog
import me.thenano.yamibo.yamibo_app.favorite.FavoriteRemovalConfirmDialog
import me.thenano.yamibo.yamibo_app.i18n.i18n
import me.thenano.yamibo.yamibo_app.repository.download.DownloadStatus

internal data class CatalogDownloadAction(
    val label: String,
    val onClick: () -> Unit,
)

@Composable
internal fun CatalogActionRow(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = YamiboTheme.colors
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = colors.creamBackground,
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            color = colors.textDark,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CatalogDownloadActionSheet(
    title: String,
    actions: List<CatalogDownloadAction>,
    onDismiss: () -> Unit,
    doNotAskAgain: Boolean? = null,
    onDoNotAskAgainChange: ((Boolean) -> Unit)? = null,
) {
    val colors = YamiboTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.creamSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, color = colors.textStrong, style = MaterialTheme.typography.titleMedium)
            actions.forEach { action ->
                CatalogActionRow(action.label, action.onClick)
            }
            if (doNotAskAgain != null && onDoNotAskAgainChange != null) {
                DownloadPromptSuppressionRow(doNotAskAgain, onDoNotAskAgainChange)
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
internal fun CatalogThreadActionDialog(
    bookmarked: Boolean,
    read: Boolean,
    hasProgress: Boolean,
    showDownloadActions: Boolean,
    downloadStatus: DownloadStatus?,
    onDismiss: () -> Unit,
    onToggleBookMark: () -> Unit,
    onMarkRead: () -> Unit,
    onMarkUnread: () -> Unit,
    onClearHistory: () -> Unit,
    onDownloadChapter: () -> Unit,
    onRefreshChapter: () -> Unit,
    onRetryChapter: () -> Unit,
    onClearChapterDownload: () -> Unit,
) {
    val colors = YamiboTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(i18n("閱讀標記"), color = colors.textStrong) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CatalogActionRow(if (bookmarked) i18n("移除書籤") else i18n("新增書籤"), onToggleBookMark)
                if (read) {
                    CatalogActionRow(i18n("標為未讀"), onMarkUnread)
                } else if (hasProgress) {
                    CatalogActionRow(i18n("標為已讀"), onMarkRead)
                    CatalogActionRow(i18n("標為未讀"), onMarkUnread)
                } else {
                    CatalogActionRow(i18n("標為已讀"), onMarkRead)
                }
                CatalogActionRow(i18n("清除閱讀紀錄"), onClearHistory)
                if (showDownloadActions) {
                    when (downloadStatus) {
                        DownloadStatus.Failed -> {
                            CatalogActionRow(i18n("重試下載"), onRetryChapter)
                            CatalogActionRow(i18n("清除此章下載"), onClearChapterDownload)
                        }
                        DownloadStatus.Downloaded, DownloadStatus.UpdateAvailable -> {
                            CatalogActionRow(i18n("重新整理此章"), onRefreshChapter)
                            CatalogActionRow(i18n("清除此章下載"), onClearChapterDownload)
                        }
                        DownloadStatus.Queued, DownloadStatus.Downloading, DownloadStatus.Paused -> {
                            CatalogActionRow(i18n("清除此章下載"), onClearChapterDownload)
                        }
                        DownloadStatus.NotDownloaded, null -> {
                            CatalogActionRow(i18n("下載此章"), onDownloadChapter)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(i18n("取消"), color = colors.textStrong)
            }
        },
        containerColor = colors.creamSurface,
    )
}

@Composable
internal fun CatalogFavoriteMultiPathRemoveDialog(
    paths: List<String>,
    onDismiss: () -> Unit,
    onRemoveAll: () -> Unit,
) {
    FavoriteMultiPathRemoveDialog(
        paths = paths,
        tip = i18n("tip：長按可詳細編輯收藏路徑"),
        onDismiss = onDismiss,
        onRemoveAll = onRemoveAll,
    )
}

@Composable
internal fun CatalogFavoriteRemovalDialogs(
    showRemovalConfirm: Boolean,
    showMultiPathDialog: Boolean,
    pendingSelection: FavoriteLocationSelection?,
    feedbackController: me.thenano.yamibo.yamibo_app.feedback.AppFeedbackController,
    setShowRemovalConfirm: (Boolean) -> Unit,
    setShowMultiPathDialog: (Boolean) -> Unit,
    clearPendingSelection: () -> Unit,
    setSkipNextTime: (Boolean) -> Unit,
    removeFavorite: suspend () -> Unit,
    onRemoved: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    if (showRemovalConfirm) {
        FavoriteRemovalConfirmDialog(
            onDismiss = {
                setShowRemovalConfirm(false)
                clearPendingSelection()
            },
            onConfirm = { skipNextTime ->
                setSkipNextTime(skipNextTime)
                setShowRemovalConfirm(false)
                scope.launch {
                    if ((pendingSelection?.paths?.size ?: 0) > 1) {
                        setShowMultiPathDialog(true)
                    } else {
                        withContext(Dispatchers.Default) { removeFavorite() }
                        onRemoved()
                        feedbackController.post(i18n("已移除收藏"))
                        clearPendingSelection()
                    }
                }
            },
        )
    }

    if (showMultiPathDialog) {
        CatalogFavoriteMultiPathRemoveDialog(
            paths = pendingSelection?.paths.orEmpty(),
            onDismiss = {
                setShowMultiPathDialog(false)
                clearPendingSelection()
            },
            onRemoveAll = {
                setShowMultiPathDialog(false)
                scope.launch {
                    withContext(Dispatchers.Default) { removeFavorite() }
                    onRemoved()
                    feedbackController.post(i18n("已從所有位置移除收藏"))
                    clearPendingSelection()
                }
            },
        )
    }
}

internal fun isCatalogMangaThread(thread: ThreadSummary): Boolean =
    thread.fid?.let { YamiboForum.isMangaForum(it) } == true ||
        thread.tag?.let { YamiboForum.isMangaForum(it) } == true

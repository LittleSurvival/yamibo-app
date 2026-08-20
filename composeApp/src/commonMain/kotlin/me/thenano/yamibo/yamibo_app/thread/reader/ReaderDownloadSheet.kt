package me.thenano.yamibo.yamibo_app.thread.reader

import YamiboIcons
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme
import me.thenano.yamibo.yamibo_app.i18n.i18n
import me.thenano.yamibo.yamibo_app.thread.detail.components.DownloadPromptSuppressionRow

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun ReaderDownloadSheet(
    onDismiss: () -> Unit,
    showDownloadPage: Boolean,
    showDownloadThread: Boolean,
    showDownloadThreadExceptLastPage: Boolean,
    showClearPage: Boolean,
    showClearThread: Boolean,
    onDownloadPage: () -> Unit,
    onDownloadThread: () -> Unit,
    onDownloadThreadExceptLastPage: () -> Unit,
    onClearPage: () -> Unit,
    onClearThread: () -> Unit,
    doNotAskAgain: Boolean? = null,
    onDoNotAskAgainChange: ((Boolean) -> Unit)? = null,
) {
    val colors = YamiboTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.creamSurface,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(i18n("下載"), color = colors.textStrong, fontSize = 22.sp)
            Text(
                text = i18n("下載會保存整頁帖子與原始圖片。"),
                color = colors.textDark.copy(alpha = 0.68f),
                fontSize = 13.sp,
            )
            if (showDownloadPage) {
                DownloadSheetAction(i18n("下載目前頁"), i18n("保存此頁所有帖子與圖片"), false, onDownloadPage)
            }
            if (showDownloadThread) {
                DownloadSheetAction(i18n("下載完整 Thread"), i18n("將全部頁面加入背景佇列"), false, onDownloadThread)
            }
            if (showDownloadThreadExceptLastPage) {
                DownloadSheetAction(
                    i18n("下載除最後一頁的所有頁面"),
                    i18n("保留可能持續更新的最後一頁在線閱讀"),
                    false,
                    onDownloadThreadExceptLastPage,
                )
            }
            if (showClearPage) {
                DownloadSheetAction(i18n("清除目前頁下載"), i18n("只刪除此頁離線內容"), true, onClearPage)
            }
            if (showClearThread) {
                DownloadSheetAction(
                    i18n("清除整個 Thread 下載"),
                    i18n("取消佇列並刪除所有已下載頁"),
                    true,
                    onClearThread,
                )
            }
            if (doNotAskAgain != null && onDoNotAskAgainChange != null) {
                DownloadPromptSuppressionRow(doNotAskAgain, onDoNotAskAgainChange)
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text(i18n("關閉"), color = colors.brownPrimary)
            }
        }
    }
}

@Composable
private fun DownloadSheetAction(
    title: String,
    subtitle: String,
    destructive: Boolean,
    onClick: () -> Unit,
) {
    val colors = YamiboTheme.colors
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = colors.creamBackground),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = YamiboIcons.Download,
                contentDescription = null,
                tint = if (destructive) colors.redAccent else colors.orangeAccent,
                modifier = Modifier.size(22.dp),
            )
            Column(Modifier.padding(start = 12.dp)) {
                Text(
                    text = title,
                    color = if (destructive) colors.redAccent else colors.textStrong,
                    fontSize = 15.sp,
                )
                Text(
                    text = subtitle,
                    color = colors.textDark.copy(alpha = 0.65f),
                    fontSize = 12.sp,
                )
            }
        }
    }
}

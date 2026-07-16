package me.thenano.yamibo.yamibo_app.favorite.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.thenano.yamibo.yamibo_app.components.controls.YamiboMultiSelectDialog
import me.thenano.yamibo.yamibo_app.components.controls.YamiboSingleSelectDialog
import me.thenano.yamibo.yamibo_app.favorite.FavoriteBatchDownloadMode
import me.thenano.yamibo.yamibo_app.favorite.FavoriteBatchDownloadScope
import me.thenano.yamibo.yamibo_app.favorite.FavoriteBatchDownloadType
import me.thenano.yamibo.yamibo_app.favorite.FavoriteCollectionDraft
import me.thenano.yamibo.yamibo_app.favorite.batchDownloadType
import me.thenano.yamibo.yamibo_app.favorite.countByType
import me.thenano.yamibo.yamibo_app.favorite.coerceFor
import me.thenano.yamibo.yamibo_app.favorite.supportsExceptLastPageDownload
import me.thenano.yamibo.yamibo_app.i18n.i18n
import me.thenano.yamibo.yamibo_app.i18n.localizedLabel
import me.thenano.yamibo.yamibo_app.repository.settings.FavoriteGridMode
import me.thenano.yamibo.yamibo_app.repository.settings.FavoriteSortMode
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme

@Composable
internal fun FavoriteSortDialog(selected: FavoriteSortMode, descending: Boolean, onDismiss: () -> Unit, onSelect: (FavoriteSortMode) -> Unit, onConfirm: () -> Unit) {
    YamiboSingleSelectDialog(
        title = i18n("排序"),
        options = FavoriteSortMode.entries,
        selected = selected,
        onDismiss = onDismiss,
        onSelect = onSelect,
        label = { it.localizedLabel() },
        selectedText = if (descending) "↓" else "↑",
        footer = { ActionChip(i18n("確定"), onConfirm) },
    )
}

@Composable
internal fun FavoriteGridModeDialog(selected: FavoriteGridMode, onDismiss: () -> Unit, onSelect: (FavoriteGridMode) -> Unit) {
    YamiboSingleSelectDialog(
        title = i18n("排列方式"),
        options = FavoriteGridMode.entries,
        selected = selected,
        onDismiss = onDismiss,
        onSelect = onSelect,
        label = { it.localizedLabel() },
        dismissOnSelect = true,
    )
}

@Composable
internal fun FavoriteBatchDownloadTypeDialog(
    scope: FavoriteBatchDownloadScope,
    selectedTypes: Set<FavoriteBatchDownloadType>,
    onDismiss: () -> Unit,
    onNext: (Set<FavoriteBatchDownloadType>) -> Unit,
) {
    val counts = scope.countByType()
    YamiboMultiSelectDialog(
        title = i18n("批量下載收藏"),
        options = FavoriteBatchDownloadType.entries,
        selected = selectedTypes,
        onConfirm = { confirmed ->
            val nextSelection = confirmed.filterTo(mutableSetOf()) { (counts[it] ?: 0) > 0 }
            onNext(nextSelection)
        },
        onCancel = onDismiss,
        label = { type -> i18n("{} ({})", type.localizedBatchLabel(), counts[type] ?: 0) },
        confirmText = i18n("下一步"),
        optionEnabled = { type -> (counts[type] ?: 0) > 0 },
    )
}

@Composable
internal fun FavoriteBatchDownloadModeDialog(
    scope: FavoriteBatchDownloadScope,
    selectedTypes: Set<FavoriteBatchDownloadType>,
    selectedMode: FavoriteBatchDownloadMode,
    onSelectMode: (FavoriteBatchDownloadMode) -> Unit,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val selectedItemCount = scope.items.count { it.batchDownloadType() in selectedTypes }
    val modeOptions = if (scope.supportsExceptLastPageDownload(selectedTypes)) {
        FavoriteBatchDownloadMode.entries
    } else {
        listOf(FavoriteBatchDownloadMode.All)
    }
    val selectedAvailableMode = selectedMode.coerceFor(scope, selectedTypes)
    YamiboSingleSelectDialog(
        title = i18n("下載方式"),
        options = modeOptions,
        selected = selectedAvailableMode,
        onDismiss = onDismiss,
        onSelect = onSelectMode,
        label = { mode ->
            "${mode.localizedBatchModeLabel()} · ${i18n("本次將處理 {} 項收藏。", selectedItemCount)}"
        },
        footer = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ActionChip(i18n("上一步"), onBack)
                ActionChip(i18n("取消"), onDismiss)
                ActionChip(i18n("開始下載"), onConfirm)
            }
        },
    )
}

@Composable
private fun FavoriteBatchSummary(scope: FavoriteBatchDownloadScope) {
    val colors = YamiboTheme.colors
    Text(
        text = i18n(
            "已選 {} 項，包含 {} 個集合；集合展開 {} 項，去重後 {} 項。",
            scope.directItemCount,
            scope.selectedCollectionCount,
            scope.expandedCollectionItemCount,
            scope.totalItemCount,
        ),
        color = colors.textDark.copy(alpha = 0.72f),
        fontSize = 13.sp,
        lineHeight = 18.sp,
    )
}

internal fun FavoriteBatchDownloadType.localizedBatchLabel(): String = when (this) {
    FavoriteBatchDownloadType.NovelThread -> i18n("小說帖子")
    FavoriteBatchDownloadType.NormalThread -> i18n("一般帖子")
    FavoriteBatchDownloadType.TagManga -> i18n("標籤漫畫")
    FavoriteBatchDownloadType.RssSearch -> i18n("RSS收藏")
}

private fun FavoriteBatchDownloadMode.localizedBatchModeLabel(): String = when (this) {
    FavoriteBatchDownloadMode.All -> i18n("全部下載")
    FavoriteBatchDownloadMode.ExceptLastPage -> i18n("下載除最後一頁以外的全部頁")
}

@Composable
internal fun CollectionEditorDialog(draft: FavoriteCollectionDraft, onDismiss: () -> Unit, onConfirm: (String, String, Boolean) -> Unit) {
    val colors = YamiboTheme.colors
    var name by remember(draft.collectionId, draft.initialName) { mutableStateOf(draft.initialName) }
    var colorKey by remember(draft.collectionId, draft.initialColorKey) { mutableStateOf(draft.initialColorKey) }
    var removeOriginal by remember(draft.collectionId, draft.removeOriginalItems) { mutableStateOf(draft.removeOriginalItems) }
    val palette = listOf("brown", "rose", "blue", "green", "gold")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(draft.title, color = colors.textStrong, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    label = { Text(i18n("名稱")) },
                    colors = favoriteDialogTextFieldColors(),
                )
                Text(i18n("顏色"), color = colors.textDark, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    palette.forEach { paletteKey ->
                        Box(Modifier.size(26.dp).clip(CircleShape).background(collectionColor(paletteKey)).pointerInput(paletteKey) { detectTapGestures(onTap = { colorKey = paletteKey }) }.border(if (paletteKey == colorKey) 2.dp else 0.dp, colors.brownDeep, CircleShape))
                    }
                }
                if (draft.showRemoveOriginalOption) {
                    Row(Modifier.fillMaxWidth().pointerInput(removeOriginal) { detectTapGestures(onTap = { removeOriginal = !removeOriginal }) }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = removeOriginal,
                            onCheckedChange = { removeOriginal = it },
                            colors = CheckboxDefaults.colors(
                                checkedColor = colors.brownDeep,
                                uncheckedColor = colors.brownPrimary.copy(alpha = 0.65f),
                                checkmarkColor = colors.creamBackground,
                            ),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(i18n("移除原始條目"), color = colors.textDark, fontSize = 13.sp)
                    }
                }
            }
        },
        confirmButton = { ActionChip(i18n("確定")) { if (name.isNotBlank()) onConfirm(name.trim(), colorKey, removeOriginal) } },
        dismissButton = { ActionChip(i18n("返回"), onDismiss) },
        containerColor = colors.creamSurface,
    )
}

@Composable
private fun favoriteDialogTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = YamiboTheme.colors.textDark,
    unfocusedTextColor = YamiboTheme.colors.textDark,
    focusedLabelColor = YamiboTheme.colors.brownDeep,
    unfocusedLabelColor = YamiboTheme.colors.textDark.copy(alpha = 0.58f),
    cursorColor = YamiboTheme.colors.brownDeep,
    focusedBorderColor = YamiboTheme.colors.brownDeep,
    unfocusedBorderColor = YamiboTheme.colors.brownPrimary.copy(alpha = 0.35f),
    focusedContainerColor = YamiboTheme.colors.creamSurface,
    unfocusedContainerColor = YamiboTheme.colors.creamSurface,
)

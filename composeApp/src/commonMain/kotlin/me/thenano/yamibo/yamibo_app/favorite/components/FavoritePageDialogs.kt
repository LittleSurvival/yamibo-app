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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
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
import me.thenano.yamibo.yamibo_app.components.controls.YamiboSingleSelectDialog
import me.thenano.yamibo.yamibo_app.favorite.FavoriteBatchDownloadMode
import me.thenano.yamibo.yamibo_app.favorite.FavoriteBatchDownloadScope
import me.thenano.yamibo.yamibo_app.favorite.FavoriteBatchDownloadType
import me.thenano.yamibo.yamibo_app.favorite.FavoriteCollectionDraft
import me.thenano.yamibo.yamibo_app.favorite.batchDownloadType
import me.thenano.yamibo.yamibo_app.favorite.countByType
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
    onToggle: (FavoriteBatchDownloadType) -> Unit,
    onDismiss: () -> Unit,
    onNext: () -> Unit,
) {
    val colors = YamiboTheme.colors
    val counts = scope.countByType()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(i18n("批量下載收藏"), color = colors.textStrong, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FavoriteBatchSummary(scope)
                FavoriteBatchDownloadType.entries.forEach { type ->
                    val count = counts[type] ?: 0
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .pointerInput(type, count) {
                                detectTapGestures(onTap = { if (count > 0) onToggle(type) })
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = type in selectedTypes,
                            enabled = count > 0,
                            onCheckedChange = { onToggle(type) },
                            colors = CheckboxDefaults.colors(
                                checkedColor = colors.brownDeep,
                                uncheckedColor = colors.brownPrimary.copy(alpha = 0.65f),
                                checkmarkColor = colors.creamBackground,
                            ),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "${type.localizedBatchLabel()} · ${i18n("{} 項", count)}",
                            color = if (count > 0) colors.textDark else colors.textDark.copy(alpha = 0.42f),
                            fontSize = 14.sp,
                            fontWeight = if (type in selectedTypes) FontWeight.SemiBold else FontWeight.Medium,
                        )
                    }
                }
            }
        },
        confirmButton = { ActionChip(i18n("下一步"), onNext) },
        dismissButton = { ActionChip(i18n("返回"), onDismiss) },
        containerColor = colors.creamSurface,
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
    val colors = YamiboTheme.colors
    val selectedItemCount = scope.items.count { it.batchDownloadType() in selectedTypes }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(i18n("下載方式"), color = colors.textStrong, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FavoriteBatchSummary(scope)
                Text(i18n("本次將處理 {} 項收藏。", selectedItemCount), color = colors.textDark, fontSize = 13.sp)
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInput(selectedMode) {
                            detectTapGestures(onTap = { onSelectMode(FavoriteBatchDownloadMode.All) })
                        },
                    shape = RoundedCornerShape(14.dp),
                    color = colors.brownPrimary.copy(alpha = 0.14f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, colors.brownDeep),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selectedMode == FavoriteBatchDownloadMode.All,
                            onClick = { onSelectMode(FavoriteBatchDownloadMode.All) },
                            colors = RadioButtonDefaults.colors(selectedColor = colors.brownDeep),
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(i18n("全部下載"), color = colors.textStrong, fontWeight = FontWeight.SemiBold)
                            Text(i18n("帖子、標籤漫畫與 RSS 收藏都走既有的全部下載流程。"), color = colors.textDark.copy(alpha = 0.68f), fontSize = 12.sp)
                        }
                    }
                }
            }
        },
        confirmButton = { ActionChip(i18n("開始下載"), onConfirm) },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ActionChip(i18n("上一步"), onBack)
                ActionChip(i18n("取消"), onDismiss)
            }
        },
        containerColor = colors.creamSurface,
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

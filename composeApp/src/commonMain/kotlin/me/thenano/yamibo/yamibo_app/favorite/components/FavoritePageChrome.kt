package me.thenano.yamibo.yamibo_app.favorite.components

import me.thenano.yamibo.yamibo_app.i18n.i18n
import me.thenano.yamibo.yamibo_app.components.navigation.NavigationBackSymbol


import YamiboIcons
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.animation.core.tween
import me.thenano.yamibo.yamibo_app.favorite.FavoriteDialogButton
import me.thenano.yamibo.yamibo_app.repository.FavoriteStoreRepository.FavoriteCategory
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme
import me.thenano.yamibo.yamibo_app.components.controls.YamiboActionChip
import me.thenano.yamibo.yamibo_app.components.navigation.YamiboMainTabIconAction
import me.thenano.yamibo.yamibo_app.components.navigation.YamiboMainTabTopBar

@Composable
fun SelectionHeaderRows(
    title: String,
    actions: List<Pair<String, () -> Unit>>,
    onCancel: () -> Unit,
) {
    val colors = YamiboTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .animateContentSize(animationSpec = tween(durationMillis = 220))
            .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = colors.textStrong,
                modifier = Modifier.weight(1f),
            )
            ActionChip(i18n("返回"), onCancel)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            actions.forEach { action ->
                ActionChip(action.first, action.second)
            }
        }
    }
}

@Composable
fun FavoriteHeaderMenuRow(
    title: String,
    filterActive: Boolean,
    showFavoriteCounts: Boolean,
    onSearch: () -> Unit,
    onShowFilter: () -> Unit,
    onToggleFavoriteCounts: () -> Unit,
    onCreateCategory: () -> Unit,
    onManageCategory: () -> Unit,
    onSyncFavorites: () -> Unit,
    onShareFavorites: () -> Unit,
    onLoadFavorites: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    val colors = YamiboTheme.colors

    YamiboMainTabTopBar(
        title = title,
    ) {
        YamiboMainTabIconAction(YamiboIcons.Search, i18n("搜尋收藏"), onSearch, iconSize = 28, iconOffsetY = 4)
        YamiboMainTabIconAction(
            icon = YamiboIcons.FilterList,
            contentDescription = i18n("篩選收藏"),
            onClick = onShowFilter,
            iconSize = 26,
            tint = if (filterActive) colors.orangeAccent else colors.textOnBackground,
        )
        RowScopeMenuBox(
            showMenu = showMenu,
            showFavoriteCounts = showFavoriteCounts,
            onShowMenu = { showMenu = true },
            onDismissMenu = { showMenu = false },
            onToggleFavoriteCounts = onToggleFavoriteCounts,
            onCreateCategory = onCreateCategory,
            onManageCategory = onManageCategory,
            onSyncFavorites = onSyncFavorites,
            onShareFavorites = onShareFavorites,
            onLoadFavorites = onLoadFavorites,
        )
    }
}

@Composable
private fun RowScopeMenuBox(
    showMenu: Boolean,
    showFavoriteCounts: Boolean,
    onShowMenu: () -> Unit,
    onDismissMenu: () -> Unit,
    onToggleFavoriteCounts: () -> Unit,
    onCreateCategory: () -> Unit,
    onManageCategory: () -> Unit,
    onSyncFavorites: () -> Unit,
    onShareFavorites: () -> Unit,
    onLoadFavorites: () -> Unit,
) {
    val colors = YamiboTheme.colors
    Box {
        YamiboMainTabIconAction(YamiboIcons.ThreeDots, i18n("收藏選單"), iconSize = 25, onClick = onShowMenu)
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = onDismissMenu,
            modifier = Modifier.background(colors.creamSurface),
        ) {
            DropdownMenuItem(
                text = { Text(i18n("建新類別"), color = colors.textStrong) },
                leadingIcon = {
                    Icon(
                        imageVector = YamiboIcons.Plus,
                        contentDescription = null,
                        tint = colors.brownPrimary,
                    )
                },
                onClick = {
                    onDismissMenu()
                    onCreateCategory()
                },
            )
            DropdownMenuItem(
                text = { Text(i18n("管理類別"), color = colors.textStrong) },
                leadingIcon = {
                    Icon(
                        imageVector = YamiboIcons.Setting,
                        contentDescription = null,
                        tint = colors.brownPrimary,
                    )
                },
                onClick = {
                    onDismissMenu()
                    onManageCategory()
                },
            )
            DropdownMenuItem(
                text = { Text(i18n("顯示收藏數量"), color = colors.textStrong) },
                leadingIcon = {
                    Icon(
                        imageVector = if (showFavoriteCounts) YamiboIcons.CheckboxEnabled else YamiboIcons.CheckboxDisabled,
                        contentDescription = null,
                        tint = colors.brownPrimary,
                    )
                },
                onClick = {
                    onDismissMenu()
                    onToggleFavoriteCounts()
                },
            )
            DropdownMenuItem(
                text = { Text(i18n("同步百合會收藏"), color = colors.textStrong) },
                leadingIcon = {
                    Icon(
                        imageVector = YamiboIcons.Sync,
                        contentDescription = null,
                        tint = colors.brownPrimary,
                    )
                },
                onClick = {
                    onDismissMenu()
                    onSyncFavorites()
                },
            )
            DropdownMenuItem(
                text = { Text(i18n("分享收藏夾"), color = colors.textStrong) },
                leadingIcon = {
                    Icon(
                        imageVector = YamiboIcons.Share,
                        contentDescription = null,
                        tint = colors.brownPrimary,
                    )
                },
                onClick = {
                    onDismissMenu()
                    onShareFavorites()
                },
            )
            DropdownMenuItem(
                text = { Text(i18n("載入收藏夾"), color = colors.textStrong) },
                leadingIcon = {
                    Icon(
                        imageVector = YamiboIcons.Download,
                        contentDescription = null,
                        tint = colors.brownPrimary,
                    )
                },
                onClick = {
                    onDismissMenu()
                    onLoadFavorites()
                },
            )
        }
    }
}

@Composable
fun FavoriteSearchTopBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = YamiboTheme.colors
    var textFieldPlaced by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Text(NavigationBackSymbol, color = colors.textStrong, fontSize = 18.sp)
        }
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .weight(1f)
                .onPlaced { textFieldPlaced = true }
                .focusProperties { canFocus = textFieldPlaced },
            placeholder = {
                Text(i18n("搜尋收藏標題..."), color = colors.textDark.copy(alpha = 0.4f), fontSize = 15.sp)
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = colors.textDark,
                unfocusedTextColor = colors.textDark,
                cursorColor = colors.brownDeep,
                focusedBorderColor = colors.brownDeep,
                unfocusedBorderColor = colors.brownPrimary.copy(alpha = 0.3f),
                focusedContainerColor = colors.brownPrimary.copy(alpha = 0.05f),
                unfocusedContainerColor = colors.brownPrimary.copy(alpha = 0.03f),
            ),
            shape = RoundedCornerShape(12.dp),
            textStyle = TextStyle(fontSize = 15.sp),
        )
        Spacer(Modifier.width(6.dp))
        Surface(onClick = onSearch, shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp), color = colors.brownDeep) {
            Text(
                text = i18n("搜尋"),
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.width(8.dp))
    }
}

@Composable
fun FavoriteSyncCategoryDialog(
    categories: List<FavoriteCategory>,
    selectedCategoryId: Long,
    onDismiss: () -> Unit,
    onSelect: (Long) -> Unit,
    onConfirm: (Long) -> Unit,
) {
    val colors = YamiboTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                i18n("同步到哪個類別"),
                color = colors.textStrong,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                categories.forEach { category ->
                    val selected = category.id == selectedCategoryId
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .pointerInput(category.id) {
                                detectTapGestures(onTap = { onSelect(category.id) })
                            },
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                        color = if (selected) colors.brownPrimary.copy(alpha = 0.16f) else colors.creamBackground,
                        border = BorderStroke(
                            1.dp,
                            if (selected) colors.brownDeep else colors.brownPrimary.copy(alpha = 0.24f),
                        ),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = category.name,
                                modifier = Modifier.weight(1f),
                                color = if (selected) colors.brownDeep else colors.textDark,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            )
                            if (selected) {
                                Text(
                                    text = i18n("已選擇"),
                                    color = colors.brownDeep,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            FavoriteDialogButton(
                text = i18n("開始同步"),
                background = colors.brownDeep,
                contentColor = Color.White,
                onClick = { onConfirm(selectedCategoryId) },
            )
        },
        dismissButton = {
            FavoriteDialogButton(
                text = i18n("取消"),
                background = colors.brownPrimary.copy(alpha = 0.1f),
                contentColor = colors.textStrong,
                onClick = onDismiss,
            )
        },
        containerColor = colors.creamSurface,
        titleContentColor = colors.textStrong,
        textContentColor = colors.textDark,
    )
}

@Composable
fun ActionChip(text: String, onClick: () -> Unit) {
    YamiboActionChip(text = text, onClick = onClick)
}

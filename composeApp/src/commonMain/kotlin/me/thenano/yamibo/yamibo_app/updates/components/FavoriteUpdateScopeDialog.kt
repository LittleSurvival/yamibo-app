package me.thenano.yamibo.yamibo_app.updates.components

import androidx.compose.foundation.layout.*
import me.thenano.yamibo.yamibo_app.components.controls.AppLazyColumn as LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.thenano.yamibo.yamibo_app.components.controls.YamiboActionChip
import me.thenano.yamibo.yamibo_app.components.controls.YamiboSingleSelectRow
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme
import me.thenano.yamibo.yamibo_app.i18n.i18n
import me.thenano.yamibo.yamibo_app.repository.FavoriteUpdateRepository

@Composable
internal fun FavoriteUpdateScopeDialog(
    forumFilters: List<FavoriteUpdateRepository.FidFilter>,
    categoryFilters: List<FavoriteUpdateRepository.CategoryFilter>,
    scopeTargets: List<FavoriteUpdateRepository.ScopeTarget>,
    onDismiss: () -> Unit,
    onConfirm: (Map<Int, Boolean>, Map<Long, Boolean>) -> Unit,
) {
    val colors = YamiboTheme.colors
    var selectedTab by remember { mutableStateOf(UpdateScopeTab.Forum) }
    var draftForumIds by remember(forumFilters) {
        mutableStateOf(forumFilters.filter { it.enabled }.map { it.fid }.toSet())
    }
    var draftCategoryIds by remember(categoryFilters) {
        mutableStateOf(categoryFilters.filter { it.enabled }.map { it.categoryId }.toSet())
    }
    val forumAll = draftForumIds.isEmpty() || draftForumIds.size == forumFilters.size
    val categoryAll = draftCategoryIds.isEmpty() || draftCategoryIds.size == categoryFilters.size
    val updateCount = remember(scopeTargets, draftForumIds, draftCategoryIds, forumAll, categoryAll) {
        scopeTargets.count { target ->
            val forumMatches = forumAll || target.fid in draftForumIds
            val categoryMatches = categoryAll || target.categoryIds.any { it in draftCategoryIds }
            forumMatches && categoryMatches
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(i18n("更新範圍"), color = colors.textStrong, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    FavoriteUpdateScopeTabButton(
                        text = i18n("版塊"),
                        selected = selectedTab == UpdateScopeTab.Forum,
                        modifier = Modifier.weight(1f),
                        onClick = { selectedTab = UpdateScopeTab.Forum },
                    )
                    FavoriteUpdateScopeTabButton(
                        text = i18n("收藏夾"),
                        selected = selectedTab == UpdateScopeTab.Category,
                        modifier = Modifier.weight(1f),
                        onClick = { selectedTab = UpdateScopeTab.Category },
                    )
                }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    when (selectedTab) {
                        UpdateScopeTab.Forum -> {
                            FilterSelectionSection(
                                filters = forumFilters,
                                draftSelection = draftForumIds,
                                isAllSelected = forumAll,
                                getId = { it.fid },
                                getLabel = { it.forumName },
                                getItemCount = { it.itemCount },
                                onSelectAll = { draftForumIds = forumFilters.map { it.fid }.toSet() },
                                onToggle = { id ->
                                    draftForumIds = toggleDraftSelection(
                                        id,
                                        draftForumIds,
                                        forumFilters.map { it.fid }.toSet(),
                                    )
                                }
                            )
                        }
                        UpdateScopeTab.Category -> {
                            FilterSelectionSection(
                                filters = categoryFilters,
                                draftSelection = draftCategoryIds,
                                isAllSelected = categoryAll,
                                getId = { it.categoryId },
                                getLabel = { it.categoryName },
                                getItemCount = { it.itemCount },
                                onSelectAll = { draftCategoryIds = categoryFilters.map { it.categoryId }.toSet() },
                                onToggle = { id ->
                                    draftCategoryIds = toggleDraftSelection(
                                        id,
                                        draftCategoryIds,
                                        categoryFilters.map { it.categoryId }.toSet(),
                                    )
                                }
                            )
                        }
                    }
                }
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = colors.brownPrimary.copy(alpha = 0.08f),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            i18n(
                                "版塊：{}",
                                summarizeScopeSelection(
                                    allSelected = forumAll,
                                    selectedLabels = forumFilters.filter { it.fid in draftForumIds }.map { it.forumName },
                                )
                            ),
                            color = colors.textDark,
                            fontSize = 12.sp,
                        )
                        Text(
                            i18n(
                                "收藏夾：{}",
                                summarizeScopeSelection(
                                    allSelected = categoryAll,
                                    selectedLabels = categoryFilters.filter { it.categoryId in draftCategoryIds }.map { it.categoryName },
                                )
                            ),
                            color = colors.textDark,
                            fontSize = 12.sp,
                        )
                        Text(
                            i18n("目前範圍會檢查 {} 個收藏", updateCount),
                            color = colors.textStrong,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                YamiboActionChip(i18n("取消"), onClick = onDismiss)
                YamiboActionChip(
                    i18n("套用"),
                    onClick = {
                        val forumChanges = forumFilters.mapNotNull { filter ->
                            val enabled = forumAll || filter.fid in draftForumIds
                            if (enabled != filter.enabled) filter.fid to enabled else null
                        }.toMap()
                        val categoryChanges = categoryFilters.mapNotNull { filter ->
                            val enabled = categoryAll || filter.categoryId in draftCategoryIds
                            if (enabled != filter.enabled) filter.categoryId to enabled else null
                        }.toMap()
                        onConfirm(forumChanges, categoryChanges)
                    },
                )
            }
        },
        dismissButton = {},
        containerColor = colors.creamSurface,
        titleContentColor = colors.textStrong,
        textContentColor = colors.textDark,
    )
}

@Composable
private fun FavoriteUpdateScopeTabButton(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = YamiboTheme.colors
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) colors.brownDeep else colors.brownPrimary.copy(alpha = 0.10f),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(vertical = 10.dp),
            color = if (selected) colors.textOnDeepHigh else colors.textOnTint,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
    }
}

private enum class UpdateScopeTab {
    Forum,
    Category,
}

private fun <T> toggleDraftSelection(value: T, selected: Set<T>, allValues: Set<T>): Set<T> {
    val normalized = if (selected.isEmpty() || selected.size == allValues.size) emptySet() else selected
    val updated = if (value in normalized) normalized - value else normalized + value
    return if (updated.isEmpty() || updated.size == allValues.size) allValues else updated
}

private fun summarizeScopeSelection(allSelected: Boolean, selectedLabels: List<String>): String {
    return when {
        allSelected -> i18n("全部")
        selectedLabels.isEmpty() -> i18n("全部")
        selectedLabels.size <= 3 -> selectedLabels.joinToString("、")
        else -> i18n("{} 項", selectedLabels.size)
    }
}

private fun <ID, T> LazyListScope.FilterSelectionSection(
    filters: List<T>,
    draftSelection: Set<ID>,
    isAllSelected: Boolean,
    getId: (T) -> ID,
    getLabel: (T) -> String,
    getItemCount: (T) -> Int,
    onSelectAll: () -> Unit,
    onToggle: (ID) -> Unit,
) {
    item {
        YamiboSingleSelectRow(
            label = i18n("全部 ({})", filters.sumOf(getItemCount)),
            selected = isAllSelected,
            selectedText = i18n("已選擇"),
            onClick = onSelectAll,
        )
    }
    items(filters, key = { getId(it) as Any }) { filter ->
        val id = getId(filter)
        val selected = !isAllSelected && id in draftSelection
        YamiboSingleSelectRow(
            label = "${getLabel(filter)} (${getItemCount(filter)})",
            selected = selected,
            selectedText = i18n("已選擇"),
            onClick = { onToggle(id) },
        )
    }
}

package me.thenano.yamibo.yamibo_app.favorite

import me.thenano.yamibo.yamibo_app.i18n.i18n
import me.thenano.yamibo.yamibo_app.components.navigation.NavigationBackSymbol


import YamiboIcons
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.littlesurvival.dto.value.ForumId
import io.github.littlesurvival.dto.value.TagId
import io.github.littlesurvival.dto.value.ThreadId
import io.github.littlesurvival.dto.value.UserId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.thenano.yamibo.yamibo_app.confirmation.AppConfirmationController
import me.thenano.yamibo.yamibo_app.confirmation.AppConfirmationEvent
import me.thenano.yamibo.yamibo_app.confirmation.AppConfirmationResult
import me.thenano.yamibo.yamibo_app.feedback.AppFeedbackEvent
import me.thenano.yamibo.yamibo_app.favorite.components.collectionColor
import me.thenano.yamibo.yamibo_app.repository.FavoriteSyncRepository
import me.thenano.yamibo.yamibo_app.repository.FavoriteSyncRepository.FavoriteSyncActionResult
import me.thenano.yamibo.yamibo_app.repository.FavoriteSyncRepository.FavoriteSyncDeleteResult
import me.thenano.yamibo.yamibo_app.repository.FavoriteStoreRepository
import me.thenano.yamibo.yamibo_app.repository.ReadHistoryRepository
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme
import me.thenano.yamibo.yamibo_app.task.AppTaskKey
import me.thenano.yamibo.yamibo_app.task.AppTaskManager
import me.thenano.yamibo.yamibo_app.task.AppTaskResult

sealed interface FavoriteTargetPayload {
    data class Thread(
        val tid: ThreadId,
        val title: String,
        val threadType: ReadHistoryRepository.ThreadEntryType,
        val authorId: UserId?,
        val coverUrl: String?,
        val lastUpdatedTime: Long?,
        val forumId: ForumId?,
        val forumName: String?,
    ) : FavoriteTargetPayload

    data class TagManga(
        val tagId: TagId,
        val tagName: String,
        val coverUrl: String?,
    ) : FavoriteTargetPayload

    data class RssSearch(
        val subscriptionId: Long,
        val title: String,
        val coverUrl: String? = null,
        val lastUpdatedTime: Long? = null,
    ) : FavoriteTargetPayload
}

private data class FavoritePickerCategory(
    val categoryId: Long,
    val categoryName: String,
    val collections: List<FavoriteStoreRepository.FavoriteCollectionOption>,
)

data class FavoriteLocationSelection(
    val item: FavoriteStoreRepository.FavoriteItem?,
    val categoryIds: Set<Long>,
    val collectionIds: Set<Long>,
    val paths: List<String>,
)

internal fun FavoriteTargetPayload.supportsRemoteWebsiteSync(): Boolean = this is FavoriteTargetPayload.Thread

internal fun FavoriteTargetPayload.taskIdentity(): String = when (this) {
    is FavoriteTargetPayload.Thread -> "thread:${tid.value}"
    is FavoriteTargetPayload.TagManga -> "tag:${tagId.value}"
    is FavoriteTargetPayload.RssSearch -> "rss:$subscriptionId"
}

internal enum class FavoriteCreationOutcome {
    Created,
    AlreadyExisted,
}

internal data class FavoriteAddResult(
    val creationOutcome: FavoriteCreationOutcome,
    val syncResult: FavoriteSyncActionResult?,
)

internal suspend fun FavoriteStoreRepository.saveFavoriteWithOutcome(
    target: FavoriteTargetPayload,
    categoryIds: List<Long> = emptyList(),
    collectionIds: List<Long> = emptyList(),
): FavoriteCreationOutcome {
    val existed = findFavoriteItem(target) != null
    saveFavorite(target, categoryIds, collectionIds)
    return if (existed) FavoriteCreationOutcome.AlreadyExisted else FavoriteCreationOutcome.Created
}

internal suspend fun addFavoriteAndMaybeSync(
    favoriteRepository: FavoriteStoreRepository,
    favoriteSyncRepository: FavoriteSyncRepository,
    target: FavoriteTargetPayload,
    syncToRemote: Boolean,
): FavoriteAddResult {
    val creationOutcome = favoriteRepository.saveFavoriteWithOutcome(target)
    val syncResult = syncExistingFavoriteIfRequested(
        favoriteRepository = favoriteRepository,
        favoriteSyncRepository = favoriteSyncRepository,
        target = target,
        syncToRemote = syncToRemote,
    )
    return FavoriteAddResult(creationOutcome, syncResult)
}

internal suspend fun syncExistingFavoriteIfRequested(
    favoriteRepository: FavoriteStoreRepository,
    favoriteSyncRepository: FavoriteSyncRepository,
    target: FavoriteTargetPayload,
    syncToRemote: Boolean,
): FavoriteSyncActionResult? {
    if (!syncToRemote || !target.supportsRemoteWebsiteSync()) return null
    val item = favoriteRepository.findFavoriteItem(target)
        ?: return FavoriteSyncActionResult(false, i18n("已加入本地收藏，但找不到同步目標。"))
    return favoriteSyncRepository.syncLocalFavoriteItem(item.id)
}

internal suspend fun hasRemoteFavoriteForTarget(
    favoriteRepository: FavoriteStoreRepository,
    favoriteSyncRepository: FavoriteSyncRepository,
    target: FavoriteTargetPayload,
): Boolean {
    val item = favoriteRepository.findFavoriteItem(target) ?: return false
    return favoriteSyncRepository.hasRemoteFavorite(item.id)
}

internal suspend fun completeFavoriteAddWithFeedback(
    favoriteRepository: FavoriteStoreRepository,
    favoriteSyncRepository: FavoriteSyncRepository,
    target: FavoriteTargetPayload,
    syncToRemote: Boolean,
    feedbackController: me.thenano.yamibo.yamibo_app.feedback.AppFeedbackController,
): FavoriteAddResult {
    val feedbackGroup = "favorite-add:${target.taskIdentity()}"
    if (syncToRemote) {
        feedbackController.post(i18n("正在同步到百合會..."), groupKey = feedbackGroup)
    }
    val result = withContext(Dispatchers.Default) {
        addFavoriteAndMaybeSync(favoriteRepository, favoriteSyncRepository, target, syncToRemote)
    }
    val message = favoriteSyncFeedbackMessage(result.syncResult, i18n("已加入收藏"))
    feedbackController.post(message, groupKey = feedbackGroup)
    return result
}

internal suspend fun completeSavedFavoriteSyncWithFeedback(
    favoriteRepository: FavoriteStoreRepository,
    favoriteSyncRepository: FavoriteSyncRepository,
    target: FavoriteTargetPayload,
    syncToRemote: Boolean,
    feedbackController: me.thenano.yamibo.yamibo_app.feedback.AppFeedbackController,
) {
    val feedbackGroup = "favorite-sync:${target.taskIdentity()}"
    if (syncToRemote) {
        feedbackController.post(i18n("正在同步到百合會..."), groupKey = feedbackGroup)
    }
    val syncResult = withContext(Dispatchers.Default) {
        syncExistingFavoriteIfRequested(favoriteRepository, favoriteSyncRepository, target, syncToRemote)
    }
    val message = favoriteSyncFeedbackMessage(syncResult, i18n("已加入本地收藏"))
    feedbackController.post(message, groupKey = feedbackGroup)
}

internal suspend fun completeFavoriteRemovalWithFeedback(
    favoriteRepository: FavoriteStoreRepository,
    favoriteSyncRepository: FavoriteSyncRepository,
    target: FavoriteTargetPayload,
    removeRemote: Boolean,
    feedbackController: me.thenano.yamibo.yamibo_app.feedback.AppFeedbackController,
    confirmationController: AppConfirmationController,
    appTaskManager: AppTaskManager,
    successMessage: String,
    failureMessage: String,
) {
    val feedbackGroup = "favorite-remove:${target.taskIdentity()}"
    if (removeRemote) {
        feedbackController.post(i18n("正在從百合會移除收藏..."), groupKey = feedbackGroup)
    }
    val itemId = withContext(Dispatchers.Default) {
        favoriteRepository.findFavoriteItem(target)?.id
    }
    if (itemId == null) {
        feedbackController.post(
            if (removeRemote) i18n("找不到本地收藏，未送出百合會解除收藏請求") else successMessage,
            groupKey = feedbackGroup,
        )
        return
    }
    completeFavoriteRemovalByItemIdWithFeedback(
        itemId = itemId,
        removeRemote = removeRemote,
        favoriteSyncRepository = favoriteSyncRepository,
        feedbackController = feedbackController,
        confirmationController = confirmationController,
        appTaskManager = appTaskManager,
        remoteSuccessMessage = i18n("已從百合會取消收藏"),
        confirmationTitle = i18n("無法從百合會解除收藏"),
        localOnlyConfirmLabel = i18n("僅解除本地收藏"),
        keepLocalDismissLabel = i18n("保留本地收藏"),
        localOnlySuccessMessage = i18n("已解除本地收藏，百合會收藏仍可能存在"),
        successMessage = successMessage,
        failureMessage = failureMessage,
        feedbackGroup = feedbackGroup,
    )
}

internal suspend fun completeFavoriteRemovalByItemIdWithFeedback(
    itemId: Long,
    removeRemote: Boolean,
    favoriteSyncRepository: FavoriteSyncRepository,
    feedbackController: me.thenano.yamibo.yamibo_app.feedback.AppFeedbackController,
    confirmationController: AppConfirmationController,
    appTaskManager: AppTaskManager,
    remoteSuccessMessage: String,
    confirmationTitle: String,
    localOnlyConfirmLabel: String,
    keepLocalDismissLabel: String,
    localOnlySuccessMessage: String,
    successMessage: String,
    failureMessage: String,
    feedbackGroup: String? = null,
) {
    val result = withContext(Dispatchers.Default) {
        favoriteSyncRepository.removeLocalFavoriteItem(itemId, removeRemote = removeRemote)
    }
    if (result.success) {
        feedbackController.post(if (removeRemote) remoteSuccessMessage else successMessage, groupKey = feedbackGroup)
        return
    }

    val reason = result.message?.takeIf { it.isNotBlank() } ?: failureMessage
    if (!removeRemote) {
        feedbackController.post(reason, groupKey = feedbackGroup)
        return
    }

    val decision = confirmationController.request(
        AppConfirmationEvent(
            title = confirmationTitle,
            message = reason,
            confirmLabel = localOnlyConfirmLabel,
            dismissLabel = keepLocalDismissLabel,
        )
    )
    if (decision != AppConfirmationResult.Confirmed) return

    appTaskManager.submit(AppTaskKey("favorite:remove-local:$itemId")) {
        val localResult = withContext(Dispatchers.Default) {
            favoriteSyncRepository.removeLocalFavoriteItem(itemId, removeRemote = false)
        }
        if (localResult.success) {
            AppTaskResult.Success(
                feedback = AppFeedbackEvent(localOnlySuccessMessage),
            )
        } else {
            val localFailure = localResult.message
                ?.takeIf { it.isNotBlank() }
                ?: failureMessage
            AppTaskResult.Failure(
                message = localFailure,
                feedback = AppFeedbackEvent(localFailure),
            )
        }
    }
}

private fun favoriteSyncFeedbackMessage(
    result: FavoriteSyncRepository.FavoriteSyncActionResult?,
    localOnlyMessage: String,
): String = when {
    result == null -> localOnlyMessage
    result.success -> result.message?.let { i18n(it) }?.takeIf { it.isNotBlank() } ?: i18n("已同步至百合會")
    else -> i18n(
        "同步至百合會失敗：{}",
        result.message?.let { i18n(it) }?.takeIf { it.isNotBlank() } ?: i18n("請稍後再試"),
    )
}

suspend fun FavoriteStoreRepository.saveFavorite(
    target: FavoriteTargetPayload,
    categoryIds: List<Long> = emptyList(),
    collectionIds: List<Long> = emptyList(),
) {
    when (target) {
        is FavoriteTargetPayload.Thread -> {
            if (target.threadType == ReadHistoryRepository.ThreadEntryType.Novel) {
                addNovelThreadFavorite(
                    tid = target.tid,
                    title = target.title,
                    authorId = target.authorId,
                    coverUrl = target.coverUrl,
                    lastUpdatedTime = target.lastUpdatedTime,
                    forumId = target.forumId,
                    forumName = target.forumName,
                    categoryIds = categoryIds,
                    collectionIds = collectionIds,
                )
            } else {
                addNormalThreadFavorite(
                    tid = target.tid,
                    title = target.title,
                    coverUrl = target.coverUrl,
                    lastUpdatedTime = target.lastUpdatedTime,
                    forumId = target.forumId,
                    forumName = target.forumName,
                    categoryIds = categoryIds,
                    collectionIds = collectionIds,
                )
            }
        }

        is FavoriteTargetPayload.TagManga -> {
            addTagMangaFavorite(
                tagId = target.tagId,
                tagName = target.tagName,
                coverUrl = target.coverUrl,
                categoryIds = categoryIds,
                collectionIds = collectionIds,
            )
        }

        is FavoriteTargetPayload.RssSearch -> {
            addRssSearchFavorite(
                subscriptionId = target.subscriptionId,
                title = target.title,
                coverUrl = target.coverUrl,
                lastUpdatedTime = target.lastUpdatedTime,
                categoryIds = categoryIds,
                collectionIds = collectionIds,
            )
        }
    }
}

@Composable
fun FavoriteActionButton(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    tint: Color = YamiboTheme.colors.brownDeep,
    iconSize: Int = 20,
    filled: Boolean = false,
) {
    Surface(
        modifier = modifier.pointerInput(onClick, onLongClick) {
            detectTapGestures(
                onTap = { onClick() },
                onLongPress = { onLongClick?.invoke() },
            )
        },
        shape = RoundedCornerShape(12.dp),
        color = Color.Transparent,
    ) {
        Box(
            modifier = Modifier.padding(8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (filled) YamiboIcons.StarFilled else YamiboIcons.StarOutline,
                contentDescription = i18n("收藏"),
                tint = if (filled) YamiboTheme.colors.orangeAccent else tint,
                modifier = Modifier.size(iconSize.dp),
            )
        }
    }
}

@Composable
fun FavoriteCollectionPickerDialog(
    categories: List<FavoriteStoreRepository.FavoriteCategory>,
    options: List<FavoriteStoreRepository.FavoriteCollectionOption>,
    initialCategorySelection: Set<Long>,
    initialCollectionSelection: Set<Long>,
    initialOpenedCategoryId: Long? = null,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onCreateCollection: (Long) -> Unit = {},
    onConfirm: (Set<Long>, Set<Long>) -> Unit,
) {
    val colors = YamiboTheme.colors
    val checkboxColors = CheckboxDefaults.colors(
        checkedColor = colors.brownDeep,
        uncheckedColor = colors.textDark.copy(alpha = 0.4f),
        checkmarkColor = Color.White,
    )
    val groupedCategories = remember(categories, options) {
        val collectionsByCategory = options.groupBy { it.categoryId }
        categories
            .sortedWith(compareBy<FavoriteStoreRepository.FavoriteCategory> { it.sortOrder }.thenBy { it.id })
            .map { category ->
                FavoritePickerCategory(
                    categoryId = category.id,
                    categoryName = category.name,
                    collections = collectionsByCategory[category.id].orEmpty().sortedBy { it.id },
                )
            }
    }
    var selectedCategories by remember(categories, options, initialCategorySelection) {
        mutableStateOf(initialCategorySelection)
    }
    var selectedCollections by remember(categories, options, initialCollectionSelection) {
        mutableStateOf(initialCollectionSelection)
    }
    var currentCategoryId by remember(categories, options, initialOpenedCategoryId) {
        mutableStateOf(initialOpenedCategoryId)
    }

    val currentCategory = groupedCategories.firstOrNull { it.categoryId == currentCategoryId }

    fun toggleCategory(category: FavoritePickerCategory) {
        val categoryCollectionIds = category.collections.map { it.id }.toSet()
        val hasAnySelection =
            category.categoryId in selectedCategories ||
                categoryCollectionIds.any(selectedCollections::contains)
        if (hasAnySelection) {
            selectedCategories -= category.categoryId
            selectedCollections -= categoryCollectionIds
        } else {
            selectedCategories += category.categoryId
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            shape = RoundedCornerShape(24.dp),
            color = colors.creamSurface,
            border = BorderStroke(1.dp, colors.brownPrimary.copy(alpha = 0.15f)),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = i18n("選擇類別"),
                    color = colors.brownDeep,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                )

                Spacer(Modifier.size(6.dp))
                Text(
                    text = currentCategory?.let { "${it.categoryName}/" } ?: i18n("勾選大類，或點進去選擇小集合"),
                    color = colors.textDark.copy(alpha = if (currentCategory == null) 0.58f else 0.82f),
                    fontSize = 12.sp,
                    fontWeight = if (currentCategory == null) FontWeight.Normal else FontWeight.Medium,
                )

                Spacer(Modifier.size(16.dp))

                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = colors.creamBackground,
                    border = BorderStroke(1.dp, colors.brownPrimary.copy(alpha = 0.16f)),
                ) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp)
                            .padding(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        if (currentCategory == null) {
                            items(groupedCategories, key = { it.categoryId }) { category ->
                                FavoriteCategoryRow(
                                    category = category,
                                    selectedCategories = selectedCategories,
                                    selectedCollections = selectedCollections,
                                    triStateColors = checkboxColors,
                                    onToggleCategory = { toggleCategory(category) },
                                    onOpenCategory = { currentCategoryId = category.categoryId },
                                )
                            }
                        } else {
                            item(key = "category_root_${currentCategory.categoryId}") {
                                FavoriteCategoryRootRow(
                                    category = currentCategory,
                                    selectedCategories = selectedCategories,
                                    selectedCollections = selectedCollections,
                                    triStateColors = checkboxColors,
                                    onToggle = { toggleCategory(currentCategory) },
                                )
                            }
                            items(currentCategory.collections, key = { it.id }) { option ->
                                FavoriteCollectionRow(
                                    option = option,
                                    checked = option.id in selectedCollections,
                                    checkboxColors = checkboxColors,
                                    onToggle = {
                                        selectedCollections = selectedCollections.toggle(option.id)
                                    },
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.size(18.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (currentCategory != null) {
                        FavoriteDialogButton(
                            text = i18n("返回"),
                            background = colors.brownPrimary.copy(alpha = 0.12f),
                            contentColor = colors.textStrong,
                            onClick = { currentCategoryId = null },
                        )
                    }

                    FavoriteDialogButton(
                        text = i18n("編輯"),
                        background = colors.brownPrimary.copy(alpha = 0.08f),
                        contentColor = colors.textStrong,
                        onClick = onEdit,
                    )

                    if (currentCategory != null) {
                        FavoriteDialogButton(
                            text = i18n("新增集合"),
                            background = colors.brownPrimary.copy(alpha = 0.08f),
                            contentColor = colors.textStrong,
                            onClick = { onCreateCollection(currentCategory.categoryId) },
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    FavoriteDialogButton(
                        text = i18n("取消"),
                        background = colors.textDark.copy(alpha = 0.06f),
                        contentColor = colors.textDark.copy(alpha = 0.8f),
                        onClick = onDismiss,
                    )

                    FavoriteDialogButton(
                        text = i18n("確定"),
                        background = colors.brownDeep,
                        contentColor = Color.White,
                        onClick = { onConfirm(selectedCategories, selectedCollections) },
                    )
                }
            }
        }
    }
}

@Composable
private fun FavoriteCategoryRow(
    category: FavoritePickerCategory,
    selectedCategories: Set<Long>,
    selectedCollections: Set<Long>,
    triStateColors: CheckboxColors,
    onToggleCategory: () -> Unit,
    onOpenCategory: () -> Unit,
) {
    val colors = YamiboTheme.colors
    val selectedCollectionCount = category.collections.count { it.id in selectedCollections }
    val toggleState = when {
        category.categoryId in selectedCategories -> ToggleableState.On
        selectedCollectionCount > 0 -> ToggleableState.Indeterminate
        else -> ToggleableState.Off
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TriStateCheckbox(
            state = toggleState,
            onClick = onToggleCategory,
            colors = triStateColors,
        )
        Spacer(Modifier.width(4.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = category.categoryName,
                color = colors.textDark,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = buildString {
                    if (category.categoryId in selectedCategories) {
                        append(i18n("已加入大類"))
                    }
                    if (selectedCollectionCount > 0) {
                        if (isNotEmpty()) append(" / ")
                        append(i18n("{} 個集合", selectedCollectionCount))
                    }
                    if (isEmpty()) append(i18n("尚未加入"))
                },
                color = colors.textDark.copy(alpha = 0.5f),
                fontSize = 11.sp,
            )
        }
        Surface(
            onClick = onOpenCategory,
            shape = CircleShape,
            color = colors.brownPrimary.copy(alpha = 0.12f),
        ) {
            Box(
                modifier = Modifier.size(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = ">",
                    color = colors.brownDeep,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun FavoriteCategoryRootRow(
    category: FavoritePickerCategory,
    selectedCategories: Set<Long>,
    selectedCollections: Set<Long>,
    triStateColors: CheckboxColors,
    onToggle: () -> Unit,
) {
    val colors = YamiboTheme.colors
    val categoryCollectionIds = remember(category) { category.collections.map { it.id }.toSet() }
    val toggleState = when {
        category.categoryId in selectedCategories -> ToggleableState.On
        categoryCollectionIds.any(selectedCollections::contains) -> ToggleableState.Indeterminate
        else -> ToggleableState.Off
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(category.categoryId, toggleState) {
                detectTapGestures(onTap = { onToggle() })
            }
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TriStateCheckbox(
                state = toggleState,
                onClick = onToggle,
                colors = triStateColors,
            )
            Spacer(Modifier.width(4.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = category.categoryName,
                    color = colors.textDark,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = when (toggleState) {
                        ToggleableState.On -> i18n("已加入大類")
                        ToggleableState.Indeterminate -> i18n("只加入部分集合")
                        ToggleableState.Off -> i18n("未加入大類")
                    },
                    color = colors.textDark.copy(alpha = 0.45f),
                    fontSize = 11.sp,
                )
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = 48.dp, top = 4.dp),
            color = colors.brownPrimary.copy(alpha = 0.1f),
        )
    }
}

@Composable
private fun FavoriteCollectionRow(
    option: FavoriteStoreRepository.FavoriteCollectionOption,
    checked: Boolean,
    checkboxColors: CheckboxColors,
    onToggle: () -> Unit,
) {
    val colors = YamiboTheme.colors

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(option.id, checked) {
                detectTapGestures(onTap = { onToggle() })
            }
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = checked,
                onCheckedChange = { onToggle() },
                colors = checkboxColors,
            )
            Spacer(Modifier.width(4.dp))
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(collectionColor(option.colorKey), CircleShape),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = option.collectionName,
                    color = colors.textDark,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "#${option.categoryName}",
                    color = colors.textDark.copy(alpha = 0.45f),
                    fontSize = 11.sp,
                )
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = 48.dp, top = 4.dp),
            color = colors.brownPrimary.copy(alpha = 0.1f),
        )
    }
}

@Composable
internal fun FavoriteDialogButton(
    text: String,
    background: Color,
    contentColor: Color,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = background,
        border = BorderStroke(1.dp, background.copy(alpha = 0.9f)),
    ) {
        Text(
            text = text,
            color = contentColor,
            fontSize = if (text == NavigationBackSymbol) 18.sp else 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

internal suspend fun FavoriteStoreRepository.findFavoriteItem(
    target: FavoriteTargetPayload,
): FavoriteStoreRepository.FavoriteItem? {
    val targetType = when (target) {
        is FavoriteTargetPayload.Thread -> {
            if (target.threadType == ReadHistoryRepository.ThreadEntryType.Novel) {
                FavoriteStoreRepository.FavoriteTargetType.ThreadNovel
            } else {
                FavoriteStoreRepository.FavoriteTargetType.ThreadNormal
            }
        }

        is FavoriteTargetPayload.TagManga -> FavoriteStoreRepository.FavoriteTargetType.TagManga
        is FavoriteTargetPayload.RssSearch -> FavoriteStoreRepository.FavoriteTargetType.RssSearch
    }
    val targetId = when (target) {
        is FavoriteTargetPayload.Thread -> target.tid.value.toLong()
        is FavoriteTargetPayload.TagManga -> target.tagId.value.toLong()
        is FavoriteTargetPayload.RssSearch -> target.subscriptionId
    }
    val authorId = when (target) {
        is FavoriteTargetPayload.Thread -> target.authorId
        is FavoriteTargetPayload.TagManga -> null
        is FavoriteTargetPayload.RssSearch -> null
    }
    return getFavoriteItem(targetType, targetId, authorId)
}

internal suspend fun FavoriteStoreRepository.getFavoriteLocationSelection(
    target: FavoriteTargetPayload,
): FavoriteLocationSelection {
    val item = findFavoriteItem(target) ?: return FavoriteLocationSelection(
        item = null,
        categoryIds = emptySet(),
        collectionIds = emptySet(),
        paths = emptyList(),
    )

    return FavoriteLocationSelection(
        item = item,
        categoryIds = getCategoryIdsForItem(item.id),
        collectionIds = getCollectionIdsForItem(item.id),
        paths = getFavoritePaths(item.id),
    )
}

internal suspend fun FavoriteStoreRepository.syncFavoriteMetadata(
    target: FavoriteTargetPayload,
) {
    val selection = getFavoriteLocationSelection(target)
    val existingItem = selection.item ?: return
    saveFavorite(
        target = target,
        categoryIds = selection.categoryIds.toList(),
        collectionIds = selection.collectionIds.toList(),
    )
    if (existingItem.id != selection.item.id) {
        setItemLocations(
            itemId = existingItem.id,
            categoryIds = selection.categoryIds,
            collectionIds = selection.collectionIds,
        )
    }
}

internal suspend fun removeFavoriteWithSync(
    favoriteRepository: FavoriteStoreRepository,
    favoriteSyncRepository: FavoriteSyncRepository,
    target: FavoriteTargetPayload,
    removeRemote: Boolean = true,
): FavoriteSyncDeleteResult {
    val item = favoriteRepository.findFavoriteItem(target)
        ?: return FavoriteSyncDeleteResult(success = true)
    return favoriteSyncRepository.removeLocalFavoriteItem(item.id, removeRemote = removeRemote)
}

@Composable
fun FavoriteRemovalConfirmDialog(
    onDismiss: () -> Unit,
    onConfirm: (Boolean) -> Unit,
) {
    val colors = YamiboTheme.colors
    var skipNextTime by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(i18n("確定要取消收藏嗎"), color = colors.textStrong, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = i18n("取消後會移除目前這個收藏項目。"),
                    color = colors.textDark,
                    fontSize = 14.sp,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = skipNextTime,
                        onCheckedChange = { skipNextTime = it },
                        colors = CheckboxDefaults.colors(
                            checkedColor = colors.brownDeep,
                            uncheckedColor = colors.textDark.copy(alpha = 0.4f),
                            checkmarkColor = Color.White,
                        ),
                    )
                    Text(
                        text = i18n("下次不再提示"),
                        color = colors.textDark,
                        fontSize = 13.sp,
                    )
                }
            }
        },
        confirmButton = {
            FavoriteDialogButton(
                text = i18n("確定"),
                background = colors.brownDeep,
                contentColor = Color.White,
                onClick = { onConfirm(skipNextTime) },
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
    )
}

@Composable
fun FavoriteAddSyncConfirmDialog(
    onDismiss: () -> Unit,
    onConfirm: (rememberChoice: Boolean, syncRemote: Boolean) -> Unit,
) {
    FavoriteRemoteSyncChoiceDialog(
        title = i18n("同步到百合會收藏嗎"),
        message = i18n("要將此收藏同步到百合會嗎？"),
        rememberLabel = i18n("記住這次選擇"),
        primaryText = i18n("同步到百合會"),
        secondaryText = i18n("只存本地"),
        onDismiss = onDismiss,
        onConfirm = onConfirm,
    )
}

@Composable
fun FavoriteRemoveSyncConfirmDialog(
    onDismiss: () -> Unit,
    onConfirm: (rememberChoice: Boolean, syncRemote: Boolean) -> Unit,
) {
    FavoriteRemoteSyncChoiceDialog(
        title = i18n("同步移除百合會收藏嗎"),
        message = i18n("要將此收藏從百合會網站移除嗎？"),
        rememberLabel = i18n("記住這次選擇"),
        primaryText = i18n("同步移除"),
        secondaryText = i18n("只刪本地"),
        onDismiss = onDismiss,
        onConfirm = onConfirm,
    )
}

@Composable
private fun FavoriteRemoteSyncChoiceDialog(
    title: String,
    message: String,
    rememberLabel: String,
    primaryText: String,
    secondaryText: String,
    onDismiss: () -> Unit,
    onConfirm: (rememberChoice: Boolean, syncRemote: Boolean) -> Unit,
) {
    val colors = YamiboTheme.colors
    var rememberChoice by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, color = colors.textStrong, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = message,
                    color = colors.textDark,
                    fontSize = 14.sp,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = rememberChoice,
                        onCheckedChange = { rememberChoice = it },
                        colors = CheckboxDefaults.colors(
                            checkedColor = colors.brownDeep,
                            uncheckedColor = colors.textDark.copy(alpha = 0.4f),
                            checkmarkColor = Color.White,
                        ),
                    )
                    Text(
                        text = rememberLabel,
                        color = colors.textDark,
                        fontSize = 13.sp,
                    )
                }
            }
        },
        confirmButton = {
            FavoriteDialogButton(
                text = primaryText,
                background = colors.brownDeep,
                contentColor = Color.White,
                onClick = { onConfirm(rememberChoice, true) },
            )
        },
        dismissButton = {
            FavoriteDialogButton(
                text = secondaryText,
                background = colors.brownPrimary.copy(alpha = 0.1f),
                contentColor = colors.textStrong,
                onClick = { onConfirm(rememberChoice, false) },
            )
        },
        containerColor = colors.creamSurface,
    )
}

@Composable
fun FavoriteMultiPathRemoveDialog(
    paths: List<String>,
    tip: String,
    onDismiss: () -> Unit,
    onRemoveAll: () -> Unit,
) {
    val colors = YamiboTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(i18n("取消全部收藏嗎"), color = colors.textStrong, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = i18n("這個項目目前收藏在：{}", paths.joinToString(i18n("?"))),
                    color = colors.textDark,
                    fontSize = 14.sp,
                )
                Text(
                    text = tip,
                    color = colors.textDark.copy(alpha = 0.62f),
                    fontSize = 12.sp,
                )
            }
        },
        confirmButton = {
            FavoriteDialogButton(
                text = i18n("取消全部收藏"),
                background = colors.brownPrimary.copy(alpha = 0.1f),
                contentColor = colors.textStrong,
                onClick = onRemoveAll,
            )
        },
        dismissButton = {
            FavoriteDialogButton(
                text = i18n("否"),
                background = colors.textDark.copy(alpha = 0.06f),
                contentColor = colors.textDark.copy(alpha = 0.8f),
                onClick = onDismiss,
            )
        },
        containerColor = colors.creamSurface,
    )
}

private fun Set<Long>.toggle(id: Long): Set<Long> {
    return if (id in this) this - id else this + id
}

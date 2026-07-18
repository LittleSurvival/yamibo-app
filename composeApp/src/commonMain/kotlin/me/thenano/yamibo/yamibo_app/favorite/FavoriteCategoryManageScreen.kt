package me.thenano.yamibo.yamibo_app.favorite


import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.thenano.yamibo.yamibo_app.LocalFavoriteRepository
import me.thenano.yamibo.yamibo_app.components.navigation.YamiboTopBar
import me.thenano.yamibo.yamibo_app.favorite.components.*
import me.thenano.yamibo.yamibo_app.i18n.i18n
import me.thenano.yamibo.yamibo_app.navigation.LocalNavigator
import me.thenano.yamibo.yamibo_app.repository.FavoriteStoreRepository.FavoriteCategory
import me.thenano.yamibo.yamibo_app.repository.FavoriteStoreRepository.FavoriteCategoryDeletePreview
import me.thenano.yamibo.yamibo_app.components.theme.YamiboSnackbarHost
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme
import me.thenano.yamibo.yamibo_app.repository.FavoriteStoreRepository as FavoriteRepositoryContract

private val ManageRowHeight = 82.dp
private val ManageRowSpacing = 12.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FavoriteCategoryManageScreen() {
    val colors = YamiboTheme.colors
    val favoriteRepository = LocalFavoriteRepository.current
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val feedbackController = me.thenano.yamibo.yamibo_app.LocalAppFeedbackController.current
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val rowStridePx = with(density) { (ManageRowHeight + ManageRowSpacing).toPx() }
    val rowHeightPx = with(density) { ManageRowHeight.toPx() }

    var categories by remember { mutableStateOf<List<FavoriteCategory>>(emptyList()) }
    var draggingCategoryId by remember { mutableLongStateOf(0L) }
    var draggingIndex by remember { mutableIntStateOf(-1) }
    var draggingStartIndex by remember { mutableIntStateOf(-1) }
    var draggingTravelY by remember { mutableFloatStateOf(0f) }
    var draggingOverlayTop by remember { mutableFloatStateOf(0f) }
    val itemTopMap = remember { mutableMapOf<Long, Float>() }
    var dragItemTopSnapshot by remember { mutableStateOf<Map<Long, Float>>(emptyMap()) }
    var dragOrderedIds by remember { mutableStateOf<List<Long>>(emptyList()) }
    var animateGapOffsets by remember { mutableStateOf(true) }
    var containerRootTop by remember { mutableFloatStateOf(0f) }
    var deletePreview by remember { mutableStateOf<FavoriteCategoryDeletePreview?>(null) }
    var moveItemsToDefaultOnDelete by remember { mutableStateOf(true) }

    suspend fun reload() {
        categories = withContext(Dispatchers.Default) {
            favoriteRepository.ensureDefaults()
            favoriteRepository.getCategories()
        }
    }

    fun requestDelete(categoryId: Long) {
        scope.launch {
            val preview = withContext(Dispatchers.Default) {
                favoriteRepository.getCategoryDeletePreview(categoryId)
            } ?: return@launch
            if (preview.isDefaultCategory) {
                feedbackController.post(i18n("{}類別不可刪除", FavoriteRepositoryContract.DEFAULT_CATEGORY_NAME))
                return@launch
            }
            moveItemsToDefaultOnDelete = true
            deletePreview = preview
        }
    }

    fun finishDragging(commit: Boolean) {
        val draggedId = draggingCategoryId
        val startIndex = draggingStartIndex
        val targetIndex = draggingIndex
        if (commit && draggedId != 0L && startIndex >= 0 && targetIndex >= 0) {
            animateGapOffsets = false
            categories = reorderedList(categories, startIndex, targetIndex)
        }
        draggingCategoryId = 0L
        draggingIndex = -1
        draggingStartIndex = -1
        draggingTravelY = 0f
        draggingOverlayTop = 0f
        dragItemTopSnapshot = emptyMap()
        dragOrderedIds = emptyList()
        if (commit && draggedId != 0L && targetIndex >= 0) {
            scope.launch {
                withContext(Dispatchers.Default) {
                    favoriteRepository.moveCategoryToIndex(draggedId, targetIndex)
                }
                reload()
            }
        } else {
            scope.launch { reload() }
        }
    }

    LaunchedEffect(navigator.stack.size) { reload() }

    Scaffold(
        containerColor = colors.creamBackground,
        topBar = {
            YamiboTopBar(
                title = i18n("管理類別"),
                titleFontSize = 18,
                onBack = { navigator.pop() },
                actions = {
                    Surface(
                        onClick = { navigator.navigate(IFavoriteCategoryEditorScreen()) },
                        color = colors.brownPrimary.copy(alpha = 0.18f),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(
                            text = i18n("新增類別"),
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            )
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .onGloballyPositioned { coordinates ->
                    containerRootTop = coordinates.positionInRoot().y
                }
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(ManageRowSpacing),
            ) {
                itemsIndexed(categories, key = { _, category -> category.id }) { index, category ->
                    val reorderOffset = rememberReorderGapOffset(
                        key = category.id,
                        index = index,
                        draggingKey = draggingCategoryId,
                        draggingStartIndex = draggingStartIndex,
                        draggingTargetIndex = draggingIndex,
                        rowStridePx = rowStridePx,
                        animate = animateGapOffsets,
                    )
                    FavoriteCategoryCard(
                        category = category,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(ManageRowHeight)
                            .onGloballyPositioned { coordinates ->
                                itemTopMap[category.id] = coordinates.positionInRoot().y - containerRootTop
                            }
                            .graphicsLayer {
                                translationY = reorderOffset
                                alpha = if (draggingCategoryId == category.id) 0f else 1f
                            },
                        dragEnabled = true,
                        dragging = false,
                        onEdit = { navigator.navigate(IFavoriteCategoryEditorScreen(category.id)) },
                        onDelete = { requestDelete(category.id) },
                        onDragStart = {
                            animateGapOffsets = true
                            draggingCategoryId = category.id
                            draggingIndex = index
                            draggingStartIndex = index
                            draggingTravelY = 0f
                            dragItemTopSnapshot = itemTopMap.toMap()
                            dragOrderedIds = categories.map { it.id }
                            draggingOverlayTop = itemTopMap[category.id] ?: (index * rowStridePx)
                        },
                        onDragEnd = { finishDragging(commit = true) },
                        onDragCancel = { finishDragging(commit = false) },
                        onDrag = { _, dragY ->
                            draggingTravelY += dragY
                            if (draggingStartIndex == -1) return@FavoriteCategoryCard
                            draggingIndex = calculateReorderTargetIndex(
                                draggingId = category.id,
                                draggingOverlayTop = draggingOverlayTop,
                                draggingTravelY = draggingTravelY,
                                itemTopMap = dragItemTopSnapshot,
                                orderedIds = dragOrderedIds,
                                itemHeightPx = rowHeightPx,
                            )
                        },
                    )
                }
            }

            val draggingCategory = categories.firstOrNull { it.id == draggingCategoryId }
            if (draggingCategory != null) {
                FavoriteCategoryCard(
                    category = draggingCategory,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(ManageRowHeight)
                        .absoluteOffset(y = with(density) { (draggingOverlayTop + draggingTravelY).toDp() })
                        .zIndex(3f),
                    dragEnabled = false,
                    dragging = true,
                    onEdit = { navigator.navigate(IFavoriteCategoryEditorScreen(draggingCategory.id)) },
                    onDelete = { requestDelete(draggingCategory.id) },
                    onDragStart = {},
                    onDragEnd = {},
                    onDragCancel = {},
                    onDrag = { _, _ -> },
                )
            }
        }
    }

    if (deletePreview != null) {
        val preview = deletePreview!!
        AlertDialog(
            onDismissRequest = { deletePreview = null },
            confirmButton = {
                Surface(
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.Default) {
                                favoriteRepository.deleteCategory(
                                    preview.categoryId,
                                    moveItemsToDefaultOnDelete,
                                )
                            }
                            deletePreview = null
                            reload()
                        }
                    },
                    color = colors.brownDeep,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        text = i18n("刪除"),
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            },
            dismissButton = {
                Surface(
                    onClick = { deletePreview = null },
                    color = colors.brownPrimary.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, colors.brownPrimary.copy(alpha = 0.18f)),
                ) {
                    Text(
                        text = i18n("返回"),
                        color = colors.brownDeep,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            },
            title = {
                Text(i18n("刪除類別"), color = colors.textStrong, fontWeight = FontWeight.Bold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = i18n("「{}」目前包含 {} 筆收藏與 {} 個集合。",
                            preview.categoryName, preview.totalDistinctItemCount, preview.collectionCount),
                        color = colors.textDark,
                    )
                    Text(
                        text = i18n("直屬收藏 {} 筆，集合內收藏 {} 筆。", preview.directItemCount, preview.collectionItemCount),
                        color = colors.textDark.copy(alpha = 0.72f),
                        fontSize = 13.sp,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = moveItemsToDefaultOnDelete,
                            onCheckedChange = { moveItemsToDefaultOnDelete = it },
                            colors = CheckboxDefaults.colors(
                                checkedColor = colors.brownPrimary,
                                uncheckedColor = colors.brownPrimary.copy(alpha = 0.65f),
                                checkmarkColor = colors.creamBackground,
                            ),
                        )
                        Text(
                            text = i18n("將當前所有收藏移動到{}", FavoriteRepositoryContract.DEFAULT_CATEGORY_NAME),
                            color = colors.textDark,
                            fontSize = 14.sp,
                        )
                    }
                }
            },
            containerColor = colors.creamSurface,
        )
    }
}

@Composable
private fun FavoriteCategoryCard(
    category: FavoriteCategory,
    modifier: Modifier = Modifier,
    dragEnabled: Boolean,
    dragging: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    onDrag: (pointerY: Float, dragY: Float) -> Unit,
) {
    val colors = YamiboTheme.colors
    val isDefaultCategory = category.name == FavoriteRepositoryContract.DEFAULT_CATEGORY_NAME

    Surface(
        modifier = modifier.fastReorderDrag(
            enabled = dragEnabled,
            key = category.id,
            onDragStart = onDragStart,
            onDragEnd = onDragEnd,
            onDragCancel = onDragCancel,
            onDrag = onDrag,
        ),
        shape = RoundedCornerShape(20.dp),
        color = if (dragging) colors.creamSurface else colors.creamBackground,
        border = BorderStroke(
            1.dp,
            if (dragging) colors.brownDeep.copy(alpha = 0.45f) else colors.brownPrimary.copy(alpha = 0.16f),
        ),
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("⋮⋮", color = colors.textStrong.copy(alpha = 0.8f), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Box(modifier = Modifier.size(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(category.name, color = colors.textDark, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            ReorderActionChip(text = i18n("編輯"), onClick = onEdit)
            Box(modifier = Modifier.size(8.dp))
            if (isDefaultCategory) {
                Surface(
                    color = colors.brownPrimary.copy(alpha = 0.08f),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, colors.brownPrimary.copy(alpha = 0.14f)),
                ) {
                    Text(
                        text = FavoriteRepositoryContract.DEFAULT_CATEGORY_NAME,
                        color = colors.brownDeep.copy(alpha = 0.62f),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
            } else {
                ReorderActionChip(text = i18n("刪除"), onClick = onDelete, emphasized = true)
            }
        }
    }
}

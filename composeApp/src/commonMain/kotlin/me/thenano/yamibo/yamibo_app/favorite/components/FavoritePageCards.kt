package me.thenano.yamibo.yamibo_app.favorite.components

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import me.thenano.yamibo.yamibo_app.components.feedback.resolvedContentCoverUrl
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme
import me.thenano.yamibo.yamibo_app.favorite.FavoriteGridEntry
import me.thenano.yamibo.yamibo_app.i18n.i18n
import me.thenano.yamibo.yamibo_app.repository.FavoriteStoreRepository.FavoriteCollectionWithItems
import me.thenano.yamibo.yamibo_app.repository.FavoriteStoreRepository.FavoriteItem
import me.thenano.yamibo.yamibo_app.repository.settings.FavoriteGridMode
import me.thenano.yamibo.yamibo_app.util.rememberImageRequest
import me.thenano.yamibo.yamibo_app.util.time.formatRelativeTime

@Composable
internal fun FavoriteGridEntryCard(
    entry: FavoriteGridEntry,
    favoriteGridMode: FavoriteGridMode,
    selecting: Boolean,
    selectedItemIds: Set<Long>,
    selectedCollectionIds: Set<Long>,
    lastReadMap: Map<Long, Long>,
    onOpenCollection: (Long) -> Unit,
    onToggleCollection: (Long) -> Unit,
    onEnterSelectCollection: (Long) -> Unit,
    onOpenItem: (FavoriteItem) -> Unit,
    onToggleItem: (Long) -> Unit,
    onEnterSelectItem: (Long) -> Unit,
) {
    Box {
        when (entry) {
            is FavoriteGridEntry.Collection -> {
                if (favoriteGridMode == FavoriteGridMode.ROW_CARD || favoriteGridMode == FavoriteGridMode.ROW_CARD_TEXT) {
                    CollectionRowCardUi(entry.value, favoriteGridMode == FavoriteGridMode.ROW_CARD, entry.value.collection.id in selectedCollectionIds, selecting, { onOpenCollection(entry.value.collection.id) }, { onToggleCollection(entry.value.collection.id) }, { onEnterSelectCollection(entry.value.collection.id) })
                } else {
                    CollectionCardUi(entry.value, entry.value.collection.id in selectedCollectionIds, selecting, { onOpenCollection(entry.value.collection.id) }, { onToggleCollection(entry.value.collection.id) }, { onEnterSelectCollection(entry.value.collection.id) })
                }
            }
            is FavoriteGridEntry.Item -> {
                if (favoriteGridMode == FavoriteGridMode.ROW_CARD || favoriteGridMode == FavoriteGridMode.ROW_CARD_TEXT) {
                    ItemRowCardUi(entry.value, favoriteGridMode == FavoriteGridMode.ROW_CARD, entry.value.id in selectedItemIds, selecting, lastReadMap[entry.value.id], { onOpenItem(entry.value) }, { onToggleItem(entry.value.id) }, { onEnterSelectItem(entry.value.id) })
                } else {
                    ItemCardUi(entry.value, entry.value.id in selectedItemIds, selecting, lastReadMap[entry.value.id], { onOpenItem(entry.value) }, { onToggleItem(entry.value.id) }, { onEnterSelectItem(entry.value.id) })
                }
            }
        }
    }
}

@Composable
internal fun CollectionCardUi(collection: FavoriteCollectionWithItems, selected: Boolean, selecting: Boolean, onOpen: () -> Unit, onToggle: () -> Unit, onEnterSelect: () -> Unit) {
    val colors = YamiboTheme.colors
    val borderColor = if (selected) colors.brownDeep else collectionColor(collection.collection.colorKey).copy(alpha = 0.45f)
    Surface(
        modifier = Modifier.fillMaxWidth().pointerInput(selecting, selected, collection.collection.id) {
            detectTapGestures(onTap = { if (selecting) onToggle() else onOpen() }, onLongPress = { if (selecting) onToggle() else onEnterSelect() })
        },
        shape = RoundedCornerShape(16.dp),
        color = colors.creamSurface,
        border = BorderStroke(1.5.dp, borderColor),
    ) {
        Column(Modifier.padding(6.dp)) {
            PreviewGrid(collection.items, collection.collection.colorKey)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(collection.collection.name, color = colors.textStrong, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                AnimatedVisibility(visible = selected, enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) { SelectedDot() }
            }
            Spacer(Modifier.height(4.dp))
            Text(i18n("{} 項", collection.items.size), color = colors.textDark.copy(alpha = 0.55f), fontSize = 11.sp)
        }
    }
}

@Composable
internal fun PreviewGrid(items: List<FavoriteItem>, colorKey: String) {
    val previewItems = items.take(4)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(2) { rowIndex ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(2) { columnIndex ->
                    val item = previewItems.getOrNull(rowIndex * 2 + columnIndex)
                    Box(Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(10.dp)).background(collectionColor(colorKey).copy(alpha = 0.18f))) {
                        val coverUrl = item?.let {
                            resolvedContentCoverUrl(it.targetType, it.targetId, it.coverUrl)
                        }
                        if (coverUrl != null) {
                            AsyncImage(model = rememberImageRequest(coverUrl), contentDescription = item.title, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        } else if (item != null) {
                            CoverTextFallback(title = item.title, color = collectionColor(colorKey))
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ItemCardUi(item: FavoriteItem, selected: Boolean, selecting: Boolean, lastReadAt: Long?, onOpen: () -> Unit, onToggle: () -> Unit, onEnterSelect: () -> Unit) {
    val colors = YamiboTheme.colors
    val effectiveLastReadAt = lastReadAt?.takeIf { it > 0L }
    val effectiveLastUpdatedAt = item.lastUpdatedTime?.takeIf { it > 0L }
    Surface(
        modifier = Modifier.fillMaxWidth().pointerInput(selecting, selected, item.id) {
            detectTapGestures(onTap = { if (selecting) onToggle() else onOpen() }, onLongPress = { if (selecting) onToggle() else onEnterSelect() })
        },
        shape = RoundedCornerShape(16.dp),
        color = colors.creamSurface,
        border = BorderStroke(1.5.dp, if (selected) colors.brownDeep else colors.brownPrimary.copy(alpha = 0.28f)),
    ) {
        Column(Modifier.padding(6.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(0.72f).clip(RoundedCornerShape(12.dp)).background(colors.brownPrimary.copy(alpha = 0.12f))) {
                FavoriteItemCover(item)
                androidx.compose.animation.AnimatedVisibility(
                    visible = selected,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                    enter = fadeIn() + scaleIn(),
                    exit = fadeOut() + scaleOut(),
                ) { SelectedDot() }
            }
            Spacer(Modifier.height(8.dp))
            item.forumName?.takeIf { it.isNotBlank() }?.let {
                Text("#$it", color = colors.textDark.copy(alpha = 0.58f), fontSize = 11.sp)
                Spacer(Modifier.height(4.dp))
            }
            Text(item.title, color = colors.textDark, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            val timingSummary = buildTimingSummary(effectiveLastReadAt, effectiveLastUpdatedAt)
            if (timingSummary != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    timingSummary,
                    color = colors.textDark.copy(alpha = 0.45f),
                    fontSize = 10.sp,
                    minLines = 1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun CollectionRowCardUi(collection: FavoriteCollectionWithItems, showPreview: Boolean, selected: Boolean, selecting: Boolean, onOpen: () -> Unit, onToggle: () -> Unit, onEnterSelect: () -> Unit) {
    val colors = YamiboTheme.colors
    val borderColor = if (selected) colors.brownDeep else collectionColor(collection.collection.colorKey).copy(alpha = 0.45f)
    Surface(
        modifier = Modifier.fillMaxWidth().pointerInput(selecting, selected, collection.collection.id) {
            detectTapGestures(onTap = { if (selecting) onToggle() else onOpen() }, onLongPress = { if (selecting) onToggle() else onEnterSelect() })
        },
        shape = RoundedCornerShape(18.dp),
        color = colors.creamSurface,
        border = BorderStroke(1.5.dp, borderColor),
    ) {
        Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showPreview) {
                Box(Modifier.width(108.dp).aspectRatio(1f).clip(RoundedCornerShape(14.dp)).background(collectionColor(collection.collection.colorKey).copy(alpha = 0.16f))) {
                    PreviewGrid(collection.items, collection.collection.colorKey)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(collection.collection.name, color = colors.textStrong, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(i18n("{} 項收藏", collection.items.size), color = colors.textDark.copy(alpha = 0.62f), fontSize = 13.sp)
                collection.items.firstOrNull()?.forumName?.takeIf { it.isNotBlank() }?.let {
                    Text("#$it", color = colors.textDark.copy(alpha = 0.52f), fontSize = 12.sp)
                }
            }
            AnimatedVisibility(visible = selected, enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) { SelectedDot() }
        }
    }
}

@Composable
internal fun ItemRowCardUi(item: FavoriteItem, showCover: Boolean, selected: Boolean, selecting: Boolean, lastReadAt: Long?, onOpen: () -> Unit, onToggle: () -> Unit, onEnterSelect: () -> Unit) {
    val colors = YamiboTheme.colors
    val effectiveLastReadAt = lastReadAt?.takeIf { it > 0L }
    val effectiveLastUpdatedAt = item.lastUpdatedTime?.takeIf { it > 0L }
    val timingSummary = buildTimingSummary(effectiveLastReadAt, effectiveLastUpdatedAt)
    Surface(
        modifier = Modifier.fillMaxWidth().pointerInput(selecting, selected, item.id) {
            detectTapGestures(onTap = { if (selecting) onToggle() else onOpen() }, onLongPress = { if (selecting) onToggle() else onEnterSelect() })
        },
        shape = RoundedCornerShape(18.dp),
        color = colors.creamSurface,
        border = BorderStroke(1.5.dp, if (selected) colors.brownDeep else colors.brownPrimary.copy(alpha = 0.28f)),
    ) {
        Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showCover) {
                Box(Modifier.width(92.dp).aspectRatio(0.72f).clip(RoundedCornerShape(14.dp)).background(colors.brownPrimary.copy(alpha = 0.12f))) {
                    FavoriteItemCover(item)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(item.title, color = colors.textDark, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (!showCover && (!item.forumName.isNullOrBlank() || effectiveLastReadAt != null || effectiveLastUpdatedAt != null)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        val forumModifier = if (timingSummary != null) Modifier.weight(1f) else Modifier
                        item.forumName?.takeIf { it.isNotBlank() }?.let {
                            Text(
                                "#$it",
                                color = colors.textDark.copy(alpha = 0.56f),
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = forumModifier
                            )
                        }
                        if (timingSummary != null) {
                            if (!item.forumName.isNullOrBlank()) {
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(
                                timingSummary,
                                color = colors.textDark.copy(alpha = 0.48f),
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                if (showCover) {
                    item.forumName?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            "#$it",
                            color = colors.textDark.copy(alpha = 0.56f),
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (showCover && timingSummary != null) {
                    Text(
                        timingSummary,
                        color = colors.textDark.copy(alpha = 0.48f),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            AnimatedVisibility(visible = selected, enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) { SelectedDot() }
        }
    }
}

@Composable
private fun FavoriteItemCover(item: FavoriteItem) {
    val colors = YamiboTheme.colors
    val coverUrl = resolvedContentCoverUrl(item.targetType, item.targetId, item.coverUrl)
    if (coverUrl != null) {
        AsyncImage(
            model = rememberImageRequest(coverUrl),
            contentDescription = item.title,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
    } else {
        CoverTextFallback(title = item.title, color = colors.brownDeep.copy(alpha = 0.75f))
    }
}

@Composable
internal fun SelectedDot() {
    val colors = YamiboTheme.colors
    Box(Modifier.size(20.dp).background(colors.brownDeep, CircleShape), contentAlignment = Alignment.Center) {
        Text("✓", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun CoverTextFallback(title: String, color: Color) {
    val fontSize = when {
        title.length <= 6 -> 32.sp
        title.length <= 12 -> 24.sp
        title.length <= 24 -> 19.sp
        title.length <= 40 -> 15.sp
        else -> 12.sp
    }
    Text(
        text = title,
        color = color,
        fontSize = fontSize,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.fillMaxSize().padding(8.dp),
        lineHeight = fontSize * 1.15f,
        textAlign = TextAlign.Center,
        softWrap = true,
    )
}

private fun buildTimingSummary(effectiveLastReadAt: Long?, effectiveLastUpdatedAt: Long?): String? {
    return buildString {
        if (effectiveLastReadAt != null) {
            append(i18n("最近閱讀 {}", formatFavoriteTime(effectiveLastReadAt)))
        }
        if (effectiveLastUpdatedAt != null) {
            if (isNotEmpty()) append(" / ")
            append(i18n("最後更新 {}", formatFavoriteTime(effectiveLastUpdatedAt)))
        }
    }.takeIf { it.isNotBlank() }
}

internal fun formatFavoriteTime(timestamp: Long): String {
    if (timestamp <= 0L) return "-"
    return formatRelativeTime(timestamp)
}

fun collectionColor(colorKey: String): Color = when (colorKey) {
    "rose" -> Color(0xFFD28B9C)
    "blue" -> Color(0xFF89A8C9)
    "green" -> Color(0xFF8FAE8A)
    "gold" -> Color(0xFFD6B46F)
    else -> Color(0xFFB4977A)
}

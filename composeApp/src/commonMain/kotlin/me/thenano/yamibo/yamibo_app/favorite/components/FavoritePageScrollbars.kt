package me.thenano.yamibo.yamibo_app.favorite.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun FavoriteListScrollbar(
    state: LazyListState,
    totalItems: Int,
    modifier: Modifier = Modifier,
) {
    FavoriteScrollbarState(
        stateKey = state,
        modifier = modifier,
        totalItems = totalItems,
        firstVisibleIndex = { state.firstVisibleItemIndex },
        visibleCount = { state.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1) },
        isScrollInProgress = { state.isScrollInProgress },
        onScrollToIndex = { index -> state.scrollToItem(index) },
    )
}

@Composable
fun FavoriteGridScrollbar(
    state: LazyGridState,
    totalItems: Int,
    modifier: Modifier = Modifier,
) {
    FavoriteScrollbarState(
        stateKey = state,
        modifier = modifier,
        totalItems = totalItems,
        firstVisibleIndex = { state.firstVisibleItemIndex },
        visibleCount = { state.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1) },
        isScrollInProgress = { state.isScrollInProgress },
        onScrollToIndex = { index -> state.scrollToItem(index) },
    )
}

@Composable
fun FavoriteStaggeredScrollbar(
    state: LazyStaggeredGridState,
    totalItems: Int,
    modifier: Modifier = Modifier,
) {
    FavoriteScrollbarState(
        stateKey = state,
        modifier = modifier,
        totalItems = totalItems,
        firstVisibleIndex = { state.firstVisibleItemIndex },
        visibleCount = { state.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1) },
        isScrollInProgress = { state.isScrollInProgress },
        onScrollToIndex = { index -> state.scrollToItem(index) },
    )
}

@Composable
private fun FavoriteScrollbarState(
    stateKey: Any,
    totalItems: Int,
    firstVisibleIndex: () -> Int,
    visibleCount: () -> Int,
    isScrollInProgress: () -> Boolean,
    onScrollToIndex: suspend (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentFirstVisibleIndex by remember(stateKey) {
        derivedStateOf(firstVisibleIndex)
    }
    val currentVisibleCount by remember(stateKey) {
        derivedStateOf(visibleCount)
    }

    androidx.compose.runtime.key(stateKey) {
        FavoriteScrollbar(
            modifier = modifier,
            totalItems = totalItems,
            firstVisibleIndex = currentFirstVisibleIndex,
            visibleCount = currentVisibleCount,
            isScrollInProgress = isScrollInProgress(),
            onScrollToIndex = onScrollToIndex,
        )
    }
}

@Composable
private fun FavoriteScrollbar(
    totalItems: Int,
    firstVisibleIndex: Int,
    visibleCount: Int,
    isScrollInProgress: Boolean,
    onScrollToIndex: suspend (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (totalItems <= visibleCount || totalItems <= 0) return

    val colors = YamiboTheme.colors
    val scrollRequests = remember { Channel<Int>(Channel.CONFLATED) }
    val latestScroll by rememberUpdatedState(onScrollToIndex)
    LaunchedEffect(scrollRequests) {
        for (index in scrollRequests) {
            latestScroll(index)
            // At most one expensive lazy layout per frame; intermediate targets are replaced.
            androidx.compose.runtime.withFrameNanos { }
        }
    }

    // Track whether the user is actively dragging the thumb
    var isDragging by remember { mutableStateOf(false) }
    var keepVisible by remember { mutableStateOf(false) }
    var dragStartThumbTopPx by remember { mutableStateOf(0f) }
    var dragOffsetPx by remember { mutableStateOf(0f) }
    var dragVisibleCount by remember { mutableStateOf(visibleCount) }

    LaunchedEffect(isScrollInProgress, isDragging) {
        if (isScrollInProgress || isDragging) {
            keepVisible = true
        } else {
            delay(1200.milliseconds)
            keepVisible = false
        }
    }

    // Animate thumb width: wider when dragging for better visibility
    val thumbWidth = remember { Animatable(6f) }
    LaunchedEffect(isDragging) {
        thumbWidth.animateTo(
            targetValue = if (isDragging) 10f else 6f,
            animationSpec = tween(durationMillis = 150),
        )
    }

    if (!keepVisible) return

    BoxWithConstraints(
        modifier = modifier
            .fillMaxHeight()
            .width(36.dp)
            .padding(end = 4.dp, top = 10.dp, bottom = 92.dp),
    ) {
        val trackHeightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val effectiveVisibleCount = if (isDragging) dragVisibleCount else visibleCount
        val hiddenCount = (totalItems - effectiveVisibleCount).coerceAtLeast(1)
        val thumbHeightPx = (trackHeightPx * (effectiveVisibleCount.toFloat() / totalItems.toFloat()))
            .coerceAtLeast(with(LocalDensity.current) { 48.dp.toPx() })
            .coerceAtMost(trackHeightPx * 0.5f)
        val travelPx = (trackHeightPx - thumbHeightPx).coerceAtLeast(0f)
        val progress = (firstVisibleIndex.toFloat() / hiddenCount.toFloat()).coerceIn(0f, 1f)
        val thumbOffsetPx = travelPx * progress
        val latestThumbOffset by rememberUpdatedState(thumbOffsetPx)
        val latestHiddenCount by rememberUpdatedState(hiddenCount)
        val latestVisibleCount by rememberUpdatedState(visibleCount)

        fun scrollFromThumbTop(thumbTop: Float) {
            val ratio = (thumbTop / travelPx.coerceAtLeast(1f)).coerceIn(0f, 1f)
            val targetIndex = (ratio * latestHiddenCount.toFloat()).roundToInt().coerceIn(0, totalItems - 1)
            scrollRequests.trySend(targetIndex)
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
        ) {
            // Track background — wider for better visibility
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(thumbWidth.value.dp)
                    .background(colors.brownPrimary.copy(alpha = 0.10f), RoundedCornerShape(999.dp)),
            )
            // Thumb — wider, taller minimum, more opaque for easy touch targeting
            Box(
                modifier = Modifier
                    .offset { IntOffset(0, (if (isDragging) (dragStartThumbTopPx + dragOffsetPx).coerceIn(0f, travelPx) else thumbOffsetPx).roundToInt()) }
                    .align(Alignment.TopEnd)
                    .width(36.dp)
                    .height(with(LocalDensity.current) { thumbHeightPx.toDp() })
                    .pointerInput(totalItems, travelPx) {
                        detectDragGestures(
                            onDragStart = {
                                dragVisibleCount = latestVisibleCount
                                isDragging = true
                                dragStartThumbTopPx = latestThumbOffset
                                dragOffsetPx = 0f
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                dragOffsetPx += dragAmount.y
                                scrollFromThumbTop(dragStartThumbTopPx + dragOffsetPx)
                            },
                            onDragEnd = { isDragging = false },
                            onDragCancel = { isDragging = false },
                        )
                    },
                contentAlignment = Alignment.TopEnd,
            ) {
                Box(
                    modifier = Modifier
                        .width(thumbWidth.value.dp)
                        .fillMaxHeight()
                        .background(colors.brownDeep.copy(alpha = 0.80f), RoundedCornerShape(999.dp)),
                )
            }
        }
    }
}

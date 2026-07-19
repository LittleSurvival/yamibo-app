package me.thenano.yamibo.yamibo_app.thread.reader.components

import YamiboIcons
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.littlesurvival.dto.page.Post
import io.github.littlesurvival.dto.value.PostId
import me.thenano.yamibo.yamibo_app.components.text.rememberConvertedText
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme
import me.thenano.yamibo.yamibo_app.i18n.i18n
import me.thenano.yamibo.yamibo_app.repository.ChapterStateRepository
import me.thenano.yamibo.yamibo_app.repository.download.DownloadQueueEntry
import me.thenano.yamibo.yamibo_app.repository.download.DownloadStatus
import me.thenano.yamibo.yamibo_app.thread.components.catalogDownloadLabel
import me.thenano.yamibo.yamibo_app.thread.components.chapterPercentProgressLabel

/** Catalog drawer panel showing pages and post-entries */
@Composable
internal fun ReaderCatalogPanel(
    totalPages: Int,
    loadedPostsByPage: Map<Int, List<Post>>,
    currentPage: Int,
    currentPid: PostId?,
    bookmarkedPostIds: Set<Long> = emptySet(),
    readPostIds: Set<Long> = emptySet(),
    downloadEntriesByPage: Map<Int, DownloadQueueEntry> = emptyMap(),
    chapterStates: Map<Long, ChapterStateRepository.Entry> = emptyMap(),
    onPageOrPostClick: (Int, Post?) -> Unit,
    onDownload: () -> Unit,
    onPostLongPress: (Post) -> Unit = {},
    drawerOpen: Boolean = false,
) {
    val colors = YamiboTheme.colors
    var expandedPages by remember { mutableStateOf(setOf(currentPage)) }

    // Auto expand current page when it changes
    LaunchedEffect(currentPage) {
        if (!expandedPages.contains(currentPage)) {
            expandedPages = expandedPages + currentPage
        }
    }

    val listState = rememberLazyListState()
    val density = androidx.compose.ui.platform.LocalDensity.current
    var hasScrolled by remember { mutableStateOf(false) }

    LaunchedEffect(drawerOpen) {
        if (drawerOpen) {
            hasScrolled = false
        }
    }

    LaunchedEffect(currentPid) {
        hasScrolled = false
    }

    val isCurrentPageLoaded = loadedPostsByPage.containsKey(currentPage)
    LaunchedEffect(currentPid, isCurrentPageLoaded, hasScrolled, expandedPages) {
        if (currentPid == null || !isCurrentPageLoaded || hasScrolled) return@LaunchedEffect

        var targetIndex = -1
        var currentPageHeaderIndex = 0
        var currentIndex = 0
        for (page in 1..totalPages) {
            val isExpanded = expandedPages.contains(page)
            val isLoaded = loadedPostsByPage.containsKey(page)

            if (page == currentPage) {
                currentPageHeaderIndex = currentIndex
            }
            currentIndex++ // page header

            if (isExpanded) {
                if (!isLoaded) {
                    currentIndex++ // page_loading
                } else {
                    val pagePosts = loadedPostsByPage[page] ?: emptyList()
                    for ((pid) in pagePosts) {
                        if (pid == currentPid) {
                            targetIndex = currentIndex
                            break
                        }
                        currentIndex++
                    }
                    if (targetIndex != -1) break
                }
            }
        }

        val scrollToIndex = if (targetIndex != -1) targetIndex else currentPageHeaderIndex
        if (scrollToIndex != -1) {
            val viewportHeight = listState.layoutInfo.viewportEndOffset - listState.layoutInfo.viewportStartOffset
            val offset = if (viewportHeight > 0) {
                val itemHeight = 50 * density.density
                -((viewportHeight - itemHeight) / 2).toInt()
            } else {
                -300
            }
            listState.scrollToItem(scrollToIndex, offset)
            hasScrolled = true
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(colors.creamBackground).systemBarsPadding()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.brownDeep)
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = i18n("目錄"),
                color = colors.textOnDeepHigh,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            IconButton(
                onClick = onDownload,
                modifier = Modifier.align(Alignment.CenterEnd),
            ) {
                Icon(
                    imageVector = YamiboIcons.Download,
                    contentDescription = i18n("下載"),
                    tint = colors.textOnDeepHigh,
                )
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize()
        ) {
            for (page in 1..totalPages) {
                val isExpanded = expandedPages.contains(page)
                val isLoaded = loadedPostsByPage.containsKey(page)
                val downloadEntry = downloadEntriesByPage[page]

                // 1. Page Header Item
                item(key = "page_header_$page") {
                    val rotation by animateFloatAsState(
                        targetValue = if (isExpanded) 180f else 0f,
                        label = "page_chevron"
                    )
                    Surface(
                        color = if (isExpanded) colors.brownLight.copy(alpha = 0.1f) else colors.creamBackground,
                        onClick = {
                            expandedPages = if (isExpanded) expandedPages - page else expandedPages + page
                            if (!isLoaded) {
                                onPageOrPostClick(page, null)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = i18n("第 {} 頁", page),
                                    color = colors.brownPrimary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp
                                )
                                if (downloadEntry != null && downloadEntry.status != DownloadStatus.NotDownloaded) {
                                    Spacer(Modifier.width(8.dp))
                                    Icon(
                                        imageVector = YamiboIcons.Downloaded,
                                        contentDescription = null,
                                        tint = if (downloadEntry.status == DownloadStatus.Failed) colors.redAccent else colors.orangeAccent,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        text = catalogDownloadLabel(downloadEntry),
                                        color = if (downloadEntry.status == DownloadStatus.Failed) colors.redAccent else colors.orangeAccent,
                                        fontSize = 12.sp,
                                    )
                                }
                            }
                            Text(
                                text = "▲",
                                modifier = Modifier.graphicsLayer { rotationZ = rotation },
                                color = colors.brownPrimary.copy(alpha = 0.6f),
                                fontSize = 12.sp
                            )
                        }
                    }
                    HorizontalDivider(color = colors.brownPrimary.copy(alpha = 0.2f))
                }

                // 2. Page Content Items
                if (isExpanded) {
                    if (!isLoaded) {
                        item(key = "page_loading_$page") {
                            Box(
                                modifier = Modifier.fillMaxWidth().height(60.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    color = colors.brownPrimary,
                                    strokeWidth = 2.dp
                                )
                            }
                            HorizontalDivider(color = colors.brownPrimary.copy(alpha = 0.2f))
                        }
                    } else {
                        val pagePosts = loadedPostsByPage[page] ?: emptyList()
                        items(pagePosts, key = { "post_${page}_${it.pid.value}" }) { post ->
                            val isCurrentPost = post.pid == currentPid
                            val isBookmarked = post.pid.value.toLong() in bookmarkedPostIds
                            val chapterState = chapterStates[post.pid.value.toLong()]
                            val isRead = post.pid.value.toLong() in readPostIds || chapterState?.read == true
                            val progressText = chapterState?.chapterPercentProgressLabel()
                            val displayTitle = rememberConvertedText(post.title.ifEmpty { "..." })
                            Surface(
                                color = if (isCurrentPost) colors.brownLight.copy(alpha = 0.15f) else colors.creamSurface,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .alpha(if (isRead) 0.6f else 1f)
                                    .pointerInput(page, post.pid) {
                                        detectTapGestures(
                                            onTap = { onPageOrPostClick(page, post) },
                                            onLongPress = { onPostLongPress(post) },
                                        )
                                    }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .let {
                                            if (isCurrentPost) {
                                                it.drawBehind {
                                                    drawRect(
                                                        color = colors.brownPrimary,
                                                        size = size.copy(width = 4.dp.toPx())
                                                    )
                                                }
                                            } else it
                                        }
                                        .padding(
                                            horizontal = 24.dp,
                                            vertical = if (isCurrentPost) 14.dp else 12.dp
                                        ),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "${post.floor}#",
                                        color = if (isCurrentPost) colors.textStrong else colors.brownPrimary,
                                        fontWeight = if (isCurrentPost) FontWeight.ExtraBold else FontWeight.Bold,
                                        fontSize = if (isCurrentPost) 16.sp else 14.sp,
                                        modifier = Modifier.width(if (isCurrentPost) 48.dp else 40.dp)
                                    )
                                    if (isBookmarked) {
                                        Icon(
                                            imageVector = YamiboIcons.Bookmark,
                                            contentDescription = null,
                                            tint = colors.orangeAccent,
                                            modifier = Modifier.size(12.dp)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                    }
                                    Text(
                                        text = displayTitle,
                                        modifier = Modifier.weight(1f),
                                        color = if (isCurrentPost) colors.textStrong else colors.textDark,
                                        fontWeight = if (isCurrentPost) FontWeight.ExtraBold else FontWeight.Normal,
                                        fontSize = if (isCurrentPost) 16.sp else 14.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (!isRead && progressText != null) {
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = progressText,
                                            color = colors.orangeAccent,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                        )
                                    }
                                }
                            }
                            HorizontalDivider(
                                color = colors.brownLight.copy(alpha = 0.1f),
                                modifier = Modifier.padding(start = 24.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

package me.thenano.yamibo.yamibo_app.history

import me.thenano.yamibo.yamibo_app.i18n.i18n

import YamiboIcons
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.thenano.yamibo.yamibo_app.LocalFavoriteRepository
import me.thenano.yamibo.yamibo_app.favorite.FavoriteLocationSelection
import me.thenano.yamibo.yamibo_app.favorite.FavoriteTargetPayload
import me.thenano.yamibo.yamibo_app.favorite.getFavoriteLocationSelection
import me.thenano.yamibo.yamibo_app.history.components.PageMode
import me.thenano.yamibo.yamibo_app.history.components.ReadHistoryCard
import me.thenano.yamibo.yamibo_app.history.components.RssSearchHistoryCard
import me.thenano.yamibo.yamibo_app.history.components.TagMangaHistoryCard
import me.thenano.yamibo.yamibo_app.history.components.formatTime
import me.thenano.yamibo.yamibo_app.navigation.ComposableNavigator
import me.thenano.yamibo.yamibo_app.repository.ContentCoverRepository
import me.thenano.yamibo.yamibo_app.repository.ReadHistoryRepository
import me.thenano.yamibo.yamibo_app.repository.ReadHistoryRepository.ThreadReadingHistory
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme
import me.thenano.yamibo.yamibo_app.thread.detail.novel.INovelThreadDetailScreen
import me.thenano.yamibo.yamibo_app.thread.detail.rss.IRssSearchSubscriptionDetailScreen
import me.thenano.yamibo.yamibo_app.thread.detail.tag.ITagDetailScreen
import me.thenano.yamibo.yamibo_app.thread.reader.IImageReaderScreen
import me.thenano.yamibo.yamibo_app.thread.reader.IThreadReaderScreen

@Composable
internal fun LoadingContent() {
    val colors = YamiboTheme.colors
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = colors.brownPrimary, modifier = Modifier.size(32.dp))
    }
}

@Composable
internal fun EmptyContent(mode: PageMode) {
    val colors = YamiboTheme.colors
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = YamiboIcons.History,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = colors.brownPrimary.copy(alpha = 0.3f),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = if (mode == PageMode.Search) i18n("搜尋無結果") else i18n("目前還沒有閱讀歷史紀錄"),
            fontSize = 16.sp,
            color = colors.textDark.copy(alpha = 0.5f),
        )
    }
}

@Composable
internal fun ThreadHistoryItem(
    history: ThreadReadingHistory,
    pageMode: PageMode,
    selectedItems: Set<ReadHistoryRepository.AnyReadingHistory>,
    onToggleSelection: () -> Unit,
    onDelete: () -> Unit,
    onFavorite: () -> Unit,
    onFavoriteLongPress: () -> Unit,
    favoriteRefreshToken: Int,
    navigator: ComposableNavigator,
) {
    val favoriteRepository = LocalFavoriteRepository.current
    val target = remember(history) {
        FavoriteTargetPayload.Thread(
            tid = history.threadId,
            title = history.threadName,
            threadType = history.threadType,
            authorId = history.authorId,
            coverUrl = history.threadCover,
            lastUpdatedTime = history.lastUpdatedTime,
            forumId = history.forumId,
            forumName = history.forumName,
        )
    }
    val favoriteSelection by produceState<FavoriteLocationSelection?>(
        initialValue = null,
        target,
        favoriteRefreshToken,
    ) {
        value = favoriteRepository.getFavoriteLocationSelection(target)
    }

    ReadHistoryCard(
        history = history,
        timeLabel = formatTime(history.lastVisitTime),
        isSelectMode = pageMode == PageMode.Select,
        isSelected = history in selectedItems,
        isFavorited = favoriteSelection?.item != null,
        onClick = {
            if (pageMode == PageMode.Select) {
                onToggleSelection()
            } else {
                navigator.navigate(
                    IThreadReaderScreen(
                        tid = history.threadId,
                        title = history.threadName,
                        threadType = history.threadType,
                        authorId = history.authorId,
                        initialPage = history.page,
                    )
                )
            }
        },
        onCoverClick = {
            if (pageMode == PageMode.Select) {
                onToggleSelection()
            } else if (history.threadType == ReadHistoryRepository.ThreadEntryType.Novel) {
                navigator.navigate(
                    INovelThreadDetailScreen(
                        tid = history.threadId,
                        title = history.threadName,
                        authorId = history.authorId,
                    )
                )
            } else {
                navigator.navigate(
                    IThreadReaderScreen(
                        tid = history.threadId,
                        title = history.threadName,
                        threadType = history.threadType,
                        authorId = history.authorId,
                        initialPage = history.page,
                    )
                )
            }
        },
        onDelete = onDelete,
        onFavorite = onFavorite,
        onFavoriteLongPress = onFavoriteLongPress,
    )
}

@Composable
internal fun TagHistoryItem(
    history: ReadHistoryRepository.TagMangaReadingHistory,
    pageMode: PageMode,
    selectedItems: Set<ReadHistoryRepository.AnyReadingHistory>,
    onToggleSelection: () -> Unit,
    onDelete: () -> Unit,
    onFavorite: () -> Unit,
    onFavoriteLongPress: () -> Unit,
    favoriteRefreshToken: Int,
    navigator: ComposableNavigator,
) {
    val favoriteRepository = LocalFavoriteRepository.current
    val target = remember(history) {
        FavoriteTargetPayload.TagManga(
            tagId = history.tagId,
            tagName = history.tagName,
            coverUrl = history.coverUrl,
        )
    }
    val favoriteSelection by produceState<FavoriteLocationSelection?>(
        initialValue = null,
        target,
        favoriteRefreshToken,
    ) {
        value = favoriteRepository.getFavoriteLocationSelection(target)
    }

    TagMangaHistoryCard(
        history = history,
        timeLabel = formatTime(history.lastVisitTime),
        modeLabel = i18n("漫畫模式"),
        isSelectMode = pageMode == PageMode.Select,
        isSelected = history in selectedItems,
        isFavorited = favoriteSelection?.item != null,
        onClick = {
            if (pageMode == PageMode.Select) {
                onToggleSelection()
            } else {
                navigator.navigate(
                    IImageReaderScreen(
                        tid = history.threadId,
                        postId = null,
                        fid = null,
                        threadTitle = history.threadTitle,
                        authorId = null,
                        imageList = emptyList(),
                        tagId = history.tagId,
                        tagName = history.tagName,
                        tagPage = history.tagPage,
                        tagThreads = emptyList(),
                        initialPage = history.threadImagePageIndex + 1,
                    )
                )
            }
        },
        onCoverClick = {
            if (pageMode == PageMode.Select) {
                onToggleSelection()
            } else {
                navigator.navigate(
                    ITagDetailScreen(
                        tagId = history.tagId,
                        title = history.tagName,
                        page = history.tagPage,
                    )
                )
            }
        },
        onDelete = onDelete,
        onFavorite = onFavorite,
        onFavoriteLongPress = onFavoriteLongPress,
    )
}

@Composable
internal fun TagCatalogHistoryItem(
    history: ReadHistoryRepository.TagCatalogReadingHistory,
    pageMode: PageMode,
    selectedItems: Set<ReadHistoryRepository.AnyReadingHistory>,
    onToggleSelection: () -> Unit,
    onDelete: () -> Unit,
    onFavorite: () -> Unit,
    onFavoriteLongPress: () -> Unit,
    favoriteRefreshToken: Int,
    navigator: ComposableNavigator,
) {
    val favoriteRepository = LocalFavoriteRepository.current
    val target = remember(history) {
        FavoriteTargetPayload.TagManga(
            tagId = history.tagId,
            tagName = history.tagName,
            coverUrl = history.coverUrl,
        )
    }
    val favoriteSelection by produceState<FavoriteLocationSelection?>(
        initialValue = null,
        target,
        favoriteRefreshToken,
    ) {
        value = favoriteRepository.getFavoriteLocationSelection(target)
    }
    val displayHistory = remember(history) {
        ReadHistoryRepository.TagMangaReadingHistory(
            tagId = history.tagId,
            tagName = history.tagName,
            tagPage = history.tagPage,
            threadId = history.threadId,
            threadTitle = history.threadTitle,
            threadImagePageIndex = 0,
            threadImageTotalPages = 1,
            lastVisitTime = history.lastVisitTime,
            coverUrl = history.coverUrl,
        )
    }

    TagMangaHistoryCard(
        history = displayHistory,
        timeLabel = formatTime(history.lastVisitTime),
        progressText = i18n("第{} ‧ {}", history.threadPage, history.postTitle.ifBlank { history.threadTitle }),
        isSelectMode = pageMode == PageMode.Select,
        isSelected = history in selectedItems,
        isFavorited = favoriteSelection?.item != null,
        onClick = {
            if (pageMode == PageMode.Select) {
                onToggleSelection()
            } else {
                navigator.navigate(
                    IThreadReaderScreen(
                        tid = history.threadId,
                        title = history.threadTitle,
                        threadType = ReadHistoryRepository.ThreadEntryType.Normal,
                        authorId = history.authorId,
                        initialPage = history.threadPage,
                        targetPid = history.postId,
                        catalogCoverTargetType = ContentCoverRepository.TargetType.TagManga,
                        catalogCoverTargetId = history.tagId.value.toLong(),
                        catalogTagId = history.tagId,
                        catalogTagName = history.tagName,
                        catalogTagPage = history.tagPage,
                    )
                )
            }
        },
        onCoverClick = {
            if (pageMode == PageMode.Select) {
                onToggleSelection()
            } else {
                navigator.navigate(ITagDetailScreen(history.tagId, history.tagName, history.tagPage))
            }
        },
        onDelete = onDelete,
        onFavorite = onFavorite,
        onFavoriteLongPress = onFavoriteLongPress,
    )
}

@Composable
internal fun RssHistoryItem(
    history: ReadHistoryRepository.RssSearchReadingHistory,
    pageMode: PageMode,
    selectedItems: Set<ReadHistoryRepository.AnyReadingHistory>,
    onToggleSelection: () -> Unit,
    onDelete: () -> Unit,
    onFavorite: () -> Unit,
    onFavoriteLongPress: () -> Unit,
    favoriteRefreshToken: Int,
    navigator: ComposableNavigator,
) {
    val favoriteRepository = LocalFavoriteRepository.current
    val target = remember(history) {
        FavoriteTargetPayload.RssSearch(
            subscriptionId = history.subscriptionId,
            title = history.subscriptionTitle,
            coverUrl = history.coverUrl,
        )
    }
    val favoriteSelection by produceState<FavoriteLocationSelection?>(
        initialValue = null,
        target,
        favoriteRefreshToken,
    ) {
        value = favoriteRepository.getFavoriteLocationSelection(target)
    }

    RssSearchHistoryCard(
        history = history,
        timeLabel = formatTime(history.lastVisitTime),
        modeLabel = i18n("漫畫模式"),
        isSelectMode = pageMode == PageMode.Select,
        isSelected = history in selectedItems,
        isFavorited = favoriteSelection?.item != null,
        onClick = {
            if (pageMode == PageMode.Select) {
                onToggleSelection()
            } else {
                navigator.navigate(
                    IImageReaderScreen(
                        tid = history.threadId,
                        postId = null,
                        fid = null,
                        threadTitle = history.threadTitle,
                        authorId = null,
                        imageList = emptyList(),
                        initialPage = history.threadImagePageIndex + 1,
                        rssSubscriptionId = history.subscriptionId,
                        rssTitle = history.subscriptionTitle,
                        rssQuery = history.subscriptionQuery,
                        rssPage = history.subscriptionPage,
                        rssThreads = emptyList(),
                    )
                )
            }
        },
        onCoverClick = {
            if (pageMode == PageMode.Select) {
                onToggleSelection()
            } else {
                navigator.navigate(
                    IRssSearchSubscriptionDetailScreen(
                        subscriptionId = history.subscriptionId,
                        page = history.subscriptionPage,
                    )
                )
            }
        },
        onDelete = onDelete,
        onFavorite = onFavorite,
        onFavoriteLongPress = onFavoriteLongPress,
    )
}

@Composable
internal fun RssCatalogHistoryItem(
    history: ReadHistoryRepository.RssCatalogReadingHistory,
    pageMode: PageMode,
    selectedItems: Set<ReadHistoryRepository.AnyReadingHistory>,
    onToggleSelection: () -> Unit,
    onDelete: () -> Unit,
    onFavorite: () -> Unit,
    onFavoriteLongPress: () -> Unit,
    favoriteRefreshToken: Int,
    navigator: ComposableNavigator,
) {
    val favoriteRepository = LocalFavoriteRepository.current
    val target = remember(history) {
        FavoriteTargetPayload.RssSearch(
            subscriptionId = history.subscriptionId,
            title = history.subscriptionTitle,
            coverUrl = history.coverUrl,
        )
    }
    val favoriteSelection by produceState<FavoriteLocationSelection?>(
        initialValue = null,
        target,
        favoriteRefreshToken,
    ) {
        value = favoriteRepository.getFavoriteLocationSelection(target)
    }
    val displayHistory = remember(history) {
        ReadHistoryRepository.RssSearchReadingHistory(
            subscriptionId = history.subscriptionId,
            subscriptionTitle = history.subscriptionTitle,
            subscriptionQuery = history.subscriptionQuery,
            subscriptionPage = history.subscriptionPage,
            threadId = history.threadId,
            threadTitle = history.threadTitle,
            threadImagePageIndex = 0,
            threadImageTotalPages = 1,
            lastVisitTime = history.lastVisitTime,
            coverUrl = history.coverUrl,
        )
    }

    RssSearchHistoryCard(
        history = displayHistory,
        timeLabel = formatTime(history.lastVisitTime),
        progressText = i18n("第{} ‧ {}", history.threadPage, history.postTitle.ifBlank { history.threadTitle }),
        isSelectMode = pageMode == PageMode.Select,
        isSelected = history in selectedItems,
        isFavorited = favoriteSelection?.item != null,
        onClick = {
            if (pageMode == PageMode.Select) {
                onToggleSelection()
            } else {
                navigator.navigate(
                    IThreadReaderScreen(
                        tid = history.threadId,
                        title = history.threadTitle,
                        threadType = ReadHistoryRepository.ThreadEntryType.Normal,
                        authorId = history.authorId,
                        initialPage = history.threadPage,
                        targetPid = history.postId,
                        catalogCoverTargetType = ContentCoverRepository.TargetType.RssSearch,
                        catalogCoverTargetId = history.subscriptionId,
                        catalogRssSubscriptionId = history.subscriptionId,
                        catalogRssTitle = history.subscriptionTitle,
                        catalogRssQuery = history.subscriptionQuery,
                        catalogRssPage = history.subscriptionPage,
                    )
                )
            }
        },
        onCoverClick = {
            if (pageMode == PageMode.Select) {
                onToggleSelection()
            } else {
                navigator.navigate(IRssSearchSubscriptionDetailScreen(history.subscriptionId, history.subscriptionPage))
            }
        },
        onDelete = onDelete,
        onFavorite = onFavorite,
        onFavoriteLongPress = onFavoriteLongPress,
    )
}


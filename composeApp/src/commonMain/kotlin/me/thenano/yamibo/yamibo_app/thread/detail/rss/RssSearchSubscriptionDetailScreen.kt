package me.thenano.yamibo.yamibo_app.thread.detail.rss

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import coil3.compose.LocalPlatformContext
import io.github.littlesurvival.YamiboForum
import io.github.littlesurvival.core.YamiboResult
import io.github.littlesurvival.dto.model.PageNav
import io.github.littlesurvival.dto.model.ThreadSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.thenano.yamibo.yamibo_app.*
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme
import me.thenano.yamibo.yamibo_app.favorite.*
import me.thenano.yamibo.yamibo_app.i18n.i18n
import me.thenano.yamibo.yamibo_app.navigation.LocalNavigator
import me.thenano.yamibo.yamibo_app.profile.settings.ISettingsCategoryScreen
import me.thenano.yamibo.yamibo_app.repository.*
import me.thenano.yamibo.yamibo_app.repository.download.RssMangaChapterDownloadKey
import me.thenano.yamibo.yamibo_app.thread.detail.components.*
import me.thenano.yamibo.yamibo_app.thread.detail.novel.INovelThreadDetailScreen
import me.thenano.yamibo.yamibo_app.thread.detail.novel.components.ThreadErrorContent
import me.thenano.yamibo.yamibo_app.thread.reader.IImageReaderScreen
import me.thenano.yamibo.yamibo_app.thread.reader.IThreadReaderScreen
import me.thenano.yamibo.yamibo_app.util.shareText
import me.thenano.yamibo.yamibo_app.util.state

private sealed interface RssDetailState {
    data object Loading : RssDetailState
    data class Success(val page: RssSearchSubscriptionRepository.CatalogPage) : RssDetailState
    data class Error(val message: String) : RssDetailState
}

@Composable
fun RssSearchSubscriptionDetailScreen(
    subscriptionId: Long,
    initialPage: Int? = null,
) {
    val colors = YamiboTheme.colors
    val navigator = LocalNavigator.current
    val repository = LocalRssSearchSubscriptionRepository.current
    val favoriteRepository = LocalFavoriteRepository.current
    val favoriteSyncRepository = LocalFavoriteSyncRepository.current
    val detailNoteRepository = LocalDetailNoteRepository.current
    val downloadRepository = LocalDownloadRepository.current
    val downloadQueue by downloadRepository.queue.collectAsState()
    val bookMarkRepository = LocalBookMarkRepository.current
    val chapterStateRepository = LocalChapterStateRepository.current
    val historyRepo = LocalReadHistoryRepository.current
    val contentCoverRepository = LocalContentCoverRepository.current
    val appSettingsRepo = LocalAppSettingsRepository.current
    val imageReaderModeOverrideRepository = LocalImageReaderModeOverrideRepository.current
    val platformContext = LocalPlatformContext.current
    val feedbackController = LocalAppFeedbackController.current
    val appTaskManager = LocalAppTaskManager.current
    val scope = rememberCoroutineScope()
    val stackSize = navigator.stack.size

    var state by remember { mutableStateOf<RssDetailState>(RssDetailState.Loading) }
    var currentPage by remember { mutableIntStateOf(initialPage ?: 1) }
    var isRefreshing by remember { mutableStateOf(false) }
    var detailNote by remember { mutableStateOf<DetailNoteRepository.DetailNote?>(null) }
    var showNoteDialog by remember { mutableStateOf(false) }
    var rssHistory by remember { mutableStateOf<ReadHistoryRepository.RssSearchReadingHistory?>(null) }
    var rssCatalogHistory by remember { mutableStateOf<ReadHistoryRepository.RssCatalogReadingHistory?>(null) }
    var threadBookMarkEntries by remember { mutableStateOf<Map<Long, BookMarkRepository.Entry>>(emptyMap()) }
    var threadChapterStates by remember { mutableStateOf<Map<Long, ChapterStateRepository.Entry>>(emptyMap()) }
    var actionThread by remember { mutableStateOf<ThreadSummary?>(null) }
    var isFavorited by remember { mutableStateOf(false) }
    var favoriteRefreshToken by remember { mutableIntStateOf(0) }
    var showFavoriteDialog by remember { mutableStateOf(false) }
    var favoriteDialogCategorySelection by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var favoriteDialogSelection by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var favoriteDialogCategories by remember {
        mutableStateOf<List<FavoriteStoreRepository.FavoriteCategory>>(emptyList())
    }
    var favoriteDialogOptions by remember {
        mutableStateOf<List<FavoriteStoreRepository.FavoriteCollectionOption>>(emptyList())
    }
    var pendingFavoriteRemovalSelection by remember { mutableStateOf<FavoriteLocationSelection?>(null) }
    var showFavoriteRemovalConfirm by remember { mutableStateOf(false) }
    var showFavoriteMultiPathDialog by remember { mutableStateOf(false) }
    var showDownloadSheet by remember { mutableStateOf(false) }
    var downloadSheetOpenedAfterFavorite by remember { mutableStateOf(false) }

    val isMangaMode = appSettingsRepo.isMangaMode.state()
    val favoriteAddDownloadPromptEnabled = appSettingsRepo.favoriteAddDownloadPromptEnabled.state()
    val longStripModeEnabled by imageReaderModeOverrideRepository
        .observeRssLongStrip(subscriptionId)
        .collectAsState(false)
    val coverKey = remember(subscriptionId) {
        ContentCoverRepository.Key(ContentCoverRepository.TargetType.RssSearch, subscriptionId)
    }
    val canonicalCover by contentCoverRepository.observeCover(coverKey).collectAsState(null)

    fun rssMangaKey(thread: ThreadSummary): RssMangaChapterDownloadKey =
        RssMangaChapterDownloadKey(subscriptionId, thread.tid.value, thread.author?.uid?.value)

    val rssDownloadEntries = remember(downloadQueue, subscriptionId) {
        downloadQueue
            .mapNotNull { entry ->
                val key = entry.key as? RssMangaChapterDownloadKey ?: return@mapNotNull null
                if (key.subscriptionId == subscriptionId) key.tid.toLong() to entry else null
            }
            .toMap()
    }

    suspend fun ensureDownloadStorageReady(): Boolean {
        if (downloadRepository.isStorageReady()) return true
        feedbackController.post(i18n("尚未設定下載資料夾"))
        navigator.navigate(ISettingsCategoryScreen("storage"))
        return false
    }

    fun launchDownloadTask(action: String, operation: suspend () -> Unit) {
        appTaskManager.launch(
            key = me.thenano.yamibo.yamibo_app.task.AppTaskKey("download:rss:$subscriptionId:$action"),
            operation = operation,
        )
    }

    fun currentSubscription(): RssSearchSubscriptionRepository.SubscriptionSummary? =
        (state as? RssDetailState.Success)?.page?.subscription

    fun currentTitle(): String =
        currentSubscription()?.title ?: rssHistory?.subscriptionTitle ?: i18n("RSS 目錄")

    fun currentQuery(): String =
        currentSubscription()?.query ?: rssHistory?.subscriptionQuery ?: currentTitle()

    fun rssCoverUrl(): String? =
        canonicalCover?.resolvedUrl ?: rssHistory?.coverUrl ?: rssCatalogHistory?.coverUrl

    fun rssLastUpdatedTime(): Long? =
        currentSubscription()?.lastRefreshFinishedAt?.takeIf { it > 0L }

    fun rssBadgeText(): String {
        val forumName = currentSubscription()?.forumName?.takeIf { it.isNotBlank() }
        return forumName?.let { i18n("RSS搜尋目錄(#{})", it) } ?: i18n("RSS搜尋目錄")
    }

    fun favoriteTarget(): FavoriteTargetPayload.RssSearch =
        FavoriteTargetPayload.RssSearch(
            subscriptionId = subscriptionId,
            title = currentTitle(),
            coverUrl = rssCoverUrl(),
            lastUpdatedTime = rssLastUpdatedTime(),
        )

    suspend fun reloadNote() {
        detailNote = detailNoteRepository.getNote(DetailNoteRepository.TargetType.RssSearch, subscriptionId)
    }

    suspend fun reloadThreadBookMarks() {
        threadBookMarkEntries = bookMarkRepository
            .getEntriesByParent(BookMarkRepository.TargetType.RssSearchThread, subscriptionId)
            .associateBy { it.targetId }
    }

    suspend fun reloadThreadChapterStates() {
        threadChapterStates = chapterStateRepository
            .getEntriesByParent(ChapterStateRepository.TargetType.RssSearchThread, subscriptionId)
            .associateBy { it.targetId }
    }

    suspend fun refreshFavoriteState() {
        isFavorited = favoriteRepository.getFavoriteLocationSelection(favoriteTarget()).item != null
    }

    suspend fun loadPage(page: Int, preferCache: Boolean = true) {
        currentPage = page
        state = RssDetailState.Loading
        val cached = if (preferCache) repository.getCachedCatalogPage(subscriptionId, page) else null
        val catalog = cached ?: repository.getCatalogPage(subscriptionId, page)
        state = catalog?.let { RssDetailState.Success(it) }
            ?: RssDetailState.Error(i18n("RSS 訂閱不存在或無法載入"))
    }

    LaunchedEffect(subscriptionId) {
        loadPage(currentPage)
        reloadNote()
    }

    LaunchedEffect(subscriptionId, stackSize) {
        reloadThreadBookMarks()
        reloadThreadChapterStates()
        rssHistory = historyRepo.getRssSearchReaderModeHistoryPosition(subscriptionId)
        rssCatalogHistory = historyRepo.getRssCatalogThreadHistoryPosition(subscriptionId)
        rssHistory?.coverUrl?.let { contentCoverRepository.setAutomaticCover(coverKey, it) }
        if (rssHistory?.coverUrl == null) {
            rssCatalogHistory?.coverUrl?.let { contentCoverRepository.setAutomaticCover(coverKey, it) }
        }
    }

    LaunchedEffect(subscriptionId, favoriteRefreshToken, canonicalCover?.resolvedUrl, state) {
        refreshFavoriteState()
        runCatching { favoriteRepository.syncFavoriteMetadata(favoriteTarget()) }
            .onFailure { Logger.w("RssSearchSubscriptionDetailScreen", "Failed to sync favorite metadata subscriptionId=$subscriptionId", it) }
    }

    fun navigateToThread(thread: ThreadSummary, threads: List<ThreadSummary>, pageNav: PageNav?) {
        val fid = thread.fid
        val isManga = isCatalogMangaThread(thread)
        val isNovel = fid?.let { YamiboForum.isNovelForum(it) } == true ||
            thread.tag?.let { YamiboForum.isNovelForum(it) } == true
        if (isMangaMode && isManga) {
            val threadState = threadChapterStates[thread.tid.value.toLong()]
            val threadHistory = rssHistory?.takeIf { it.threadId == thread.tid }
            val isCompleted = threadState?.read == true
            val initialImagePage = if (isCompleted) {
                1
            } else {
                threadState?.lastPageIndex?.plus(1)
                    ?: threadHistory?.threadImagePageIndex?.plus(1)
                    ?: 1
            }
            navigator.navigate(
                IImageReaderScreen(
                    tid = thread.tid,
                    postId = null,
                    fid = fid,
                    threadTitle = thread.title,
                    imageList = emptyList(),
                    initialPage = initialImagePage.coerceAtLeast(1),
                    loadHistory = !isCompleted && threadHistory != null,
                    authorId = thread.author?.uid,
                    rssSubscriptionId = subscriptionId,
                    rssTitle = currentTitle(),
                    rssQuery = currentQuery(),
                    rssThreads = threads,
                    rssPage = currentPage,
                    rssTotalPages = pageNav?.totalPages ?: 1,
                )
            )
        } else if (isNovel) {
            navigator.navigate(INovelThreadDetailScreen(thread.tid, thread.title, thread.author?.uid))
        } else {
            navigator.navigate(
                IThreadReaderScreen(
                    tid = thread.tid,
                    title = thread.title,
                    catalogCoverTargetType = ContentCoverRepository.TargetType.RssSearch,
                    catalogCoverTargetId = subscriptionId,
                    catalogRssSubscriptionId = subscriptionId,
                    catalogRssTitle = currentTitle(),
                    catalogRssQuery = currentQuery(),
                    catalogRssPage = currentPage,
                )
            )
        }
    }

    fun handleContinueRead(threads: List<ThreadSummary>, pageNav: PageNav?) {
        val history = rssHistory
        if (history != null && isMangaMode) {
            scope.launch {
                val historyThreads = if (history.subscriptionPage == currentPage && threads.any { it.tid == history.threadId }) {
                    threads
                } else {
                    repository.getCachedCatalogPage(subscriptionId, history.subscriptionPage)
                        ?.tagPage
                        ?.threadSummaries
                        .orEmpty()
                        .ifEmpty {
                            repository.getCatalogPage(subscriptionId, history.subscriptionPage)
                                ?.tagPage
                                ?.threadSummaries
                                .orEmpty()
                        }
                        .ifEmpty { threads }
                }
                val threadIndex = historyThreads.indexOfFirst { it.tid == history.threadId }
                val historyThread = historyThreads.getOrNull(threadIndex)
                if (historyThread?.let(::isCatalogMangaThread) == true) {
                    navigator.navigate(
                        IImageReaderScreen(
                            tid = history.threadId,
                            postId = null,
                            fid = historyThread.fid,
                            threadTitle = history.threadTitle,
                            imageList = emptyList(),
                            initialPage = 1,
                            loadHistory = true,
                            authorId = historyThread.author?.uid,
                            rssSubscriptionId = subscriptionId,
                            rssTitle = currentTitle(),
                            rssQuery = currentQuery(),
                            rssThreads = historyThreads,
                            rssPage = history.subscriptionPage,
                            rssTotalPages = pageNav?.totalPages ?: 1,
                        )
                    )
                } else {
                    navigator.navigate(
                        IThreadReaderScreen(
                            tid = history.threadId,
                            title = history.threadTitle,
                            authorId = historyThread?.author?.uid,
                            catalogCoverTargetType = ContentCoverRepository.TargetType.RssSearch,
                            catalogCoverTargetId = subscriptionId,
                            catalogRssSubscriptionId = subscriptionId,
                            catalogRssTitle = currentTitle(),
                            catalogRssQuery = currentQuery(),
                            catalogRssPage = history.subscriptionPage,
                        )
                    )
                }
            }
        } else if (rssCatalogHistory != null) {
            val catalogHistory = rssCatalogHistory ?: return
            navigator.navigate(
                IThreadReaderScreen(
                    tid = catalogHistory.threadId,
                    title = catalogHistory.threadTitle,
                    authorId = catalogHistory.authorId,
                    initialPage = catalogHistory.threadPage,
                    targetPid = catalogHistory.postId,
                    catalogCoverTargetType = ContentCoverRepository.TargetType.RssSearch,
                    catalogCoverTargetId = subscriptionId,
                    catalogRssSubscriptionId = subscriptionId,
                    catalogRssTitle = currentTitle(),
                    catalogRssQuery = currentQuery(),
                    catalogRssPage = catalogHistory.subscriptionPage,
                )
            )
        } else {
            threads.firstOrNull()?.let { navigateToThread(it, threads, pageNav) }
        }
    }

    val activeReadingHistory = if (isMangaMode) rssHistory else rssCatalogHistory
    val readingProgressText = remember(rssHistory, rssCatalogHistory, isMangaMode) {
        if (isMangaMode) {
            val h = rssHistory ?: return@remember null
            i18n("第{} ‧ {} ‧ {}/{}", h.subscriptionPage, h.threadTitle, h.threadImagePageIndex + 1, h.threadImageTotalPages)
        } else {
            val h = rssCatalogHistory ?: return@remember null
            i18n("第{} ‧ {}", h.threadPage, h.threadTitle)
        }
    }

    Scaffold(
        containerColor = colors.creamBackground,
        topBar = { CatalogDetailTopBar(title = currentTitle(), onBack = { navigator.pop() }) },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
                .background(colors.creamBackground)
        ) {
            when (val currentState = state) {
                RssDetailState.Loading -> CatalogLoadingSkeleton()
                is RssDetailState.Error -> ThreadErrorContent(
                    message = currentState.message,
                    onRetry = {
                        state = RssDetailState.Loading
                        scope.launch { loadPage(currentPage, preferCache = false) }
                    },
                )
                is RssDetailState.Success -> PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = {
                        isRefreshing = true
                        scope.launch {
                            loadPage(currentPage, preferCache = false)
                            isRefreshing = false
                            feedbackController.post(i18n("RSS 已刷新"))
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    val page = currentState.page.tagPage
                    CatalogDetailContent(
                        tagPage = page,
                        tagName = currentTitle(),
                        displayTitle = "#${currentTitle()}",
                        badgeText = rssBadgeText(),
                        coverUrl = rssCoverUrl(),
                        isMangaMode = isMangaMode,
                        onMangaModeChange = { appSettingsRepo.isMangaMode.setValue(it) },
                        dynamicCoverEnabled = canonicalCover?.dynamicEnabled ?: true,
                        onDynamicCoverEnabledChange = { enabled ->
                            scope.launch { contentCoverRepository.setDynamicEnabled(coverKey, enabled) }
                        },
                        longStripModeEnabled = longStripModeEnabled,
                        onLongStripModeEnabledChange = { enabled ->
                            imageReaderModeOverrideRepository.setRssLongStrip(subscriptionId, enabled)
                        },
                        hasReadingHistory = activeReadingHistory != null,
                        readingProgressText = readingProgressText,
                        onContinueRead = { handleContinueRead(page.threadSummaries, page.pageNav) },
                        isFavorited = isFavorited,
                        onFavorite = {
                            scope.launch {
                                val target = favoriteTarget()
                                val selection = favoriteRepository.getFavoriteLocationSelection(target)
                                if (selection.item == null) {
                                    val creationOutcome = favoriteRepository.saveFavoriteWithOutcome(target)
                                    favoriteRefreshToken += 1
                                    feedbackController.post(i18n("已加入收藏"))
                                    if (creationOutcome == FavoriteCreationOutcome.Created && favoriteAddDownloadPromptEnabled) {
                                        downloadSheetOpenedAfterFavorite = true
                                        showDownloadSheet = true
                                    }
                                } else {
                                    pendingFavoriteRemovalSelection = selection
                                    if (appSettingsRepo.skipFavoriteRemovalConfirm.getValue()) {
                                        withContext(Dispatchers.Default) { removeFavoriteWithSync(favoriteRepository, favoriteSyncRepository, target) }
                                        favoriteRefreshToken += 1
                                        feedbackController.post(i18n("已移除收藏"))
                                    } else {
                                        showFavoriteRemovalConfirm = true
                                    }
                                }
                            }
                        },
                        onFavoriteLongPress = {
                            scope.launch {
                                val target = favoriteTarget()
                                val selection = favoriteRepository.getFavoriteLocationSelection(target)
                                favoriteDialogCategories = favoriteRepository.getCategories()
                                favoriteDialogOptions = favoriteRepository.getCollectionOptions()
                                favoriteDialogCategorySelection = selection.categoryIds
                                favoriteDialogSelection = selection.collectionIds
                                showFavoriteDialog = true
                            }
                        },
                        onShare = {
                            shareText(platformContext, "RSS: ${currentTitle()}", currentTitle())
                        },
                        showDownloadAction = isMangaMode,
                        onDownload = {
                            downloadSheetOpenedAfterFavorite = false
                            showDownloadSheet = true
                        },
                        noteContent = detailNote?.content.orEmpty(),
                        onNoteClick = { showNoteDialog = true },
                        onPageChange = { pageIndex ->
                            scope.launch { loadPage(pageIndex) }
                        },
                        onThreadClick = { thread -> navigateToThread(thread, page.threadSummaries, page.pageNav) },
                        bookmarkedThreadIds = threadBookMarkEntries.values.filter { it.bookmarked }.mapTo(mutableSetOf()) { it.targetId },
                        readThreadIds = currentState.page.readThreadIds,
                        downloadEntries = rssDownloadEntries,
                        chapterStates = threadChapterStates,
                        historyThreadId = if (isMangaMode) {
                            rssHistory?.threadId?.value?.toLong()
                        } else {
                            rssCatalogHistory?.threadId?.value?.toLong()
                        },
                        historyThreadCompleted = isMangaMode && rssHistory?.let {
                            it.threadImageTotalPages > 0 && it.threadImagePageIndex >= it.threadImageTotalPages - 1
                        } == true,
                        historyThreadProgressText = if (isMangaMode) rssHistory?.let {
                            catalogHistoryProgressLabel(it.threadImagePageIndex, it.threadImageTotalPages)
                        } else rssCatalogHistory?.let { i18n("第{}頁", it.threadPage) },
                        onThreadLongPress = { actionThread = it },
                    )
                }
            }
        }
    }

    if (showNoteDialog) {
        DetailNoteEditorDialog(
            initialContent = detailNote?.content.orEmpty(),
            onDismiss = { showNoteDialog = false },
            onSave = { content ->
                showNoteDialog = false
                scope.launch {
                    detailNoteRepository.saveNote(DetailNoteRepository.TargetType.RssSearch, subscriptionId, content = content)
                    reloadNote()
                    feedbackController.post(if (content.isBlank()) i18n("已刪除筆記") else i18n("已保存筆記"))
                }
            },
            onDelete = {
                showNoteDialog = false
                scope.launch {
                    detailNoteRepository.deleteNote(DetailNoteRepository.TargetType.RssSearch, subscriptionId)
                    reloadNote()
                    feedbackController.post(i18n("已刪除筆記"), duration = me.thenano.yamibo.yamibo_app.feedback.AppFeedbackDuration.Short)
                }
            },
        )
    }

    actionThread?.let { thread ->
        val entry = threadBookMarkEntries[thread.tid.value.toLong()]
        val chapterState = threadChapterStates[thread.tid.value.toLong()]
        val downloadEntry = rssDownloadEntries[thread.tid.value.toLong()]
        val downloadKey = rssMangaKey(thread)
        CatalogThreadActionDialog(
            bookmarked = entry?.bookmarked == true,
            read = chapterState?.read == true,
            hasProgress = chapterState?.hasProgress == true,
            showDownloadActions = isMangaMode,
            downloadStatus = downloadEntry?.status,
            onDismiss = { actionThread = null },
            onToggleBookMark = {
                actionThread = null
                scope.launch {
                    val next = entry?.bookmarked != true
                    bookMarkRepository.setBookmarked(
                        BookMarkRepository.TargetType.RssSearchThread,
                        subscriptionId,
                        thread.tid.value.toLong(),
                        thread.title,
                        next,
                    )
                    reloadThreadBookMarks()
                    feedbackController.post(if (next) i18n("已新增書籤") else i18n("已移除書籤"))
                }
            },
            onMarkRead = {
                actionThread = null
                scope.launch {
                    chapterStateRepository.setRead(ChapterStateRepository.TargetType.RssSearchThread, subscriptionId, thread.tid.value.toLong(), thread.title, true)
                    repository.markRead(subscriptionId, thread.tid.value.toLong())
                    reloadThreadChapterStates()
                    feedbackController.post(i18n("已標為已讀"))
                }
            },
            onMarkUnread = {
                actionThread = null
                scope.launch {
                    chapterStateRepository.setRead(ChapterStateRepository.TargetType.RssSearchThread, subscriptionId, thread.tid.value.toLong(), thread.title, false)
                    repository.markUnread(subscriptionId, thread.tid.value.toLong())
                    reloadThreadChapterStates()
                    feedbackController.post(i18n("已標為未讀"))
                }
            },
            onClearHistory = {
                actionThread = null
                scope.launch {
                    val threadId = thread.tid.value.toLong()
                    if (isMangaMode) {
                        chapterStateRepository.clearTarget(ChapterStateRepository.TargetType.RssSearchThread, subscriptionId, threadId)
                        repository.markUnread(subscriptionId, threadId)
                        if (rssHistory?.threadId == thread.tid) {
                            historyRepo.deleteRssSearchHistory(subscriptionId)
                            rssHistory = null
                        }
                    } else if (rssCatalogHistory?.threadId == thread.tid) {
                        historyRepo.deleteRssCatalogThreadHistory(subscriptionId)
                        rssCatalogHistory = null
                    }
                    reloadThreadChapterStates()
                    loadPage(currentPage, preferCache = true)
                    feedbackController.post(i18n("已清除閱讀紀錄"))
                }
            },
            onDownloadChapter = {
                actionThread = null
                launchDownloadTask("chapter:${thread.tid.value}") {
                    if (!ensureDownloadStorageReady()) return@launchDownloadTask
                    downloadRepository.enqueueRssMangaChapter(subscriptionId, currentTitle(), currentQuery(), thread, currentPage)
                        .onFailure { error ->
                            Logger.e("RssSearchSubscriptionDetailScreen", "Failed to enqueue RSS manga chapter subscriptionId=$subscriptionId tid=${thread.tid.value}", error)
                            feedbackController.post(error.message ?: i18n("加入下載失敗"))
                        }
                }
            },
            onRefreshChapter = {
                actionThread = null
                launchDownloadTask("refresh:${thread.tid.value}") {
                    if (!ensureDownloadStorageReady()) return@launchDownloadTask
                    when (val result = downloadRepository.refreshRssMangaChapter(downloadKey)) {
                        is YamiboResult.Success -> feedbackController.post(i18n("已重新整理此章"))
                        else -> feedbackController.post(result.message())
                    }
                }
            },
            onRetryChapter = {
                actionThread = null
                launchDownloadTask("retry:${thread.tid.value}") {
                    if (!ensureDownloadStorageReady()) return@launchDownloadTask
                    downloadRepository.retry(downloadKey)
                }
            },
            onClearChapterDownload = {
                actionThread = null
                launchDownloadTask("clear:${thread.tid.value}") {
                    downloadRepository.clearRssMangaChapter(downloadKey)
                    feedbackController.post(i18n("已清除此章下載"))
                }
            },
        )
    }

    if (showDownloadSheet) {
        val currentState = state as? RssDetailState.Success
        val currentThreads = currentState?.page?.tagPage?.threadSummaries.orEmpty()
        val currentPageHasDownloads = currentThreads.any { rssDownloadEntries.containsKey(it.tid.value.toLong()) }
        CatalogDownloadActionSheet(
            title = i18n("RSS 漫畫下載"),
            onDismiss = {
                showDownloadSheet = false
                downloadSheetOpenedAfterFavorite = false
            },
            actions = buildList {
                add(CatalogDownloadAction(i18n("下載目前分頁")) {
                    showDownloadSheet = false
                    downloadSheetOpenedAfterFavorite = false
                    launchDownloadTask("current-page:$currentPage") {
                        if (!ensureDownloadStorageReady()) return@launchDownloadTask
                        downloadRepository.enqueueRssMangaCurrentPage(subscriptionId, currentTitle(), currentQuery(), currentThreads, currentPage)
                            .onFailure { error ->
                                Logger.e("RssSearchSubscriptionDetailScreen", "Failed to enqueue current RSS manga page subscriptionId=$subscriptionId page=$currentPage", error)
                                feedbackController.post(error.message ?: i18n("加入下載失敗"))
                            }
                    }
                })
                add(CatalogDownloadAction(i18n("下載全部分頁")) {
                    showDownloadSheet = false
                    downloadSheetOpenedAfterFavorite = false
                    launchDownloadTask("all-pages") {
                        if (!ensureDownloadStorageReady()) return@launchDownloadTask
                        downloadRepository.enqueueRssMangaAllPages(subscriptionId, currentTitle(), currentQuery())
                            .onFailure { error ->
                                Logger.e("RssSearchSubscriptionDetailScreen", "Failed to enqueue all RSS manga pages subscriptionId=$subscriptionId", error)
                                feedbackController.post(error.message ?: i18n("加入下載失敗"))
                            }
                    }
                })
                if (currentPageHasDownloads) {
                    add(CatalogDownloadAction(i18n("清除目前分頁下載")) {
                        showDownloadSheet = false
                        downloadSheetOpenedAfterFavorite = false
                        launchDownloadTask("clear-page:$currentPage") {
                            currentThreads.forEach { downloadRepository.clearRssMangaChapter(rssMangaKey(it)) }
                            feedbackController.post(i18n("已清除目前分頁下載"))
                        }
                    })
                }
                if (rssDownloadEntries.isNotEmpty()) {
                    add(CatalogDownloadAction(i18n("清除整個 RSS 下載")) {
                        showDownloadSheet = false
                        downloadSheetOpenedAfterFavorite = false
                        launchDownloadTask("clear-all") {
                            downloadRepository.clearRssManga(subscriptionId)
                            feedbackController.post(i18n("已清除整個 RSS 下載"))
                        }
                    })
                }
            },
            doNotAskAgain = if (downloadSheetOpenedAfterFavorite) {
                !favoriteAddDownloadPromptEnabled
            } else {
                null
            },
            onDoNotAskAgainChange = if (downloadSheetOpenedAfterFavorite) {
                { checked -> appSettingsRepo.favoriteAddDownloadPromptEnabled.setValue(!checked) }
            } else {
                null
            },
        )
    }

    if (showFavoriteDialog) {
        val target = favoriteTarget()
        FavoriteCollectionPickerDialog(
            categories = favoriteDialogCategories,
            options = favoriteDialogOptions,
            initialCategorySelection = favoriteDialogCategorySelection,
            initialCollectionSelection = favoriteDialogSelection,
            onDismiss = { showFavoriteDialog = false },
            onEdit = {
                showFavoriteDialog = false
                navigator.navigate(IFavoriteCategoryManageScreen())
            },
            onConfirm = { selectedCategories, selectedCollections ->
                scope.launch {
                    val existing = favoriteRepository.findFavoriteItem(target)
                    if (existing == null) {
                        val creationOutcome = favoriteRepository.saveFavoriteWithOutcome(
                            target,
                            selectedCategories.toList(),
                            selectedCollections.toList(),
                        )
                        if (creationOutcome == FavoriteCreationOutcome.Created && favoriteAddDownloadPromptEnabled) {
                            downloadSheetOpenedAfterFavorite = true
                            showDownloadSheet = true
                        }
                    } else {
                        favoriteRepository.setItemLocations(existing.id, selectedCategories, selectedCollections)
                    }
                    showFavoriteDialog = false
                    favoriteRefreshToken += 1
                    feedbackController.post(i18n("收藏位置已更新"))
                }
            },
        )
    }

    CatalogFavoriteRemovalDialogs(
        showRemovalConfirm = showFavoriteRemovalConfirm,
        showMultiPathDialog = showFavoriteMultiPathDialog,
        pendingSelection = pendingFavoriteRemovalSelection,
        feedbackController = feedbackController,
        setShowRemovalConfirm = { showFavoriteRemovalConfirm = it },
        setShowMultiPathDialog = { showFavoriteMultiPathDialog = it },
        clearPendingSelection = { pendingFavoriteRemovalSelection = null },
        setSkipNextTime = { appSettingsRepo.skipFavoriteRemovalConfirm.setValue(it) },
        removeFavorite = { removeFavoriteWithSync(favoriteRepository, favoriteSyncRepository, favoriteTarget()) },
        onRemoved = { favoriteRefreshToken += 1 },
    )
}

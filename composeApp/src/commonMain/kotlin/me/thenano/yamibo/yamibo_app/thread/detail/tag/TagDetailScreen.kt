package me.thenano.yamibo.yamibo_app.thread.detail.tag

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import coil3.compose.LocalPlatformContext
import io.github.littlesurvival.YamiboForum
import io.github.littlesurvival.YamiboRoute
import io.github.littlesurvival.core.YamiboResult
import io.github.littlesurvival.dto.model.PageNav
import io.github.littlesurvival.dto.model.ThreadSummary
import io.github.littlesurvival.dto.page.TagPage
import io.github.littlesurvival.dto.value.TagId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.thenano.yamibo.yamibo_app.*
import me.thenano.yamibo.yamibo_app.components.theme.YamiboSnackbarHost
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme
import me.thenano.yamibo.yamibo_app.favorite.*
import me.thenano.yamibo.yamibo_app.i18n.i18n
import me.thenano.yamibo.yamibo_app.navigation.LocalNavigator
import me.thenano.yamibo.yamibo_app.profile.settings.backup.IBackupSettingsScreen
import me.thenano.yamibo.yamibo_app.repository.*
import me.thenano.yamibo.yamibo_app.repository.download.TagMangaChapterDownloadKey
import me.thenano.yamibo.yamibo_app.thread.detail.components.*
import me.thenano.yamibo.yamibo_app.thread.detail.novel.INovelThreadDetailScreen
import me.thenano.yamibo.yamibo_app.thread.detail.novel.components.ThreadErrorContent
import me.thenano.yamibo.yamibo_app.thread.reader.IImageReaderScreen
import me.thenano.yamibo.yamibo_app.thread.reader.IThreadReaderScreen
import me.thenano.yamibo.yamibo_app.util.shareText
import me.thenano.yamibo.yamibo_app.util.state

/** Tag page state */
internal sealed interface TagDetailState {
    data object Loading : TagDetailState
    data class Success(val page: TagPage) : TagDetailState
    data class Error(val message: String) : TagDetailState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TagDetailScreen(
    tagId: TagId,
    tagName: String,
    initialPage: Int? = null
) {
    val colors = YamiboTheme.colors
    val navigator = LocalNavigator.current
    val tagRepository = LocalTagRepository.current
    val favoriteRepository = LocalFavoriteRepository.current
    val favoriteSyncRepository = LocalFavoriteSyncRepository.current
    val detailNoteRepository = LocalDetailNoteRepository.current
    val bookMarkRepository = LocalBookMarkRepository.current
    val chapterStateRepository = LocalChapterStateRepository.current
    val historyRepo = LocalReadHistoryRepository.current
    val contentCoverRepository = LocalContentCoverRepository.current
    val downloadRepository = LocalDownloadRepository.current
    val downloadQueue by downloadRepository.queue.collectAsState()
    val platformContext = LocalPlatformContext.current

    val scope = rememberCoroutineScope()
    val feedbackController = me.thenano.yamibo.yamibo_app.LocalAppFeedbackController.current
    val appTaskManager = me.thenano.yamibo.yamibo_app.LocalAppTaskManager.current

    var state by remember { mutableStateOf<TagDetailState>(TagDetailState.Loading) }
    var currentPage by remember { mutableIntStateOf(initialPage ?: 1) }
    var currentTagName by remember { mutableStateOf(tagName) }
    var isRefreshing by remember { mutableStateOf(false) }
    var showFavoriteDialog by remember { mutableStateOf(false) }
    var favoriteDialogCategorySelection by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var favoriteDialogSelection by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var favoriteDialogCategories by remember {
        mutableStateOf<List<FavoriteStoreRepository.FavoriteCategory>>(emptyList())
    }
    var favoriteDialogOptions by remember {
        mutableStateOf<List<FavoriteStoreRepository.FavoriteCollectionOption>>(emptyList())
    }
    var isFavorited by remember { mutableStateOf(false) }
    var favoritePaths by remember { mutableStateOf<List<String>>(emptyList()) }
    var favoriteRefreshToken by remember { mutableStateOf(0) }
    var pendingFavoriteRemovalSelection by remember { mutableStateOf<FavoriteLocationSelection?>(null) }
    var showFavoriteRemovalConfirm by remember { mutableStateOf(false) }
    var showFavoriteMultiPathDialog by remember { mutableStateOf(false) }
    var detailNote by remember { mutableStateOf<DetailNoteRepository.DetailNote?>(null) }
    var showNoteDialog by remember { mutableStateOf(false) }
    var threadBookMarkEntries by remember { mutableStateOf<Map<Long, BookMarkRepository.Entry>>(emptyMap()) }
    var threadChapterStates by remember { mutableStateOf<Map<Long, ChapterStateRepository.Entry>>(emptyMap()) }
    var actionThread by remember { mutableStateOf<ThreadSummary?>(null) }
    var showDownloadSheet by remember { mutableStateOf(false) }

    val appSettingsRepo = LocalAppSettingsRepository.current
    val imageReaderModeOverrideRepository = LocalImageReaderModeOverrideRepository.current
    val isMangaMode = appSettingsRepo.isMangaMode.state()
    val longStripModeEnabled by imageReaderModeOverrideRepository.observeTagLongStrip(tagId).collectAsState(false)
    val stackSize = navigator.stack.size

    fun tagMangaKey(thread: ThreadSummary): TagMangaChapterDownloadKey {
        return TagMangaChapterDownloadKey(
            tagId = tagId.value,
            tid = thread.tid.value,
            authorId = thread.author?.uid?.value,
        )
    }

    suspend fun ensureDownloadStorageReady(): Boolean {
        if (downloadRepository.isStorageReady()) return true
        feedbackController.post(i18n("尚未設定下載資料夾"))
        navigator.navigate(IBackupSettingsScreen())
        return false
    }

    fun launchDownloadTask(action: String, operation: suspend () -> Unit) {
        appTaskManager.launch(
            key = me.thenano.yamibo.yamibo_app.task.AppTaskKey("download:tag:${tagId.value}:$action"),
            operation = operation,
        )
    }

    val tagMangaDownloadEntries = remember(downloadQueue, tagId) {
        downloadQueue
            .mapNotNull { entry ->
                val key = entry.key as? TagMangaChapterDownloadKey ?: return@mapNotNull null
                if (key.tagId == tagId.value) key.tid.toLong() to entry else null
            }
            .toMap()
    }

    suspend fun reloadThreadBookMarks() {
        threadBookMarkEntries = bookMarkRepository
            .getEntriesByParent(BookMarkRepository.TargetType.TagMangaThread, tagId.value.toLong())
            .associateBy { it.targetId }
    }

    suspend fun reloadThreadChapterStates() {
        threadChapterStates = chapterStateRepository
            .getEntriesByParent(ChapterStateRepository.TargetType.TagMangaThread, tagId.value.toLong())
            .associateBy { it.targetId }
    }

    suspend fun reloadNote() {
        detailNote = detailNoteRepository.getNote(
            targetType = DetailNoteRepository.TargetType.TagManga,
            targetId = tagId.value.toLong(),
        )
    }

    LaunchedEffect(tagId) {
        reloadNote()
    }

    LaunchedEffect(tagId, stackSize) {
        reloadThreadBookMarks()
        reloadThreadChapterStates()
    }

    // Reading history
    var mangaTagHistory by remember {
        mutableStateOf<ReadHistoryRepository.TagMangaReadingHistory?>(null)
    }
    var tagCatalogHistory by remember {
        mutableStateOf<ReadHistoryRepository.TagCatalogReadingHistory?>(null)
    }
    val coverKey = remember(tagId) {
        ContentCoverRepository.Key(
            ContentCoverRepository.TargetType.TagManga,
            tagId.value.toLong(),
        )
    }
    val canonicalCover by contentCoverRepository.observeCover(coverKey).collectAsState(null)
    val coverUrl: () -> String? = { canonicalCover?.resolvedUrl ?: mangaTagHistory?.coverUrl ?: tagCatalogHistory?.coverUrl }

    suspend fun loadPage(page: Int) {
        val cached = tagRepository.getCachedTagPage(tagId, page)
        if (cached != null) {
            currentPage = cached.pageNav?.currentPage ?: page
            currentTagName = cached.tagName
            state = TagDetailState.Success(cached)
            return
        }

        val result = tagRepository.fetchTagPage(tagId, page)
        state =
            when (result) {
                is YamiboResult.Success -> {
                    // Force the currentPage to be the requested page
                    val correctedPageNav = result.value.pageNav?.copy(currentPage = page)
                    val correctedTagPage = result.value.copy(pageNav = correctedPageNav)
                    
                    currentPage = page
                    currentTagName = result.value.tagName
                    TagDetailState.Success(correctedTagPage)
                }

                else -> TagDetailState.Error(i18n(result.message()))
            }
    }

    LaunchedEffect(tagId) {
        if (initialPage != null) {
            currentPage = initialPage
            loadPage(initialPage)
            return@LaunchedEffect
        }
        
        val cached = tagRepository.getCachedTagPage(tagId)
        if (cached != null) {
            currentPage = cached.pageNav?.currentPage ?: 1
            currentTagName = cached.tagName
            state = TagDetailState.Success(cached)
            return@LaunchedEffect
        }
        loadPage(1)
    }

    // Load reading history for manga tags (hot reload on resume/back navigation)
    LaunchedEffect(tagId, stackSize) {
        mangaTagHistory = historyRepo.getTagMangaReaderModeHistoryPosition(tagId)
        tagCatalogHistory = historyRepo.getTagCatalogThreadHistoryPosition(tagId)
        mangaTagHistory?.coverUrl?.let { contentCoverRepository.setAutomaticCover(coverKey, it) }
        if (mangaTagHistory?.coverUrl == null) {
            tagCatalogHistory?.coverUrl?.let { contentCoverRepository.setAutomaticCover(coverKey, it) }
        }
    }

    fun favoriteTarget(): FavoriteTargetPayload.TagManga {
        return FavoriteTargetPayload.TagManga(
            tagId = tagId,
            tagName = currentTagName,
            coverUrl = coverUrl()
        )
    }

    suspend fun refreshFavoriteState() {
        val selection = favoriteRepository.getFavoriteLocationSelection(favoriteTarget())
        isFavorited = selection.item != null
        favoritePaths = selection.paths
    }

    suspend fun toggleFavorite() {
        val target = favoriteTarget()
        val selection = favoriteRepository.getFavoriteLocationSelection(target)
        if (selection.item != null) {
            pendingFavoriteRemovalSelection = selection
            if (appSettingsRepo.skipFavoriteRemovalConfirm.getValue()) {
                if (selection.paths.size > 1) {
                    showFavoriteMultiPathDialog = true
                } else {
                    withContext(Dispatchers.Default) { removeFavoriteWithSync(favoriteRepository, favoriteSyncRepository, target) }
                    favoriteRefreshToken += 1
                    feedbackController.post(i18n("已移除收藏"))
                    pendingFavoriteRemovalSelection = null
                }
            } else {
                showFavoriteRemovalConfirm = true
            }
            return
        }

        favoriteRepository.saveFavorite(target)
        favoriteRefreshToken += 1
        feedbackController.post(i18n("已加入收藏"))
    }

    LaunchedEffect(tagId, currentTagName, favoriteRefreshToken, canonicalCover?.resolvedUrl) {
        refreshFavoriteState()
    }
    
    // Reading progress text
    val activeReadingHistory = if (isMangaMode) mangaTagHistory else tagCatalogHistory
    val readingProgressText = remember(mangaTagHistory, tagCatalogHistory, isMangaMode) {
        if (isMangaMode) {
            val h = mangaTagHistory ?: return@remember null
            i18n("第{} ‧ {} ‧ {}/{}", h.tagPage, h.threadTitle, h.threadImagePageIndex + 1, h.threadImageTotalPages)
        } else {
            val h = tagCatalogHistory ?: return@remember null
            i18n("第{} ‧ {}", h.threadPage, h.threadTitle)
        }
    }

    /** Handle thread click: manga mode opens TagMangaReaderScreen */
    fun navigateToThread(thread: ThreadSummary, threads: List<ThreadSummary>, pageNav: PageNav?) {
        val fid = thread.fid
        val isManga = fid?.let { YamiboForum.isMangaForum(it) } == true
        val isNovel = fid?.let { YamiboForum.isNovelForum(it) } == true

        if (isMangaMode && isManga) {
            val threadState = threadChapterStates[thread.tid.value.toLong()]
            val threadHistory = mangaTagHistory?.takeIf { it.threadId == thread.tid }
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
                    authorId = thread.author?.uid,
                    imageList = emptyList(),
                    initialPage = initialImagePage.coerceAtLeast(1),
                    loadHistory = !isCompleted && threadHistory != null,
                    tagId = tagId,
                    tagName = currentTagName,
                    tagThreads = threads,
                    tagPage = currentPage,
                    tagTotalPages = pageNav?.totalPages ?: 1
                )
            )
        } else if (isNovel) {
            navigator.navigate(
                INovelThreadDetailScreen(
                    tid = thread.tid,
                    title = thread.title,
                    authorId = thread.author?.uid
                )
            )
        } else {
            navigator.navigate(
                IThreadReaderScreen(
                    tid = thread.tid,
                    title = thread.title,
                    threadType = ReadHistoryRepository.ThreadEntryType.Normal,
                    catalogCoverTargetType = ContentCoverRepository.TargetType.TagManga,
                    catalogCoverTargetId = tagId.value.toLong(),
                    catalogTagId = tagId,
                    catalogTagName = currentTagName,
                    catalogTagPage = currentPage,
                )
            )
        }
    }

    /** Handle continue reading / start reading */
    fun handleContinueRead(threads: List<ThreadSummary>, pageNav: PageNav?) {
        val history = mangaTagHistory
        if (history != null && isMangaMode) {
            scope.launch {
                val historyThreads = if (history.tagPage == currentPage && threads.any { it.tid == history.threadId }) {
                    threads
                } else {
                    val cached = tagRepository.getCachedTagPage(tagId, history.tagPage)
                    val page = cached ?: when (val result = tagRepository.fetchTagPage(tagId, history.tagPage)) {
                        is YamiboResult.Success -> {
                            tagRepository.setCachedTagPage(tagId, history.tagPage, result.value)
                            result.value
                        }
                        else -> null
                    }
                    page?.threadSummaries.orEmpty().ifEmpty { threads }
                }
                val threadIndex = historyThreads.indexOfFirst { it.tid == history.threadId }
                val historyThread = historyThreads.getOrNull(threadIndex)
                val authorId = historyThread?.author?.uid
                val isHistoryManga = historyThread?.fid?.let { YamiboForum.isMangaForum(it) } == true
                if (isHistoryManga) {
                    navigator.navigate(
                        IImageReaderScreen(
                            tid = history.threadId,
                            postId = null,
                            fid = historyThread.fid,
                            threadTitle = history.threadTitle,
                            authorId = authorId,
                            imageList = emptyList(),
                            initialPage = 1,
                            loadHistory = true,
                            tagId = tagId,
                            tagName = currentTagName,
                            tagThreads = historyThreads,
                            tagPage = history.tagPage,
                            tagTotalPages = pageNav?.totalPages ?: 1
                        )
                    )
                } else {
                    navigator.navigate(
                        IThreadReaderScreen(
                            tid = history.threadId,
                            title = history.threadTitle,
                            threadType = ReadHistoryRepository.ThreadEntryType.Normal,
                            authorId = authorId,
                            catalogCoverTargetType = ContentCoverRepository.TargetType.TagManga,
                            catalogCoverTargetId = tagId.value.toLong(),
                            catalogTagId = tagId,
                            catalogTagName = currentTagName,
                            catalogTagPage = history.tagPage,
                        )
                    )
                }
            }
        } else if (tagCatalogHistory != null) {
            val catalogHistory = tagCatalogHistory ?: return
            navigator.navigate(
                IThreadReaderScreen(
                    tid = catalogHistory.threadId,
                    title = catalogHistory.threadTitle,
                    threadType = ReadHistoryRepository.ThreadEntryType.Normal,
                    authorId = catalogHistory.authorId,
                    initialPage = catalogHistory.threadPage,
                    targetPid = catalogHistory.postId,
                    catalogCoverTargetType = ContentCoverRepository.TargetType.TagManga,
                    catalogCoverTargetId = tagId.value.toLong(),
                    catalogTagId = tagId,
                    catalogTagName = currentTagName,
                    catalogTagPage = catalogHistory.tagPage,
                )
            )
        } else {
            val first = threads.firstOrNull() ?: return
            navigateToThread(first, threads, pageNav)
        }
    }

    Scaffold(
        containerColor = colors.creamBackground,
        topBar = {
            CatalogDetailTopBar(
                title = currentTagName,
                onBack = { navigator.pop() }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
                .background(colors.creamBackground)
        ) {
            when (val currentState = state) {
                is TagDetailState.Loading -> CatalogLoadingSkeleton()

                is TagDetailState.Error -> {
                    ThreadErrorContent(
                        message = currentState.message,
                        onRetry = {
                            state = TagDetailState.Loading
                            scope.launch { loadPage(currentPage) }
                        }
                    )
                }

                is TagDetailState.Success -> {
                    PullToRefreshBox(
                        isRefreshing = isRefreshing,
                        onRefresh = {
                            isRefreshing = true
                            scope.launch {
                                when (val result =
                                    tagRepository.fetchTagPage(tagId, currentPage)
                                ) {
                                    is YamiboResult.Success -> {
                                        tagRepository.clearCachedTagPage(tagId)
                                        tagRepository.setCachedTagPage(
                                            tagId,
                                            currentPage,
                                            result.value
                                        )
                                        currentTagName = result.value.tagName
                                        state = TagDetailState.Success(result.value)
                                    }

                                    else -> {
                                        feedbackController.post(
                                            message = i18n("重新整理標籤頁失敗：{}", i18n(result.message())),
                                            duration = me.thenano.yamibo.yamibo_app.feedback.AppFeedbackDuration.Short,
                                        )
                                    }
                                }
                                isRefreshing = false
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    ) {
                        CatalogDetailContent(
                            tagPage = currentState.page,
                            tagName = currentTagName,
                            coverUrl = coverUrl(),
                            isMangaMode = isMangaMode,
                            onMangaModeChange = { appSettingsRepo.isMangaMode.setValue(it) },
                    dynamicCoverEnabled = canonicalCover?.dynamicEnabled ?: true,
                    onDynamicCoverEnabledChange = { enabled ->
                        scope.launch { contentCoverRepository.setDynamicEnabled(coverKey, enabled) }
                    },
                    longStripModeEnabled = longStripModeEnabled,
                    onLongStripModeEnabledChange = { enabled ->
                        imageReaderModeOverrideRepository.setTagLongStrip(tagId, enabled)
                    },
                              hasReadingHistory = activeReadingHistory != null,
                            readingProgressText = readingProgressText,
                            onContinueRead = {
                                handleContinueRead(currentState.page.threadSummaries, currentState.page.pageNav)
                            },
                            onFavorite = { scope.launch { toggleFavorite() } },
                            onFavoriteLongPress = {
                                scope.launch {
                                    val target = favoriteTarget()
                                    val selection = favoriteRepository.getFavoriteLocationSelection(target)
                                    favoriteDialogCategories = favoriteRepository.getCategories()
                                    favoriteDialogOptions = favoriteRepository.getCollectionOptions()
                                    favoriteDialogCategorySelection = selection.categoryIds
                                    favoriteDialogSelection = selection.collectionIds
                                    showFavoriteDialog = true
                                    return@launch
                                }
                            },
                            onShare = {
                                val url = YamiboRoute.TagPage(tagId).build()
                                shareText(platformContext, url, currentTagName)
                            },
                            showDownloadAction = isMangaMode,
                            onDownload = { showDownloadSheet = true },
                            noteContent = detailNote?.content.orEmpty(),
                            onNoteClick = { showNoteDialog = true },
                            onPageChange = { page ->
                                state = TagDetailState.Loading
                                scope.launch { loadPage(page) }
                            },
                            isFavorited = isFavorited,
                            onThreadClick = { thread ->
                                navigateToThread(
                                    thread,
                                    currentState.page.threadSummaries,
                                    currentState.page.pageNav
                                )
                            },
                            bookmarkedThreadIds = threadBookMarkEntries.values
                                .filter { it.bookmarked }
                                .mapTo(mutableSetOf()) { it.targetId },
                            readThreadIds = emptySet(),
                            downloadEntries = tagMangaDownloadEntries,
                            chapterStates = threadChapterStates,
                              historyThreadId = if (isMangaMode) {
                                  mangaTagHistory?.threadId?.value?.toLong()
                              } else {
                                  tagCatalogHistory?.threadId?.value?.toLong()
                              },
                              historyThreadCompleted = if (isMangaMode) mangaTagHistory?.let {
                                  it.threadImageTotalPages > 0 && it.threadImagePageIndex >= it.threadImageTotalPages - 1
                              } == true else false,
                              historyThreadProgressText = if (isMangaMode) mangaTagHistory?.let {
                                  if (it.threadImageTotalPages <= 0) null else {
                                      val clampedPageIndex = it.threadImagePageIndex.coerceIn(0, it.threadImageTotalPages - 1)
                                      val progress = (((clampedPageIndex + 1) * 100f) / it.threadImageTotalPages).toInt().coerceIn(1, 100)
                                      if (progress >= 100) null else "${clampedPageIndex + 1}/${it.threadImageTotalPages}"
                                  }
                              } else tagCatalogHistory?.let { i18n("第{}頁", it.threadPage) },
                            onThreadLongPress = { actionThread = it },
                        )
                    }
                }
            }
        }
    }

    if (showDownloadSheet) {
        val currentState = state as? TagDetailState.Success
        val currentThreads = currentState?.page?.threadSummaries.orEmpty()
        val currentPageHasDownloads = currentThreads.any { tagMangaDownloadEntries.containsKey(it.tid.value.toLong()) }
        CatalogDownloadActionSheet(
            title = i18n("標籤漫畫下載"),
            onDismiss = { showDownloadSheet = false },
            actions = buildList {
                add(CatalogDownloadAction(i18n("下載目前分頁")) {
                    showDownloadSheet = false
                    launchDownloadTask("current-page:$currentPage") {
                        if (!ensureDownloadStorageReady()) return@launchDownloadTask
                        downloadRepository.enqueueTagMangaCurrentPage(
                            tagId = tagId,
                            tagName = currentTagName,
                            threads = currentThreads,
                            tagPage = currentPage,
                        ).onFailure { error ->
                            Logger.e("TagDetailScreen", "Failed to enqueue current tag manga page tagId=${tagId.value} page=$currentPage", error)
                            feedbackController.post(error.message ?: i18n("加入下載失敗"))
                        }
                    }
                })
                add(CatalogDownloadAction(i18n("下載全部分頁")) {
                    showDownloadSheet = false
                    launchDownloadTask("all-pages") {
                        if (!ensureDownloadStorageReady()) return@launchDownloadTask
                        downloadRepository.enqueueTagMangaAllPages(tagId, currentTagName)
                            .onFailure { error ->
                                Logger.e("TagDetailScreen", "Failed to enqueue all tag manga pages tagId=${tagId.value}", error)
                                feedbackController.post(error.message ?: i18n("加入下載失敗"))
                            }
                    }
                })
                if (currentPageHasDownloads) {
                    add(CatalogDownloadAction(i18n("清除目前分頁下載")) {
                        showDownloadSheet = false
                        launchDownloadTask("clear-page:$currentPage") {
                            currentThreads.forEach { downloadRepository.clearTagMangaChapter(tagMangaKey(it)) }
                            feedbackController.post(i18n("已清除目前分頁下載"))
                        }
                    })
                }
                if (tagMangaDownloadEntries.isNotEmpty()) {
                    add(CatalogDownloadAction(i18n("清除整個標籤下載")) {
                        showDownloadSheet = false
                        launchDownloadTask("clear-all") {
                            downloadRepository.clearTagManga(tagId)
                            feedbackController.post(i18n("已清除整個標籤下載"))
                        }
                    })
                }
            },
        )
    }

    if (showFavoriteDialog) {
        val target = FavoriteTargetPayload.TagManga(
            tagId = tagId,
            tagName = currentTagName,
            coverUrl = coverUrl()
        )
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
                        favoriteRepository.saveFavorite(
                            target,
                            categoryIds = selectedCategories.toList(),
                            collectionIds = selectedCollections.toList()
                        )
                        showFavoriteDialog = false
                        favoriteRefreshToken += 1
                        feedbackController.post(i18n("已加入收藏"))
                    } else if (selectedCategories.isEmpty() && selectedCollections.isEmpty()) {
                        showFavoriteDialog = false
                        pendingFavoriteRemovalSelection = favoriteRepository.getFavoriteLocationSelection(target)
                        if (appSettingsRepo.skipFavoriteRemovalConfirm.getValue()) {
                            if ((pendingFavoriteRemovalSelection?.paths?.size ?: 0) > 1) {
                                showFavoriteMultiPathDialog = true
                            } else {
                                withContext(Dispatchers.Default) { removeFavoriteWithSync(favoriteRepository, favoriteSyncRepository, target) }
                                favoriteRefreshToken += 1
                                feedbackController.post(i18n("已從所有位置移除收藏"))
                                pendingFavoriteRemovalSelection = null
                            }
                        } else {
                            showFavoriteRemovalConfirm = true
                        }
                    } else {
                        favoriteRepository.setItemLocations(existing.id, selectedCategories, selectedCollections)
                        showFavoriteDialog = false
                        favoriteRefreshToken += 1
                        feedbackController.post(i18n("收藏位置已更新"))
                    }
                }
            }
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

    if (showNoteDialog) {
        DetailNoteEditorDialog(
            initialContent = detailNote?.content.orEmpty(),
            onDismiss = { showNoteDialog = false },
            onSave = { content ->
                showNoteDialog = false
                scope.launch {
                    detailNoteRepository.saveNote(
                        targetType = DetailNoteRepository.TargetType.TagManga,
                        targetId = tagId.value.toLong(),
                        content = content,
                    )
                    reloadNote()
                    feedbackController.post(
                        if (content.isBlank()) i18n("已刪除筆記") else i18n("已保存筆記"),
                        duration = me.thenano.yamibo.yamibo_app.feedback.AppFeedbackDuration.Short,
                    )
                }
            },
            onDelete = {
                showNoteDialog = false
                scope.launch {
                    detailNoteRepository.deleteNote(
                        targetType = DetailNoteRepository.TargetType.TagManga,
                        targetId = tagId.value.toLong(),
                    )
                    reloadNote()
                    feedbackController.post(i18n("已刪除筆記"), duration = me.thenano.yamibo.yamibo_app.feedback.AppFeedbackDuration.Short)
                }
            },
        )
    }

    actionThread?.let { thread ->
        val entry = threadBookMarkEntries[thread.tid.value.toLong()]
        val chapterState = threadChapterStates[thread.tid.value.toLong()]
        val downloadEntry = tagMangaDownloadEntries[thread.tid.value.toLong()]
        val downloadKey = tagMangaKey(thread)
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
                        targetType = BookMarkRepository.TargetType.TagMangaThread,
                        parentId = tagId.value.toLong(),
                        targetId = thread.tid.value.toLong(),
                        title = thread.title,
                        bookmarked = next,
                    )
                    reloadThreadBookMarks()
                    feedbackController.post(
                        if (next) i18n("已新增書籤") else i18n("已移除書籤"),
                        duration = me.thenano.yamibo.yamibo_app.feedback.AppFeedbackDuration.Short,
                    )
                }
            },
            onMarkRead = {
                actionThread = null
                scope.launch {
                    chapterStateRepository.setRead(
                        targetType = ChapterStateRepository.TargetType.TagMangaThread,
                        parentId = tagId.value.toLong(),
                        targetId = thread.tid.value.toLong(),
                        title = thread.title,
                        read = true,
                    )
                    reloadThreadChapterStates()
                    feedbackController.post(i18n("已標為已讀"), duration = me.thenano.yamibo.yamibo_app.feedback.AppFeedbackDuration.Short)
                }
            },
            onMarkUnread = {
                actionThread = null
                scope.launch {
                    chapterStateRepository.setRead(
                        targetType = ChapterStateRepository.TargetType.TagMangaThread,
                        parentId = tagId.value.toLong(),
                        targetId = thread.tid.value.toLong(),
                        title = thread.title,
                        read = false,
                    )
                    reloadThreadChapterStates()
                    feedbackController.post(i18n("已標為未讀"), duration = me.thenano.yamibo.yamibo_app.feedback.AppFeedbackDuration.Short)
                }
            },
            onClearHistory = {
                actionThread = null
                scope.launch {
                    if (isMangaMode) {
                        chapterStateRepository.clearTarget(
                            targetType = ChapterStateRepository.TargetType.TagMangaThread,
                            parentId = tagId.value.toLong(),
                            targetId = thread.tid.value.toLong(),
                        )
                        if (mangaTagHistory?.threadId == thread.tid) {
                            historyRepo.deleteMangaTagHistory(tagId)
                            mangaTagHistory = null
                        }
                    } else if (tagCatalogHistory?.threadId == thread.tid) {
                        historyRepo.deleteTagCatalogThreadHistory(tagId)
                        tagCatalogHistory = null
                    }
                    reloadThreadChapterStates()
                    feedbackController.post(i18n("已清除閱讀紀錄"), duration = me.thenano.yamibo.yamibo_app.feedback.AppFeedbackDuration.Short)
                }
            },
            onDownloadChapter = {
                actionThread = null
                launchDownloadTask("chapter:${thread.tid.value}") {
                    if (!ensureDownloadStorageReady()) return@launchDownloadTask
                    downloadRepository.enqueueTagMangaChapter(tagId, currentTagName, thread, currentPage)
                        .onFailure { error ->
                            Logger.e("TagDetailScreen", "Failed to enqueue tag manga chapter tagId=${tagId.value} tid=${thread.tid.value}", error)
                            feedbackController.post(error.message ?: i18n("加入下載失敗"))
                        }
                }
            },
            onRefreshChapter = {
                actionThread = null
                launchDownloadTask("refresh:${thread.tid.value}") {
                    if (!ensureDownloadStorageReady()) return@launchDownloadTask
                    when (val result = downloadRepository.refreshTagMangaChapter(downloadKey)) {
                        is YamiboResult.Success -> feedbackController.post(i18n("已重新整理下載"))
                        else -> feedbackController.post(i18n("重新整理下載失敗：{}", i18n(result.message())))
                    }
                }
            },
            onRetryChapter = {
                actionThread = null
                launchDownloadTask("retry:${thread.tid.value}") {
                    if (!ensureDownloadStorageReady()) return@launchDownloadTask
                    downloadRepository.retry(downloadKey)
                        .onFailure { error ->
                            Logger.e("TagDetailScreen", "Failed to retry tag manga chapter key=${downloadKey.stableId}", error)
                            feedbackController.post(error.message ?: i18n("重試失敗"))
                        }
                }
            },
            onClearChapterDownload = {
                actionThread = null
                launchDownloadTask("clear:${thread.tid.value}") {
                    downloadRepository.clearTagMangaChapter(downloadKey)
                    feedbackController.post(i18n("已清除此章下載"), duration = me.thenano.yamibo.yamibo_app.feedback.AppFeedbackDuration.Short)
                }
            },
        )
    }
}

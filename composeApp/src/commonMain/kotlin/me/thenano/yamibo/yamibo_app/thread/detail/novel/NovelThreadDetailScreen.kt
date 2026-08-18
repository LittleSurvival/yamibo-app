package me.thenano.yamibo.yamibo_app.thread.detail.novel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.PlatformContext
import coil3.compose.LocalPlatformContext
import io.github.littlesurvival.YamiboRoute
import io.github.littlesurvival.core.YamiboResult
import io.github.littlesurvival.dto.page.Post
import io.github.littlesurvival.dto.page.ThreadPage
import io.github.littlesurvival.dto.value.ThreadId
import io.github.littlesurvival.dto.value.UserId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.thenano.yamibo.yamibo_app.*
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme
import me.thenano.yamibo.yamibo_app.favorite.*
import me.thenano.yamibo.yamibo_app.i18n.i18n
import me.thenano.yamibo.yamibo_app.navigation.LocalNavigator
import me.thenano.yamibo.yamibo_app.repository.*
import me.thenano.yamibo.yamibo_app.repository.ReadHistoryRepository.ThreadReadingHistory
import me.thenano.yamibo.yamibo_app.repository.contentcover.findThreadCoverCandidate
import me.thenano.yamibo.yamibo_app.thread.detail.components.DetailNoteCard
import me.thenano.yamibo.yamibo_app.thread.detail.components.DetailNoteEditorDialog
import me.thenano.yamibo.yamibo_app.novelexport.rememberNovelTxtExportFileActions
import me.thenano.yamibo.yamibo_app.repository.novelexport.NovelTxtExportResult
import me.thenano.yamibo.yamibo_app.repository.novelexport.NovelTxtExporter
import me.thenano.yamibo.yamibo_app.thread.detail.novel.components.*
import me.thenano.yamibo.yamibo_app.thread.reader.IThreadReaderScreen
import me.thenano.yamibo.yamibo_app.util.shareText
import me.thenano.yamibo.yamibo_app.util.time.epochMillisOrNull

/** Thread detail state */
internal sealed interface ThreadState {
    data object Loading : ThreadState
    data class Success(val page: ThreadPage) : ThreadState
    data class Error(val message: String) : ThreadState
}

/** Main Thread Detail Screen */
@Composable
internal fun NovelThreadDetailScreen(tid: ThreadId, title: String, authorId: UserId? = null) {
    val colors = YamiboTheme.colors
    val appSettingsRepo = LocalAppSettingsRepository.current
    val threadRepository = LocalThreadRepository.current
    val favoriteRepository = LocalFavoriteRepository.current
    val favoriteSyncRepository = LocalFavoriteSyncRepository.current
    val detailNoteRepository = LocalDetailNoteRepository.current
    val bookMarkRepository = LocalBookMarkRepository.current
    val chapterStateRepository = LocalChapterStateRepository.current
    val readHistoryRepo = LocalReadHistoryRepository.current
    val contentCoverRepository = LocalContentCoverRepository.current
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val feedbackController = LocalAppFeedbackController.current
    val confirmationController = LocalAppConfirmationController.current
    val appTaskManager = LocalAppTaskManager.current
    val platformContext = LocalPlatformContext.current

    var state by remember { mutableStateOf<ThreadState>(ThreadState.Loading) }
    var isRefreshing by remember { mutableStateOf(false) }
    val pagePostsCache = remember { mutableStateMapOf<Int, List<Post>>() }
    var expandedPages by remember { mutableStateOf(setOf(1)) }
    var readHistory by remember { mutableStateOf<ThreadReadingHistory?>(null) }
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
    val favoriteRepositoryRevision by favoriteRepository.favoriteItemRevision.collectAsState()
    var pendingFavoriteRemovalSelection by remember { mutableStateOf<FavoriteLocationSelection?>(null) }
    var pendingFavoriteRemovalSuccessMessage by remember { mutableStateOf(i18n("已移除收藏")) }
    var showFavoriteRemovalConfirm by remember { mutableStateOf(false) }
    var showFavoriteMultiPathDialog by remember { mutableStateOf(false) }
    var showFavoriteAddSyncConfirm by remember { mutableStateOf(false) }
    var showFavoriteRemoveSyncConfirm by remember { mutableStateOf(false) }
    var detailNote by remember { mutableStateOf<DetailNoteRepository.DetailNote?>(null) }
    var showNoteDialog by remember { mutableStateOf(false) }
    var postBookMarkEntries by remember { mutableStateOf<Map<Long, BookMarkRepository.Entry>>(emptyMap()) }
    var postChapterStates by remember { mutableStateOf<Map<Long, ChapterStateRepository.Entry>>(emptyMap()) }
    var actionPost by remember { mutableStateOf<Post?>(null) }
    var exportingTxt by remember { mutableStateOf(false) }
    var exportTxtProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val novelTxtExporter = remember { NovelTxtExporter() }
    val txtExportFileActions = rememberNovelTxtExportFileActions(
        onExported = { fileName ->
            feedbackController.post(
                message = i18n("已导出TXT文件：{}", fileName),
                duration = me.thenano.yamibo.yamibo_app.feedback.AppFeedbackDuration.Short,
            )
        },
        onExportFailed = { message ->
            feedbackController.post(
                message = i18n("导出TXT失败：{}", message),
                duration = me.thenano.yamibo.yamibo_app.feedback.AppFeedbackDuration.Short,
            )
        },
    )
    val noteAuthorId = authorId?.value?.toLong() ?: 0L
    val coverKey = remember(tid) {
        ContentCoverRepository.Key(ContentCoverRepository.TargetType.ThreadNovel, tid.value.toLong())
    }
    val canonicalCover by contentCoverRepository.observeCover(coverKey).collectAsState(null)

    suspend fun reloadReadingHistory() {
        readHistory = try {
            readHistoryRepo.getPosition(tid, ReadHistoryRepository.ThreadEntryType.Novel, authorId)
        } catch (error: Exception) {
            Logger.w("NovelThreadDetailScreen", "Failed to reload reading history tid=${tid.value}", error)
            null
        }
    }

    suspend fun reloadPostBookMarks() {
        postBookMarkEntries = bookMarkRepository
            .getEntriesByParent(BookMarkRepository.TargetType.ThreadPost, tid.value.toLong())
            .associateBy { it.targetId }
    }

    suspend fun reloadNote() {
        detailNote = detailNoteRepository.getNote(
            targetType = DetailNoteRepository.TargetType.NovelThread,
            targetId = tid.value.toLong(),
            authorId = noteAuthorId,
        )
    }

    LaunchedEffect(tid, noteAuthorId) {
        reloadNote()
    }

    LaunchedEffect(tid) {
        reloadPostBookMarks()
    }

    val isCurrentDetailScreen = navigator.currentScreen is INovelThreadDetailScreen
    LaunchedEffect(tid, isCurrentDetailScreen) {
        if (!isCurrentDetailScreen) return@LaunchedEffect
        chapterStateRepository.observeEntriesByParent(
            targetType = ChapterStateRepository.TargetType.ThreadPost,
            parentId = tid.value.toLong(),
        ).collect { states -> postChapterStates = states }
    }

    LaunchedEffect(tid, authorId, navigator.stack.size, navigator.currentScreen.id) {
        if (navigator.currentScreen is INovelThreadDetailScreen) {
            reloadReadingHistory()
            reloadPostBookMarks()
        }
    }

    LaunchedEffect((state as? ThreadState.Success)?.page) {
        val threadPage = (state as? ThreadState.Success)?.page
        threadPage?.let(::findThreadCoverCandidate)?.let { coverUrl ->
            contentCoverRepository.setAutomaticCover(coverKey, coverUrl)
        }
        readHistory?.threadCover?.let { contentCoverRepository.setAutomaticCover(coverKey, it) }
    }

    fun favoriteTarget(): FavoriteTargetPayload.Thread {
        val currentTitle = (state as? ThreadState.Success)?.page?.thread?.title ?: title
        val currentForumId = (state as? ThreadState.Success)?.page?.thread?.forum?.fid
        val currentForumName = (state as? ThreadState.Success)?.page?.thread?.forum?.name
        val firstPost = (state as? ThreadState.Success)?.page?.posts?.firstOrNull { it.floor == 1 }
        return FavoriteTargetPayload.Thread(
            tid = tid,
            title = currentTitle,
            threadType = ReadHistoryRepository.ThreadEntryType.Novel,
            authorId = authorId,
            coverUrl = canonicalCover?.resolvedUrl ?: readHistory?.threadCover,
            lastUpdatedTime = firstPost?.lastEditedTime?.epochMillisOrNull() ?: firstPost?.timeCreate?.epochMillisOrNull(),
            forumId = currentForumId,
            forumName = currentForumName
        )
    }

    suspend fun refreshFavoriteState() {
        val target = favoriteTarget()
        favoriteRepository.syncFavoriteMetadata(target)
        val selection = favoriteRepository.getFavoriteLocationSelection(target)
        isFavorited = selection.item != null
        favoritePaths = selection.paths
    }

    fun launchFavoriteTask(action: String, operation: suspend () -> Unit) {
        appTaskManager.launch(
            key = me.thenano.yamibo.yamibo_app.task.AppTaskKey("favorite:$action:thread:${tid.value}"),
            operation = operation,
        )
    }

    suspend fun completeFavoriteAdd(syncToRemote: Boolean) {
        completeFavoriteAddWithFeedback(
            favoriteRepository = favoriteRepository,
            favoriteSyncRepository = favoriteSyncRepository,
            target = favoriteTarget(),
            syncToRemote = syncToRemote,
            feedbackController = feedbackController,
        )
    }

    suspend fun completeSavedFavoriteSync(syncToRemote: Boolean) {
        completeSavedFavoriteSyncWithFeedback(
            favoriteRepository = favoriteRepository,
            favoriteSyncRepository = favoriteSyncRepository,
            target = favoriteTarget(),
            syncToRemote = syncToRemote,
            feedbackController = feedbackController,
        )
    }

    suspend fun completeFavoriteRemoval(removeRemote: Boolean) {
        completeFavoriteRemovalWithFeedback(
            favoriteRepository = favoriteRepository,
            favoriteSyncRepository = favoriteSyncRepository,
            target = favoriteTarget(),
            removeRemote = removeRemote,
            feedbackController = feedbackController,
            confirmationController = confirmationController,
            appTaskManager = appTaskManager,
            successMessage = pendingFavoriteRemovalSuccessMessage,
            failureMessage = i18n("移除收藏失敗"),
        )
    }

    suspend fun maybePromptRemoteRemoval() {
        val target = favoriteTarget()
        val shouldPromptRemote = target.supportsRemoteWebsiteSync() &&
            hasRemoteFavoriteForTarget(favoriteRepository, favoriteSyncRepository, target)
        when {
            shouldPromptRemote && appSettingsRepo.favoriteRemoveSyncPromptEnabled.getValue() -> {
                showFavoriteRemoveSyncConfirm = true
            }
            else -> {
                completeFavoriteRemoval(
                    removeRemote = shouldPromptRemote && appSettingsRepo.favoriteRemoveSyncDefault.getValue(),
                )
            }
        }
    }

    suspend fun toggleFavorite() {
        val target = favoriteTarget()
        val selection = favoriteRepository.getFavoriteLocationSelection(target)
        if (selection.item != null) {
            pendingFavoriteRemovalSelection = selection
            pendingFavoriteRemovalSuccessMessage = i18n("已移除收藏")
            if (appSettingsRepo.skipFavoriteRemovalConfirm.getValue()) {
                if (selection.paths.size > 1) {
                    showFavoriteMultiPathDialog = true
                } else {
                    maybePromptRemoteRemoval()
                }
            } else {
                showFavoriteRemovalConfirm = true
            }
            return
        }

        if (target.supportsRemoteWebsiteSync() && appSettingsRepo.favoriteAddSyncPromptEnabled.getValue()) {
            favoriteRepository.saveFavorite(target)
            favoriteRefreshToken += 1
            showFavoriteAddSyncConfirm = true
        } else {
            completeFavoriteAdd(
                syncToRemote = target.supportsRemoteWebsiteSync() && appSettingsRepo.favoriteAddSyncDefault.getValue(),
            )
        }
    }

    LaunchedEffect(
        tid,
        authorId,
        favoriteRefreshToken,
        favoriteRepositoryRevision,
        canonicalCover?.resolvedUrl,
        (state as? ThreadState.Success)?.page?.thread?.title,
    ) {
        refreshFavoriteState()
    }

    suspend fun loadThread(page: Int = 1) {
        val cached = threadRepository.getCachedThread(tid, authorId, page)
        if (cached != null) {
            pagePostsCache[page] = cached.posts
            state = ThreadState.Success(cached)
            return
        }

        val result = threadRepository.fetchThread(tid, authorId, page)
        state =
            when (result) {
                is YamiboResult.Success -> {
                    pagePostsCache[page] = result.value.posts
                    ThreadState.Success(result.value)
                }
                else -> ThreadState.Error(i18n(result.message()))
            }
    }

    /** initial load ??use cache if available, only fetch on cold start */
    LaunchedEffect(tid) {
        val cached = threadRepository.getCachedThread(tid, authorId)
        if (cached != null) {
            pagePostsCache[1] = cached.posts
            state = ThreadState.Success(cached)
            return@LaunchedEffect
        }
        loadThread()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = colors.creamBackground,
        topBar = {
            ThreadTopBar(
                title =
                    when (val s = state) {
                        is ThreadState.Success -> s.page.thread.title
                        else -> title
                    },
                onBack = { navigator.pop() }
            )
        }
    ) { paddingValues ->
        Box(
            modifier =
                Modifier.padding(paddingValues)
                    .fillMaxSize()
                    .background(colors.creamBackground)
        ) {
            when (val current = state) {
                is ThreadState.Loading -> ThreadLoadingSkeleton()
                is ThreadState.Error ->
                    ThreadErrorContent(
                        message = current.message,
                        onRetry = {
                            state = ThreadState.Loading
                            scope.launch { loadThread() }
                        }
                    )

                is ThreadState.Success ->
                    PullToRefreshBox(
                        isRefreshing = isRefreshing,
                        onRefresh = {
                            isRefreshing = true
                            scope.launch {
                                when (val result =
                                    threadRepository.fetchThread(tid, authorId)
                                ) {
                                    is YamiboResult.Success -> {
                                        threadRepository.clearCachedThread(tid)
                                        threadRepository.setCachedThread(
                                            tid,
                                            authorId,
                                            1,
                                            result.value
                                        )
                                        pagePostsCache.clear() // Clear local cache as well
                                        pagePostsCache[1] = result.value.posts
                                        expandedPages = setOf(1)
                                        state = ThreadState.Success(result.value)
                                    }

                                    else -> {
                                        feedbackController.post(
                                            message = i18n("重新整理帖子失敗：{}", i18n(result.message())),
                                            duration = me.thenano.yamibo.yamibo_app.feedback.AppFeedbackDuration.Short,
                                        )
                                    }
                                }
                                isRefreshing = false
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    ) {
                    ThreadContent(
                        threadPage = current.page,
                        coverUrl = canonicalCover?.resolvedUrl ?: readHistory?.threadCover,
                            pagePostsCache = pagePostsCache,
                            expandedPages = expandedPages,
                            onTogglePage = { page ->
                                expandedPages =
                                    if (page in expandedPages) {
                                        expandedPages - page
                                    } else {
                                        expandedPages + page
                                    }
                            },
                            onLoadPage = { page ->
                                scope.launch {
                                    val cached = threadRepository.getCachedThread(tid, authorId, page)
                                    if (cached != null) {
                                        pagePostsCache[page] = cached.posts
                                    } else {
                                        val result =
                                            threadRepository.fetchThread(
                                                tid,
                                                authorId,
                                                page
                                            )
                                        if (result is YamiboResult.Success) {
                                            pagePostsCache[page] = result.value.posts
                                        } else {
                                            feedbackController.post(
                                                message = i18n("載入第 {} 頁失敗: {}", page, i18n(result.message())),
                                                duration = me.thenano.yamibo.yamibo_app.feedback.AppFeedbackDuration.Short
                                            )
                                        }
                                    }
                                }
                            },
                            onPostClick = { page, post ->
                                navigator.navigate(
                                    IThreadReaderScreen(
                                        tid = tid,
                                        title = current.page.thread.title,
                                        threadType = ReadHistoryRepository.ThreadEntryType.Novel,
                                        authorId = authorId,
                                        initialPage = page,
                                        targetPid = post.pid
                                    )
                                )
                            },
                            onPostLongPress = { actionPost = it },
                            onFavorite = { launchFavoriteTask("toggle") { toggleFavorite() } },
                            onFavoriteLongPress = {
                                scope.launch {
                                    val selection = favoriteRepository.getFavoriteLocationSelection(favoriteTarget())
                                    favoriteDialogCategories = favoriteRepository.getCategories()
                                    favoriteDialogOptions = favoriteRepository.getCollectionOptions()
                                    favoriteDialogCategorySelection = selection.categoryIds
                                    favoriteDialogSelection = selection.collectionIds
                                    showFavoriteDialog = true
                                }
                            },
                            onExportTxt = {
                                val page = (state as? ThreadState.Success)?.page
                                if (page != null && !exportingTxt) {
                                    scope.launch {
                                        exportingTxt = true
                                        val result = novelTxtExporter.buildNovelTxt(
                                            firstPage = page,
                                            loadPage = { p ->
                                                threadRepository.getCachedThread(tid, authorId, p)
                                                    ?: (threadRepository.fetchThread(tid, authorId, p) as? YamiboResult.Success)?.value
                                            },
                                            onProgress = { fetched, total ->
                                                exportTxtProgress = fetched to total
                                            },
                                        )
                                        exportingTxt = false
                                        exportTxtProgress = null
                                        when (result) {
                                            is NovelTxtExportResult.Success -> {
                                                txtExportFileActions.exportTxt(
                                                    novelTxtExporter.sanitizeFileName(result.title),
                                                    result.content,
                                                )
                                            }
                                            is NovelTxtExportResult.Failure -> {
                                                feedbackController.post(
                                                    message = i18n("导出TXT失败：{}", result.message),
                                                    duration = me.thenano.yamibo.yamibo_app.feedback.AppFeedbackDuration.Short,
                                                )
                                            }
                                        }
                                    }
                                }
                            },
                            onContinueRead = {
                                val history = readHistory
                                if (history != null) {
                                    navigator.navigate(
                                        IThreadReaderScreen(
                                            tid = tid,
                                            title = current.page.thread.title,
                                            threadType = ReadHistoryRepository.ThreadEntryType.Novel,
                                            authorId = authorId,
                                            initialPage = history.page
                                        )
                                    )
                                } else {
                                    navigator.navigate(
                                        IThreadReaderScreen(
                                            tid = tid,
                                            title = current.page.thread.title,
                                            threadType = ReadHistoryRepository.ThreadEntryType.Novel,
                                            authorId = authorId,
                                            initialPage = 1
                                        )
                                    )
                                }
                            },
                            readingProgressText = readHistory?.let { h ->
                                buildString {
                                    append(i18n("第 {} 頁", h.page))
                                    if (h.postTitle.isNotEmpty()) {
                                        append(" ‧ ${h.postTitle}")
                                    }
                                }
                            },
                            isFavorited = isFavorited,
                            feedbackController = feedbackController,
                            scope = scope,
                            platformContext = platformContext,
                            noteContent = detailNote?.content.orEmpty(),
                            onNoteClick = { showNoteDialog = true },
                            bookmarkedPostIds = postBookMarkEntries.values
                                .filter { it.bookmarked }
                                .mapTo(mutableSetOf()) { it.targetId },
                            readPostIds = postBookMarkEntries.values
                                .filter { it.read }
                                .mapTo(mutableSetOf()) { it.targetId } +
                                postChapterStates.values
                                    .filter { it.read }
                                    .mapTo(mutableSetOf()) { it.targetId },
                            chapterStates = postChapterStates,
                        )
                    }
            }
        }
    }

if (showFavoriteDialog) {
    val target = FavoriteTargetPayload.Thread(
        tid = tid,
        title = (state as? ThreadState.Success)?.page?.thread?.title ?: title,
        threadType = ReadHistoryRepository.ThreadEntryType.Novel,
        authorId = authorId,
        coverUrl = readHistory?.threadCover,
        lastUpdatedTime = (state as? ThreadState.Success)?.page?.posts?.firstOrNull { it.floor == 1 }?.let {
            it.lastEditedTime?.epochMillisOrNull() ?: it.timeCreate.epochMillisOrNull()
        },
        forumId = (state as? ThreadState.Success)?.page?.thread?.forum?.fid,
        forumName = (state as? ThreadState.Success)?.page?.thread?.forum?.name
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
                    if (target.supportsRemoteWebsiteSync() && appSettingsRepo.favoriteAddSyncPromptEnabled.getValue()) {
                        showFavoriteAddSyncConfirm = true
                    } else {
                        completeSavedFavoriteSync(
                            syncToRemote = target.supportsRemoteWebsiteSync() && appSettingsRepo.favoriteAddSyncDefault.getValue(),
                        )
                    }
                } else if (selectedCategories.isEmpty() && selectedCollections.isEmpty()) {
                    showFavoriteDialog = false
                    pendingFavoriteRemovalSelection = favoriteRepository.getFavoriteLocationSelection(target)
                    pendingFavoriteRemovalSuccessMessage = i18n("已從所有位置移除收藏")
                    if (appSettingsRepo.skipFavoriteRemovalConfirm.getValue()) {
                        if ((pendingFavoriteRemovalSelection?.paths?.size ?: 0) > 1) {
                            showFavoriteMultiPathDialog = true
                        } else {
                            maybePromptRemoteRemoval()
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

if (showFavoriteRemovalConfirm) {
    FavoriteRemovalConfirmDialog(
        onDismiss = {
            showFavoriteRemovalConfirm = false
            pendingFavoriteRemovalSelection = null
        },
        onConfirm = { skipNextTime ->
            appSettingsRepo.skipFavoriteRemovalConfirm.setValue(skipNextTime)
            showFavoriteRemovalConfirm = false
            scope.launch {
                val selection = pendingFavoriteRemovalSelection
                if ((selection?.paths?.size ?: 0) > 1) {
                    showFavoriteMultiPathDialog = true
                } else {
                    pendingFavoriteRemovalSuccessMessage = i18n("已移除收藏")
                    maybePromptRemoteRemoval()
                }
            }
        },
    )
}

if (showFavoriteAddSyncConfirm) {
    FavoriteAddSyncConfirmDialog(
        onDismiss = {
            showFavoriteAddSyncConfirm = false
            launchFavoriteTask("sync") { completeSavedFavoriteSync(syncToRemote = false) }
        },
        onConfirm = { rememberChoice, syncRemote ->
            showFavoriteAddSyncConfirm = false
            if (rememberChoice) {
                appSettingsRepo.favoriteAddSyncPromptEnabled.setValue(false)
                appSettingsRepo.favoriteAddSyncDefault.setValue(syncRemote)
            }
            launchFavoriteTask("sync") { completeSavedFavoriteSync(syncRemote) }
        },
    )
}

if (showFavoriteRemoveSyncConfirm) {
    FavoriteRemoveSyncConfirmDialog(
        onDismiss = {
            showFavoriteRemoveSyncConfirm = false
            pendingFavoriteRemovalSelection = null
        },
        onConfirm = { rememberChoice, syncRemote ->
            showFavoriteRemoveSyncConfirm = false
            if (rememberChoice) {
                appSettingsRepo.favoriteRemoveSyncPromptEnabled.setValue(false)
                appSettingsRepo.favoriteRemoveSyncDefault.setValue(syncRemote)
            }
            launchFavoriteTask("remove") { completeFavoriteRemoval(syncRemote) }
        },
    )
}

if (showFavoriteMultiPathDialog) {
    FavoriteMultiPathRemoveDialog(
        paths = pendingFavoriteRemovalSelection?.paths.orEmpty(),
        tip = i18n("tip：長按可詳細編輯收藏路徑"),
        onDismiss = {
            showFavoriteMultiPathDialog = false
            pendingFavoriteRemovalSelection = null
        },
        onRemoveAll = {
            showFavoriteMultiPathDialog = false
            pendingFavoriteRemovalSuccessMessage = i18n("已從所有位置移除收藏")
            scope.launch {
                maybePromptRemoteRemoval()
            }
        },
    )
}

if (showNoteDialog) {
    DetailNoteEditorDialog(
        initialContent = detailNote?.content.orEmpty(),
        onDismiss = { showNoteDialog = false },
        onSave = { content ->
            showNoteDialog = false
            scope.launch {
                detailNoteRepository.saveNote(
                    targetType = DetailNoteRepository.TargetType.NovelThread,
                    targetId = tid.value.toLong(),
                    authorId = noteAuthorId,
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
                    targetType = DetailNoteRepository.TargetType.NovelThread,
                    targetId = tid.value.toLong(),
                    authorId = noteAuthorId,
                )
                reloadNote()
                feedbackController.post(i18n("已刪除筆記"), duration = me.thenano.yamibo.yamibo_app.feedback.AppFeedbackDuration.Short)
            }
        },
    )
}

actionPost?.let { post ->
    val entry = postBookMarkEntries[post.pid.value.toLong()]
    val chapterState = postChapterStates[post.pid.value.toLong()]
    BookMarkActionDialog(
        bookmarked = entry?.bookmarked == true,
        read = entry?.read == true || chapterState?.read == true,
        onDismiss = { actionPost = null },
        onToggleBookMark = {
            actionPost = null
            scope.launch {
                val next = entry?.bookmarked != true
                bookMarkRepository.setBookmarked(
                    targetType = BookMarkRepository.TargetType.ThreadPost,
                    parentId = tid.value.toLong(),
                    targetId = post.pid.value.toLong(),
                    title = post.title.ifBlank { i18n("（無標題）") },
                    bookmarked = next,
                )
                reloadPostBookMarks()
                feedbackController.post(
                    if (next) i18n("已新增書籤") else i18n("已移除書籤"),
                    duration = me.thenano.yamibo.yamibo_app.feedback.AppFeedbackDuration.Short,
                )
            }
        },
        onToggleRead = {
            actionPost = null
            scope.launch {
                val next = !(entry?.read == true || chapterState?.read == true)
                bookMarkRepository.setRead(
                    targetType = BookMarkRepository.TargetType.ThreadPost,
                    parentId = tid.value.toLong(),
                    targetId = post.pid.value.toLong(),
                    title = post.title.ifBlank { i18n("（無標題）") },
                    read = next,
                )
                chapterStateRepository.setRead(
                    targetType = ChapterStateRepository.TargetType.ThreadPost,
                    parentId = tid.value.toLong(),
                    targetId = post.pid.value.toLong(),
                    title = post.title.ifBlank { i18n("（無標題）") },
                    read = next,
                )
                reloadPostBookMarks()
                feedbackController.post(
                    if (next) i18n("已標為已讀") else i18n("已標為未讀"),
                    duration = me.thenano.yamibo.yamibo_app.feedback.AppFeedbackDuration.Short,
                )
            }
        },
        onClearHistory = {
            actionPost = null
            scope.launch {
                bookMarkRepository.clearParent(
                    targetType = BookMarkRepository.TargetType.ThreadPost,
                    parentId = tid.value.toLong(),
                )
                chapterStateRepository.clearParent(
                    targetType = ChapterStateRepository.TargetType.ThreadPost,
                    parentId = tid.value.toLong(),
                )
                readHistoryRepo.deleteHistory(tid, ReadHistoryRepository.ThreadEntryType.Novel, authorId)
                readHistory = null
                reloadPostBookMarks()
                feedbackController.post(i18n("已清除全部閱讀紀錄"), duration = me.thenano.yamibo.yamibo_app.feedback.AppFeedbackDuration.Short)
            }
        },
    )
}

if (exportingTxt) {
    val progress = exportTxtProgress
    ExportTxtProgressDialog(
        currentPage = progress?.first ?: 1,
        totalPages = progress?.second ?: 1,
    )
}
}

/** Thread content body */
@Composable
private fun ThreadContent(
    threadPage: ThreadPage,
    coverUrl: String?,
    pagePostsCache: Map<Int, List<Post>>,
    expandedPages: Set<Int>,
    onTogglePage: (Int) -> Unit,
    onLoadPage: (Int) -> Unit,
    onPostClick: (Int, Post) -> Unit,
    onPostLongPress: (Post) -> Unit,
    onFavorite: () -> Unit,
    onFavoriteLongPress: () -> Unit,
    onExportTxt: () -> Unit,
    isFavorited: Boolean,
    onContinueRead: () -> Unit,
    readingProgressText: String?,
    feedbackController: me.thenano.yamibo.yamibo_app.feedback.AppFeedbackController,
    scope: CoroutineScope,
    platformContext: PlatformContext,
    noteContent: String,
    onNoteClick: () -> Unit,
    bookmarkedPostIds: Set<Long>,
    readPostIds: Set<Long>,
    chapterStates: Map<Long, ChapterStateRepository.Entry>,
) {
    val colors = YamiboTheme.colors
    val thread = threadPage.thread
    val firstPost = threadPage.posts.firstOrNull()
    val totalPages = threadPage.pageNav?.totalPages ?: 1

    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        /** Thread Header */
        item {
            ThreadHeader(
                threadPage = threadPage,
                coverUrlOverride = coverUrl,
                isFavorited = isFavorited,
                onFavorite = onFavorite,
                onFavoriteLongPress = onFavoriteLongPress,
                onShare = {
                    val url = YamiboRoute.Thread(thread.tid).build()
                    shareText(platformContext, url, thread.title)
                },
                onContinueRead = onContinueRead,
                onExportTxt = onExportTxt,
                readingProgressText = readingProgressText,
                noteContent = noteContent,
                onNoteClick = onNoteClick,
                onCopy = { message ->
                    scope.launch {
                        feedbackController.post(
                            message = message,
                            duration = me.thenano.yamibo.yamibo_app.feedback.AppFeedbackDuration.Short
                        )
                    }
                }
            )
        }

        if (noteContent.isNotBlank()) {
            item {
                DetailNoteCard(
                    content = noteContent,
                    onEdit = onNoteClick,
                )
            }
        }

        /** First floor preview */
        if (firstPost != null) {
            item { FirstFloorPreview(post = firstPost) }
        }

        /** Divider */
        item {
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = colors.brownPrimary.copy(alpha = 0.15f)
            )
        }

        /** Page sections */
        items(totalPages) { index ->
            val page = index + 1
            val isExpanded = page in expandedPages
            val posts = pagePostsCache[page]

            PostPageSection(
                page = page,
                isExpanded = isExpanded,
                posts = posts,
                bookmarkedPostIds = bookmarkedPostIds,
                readPostIds = readPostIds,
                chapterStates = chapterStates,
                isFirstPage = page == 1,
                onToggle = {
                    onTogglePage(page)
                    if (posts == null) {
                        onLoadPage(page)
                    }
                },
                onPostClick = { post -> onPostClick(page, post) },
                onPostLongPress = onPostLongPress,
            )
        }
    }
}

@Composable
private fun BookMarkActionDialog(
    bookmarked: Boolean,
    read: Boolean,
    onDismiss: () -> Unit,
    onToggleBookMark: () -> Unit,
    onToggleRead: () -> Unit,
    onClearHistory: () -> Unit,
) {
    val colors = YamiboTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(i18n("閱讀標記"), color = colors.textStrong) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                YamiboActionRow(if (bookmarked) i18n("移除書籤") else i18n("新增書籤"), onToggleBookMark)
                YamiboActionRow(if (read) i18n("標為未讀") else i18n("標為已讀"), onToggleRead)
                YamiboActionRow(i18n("清除全部閱讀紀錄"), onClearHistory)
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(i18n("取消"), color = colors.textStrong) }
        },
        containerColor = colors.creamSurface,
    )
}

@Composable
private fun YamiboActionRow(text: String, onClick: () -> Unit) {
    val colors = YamiboTheme.colors
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = colors.creamBackground,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            color = colors.textDark,
        )
    }
}

@Composable
private fun ExportTxtProgressDialog(
    currentPage: Int,
    totalPages: Int,
) {
    val colors = YamiboTheme.colors
    AlertDialog(
        onDismissRequest = {},
        title = { Text(i18n("正在导出TXT..."), color = colors.textStrong) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(color = colors.brownPrimary)
                Text(
                    text = i18n("正在读取第 {} 页 / 共 {} 页", currentPage, totalPages),
                    color = colors.textDark.copy(alpha = 0.68f),
                    fontSize = 13.sp,
                )
            }
        },
        confirmButton = {},
        dismissButton = {},
        containerColor = colors.creamSurface,
    )
}

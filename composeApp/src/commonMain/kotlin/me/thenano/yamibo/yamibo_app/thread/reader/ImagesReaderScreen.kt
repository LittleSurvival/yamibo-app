package me.thenano.yamibo.yamibo_app.thread.reader

import YamiboIcons
import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.ButtonDefaults.outlinedButtonColors
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import io.github.littlesurvival.YamiboForum
import io.github.littlesurvival.YamiboRoute
import io.github.littlesurvival.core.YamiboResult
import io.github.littlesurvival.dto.model.ThreadSummary
import io.github.littlesurvival.dto.page.TagPage
import io.github.littlesurvival.dto.page.ThreadPage
import io.github.littlesurvival.dto.value.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import me.thenano.yamibo.yamibo_app.*
import me.thenano.yamibo.yamibo_app.components.systembars.SystemBarsEffect
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme
import me.thenano.yamibo.yamibo_app.components.tracking.ReadingTimeTracker
import me.thenano.yamibo.yamibo_app.i18n.i18n
import me.thenano.yamibo.yamibo_app.navigation.LocalNavigator
import me.thenano.yamibo.yamibo_app.repository.ChapterStateRepository
import me.thenano.yamibo.yamibo_app.repository.ContentCoverRepository
import me.thenano.yamibo.yamibo_app.repository.ReadHistoryRepository
import me.thenano.yamibo.yamibo_app.repository.download.DownloadStatus
import me.thenano.yamibo.yamibo_app.repository.download.DownloadTaskKey
import me.thenano.yamibo.yamibo_app.repository.download.RssMangaChapterDownloadKey
import me.thenano.yamibo.yamibo_app.repository.download.TagMangaChapterDownloadKey
import me.thenano.yamibo.yamibo_app.repository.settings.EffectiveReadingModeSource
import me.thenano.yamibo.yamibo_app.repository.settings.ReadingMode
import me.thenano.yamibo.yamibo_app.repository.settings.TouchZoneLayout
import me.thenano.yamibo.yamibo_app.repository.settings.resolveEffectiveReadingMode
import me.thenano.yamibo.yamibo_app.task.AppTaskDuplicatePolicy
import me.thenano.yamibo.yamibo_app.task.AppTaskKey
import me.thenano.yamibo.yamibo_app.thread.detail.novel.INovelThreadDetailScreen
import me.thenano.yamibo.yamibo_app.thread.image.ImageContextMenu
import me.thenano.yamibo.yamibo_app.thread.image.ImageViewer
import me.thenano.yamibo.yamibo_app.thread.reader.components.manga.*
import me.thenano.yamibo.yamibo_app.util.buildImageRequest
import me.thenano.yamibo.yamibo_app.util.shareText
import me.thenano.yamibo.yamibo_app.util.state
import me.thenano.yamibo.yamibo_app.util.time.currentTimeMillis
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds

private data class ImageReaderPageTarget(
    val threadId: Int,
    val page: Int,
    val transitionSerial: Int,
    val directionOverride: Int = 0,
)

@Suppress("DuplicatedCode")
@Composable
fun ImagesReaderScreen(
    tid: ThreadId,
    postId: PostId?,
    fid: ForumId?,
    threadTitle: String,
    imageList: List<String>,
    initialPage: Int = 1,
    loadHistory: Boolean = false,

    // Tag Manga Mode Fields:
    tagId: TagId? = null,
    tagName: String? = null,
    tagPage: Int? = null,
    tagTotalPages: Int? = null,
    authorId: UserId?,
    tagThreads: List<ThreadSummary>?,
    rssSubscriptionId: Long? = null,
    rssTitle: String? = null,
    rssQuery: String? = null,
    rssPage: Int? = null,
    rssTotalPages: Int? = null,
    rssThreads: List<ThreadSummary>? = null,
) {
    SystemBarsEffect(
        statusBarColor = Color.Black,
        navigationBarColor = Color.Black,
        priority = 20,
        darkStatusBarIcons = false,
        darkNavigationBarIcons = false,
    )
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val feedbackController = LocalAppFeedbackController.current
    val appTaskManager = LocalAppTaskManager.current
    val historyRepo = LocalReadHistoryRepository.current
    val contentCoverRepository = LocalContentCoverRepository.current
    val threadRepository = LocalThreadRepository.current
    val downloadRepository = LocalDownloadRepository.current
    val downloadQueue by downloadRepository.queue.collectAsState()
    val chapterStateRepository = LocalChapterStateRepository.current
    val platformContext = LocalPlatformContext.current
    val authRepository = LocalAuthRepository.current
    ReadingTimeTracker()

    val isCatalogMode = tagId != null || rssSubscriptionId != null
    val catalogTitle = tagName ?: rssTitle
    val initialCatalogPage = tagPage ?: rssPage ?: 1
    val initialCatalogTotalPages = tagTotalPages ?: rssTotalPages
    val initialCatalogThreads = tagThreads ?: rssThreads ?: emptyList()

    // Catalog specific state (Tag Manga / RSS Search)
    var currentTagPage by remember { mutableStateOf(initialCatalogPage) }
    var currentTagTotalPages by remember { mutableStateOf(initialCatalogTotalPages) }
    var currentThreads by remember { mutableStateOf(initialCatalogThreads) }

    var activeTid by remember(tid) { mutableStateOf(tid) }

    // Computed properties for the currently active thread

    val currentThreadIndex: () -> Int = { currentThreads.indexOfFirst { it.tid == activeTid } }
    val activeThread = if (currentThreadIndex() in currentThreads.indices) currentThreads[currentThreadIndex()] else null
    val activeTitle = activeThread?.title ?: threadTitle
    val activeAuthorId = activeThread?.author?.uid ?: authorId
    val activeFid = activeThread?.fid ?: fid

    val isMangaForum = remember(activeFid, isCatalogMode) { isCatalogMode || (activeFid?.let { YamiboForum.isMangaForum(it) } == true) }

    val mangaSettingsRepo = LocalMangaReaderSettingsRepository.current
    val imageReaderModeOverrideRepository = LocalImageReaderModeOverrideRepository.current
    val globalReadingMode = mangaSettingsRepo.readingMode.state()
    val tagLongStripEnabled by remember(tagId, rssSubscriptionId) {
        when {
            tagId != null -> imageReaderModeOverrideRepository.observeTagLongStrip(tagId)
            rssSubscriptionId != null -> imageReaderModeOverrideRepository.observeRssLongStrip(rssSubscriptionId)
            else -> flowOf(false)
        }
    }.collectAsState(false)
    val threadModeOverride by remember(activeTid, activeAuthorId) {
        imageReaderModeOverrideRepository.observeThreadMode(activeTid, activeAuthorId)
    }.collectAsState(null)
    val effectiveReadingMode = remember(globalReadingMode, tagLongStripEnabled, threadModeOverride) {
        resolveEffectiveReadingMode(
            global = globalReadingMode,
            catalogLongStrip = tagLongStripEnabled,
            threadOverride = threadModeOverride,
        )
    }
    val readingMode = effectiveReadingMode.mode
    val modeSource = effectiveReadingMode.source
    val touchZoneLayout = mangaSettingsRepo.touchZone.state()
    val reverseTouchZones = mangaSettingsRepo.reverseTouchZones.state()
    
    val isRtl = readingMode == ReadingMode.SINGLE_RTL
    var tagThreadChapterStates by remember { mutableStateOf<Map<Long, ChapterStateRepository.Entry>>(emptyMap()) }

    fun catalogChapterTargetType(): ChapterStateRepository.TargetType? = when {
        tagId != null -> ChapterStateRepository.TargetType.TagMangaThread
        rssSubscriptionId != null -> ChapterStateRepository.TargetType.RssSearchThread
        else -> null
    }

    fun catalogParentId(): Long? = when {
        tagId != null -> tagId.value.toLong()
        rssSubscriptionId != null -> rssSubscriptionId
        else -> null
    }

    suspend fun reloadTagThreadChapterStates() {
        val targetType = catalogChapterTargetType() ?: return
        val parentId = catalogParentId() ?: return
        tagThreadChapterStates = chapterStateRepository
            .getEntriesByParent(
                targetType = targetType,
                parentId = parentId,
            )
            .associateBy { it.targetId }
    }

    LaunchedEffect(tagId, rssSubscriptionId) {
        reloadTagThreadChapterStates()
    }

    suspend fun syncTagMangaProgress(pageIndex: Int, totalPages: Int) {
        val targetType = catalogChapterTargetType() ?: return
        val parentId = catalogParentId() ?: return
        val currentIndex = currentThreadIndex()
        if (currentIndex < 0 || totalPages <= 0) return

        val thread = currentThreads.getOrNull(currentIndex) ?: return
        val imagePageIndex = pageIndex.coerceIn(0, totalPages - 1)
        val progressPercent = (((imagePageIndex + 1) * 100f) / totalPages)
            .toInt()
            .coerceIn(1, 100)
        val existing = chapterStateRepository.getEntry(
            targetType = targetType,
            parentId = parentId,
            targetId = thread.tid.value.toLong(),
        )
        if (existing?.read == true && progressPercent < 100) return

        chapterStateRepository.upsertProgress(
            targetType = targetType,
            parentId = parentId,
            targetId = thread.tid.value.toLong(),
            title = thread.title.ifBlank { i18n("（無標題）") },
            progressPercent = progressPercent,
            read = progressPercent >= 100,
            lastPageIndex = imagePageIndex,
            totalPages = totalPages,
        )
        reloadTagThreadChapterStates()
    }

    val isVerticalMode = readingMode == ReadingMode.SINGLE_TTB
    val isScrollMode = readingMode == ReadingMode.SCROLL_CONTINUOUS || readingMode == ReadingMode.SCROLL_GAP

    var actualImageList by remember(activeTid) { mutableStateOf(if (activeTid == tid) imageList else emptyList()) }
    var isLoadingImages by remember { mutableStateOf(actualImageList.isEmpty()) }
    var actualPostId by remember(activeTid) { mutableStateOf(if (activeTid == tid) postId else null) }
    var startFromLastPage by remember { mutableStateOf(false) }
    var restoreHistoryForActiveThread by remember { mutableStateOf(loadHistory) }
    var pageTurnDirection by remember { mutableIntStateOf(0) }
    var pageTransitionSerial by remember { mutableIntStateOf(0) }

    /** State */
    var showOverlay by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showTouchZonePreview by remember { mutableStateOf(false) }
    var showContextMenu by remember { mutableStateOf(false) }
    var showDownloadedRefreshConfirm by remember { mutableStateOf(false) }
    var isDownloadedTagChapter by remember { mutableStateOf(false) }
    var observedDownloadedTagChapter by remember { mutableStateOf<Boolean?>(null) }
    var contextMenuImageUrl by remember { mutableStateOf("") }
    // Tag Catalog State
    var showCatalog by remember { mutableStateOf(false) }
    val tagRepository = LocalTagRepository.current
    val rssRepository = LocalRssSearchSubscriptionRepository.current
    val loadedThreadsByPage = remember { mutableStateMapOf<Int, List<ThreadSummary>>() }

    suspend fun loadCatalogPage(page: Int, preferCache: Boolean = false): TagPage? {
        return when {
            tagId != null -> {
                val cached = if (preferCache) tagRepository.getCachedTagPage(tagId, page) else null
                cached ?: when (val result = tagRepository.fetchTagPage(tagId, page)) {
                    is YamiboResult.Success -> result.value.copy(pageNav = result.value.pageNav?.copy(currentPage = page))
                    else -> null
                }
            }
            rssSubscriptionId != null -> {
                val catalog = if (preferCache) {
                    rssRepository.getCachedCatalogPage(rssSubscriptionId, page)
                        ?: rssRepository.getCatalogPage(rssSubscriptionId, page)
                } else {
                    rssRepository.getCatalogPage(rssSubscriptionId, page)
                }
                catalog?.tagPage?.copy(pageNav = catalog.tagPage.pageNav?.copy(currentPage = page))
            }
            else -> null
        }
    }

    LaunchedEffect(showCatalog, tagId, rssSubscriptionId) {
        if (showCatalog) {
            reloadTagThreadChapterStates()
        }
    }

    LaunchedEffect(currentTagPage, currentThreads) {
        if (currentThreads.isNotEmpty()) {
            loadedThreadsByPage[currentTagPage] = currentThreads
        }
    }

    LaunchedEffect(tagId, rssSubscriptionId, currentTagPage) {
        if (isCatalogMode && currentThreads.isEmpty()) {
            val result = loadCatalogPage(currentTagPage, preferCache = true)
            if (result != null) {
                currentThreads = result.threadSummaries
                currentTagTotalPages = result.pageNav?.totalPages ?: currentTagTotalPages
                val idx = currentThreads.indexOfFirst { it.tid == activeTid }
                if (idx == -1 && currentThreads.isNotEmpty() && activeTid.value == 0) { // Only fallback if dummy tid
                    activeTid = currentThreads.first().tid
                }
            } else {
                feedbackController.post(i18n("無法載入閱讀目錄"))
            }
        }
    }
    
    var prevThreadTitle by remember { mutableStateOf<String?>(null) }
    var nextThreadTitle by remember { mutableStateOf<String?>(null) }
    var hasPrevChapter by remember { mutableStateOf(false) }
    var hasNextChapter by remember { mutableStateOf(false) }

    LaunchedEffect(currentThreadIndex(), currentThreads, currentTagPage, isLoadingImages, isCatalogMode, currentTagTotalPages) {
        if (isCatalogMode) {
            if (currentThreadIndex() > 0) {
                hasPrevChapter = true
                prevThreadTitle = currentThreads.getOrNull(currentThreadIndex() - 1)?.title
            } else if (currentTagPage > 1) {
                hasPrevChapter = true
                prevThreadTitle = if (isLoadingImages) i18n("正在載入上一個分頁...") else i18n("上一個分頁")
            } else {
                hasPrevChapter = false
                prevThreadTitle = null
            }

            if (currentThreadIndex() >= 0 && currentThreadIndex() < currentThreads.lastIndex) {
                hasNextChapter = true
                nextThreadTitle = currentThreads.getOrNull(currentThreadIndex() + 1)?.title
            } else if (currentTagPage < (currentTagTotalPages ?: 1)) {
                hasNextChapter = true
                nextThreadTitle = if (isLoadingImages) i18n("正在載入下一個分頁...") else i18n("下一個分頁")
            } else {
                hasNextChapter = false
                nextThreadTitle = null
            }
        }
    }

    val scrollListState = rememberLazyListState()
    val totalContentPages: () -> Int = { actualImageList.size.coerceAtLeast(1) }
    val minPage: () -> Int = { if (isCatalogMode) -1 else 0 }
    val maxPage: () -> Int = { if (isCatalogMode) totalContentPages() else totalContentPages() - 1 }

    var currentPage by remember { mutableIntStateOf(initialPage - 1) }

    var retryTrigger by remember { mutableIntStateOf(0) }
    var forceNetworkLoadTrigger by remember { mutableIntStateOf(0) }

    var snapNextTransition by remember { mutableStateOf(false) }

    LaunchedEffect(tagId, rssSubscriptionId, currentTagPage) {
        if (isCatalogMode && currentThreads.isEmpty()) {
            val result = loadCatalogPage(currentTagPage, preferCache = true)
            if (result != null) {
                currentThreads = result.threadSummaries
                currentTagTotalPages = result.pageNav?.totalPages ?: 1
                loadedThreadsByPage[currentTagPage] = currentThreads
            }
        }
    }

    fun activeTagMangaDownloadKey(): TagMangaChapterDownloadKey? {
        val currentTagId = tagId ?: return null
        return TagMangaChapterDownloadKey(
            tagId = currentTagId.value,
            tid = activeTid.value,
            authorId = activeAuthorId?.value,
        )
    }

    fun activeRssMangaDownloadKey(): RssMangaChapterDownloadKey? {
        val currentSubscriptionId = rssSubscriptionId ?: return null
        return RssMangaChapterDownloadKey(
            subscriptionId = currentSubscriptionId,
            tid = activeTid.value,
            authorId = activeAuthorId?.value,
        )
    }

    fun activeCatalogDownloadKey(): DownloadTaskKey? =
        activeTagMangaDownloadKey() ?: activeRssMangaDownloadKey()

    LaunchedEffect(downloadQueue, activeTid, activeAuthorId, tagId, rssSubscriptionId) {
        val key = activeCatalogDownloadKey()
        val downloaded = key != null && downloadQueue.any {
            it.key == key && it.status == DownloadStatus.Downloaded
        }
        val previous = observedDownloadedTagChapter
        if (previous == false && downloaded) {
            feedbackController.post(i18n("下載完成"))
        }
        observedDownloadedTagChapter = downloaded
    }

    LaunchedEffect(activeTid, retryTrigger, forceNetworkLoadTrigger) {
        if (actualImageList.isEmpty()) {
            isLoadingImages = true

            val downloadKey = activeCatalogDownloadKey()
            if (downloadKey != null && forceNetworkLoadTrigger == 0) {
                val localImages = when (downloadKey) {
                    is TagMangaChapterDownloadKey -> downloadRepository.getTagMangaChapterImages(downloadKey)
                    is RssMangaChapterDownloadKey -> downloadRepository.getRssMangaChapterImages(downloadKey)
                    else -> null
                }
                if (!localImages.isNullOrEmpty()) {
                    actualPostId = null
                    actualImageList = localImages
                    isDownloadedTagChapter = true
                    currentPage = currentPage.coerceIn(minPage(), maxPage())
                    if (loadHistory && restoreHistoryForActiveThread) {
                        val history = when (downloadKey) {
                            is TagMangaChapterDownloadKey -> historyRepo.getTagMangaReaderModeHistoryPosition(TagId(downloadKey.tagId))
                            is RssMangaChapterDownloadKey -> historyRepo.getRssSearchReaderModeHistoryPosition(downloadKey.subscriptionId)
                            else -> null
                        }
                        val historyThreadId = when (history) {
                            is ReadHistoryRepository.TagMangaReadingHistory -> history.threadId
                            is ReadHistoryRepository.RssSearchReadingHistory -> history.threadId
                            else -> null
                        }
                        val historyPageIndex = when (history) {
                            is ReadHistoryRepository.TagMangaReadingHistory -> history.threadImagePageIndex
                            is ReadHistoryRepository.RssSearchReadingHistory -> history.threadImagePageIndex
                            else -> null
                        }
                        if (historyThreadId == activeTid && historyPageIndex != null) {
                            snapNextTransition = true
                            currentPage = historyPageIndex.coerceIn(0, (localImages.size - 1).coerceAtLeast(0))
                        }
                    }
                    restoreHistoryForActiveThread = false
                    isLoadingImages = false
                    return@LaunchedEffect
                }
            }
            
            suspend fun processThreadResult(result: YamiboResult<ThreadPage>? = null, cached: ThreadPage? = null) {
                val threadPage = cached ?: (result as? YamiboResult.Success)?.value
                if (threadPage != null) {
                    val p = threadPage.posts.firstOrNull()
                    if (p != null) {
                        actualPostId = p.pid
                        val targetAuthorId = activeAuthorId ?: p.author.uid
                        val authorPosts = threadPage.posts.filter { it.author.uid == targetAuthorId }.take(2)
                        actualImageList = authorPosts.flatMap { it.images }.map { it.url }
                        isDownloadedTagChapter = false
                        
                        // Coerce the initial page against the actual loaded bounds now
                        currentPage = currentPage.coerceIn(minPage(), maxPage())

                        if (startFromLastPage && actualImageList.isNotEmpty()) {
                            snapNextTransition = true
                            currentPage = actualImageList.size - 1
                        } else if (loadHistory && restoreHistoryForActiveThread) {
                            var didRestore = false

                            fun onRestoreScrollListState(history: ReadHistoryRepository.AnyReadingHistory) {
                                when(history) {
                                    is ReadHistoryRepository.TagMangaReadingHistory,
                                    is ReadHistoryRepository.RssSearchReadingHistory,
                                    is ReadHistoryRepository.ImageReadingHistory -> {
                                        snapNextTransition = true
                                        val historyPage = when (history) {
                                            is ReadHistoryRepository.TagMangaReadingHistory -> history.threadImagePageIndex
                                            is ReadHistoryRepository.RssSearchReadingHistory -> history.threadImagePageIndex
                                            is ReadHistoryRepository.ImageReadingHistory -> history.pageIndex
                                        }
                                        val finalPageToRestore = historyPage.coerceIn(0, (actualImageList.size - 1).coerceAtLeast(0))
                                        currentPage = finalPageToRestore
                                        val firstVisibleItemIndex = when (history) {
                                            is ReadHistoryRepository.TagMangaReadingHistory -> history.firstVisibleItemIndex
                                            is ReadHistoryRepository.RssSearchReadingHistory -> history.firstVisibleItemIndex
                                            is ReadHistoryRepository.ImageReadingHistory -> history.firstVisibleItemIndex
                                        }
                                        val firstVisibleItemOffset = when (history) {
                                            is ReadHistoryRepository.TagMangaReadingHistory -> history.firstVisibleItemOffset
                                            is ReadHistoryRepository.RssSearchReadingHistory -> history.firstVisibleItemOffset
                                            is ReadHistoryRepository.ImageReadingHistory -> history.firstVisibleItemOffset
                                        }
                                        if (firstVisibleItemIndex != null && finalPageToRestore > 0 && isScrollMode) {
                                            scope.launch {
                                                while (scrollListState.layoutInfo.totalItemsCount == 0) delay(20.milliseconds)
                                                scrollListState.scrollToItem(firstVisibleItemIndex, firstVisibleItemOffset ?: 0)
                                            }
                                        }
                                        didRestore = true
                                    }
                                    else -> return
                                }
                            }

                            if (tagId != null) {
                                val history = historyRepo.getTagMangaReaderModeHistoryPosition(tagId)
                                if (history != null && history.threadId == activeTid) {
                                    onRestoreScrollListState(history)
                                }
                            } else if (rssSubscriptionId != null) {
                                val history = historyRepo.getRssSearchReaderModeHistoryPosition(rssSubscriptionId)
                                if (history != null && history.threadId == activeTid) {
                                    onRestoreScrollListState(history)
                                }
                            }
                            if (!didRestore) {
                                val history = historyRepo.getImagePosition(p.pid)
                                if (history != null) {
                                    onRestoreScrollListState(history)
                                }
                            }
                            
                            if (!didRestore && isScrollMode) {
                                scope.launch {
                                    while (scrollListState.layoutInfo.totalItemsCount == 0) delay(20.milliseconds)
                                    scrollListState.scrollToItem(if (isCatalogMode) 1 else 0)
                                }
                            }
                        } else {
                            if (isScrollMode) {
                                scope.launch {
                                    while (scrollListState.layoutInfo.totalItemsCount == 0) delay(20.milliseconds)
                                    scrollListState.scrollToItem(if (isCatalogMode) 1 else 0)
                                }
                            }
                        }
                    }
                    startFromLastPage = false
                    restoreHistoryForActiveThread = false
                    isLoadingImages = false
                } else {
                    isLoadingImages = false
                }
            }

            val cachedPage = if (forceNetworkLoadTrigger == 0) threadRepository.getCachedThread(activeTid, null, 1) else null
            if (cachedPage != null) {
                processThreadResult(cached = cachedPage)
            } else {
                processThreadResult(result = threadRepository.fetchThread(activeTid, null, 1))
            }
            forceNetworkLoadTrigger = 0
        }
    }

    val currentHistorySaver by rememberUpdatedState {
        if (isMangaForum || isCatalogMode) {
            val isScroll = readingMode == ReadingMode.SCROLL_CONTINUOUS || readingMode == ReadingMode.SCROLL_GAP
            val finalIdx = if (isScroll) scrollListState.firstVisibleItemIndex else null
            val finalOffset = if (isScroll) scrollListState.firstVisibleItemScrollOffset else null
            val snapshotPage = currentPage
            val total = actualImageList.size
            val activeTidSnap = activeTid
            val postIdSnap = actualPostId
            val tagPageSnap = currentTagPage
            val currentCoverSnap = actualImageList.getOrNull(1) ?: actualImageList.getOrNull(0)
            
            appTaskManager.launch(
                key = AppTaskKey("reader-history:${activeTidSnap.value}"),
                duplicatePolicy = AppTaskDuplicatePolicy.AllowParallel,
                instanceId = "${tagPageSnap}:${snapshotPage}:${currentTimeMillis()}",
            ) {
                if (tagId != null && tagName != null) {
                    val finalCover = if (actualImageList.isEmpty()) {
                        historyRepo.getTagMangaReaderModeHistoryPosition(tagId)?.coverUrl
                    } else {
                        currentCoverSnap
                    }
                    finalCover?.let {
                        contentCoverRepository.setAutomaticCover(
                            ContentCoverRepository.Key(ContentCoverRepository.TargetType.TagManga, tagId.value.toLong()),
                            it,
                        )
                    }
                    historyRepo.saveTagMangaReaderModeHistory(
                        ReadHistoryRepository.TagMangaReadingHistory(
                            tagId = tagId,
                            tagName = tagName,
                            tagPage = tagPageSnap,
                            threadId = activeTidSnap,
                            threadTitle = activeTitle,
                            threadImagePageIndex = snapshotPage.coerceIn(minPage(), maxPage()),
                            threadImageTotalPages = total.coerceAtLeast(1),
                            firstVisibleItemIndex = finalIdx,
                            firstVisibleItemOffset = finalOffset,
                            lastVisitTime = currentTimeMillis(),
                            coverUrl = finalCover,
                        )
                    )
                    syncTagMangaProgress(snapshotPage.coerceIn(0, (total - 1).coerceAtLeast(0)), total.coerceAtLeast(1))
                } else if (rssSubscriptionId != null && catalogTitle != null) {
                    val finalCover = if (actualImageList.isEmpty()) {
                        historyRepo.getRssSearchReaderModeHistoryPosition(rssSubscriptionId)?.coverUrl
                    } else {
                        currentCoverSnap
                    }
                    finalCover?.let {
                        contentCoverRepository.setAutomaticCover(
                            ContentCoverRepository.Key(ContentCoverRepository.TargetType.RssSearch, rssSubscriptionId),
                            it,
                        )
                    }
                    historyRepo.saveRssSearchReaderModeHistory(
                        ReadHistoryRepository.RssSearchReadingHistory(
                            subscriptionId = rssSubscriptionId,
                            subscriptionTitle = catalogTitle,
                            subscriptionQuery = rssQuery ?: catalogTitle,
                            subscriptionPage = tagPageSnap,
                            threadId = activeTidSnap,
                            threadTitle = activeTitle,
                            threadImagePageIndex = snapshotPage.coerceIn(minPage(), maxPage()),
                            threadImageTotalPages = total.coerceAtLeast(1),
                            firstVisibleItemIndex = finalIdx,
                            firstVisibleItemOffset = finalOffset,
                            lastVisitTime = currentTimeMillis(),
                            coverUrl = finalCover,
                        )
                    )
                    syncTagMangaProgress(snapshotPage.coerceIn(0, (total - 1).coerceAtLeast(0)), total.coerceAtLeast(1))
                } else if (isMangaForum && postIdSnap != null) {
                    historyRepo.saveImagePosition(
                        ReadHistoryRepository.ImageReadingHistory(
                            postId = postIdSnap,
                            threadId = activeTidSnap,
                            pageIndex = snapshotPage,
                            totalPages = total,
                            firstVisibleItemIndex = finalIdx,
                            firstVisibleItemOffset = finalOffset,
                            lastVisitTime = currentTimeMillis()
                        )
                    )
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { currentHistorySaver() }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                currentHistorySaver()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(activeTid, currentPage, currentTagPage) {
        delay(2000.milliseconds)
        currentHistorySaver()
    }

    /** Animated zoom state */
    val scaleAnim = remember { Animatable(1f) }
    val offsetXAnim = remember { Animatable(0f) }
    val offsetYAnim = remember { Animatable(0f) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var pageDragOffset by remember { mutableFloatStateOf(0f) }
    var pageDragTarget by remember { mutableStateOf<Int?>(null) }
    var isPageDragSettling by remember { mutableStateOf(false) }
    var committedPageOverlay by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(readingMode) {
        if (isScrollMode) {
            val targetIndex = if (isCatalogMode) currentPage.coerceAtLeast(0) + 1 else currentPage.coerceAtLeast(0)
            if (targetIndex >= 0) {
                while (scrollListState.layoutInfo.totalItemsCount == 0 && actualImageList.isNotEmpty()) {
                    delay(20.milliseconds)
                }
                scrollListState.scrollToItem(targetIndex)
            }
        }
    }

    LaunchedEffect(isScrollMode, actualImageList, currentPage) {
        if (isScrollMode || actualImageList.isEmpty()) return@LaunchedEffect

        val imageLoader = SingletonImageLoader.get(platformContext)
        val cookie = authRepository.cookieStore.load().orEmpty()
        listOf(currentPage - 1, currentPage, currentPage + 1)
            .filter { it in actualImageList.indices }
            .distinct()
            .forEach { index ->
                imageLoader.enqueue(
                    buildImageRequest(
                        context = platformContext,
                        url = actualImageList[index],
                        cookie = cookie,
                        enableCrossfade = false,
                    )
                )
            }
    }

    /** Reset zoom with animation */
    fun resetZoom(animated: Boolean = false) {
        scope.launch {
            if (animated) {
                launch { scaleAnim.animateTo(1f, tween(250)) }
                launch { offsetXAnim.animateTo(0f, tween(250)) }
                launch { offsetYAnim.animateTo(0f, tween(250)) }
            } else {
                scaleAnim.snapTo(1f)
                offsetXAnim.snapTo(0f)
                offsetYAnim.snapTo(0f)
            }
        }
    }
    
    // Scroll Mode boundary jumper accumulator
    var scrollOverscrollY by remember { mutableFloatStateOf(0f) }

    fun prepareChapterSwitch(
        forward: Boolean,
        startAtLastPage: Boolean = false,
        startPageIndex: Int? = null,
    ) {
        scrollOverscrollY = 0f
        restoreHistoryForActiveThread = false
        startFromLastPage = startAtLastPage
        pageTurnDirection = if (forward) 1 else -1
        pageTransitionSerial++
        currentPage = startPageIndex?.coerceAtLeast(0) ?: 0
        actualImageList = emptyList()
        actualPostId = null
        resetZoom()
        if (isScrollMode) {
            scope.launch { scrollListState.scrollToItem(0) }
        }
    }

    fun setReaderPage(page: Int) {
        pageTurnDirection = 0
        currentPage = page
    }

    fun sameChapterPreviewSide(targetPage: Int): Int {
        val targetIsNext = targetPage > currentPage
        return if (isVerticalMode) {
            if (targetIsNext) 1 else -1
        } else if (isRtl) {
            if (targetIsNext) -1 else 1
        } else {
            if (targetIsNext) 1 else -1
        }
    }

    fun settleSameChapterDrag(targetPage: Int?, shouldCommit: Boolean) {
        val axisSize = if (isVerticalMode) containerSize.height else containerSize.width
        if (axisSize <= 0 || targetPage == null || targetPage !in actualImageList.indices) {
            pageDragOffset = 0f
            pageDragTarget = null
            isPageDragSettling = false
            return
        }

        scope.launch {
            isPageDragSettling = true
            val animation = Animatable(pageDragOffset)
            if (shouldCommit) {
                val side = sameChapterPreviewSide(targetPage)
                animation.animateTo(-side * axisSize.toFloat(), tween(180)) {
                    pageDragOffset = value
                }
                committedPageOverlay = targetPage
                snapNextTransition = true
                setReaderPage(targetPage)
                resetZoom()
                delay(360.milliseconds)
            } else {
                animation.animateTo(0f, tween(180)) {
                    pageDragOffset = value
                }
            }
            pageDragOffset = 0f
            pageDragTarget = null
            committedPageOverlay = null
            isPageDragSettling = false
        }
    }

    val launchNextChapter = {
        if (isCatalogMode && !isLoadingImages && hasNextChapter) {
            isLoadingImages = true
            scope.launch {
                if (currentThreadIndex() >= 0 && currentThreadIndex() < currentThreads.lastIndex) {
                    prepareChapterSwitch(forward = true)
                    activeTid = currentThreads[currentThreadIndex() + 1].tid
                } else if (currentTagPage < (currentTagTotalPages ?: 1)) {
                    val pageToLoad = currentTagPage + 1
                    val result = loadCatalogPage(pageToLoad, preferCache = true)
                    if (result != null) {
                        prepareChapterSwitch(forward = true)
                        currentThreads = result.threadSummaries
                        currentTagTotalPages = result.pageNav?.totalPages ?: currentTagTotalPages
                        currentTagPage = pageToLoad
                        if (currentThreads.isNotEmpty()) {
                            activeTid = currentThreads.first().tid
                        }
                    } else {
                        feedbackController.post(i18n("無法載入下一頁"))
                    }
                }
                delay(600.milliseconds)
                isLoadingImages = false
            }
        }
    }

    val launchPrevChapter = {
        if (isCatalogMode && !isLoadingImages && hasPrevChapter) {
            isLoadingImages = true
            scope.launch {
                if (currentThreadIndex() > 0) {
                    prepareChapterSwitch(forward = false, startAtLastPage = true)
                    activeTid = currentThreads[currentThreadIndex() - 1].tid
                } else if (currentTagPage > 1) {
                    val pageToLoad = currentTagPage - 1
                    val result = loadCatalogPage(pageToLoad, preferCache = true)
                    if (result != null) {
                        prepareChapterSwitch(forward = false, startAtLastPage = true)
                        currentThreads = result.threadSummaries
                        currentTagTotalPages = result.pageNav?.totalPages ?: currentTagTotalPages
                        currentTagPage = pageToLoad
                        if (currentThreads.isNotEmpty()) {
                            activeTid = currentThreads.last().tid
                        }
                    } else {
                        feedbackController.post(i18n("無法載入上一頁"))
                    }
                }
                delay(600.milliseconds)
                isLoadingImages = false
            }
        }
    }

    /** Handle back press: settings > overlay > touchZonePreview */
    fun handleBack(): Boolean {
        return when {
            showCatalog -> { showCatalog = false; true }
            showSettings -> { showSettings = false; true }
            showOverlay -> { showOverlay = false; true }
            showTouchZonePreview -> { showTouchZonePreview = false; true }
            else -> false
        }
    }

    /** Register back handler */
    DisposableEffect(Unit) {
        val handler = { handleBack() }
        navigator.backHandlers.add(handler)
        onDispose { navigator.backHandlers.remove(handler) }
    }

    /** Handle single tap touch zone logic */
    fun handleSingleTap(xFraction: Float, yFraction: Float) {
        if (showCatalog) { showCatalog = false; return }
        if (showSettings) { showSettings = false; return }
        if (showOverlay) return  // Overlay dismissed via its own scrim
        if (showTouchZonePreview) { showTouchZonePreview = false; return }

        if (touchZoneLayout != TouchZoneLayout.DISABLED) {
            val action = getEffectiveMangaTouchAction(
                layout = touchZoneLayout,
                xFraction = xFraction,
                yFraction = yFraction,
                reverseTouchZones = reverseTouchZones,
                isRtl = isRtl,
            )
            when (action) {
                TouchAction.PREV -> {
                    if (isScrollMode) {
                        if (currentPage == minPage() && hasPrevChapter) launchPrevChapter()
                        return
                    }
                    if (currentPage > minPage()) { setReaderPage(currentPage - 1); resetZoom() }
                    else if (currentPage == minPage() && hasPrevChapter) { launchPrevChapter() }
                }
                TouchAction.NEXT -> {
                    if (isScrollMode) {
                        if (currentPage == maxPage() && hasNextChapter) launchNextChapter()
                        return
                    }
                    if (currentPage < maxPage()) { setReaderPage(currentPage + 1); resetZoom() }
                    else if (currentPage == maxPage() && hasNextChapter) { launchNextChapter() }
                }
                TouchAction.MENU -> showOverlay = true
                null -> {}
            }
        } else {
            showOverlay = !showOverlay
        }
    }

    /** Handle double tap → animated zoom toggle */
    fun handleDoubleTap(tapOffset: Offset) {
        scope.launch {
            if (scaleAnim.value > 1.1f) {
                // Zoom Out
                launch { scaleAnim.animateTo(1f, tween(280)) }
                launch { offsetXAnim.animateTo(0f, tween(280)) }
                launch { offsetYAnim.animateTo(0f, tween(280)) }
            } else {
                // Zoom In
                val centerX = containerSize.width / 2f
                val centerY = containerSize.height / 2f
                val targetScale = 2.0f
                launch { scaleAnim.animateTo(targetScale, tween(280)) }
                launch { offsetXAnim.animateTo((centerX - tapOffset.x) * (targetScale - 1f), tween(280)) }
                launch { offsetYAnim.animateTo((centerY - tapOffset.y) * (targetScale - 1f), tween(280)) }
            }
        }
    }

    // Scroll Mode boundary jumper (Overscroll Chapter Jump)
    val nestedScrollConnection = remember(hasNextChapter, hasPrevChapter) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y > 0f && scrollListState.canScrollForward) scrollOverscrollY = 0f
                if (available.y < 0f && scrollListState.canScrollBackward) scrollOverscrollY = 0f
                return Offset.Zero
            }
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (source.toString() != "Fling" && source.toString() != "SideEffect") {
                    if (!scrollListState.canScrollForward && available.y < 0f) {
                        scrollOverscrollY += available.y
                        if (scrollOverscrollY < -100f && hasNextChapter) {
                            scrollOverscrollY = 0f
                            launchNextChapter()
                        }
                    } else if (!scrollListState.canScrollBackward && available.y > 0f) {
                        scrollOverscrollY += available.y
                        if (scrollOverscrollY > 100f && hasPrevChapter) {
                            scrollOverscrollY = 0f
                            launchPrevChapter()
                        }
                    } else {
                        scrollOverscrollY = 0f
                    }
                } else {
                    scrollOverscrollY = 0f
                }
                return Offset.Zero
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Zoomable Content Box
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { containerSize = it }
                // 1. Unified Tap Handler
                .pointerInput(touchZoneLayout, reverseTouchZones, readingMode, actualImageList.size) {
                    detectTapGestures(
                        onTap = { offset ->
                            val xFrac = offset.x / size.width.toFloat()
                            val yFrac = offset.y / size.height.toFloat()
                            handleSingleTap(xFrac, yFrac)
                        },
                        onDoubleTap = { handleDoubleTap(it) },
                        onLongPress = {
                            contextMenuImageUrl = actualImageList.getOrElse(currentPage) { "" }
                            showContextMenu = true
                        }
                    )
                }
                // 2. Swipe Handler for Single Page Mode
                .pointerInput(readingMode, actualImageList.size) {
                    if (!isScrollMode) {
                        var dragAccX = 0f
                        var dragAccY = 0f
                        var swipeVelocityTracker = VelocityTracker()
                        detectDragGestures(
                            onDragStart = {
                                dragAccX = 0f
                                dragAccY = 0f
                                swipeVelocityTracker = VelocityTracker()
                                isPageDragSettling = false
                                pageDragOffset = 0f
                                pageDragTarget = null
                            },
                            onDragEnd = {
                                val sameChapterTarget = pageDragTarget
                                var handledByPreviewAnimation = false
                                val axisSize = if (isVerticalMode) containerSize.height else containerSize.width
                                val velocity = swipeVelocityTracker.calculateVelocity()
                                val axisDrag = if (isVerticalMode) dragAccY else dragAccX
                                val axisVelocity = if (isVerticalMode) velocity.y else velocity.x
                                val dir = if (!isVerticalMode && isRtl) -1 else 1
                                val effectiveDrag = axisDrag * dir
                                val effectiveVelocity = axisVelocity * dir
                                val distanceCommit = axisSize > 0 && abs(effectiveDrag) >= axisSize * 0.4f
                                val velocityCommit = abs(effectiveVelocity) >= 1200f
                                val shouldTurnPage = distanceCommit || velocityCommit
                                val wantsNext = effectiveDrag < 0f || effectiveVelocity < -1200f
                                val wantsPrev = effectiveDrag > 0f || effectiveVelocity > 1200f
                                if (scaleAnim.value <= 1.05f) { // Only turn page if not zoomed in
                                    if (shouldTurnPage && wantsNext) {
                                        if (sameChapterTarget == currentPage + 1 && sameChapterTarget in actualImageList.indices) {
                                            handledByPreviewAnimation = true
                                            settleSameChapterDrag(sameChapterTarget, shouldCommit = true)
                                        } else if (currentPage < maxPage()) { setReaderPage(currentPage + 1); resetZoom() }
                                        else if (currentPage == totalContentPages() && hasNextChapter) { launchNextChapter() }
                                    } else if (shouldTurnPage && wantsPrev) {
                                        if (sameChapterTarget == currentPage - 1 && sameChapterTarget in actualImageList.indices) {
                                            handledByPreviewAnimation = true
                                            settleSameChapterDrag(sameChapterTarget, shouldCommit = true)
                                        } else if (currentPage > minPage()) { setReaderPage(currentPage - 1); resetZoom() }
                                        else if (currentPage == minPage() && hasPrevChapter) { launchPrevChapter() }
                                    }
                                }
                                if (!handledByPreviewAnimation) {
                                    if (sameChapterTarget != null) {
                                        settleSameChapterDrag(sameChapterTarget, shouldCommit = false)
                                    } else {
                                        pageDragOffset = 0f
                                        pageDragTarget = null
                                    }
                                }
                            },
                            onDragCancel = {
                                settleSameChapterDrag(pageDragTarget, shouldCommit = false)
                            }
                        ) { change, dragAmount ->
                            if (scaleAnim.value <= 1.05f && !isPageDragSettling) {
                                swipeVelocityTracker.addPosition(change.uptimeMillis, change.position)
                                dragAccX += dragAmount.x
                                dragAccY += dragAmount.y
                                val target = if (actualImageList.isNotEmpty() && currentPage in actualImageList.indices) {
                                    if (isVerticalMode) {
                                        when {
                                            dragAccY < 0f && currentPage < actualImageList.lastIndex -> currentPage + 1
                                            dragAccY > 0f && currentPage > 0 -> currentPage - 1
                                            else -> null
                                        }
                                    } else {
                                        val dir = if (isRtl) -1 else 1
                                        val effectiveDrag = dragAccX * dir
                                        when {
                                            effectiveDrag < 0f && currentPage < actualImageList.lastIndex -> currentPage + 1
                                            effectiveDrag > 0f && currentPage > 0 -> currentPage - 1
                                            else -> null
                                        }
                                    }
                                } else {
                                    null
                                }
                                pageDragTarget = target
                                pageDragOffset = if (target != null) {
                                    val rawOffset = if (isVerticalMode) dragAccY else dragAccX
                                    val axisSize = if (isVerticalMode) containerSize.height else containerSize.width
                                    if (axisSize > 0) {
                                        rawOffset.coerceIn(-axisSize.toFloat(), axisSize.toFloat())
                                    } else {
                                        rawOffset
                                    }
                                } else {
                                    0f
                                }
                                change.consume() // Prevent other gestures from seeing this unzoomed drag
                            }
                        }
                    }
                }
                // 3. Zoom and Pan Handler
                .pointerInput(readingMode) {
                    awaitEachGesture {
                        var pan: Offset
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var currentScale = scaleAnim.value
                        var currentOffsetX = offsetXAnim.value
                        var currentOffsetY = offsetYAnim.value
                        
                        var boundX: Float
                        var boundY: Float
                        var lastMoveTime = down.uptimeMillis
                        var isStaleFling = false
                        var isPanning = false

                        val velocityTracker = VelocityTracker()
                        velocityTracker.addPosition(down.uptimeMillis, down.position)

                        do {
                            val event = awaitPointerEvent()
                            if (event.changes.size == 1) {
                                val change = event.changes[0]
                                if (change.pressed) {
                                    velocityTracker.addPosition(change.uptimeMillis, change.position)
                                    if ((change.position - change.previousPosition).getDistance() > 1f) {
                                        lastMoveTime = change.uptimeMillis
                                    }
                                } else {
                                    if (change.uptimeMillis - lastMoveTime > 60L) {
                                        isStaleFling = true
                                    }
                                }
                            } else {
                                velocityTracker.resetTracking()
                                isStaleFling = true // Never fling after a multitouch/pinch release
                            }

                            if (event.changes.size >= 2) {
                                val zoomChange = event.calculateZoom()
                                val panChange = event.calculatePan()
                                
                                if (zoomChange != 1f || panChange != Offset.Zero) {
                                    val newScale = (currentScale * zoomChange).coerceIn(1f, 5f)
                                    val effectiveZoom = newScale / currentScale
                                    currentScale = newScale
                                    
                                    val centroid = event.calculateCentroid(useCurrent = false)
                                    val centerX = containerSize.width / 2f
                                    val centerY = containerSize.height / 2f
                                    val locCentroidX = centroid.x - centerX
                                    val locCentroidY = centroid.y - centerY
                                    
                                    currentOffsetX = locCentroidX - (locCentroidX - currentOffsetX) * effectiveZoom + panChange.x
                                    currentOffsetY = locCentroidY - (locCentroidY - currentOffsetY) * effectiveZoom + panChange.y
                                    
                                    boundX = if (currentScale > 1f) (containerSize.width * (currentScale - 1f)) / 2f else 0f
                                    boundY = if (currentScale > 1f) (containerSize.height * (currentScale - 1f)) / 2f else 0f
                                    offsetXAnim.updateBounds(-boundX, boundX)
                                    offsetYAnim.updateBounds(-boundY, boundY)
                                    
                                    currentOffsetX = currentOffsetX.coerceIn(-boundX, boundX)
                                    currentOffsetY = currentOffsetY.coerceIn(-boundY, boundY)
                                    
                                    scope.launch { 
                                        scaleAnim.snapTo(currentScale) 
                                        offsetXAnim.snapTo(currentOffsetX)
                                        offsetYAnim.snapTo(currentOffsetY)
                                    }
                                }
                                event.changes.forEach { it.consume() } // consume pinch
                            } else if (currentScale > 1.1f && event.changes.size == 1) {
                                val change = event.changes[0]
                                pan = change.position - change.previousPosition
                                
                                boundX = if (currentScale > 1f) (containerSize.width * (currentScale - 1f)) / 2f else 0f
                                boundY = if (currentScale > 1f) (containerSize.height * (currentScale - 1f)) / 2f else 0f
                                offsetXAnim.updateBounds(-boundX, boundX)
                                offsetYAnim.updateBounds(-boundY, boundY)
                                
                                if (!isPanning && (change.position - down.position).getDistance() > 18f) {
                                    isPanning = true
                                }
                                
                                if (isPanning) {
                                    currentOffsetX = (currentOffsetX + pan.x).coerceIn(-boundX, boundX)
                                    if (!isScrollMode) {
                                        currentOffsetY = (currentOffsetY + pan.y).coerceIn(-boundY, boundY)
                                        change.consume() // Consume 1-finger drag so background lists don't scroll
                                    }
                                    scope.launch {
                                        offsetXAnim.snapTo(currentOffsetX)
                                        if (!isScrollMode) offsetYAnim.snapTo(currentOffsetY)
                                    }
                                }
                            }
                        } while (event.changes.any { it.pressed })

                        // ── Inertia / Fling Animation ──
                        if (currentScale > 1.1f && !isStaleFling) {
                            val velocity = velocityTracker.calculateVelocity()
                            if (abs(velocity.x) > 300f || (!isScrollMode && abs(velocity.y) > 300f)) {
                                scope.launch {
                                    if (abs(velocity.x) > 300f) {
                                        launch {
                                            offsetXAnim.animateDecay(
                                                initialVelocity = velocity.x * 0.4f,
                                                animationSpec = exponentialDecay()
                                            )
                                        }
                                    }
                                    if (!isScrollMode && abs(velocity.y) > 300f) {
                                        launch {
                                            offsetYAnim.animateDecay(
                                                initialVelocity = velocity.y * 0.4f,
                                                animationSpec = exponentialDecay()
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                .graphicsLayer {
                    scaleX = scaleAnim.value
                    scaleY = scaleAnim.value
                    translationX = offsetXAnim.value
                    translationY = offsetYAnim.value
                }
        ) {
            if (isScrollMode) {
                LazyColumn(
                    state = scrollListState,
                    modifier = Modifier.fillMaxSize()
                        .nestedScroll(nestedScrollConnection),
                    verticalArrangement = if (!isCatalogMode && actualImageList.size == 1) {
                        Arrangement.Center
                    } else {
                        Arrangement.Top
                    },
                ) {
                    if (isCatalogMode) {
                        item {
                            Box(modifier = Modifier.clickable { if (hasPrevChapter) launchPrevChapter() }) {
                                InterstitialCard(
                                    item = ReaderItem.InterstitialItem(
                                        prevTitle = prevThreadTitle,
                                        nextTitle = activeTitle,
                                        isEnd = !hasPrevChapter,
                                        isPrev = true,
                                        readingMode = readingMode
                                    )
                                )
                            }
                        }
                    }

                    if (actualImageList.isEmpty()) {
                        item {
                            Box(modifier = Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                                if (isLoadingImages) {
                                    CircularProgressIndicator(color = YamiboTheme.colors.brownPrimary)
                                } else {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(
                                            imageVector = YamiboIcons.Book,
                                            contentDescription = null,
                                            tint = Color.White.copy(alpha = 0.5f),
                                            modifier = Modifier.size(48.dp)
                                        )
                                        Spacer(Modifier.height(16.dp))
                                        Text(text = i18n("沒有找到圖片"), color = Color.White.copy(alpha = 0.5f), fontSize = 14.sp)
                                        Spacer(Modifier.height(16.dp))
                                        OutlinedButton(
                                            onClick = { retryTrigger++ },
                                            colors = outlinedButtonColors(contentColor = Color.White.copy(alpha = 0.8f))
                                        ) {
                                            Text(i18n("重新載入"))
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        itemsIndexed(actualImageList) { index, url ->
                            ImageViewer(
                                url = url,
                                contentDescription = i18n("第{}頁", index + 1),
                                contentScale = ContentScale.FillWidth,
                                modifier = Modifier.fillMaxWidth(),
                                enableContextMenu = false,
                                isDarkTheme = true,
                                imageVerticalPadding = if (readingMode == ReadingMode.SCROLL_CONTINUOUS) 0.dp else 1.dp,
                                enableCrossfade = false
                            )
                            if (readingMode == ReadingMode.SCROLL_GAP && index < actualImageList.lastIndex) {
                                Spacer(Modifier.height(16.dp))
                            }
                        }
                    }

                    if (isCatalogMode) {
                        item {
                            Spacer(Modifier.height(16.dp))
                            Box(modifier = Modifier.clickable { if (hasNextChapter) launchNextChapter() }) {
                                InterstitialCard(
                                    item = ReaderItem.InterstitialItem(
                                        prevTitle = activeTitle,
                                        nextTitle = nextThreadTitle,
                                        isEnd = !hasNextChapter,
                                        isPrev = false,
                                        readingMode = readingMode
                                    )
                                )
                            }
                        }
                    }
                }

                LaunchedEffect(scrollListState, actualImageList.size, activeTid) {
                    snapshotFlow { scrollListState.firstVisibleItemIndex }
                        .collect { index ->
                            val adjustedIndex = if (isCatalogMode) index - 1 else index
                            currentPage = adjustedIndex.coerceIn(minPage(), maxPage())
                        }
                }
            } else {
                @Composable
                fun SinglePageContent(
                    page: Int,
                    modifier: Modifier = Modifier,
                    showLoadingPlaceholder: Boolean = true,
                ) {
                    Box(modifier = modifier) {
                        when (page) {
                            -1 -> InterstitialCard(
                                item = ReaderItem.InterstitialItem(
                                    prevTitle = prevThreadTitle,
                                    nextTitle = activeTitle,
                                    isEnd = !hasPrevChapter,
                                    isPrev = true,
                                    readingMode = readingMode
                                )
                            )
                            totalContentPages() -> InterstitialCard(
                                item = ReaderItem.InterstitialItem(
                                    prevTitle = activeTitle,
                                    nextTitle = nextThreadTitle,
                                    isEnd = !hasNextChapter,
                                    isPrev = false,
                                    readingMode = readingMode
                                )
                            )
                            else -> {
                                if (actualImageList.isEmpty()) {
                                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        if (isLoadingImages) {
                                            CircularProgressIndicator(color = YamiboTheme.colors.brownPrimary)
                                        } else {
                                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                Icon(
                                                    imageVector = YamiboIcons.Book,
                                                    contentDescription = null,
                                                    tint = Color.White.copy(alpha = 0.5f),
                                                    modifier = Modifier.size(48.dp)
                                                )
                                                Spacer(Modifier.height(16.dp))
                                                Text(text = i18n("沒有找到圖片"), color = Color.White.copy(alpha = 0.5f), fontSize = 14.sp)
                                                Spacer(Modifier.height(16.dp))
                                                OutlinedButton(
                                                    onClick = { retryTrigger++ },
                                                    colors = outlinedButtonColors(contentColor = Color.White.copy(alpha = 0.8f))
                                                ) {
                                                    Text(i18n("重新載入"))
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    val imageUrl = actualImageList.getOrElse(page) { "" }
                                    key(page, imageUrl, showLoadingPlaceholder) {
                                        ImageViewer(
                                            url = imageUrl,
                                            contentDescription = i18n("第{}頁", page + 1),
                                            contentScale = ContentScale.Fit,
                                            modifier = Modifier.fillMaxSize(),
                                            fillContainer = true,
                                            enableContextMenu = false,
                                            isDarkTheme = true,
                                            enableCrossfade = false,
                                            showLoadingPlaceholder = showLoadingPlaceholder,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                val dragTarget = pageDragTarget
                val activeDragTarget = dragTarget?.takeIf {
                    pageDragOffset != 0f &&
                        currentPage in actualImageList.indices &&
                        it in actualImageList.indices &&
                        (it != currentPage || committedPageOverlay == it) &&
                        containerSize != IntSize.Zero
                }
                Box(modifier = Modifier.fillMaxSize()) {
                    AnimatedContent(
                        targetState = ImageReaderPageTarget(
                            threadId = activeTid.value,
                            page = currentPage,
                            transitionSerial = pageTransitionSerial,
                            directionOverride = pageTurnDirection,
                        ),
                        transitionSpec = {
                            val forcedDir = targetState.directionOverride.takeIf { it != 0 }
                            val dir = forcedDir ?: if (targetState.page > initialState.page) 1 else -1

                            val snapDragCommit = committedPageOverlay != null &&
                                committedPageOverlay == targetState.page
                            if (snapDragCommit || snapNextTransition) {
                                snapNextTransition = false
                                EnterTransition.None togetherWith ExitTransition.None
                            } else if (isVerticalMode) {
                                slideInVertically(tween(300)) { it * dir } togetherWith
                                    slideOutVertically(tween(300)) { -it * dir }
                            } else {
                                val dirMul = if (isRtl) -1 else 1
                                slideInHorizontally(tween(300)) { it * dirMul * dir } togetherWith
                                    slideOutHorizontally(tween(300)) { -it * dirMul * dir }
                            }
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .zIndex(0f)
                            .graphicsLayer {
                                if (activeDragTarget != null) {
                                    if (isVerticalMode) {
                                        translationY = pageDragOffset
                                    } else {
                                        translationX = pageDragOffset
                                    }
                                }
                            }
                    ) { pageTarget ->
                        SinglePageContent(page = pageTarget.page, modifier = Modifier.fillMaxSize())
                    }

                    if (activeDragTarget != null) {
                        val side = if (pageDragOffset < 0f) 1 else -1
                        SinglePageContent(
                            page = activeDragTarget,
                            modifier = Modifier
                                .fillMaxSize()
                                .zIndex(if (committedPageOverlay == activeDragTarget) 2f else 1f)
                                .graphicsLayer {
                                    if (isVerticalMode) {
                                        translationY = pageDragOffset + side * containerSize.height
                                    } else {
                                        translationX = pageDragOffset + side * containerSize.width
                                    }
                                },
                            showLoadingPlaceholder = false,
                        )
                    } else if (committedPageOverlay != null && committedPageOverlay == currentPage) {
                        SinglePageContent(
                            page = currentPage,
                            modifier = Modifier
                                .fillMaxSize()
                                .zIndex(2f),
                            showLoadingPlaceholder = false,
                        )
                    }
                }
            }
        }

        /** Overlays (Not affected by graphicsLayer zoom) */

        // Page Indicator
        if (!showOverlay && actualImageList.isNotEmpty()) {
            val totalPagesUi = actualImageList.size.coerceAtLeast(1)
            val clampedPage = currentPage.coerceIn(0, totalPagesUi - 1)
            Text(
                text = "${clampedPage + 1} / $totalPagesUi",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }

        // Touch zone preview overlay
        TouchZoneOverlay(
            visible = showTouchZonePreview,
            layout = touchZoneLayout,
            reverseTouchZones = reverseTouchZones,
            isRtl = isRtl,
        )

        // Manga overlay (TopBar + BottomBar) — has its own scrim for dismissal
        MangaReaderOverlay(
            visible = showOverlay && !showSettings && !showCatalog,
            title = activeTitle,
            currentPage = currentPage.coerceIn(0, (actualImageList.size - 1).coerceAtLeast(0)),
            totalPages = actualImageList.size,
            isRtl = isRtl,
            onBack = {
                // Ignore back handlers by temporarily hiding overlays so pop() works normally
                showOverlay = false
                showSettings = false
                showTouchZonePreview = false
                showCatalog = false
                navigator.pop()
            },
            onPageChange = { page ->
                setReaderPage(page.coerceIn(0, actualImageList.lastIndex))
                if (isScrollMode) {
                    val targetIndex = if (isCatalogMode) currentPage + 1 else currentPage
                    scope.launch { scrollListState.scrollToItem(targetIndex) }
                }
                resetZoom()
            },
            onSettings = { showSettings = true },
            onDismiss = { showOverlay = false },
            onCatalog = if (isCatalogMode) { { showCatalog = true; showOverlay = false } } else null,
            onNavigateToThread = if (isCatalogMode) { {
                if (activeFid != null && YamiboForum.isNovelForum(activeFid)) {
                    navigator.navigate(INovelThreadDetailScreen(activeTid, activeTitle, activeAuthorId))
                } else {
                    navigator.navigate(
                        IThreadReaderScreen(
                            tid = activeTid,
                            title = activeTitle,
                            threadType = ReadHistoryRepository.ThreadEntryType.Normal,
                            authorId = null
                        )
                    )
                }
            } } else null,
            subtitle = catalogTitle,
            onShare = {
                val url = YamiboRoute.Thread(activeTid).build()
                shareText(platformContext, url, activeTitle)
            },
            onRefresh = if (isCatalogMode && isDownloadedTagChapter) { { showDownloadedRefreshConfirm = true } } else null,
        )

        if (showDownloadedRefreshConfirm) {
            AlertDialog(
                onDismissRequest = { showDownloadedRefreshConfirm = false },
                title = { Text(i18n("重新整理下載內容"), color = YamiboTheme.colors.textStrong) },
                text = {
                    Text(
                        i18n("將從網站重新抓取此章圖片，成功後覆蓋本地下載；失敗會保留舊內容。"),
                        color = YamiboTheme.colors.textDark,
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showDownloadedRefreshConfirm = false
                            val key = activeCatalogDownloadKey()
                            if (key != null) {
                                scope.launch {
                                    isLoadingImages = true
                                    val result = when (key) {
                                        is TagMangaChapterDownloadKey -> downloadRepository.refreshTagMangaChapter(key)
                                        is RssMangaChapterDownloadKey -> downloadRepository.refreshRssMangaChapter(key)
                                        else -> YamiboResult.Failure("不支援的下載類型")
                                    }
                                    when (result) {
                                        is YamiboResult.Success -> {
                                            actualImageList = result.value
                                            isDownloadedTagChapter = true
                                            currentPage = currentPage.coerceIn(0, (actualImageList.size - 1).coerceAtLeast(0))
                                            feedbackController.post(i18n("已重新整理下載"))
                                        }
                                        else -> {
                                            feedbackController.post(i18n("重新整理下載失敗：{}", i18n(result.message())))
                                        }
                                    }
                                    isLoadingImages = false
                                }
                            }
                        },
                    ) {
                        Text(i18n("重新整理"), color = YamiboTheme.colors.brownPrimary)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDownloadedRefreshConfirm = false }) {
                        Text(i18n("取消"), color = YamiboTheme.colors.textDark)
                    }
                },
                containerColor = YamiboTheme.colors.creamSurface,
            )
        }

        // Catalog Panel
        if (isCatalogMode) {
            AnimatedVisibility(
                visible = showCatalog,
                enter = slideInHorizontally(tween(250)) { -it } + fadeIn(tween(200)),
                exit = slideOutHorizontally(tween(250)) { -it } + fadeOut(tween(200))
            ) {
                TagCatalogPanel(
                    totalPages = currentTagTotalPages ?: 1,
                    loadedThreadsByPage = loadedThreadsByPage,
                    currentTagPage = currentTagPage,
                    currentThreadId = activeTid,
                    chapterStates = tagThreadChapterStates,
                    onPageOrThreadClick = { page, targetThread ->
                        if (targetThread == null) {
                            if (!loadedThreadsByPage.containsKey(page)) {
                                scope.launch {
                                    val result = loadCatalogPage(page, preferCache = true)
                                    if (result != null) {
                                        loadedThreadsByPage[page] = result.threadSummaries
                                    }
                                }
                            }
                        } else {
                            showCatalog = false
                            // Navigate to target thread by updating local state
                            if (targetThread.tid != activeTid) {
                                val previousTagPage = currentTagPage
                                val previousThreadIndex = currentThreadIndex()
                                val pageThreads = loadedThreadsByPage[page] ?: currentThreads
                                val targetThreadIndex = pageThreads.indexOfFirst { it.tid == targetThread.tid }
                                val movingForward = when {
                                    page > previousTagPage -> true
                                    page < previousTagPage -> false
                                    targetThreadIndex >= 0 && previousThreadIndex >= 0 -> targetThreadIndex > previousThreadIndex
                                    else -> true
                                }
                                val targetStartPageIndex = tagThreadChapterStates[targetThread.tid.value.toLong()]
                                    ?.lastPageIndex
                                prepareChapterSwitch(
                                    forward = movingForward,
                                    startPageIndex = targetStartPageIndex,
                                )
                                activeTid = targetThread.tid
                                if (page != previousTagPage) {
                                    currentTagPage = page
                                    currentThreads = loadedThreadsByPage[page] ?: emptyList()
                                } else {
                                    currentTagPage = page
                                }
                            }
                        }
                    },
                    onDismiss = { showCatalog = false }
                )
            }
        }

        // Settings scrim to close panel when tapping outside
        if (showSettings) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(YamiboTheme.colors.brownDeep.copy(alpha = 0.18f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { showSettings = false }
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .windowInsetsTopHeight(WindowInsets.statusBars)
                    .background(YamiboTheme.colors.brownDeep)
            )
        }

        // Settings panel
        MangaReaderSettingsPanel(
            visible = showSettings,
            currentReadingMode = readingMode,
            currentTouchZoneLayout = touchZoneLayout,
            readingModeSource = modeSource,
            threadModeOverrideEnabled = threadModeOverride != null,
            onReadingModeChange = { mode ->
                if (modeSource != EffectiveReadingModeSource.CatalogLongStrip) {
                    if (threadModeOverride != null) {
                        imageReaderModeOverrideRepository.setThreadMode(activeTid, activeAuthorId, mode)
                    } else {
                        mangaSettingsRepo.readingMode.setValue(mode)
                    }
                    resetZoom()
                }
            },
            onThreadModeOverrideEnabledChange = { enabled ->
                if (modeSource != EffectiveReadingModeSource.CatalogLongStrip) {
                    imageReaderModeOverrideRepository.setThreadMode(
                        activeTid,
                        activeAuthorId,
                        if (enabled) readingMode else null,
                    )
                    resetZoom()
                }
            },
            onTouchZoneLayoutChange = { layout -> mangaSettingsRepo.touchZone.setValue(layout); showTouchZonePreview = true },
            onReverseTouchZonesChanged = { showTouchZonePreview = true },
            modifier = Modifier.align(Alignment.BottomCenter)
        )

        // Context menu (long press)
        ImageContextMenu(
            visible = showContextMenu,
            imageUrl = contextMenuImageUrl,
            onSetAsCover = if (isCatalogMode) {
                { imageUrl ->
                    scope.launch {
                        val coverKey = when {
                            tagId != null -> ContentCoverRepository.Key(
                                ContentCoverRepository.TargetType.TagManga,
                                tagId.value.toLong(),
                            )
                            rssSubscriptionId != null -> ContentCoverRepository.Key(
                                ContentCoverRepository.TargetType.RssSearch,
                                rssSubscriptionId,
                            )
                            else -> null
                        }
                        if (coverKey == null) return@launch
                        val saved = contentCoverRepository.setManualCover(
                            coverKey,
                            imageUrl,
                        )
                        if (saved) feedbackController.post(i18n("已設為封面"))
                    }
                }
            } else null,
            onMessage = { message ->
                scope.launch {
                    feedbackController.post(message)
                }
            },
            onDismiss = { showContextMenu = false },
            isBottomSheet = true
        )

    }
}

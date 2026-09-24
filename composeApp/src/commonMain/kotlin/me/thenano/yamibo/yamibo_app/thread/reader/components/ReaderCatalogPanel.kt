package me.thenano.yamibo.yamibo_app.thread.reader.components

import YamiboIcons
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.littlesurvival.dto.page.Post
import io.github.littlesurvival.dto.value.PostId
import me.thenano.yamibo.yamibo_app.LocalChineseConversionRepository
import me.thenano.yamibo.yamibo_app.forum.components.PagePickerDialog
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
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
    onLoadPage: suspend (Int) -> Boolean,
) {
    val colors = YamiboTheme.colors
    var expandedPages by remember { mutableStateOf(setOf(currentPage)) }

    val initialPostIndex = loadedPostsByPage[currentPage].orEmpty().indexOfFirst { it.pid == currentPid }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = (currentPage - 1).coerceAtLeast(0) +
            if (initialPostIndex > 0) initialPostIndex + 1 else 0,
    )
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    var query by remember { mutableStateOf("") }
    val parsedQuery = remember(query) { parseCatalogQuery(query) }
    val textQuery = (parsedQuery as? CatalogQuery.Text)?.value.orEmpty()
    var showPagePicker by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messagePage by remember { mutableStateOf<Int?>(null) }
    var highlightedKey by remember { mutableStateOf<String?>(null) }
    var navigationJob by remember { mutableStateOf<Job?>(null) }
    var savedPosition by remember { mutableStateOf<Pair<String?, Int>?>(null) }
    var retryFloor by remember { mutableStateOf<Pair<Int, Long>?>(null) }
    val loadErrors = remember { mutableStateMapOf<Int, Boolean>() }
    val loadingPages = remember { mutableStateMapOf<Int, Boolean>() }
    val latestLoadPage by rememberUpdatedState(onLoadPage)
    val latestPosts by rememberUpdatedState(loadedPostsByPage)
    val conversion = LocalChineseConversionRepository.current
    val conversionMode by conversion.currentMode.collectAsState()
    val sourceTitles = remember(loadedPostsByPage) {
        loadedPostsByPage.values.flatten().associate { it.pid to it.title.ifEmpty { "..." } }
    }
    var convertedTitles by remember(conversionMode) { mutableStateOf<Map<PostId, String>>(emptyMap()) }
    LaunchedEffect(sourceTitles, conversionMode) {
        convertedTitles = sourceTitles.mapValues { (_, title) -> conversion.convert(title) }
    }
    val visiblePosts = remember(loadedPostsByPage, convertedTitles, sourceTitles, textQuery) { loadedPostsByPage.mapValues { (_, posts) ->
        if (textQuery.isEmpty()) posts else posts.filter {
            (convertedTitles[it.pid] ?: sourceTitles[it.pid].orEmpty()).contains(textQuery, ignoreCase = true)
        }
    } }
    val visiblePages = (1..totalPages).filter { textQuery.isEmpty() || visiblePosts[it].orEmpty().isNotEmpty() }
    val keys = buildList {
        if ((message != null && messagePage == null) || (textQuery.isNotEmpty() && visiblePages.isEmpty())) add("catalog_message")
        for (page in visiblePages) {
            add("page_header_$page")
            if (message != null && messagePage == page) add("catalog_message")
            if (textQuery.isNotEmpty() || page in expandedPages) {
                if (page !in loadedPostsByPage) add("page_loading_$page")
                else visiblePosts[page].orEmpty().forEach { add("post_${page}_${it.pid.value}") }
            }
        }
    }
    val latestKeys by rememberUpdatedState(keys)

    fun cancelNavigation() {
        navigationJob?.cancel()
        navigationJob = null
        highlightedKey = null
    }

    suspend fun locate(key: String, animate: Boolean = true, offset: Int = 0) {
        // Wait for the actual lazy-list model; loading/expansion can change indices.
        snapshotFlow { latestKeys.indexOf(key).takeIf { it >= 0 && listState.layoutInfo.totalItemsCount == latestKeys.size } }
            .first { it != null }
        val index = latestKeys.indexOf(key)
        if (index < 0) return
        if (animate) {
            val delta = index - listState.firstVisibleItemIndex
            if (kotlin.math.abs(delta) > 35) listState.scrollToItem((index - if (delta > 0) 8 else -8).coerceIn(latestKeys.indices))
            listState.animateScrollToItem(index, offset)
        } else listState.scrollToItem(index, offset)
    }

    suspend fun loadCatalogPage(page: Int): Boolean {
        loadErrors.remove(page)
        loadingPages[page] = true
        return try {
            latestLoadPage(page).also {
                coroutineContext.ensureActive()
                if (!it) loadErrors[page] = true
                else snapshotFlow { page in latestPosts }.first { loaded -> loaded }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            loadErrors[page] = true
            false
        } finally {
            loadingPages.remove(page)
        }
    }

    fun navigate(page: Int, floor: Long? = null) {
        cancelNavigation()
        savedPosition = null
        retryFloor = floor?.let { page to it }
        if (floor == null) query = ""
        message = null
        messagePage = null
        focus.clearFocus()
        keyboard?.hide()
        expandedPages = expandedPages + page
        navigationJob = scope.launch {
            val existing = latestPosts[page]?.firstOrNull { it.floor.toLong() == floor }
            var key = existing?.let { "post_${page}_${it.pid.value}" } ?: "page_header_$page"
            locate(key)
            if (page !in latestPosts && !loadCatalogPage(page)) return@launch
            // An unloaded last page cannot align at the top until its rows exist.
            if (floor == null) locate(key)
            if (floor != null) {
                val post = latestPosts[page]?.firstOrNull { it.floor.toLong() == floor }
                if (post == null) {
                    message = i18n("此頁找不到此樓層")
                    messagePage = page
                    locate("page_header_$page")
                    return@launch
                }
                key = "post_${page}_${post.pid.value}"
                locate(key)
            }
            highlightedKey = key
            delay(900)
            highlightedKey = null
        }
    }

    fun submitFloor() {
        val floor = (parseCatalogQuery(query) as? CatalogQuery.Floor)?.value
        if (parseCatalogQuery(query) !is CatalogQuery.Floor) { keyboard?.hide(); return }
        val estimate = catalogFloorPage(floor, totalPages)
        if (estimate == null) {
            cancelNavigation()
            message = i18n("找不到此樓層")
            messagePage = null
            keyboard?.hide()
            navigationJob = scope.launch { locate("catalog_message") }
        } else {
            val actual = latestPosts.entries.firstOrNull { (_, posts) -> posts.any { it.floor.toLong() == floor } }?.key
            navigate(actual ?: estimate, floor)
        }
    }

    fun changeQuery(value: String) {
        cancelNavigation()
        message = null
        messagePage = null
        if (query.isEmpty() && value.isNotEmpty()) {
            savedPosition = (listState.layoutInfo.visibleItemsInfo.firstOrNull()?.key as? String) to listState.firstVisibleItemScrollOffset
        }
        query = value
        retryFloor = null
        val restore = savedPosition.takeIf { value.isEmpty() }
        navigationJob = scope.launch {
            withFrameNanos { }
            if (restore != null) {
                restore.first?.takeIf { it in latestKeys }?.let { locate(it, animate = false, offset = restore.second) }
                savedPosition = null
            } else if (parseCatalogQuery(value) is CatalogQuery.Text && latestKeys.isNotEmpty()) {
                locate(latestKeys.first(), animate = false)
            }
        }
    }

    val dragged by listState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragged) { if (dragged) cancelNavigation() }
    LaunchedEffect(drawerOpen) {
        cancelNavigation()
        showPagePicker = false
    }
    // Prepare the reading position while hidden, never after the drawer opens.
    // Keep the page heading visible when reading its first entry.
    LaunchedEffect(drawerOpen, currentPage, currentPid, loadedPostsByPage, query) {
        if (!drawerOpen && query.isEmpty()) {
            expandedPages = expandedPages + currentPage
            val posts = loadedPostsByPage[currentPage].orEmpty()
            val postIndex = posts.indexOfFirst { it.pid == currentPid }
            val key = if (postIndex > 0) "post_${currentPage}_${posts[postIndex].pid.value}"
                else "page_header_$currentPage"
            locate(key, animate = false)
        }
    }

    if (showPagePicker) {
        val visiblePage = listState.layoutInfo.visibleItemsInfo.firstOrNull()?.key?.toString()
            ?.let { key -> if (key.startsWith("page_header_")) key.removePrefix("page_header_").toIntOrNull() else key.split('_').getOrNull(1)?.toIntOrNull() }
        PagePickerDialog(
            currentPage = (visiblePage ?: currentPage).coerceIn(1, totalPages.coerceAtLeast(1)),
            totalPages = totalPages.coerceAtLeast(1),
            onPageSelected = { showPagePicker = false; navigate(it) },
            onDismiss = { showPagePicker = false },
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(colors.creamBackground).systemBarsPadding()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.brownDeep)
                .padding(horizontal = 16.dp, vertical = 4.dp),
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

        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val searchLabel = i18n("搜尋目錄或輸入樓層#")
            Row(
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                    .background(colors.brownLight.copy(alpha = 0.12f)).padding(start = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(YamiboIcons.Search, null, tint = colors.brownPrimary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                BasicTextField(
                    value = query,
                    onValueChange = ::changeQuery,
                    modifier = Modifier.weight(1f).heightIn(min = 36.dp).semantics { contentDescription = searchLabel },
                    singleLine = true,
                    textStyle = TextStyle(color = colors.textDark, fontSize = 14.sp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submitFloor() }),
                    decorationBox = { field ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (query.isEmpty()) Text(searchLabel, color = colors.brownPrimary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            field()
                        }
                    },
                )
                if (query.isNotEmpty()) IconButton(onClick = { changeQuery("") }, modifier = Modifier.size(48.dp).semantics { contentDescription = i18n("清除搜尋") }) {
                    Text("×", color = colors.brownPrimary, fontSize = 22.sp)
                } else Spacer(Modifier.width(8.dp))
            }
            IconButton(onClick = { cancelNavigation(); keyboard?.hide(); showPagePicker = true }) {
                Icon(YamiboIcons.ListStart, contentDescription = i18n("跳至頁碼"), tint = colors.brownPrimary)
            }
        }
        HorizontalDivider(color = colors.brownPrimary.copy(alpha = 0.15f))
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize()
        ) {
            if ((message != null && messagePage == null) || (textQuery.isNotEmpty() && visiblePages.isEmpty())) {
                item(key = "catalog_message") {
                    Text(message ?: i18n("已載入的目錄中沒有符合項目"), modifier = Modifier.padding(20.dp), color = colors.brownPrimary)
                }
            }
            for (page in visiblePages) {
                val isExpanded = textQuery.isNotEmpty() || expandedPages.contains(page)
                val isLoaded = loadedPostsByPage.containsKey(page)
                val downloadEntry = downloadEntriesByPage[page]

                // 1. Page Header Item
                item(key = "page_header_$page") {
                    val rotation by animateFloatAsState(
                        targetValue = if (isExpanded) 180f else 0f,
                        label = "page_chevron"
                    )
                    Surface(
                        color = if (highlightedKey == "page_header_$page") colors.orangeAccent.copy(alpha = 0.2f) else if (isExpanded) colors.brownLight.copy(alpha = 0.1f) else colors.creamBackground,
                        onClick = {
                            cancelNavigation()
                            if (textQuery.isEmpty()) expandedPages = if (isExpanded) expandedPages - page else expandedPages + page
                            if (!isLoaded && !isExpanded) {
                                navigationJob = scope.launch { loadCatalogPage(page) }
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

                if (message != null && messagePage == page) {
                    item(key = "catalog_message") {
                        Text(message.orEmpty(), modifier = Modifier.padding(16.dp), color = colors.brownPrimary)
                    }
                }
                // 2. Page Content Items
                if (isExpanded) {
                    if (!isLoaded) {
                        item(key = "page_loading_$page") {
                            Box(
                                modifier = Modifier.fillMaxWidth().height(60.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                if (loadErrors[page] == true || loadingPages[page] != true) {
                                    TextButton(onClick = {
                                        val pendingFloor = retryFloor?.takeIf { it.first == page }?.second
                                        navigate(page, pendingFloor)
                                    }) {
                                        Text(if (loadErrors[page] == true) i18n("載入失敗，點擊重試") else i18n("載入此頁"))
                                    }
                                } else CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    color = colors.brownPrimary,
                                    strokeWidth = 2.dp
                                )
                            }
                            HorizontalDivider(color = colors.brownPrimary.copy(alpha = 0.2f))
                        }
                    } else {
                        val pagePosts = visiblePosts[page] ?: emptyList()
                        items(pagePosts, key = { "post_${page}_${it.pid.value}" }) { post ->
                            val isCurrentPost = post.pid == currentPid
                            val isBookmarked = post.pid.value.toLong() in bookmarkedPostIds
                            val chapterState = chapterStates[post.pid.value.toLong()]
                            val isRead = post.pid.value.toLong() in readPostIds || chapterState?.read == true
                            val progressText = chapterState?.chapterPercentProgressLabel()
                            val displayTitle = convertedTitles[post.pid] ?: sourceTitles[post.pid].orEmpty()
                            val matchRanges = catalogMatchRanges(displayTitle, textQuery)
                            val titleStart = matchRanges.firstOrNull()?.first?.minus(8)?.coerceAtLeast(0) ?: 0
                            val titleText = (if (titleStart > 0) "…" else "") + displayTitle.substring(titleStart)
                            val annotatedTitle = AnnotatedString.Builder(titleText).apply {
                                catalogMatchRanges(titleText, textQuery).forEach { range ->
                                    addStyle(SpanStyle(background = colors.orangeAccent.copy(alpha = 0.28f)), range.first, range.last + 1)
                                }
                            }.toAnnotatedString()
                            Surface(
                                color = if (highlightedKey == "post_${page}_${post.pid.value}") colors.orangeAccent.copy(alpha = 0.2f) else if (isCurrentPost) colors.brownLight.copy(alpha = 0.15f) else colors.creamSurface,
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
                                        text = annotatedTitle,
                                        modifier = Modifier.weight(1f),
                                        color = if (isCurrentPost) colors.textStrong else colors.textDark,
                                        fontWeight = if (isCurrentPost) FontWeight.ExtraBold else FontWeight.Normal,
                                        fontSize = if (isCurrentPost) 16.sp else 14.sp,
                                        maxLines = if (textQuery.isEmpty()) 1 else 2,
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

package me.thenano.yamibo.yamibo_app.localnovel

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import me.thenano.yamibo.yamibo_app.*
import me.thenano.yamibo.yamibo_app.thread.reader.components.novel.NovelReaderSettingsPanel
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme
import me.thenano.yamibo.yamibo_app.i18n.i18n
import me.thenano.yamibo.yamibo_app.navigation.*
import me.thenano.yamibo.yamibo_app.repository.LocalNovelFileType
import me.thenano.yamibo.yamibo_app.repository.LocalNovelInfo
import me.thenano.yamibo.yamibo_app.repository.LocalNovelChapterInfo
import me.thenano.yamibo.yamibo_app.repository.LocalNovelProgressInfo
import me.thenano.yamibo.yamibo_app.repository.localnovel.EpubFileParser
import me.thenano.yamibo.yamibo_app.repository.localnovel.TxtFileParser
import me.thenano.yamibo.yamibo_app.thread.reader.components.overlay.ReaderFloatButtons
import me.thenano.yamibo.yamibo_app.thread.reader.components.post.impl.HtmlRenderer
import me.thenano.yamibo.yamibo_app.thread.reader.MAX_READER_TEXT_SEGMENT_CHARS
import me.thenano.yamibo.yamibo_app.util.time.currentTimeMillis

private sealed interface SegmentItem {
    data class ChapterTitle(val chapterId: Long, val chapterIndex: Int, val title: String) : SegmentItem
    data class Content(val chapterId: Long, val chapterIndex: Int, val html: String) : SegmentItem
}

/** A group of chapters loaded together. */
private data class ChapterGroup(val startIndex: Int, val chapters: List<LocalNovelChapterInfo>)

private const val CHAPTERS_PER_GROUP = 10

@Serializable private data class LocalNovelReaderRestorePayload(val novelId: Long)

@RestorableScreenEntry
class ILocalNovelReaderScreen(val novelId: Long) : RestorableNavigatable {
    override val id = buildId(novelId.toString())
    override val restoreDecoder = Decoder
    override fun toRestoreSnapshot(): RestorableScreenSnapshot = restoreSnapshot(decoder = restoreDecoder, payload = LocalNovelReaderRestorePayload(novelId = novelId))
    @Composable override fun Content() { LocalNovelReaderScreen(novelId) }
    companion object Decoder : TypedRestorableNavigatableDecoder<ILocalNovelReaderScreen>(ILocalNovelReaderScreen::class) {
        override fun decode(payload: String): RestorableNavigatable {
            val data = decodeRestorePayload<LocalNovelReaderRestorePayload>(payload)
            return ILocalNovelReaderScreen(novelId = data.novelId)
        }
    }
}

// ---- Grouping ----

private fun groupChapters(chapters: List<LocalNovelChapterInfo>): List<ChapterGroup> {
    if (chapters.isEmpty()) return emptyList()
    val groups = mutableListOf<ChapterGroup>()
    var start = 0
    val volPat = Regex("""第\s*[0-9零一二两三四五六七八九十百千万]+\s*[卷集部篇]""")
    for (i in chapters.indices) {
        if (i == 0) continue
        if (volPat.containsMatchIn(chapters[i].title)) {
            groups.add(ChapterGroup(start, chapters.subList(start, i)))
            start = i
        } else if (i - start >= CHAPTERS_PER_GROUP) {
            groups.add(ChapterGroup(start, chapters.subList(start, i)))
            start = i
        }
    }
    groups.add(ChapterGroup(start, chapters.subList(start, chapters.size)))
    return groups
}

/** Find the group index containing the given chapterId. */
private fun findGroupIndex(groups: List<ChapterGroup>, chapterId: Long): Int {
    for ((gi, g) in groups.withIndex()) {
        if (g.chapters.any { it.id == chapterId }) return gi
    }
    return 0
}

// ---- Screen ----

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalNovelReaderScreen(novelId: Long) {
    val colors = YamiboTheme.colors
    val navigator = LocalNavigator.current
    val repository = LocalLocalNovelRepository.current
    val fileOps = LocalPlatformFileOperations.current
    val appSettingsRepo = LocalAppSettingsRepository.current
    val scope = rememberCoroutineScope()

    var novel by remember { mutableStateOf<LocalNovelInfo?>(null) }
    var chapters by remember { mutableStateOf<List<LocalNovelChapterInfo>>(emptyList()) }
    var groups by remember { mutableStateOf<List<ChapterGroup>>(emptyList()) }
    val allSegments = remember { mutableStateListOf<SegmentItem>() }
    // chapterIndex -> first segment index in allSegments
    var chapterStarts by remember { mutableStateOf<Map<Int, Int>>(emptyMap()) }

    var currentGroupIndex by remember { mutableIntStateOf(0) }
    var currentChapterId by remember { mutableLongStateOf(0L) }
    var savedSegmentIndex by remember { mutableLongStateOf(0L) }  // within-group offset
    var canSaveProgress by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    var showMenu by remember { mutableStateOf(true) }
    var showSettingsPanel by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // ---- Helpers ----

    fun buildChapterItems(n: LocalNovelInfo, ch: LocalNovelChapterInfo): List<SegmentItem> {
        val items = mutableListOf<SegmentItem>()
        items.add(SegmentItem.ChapterTitle(chapterId = ch.id, chapterIndex = ch.chapterIndex, title = ch.title))
        for (h in buildChapterSegments(n, ch, fileOps))
            items.add(SegmentItem.Content(chapterId = ch.id, chapterIndex = ch.chapterIndex, html = h))
        return items
    }

    /** Append all chapters of a group to allSegments. Returns the start index. */
    fun appendGroup(n: LocalNovelInfo, group: ChapterGroup): Int {
        val start = allSegments.size
        val ns = chapterStarts.toMutableMap()
        for (ch in group.chapters) {
            ns[ch.chapterIndex] = allSegments.size
            allSegments.addAll(buildChapterItems(n, ch))
        }
        chapterStarts = ns
        return start
    }

    /** Prepend all chapters of a group to allSegments, correcting scroll position. */
    suspend fun prependGroup(n: LocalNovelInfo, group: ChapterGroup) {
        val oldIndex = listState.firstVisibleItemIndex
        val oldOffset = listState.firstVisibleItemScrollOffset
        val items = mutableListOf<SegmentItem>()
        val ns = chapterStarts.toMutableMap()
        var offset = 0
        for (ch in group.chapters) {
            val chItems = buildChapterItems(n, ch)
            ns[ch.chapterIndex] = offset; offset += chItems.size; items.addAll(chItems)
        }
        for ((ci, idx) in chapterStarts) ns[ci] = (ns[ci] ?: idx) + offset
        chapterStarts = ns
        allSegments.addAll(0, items)
        if (allSegments.isNotEmpty())
            listState.scrollToItem((oldIndex + offset).coerceAtMost(allSegments.lastIndex), oldOffset)
    }

    /** Trim old groups if we have more than MAX_LOADED_GROUPS, keeping [keepFirst..keepLast]. */
    fun saveProgressNow(groupIndex: Int, globalIndex: Long, scrollOffset: Int = 0) {
        val g = groups.getOrNull(groupIndex) ?: return
        val chId = g.chapters.firstOrNull()?.id ?: return
        val chStart = chapterStarts[g.chapters.first().chapterIndex] ?: return
        val withinGroup = (globalIndex - chStart).coerceAtLeast(0)
        scope.launch {
            withContext(Dispatchers.Default) {
                repository.saveProgress(LocalNovelProgressInfo(
                    novelId = novelId, chapterId = chId,
                    charOffset = (withinGroup shl 32) or (scrollOffset.toLong() and 0xFFFF_FFFF),
                ))
                repository.updateLastReadAt(novelId, currentTimeMillis())
            }
        }
    }

    // ---- Initial load ----

    LaunchedEffect(novelId) {
        isLoading = true; canSaveProgress = false
        allSegments.clear(); chapterStarts = emptyMap()
        val data = withContext(Dispatchers.Default) {
            val n = repository.getNovelById(novelId) ?: return@withContext null
            val chs = repository.getChaptersByNovelId(novelId)
            val gs = groupChapters(chs)
            val progress = repository.getProgress(novelId)
            val restoreChId = progress?.chapterId ?: chs.firstOrNull()?.id ?: 0L
            val gi = findGroupIndex(gs, restoreChId)
            val encoded = progress?.charOffset ?: 0L
            InitData(n, chs, gs, gi, restoreChId, encoded shr 32, (encoded and 0xFFFF_FFFF).toInt())
        }
        if (data != null) {
            novel = data.novel; chapters = data.chapters; groups = data.groups
            currentGroupIndex = data.groupIndex; currentChapterId = data.restoreChapterId
            // Load current group
            appendGroup(data.novel, data.groups[data.groupIndex])
            // Load next group
            val nextGi = data.groupIndex + 1
            if (nextGi < data.groups.size) appendGroup(data.novel, data.groups[nextGi])
            isLoading = false
            // Restore position: within-group offset
            val g = data.groups[data.groupIndex]
            val chStart = chapterStarts[g.chapters.first().chapterIndex] ?: 0
            val target = (chStart + data.restoreWithinGroup.toInt()).coerceAtMost(allSegments.lastIndex.coerceAtLeast(0))
            listState.scrollToItem(target, data.restoreOff)
            kotlinx.coroutines.delay(120); listState.scrollToItem(target, data.restoreOff)
            kotlinx.coroutines.delay(300); listState.scrollToItem(target, data.restoreOff)
            savedSegmentIndex = (data.restoreWithinGroup shl 32) or (data.restoreOff.toLong() and 0xFFFF_FFFF)
            canSaveProgress = true
        }
    }

    // ---- Lazy-load adjacent groups ----

    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val firstVisible = info.visibleItemsInfo.firstOrNull()?.index ?: 0
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            Triple(firstVisible, lastVisible, info.totalItemsCount)
        }.collect { (first, last, total) ->
            if (!canSaveProgress || allSegments.isEmpty() || novel == null) return@collect
            val n = novel!!
            // Near bottom of loaded content: load next group
            if (total - last < 10) {
                val nextGi = currentGroupIndex + 1
                if (nextGi < groups.size) {
                    val firstCh = groups[nextGi].chapters.firstOrNull()
                    if (firstCh != null && firstCh.chapterIndex !in chapterStarts) {
                        appendGroup(n, groups[nextGi])
                        currentGroupIndex = nextGi
                    }
                }
            }
            // Near top: load previous group
            if (first < 2) {
                val prevGi = currentGroupIndex - 1
                if (prevGi >= 0) {
                    val firstCh = groups[prevGi].chapters.firstOrNull()
                    if (firstCh != null && firstCh.chapterIndex !in chapterStarts) {
                        scope.launch {
                            prependGroup(n, groups[prevGi])
                            currentGroupIndex = prevGi
                        }
                    }
                }
            }
        }
    }

    // ---- Progress tracking ----

    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                if (canSaveProgress && index in allSegments.indices) {
                    val seg = allSegments[index]
                    val chId = when (seg) { is SegmentItem.ChapterTitle -> seg.chapterId; is SegmentItem.Content -> seg.chapterId }
                    if (chId != currentChapterId) currentChapterId = chId
                    val gi = findGroupIndex(groups, chId)
                    val encoded = (index.toLong() shl 32) or (offset.toLong() and 0xFFFF_FFFF)
                    if (encoded != savedSegmentIndex) {
                        savedSegmentIndex = encoded
                        saveProgressNow(gi, index.toLong(), offset)
                    }
                }
            }
    }

    DisposableEffect(novelId) {
        onDispose {
            val idx = listState.firstVisibleItemIndex
            if (idx in allSegments.indices) {
                val seg = allSegments[idx]
                val chId = when (seg) { is SegmentItem.ChapterTitle -> seg.chapterId; is SegmentItem.Content -> seg.chapterId }
                val gi = findGroupIndex(groups, chId)
                val g = groups.getOrNull(gi) ?: return@onDispose
                val chStart = chapterStarts[g.chapters.first().chapterIndex] ?: 0
                val withinGroup = (idx - chStart).coerceAtLeast(0)
                kotlinx.coroutines.runBlocking {
                    withContext(Dispatchers.Default) {
                        repository.saveProgress(LocalNovelProgressInfo(
                            novelId = novelId, chapterId = g.chapters.first().id,
                            charOffset = (withinGroup.toLong() shl 32) or (listState.firstVisibleItemScrollOffset.toLong() and 0xFFFF_FFFF),
                        ))
                        repository.updateLastReadAt(novelId, currentTimeMillis())
                    }
                }
            }
        }
    }

    LaunchedEffect(showSettingsPanel) { if (!showSettingsPanel) showMenu = true else showMenu = false }

    val currentTitle by remember {
        derivedStateOf {
            val idx = listState.firstVisibleItemIndex
            if (idx in allSegments.indices) {
                when (val seg = allSegments[idx]) {
                    is SegmentItem.ChapterTitle -> seg.title
                    is SegmentItem.Content -> chapters.find { it.id == seg.chapterId }?.title ?: ""
                }
            } else ""
        }
    }

    // ---- UI ----

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                ChapterDrawerContent(
                    novelTitle = novel?.title ?: "", chapters = chapters, currentChapterId = currentChapterId,
                    onChapterClick = { ch ->
                        scope.launch {
                            val gi = findGroupIndex(groups, ch.id)
                            val g = groups[gi]
                            val n = novel ?: return@launch
                            if (g.chapters.firstOrNull()?.chapterIndex !in chapterStarts) {
                                allSegments.clear(); chapterStarts = emptyMap()
                                appendGroup(n, g)
                                if (gi + 1 < groups.size) appendGroup(n, groups[gi + 1])
                                currentGroupIndex = gi
                            }
                            val chStart = chapterStarts[ch.chapterIndex] ?: 0
                            listState.scrollToItem(chStart); drawerState.close()
                        }
                    },
                )
            }
        },
    ) {
        Box(Modifier.fillMaxSize().background(colors.creamBackground)) {
            if (isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = colors.brownPrimary) }
            } else {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().background(colors.creamSurface).statusBarsPadding().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { navigator.pop() }) { Text(i18n("返回"), color = colors.brownPrimary, fontSize = 14.sp) }
                        Spacer(Modifier.weight(1f))
                        Text(currentTitle, color = colors.textDark, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { scope.launch { drawerState.open() } }) { Text(i18n("目錄"), color = colors.brownPrimary, fontSize = 14.sp) }
                    }
                    LazyColumn(Modifier.fillMaxSize().weight(1f).padding(horizontal = 16.dp).pointerInput(Unit) { detectTapGestures { showMenu = !showMenu } }, state = listState) {
                        itemsIndexed(allSegments, key = { idx, _ -> idx }) { _, item ->
                            when (item) {
                                is SegmentItem.ChapterTitle -> {
                                    Spacer(Modifier.height(16.dp))
                                    Text(item.title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = colors.textDark, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                                    HorizontalDivider(color = colors.brownPrimary.copy(alpha = 0.4f), thickness = 2.dp)
                                    Spacer(Modifier.height(8.dp))
                                }
                                is SegmentItem.Content -> HtmlRenderer(html = item.html, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                            }
                        }
                        item { Spacer(Modifier.height(80.dp)) }
                    }
                }
            }
            if (showSettingsPanel) {
                Box(Modifier.fillMaxSize().clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { showSettingsPanel = false; showMenu = true })
                NovelReaderSettingsPanel(visible = showSettingsPanel, appSettingsRepo = appSettingsRepo, modifier = Modifier.align(Alignment.BottomCenter))
            }
            AnimatedVisibility(visible = showMenu && !showSettingsPanel, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 16.dp, end = 16.dp)) {
                ReaderFloatButtons(visible = true,
                    onRefresh = {
                        scope.launch {
                            isLoading = true; canSaveProgress = false; allSegments.clear(); chapterStarts = emptyMap()
                            val n = novel ?: return@launch
                            val gi = findGroupIndex(groups, currentChapterId)
                            appendGroup(n, groups[gi])
                            if (gi + 1 < groups.size) appendGroup(n, groups[gi + 1])
                            currentGroupIndex = gi; isLoading = false; canSaveProgress = true
                        }
                    },
                    onSettings = { showSettingsPanel = true; showMenu = false },
                )
            }
        }
    }
}

// ---- Chapter drawer ----

@Composable private fun ChapterDrawerContent(novelTitle: String, chapters: List<LocalNovelChapterInfo>, currentChapterId: Long, onChapterClick: (LocalNovelChapterInfo) -> Unit) {
    val colors = YamiboTheme.colors
    Column(Modifier.fillMaxWidth()) {
        Text(i18n("目錄"), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = colors.textDark, modifier = Modifier.padding(16.dp))
        Text(novelTitle, fontSize = 14.sp, color = colors.textOnBackground, modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp))
        HorizontalDivider(color = colors.textDark.copy(alpha = 0.1f))
        LazyColumn(Modifier.fillMaxWidth()) {
            itemsIndexed(chapters) { _, ch ->
                Text(ch.title, modifier = Modifier.fillMaxWidth().clickable { onChapterClick(ch) }.padding(vertical = 12.dp, horizontal = 16.dp),
                    color = if (ch.id == currentChapterId) colors.brownPrimary else colors.textDark,
                    fontWeight = if (ch.id == currentChapterId) FontWeight.Bold else FontWeight.Normal, fontSize = 15.sp)
            }
        }
    }
}

// ---- Segment builders ----

private fun buildChapterSegments(n: LocalNovelInfo, ch: LocalNovelChapterInfo, fo: me.thenano.yamibo.yamibo_app.repository.localnovel.PlatformFileOperations): List<String> {
    val html = when (n.fileType) {
        LocalNovelFileType.TXT -> { val t = TxtFileParser(fo).readChapterText(n.fileUri, n.encoding, ch.startOffset, ch.endOffset); textToHtml(t) }
        LocalNovelFileType.EPUB -> { val raw = EpubFileParser(fo).readChapterHtml(ch.internalPath); fixEpubImagePaths(raw, ch.internalPath.substringBeforeLast("/", "")) }
    }
    return segmentHtml(html, MAX_READER_TEXT_SEGMENT_CHARS)
}
private fun textToHtml(text: String) = buildString { for (l in text.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").split("\n")) { val t=l.trim(); if (t.isEmpty()) append("<br/>\n") else { append("<p>"); append(t); append("</p>\n") } } }
private fun segmentHtml(html: String, m: Int): List<String> { if (html.length<=m) return listOf(html); val c=mutableListOf<String>(); var r=html; while(r.length>m){ val s=(m*2/3).coerceAtLeast(0); var b=-1; for(i in m downTo s){ if(i+4<=r.length){ val w=r.substring(i,(i+4).coerceAtMost(r.length)); if(w.startsWith("<p>")||w.startsWith("<br")||w.startsWith("</p>")||w.startsWith("</d")){ b=i; break } } }; if(b==-1) b=m; c.add(r.substring(0,b).trim()); r=r.substring(b).trim() }; if(r.isNotBlank()) c.add(r); return c.ifEmpty{listOf(html)} }
private fun fixEpubImagePaths(h: String, d: String) = Regex("""(src=["'])(.*?)(["'])""", RegexOption.IGNORE_CASE).replace(h){ val p=it.groupValues[2]; if(p.startsWith("http")||p.startsWith("data:")||p.startsWith("/")) "${it.groupValues[1]}${p}${it.groupValues[3]}" else "${it.groupValues[1]}file://${if(d.isNotEmpty())"$d/$p" else p}${it.groupValues[3]}" }
private data class InitData(val novel: LocalNovelInfo, val chapters: List<LocalNovelChapterInfo>, val groups: List<ChapterGroup>, val groupIndex: Int, val restoreChapterId: Long, val restoreWithinGroup: Long, val restoreOff: Int)

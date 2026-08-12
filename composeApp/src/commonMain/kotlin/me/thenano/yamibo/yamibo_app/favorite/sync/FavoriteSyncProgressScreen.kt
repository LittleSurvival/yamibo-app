package me.thenano.yamibo.yamibo_app.favorite.sync

import me.thenano.yamibo.yamibo_app.i18n.i18n


import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.Serializable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.thenano.yamibo.yamibo_app.LocalFavoriteRepository
import me.thenano.yamibo.yamibo_app.LocalFavoriteSyncRunner
import me.thenano.yamibo.yamibo_app.components.navigation.YamiboTopBar
import me.thenano.yamibo.yamibo_app.navigation.LocalNavigator
import me.thenano.yamibo.yamibo_app.navigation.RestorableNavigatable
import me.thenano.yamibo.yamibo_app.navigation.RestorableScreenEntry
import me.thenano.yamibo.yamibo_app.navigation.RestorableScreenSnapshot
import me.thenano.yamibo.yamibo_app.navigation.TypedRestorableNavigatableDecoder
import me.thenano.yamibo.yamibo_app.navigation.decodeRestorePayload
import me.thenano.yamibo.yamibo_app.navigation.restoreSnapshot
import me.thenano.yamibo.yamibo_app.repository.FavoriteSyncRepository.*
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme
import me.thenano.yamibo.yamibo_app.util.time.currentTimeMillis
import me.thenano.yamibo.yamibo_app.util.time.formatDateTime
import kotlin.time.Duration.Companion.milliseconds

@Serializable
private data class FavoriteSyncProgressRestorePayload(
    val runId: String,
)
@RestorableScreenEntry
class IFavoriteSyncProgressScreen(
    val runId: String,
) : RestorableNavigatable {
    override val id = buildId(runId)
    override val restoreDecoder = Decoder

    override fun toRestoreSnapshot(): RestorableScreenSnapshot = restoreSnapshot(
        decoder = restoreDecoder,
        payload = FavoriteSyncProgressRestorePayload(runId = runId),
    )

    @Composable
    override fun Content() {
        FavoriteSyncProgressScreen(runId)
    }

    companion object Decoder : TypedRestorableNavigatableDecoder<IFavoriteSyncProgressScreen>(IFavoriteSyncProgressScreen::class) {
        override fun decode(payload: String): RestorableNavigatable {
            val data = decodeRestorePayload<FavoriteSyncProgressRestorePayload>(payload)
            return IFavoriteSyncProgressScreen(runId = data.runId)
        }
    }
}

@Composable
fun FavoriteSyncProgressScreen(runId: String) {
    val colors = YamiboTheme.colors
    val navigator = LocalNavigator.current
    val runner = LocalFavoriteSyncRunner.current
    val state by runner.state.collectAsState()
    val snapshot = state.snapshotOrNull()
    val coroutineScope = rememberCoroutineScope()

    BoxWithConstraints {
        val messageMaxHeight = maxHeight / 4

        Scaffold(
            topBar = {
                YamiboTopBar(
                    title = i18n("同步百合會收藏"),
                    titleFontSize = 18,
                    onBack = { navigator.pop() },
                )
            },
            containerColor = colors.creamBackground,
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when {
                    snapshot == null || snapshot.runId != runId -> {
                        Text(
                            text = i18n("找不到這個同步任務。"),
                            color = colors.textDark,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }

                    else -> {
                        FavoriteSyncStatusCard(
                            state = state,
                            modifier = Modifier.fillMaxWidth(),
                            messageMaxHeight = messageMaxHeight,
                            onInterrupt = if (state is FavoriteSyncState.Running) {
                                {
                                    coroutineScope.launch {
                                        runner.interruptImport(runId)
                                    }
                                }
                            } else {
                                null
                            },
                        )
                        Text(
                            text = i18n("關閉這個畫面不會中止同步，你之後仍可從收藏頁或設定頁重新查看進度。"),
                            color = colors.textDark.copy(alpha = 0.72f),
                            fontSize = 13.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun FavoriteSyncStatusCard(
    state: FavoriteSyncState,
    modifier: Modifier = Modifier,
    onOpenProgress: (() -> Unit)? = null,
    onResume: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
    onInterrupt: (() -> Unit)? = null,
    messageMaxHeight: Dp = 180.dp,
    contentMaxHeight: Dp? = null,
) {
    val colors = YamiboTheme.colors
    val favoriteRepository = LocalFavoriteRepository.current
    val coroutineScope = rememberCoroutineScope()
    val snapshot = state.snapshotOrNull() ?: return
    var now by remember(state, snapshot.runId) { mutableLongStateOf(currentTimeMillis()) }
    LaunchedEffect(state, snapshot.runId) {
        if (state is FavoriteSyncState.Running) {
            while (true) {
                now = currentTimeMillis()
                delay(1000.milliseconds)
            }
        } else {
            now = currentTimeMillis()
        }
    }
    val progressUi = remember(snapshot, now, state) { snapshot.toProgressUi() }
    val displayedElapsedDuration = if (state is FavoriteSyncState.Running) {
        (now - snapshot.startedAt).coerceAtLeast(0L)
    } else {
        snapshot.elapsedDurationMs
    }
    val lastSyncTimestamp = snapshot.lastCompletedAt ?: snapshot.updatedAt
    var categoryName by remember(snapshot.targetCategoryId) { mutableStateOf<String?>(null) }
    val contentScrollState = rememberScrollState()

    LaunchedEffect(snapshot.targetCategoryId) {
        categoryName = withContext(Dispatchers.Default) {
            favoriteRepository.getCategories().firstOrNull { it.id == snapshot.targetCategoryId }?.name
        }
    }

    Surface(
        modifier = modifier,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
        color = colors.creamSurface,
        border = BorderStroke(1.dp, colors.brownPrimary.copy(alpha = 0.18f)),
    ) {
        Column(
            modifier = Modifier
                .then(
                    if (contentMaxHeight != null) {
                        Modifier
                            .heightIn(max = contentMaxHeight)
                            .verticalScroll(contentScrollState)
                    } else {
                        Modifier
                    },
                )
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = state.title(),
                        color = colors.brownDeep,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                    )
                    Text(
                        text = i18n("目標類別：{}", (categoryName?.takeIf { it.isNotBlank() } ?: i18n("預設"))),
                        color = colors.textDark.copy(alpha = 0.72f),
                        fontSize = 13.sp,
                    )
                }
                Text(
                    text = "${(progressUi.progress * 100).toInt()}%",
                    color = colors.brownDeep,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = progressUi.label,
                    color = colors.textDark,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                )
                LinearProgressIndicator(
                    progress = { progressUi.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp),
                    color = colors.brownDeep,
                    trackColor = colors.brownPrimary.copy(alpha = 0.18f),
                )
                SyncMetricRow(i18n("最後一次同步時間"), formatDateTime(lastSyncTimestamp))
                SyncMetricRow(i18n("同步花費時間"), formatSyncDuration(displayedElapsedDuration))
                progressUi.lines.forEach { line ->
                    SyncMetricRow(line.first, line.second)
                }
            }

            snapshot.logMessage?.takeIf { it.isNotBlank() }?.let {
                SyncMessageBlock(
                    title = i18n("日誌"),
                    message = it,
                    tint = colors.brownPrimary,
                    textColor = colors.textDark,
                    maxHeight = messageMaxHeight,
                )
            }

            snapshot.warningMessage?.takeIf { it.isNotBlank() }?.let {
                SyncMessageBlock(
                    title = i18n("提醒"),
                    message = it,
                    tint = colors.orangeAccent,
                    textColor = colors.textStrong,
                    maxHeight = messageMaxHeight,
                )
            }

            snapshot.errorMessage?.takeIf { it.isNotBlank() }?.let {
                SyncMessageBlock(
                    title = i18n("錯誤"),
                    message = it,
                    tint = colors.redAccent,
                    textColor = colors.redAccent,
                    maxHeight = messageMaxHeight,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (onOpenProgress != null) {
                    Button(
                        onClick = onOpenProgress,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.brownDeep,
                            contentColor = Color.White,
                        ),
                    ) {
                        Text(if (state is FavoriteSyncState.Running) i18n("查看進度") else i18n("打開進度"))
                    }
                }
                if (onInterrupt != null && state is FavoriteSyncState.Running) {
                    Button(
                        onClick = onInterrupt,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF8C4B3B),
                            contentColor = Color.White,
                        ),
                    ) {
                        Text(i18n("中斷同步"))
                    }
                }
                if (onResume != null && state !is FavoriteSyncState.Running) {
                    Button(
                        onClick = { coroutineScope.launch { onResume() } },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.brownPrimary.copy(alpha = 0.18f),
                            contentColor = colors.textStrong,
                        ),
                    ) {
                        Text(if (state is FavoriteSyncState.Completed) i18n("重新同步") else i18n("繼續同步"))
                    }
                }
                if (onDismiss != null) {
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.textDark.copy(alpha = 0.06f),
                            contentColor = colors.textDark.copy(alpha = 0.82f),
                        ),
                    ) {
                        Text(i18n("隱藏"))
                    }
                }
            }
        }
    }
}

@Composable
private fun SyncMetricRow(label: String, value: String) {
    val colors = YamiboTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = colors.textDark.copy(alpha = 0.62f),
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            color = colors.textDark,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
        )
    }
}

@Composable
private fun SyncMessageBlock(
    title: String,
    message: String,
    tint: Color,
    textColor: Color = tint,
    maxHeight: Dp,
) {
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    val lines = remember(message) {
        message
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toList()
    }
    val viewportPx = with(density) { maxHeight.roundToPx() }.toFloat().coerceAtLeast(1f)
    val thumbMetrics by remember(scrollState, viewportPx) {
        derivedStateOf {
            val totalScrollablePx = scrollState.maxValue.toFloat()
            val totalContentPx = viewportPx + totalScrollablePx
            val heightFraction =
                if (totalContentPx <= 0f) 1f else (viewportPx / totalContentPx).coerceIn(0.18f, 1f)
            val offsetFraction =
                if (totalScrollablePx <= 0f) 0f else (scrollState.value.toFloat() / totalScrollablePx).coerceIn(0f, 1f)
            heightFraction to offsetFraction
        }
    }
    val thumbHeightFraction = thumbMetrics.first
    val thumbOffsetFraction = thumbMetrics.second

    LaunchedEffect(message, lines.size, scrollState.maxValue) {
        scrollState.scrollTo(scrollState.maxValue)
    }

    Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        color = tint.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.16f)),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = title, color = textColor, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Spacer(Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeight)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = 10.dp)
                        .verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    lines.forEach { line ->
                        Text(
                            text = line,
                            color = textColor,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .width(6.dp)
                        .background(tint.copy(alpha = 0.12f), androidx.compose.foundation.shape.RoundedCornerShape(999.dp))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(thumbHeightFraction)
                            .offset(y = maxHeight * ((1f - thumbHeightFraction) * thumbOffsetFraction))
                            .background(tint.copy(alpha = 0.55f), androidx.compose.foundation.shape.RoundedCornerShape(999.dp))
                    )
                }
            }
        }
    }
}

private fun formatSyncDuration(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0) {
        i18n("{}時 {}分 {}秒", hours, minutes, seconds)
    } else if (minutes > 0) {
        i18n("{}分 {}秒", minutes, seconds)
    } else {
        i18n("{}秒", seconds)
    }
}


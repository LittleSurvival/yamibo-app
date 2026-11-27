package me.thenano.yamibo.yamibo_app.thread.image

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.catch
import me.thenano.yamibo.yamibo_app.LocalDownloadRepository
import me.thenano.yamibo.yamibo_app.repository.download.MissingThreadImage
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import coil3.compose.rememberConstraintsSizeResolver
import me.thenano.yamibo.yamibo_app.i18n.i18n
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme
import me.thenano.yamibo.yamibo_app.thread.reader.debug.DebugRecomposeProbe
import me.thenano.yamibo.yamibo_app.thread.reader.debug.debugPerfLog
import me.thenano.yamibo.yamibo_app.util.imageErrorForDiagnostics
import me.thenano.yamibo.yamibo_app.util.imageSourceForDiagnostics
import me.thenano.yamibo.yamibo_app.util.normalizeImageUrl
import me.thenano.yamibo.yamibo_app.util.rememberImageRequest
import org.jetbrains.compose.resources.painterResource
import yamibo_app.composeapp.generated.resources.Res
import yamibo_app.composeapp.generated.resources.image_icon

val LocalReaderOverlayVisible = compositionLocalOf<() -> Boolean> { { false } }
internal interface ReaderImagePainterCache {
    operator fun get(url: String): Painter?
    fun put(url: String, painter: Painter)
    val size: Int
}

internal fun createReaderImagePainterCache(maxEntries: Int = 24, maxPixels: Long = 8_000_000L): ReaderImagePainterCache =
    LruReaderImagePainterCache(maxEntries, maxPixels)

internal val LocalReaderImagePainterCache = compositionLocalOf<ReaderImagePainterCache?> { null }
val LocalImageClickListener = compositionLocalOf<(() -> Unit)?> { null }
val LocalImageDoubleClickListener = compositionLocalOf<((String) -> Unit)?> { null }
val LocalImageSetCoverListener = compositionLocalOf<((String) -> Unit)?> { null }
val LocalImageSetCatalogCoverListener = compositionLocalOf<((String) -> Unit)?> { null }
val LocalImageSetCatalogCoverLabel = compositionLocalOf<String?> { null }
val LocalImageActionMessageListener = compositionLocalOf<((String) -> Unit)?> { null }

private class LruReaderImagePainterCache(
    private val maxEntries: Int,
    private val maxPixels: Long = 8_000_000L,
) : ReaderImagePainterCache {
    private val painters = mutableMapOf<String, Painter>()
    private val accessOrder = ArrayDeque<String>()
    private val pixelsByUrl = mutableMapOf<String, Long>()
    private var retainedPixels = 0L

    init {
        require(maxEntries > 0)
        require(maxPixels > 0)
    }

    override val size: Int get() = painters.size

    override fun get(url: String): Painter? = painters[url]?.also {
        accessOrder.remove(url)
        accessOrder.addLast(url)
    }

    override fun put(url: String, painter: Painter) {
        retainedPixels -= pixelsByUrl.remove(url) ?: 0L
        painters.remove(url)
        accessOrder.remove(url)
        val size = painter.intrinsicSize
        val pixels = if (size.width.isFinite() && size.height.isFinite()) {
            (size.width.toDouble() * size.height.toDouble()).toLong().coerceAtLeast(0)
        } else 0L
        // A single giant scan must not keep tens/hundreds of MB alive after scrolling away.
        if (pixels > maxPixels) return
        pixelsByUrl[url] = pixels
        retainedPixels += pixels
        painters[url] = painter
        accessOrder.remove(url)
        accessOrder.addLast(url)
        while (accessOrder.size > maxEntries || retainedPixels > maxPixels) {
            val removed = accessOrder.removeFirst()
            painters.remove(removed)
            retainedPixels -= pixelsByUrl.remove(removed) ?: 0L
        }
    }
}

private object ReaderImagePainterMemoryCache : ReaderImagePainterCache by LruReaderImagePainterCache(8, 2_000_000L)

/**
 * A unified image viewer for posts and manga reading.
 * Includes loading spinners, detailed error messaging, a retry button,
 * and an optional long-press context menu (`MangaImageContextMenu`).
 */
@Composable
fun ImageViewer(
    url: String,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    contentScale: ContentScale = ContentScale.FillWidth,
    fillContainer: Boolean = false,
    enableContextMenu: Boolean = true,
    isDarkTheme: Boolean = false,
    enableCrossfade: Boolean = true,
    onSuccess: ((String) -> Unit)? = null,
    onError: ((String, String) -> Unit)? = null,
    blockedErrorMessage: String? = null,
    externalRetryKey: Int = 0,
    onReload: (() -> Unit)? = null,
    cachedHeightPx: Int? = null,
    placeholderAspectRatio: Float? = null,
    maxRenderedHeight: Dp? = null,
    imageVerticalPadding: Dp = 1.dp,
    showLoadingPlaceholder: Boolean = true,
    loadingPlaceholderModifier: Modifier = Modifier,
    suppressLoadingPlaceholderWhenCached: Boolean = false,
    reuseCachedPainterWhileLoading: Boolean = false,
    onRenderedHeightChanged: ((Int) -> Unit)? = null,
    onRenderedAspectRatioChanged: ((Float) -> Unit)? = null,
) {
    val missing = remember(url) { MissingThreadImage.fromReaderUri(url) }
    val original = normalizeImageUrl(url)
    val localDownload = missing != null || original.startsWith("yamibo-image://")
    val colors = YamiboTheme.colors
    val errorBgColor = if (isDarkTheme) Color.White.copy(alpha = 0.08f) else colors.readerImageErrorLightSurface
    val errorTextColor = if (isDarkTheme) Color.White else colors.textDark
    val errorSubTextColor = if (isDarkTheme) Color.White.copy(alpha = 0.5f) else colors.textDark.copy(alpha = 0.6f)
    val errorUrlColor = if (isDarkTheme) Color.White.copy(alpha = 0.3f) else colors.brownDeep

    val repository = LocalDownloadRepository.current
    val scope = rememberCoroutineScope()
    var repairedUri by remember(url) { mutableStateOf<String?>(null) }
    var repairing by remember(url) { mutableStateOf(false) }
    var repairFailed by remember(url) { mutableStateOf(false) }
    val cachedHeight = with(LocalDensity.current) { cachedHeightPx?.toDp() }
    fun placeholderHeight(width: Dp): Dp {
        val estimated = cachedHeight ?: if (placeholderAspectRatio != null && width > 0.dp) {
            (width * placeholderAspectRatio).coerceAtLeast(220.dp)
        } else 220.dp
        return maxRenderedHeight?.let { estimated.coerceAtMost(it) } ?: estimated
    }
    val repairMessage = if (repairFailed) i18n("下載失敗，請確認網路或登入狀態後再試。") else null
    val localUrl = if (missing == null) original else key(missing) {
        val local by remember(repository, missing) {
            repository.observeLocalImage(missing).catch { emit(null) }
        }.collectAsState(initial = null)
        val source = repairedUri ?: local
        if (source == null) {
            BoxWithConstraints(
                modifier = modifier.then(maxRenderedHeight?.let { Modifier.heightIn(max = it) } ?: Modifier),
                contentAlignment = Alignment.Center,
            ) {
                val reservedHeight = placeholderHeight(maxWidth)
                if (repairing) {
                    ImageLoadingContent(reservedHeight, onImageRetry = null)
                } else {
                    ImageErrorContent(
                        fullUrl = imageSourceForDiagnostics(missing.sourceUrl),
                        errorMessage = repairMessage ?: i18n("這張圖片缺少離線檔案"),
                        reservedHeight = reservedHeight,
                        errorBgColor = errorBgColor,
                        errorTextColor = errorTextColor,
                        errorSubTextColor = errorSubTextColor,
                        errorUrlColor = errorUrlColor,
                    ) {
                        if (!repairing) {
                            repairing = true
                            scope.launch {
                                try {
                                    repository.retryImage(missing).onSuccess { repairedUri = it; repairFailed = false }.onFailure { repairFailed = true }
                                } finally {
                                    repairing = false
                                }
                            }
                        }
                    }
                }
            }
        }
        source
    } ?: return
    val fullUrl = repairedUri ?: localUrl

    val diagnosticUrl = imageSourceForDiagnostics(fullUrl)
    DebugRecomposeProbe("ImageViewer", diagnosticUrl)
    var localRetryKey by remember(fullUrl) { mutableIntStateOf(0) }
    fun retryFailedImage() {
        if (missing == null && !original.startsWith("yamibo-image://")) {
            if (onReload != null) onReload() else localRetryKey++
            return
        }
        if (repairing) return
        repairing = true
        scope.launch {
            try {
                val result = if (missing != null) repository.retryImage(missing, fullUrl)
                    else repository.retryArchivedImage(original, fullUrl)
                result.onSuccess {
                    repairFailed = false
                    repairedUri = it
                    localRetryKey++
                    onReload?.invoke()
                }.onFailure { repairFailed = true }
            } finally { repairing = false }
        }
    }
    var showMenu by remember { mutableStateOf(false) }
    val retryKey = localRetryKey + externalRetryKey
    var hasReportedSuccess by remember(fullUrl, localRetryKey, externalRetryKey) { mutableStateOf(false) }
    var hasReportedError by remember(fullUrl, localRetryKey, externalRetryKey) { mutableStateOf(false) }

    val isOverlayOpen = LocalReaderOverlayVisible.current

    val onSingleTap = LocalImageClickListener.current
    val onDoubleTap = LocalImageDoubleClickListener.current
    val onSetCover = LocalImageSetCoverListener.current
    val onSetCatalogCover = LocalImageSetCatalogCoverListener.current
    val catalogCoverLabel = LocalImageSetCatalogCoverLabel.current
    val onImageActionMessage = LocalImageActionMessageListener.current

    val hasGestures = enableContextMenu || onSingleTap != null || onDoubleTap != null

    val imageRequest = rememberImageRequest(
        url = fullUrl,
        retryKey = retryKey,
        enableCrossfade = enableCrossfade,
    )
    val sizeResolver = rememberConstraintsSizeResolver()
    val sizedImageRequest = remember(imageRequest, sizeResolver) {
        imageRequest.newBuilder()
            .size(sizeResolver)
            .build()
    }
    // Coil's default model equality ignores cache policies, so changing the request alone
    // does not restart a failed load. Each retry owns a fresh painter and request job.
    val painterKey = imageRequest.memoryCacheKey ?: fullUrl
    val painter = key(painterKey, localRetryKey, externalRetryKey) {
        rememberAsyncImagePainter(model = sizedImageRequest)
    }
    val painterState by key(painter) { painter.state.collectAsState() }
    val sharedPainterCache = LocalReaderImagePainterCache.current
    var lastSuccessfulPainter by remember(painterKey) {
        mutableStateOf(sharedPainterCache?.get(painterKey) ?: ReaderImagePainterMemoryCache[painterKey])
    }
    val latestOverlayOpen by rememberUpdatedState(isOverlayOpen)
    val latestOnSingleTap by rememberUpdatedState(onSingleTap)
    val latestOnDoubleTap by rememberUpdatedState(onDoubleTap)
    val latestEnableContextMenu by rememberUpdatedState(enableContextMenu)

    BoxWithConstraints(
        modifier = modifier
            .then(sizeResolver)
            .then(
                if (hasGestures) {
                    Modifier.pointerInput(hasGestures, enableContextMenu) {
                        detectTapGestures(
                            onTap = {
                                latestOnSingleTap?.invoke()
                            },
                            onDoubleTap = {
                                latestOnDoubleTap?.invoke(original)
                            },
                            onLongPress = {
                                if (latestEnableContextMenu && !latestOverlayOpen()) {
                                    showMenu = true
                                }
                            }
                        )
                    }
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        val reservedHeight = placeholderHeight(maxWidth)

        Box(
            modifier = (if (fillContainer) Modifier.fillMaxSize() else Modifier.fillMaxWidth())
                .then(maxRenderedHeight?.let { Modifier.heightIn(max = it) } ?: Modifier),
            contentAlignment = Alignment.Center
        ) {
            if (repairing) {
                ImageLoadingContent(reservedHeight, onImageRetry = null)
            } else if (blockedErrorMessage != null && repairedUri == null) {
                ImageErrorContent(
                    reservedHeight = reservedHeight,
                    fullUrl = if (localDownload) failedImageSource(original, missing?.sourceUrl) else diagnosticUrl,
                    errorMessage = if (localDownload) repairMessage ?: i18n("這張離線圖片無法讀取") else imageErrorForDiagnostics(blockedErrorMessage, fullUrl),
                    errorBgColor = errorBgColor,
                    errorTextColor = errorTextColor,
                    errorSubTextColor = errorSubTextColor,
                    errorUrlColor = errorUrlColor,
                    onReload = ::retryFailedImage,
                )
            } else {
                when (val state = painterState) {
                    is AsyncImagePainter.State.Loading, is AsyncImagePainter.State.Empty -> {
                        val cachedPainter = lastSuccessfulPainter
                        if ((suppressLoadingPlaceholderWhenCached || reuseCachedPainterWhileLoading) && cachedPainter != null) {
                            Image(
                                painter = cachedPainter,
                                contentDescription = contentDescription ?: "Yamibo Image",
                                modifier = (if (fillContainer) Modifier.fillMaxSize() else Modifier.fillMaxWidth())
                                    .then(maxRenderedHeight?.let { Modifier.heightIn(max = it) } ?: Modifier)
                                    .padding(vertical = if (fillContainer) 0.dp else imageVerticalPadding),
                                contentScale = contentScale
                            )
                        } else if (showLoadingPlaceholder && !suppressLoadingPlaceholderWhenCached) {
                            ImageLoadingContent(
                                reservedHeight = reservedHeight,
                                onImageRetry = onReload,
                                modifier = loadingPlaceholderModifier,
                            )
                        }
                    }

                    is AsyncImagePainter.State.Error -> {
                        val errorMsg = imageErrorForDiagnostics(
                            state.result.throwable.message ?: "Unknown Error",
                            fullUrl,
                        )

                        LaunchedEffect(fullUrl, localRetryKey, externalRetryKey, errorMsg) {
                            if (!hasReportedError) {
                                hasReportedError = true
                                debugPerfLog("image_error|url=$diagnosticUrl|retryKey=$retryKey|msg=$errorMsg")
                                onError?.invoke(fullUrl, errorMsg)
                            }
                        }

                        ImageErrorContent(
                            reservedHeight = reservedHeight,
                            fullUrl = if (localDownload) failedImageSource(original, missing?.sourceUrl) else diagnosticUrl,
                            errorMessage = if (localDownload) repairMessage ?: i18n("這張離線圖片無法讀取") else errorMsg,
                            errorBgColor = errorBgColor,
                            errorTextColor = errorTextColor,
                            errorSubTextColor = errorSubTextColor,
                            errorUrlColor = errorUrlColor,
                            onReload = ::retryFailedImage,
                        )
                    }

                    is AsyncImagePainter.State.Success -> {
                        SideEffect {
                            lastSuccessfulPainter = state.painter
                            sharedPainterCache?.put(painterKey, state.painter)
                            ReaderImagePainterMemoryCache.put(painterKey, state.painter)
                        }
                        LaunchedEffect(fullUrl, localRetryKey, externalRetryKey, onSuccess) {
                            if (!hasReportedSuccess) {
                                hasReportedSuccess = true
                                debugPerfLog("image_success|url=$diagnosticUrl|retryKey=$retryKey")
                                onSuccess?.invoke(fullUrl)
                            }
                        }

                        Image(
                            painter = painter,
                            contentDescription = contentDescription ?: "Yamibo Image",
                            modifier = (if (fillContainer) Modifier.fillMaxSize() else Modifier.fillMaxWidth())
                                .then(maxRenderedHeight?.let { Modifier.heightIn(max = it) } ?: Modifier)
                                .padding(vertical = if (fillContainer) 0.dp else imageVerticalPadding)
                                .onSizeChanged { size ->
                                    if (size.height > 0) {
                                        onRenderedHeightChanged?.invoke(size.height)
                                    }
                                    if (size.width > 0 && size.height > 0) {
                                        onRenderedAspectRatioChanged?.invoke(size.height.toFloat() / size.width.toFloat())
                                    }
                                },
                            contentScale = contentScale
                        )
                    }
                }
            }

            ImageContextMenu(
                visible = enableContextMenu && showMenu,
                imageUrl = fullUrl,
                onSetAsCover = onSetCover,
                onSetAsCatalogCover = onSetCatalogCover,
                catalogCoverLabel = catalogCoverLabel,
                onMessage = onImageActionMessage,
                onDismiss = { showMenu = false }
            )
        }
    }
}

@Composable
private fun ImageLoadingContent(
    reservedHeight: Dp,
    onImageRetry: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = YamiboTheme.colors
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(reservedHeight)
            .padding(vertical = 1.dp),
        shape = RoundedCornerShape(14.dp),
        color = colors.creamSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.brownPrimary.copy(alpha = 0.12f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator(
                color = colors.brownPrimary,
                modifier = Modifier.size(34.dp),
                strokeWidth = 3.dp,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = i18n("圖片載入中"),
                color = colors.textDark.copy(alpha = 0.72f),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
            if (onImageRetry != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = i18n("若等待過久可重新載入"),
                    color = colors.textDark.copy(alpha = 0.48f),
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
private fun failedImageSource(uri: String, knownSource: String?): String {
    if (knownSource != null) return imageSourceForDiagnostics(knownSource)
    val repository = LocalDownloadRepository.current
    val source by produceState<String?>(null, repository, uri) { value = repository.archivedImageSource(uri) }
    return source?.let(::imageSourceForDiagnostics) ?: i18n("離線圖片")
}

@Composable
private fun ImageErrorContent(
    fullUrl: String,
    errorMessage: String,
    reservedHeight: Dp,
    errorBgColor: Color,
    errorTextColor: Color,
    errorSubTextColor: Color,
    errorUrlColor: Color,
    onReload: () -> Unit,
) {
    val colors = YamiboTheme.colors

    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val compact = maxWidth <= 140.dp || maxHeight <= 140.dp
        val bounded = constraints.hasBoundedHeight
        Column(
            modifier = Modifier.fillMaxWidth()
                .heightIn(min = reservedHeight)
                .padding(if (compact) 1.dp else 16.dp)
                .background(errorBgColor, RoundedCornerShape(12.dp))
                .padding(if (compact) 1.dp else 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // In a fixed-height cover or page the retry action must stay reachable.
            Column(
                modifier = if (bounded) Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()) else Modifier,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val heading: @Composable () -> Unit = {
                    Image(
                        painter = painterResource(Res.drawable.image_icon),
                        contentDescription = "Image Load Failed",
                        modifier = Modifier.size(if (compact) 18.dp else 64.dp),
                    )
                    Spacer(Modifier.size(if (compact) 3.dp else 12.dp))
                    Text(
                        text = i18n("圖片載入失敗"),
                        color = errorTextColor,
                        fontSize = 14.sp,
                        lineHeight = if (compact) 15.sp else TextUnit.Unspecified,
                        maxLines = if (compact) 1 else Int.MAX_VALUE,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (compact) {
                    Row(verticalAlignment = Alignment.CenterVertically) { heading() }
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) { heading() }
                }
                Spacer(Modifier.height(if (compact) 0.dp else 6.dp))
                Text(
                    text = errorMessage,
                    color = errorSubTextColor,
                    fontSize = 11.sp,
                    lineHeight = if (compact) 13.sp else TextUnit.Unspecified,
                    maxLines = if (compact) 1 else 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(if (compact) 2.dp else 4.dp))
                Text(
                    text = fullUrl,
                    color = errorUrlColor,
                    fontSize = 10.sp,
                    lineHeight = if (compact) 12.sp else TextUnit.Unspecified,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(textDecoration = TextDecoration.Underline)
                )
            }
            Spacer(Modifier.height(if (compact) 1.dp else 16.dp))
            OutlinedButton(
                onClick = onReload,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = colors.brownPrimary
                ),
                contentPadding = PaddingValues(horizontal = if (compact) 4.dp else 28.dp, vertical = 8.dp)
            ) {
                Text(
                    text = i18n("重新載入"),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

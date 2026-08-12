package me.thenano.yamibo.yamibo_app.thread.image

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

val LocalReaderOverlayVisible = compositionLocalOf { false }
internal interface ReaderImagePainterCache {
    operator fun get(url: String): Painter?
    fun put(url: String, painter: Painter)
    val size: Int
}

internal fun createReaderImagePainterCache(maxEntries: Int = 24): ReaderImagePainterCache =
    LruReaderImagePainterCache(maxEntries)

internal val LocalReaderImagePainterCache = compositionLocalOf<ReaderImagePainterCache?> { null }
val LocalImageClickListener = compositionLocalOf<(() -> Unit)?> { null }
val LocalImageDoubleClickListener = compositionLocalOf<((String) -> Unit)?> { null }
val LocalImageSetCoverListener = compositionLocalOf<((String) -> Unit)?> { null }
val LocalImageSetCatalogCoverListener = compositionLocalOf<((String) -> Unit)?> { null }
val LocalImageSetCatalogCoverLabel = compositionLocalOf<String?> { null }
val LocalImageActionMessageListener = compositionLocalOf<((String) -> Unit)?> { null }

private class LruReaderImagePainterCache(
    private val maxEntries: Int,
) : ReaderImagePainterCache {
    private val painters = mutableMapOf<String, Painter>()
    private val accessOrder = ArrayDeque<String>()

    init {
        require(maxEntries > 0)
    }

    override val size: Int get() = painters.size

    override fun get(url: String): Painter? = painters[url]?.also {
        accessOrder.remove(url)
        accessOrder.addLast(url)
    }

    override fun put(url: String, painter: Painter) {
        painters[url] = painter
        accessOrder.remove(url)
        accessOrder.addLast(url)
        while (accessOrder.size > maxEntries) {
            painters.remove(accessOrder.removeFirst())
        }
    }
}

private object ReaderImagePainterMemoryCache : ReaderImagePainterCache by LruReaderImagePainterCache(24)

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
    suppressLoadingPlaceholderWhenCached: Boolean = false,
    onRenderedHeightChanged: ((Int) -> Unit)? = null,
    onRenderedAspectRatioChanged: ((Float) -> Unit)? = null,
) {
    val fullUrl = normalizeImageUrl(url)
    val diagnosticUrl = imageSourceForDiagnostics(fullUrl)
    DebugRecomposeProbe("ImageViewer", diagnosticUrl)
    var localRetryKey by remember { mutableIntStateOf(0) }
    var showMenu by remember { mutableStateOf(false) }
    val retryKey = localRetryKey + externalRetryKey
    var hasReportedSuccess by remember(url, retryKey) { mutableStateOf(false) }
    var hasReportedError by remember(url, retryKey) { mutableStateOf(false) }

    val colors = YamiboTheme.colors
    val isOverlayOpen = LocalReaderOverlayVisible.current
    val density = LocalDensity.current

    val onSingleTap = LocalImageClickListener.current
    val onDoubleTap = LocalImageDoubleClickListener.current
    val onSetCover = LocalImageSetCoverListener.current
    val onSetCatalogCover = LocalImageSetCatalogCoverListener.current
    val catalogCoverLabel = LocalImageSetCatalogCoverLabel.current
    val onImageActionMessage = LocalImageActionMessageListener.current

    val hasGestures = enableContextMenu || onSingleTap != null || onDoubleTap != null

    // Formatting styles
    val errorBgColor = if (isDarkTheme) Color.White.copy(alpha = 0.08f) else Color(0xFFF3F3F3)
    val errorTextColor = if (isDarkTheme) Color.White else colors.textDark
    val errorSubTextColor = if (isDarkTheme) Color.White.copy(alpha = 0.5f) else colors.textDark.copy(alpha = 0.6f)
    val errorUrlColor = if (isDarkTheme) Color.White.copy(alpha = 0.3f) else colors.brownDeep

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
    val painter = rememberAsyncImagePainter(model = sizedImageRequest)
    val painterState by painter.state.collectAsState()
    val sharedPainterCache = LocalReaderImagePainterCache.current
    var lastSuccessfulPainter by remember(fullUrl) {
        mutableStateOf(sharedPainterCache?.get(fullUrl) ?: ReaderImagePainterMemoryCache[fullUrl])
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
                                latestOnDoubleTap?.invoke(fullUrl)
                            },
                            onLongPress = {
                                if (latestEnableContextMenu && !latestOverlayOpen) {
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
        val reservedHeight = remember(cachedHeightPx, placeholderAspectRatio, maxWidth, density, maxRenderedHeight) {
            val estimatedHeight = when {
                cachedHeightPx != null -> with(density) { cachedHeightPx.toDp() }
                placeholderAspectRatio != null && maxWidth > 0.dp -> {
                    (maxWidth * placeholderAspectRatio).coerceAtLeast(220.dp)
                }
                else -> 220.dp
            }
            maxRenderedHeight?.let { estimatedHeight.coerceAtMost(it) } ?: estimatedHeight
        }

        Box(
            modifier = (if (fillContainer) Modifier.fillMaxSize() else Modifier.fillMaxWidth())
                .then(maxRenderedHeight?.let { Modifier.heightIn(max = it) } ?: Modifier),
            contentAlignment = Alignment.Center
        ) {
            if (blockedErrorMessage != null) {
                ImageErrorContent(
                    fullUrl = diagnosticUrl,
                    errorMessage = imageErrorForDiagnostics(blockedErrorMessage, fullUrl),
                    reservedHeight = reservedHeight,
                    errorBgColor = errorBgColor,
                    errorTextColor = errorTextColor,
                    errorSubTextColor = errorSubTextColor,
                    errorUrlColor = errorUrlColor,
                    onReload = {
                        if (onReload != null) {
                            onReload()
                        } else {
                            localRetryKey++
                        }
                    }
                )
            } else {
                when (val state = painterState) {
                    is AsyncImagePainter.State.Loading, is AsyncImagePainter.State.Empty -> {
                        val cachedPainter = lastSuccessfulPainter
                        if (suppressLoadingPlaceholderWhenCached && cachedPainter != null) {
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
                            )
                        }
                    }

                    is AsyncImagePainter.State.Error -> {
                        val errorMsg = imageErrorForDiagnostics(
                            state.result.throwable.message ?: "Unknown Error",
                            fullUrl,
                        )

                        LaunchedEffect(fullUrl, retryKey, errorMsg) {
                            if (!hasReportedError) {
                                hasReportedError = true
                                debugPerfLog("image_error|url=$diagnosticUrl|retryKey=$retryKey|msg=$errorMsg")
                                onError?.invoke(fullUrl, errorMsg)
                            }
                        }

                        ImageErrorContent(
                            fullUrl = diagnosticUrl,
                            errorMessage = errorMsg,
                            reservedHeight = reservedHeight,
                            errorBgColor = errorBgColor,
                            errorTextColor = errorTextColor,
                            errorSubTextColor = errorSubTextColor,
                            errorUrlColor = errorUrlColor,
                            onReload = {
                                if (onReload != null) {
                                    onReload()
                                } else {
                                    localRetryKey++
                                }
                            }
                        )
                    }

                    is AsyncImagePainter.State.Success -> {
                        SideEffect {
                            lastSuccessfulPainter = state.painter
                            sharedPainterCache?.put(fullUrl, state.painter)
                            ReaderImagePainterMemoryCache.put(fullUrl, state.painter)
                        }
                        LaunchedEffect(fullUrl, retryKey, onSuccess) {
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
) {
    val colors = YamiboTheme.colors
    Surface(
        modifier = Modifier
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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(reservedHeight)
            .padding(16.dp)
            .background(errorBgColor, RoundedCornerShape(12.dp))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Image(
            painter = painterResource(Res.drawable.image_icon),
            contentDescription = "Image Load Failed",
            modifier = Modifier.size(64.dp)
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = i18n("圖片載入失敗"),
            color = errorTextColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = errorMessage,
            color = errorSubTextColor,
            fontSize = 11.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = fullUrl,
            color = errorUrlColor,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(textDecoration = TextDecoration.Underline)
        )
        Spacer(Modifier.height(16.dp))
        OutlinedButton(
            onClick = onReload,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = colors.brownPrimary
            ),
            contentPadding = PaddingValues(horizontal = 28.dp, vertical = 8.dp)
        ) {
            Text(
                text = i18n("重新載入"),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

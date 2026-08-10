package me.thenano.yamibo.yamibo_app.thread.reader.components.post.impl

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.littlesurvival.dto.value.ThreadId
import me.thenano.yamibo.yamibo_app.LocalFontRepository
import me.thenano.yamibo.yamibo_app.components.font.getFontFamily
import me.thenano.yamibo.yamibo_app.LocalNovelReaderSettingsRepository
import me.thenano.yamibo.yamibo_app.Logger
import me.thenano.yamibo.yamibo_app.components.text.rememberConvertedText
import me.thenano.yamibo.yamibo_app.i18n.i18n
import me.thenano.yamibo.yamibo_app.navigation.IInAppLinkResolvingScreen
import me.thenano.yamibo.yamibo_app.navigation.LocalNavigator
import me.thenano.yamibo.yamibo_app.navigation.looksLikeSupportedYamiboInAppLink
import me.thenano.yamibo.yamibo_app.repository.inapplinknavigation.InAppLinkContext
import me.thenano.yamibo.yamibo_app.repository.inapplinknavigation.normalizeYamiboUrl
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme
import me.thenano.yamibo.yamibo_app.thread.image.ImageViewer
import me.thenano.yamibo.yamibo_app.thread.reader.debug.DebugRecomposeProbe
import me.thenano.yamibo.yamibo_app.util.normalizeImageUrl
import me.thenano.yamibo.yamibo_app.util.rememberImageRequest
import me.thenano.yamibo.yamibo_app.util.state
import me.thenano.yamibo.yamibo_app.webview.IPlatformWebView

internal fun normalizeHtmlBlocks(rawBlocks: List<HtmlBlock>): List<HtmlBlock> {
    val maxMergedTextLength = 320
    val filteredBlocks = rawBlocks.filterIndexed { index, block ->
        if (block is HtmlBlock.Text) {
            val content = block.annotatedString.text.trim()
            if (content.isEmpty()) {
                val prev = rawBlocks.getOrNull(index - 1)
                val next = rawBlocks.getOrNull(index + 1)
                val adjacentVisualBlock =
                    prev is HtmlBlock.Image || next is HtmlBlock.Image ||
                        prev is HtmlBlock.Attachment || next is HtmlBlock.Attachment
                return@filterIndexed !adjacentVisualBlock
            }
        }
        true
    }

    val mergedBlocks = mutableListOf<HtmlBlock>()
    var pendingTextBuilder: AnnotatedString.Builder? = null
    var pendingTextAlign: TextAlign? = null
    var pendingTextAnchorId: String? = null
    val pendingRubies = mutableListOf<HtmlBlock.RubyText>()

    fun flushPendingText() {
        val builder = pendingTextBuilder ?: return
        val text = builder.toAnnotatedString()
        if (text.isNotEmpty()) {
            mergedBlocks += HtmlBlock.Text(
                annotatedString = text,
                textAlign = pendingTextAlign ?: TextAlign.Start,
                rubies = pendingRubies.toList(),
                anchorId = pendingTextAnchorId.orEmpty(),
            )
        }
        pendingTextBuilder = null
        pendingTextAlign = null
        pendingTextAnchorId = null
        pendingRubies.clear()
    }

    fun appendTextBlock(block: HtmlBlock.Text) {
        val builder = pendingTextBuilder
        val pendingLength = builder?.length ?: 0
        var appendOffset = pendingLength
        val shouldSplitLongText = builder != null &&
            pendingLength > 0 &&
            pendingLength + block.annotatedString.length > maxMergedTextLength
        if (builder == null || pendingTextAlign != block.textAlign || shouldSplitLongText) {
            flushPendingText()
            pendingTextBuilder = AnnotatedString.Builder()
            pendingTextAlign = block.textAlign
            pendingTextAnchorId = block.anchorId
            appendOffset = 0
        } else {
            val currentText = builder.toAnnotatedString().text
            val nextText = block.annotatedString.text
            if (currentText.isNotEmpty() && currentText.last() != '\n' && nextText.firstOrNull() != '\n') {
                builder.append("\n")
                appendOffset += 1
            }
        }
        pendingTextBuilder?.append(block.annotatedString)
        pendingRubies += block.rubies.map { ruby ->
            ruby.copy(
                start = ruby.start + appendOffset,
                end = ruby.end + appendOffset,
            )
        }
    }

    fun splitLongTextBlock(block: HtmlBlock.Text): List<HtmlBlock.Text> {
        if (block.annotatedString.length <= maxMergedTextLength) return listOf(block)

        val chunks = mutableListOf<HtmlBlock.Text>()
        val text = block.annotatedString.text
        var start = 0
        while (start < text.length) {
            val preferredEnd = (start + maxMergedTextLength).coerceAtMost(text.length)
            val end = if (preferredEnd < text.length) {
                val newline = text.lastIndexOf('\n', preferredEnd)
                if (newline > start + maxMergedTextLength / 3) newline + 1 else preferredEnd
            } else {
                preferredEnd
            }
            val chunk = block.annotatedString.subSequence(start, end)
            if (chunk.isNotEmpty()) {
                val chunkRubies = block.rubies
                    .filter { it.start >= start && it.end <= end }
                    .map { ruby ->
                        ruby.copy(
                            start = ruby.start - start,
                            end = ruby.end - start,
                        )
                    }
                chunks += block.copy(
                    annotatedString = chunk,
                    rubies = chunkRubies,
                    anchorId = if (start == 0) block.anchorId else "${block.anchorId}-$start",
                )
            }
            start = end
        }
        return chunks
    }

    filteredBlocks.forEach { block ->
        if (block is HtmlBlock.Text) {
            splitLongTextBlock(block).forEach(::appendTextBlock)
        } else {
            flushPendingText()
            mergedBlocks += block
        }
    }
    flushPendingText()

    return mergedBlocks
}

private data class LinkMenuState(
    val url: String,
    val linkText: String,
    val canOpenInApp: Boolean,
)

private const val SPOILER_ANNOTATION_TAG = "YAMIBO_SPOILER"

private fun applyThemedLinkStyle(text: AnnotatedString, linkColor: Color): AnnotatedString {
    val links = text.getStringAnnotations("URL", 0, text.length)
    if (links.isEmpty()) return text
    val builder = AnnotatedString.Builder(text)
    links.forEach { link ->
        builder.addStyle(
            SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
            link.start,
            link.end,
        )
    }
    return builder.toAnnotatedString()
}

private fun applySpoilerAnnotations(
    text: AnnotatedString,
    revealedKeys: Set<String>,
    defaultTextColor: Color,
): AnnotatedString {
    val spoilerRanges = findSpoilerRanges(text)
    if (spoilerRanges.isEmpty()) return text

    val builder = AnnotatedString.Builder(text)
    spoilerRanges.forEach { range ->
        val key = spoilerKey(range.start, range.end)
        builder.addStringAnnotation(SPOILER_ANNOTATION_TAG, key, range.start, range.end)
        if (key in revealedKeys) {
            builder.addStyle(
                SpanStyle(color = readableSpoilerTextColor(range.background, defaultTextColor)),
                range.start,
                range.end,
            )
        }
    }
    return builder.toAnnotatedString()
}

private data class SpoilerRange(
    val start: Int,
    val end: Int,
    val background: Color,
)

private fun findSpoilerRanges(text: AnnotatedString): List<SpoilerRange> {
    val directRanges = text.spanStyles.mapNotNull { range ->
        val color = range.item.color
        val background = range.item.background
        if (
            range.start < range.end &&
            color != Color.Unspecified &&
            background != Color.Unspecified &&
            color.isVisuallySameAs(background)
        ) {
            SpoilerRange(range.start, range.end, background)
        } else {
            null
        }
    }

    val colorRanges = text.spanStyles.filter { it.item.color != Color.Unspecified && it.start < it.end }
    val backgroundRanges = text.spanStyles.filter { it.item.background != Color.Unspecified && it.start < it.end }
    val overlapRanges = colorRanges.flatMap { colorRange ->
        backgroundRanges.mapNotNull { backgroundRange ->
            if (!colorRange.item.color.isVisuallySameAs(backgroundRange.item.background)) {
                return@mapNotNull null
            }
            val start = maxOf(colorRange.start, backgroundRange.start)
            val end = minOf(colorRange.end, backgroundRange.end)
            if (start < end) SpoilerRange(start, end, backgroundRange.item.background) else null
        }
    }

    return (directRanges + overlapRanges)
        .distinctBy { spoilerKey(it.start, it.end) }
        .sortedWith(compareBy<SpoilerRange> { it.start }.thenBy { it.end })
}

private fun spoilerKey(start: Int, end: Int): String = "$start:$end"

private fun Color.isVisuallySameAs(other: Color): Boolean {
    return alpha > 0.01f &&
        other.alpha > 0.01f &&
        kotlin.math.abs(red - other.red) < 0.015f &&
        kotlin.math.abs(green - other.green) < 0.015f &&
        kotlin.math.abs(blue - other.blue) < 0.015f
}

private fun readableSpoilerTextColor(background: Color, fallback: Color): Color {
    val luminance = (0.299f * background.red) + (0.587f * background.green) + (0.114f * background.blue)
    return if (luminance < 0.45f) Color.White else fallback
}

private fun htmlTextLineHeightSp(
    baseFontSizeSp: Float,
    lineSpacing: Float,
    text: AnnotatedString,
    hasRuby: Boolean = false,
): Float {
    val largestSpanFontSizeSp = text.spanStyles
        .mapNotNull { it.item.fontSize.toAbsoluteSpOrNull(baseFontSizeSp) }
        .maxOrNull()
        ?: baseFontSizeSp
    val largestFontSizeSp = maxOf(baseFontSizeSp, largestSpanFontSizeSp)
    val configuredLineHeightSp = baseFontSizeSp * lineSpacing
    val requiredLineHeightSp = largestFontSizeSp * maxOf(lineSpacing, 1.2f)
    val rubyLineHeightSp = if (hasRuby) largestFontSizeSp * maxOf(lineSpacing, 2.15f) else 0f
    return maxOf(configuredLineHeightSp, requiredLineHeightSp, rubyLineHeightSp)
}

private fun TextUnit.toAbsoluteSpOrNull(baseFontSizeSp: Float): Float? {
    return when (type) {
        TextUnitType.Sp -> value
        TextUnitType.Em -> value * baseFontSizeSp
        else -> null
    }
}

private fun String.isHtmlBlankText(): Boolean {
    return all { it.isWhitespace() || it == '\u3000' }
}

@Composable
fun HtmlRenderer(
    html: String,
    tid: ThreadId? = null,
    linkContext: InAppLinkContext = InAppLinkContext(currentTid = tid),
    @Suppress("ModifierParameter") modifier: Modifier = Modifier,
    onImageSuccess: ((String) -> Unit)? = null,
    onImageError: ((String, String) -> Unit)? = null,
    onImageReload: ((String) -> Unit)? = null,
    imageErrorMessageFor: ((String) -> String?)? = null,
    imageRetryKeyFor: ((String) -> Int)? = null,
    imageHasLoadedFor: ((String) -> Boolean)? = null,
    imageCachedHeightFor: ((String) -> Int?)? = null,
    imagePlaceholderAspectRatioFor: ((String) -> Float?)? = null,
    maxImageHeight: Dp? = null,
    onImageHeightChanged: ((String, Int) -> Unit)? = null,
    onImageAspectRatioChanged: ((String, Float) -> Unit)? = null,
) {
    DebugRecomposeProbe("HtmlRenderer", html.hashCode().toString())
    val convertedHtml = rememberConvertedText(html)
    val rawBlocks = remember(convertedHtml) { HtmlParser.parseHtml(convertedHtml) }
    val blocks = remember(rawBlocks) { normalizeHtmlBlocks(rawBlocks) }
    HtmlBlocksRenderer(
        blocks = blocks,
        tid = tid,
        linkContext = linkContext,
        modifier = modifier,
        onImageSuccess = onImageSuccess,
        onImageError = onImageError,
        onImageReload = onImageReload,
        imageErrorMessageFor = imageErrorMessageFor,
        imageRetryKeyFor = imageRetryKeyFor,
        imageHasLoadedFor = imageHasLoadedFor,
        imageCachedHeightFor = imageCachedHeightFor,
        imagePlaceholderAspectRatioFor = imagePlaceholderAspectRatioFor,
        maxImageHeight = maxImageHeight,
        onImageHeightChanged = onImageHeightChanged,
        onImageAspectRatioChanged = onImageAspectRatioChanged,
    )
}

@Composable
fun HtmlBlocksRenderer(
    blocks: List<HtmlBlock>,
    tid: ThreadId? = null,
    linkContext: InAppLinkContext = InAppLinkContext(currentTid = tid),
    @Suppress("ModifierParameter") modifier: Modifier = Modifier,
    onImageSuccess: ((String) -> Unit)? = null,
    onImageError: ((String, String) -> Unit)? = null,
    onImageReload: ((String) -> Unit)? = null,
    imageErrorMessageFor: ((String) -> String?)? = null,
    imageRetryKeyFor: ((String) -> Int)? = null,
    imageHasLoadedFor: ((String) -> Boolean)? = null,
    imageCachedHeightFor: ((String) -> Int?)? = null,
    imagePlaceholderAspectRatioFor: ((String) -> Float?)? = null,
    maxImageHeight: Dp? = null,
    onImageHeightChanged: ((String, Int) -> Unit)? = null,
    onImageAspectRatioChanged: ((String, Float) -> Unit)? = null,
) {
    val novelSettingsRepo = LocalNovelReaderSettingsRepository.current
    val fontRepository = LocalFontRepository.current
    val readerFontId = novelSettingsRepo.readerFontId.state()
    val readerFontFamily = remember(readerFontId) {
        fontRepository.getFontFamily(readerFontId) ?: HtmlDefaultFontFamily
    }
    val hasSelectableText = remember(blocks) {
        blocks.any { it is HtmlBlock.Text }
    }
    val content: @Composable () -> Unit = {
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(0.dp)) {
            blocks.forEach { block ->
                HtmlBlockRenderer(
                    block = block,
                    tid = tid,
                    linkContext = linkContext,
                    onImageSuccess = onImageSuccess,
                    onImageError = onImageError,
                    onImageReload = onImageReload,
                    imageErrorMessageFor = imageErrorMessageFor,
                    imageRetryKeyFor = imageRetryKeyFor,
                    imageHasLoadedFor = imageHasLoadedFor,
                    imageCachedHeightFor = imageCachedHeightFor,
                    imagePlaceholderAspectRatioFor = imagePlaceholderAspectRatioFor,
                    maxImageHeight = maxImageHeight,
                    onImageHeightChanged = onImageHeightChanged,
                    onImageAspectRatioChanged = onImageAspectRatioChanged,
                    fontFamily = readerFontFamily,
                )
            }
        }
    }
    if (hasSelectableText) {
        SelectionContainer {
            content()
        }
    } else {
        content()
    }
}

@Composable
private fun RubyTextBlock(
    text: AnnotatedString,
    rubies: List<HtmlBlock.RubyText>,
    textAlign: TextAlign,
    fontFamily: FontFamily,
    fontSizeSp: Float,
    lineHeightSp: Float,
    textColor: Color,
    modifier: Modifier = Modifier,
    onTextLayout: (TextLayoutResult) -> Unit,
) {
    val inlineLayout = remember(text, rubies) {
        buildRubyInlineLayout(text, rubies)
    }
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val rubyFontSizeSp = fontSizeSp * 0.72f
    val baseStyle = remember(fontFamily, fontSizeSp, textColor) {
        TextStyle(
            color = textColor,
            fontFamily = fontFamily,
            fontSize = fontSizeSp.sp,
            lineHeight = fontSizeSp.sp,
        )
    }
    val rubyStyle = remember(fontFamily, rubyFontSizeSp, textColor) {
        TextStyle(
            color = textColor,
            fontFamily = fontFamily,
            fontSize = rubyFontSizeSp.sp,
            lineHeight = rubyFontSizeSp.sp,
        )
    }

    BoxWithConstraints(modifier = modifier) {
        val maxWidthPx = with(density) { maxWidth.roundToPx() }
        val horizontalPaddingPx = with(density) { 4.dp.roundToPx() }
        val lineHeightPx = with(density) { lineHeightSp.sp.roundToPx() }
        val inlineContent = remember(
            inlineLayout.contents,
            textMeasurer,
            baseStyle,
            rubyStyle,
            lineHeightSp,
            maxWidthPx,
            horizontalPaddingPx,
            lineHeightPx,
        ) {
            inlineLayout.contents.associate { content ->
                val baseLayout = textMeasurer.measure(
                    text = content.baseText,
                    style = baseStyle,
                    softWrap = false,
                    maxLines = 1,
                )
                val rubyWidthPx = textMeasurer.measure(
                    text = content.rubyText,
                    style = rubyStyle,
                    softWrap = false,
                    maxLines = 1,
                ).size.width
                val widthPx = (maxOf(baseLayout.size.width, rubyWidthPx) + horizontalPaddingPx)
                    .coerceAtMost(maxWidthPx)
                val widthSp = with(density) { widthPx.toSp() }
                val baseTop = with(density) {
                    (lineHeightPx - baseLayout.firstBaseline).coerceAtLeast(0f).toDp()
                }

                content.id to InlineTextContent(
                    placeholder = Placeholder(
                        width = widthSp,
                        height = lineHeightSp.sp,
                        placeholderVerticalAlign = PlaceholderVerticalAlign.AboveBaseline,
                    ),
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        Text(
                            text = content.rubyText,
                            style = rubyStyle,
                            maxLines = 1,
                            overflow = TextOverflow.Clip,
                            modifier = Modifier.align(Alignment.TopCenter),
                        )
                        Text(
                            text = content.baseText,
                            style = baseStyle,
                            maxLines = 1,
                            overflow = TextOverflow.Clip,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .offset(y = baseTop),
                        )
                    }
                }
            }
        }

        Text(
            text = inlineLayout.text,
            style = TextStyle(
                color = textColor,
                fontFamily = fontFamily,
                fontSize = fontSizeSp.sp,
                lineHeight = lineHeightSp.sp,
                textAlign = textAlign,
            ),
            modifier = Modifier.fillMaxWidth(),
            inlineContent = inlineContent,
            onTextLayout = onTextLayout,
        )
    }
}

@Composable
private fun HtmlBlockRenderer(
    block: HtmlBlock,
    tid: ThreadId? = null,
    linkContext: InAppLinkContext = InAppLinkContext(currentTid = tid),
    onImageSuccess: ((String) -> Unit)? = null,
    onImageError: ((String, String) -> Unit)? = null,
    onImageReload: ((String) -> Unit)? = null,
    imageErrorMessageFor: ((String) -> String?)? = null,
    imageRetryKeyFor: ((String) -> Int)? = null,
    imageHasLoadedFor: ((String) -> Boolean)? = null,
    imageCachedHeightFor: ((String) -> Int?)? = null,
    imagePlaceholderAspectRatioFor: ((String) -> Float?)? = null,
    maxImageHeight: Dp? = null,
    onImageHeightChanged: ((String, Int) -> Unit)? = null,
    onImageAspectRatioChanged: ((String, Float) -> Unit)? = null,
    fontFamily: FontFamily = HtmlDefaultFontFamily,
) {
    DebugRecomposeProbe("HtmlBlockRenderer", "${block::class.simpleName}:${block.hashCode()}")
    val colors = YamiboTheme.colors
    val uriHandler = LocalUriHandler.current
    val navigator = LocalNavigator.current
    
    val novelSettingsRepo = LocalNovelReaderSettingsRepository.current
    val fontSize = novelSettingsRepo.fontSize.state()
    val lineSpacing = novelSettingsRepo.lineSpacing.state()
    @Suppress("DEPRECATION") val clipboardManager = LocalClipboardManager.current
    val isDarkTheme = (colors.creamBackground.red + colors.creamBackground.green + colors.creamBackground.blue) < 1.5f

    val adjustAnnotatedString: @Composable (AnnotatedString) -> AnnotatedString = { input ->
        remember(input, isDarkTheme) {
            if (!isDarkTheme) {
                input
            } else {
                val newBuilder = AnnotatedString.Builder(input.text)
                input.spanStyles.forEach { range ->
                    var style = range.item
                    if (style.color != Color.Unspecified) {
                        if (style.color.red <= 0.2f && style.color.green <= 0.2f && style.color.blue <= 0.2f && style.color.alpha > 0f) {
                            style = style.copy(color = Color.White.copy(alpha = 0.9f))
                        }
                    }
                    newBuilder.addStyle(style, range.start, range.end)
                }
                input.paragraphStyles.forEach { newBuilder.addStyle(it.item, it.start, it.end) }
                input.getStringAnnotations(0, input.length).forEach {
                    newBuilder.addStringAnnotation(it.tag, it.item, it.start, it.end) 
                }
                newBuilder.toAnnotatedString()
            }
        }
    }

    when (block) {
        is HtmlBlock.Text -> {
            var showLongPressMenu by remember { mutableStateOf<LinkMenuState?>(null) }
            var revealedSpoilerKeys by remember(block.anchorId, block.annotatedString.text) {
                mutableStateOf(emptySet<String>())
            }
            val layoutResult = remember { mutableStateOf<TextLayoutResult?>(null) }
            val baseAdjustedAnnotatedString = adjustAnnotatedString(block.annotatedString)
            val linkedAnnotatedString = remember(baseAdjustedAnnotatedString, colors.htmlTextDark) {
                applyThemedLinkStyle(baseAdjustedAnnotatedString, colors.htmlTextDark)
            }
            val adjustedAnnotatedString = remember(linkedAnnotatedString, revealedSpoilerKeys, colors.htmlTextDark) {
                applySpoilerAnnotations(
                    text = linkedAnnotatedString,
                    revealedKeys = revealedSpoilerKeys,
                    defaultTextColor = colors.htmlTextDark,
                )
            }
            val lineHeightSp = remember(adjustedAnnotatedString, fontSize, lineSpacing) {
                htmlTextLineHeightSp(
                    baseFontSizeSp = fontSize.toFloat(),
                    lineSpacing = lineSpacing,
                    text = adjustedAnnotatedString,
                    hasRuby = block.rubies.isNotEmpty(),
                )
            }
            val isBlankText = remember(adjustedAnnotatedString) {
                adjustedAnnotatedString.text.isHtmlBlankText()
            }
            val inlineContent = remember { emptyMap<String, InlineTextContent>() }
            val hasLinks = remember(adjustedAnnotatedString) {
                adjustedAnnotatedString.getStringAnnotations("URL", 0, adjustedAnnotatedString.length).isNotEmpty()
            }
            val hasSpoilers = remember(adjustedAnnotatedString) {
                adjustedAnnotatedString.getStringAnnotations(SPOILER_ANNOTATION_TAG, 0, adjustedAnnotatedString.length)
                    .isNotEmpty()
            }
            val textModifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 0.dp)
                .then(
                    if (hasLinks || hasSpoilers) {
                        Modifier.pointerInput(adjustedAnnotatedString, linkContext, hasLinks, hasSpoilers) {
                            awaitPointerEventScope {
                                while (true) {
                                    // 1. Initial Pass: Intercept 'down' on links to disable global selection
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val down = event.changes.firstOrNull { it.changedToDown() }

                                    if (down != null) {
                                        val layout = layoutResult.value
                                        if (layout != null) {
                                            val offset = layout.getOffsetForPosition(down.position)
                                            if (hasSpoilers) {
                                                val spoiler = adjustedAnnotatedString
                                                    .getStringAnnotations(SPOILER_ANNOTATION_TAG, offset, offset)
                                                    .firstOrNull()
                                                if (spoiler != null && spoiler.item !in revealedSpoilerKeys) {
                                                    revealedSpoilerKeys = revealedSpoilerKeys + spoiler.item
                                                }
                                            }

                                            val hasLink = hasLinks &&
                                                adjustedAnnotatedString.getStringAnnotations("URL", offset, offset)
                                                    .isNotEmpty()
                                            if (hasLink) {
                                                // Consume down in Initial pass -> Parents/Selection internal won't see it (prevents selection)
                                                down.consume()

                                                // 2. Manual detection for this specific link tap/long press
                                                val longPressTimeout = viewConfiguration.longPressTimeoutMillis

                                                // Manual wait for up or timeout
                                                val upOrNull = withTimeoutOrNull(longPressTimeout) {
                                                    waitForUpOrCancellation()
                                                }

                                                if (upOrNull == null) {
                                                    val link =
                                                        adjustedAnnotatedString.getStringAnnotations("URL", offset, offset)
                                                            .firstOrNull()
                                                    if (link != null) {
                                                        val fullUrl = normalizeYamiboUrl(link.item)
                                                        val linkText = adjustedAnnotatedString.substring(link.start, link.end)
                                                        showLongPressMenu = LinkMenuState(
                                                            url = fullUrl,
                                                            linkText = linkText,
                                                            canOpenInApp = looksLikeSupportedYamiboInAppLink(fullUrl),
                                                        )
                                                        // Consume all movement until release
                                                        while (true) {
                                                            val moveEvent = awaitPointerEvent()
                                                            moveEvent.changes.forEach { it.consume() }
                                                            if (moveEvent.changes.none { it.pressed }) break
                                                        }
                                                    }
                                                } else {
                                                    val link =
                                                        adjustedAnnotatedString.getStringAnnotations("URL", offset, offset)
                                                            .firstOrNull()
                                                    if (link != null) {
                                                        val fullUrl = normalizeYamiboUrl(link.item)
                                                        if (looksLikeSupportedYamiboInAppLink(fullUrl)) {
                                                            navigator.navigate(IInAppLinkResolvingScreen(fullUrl, linkContext))
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        Modifier
                    }
                )

            if (isBlankText) {
                val density = LocalDensity.current
                Spacer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(with(density) { lineHeightSp.sp.toDp() })
                )
            } else if (block.rubies.isNotEmpty()) {
                RubyTextBlock(
                    text = adjustedAnnotatedString,
                    rubies = block.rubies,
                    textAlign = block.textAlign,
                    fontFamily = fontFamily,
                    fontSizeSp = fontSize.toFloat(),
                    lineHeightSp = lineHeightSp,
                    textColor = colors.htmlTextDark,
                    modifier = textModifier,
                    onTextLayout = { layoutResult.value = it },
                )
            } else {
                Text(
                    text = adjustedAnnotatedString,
                    style = TextStyle(
                        color = colors.htmlTextDark,
                        fontFamily = fontFamily,
                        fontSize = fontSize.sp,
                        lineHeight = lineHeightSp.sp,
                        textAlign = block.textAlign
                    ),
                    modifier = textModifier,
                    inlineContent = inlineContent,
                    onTextLayout = { layoutResult.value = it }
                )
            }

            if (!isBlankText && showLongPressMenu != null) {
                DisableSelection {
                    val menu = showLongPressMenu!!
                    val fullUrl = menu.url

                    AlertDialog(
                        onDismissRequest = { showLongPressMenu = null },
                        title = { Text(i18n("連結選項"), style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp)) },
                        text = {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                                    Text(
                                        text = menu.linkText,
                                        color = colors.htmlTextDark,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = fullUrl,
                                        color = colors.htmlTextDark.copy(alpha = 0.6f),
                                        fontSize = 12.sp,
                                        lineHeight = 16.sp,
                                    )
                                }

                                HorizontalDivider(
                                    modifier = Modifier.padding(bottom = 8.dp),
                                    color = colors.brownPrimary.copy(alpha = 0.1f),
                                )

                                if (menu.canOpenInApp) {
                                    TextButton(onClick = {
                                        navigator.navigate(IInAppLinkResolvingScreen(fullUrl, linkContext))
                                        showLongPressMenu = null
                                    }) { Text(i18n("App 內打開"), color = colors.brownPrimary, fontSize = 16.sp) }
                                }

                                TextButton(onClick = {
                                    navigator.navigate(IPlatformWebView(fullUrl))
                                    showLongPressMenu = null
                                }) { Text(i18n("WebView 打開"), color = colors.brownPrimary, fontSize = 16.sp) }

                                TextButton(onClick = {
                                    clipboardManager.setText(AnnotatedString(fullUrl))
                                    showLongPressMenu = null
                                }) { Text(i18n("複製連結"), color = colors.brownPrimary, fontSize = 16.sp) }

                                TextButton(onClick = {
                                    clipboardManager.setText(AnnotatedString(menu.linkText))
                                    showLongPressMenu = null
                                }) { Text(i18n("複製文字"), color = colors.brownPrimary, fontSize = 16.sp) }

                                TextButton(onClick = {
                                    try {
                                        uriHandler.openUri(fullUrl)
                                    } catch (error: Exception) {
                                        Logger.w("HtmlRenderer", "Failed to open link in external browser url=$fullUrl", error)
                                    }
                                    showLongPressMenu = null
                                }) { Text(i18n("外部瀏覽器"), color = colors.brownPrimary, fontSize = 16.sp) }
                            }
                        },
                        confirmButton = {},
                        dismissButton = {
                            TextButton(onClick = { showLongPressMenu = null }) {
                                Text(i18n("取消"), color = colors.textDark.copy(alpha = 0.5f))
                            }
                        },
                        containerColor = colors.creamSurface,
                        titleContentColor = colors.textDark,
                    )
                }
            }
        }

        is HtmlBlock.Image -> {
            val url = normalizeImageUrl(block.url)
            if (block.isEmoticon) {
                AsyncImage(
                    model = rememberImageRequest(url, enableCrossfade = false),
                    contentDescription = block.alt,
                    modifier = Modifier
                        .padding(vertical = 2.dp)
                        .size(40.dp),
                    contentScale = ContentScale.Fit,
                )
            } else {
                ImageViewer(
                    url = url,
                    contentDescription = block.alt,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 1.dp),
                    contentScale = ContentScale.FillWidth,
                    enableContextMenu = true,
                    isDarkTheme = isDarkTheme,
                    enableCrossfade = false,
                    onSuccess = onImageSuccess,
                    onError = onImageError,
                    blockedErrorMessage = imageErrorMessageFor?.invoke(url),
                    externalRetryKey = imageRetryKeyFor?.invoke(url) ?: 0,
                    onReload = { onImageReload?.invoke(url) },
                    cachedHeightPx = imageCachedHeightFor?.invoke(url),
                    placeholderAspectRatio = imagePlaceholderAspectRatioFor?.invoke(url),
                    maxRenderedHeight = maxImageHeight,
                    suppressLoadingPlaceholderWhenCached = imageHasLoadedFor?.invoke(url) == true,
                    onRenderedHeightChanged = { heightPx -> onImageHeightChanged?.invoke(url, heightPx) },
                    onRenderedAspectRatioChanged = { ratio -> onImageAspectRatioChanged?.invoke(url, ratio) },
                )
            }
        }

        is HtmlBlock.Attachment -> {
            val fullUrl = if (block.url.startsWith("http")) block.url else "https://bbs.yamibo.com/${block.url.removePrefix("/")}"
            val iconUrl = block.iconUrl?.let {
                if (it.startsWith("http")) it else "https://bbs.yamibo.com/${it.removePrefix("/")}"
            }
            var showAttachmentMenu by remember { mutableStateOf(false) }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .clickable {
                        showAttachmentMenu = true
                    },
                shape = RoundedCornerShape(10.dp),
                color = colors.creamSurface,
                border = BorderStroke(1.dp, colors.brownPrimary.copy(alpha = 0.12f)),
                tonalElevation = 1.dp,
                shadowElevation = 0.dp,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(colors.creamBackground),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (iconUrl != null) {
                            AsyncImage(
                                model = rememberImageRequest(iconUrl, enableCrossfade = false),
                                contentDescription = block.fileName,
                                modifier = Modifier.size(30.dp),
                                contentScale = ContentScale.Fit,
                            )
                        } else {
                            Text(
                                text = "?"
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = block.fileName,
                            color = colors.textStrong,
                            fontSize = 14.sp,
                            lineHeight = 18.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )

                        block.uploadInfo?.let {
                            Text(
                                text = it,
                                color = colors.htmlTextDark.copy(alpha = 0.72f),
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        block.statInfo?.let {
                            Text(
                                text = it,
                                color = colors.htmlTextDark.copy(alpha = 0.72f),
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            if (showAttachmentMenu) {
                DisableSelection {
                    AlertDialog(
                        onDismissRequest = { showAttachmentMenu = false },
                        title = { Text(i18n("附件選項"), style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp)) },
                        text = {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                                    Text(
                                        text = block.fileName,
                                        color = colors.htmlTextDark,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = fullUrl,
                                        color = colors.htmlTextDark.copy(alpha = 0.6f),
                                        fontSize = 12.sp,
                                        lineHeight = 16.sp
                                    )
                                    block.uploadInfo?.let {
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = it,
                                            color = colors.htmlTextDark.copy(alpha = 0.72f),
                                            fontSize = 12.sp
                                        )
                                    }
                                    block.statInfo?.let {
                                        Text(
                                            text = it,
                                            color = colors.htmlTextDark.copy(alpha = 0.72f),
                                            fontSize = 12.sp
                                        )
                                    }
                                }

                                HorizontalDivider(
                                    modifier = Modifier.padding(bottom = 8.dp),
                                    color = colors.brownPrimary.copy(alpha = 0.1f)
                                )

                                TextButton(onClick = {
                                    navigator.navigate(IPlatformWebView(fullUrl))
                                    showAttachmentMenu = false
                                }) { Text(i18n("在應用內開啟連結"), color = colors.brownPrimary, fontSize = 16.sp) }

                                TextButton(onClick = {
                                    clipboardManager.setText(AnnotatedString(fullUrl))
                                    showAttachmentMenu = false
                                }) { Text(i18n("複製連結地址"), color = colors.brownPrimary, fontSize = 16.sp) }

                                TextButton(onClick = {
                                    clipboardManager.setText(AnnotatedString(block.fileName))
                                    showAttachmentMenu = false
                                }) { Text(i18n("複製連結文字"), color = colors.brownPrimary, fontSize = 16.sp) }

                                TextButton(onClick = {
                                    try {
                                        uriHandler.openUri(fullUrl)
                                    } catch (error: Exception) {
                                        Logger.w("HtmlRenderer", "Failed to open attachment in external browser url=$fullUrl", error)
                                    }
                                    showAttachmentMenu = false
                                }) { Text(i18n("使用外部瀏覽器開啟"), color = colors.brownPrimary, fontSize = 16.sp) }
                            }
                        },
                        confirmButton = {},
                        dismissButton = {
                            TextButton(onClick = { showAttachmentMenu = false }) {
                                Text(i18n("取消"), color = colors.textDark.copy(alpha = 0.5f))
                            }
                        },
                        containerColor = colors.creamSurface,
                        titleContentColor = colors.textDark
                    )
                }
            }
        }

        is HtmlBlock.Hr -> {
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                thickness = 1.dp,
                color = colors.brownPrimary.copy(alpha = 0.3f)
            )
        }

        is HtmlBlock.Collapse -> {
            var expanded by remember { mutableStateOf(false) }
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                shape = RoundedCornerShape(4.dp),
                border = BorderStroke(1.dp, colors.brownPrimary.copy(alpha = 0.2f)),
                colors = CardDefaults.cardColors(containerColor = colors.creamSurface),
                onClick = { expanded = !expanded }
            ) {
                Column(modifier = Modifier.padding(12.dp).fillMaxWidth()) {
                    Text(
                        text = if (expanded) "▼ ${block.title ?: i18n("點擊展開 / 收起")}" else "▶ ${block.title ?: i18n("點擊展開 / 收起")}",
                        color = colors.textStrong,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                    AnimatedVisibility(visible = expanded) {
                        Column(modifier = Modifier.padding(top = 12.dp)) {
                            HorizontalDivider(
                                Modifier.padding(bottom = 12.dp),
                                color = colors.brownPrimary.copy(alpha = 0.1f)
                            )
                            block.contentBlocks.forEach {
                                HtmlBlockRenderer(
                                    block = it,
                                    tid = tid,
                                    linkContext = linkContext,
                                    onImageSuccess = onImageSuccess,
                                    onImageError = onImageError,
                                    onImageReload = onImageReload,
                                    imageErrorMessageFor = imageErrorMessageFor,
                                    imageRetryKeyFor = imageRetryKeyFor,
                                    imageHasLoadedFor = imageHasLoadedFor,
                                    imageCachedHeightFor = imageCachedHeightFor,
                                    imagePlaceholderAspectRatioFor = imagePlaceholderAspectRatioFor,
                                    maxImageHeight = maxImageHeight,
                                    onImageHeightChanged = onImageHeightChanged,
                                    onImageAspectRatioChanged = onImageAspectRatioChanged,
                                    fontFamily = fontFamily,
                                )
                            }
                        }
                    }
                }
            }
        }

        is HtmlBlock.Locked -> {
            val dashPattern = remember { PathEffect.dashPathEffect(floatArrayOf(15f, 10f), 0f) }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .drawBehind {
                        drawRoundRect(
                            color = Color(0xFFD32F2F),
                            style = Stroke(width = 4f, pathEffect = dashPattern),
                            cornerRadius = CornerRadius(24f, 24f)
                        )
                    }
                    .padding(16.dp)
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "🔒",
                            fontSize = 18.sp,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(
                            text = i18n("本帖隱藏內容需要積分: {}", block.cost.toString()),
                            color = Color(0xFFD32F2F),
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    block.contentBlocks.forEach {
                        HtmlBlockRenderer(
                            block = it,
                            tid = tid,
                            linkContext = linkContext,
                            onImageSuccess = onImageSuccess,
                            onImageError = onImageError,
                            onImageReload = onImageReload,
                            imageErrorMessageFor = imageErrorMessageFor,
                            imageRetryKeyFor = imageRetryKeyFor,
                            imageHasLoadedFor = imageHasLoadedFor,
                            imageCachedHeightFor = imageCachedHeightFor,
                            imagePlaceholderAspectRatioFor = imagePlaceholderAspectRatioFor,
                            maxImageHeight = maxImageHeight,
                            onImageHeightChanged = onImageHeightChanged,
                            onImageAspectRatioChanged = onImageAspectRatioChanged,
                            fontFamily = fontFamily,
                        )
                    }
                }
            }
        }

        is HtmlBlock.Quote -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.creamSurface)
            ) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .fillMaxHeight()
                        .background(colors.brownPrimary)
                )
                Column(modifier = Modifier.padding(12.dp)) {
                    block.contentBlocks.forEach {
                        HtmlBlockRenderer(
                            block = it,
                            tid = tid,
                            linkContext = linkContext,
                            onImageSuccess = onImageSuccess,
                            onImageError = onImageError,
                            onImageReload = onImageReload,
                            imageErrorMessageFor = imageErrorMessageFor,
                            imageRetryKeyFor = imageRetryKeyFor,
                            imageHasLoadedFor = imageHasLoadedFor,
                            imageCachedHeightFor = imageCachedHeightFor,
                            imagePlaceholderAspectRatioFor = imagePlaceholderAspectRatioFor,
                            maxImageHeight = maxImageHeight,
                            onImageHeightChanged = onImageHeightChanged,
                            onImageAspectRatioChanged = onImageAspectRatioChanged,
                            fontFamily = fontFamily,
                        )
                    }
                }
            }
        }

        is HtmlBlock.Code -> {
            Surface(
                color = Color(0xFF2B2B2B), // very dark gray for code
                contentColor = Color(0xFFA9B7C6), // standard light text
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
            ) {
                Text(
                    text = block.codeText,
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(12.dp),
                    style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                )
            }
        }

        is HtmlBlock.Table -> {
            val rows = block.rows
            if (rows.isEmpty()) return

            val maxCols = rows.maxOf { it.cells.size }

            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, colors.brownPrimary.copy(alpha = 0.2f)),
                colors = CardDefaults.cardColors(containerColor = colors.creamSurface),
            ) {
                val scrollState = rememberScrollState()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(scrollState)
                ) {
                    Column {
                        rows.forEachIndexed { rowIdx, row ->
                            val isFirstRow = rowIdx == 0
                            val isHeaderRow = isFirstRow && row.cells.any { it.isHeader }

                            Row(modifier = Modifier.fillMaxWidth()) {
                                for (colIdx in 0 until maxCols) {
                                    val cell = row.cells.getOrNull(colIdx)
                                    val cellBg = when {
                                        isHeaderRow -> colors.brownDeep
                                        rowIdx % 2 == 0 -> colors.creamSurface
                                        else -> colors.creamBackground
                                    }
                                    val cellTextColor = if (isHeaderRow) Color.White else colors.htmlTextDark

                                    Box(
                                        modifier = Modifier
                                            .widthIn(min = 80.dp, max = 240.dp)
                                            .background(cellBg)
                                            .padding(horizontal = 10.dp, vertical = 8.dp)
                                    ) {
                                        if (cell != null && cell.blocks.isNotEmpty()) {
                                            Column {
                                                cell.blocks.forEach { innerBlock ->
                                                    when (innerBlock) {
                                                        is HtmlBlock.Text -> {
                                                            val tableFontSize = (fontSize - 3).coerceAtLeast(10)
                                                            val adjustedTableText = adjustAnnotatedString(innerBlock.annotatedString)
                                                            val tableLineHeightSp = htmlTextLineHeightSp(
                                                                baseFontSizeSp = tableFontSize.toFloat(),
                                                                lineSpacing = lineSpacing,
                                                                text = adjustedTableText,
                                                            )
                                                            Text(
                                                                text = adjustedTableText,
                                                                style = TextStyle(
                                                                    color = cellTextColor,
                                                                    fontFamily = fontFamily,
                                                                    fontSize = tableFontSize.sp,
                                                                    lineHeight = tableLineHeightSp.sp,
                                                                    fontWeight = if (cell.isHeader) FontWeight.Bold else FontWeight.Normal,
                                                                    textAlign = innerBlock.textAlign
                                                                )
                                                            )
                                                        }
                                                        else -> HtmlBlockRenderer(
                                                            block = innerBlock,
                                                            tid = tid,
                                                            linkContext = linkContext,
                                                            onImageSuccess = onImageSuccess,
                                                            onImageError = onImageError,
                                                            onImageReload = onImageReload,
                                                            imageErrorMessageFor = imageErrorMessageFor,
                                                            imageRetryKeyFor = imageRetryKeyFor,
                                                            imageHasLoadedFor = imageHasLoadedFor,
                                                            imageCachedHeightFor = imageCachedHeightFor,
                                                            imagePlaceholderAspectRatioFor = imagePlaceholderAspectRatioFor,
                                                            maxImageHeight = maxImageHeight,
                                                            onImageHeightChanged = onImageHeightChanged,
                                                            onImageAspectRatioChanged = onImageAspectRatioChanged,
                                                            fontFamily = fontFamily,
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    // Column divider
                                    if (colIdx < maxCols - 1) {
                                        Box(
                                            modifier = Modifier
                                                .width(1.dp)
                                                .heightIn(min = 36.dp)
                                                .background(colors.brownPrimary.copy(alpha = 0.15f))
                                        )
                                    }
                                }
                            }

                            // Row divider
                            if (rowIdx < rows.size - 1) {
                                HorizontalDivider(color = colors.brownPrimary.copy(alpha = 0.15f))
                            }
                        }
                    }
                }
            }
        }
    }
}

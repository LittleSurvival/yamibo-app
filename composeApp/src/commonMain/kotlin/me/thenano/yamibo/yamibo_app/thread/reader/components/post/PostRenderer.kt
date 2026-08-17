package me.thenano.yamibo.yamibo_app.thread.reader.components.post

import me.thenano.yamibo.yamibo_app.i18n.i18n

import YamiboIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil3.compose.AsyncImage
import io.github.littlesurvival.core.YamiboResult
import io.github.littlesurvival.dto.page.Post
import io.github.littlesurvival.dto.page.RatePopoutPage
import io.github.littlesurvival.dto.page.RateResultPopoutPage
import io.github.littlesurvival.dto.page.ManageButton
import io.github.littlesurvival.dto.page.VotersPopoutScreen
import io.github.littlesurvival.dto.value.PollOptionId
import me.thenano.yamibo.yamibo_app.components.text.rememberConvertedText
import me.thenano.yamibo.yamibo_app.LocalNovelReaderSettingsRepository
import me.thenano.yamibo.yamibo_app.navigation.LocalNavigator
import me.thenano.yamibo.yamibo_app.repository.inapplinknavigation.InAppLinkContext
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme
import me.thenano.yamibo.yamibo_app.thread.reader.debug.DebugRecomposeProbe
import me.thenano.yamibo.yamibo_app.thread.reader.debug.debugPerfLog
import me.thenano.yamibo.yamibo_app.thread.reader.components.post.impl.*
import me.thenano.yamibo.yamibo_app.util.rememberImageRequest
import me.thenano.yamibo.yamibo_app.util.state
import me.thenano.yamibo.yamibo_app.userspace.IUserSpaceScreen
import me.thenano.yamibo.yamibo_app.webview.action.IActionWebView

enum class PostFooterSection {
    Metadata,
    Navigation,
    Poll,
    Rates,
    Comments,
    Attachments,
    Actions;

    companion object {
        val All: Set<PostFooterSection> = entries.toSet()
    }
}

data class PostFooterRenderOptions(
    val rateRange: IntRange? = null,
    val commentRange: IntRange? = null,
)

@Composable
fun PostRenderer(
    post: Post,
    modifier: Modifier = Modifier,
    threadTitle: String? = null,
    onVote: (suspend (List<PollOptionId>) -> Boolean)? = null,
    onLoadVoters: (suspend (PollOptionId?, Int) -> YamiboResult<VotersPopoutScreen>)? = null,
    onLoadRateOptions: (suspend () -> YamiboResult<RatePopoutPage>)? = null,
    onLoadRateResults: (suspend () -> YamiboResult<RateResultPopoutPage>)? = null,
    onRate: ((Int, String, Boolean) -> Unit)? = null,
    onComment: ((String) -> Unit)? = null,
    onReply: (() -> Unit)? = null,
    cachedHeightPx: Int? = null,
    onHeightChanged: ((Int) -> Unit)? = null,
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
    bodyBlocks: List<HtmlBlock>? = null,
    showHeader: Boolean = true,
    showFooter: Boolean = true,
    footerSections: Set<PostFooterSection> = PostFooterSection.All,
    footerRenderOptions: PostFooterRenderOptions = PostFooterRenderOptions(),
    verticalPadding: Dp = 8.dp,
    totalViews: Int? = null,
    totalReplies: Int? = null,
    linkContext: InAppLinkContext = InAppLinkContext(),
) {
    DebugRecomposeProbe("PostRenderer") { "${post.pid.value}#${post.floor}" }

    var showRateDialog by remember { mutableStateOf(false) }
    var showRateResultsDialog by remember { mutableStateOf(false) }
    var showCommentDialog by remember { mutableStateOf(false) }
    var showManageDialog by remember { mutableStateOf(false) }
    val navigator = LocalNavigator.current

    fun openManageAction(button: ManageButton) {
        val url = if (button.url.startsWith("http://") || button.url.startsWith("https://")) {
            button.url
        } else {
            "https://bbs.yamibo.com/${button.url.removePrefix("/")}"
        }
        navigator.navigate(
            IActionWebView(
                title = button.name,
                initialUrl = url,
                successCondition = { target -> target.contains("mod=viewthread") && target.contains("tid=") },
            )
        )
    }
    val novelSettingsRepo = LocalNovelReaderSettingsRepository.current
    val contentWidthFraction = novelSettingsRepo.contentWidthFraction.state()
    val density = LocalDensity.current
    val cachedMinHeight = remember(cachedHeightPx, density) {
        cachedHeightPx?.let { with(density) { it.toDp() } } ?: 0.dp
    }

    val heightTrackingModifier = if (cachedHeightPx != null || onHeightChanged != null) {
        Modifier
            .heightIn(min = cachedMinHeight)
            .onSizeChanged { size ->
                if (size.height > 0) {
                    debugPerfLog("post_height|pid=${post.pid.value}|floor=${post.floor}|height=${size.height}")
                    onHeightChanged?.invoke(size.height)
                }
            }
    } else {
        Modifier
    }
    val convertedThreadTitle = if (threadTitle != null) rememberConvertedText(threadTitle) else null
    if (!showHeader && !showFooter && !bodyBlocks.isNullOrEmpty()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .then(heightTrackingModifier),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(contentWidthFraction)
                    .padding(horizontal = 16.dp, vertical = verticalPadding)
            ) {
                HtmlBlocksRenderer(
                    blocks = bodyBlocks,
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
                )
            }
        }
        return
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(heightTrackingModifier),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(modifier = Modifier.fillMaxWidth(contentWidthFraction).padding(horizontal = 16.dp, vertical = verticalPadding)) {
            if (showHeader) {
                // Title
                if (post.floor == 1 && !convertedThreadTitle.isNullOrEmpty()) {
                    SelectionContainer {
                        Text(
                            text = convertedThreadTitle,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = YamiboTheme.colors.textDark,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                    }
                    if (totalViews != null || totalReplies != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            totalViews?.let {
                                ThreadReaderStatBadge(icon = YamiboIcons.Views, value = it.toString())
                            }
                            if (totalViews != null && totalReplies != null) Spacer(Modifier.width(10.dp))
                            totalReplies?.let {
                                ThreadReaderStatBadge(icon = YamiboIcons.Comment, value = it.toString())
                            }
                        }
                    }
                }

                // Author Header
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val avatarUrl = post.author.avatarUrl
                    Box(
                        modifier = Modifier.clickable {
                            navigator.navigate(IUserSpaceScreen(post.author.uid, post.author.name))
                        }
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = YamiboIcons.PersonFill,
                                contentDescription = null,
                                modifier = Modifier.size(36.dp),
                                tint = YamiboTheme.colors.textDark.copy(alpha = 0.5f)
                            )
                            if (!avatarUrl.isNullOrEmpty()) {
                                AsyncImage(
                                    model = rememberImageRequest(url = avatarUrl, enableCrossfade = false),
                                    contentDescription = "Avatar",
                                    modifier = Modifier.matchParentSize(),
                                    contentScale = ContentScale.Crop,
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            post.author.name,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = YamiboTheme.colors.brownPrimary,
                            modifier = Modifier.clickable {
                                navigator.navigate(IUserSpaceScreen(post.author.uid, post.author.name))
                            }
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(post.timeCreate.text, fontSize = 12.sp, color = YamiboTheme.colors.textDark.copy(alpha = 0.5f))
                    }

                    if (post.manageButtons.isNotEmpty()) {
                        val singleAction = post.manageButtons.singleOrNull()
                        TextButton(
                            onClick = {
                                if (singleAction != null) openManageAction(singleAction) else showManageDialog = true
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        ) {
                            Text(
                                text = manageButtonLabel(post.manageButtons)?.let { i18n(it) }.orEmpty(),
                                color = YamiboTheme.colors.redAccent,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }

                    if (post.isPinned) {
                        Icon(
                            imageVector = YamiboIcons.Pin,
                            contentDescription = i18n("置頂帖"),
                            modifier = Modifier.size(15.dp),
                            tint = YamiboTheme.colors.textDark.copy(alpha = 0.55f),
                        )
                        Spacer(Modifier.width(3.dp))
                    }
                    Text("${post.floor}#", fontSize = 12.sp, color = YamiboTheme.colors.textDark.copy(alpha = 0.5f))
                }
            }

            if (bodyBlocks != null) {
                HtmlBlocksRenderer(
                    blocks = bodyBlocks,
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
                )
            } else {
                HtmlRenderer(
                    html = post.contentHtml,
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
                )
            }

            if (showFooter) {
                // Edited Text
                val lastEditedTime = post.lastEditedTime
                if (PostFooterSection.Metadata in footerSections && lastEditedTime != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = lastEditedTime.specialText ?: i18n("最後編輯於 {}", lastEditedTime.text),
                        fontSize = 12.sp,
                        color = YamiboTheme.colors.textDark.copy(alpha = 0.5f)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Poll
                if (PostFooterSection.Poll in footerSections) post.poll?.let { poll ->
                    PollRenderer(poll, onVote = onVote, onLoadVoters = onLoadVoters)
                }

                // Rates
                if (PostFooterSection.Rates in footerSections && post.rateBlock.rates.isNotEmpty()) {
                    RateRenderer(
                        rateBlock = post.rateBlock,
                        rateRange = footerRenderOptions.rateRange,
                        onShowAllRatings = onLoadRateResults?.let { { showRateResultsDialog = true } },
                    )
                }

                // Comments
                if (PostFooterSection.Comments in footerSections && post.comments.isNotEmpty()) {
                    val visibleComments = footerRenderOptions.commentRange
                        ?.let { range -> post.comments.filterIndexed { index, _ -> index in range } }
                        ?: post.comments
                    CommentRenderer(visibleComments)
                }

                // Attachments
                if (PostFooterSection.Attachments in footerSections && post.attachments.isNotEmpty()) {
                    AttachmentRenderer(post.attachments)
                }

                // Action Buttons Row (Bottom)
                if (PostFooterSection.Actions in footerSections && (onRate != null || onComment != null || onReply != null)) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), thickness = 0.5.dp, color = YamiboTheme.colors.brownPrimary.copy(alpha = 0.15f))
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (onRate != null) {
                            TextButton(onClick = { showRateDialog = true }) {
                                Icon(imageVector = YamiboIcons.Heart, contentDescription = i18n("評分"), modifier = Modifier.size(18.dp), tint = YamiboTheme.colors.brownPrimary)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(i18n("評分"), fontSize = 13.sp, color = YamiboTheme.colors.brownPrimary, fontWeight = FontWeight.SemiBold)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        if (onComment != null) {
                            TextButton(onClick = { showCommentDialog = true }) {
                                Icon(imageVector = YamiboIcons.Comment, contentDescription = i18n("點評"), modifier = Modifier.size(18.dp), tint = YamiboTheme.colors.brownPrimary)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(i18n("點評"), fontSize = 13.sp, color = YamiboTheme.colors.brownPrimary, fontWeight = FontWeight.SemiBold)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        if (onReply != null) {
                            TextButton(onClick = { onReply() }) {
                                Icon(imageVector = YamiboIcons.Reply, contentDescription = i18n("回復"), modifier = Modifier.size(18.dp), tint = YamiboTheme.colors.brownPrimary)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(i18n("回復"), fontSize = 13.sp, color = YamiboTheme.colors.brownPrimary, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showRateResultsDialog && onLoadRateResults != null) {
        RateResultsDialog(
            onLoad = onLoadRateResults,
            onDismiss = { showRateResultsDialog = false },
        )
    }

    if (showRateDialog) {
        var scoreInput by remember { mutableStateOf("") }
        var reasonInput by remember { mutableStateOf("") }
        var noticeAuthor by remember { mutableStateOf(false) }
        var rateOptions by remember { mutableStateOf<RatePopoutPage?>(null) }
        var rateHint by remember { mutableStateOf<String?>(null) }
        val loadingHint = i18n("正在載入選項")
        val failedHint = i18n("載入失敗, 可選擇直接填寫")

        LaunchedEffect(onLoadRateOptions) {
            if (onLoadRateOptions == null) return@LaunchedEffect
            rateHint = loadingHint
            when (val result = onLoadRateOptions()) {
                is YamiboResult.Success -> {
                    rateOptions = result.value
                    rateHint = null
                }

                else -> {
                    rateOptions = null
                    rateHint = failedHint
                }
            }
        }

        AlertDialog(
            onDismissRequest = {
                showRateDialog = false
            },
            title = { Text(i18n("本帖評分"), fontWeight = FontWeight.Bold, fontSize = 20.sp) },
            text = {
                Column {
                    RateOptionTextField(
                        value = scoreInput,
                        onValueChange = { scoreInput = it },
                        label = { Text(i18n("分數"), fontSize = 12.sp) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        pickerTitle = i18n("選擇分數"),
                        options = rateOptions?.availableScores.orEmpty().distinct().sortedDescending().map { it.toString() },
                        compactGrid = true,
                    )
                    RateOptionTextField(
                        value = reasonInput,
                        onValueChange = { reasonInput = it },
                        label = { Text(i18n("評分理由"), fontSize = 12.sp) },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        pickerTitle = i18n("選擇評分理由"),
                        options = rateOptions?.defaultReasons.orEmpty(),
                    )
                    if (rateHint != null) {
                        Text(
                            text = rateHint.orEmpty(),
                            color = YamiboTheme.colors.textDark.copy(alpha = 0.55f),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { noticeAuthor = !noticeAuthor }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = i18n("通知作者"),
                            color = YamiboTheme.colors.textDark,
                            fontSize = 14.sp,
                            modifier = Modifier.weight(1f)
                        )
                        Checkbox(
                            checked = noticeAuthor,
                            onCheckedChange = { noticeAuthor = it },
                            colors = CheckboxDefaults.colors(
                                checkedColor = YamiboTheme.colors.brownPrimary,
                                uncheckedColor = YamiboTheme.colors.textDark.copy(alpha = 0.5f),
                                checkmarkColor = YamiboTheme.colors.creamBackground,
                            )
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val score = scoreInput.toIntOrNull() ?: 0
                        onRate?.invoke(score, reasonInput, noticeAuthor)
                        showRateDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = YamiboTheme.colors.brownPrimary)
                ) { Text(i18n("提交"), color = YamiboTheme.colors.creamBackground) }
            },
            dismissButton = {
                TextButton(onClick = { showRateDialog = false }) { Text(i18n("取消"), color = YamiboTheme.colors.brownPrimary) }
            },
            containerColor = YamiboTheme.colors.creamSurface,
            titleContentColor = YamiboTheme.colors.brownPrimary,
            textContentColor = YamiboTheme.colors.textDark
        )
    }

    if (showCommentDialog) {
        var commentInput by remember { mutableStateOf("") }
        Dialog(onDismissRequest = { showCommentDialog = false }) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = YamiboTheme.colors.creamSurface
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = i18n("點評"),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = YamiboTheme.colors.brownPrimary,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                            .background(
                                YamiboTheme.colors.creamBackground.copy(alpha = 0.5f),
                                RoundedCornerShape(8.dp)
                            )
                            .padding(8.dp)
                    ) {
                        BasicTextField(
                            value = commentInput,
                            onValueChange = { commentInput = it },
                            modifier = Modifier.fillMaxSize(),
                            textStyle = TextStyle(
                                color = YamiboTheme.colors.textDark,
                                fontSize = 16.sp
                            ),
                            cursorBrush = SolidColor(YamiboTheme.colors.brownPrimary)
                        )
                        if (commentInput.isEmpty()) {
                            Text(
                                i18n("輸入內容..."),
                                color = YamiboTheme.colors.textDark.copy(alpha = 0.4f),
                                fontSize = 16.sp,
                                modifier = Modifier.align(Alignment.TopStart)
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    Button(
                        onClick = {
                            onComment?.invoke(commentInput)
                            showCommentDialog = false
                        },
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = YamiboTheme.colors.brownDeep,
                            disabledContainerColor = YamiboTheme.colors.brownDeep.copy(alpha = 0.5f)
                        ),
                        enabled = commentInput.isNotBlank()
                    ) {
                        Text(i18n("發布"), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    if (showManageDialog) {
        ManageActionsDialog(
            actions = post.manageButtons,
            onSelect = { action ->
                showManageDialog = false
                openManageAction(action)
            },
            onDismiss = { showManageDialog = false },
        )
    }
}

internal fun manageButtonLabel(actions: List<ManageButton>): String? = when (actions.size) {
    0 -> null
    1 -> actions.single().name
    else -> "管理"
}

@Composable
private fun ManageActionsDialog(
    actions: List<ManageButton>,
    onSelect: (ManageButton) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = YamiboTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(i18n("管理"), color = colors.textStrong, fontWeight = FontWeight.Bold) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(actions) { action ->
                    Surface(
                        onClick = { onSelect(action) },
                        shape = RoundedCornerShape(12.dp),
                        color = colors.creamBackground,
                    ) {
                        Text(
                            text = action.name,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                            color = colors.brownPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(i18n("關閉"), color = colors.brownPrimary) }
        },
        containerColor = colors.creamSurface,
    )
}

@Composable
private fun RateOptionTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: @Composable (() -> Unit),
    pickerTitle: String,
    options: List<String>,
    modifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    compactGrid: Boolean = false,
) {
    var showPicker by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = label,
            keyboardOptions = keyboardOptions,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            trailingIcon = {
                if (options.isNotEmpty()) {
                    TextButton(
                        onClick = { showPicker = true },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.textButtonColors(contentColor = YamiboTheme.colors.brownPrimary),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text(
                            i18n("選擇"),
                            color = YamiboTheme.colors.brownPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = YamiboTheme.colors.brownPrimary,
                unfocusedBorderColor = YamiboTheme.colors.brownPrimary.copy(alpha = 0.35f),
                focusedLabelColor = YamiboTheme.colors.brownPrimary,
                cursorColor = YamiboTheme.colors.brownPrimary,
            )
        )
    }

    if (showPicker) {
        RateOptionPickerDialog(
            title = pickerTitle,
            options = options,
            selected = value,
            compactGrid = compactGrid,
            onSelected = { option ->
                onValueChange(option)
                showPicker = false
            },
            onDismiss = { showPicker = false },
        )
    }
}

@Composable
private fun RateOptionPickerDialog(
    title: String,
    options: List<String>,
    selected: String,
    compactGrid: Boolean,
    onSelected: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = YamiboTheme.colors
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = colors.creamSurface),
            elevation = CardDefaults.cardElevation(8.dp),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = title,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textDark,
                )
                Spacer(Modifier.height(16.dp))

                if (compactGrid) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        modifier = Modifier.heightIn(max = 260.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(options) { option ->
                            RateOptionButton(
                                text = option,
                                selected = option == selected,
                                onClick = { onSelected(option) },
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 360.dp).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(options) { option ->
                            RateOptionListRow(
                                text = option,
                                selected = option == selected,
                                onClick = { onSelected(option) },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(
                            text = i18n("取消"),
                            color = colors.brownPrimary,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RateOptionButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = YamiboTheme.colors
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (selected) colors.brownDeep else colors.creamBackground,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(vertical = 10.dp).fillMaxWidth(),
            color = if (selected) Color.White else colors.textDark,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun RateOptionListRow(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = YamiboTheme.colors
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) colors.brownPrimary.copy(alpha = 0.12f) else Color.Transparent,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                color = colors.textDark,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f),
            )
            if (selected) {
                Text(
                    text = i18n("已選擇"),
                    color = colors.textStrong,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun ThreadReaderStatBadge(icon: ImageVector, value: String) {
    val colors = YamiboTheme.colors
    Surface(shape = RoundedCornerShape(12.dp), color = colors.orangeAccent.copy(alpha = 0.15f)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.textOnSurface,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = value,
                fontSize = 11.sp,
                color = colors.textOnSurface,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

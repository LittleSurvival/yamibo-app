package me.thenano.yamibo.yamibo_app.navigation


import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.littlesurvival.dto.value.ForumId
import io.github.littlesurvival.dto.value.ThreadId
import io.github.littlesurvival.dto.value.UserId
import kotlinx.serialization.Serializable
import me.thenano.yamibo.yamibo_app.LocalInAppLinkNavigationRepository
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme
import me.thenano.yamibo.yamibo_app.forum.IForumScreen
import me.thenano.yamibo.yamibo_app.i18n.i18n
import me.thenano.yamibo.yamibo_app.repository.ReadHistoryRepository
import me.thenano.yamibo.yamibo_app.repository.inapplinknavigation.InAppLinkContext
import me.thenano.yamibo.yamibo_app.repository.inapplinknavigation.InAppLinkResolveResult
import me.thenano.yamibo.yamibo_app.repository.inapplinknavigation.InAppLinkTarget
import me.thenano.yamibo.yamibo_app.thread.detail.novel.INovelThreadDetailScreen
import me.thenano.yamibo.yamibo_app.thread.detail.novel.components.ThreadTopBar
import me.thenano.yamibo.yamibo_app.thread.detail.tag.ITagDetailScreen
import me.thenano.yamibo.yamibo_app.thread.reader.ICommentReaderScreen
import me.thenano.yamibo.yamibo_app.thread.reader.IThreadReaderScreen
import me.thenano.yamibo.yamibo_app.userspace.IUserSpaceScreen
import me.thenano.yamibo.yamibo_app.userspace.blog.IBlogReaderScreen
import me.thenano.yamibo.yamibo_app.webview.IPlatformWebView

@Serializable
private data class InAppLinkResolvingRestorePayload(
    val url: String,
    val currentTid: Int? = null,
    val currentTitle: String? = null,
    val currentFid: Int? = null,
    val currentAuthorId: Int? = null,
    val currentThreadTypeName: String? = null,
)
@RestorableScreenEntry
class IInAppLinkResolvingScreen(
    val url: String,
    val context: InAppLinkContext = InAppLinkContext(),
) : RestorableNavigatable {
    override val id = buildId(url)
    override val restoreDecoder = Decoder

    override fun toRestoreSnapshot(): RestorableScreenSnapshot = restoreSnapshot(
        decoder = restoreDecoder,
        payload = InAppLinkResolvingRestorePayload(
            url = url,
            currentTid = context.currentTid?.value,
            currentTitle = context.currentTitle,
            currentFid = context.currentFid?.value,
            currentAuthorId = context.currentAuthorId?.value,
            currentThreadTypeName = context.currentThreadType?.name,
        ),
    )

    @Composable
    override fun Content() {
        InAppLinkResolvingScreen(url = url, context = context)
    }

    companion object Decoder : TypedRestorableNavigatableDecoder<IInAppLinkResolvingScreen>(IInAppLinkResolvingScreen::class) {
        override fun decode(payload: String): RestorableNavigatable {
            val data = decodeRestorePayload<InAppLinkResolvingRestorePayload>(payload)
            return IInAppLinkResolvingScreen(
                url = data.url,
                context = InAppLinkContext(
                    currentTid = data.currentTid?.let(::ThreadId),
                    currentTitle = data.currentTitle,
                    currentFid = data.currentFid?.let(::ForumId),
                    currentAuthorId = data.currentAuthorId?.let(::UserId),
                    currentThreadType = data.currentThreadTypeName?.let(ReadHistoryRepository.ThreadEntryType::valueOf),
                ),
            )
        }
    }
}

private sealed interface InAppLinkResolvingState {
    data object Loading : InAppLinkResolvingState
    data class Error(
        val message: String,
        val fallbackTarget: InAppLinkTarget?,
    ) : InAppLinkResolvingState
}

@Composable
private fun InAppLinkResolvingScreen(url: String, context: InAppLinkContext) {
    val colors = YamiboTheme.colors
    val navigator = LocalNavigator.current
    val repository = LocalInAppLinkNavigationRepository.current
    var state by remember(url, context) { mutableStateOf<InAppLinkResolvingState>(InAppLinkResolvingState.Loading) }
    var progressText by remember(url, context) { mutableStateOf(i18n("準備定位")) }
    var attempt by remember(url, context) { mutableIntStateOf(0) }

    LaunchedEffect(url, context, attempt) {
        state = InAppLinkResolvingState.Loading
        progressText = i18n("準備定位")
        when (
            val result = repository.resolve(url, context) { progressText = it }
        ) {
            is InAppLinkResolveResult.Resolved -> {
                val opened = navigator.navigateInAppLinkTarget(result.target, replaceCurrent = true)
                if (!opened) {
                    state = InAppLinkResolvingState.Error(i18n("此連結暫不支援 App 內開啟"), result.target)
                }
            }
            is InAppLinkResolveResult.Failed -> {
                state = InAppLinkResolvingState.Error(result.reason?.takeIf { it.isNotBlank() } ?: i18n("定位失敗"), result.target)
            }
        }
    }

    Scaffold(
        containerColor = colors.creamBackground,
        topBar = {
            ThreadTopBar(
                title = i18n("定位中"),
                onBack = { navigator.pop() },
            )
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.creamBackground)
                .padding(paddingValues)
                .padding(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            when (val current = state) {
                InAppLinkResolvingState.Loading -> {
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(i18n("正在定位連結"), color = colors.textStrong, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Text(progressText, color = colors.brownPrimary, fontSize = 14.sp)
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth(),
                                color = colors.brownDeep,
                                trackColor = colors.brownPrimary.copy(alpha = 0.18f),
                            )
                            Text(
                                text = url,
                                color = colors.textDark.copy(alpha = 0.55f),
                                fontSize = 12.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                is InAppLinkResolvingState.Error -> {
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(i18n("定位失敗"), color = colors.textStrong, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Text(current.message, color = colors.brownPrimary, fontSize = 14.sp)
                            Text(
                                text = url,
                                color = colors.textDark.copy(alpha = 0.55f),
                                fontSize = 12.sp,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                current.fallbackTarget?.takeIf { it.canOpenInApp() }?.let { target ->
                                    TextButton(onClick = { navigator.navigateInAppLinkTarget(target, replaceCurrent = true) }) {
                                        Text(i18n("開啟替代頁"), color = colors.brownPrimary)
                                    }
                                }
                                TextButton(onClick = { navigator.navigate(IPlatformWebView(url)) }) {
                                    Text("WebView", color = colors.brownPrimary)
                                }
                                OutlinedButton(
                                    onClick = { attempt++ },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.textStrong),
                                ) {
                                    Text(i18n("重試"))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

fun ComposableNavigator.navigateInAppLinkTarget(
    target: InAppLinkTarget,
    replaceCurrent: Boolean = false,
): Boolean {
    fun open(navigatable: Navigatable) {
        if (replaceCurrent) replace(navigatable) else navigate(navigatable)
    }
    when (target) {
        is InAppLinkTarget.ForumTarget -> open(
            IForumScreen(target.fid, target.title ?: "Forum ${target.fid.value}"),
        )

        is InAppLinkTarget.ThreadReaderTarget -> open(
            IThreadReaderScreen(
                tid = target.tid,
                title = target.title,
                threadType = target.threadType,
                authorId = target.authorId,
                initialPage = target.initialPage,
                targetPid = target.targetPid,
            ),
        )

        is InAppLinkTarget.NovelDetailTarget -> open(
            INovelThreadDetailScreen(target.tid, target.title, target.authorId),
        )

        is InAppLinkTarget.CommentReaderTarget -> open(
            ICommentReaderScreen(
                tid = target.tid,
                postTitle = target.postTitle,
                oPostId = target.oPostId,
                authorId = target.authorId,
                targetCommentPid = target.targetCommentPid,
            ),
        )

        is InAppLinkTarget.UserSpaceTarget -> open(IUserSpaceScreen(target.userId, target.titleHint))
        is InAppLinkTarget.BlogReaderTarget -> open(IBlogReaderScreen(target.blogId, target.userId, target.titleHint))
        is InAppLinkTarget.TagDetailTarget -> open(ITagDetailScreen(target.tagId, target.title, target.page))
        is InAppLinkTarget.WebOnlyTarget -> open(IPlatformWebView(target.url))
        is InAppLinkTarget.UnsupportedTarget -> return false
    }
    return true
}

fun InAppLinkTarget.canOpenInApp(): Boolean {
    return this !is InAppLinkTarget.WebOnlyTarget && this !is InAppLinkTarget.UnsupportedTarget
}

fun looksLikeSupportedYamiboInAppLink(rawUrl: String): Boolean {
    val url = rawUrl.trim().replace("&amp;", "&")
    val lower = url.lowercase()
    val path = lower
        .removePrefix("http://bbs.yamibo.com/")
        .removePrefix("https://bbs.yamibo.com/")
        .removePrefix("http://yamibo.com/")
        .removePrefix("https://yamibo.com/")
    val yamibo = lower.startsWith("http://bbs.yamibo.com/") ||
        lower.startsWith("https://bbs.yamibo.com/") ||
        lower.startsWith("http://yamibo.com/") ||
        lower.startsWith("https://yamibo.com/") ||
        !lower.startsWith("http")
    return yamibo && (path.contains("mod=forumdisplay") ||
        path.startsWith("forum-") ||
        path.contains("mod=viewthread") ||
        path.startsWith("thread-") ||
        path.contains("goto=findpost") ||
        path.contains("mod=space") ||
        path.startsWith("space-uid-") ||
        path.contains("do=blog") ||
        path.startsWith("blog-") ||
        (path.contains("misc.php") && path.contains("mod=tag")))
}
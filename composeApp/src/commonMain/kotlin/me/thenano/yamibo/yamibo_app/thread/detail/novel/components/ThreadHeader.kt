package me.thenano.yamibo.yamibo_app.thread.detail.novel.components

import YamiboIcons
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.SubcomposeAsyncImage
import io.github.littlesurvival.YamiboRoute
import io.github.littlesurvival.dto.page.ThreadPage
import me.thenano.yamibo.yamibo_app.components.text.rememberConvertedText
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme
import me.thenano.yamibo.yamibo_app.favorite.FavoriteActionButton
import me.thenano.yamibo.yamibo_app.i18n.i18n
import me.thenano.yamibo.yamibo_app.navigation.LocalNavigator
import me.thenano.yamibo.yamibo_app.thread.detail.components.DetailNoteActionButton
import me.thenano.yamibo.yamibo_app.userspace.IUserSpaceScreen
import me.thenano.yamibo.yamibo_app.util.rememberImageRequest
import org.jetbrains.compose.resources.painterResource
import yamibo_app.composeapp.generated.resources.Res
import yamibo_app.composeapp.generated.resources.book

@Composable
internal fun ThreadHeader(
    threadPage: ThreadPage,
    coverUrlOverride: String? = null,
    isFavorited: Boolean,
    onFavorite: () -> Unit,
    onFavoriteLongPress: (() -> Unit)? = null,
    onShare: () -> Unit,
    onExportTxt: () -> Unit = {},
    onContinueRead: () -> Unit = {},
    readingProgressText: String? = null,
    noteContent: String = "",
    onNoteClick: () -> Unit = {},
    onCopy: (String) -> Unit = {}
) {
    @Suppress("DEPRECATION") val clipboardManager = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current
    val navigator = LocalNavigator.current
    val colors = YamiboTheme.colors
    val thread = threadPage.thread
    val firstPost = threadPage.posts.firstOrNull { it.floor == 1 } ?: threadPage.posts.firstOrNull()
    val convertedThreadTitle = rememberConvertedText(thread.title)

    val coverUrl = coverUrlOverride ?: remember(firstPost) {
        val attachedImage = firstPost?.images?.firstOrNull()?.url ?: return@remember null
        if (
            attachedImage.contains("none.gif") ||
            attachedImage.contains("smiley/") ||
            attachedImage.contains("face")
        ) {
            null
        } else if (attachedImage.startsWith("http")) {
            attachedImage
        } else {
            "${YamiboRoute.Domain.build()}$attachedImage"
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(4.dp),
        colors = CardDefaults.cardColors(containerColor = colors.creamSurface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.width(100.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Card(
                        modifier = Modifier.size(width = 100.dp, height = 130.dp),
                        shape = RoundedCornerShape(12.dp),
                        elevation = CardDefaults.cardElevation(2.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = colors.brownPrimary.copy(alpha = 0.1f)
                        )
                    ) {
                        if (coverUrl != null) {
                            SubcomposeAsyncImage(
                                model = rememberImageRequest(url = coverUrl),
                                contentDescription = "cover",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                                loading = {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Icon(
                                            painter = painterResource(Res.drawable.book),
                                            contentDescription = "loading",
                                            modifier = Modifier.size(32.dp),
                                            tint = colors.brownPrimary.copy(alpha = 0.2f)
                                        )
                                    }
                                },
                                error = {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Icon(
                                            painter = painterResource(Res.drawable.book),
                                            contentDescription = "error",
                                            modifier = Modifier.size(48.dp),
                                            tint = colors.brownPrimary.copy(alpha = 0.4f)
                                        )
                                    }
                                }
                            )
                        } else {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Icon(
                                    painter = painterResource(Res.drawable.book),
                                    contentDescription = "cover",
                                    modifier = Modifier.size(48.dp),
                                    tint = colors.brownPrimary.copy(alpha = 0.4f)
                                )
                            }
                        }
                    }
                    DetailNoteActionButton(
                        hasNote = noteContent.isNotBlank(),
                        onClick = onNoteClick,
                    )
                }

                Spacer(Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    CopyableLabel(
                        text = convertedThreadTitle,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.textDark,
                        onCopy = {
                            clipboardManager.setText(AnnotatedString(convertedThreadTitle))
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onCopy(i18n("已複製標題"))
                        }
                    )
                    Spacer(Modifier.height(6.dp))

                    if (firstPost != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = YamiboIcons.PersonFill,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = colors.brownPrimary.copy(alpha = 0.7f)
                            )
                            Spacer(Modifier.width(4.dp))
                            CopyableLabel(
                                text = firstPost.author.name,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Normal,
                                color = colors.brownPrimary.copy(alpha = 0.8f),
                                onClick = {
                                    navigator.navigate(IUserSpaceScreen(firstPost.author.uid, firstPost.author.name))
                                },
                                onCopy = {
                                    clipboardManager.setText(AnnotatedString(firstPost.author.name))
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onCopy(i18n("已複製作者名稱"))
                                }
                            )
                        }
                        Spacer(Modifier.height(3.dp))

                        Text(
                            text = i18n("發表於 {}", firstPost.timeCreate.text),
                            fontSize = 12.sp,
                            color = colors.brownPrimary.copy(alpha = 0.5f)
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = i18n("最後更新於 {}", (firstPost.lastEditedTime ?: firstPost.timeCreate).text),
                            fontSize = 12.sp,
                            color = colors.brownPrimary.copy(alpha = 0.5f)
                        )
                        Spacer(Modifier.height(3.dp))
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        thread.totalViews?.let { totalViews ->
                            Icon(
                                imageVector = YamiboIcons.Views,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = colors.brownPrimary.copy(alpha = 0.6f)
                            )
                            Spacer(Modifier.width(3.dp))
                            Text(
                                text = totalViews.toString(),
                                fontSize = 12.sp,
                                color = colors.brownPrimary.copy(alpha = 0.6f)
                            )
                            Spacer(Modifier.width(10.dp))
                        }
                        thread.totalReplies?.let { totalReplies ->
                            Icon(
                                imageVector = YamiboIcons.Comment,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = colors.brownPrimary.copy(alpha = 0.6f)
                            )
                            Spacer(Modifier.width(3.dp))
                            Text(
                                text = totalReplies.toString(),
                                fontSize = 12.sp,
                                color = colors.brownPrimary.copy(alpha = 0.6f)
                            )
                        }
                    }

                    Spacer(Modifier.height(6.dp))

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = colors.brownDeep.copy(alpha = 0.12f)
                    ) {
                        Text(
                            text = "#${thread.forum.name}",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            fontSize = 11.sp,
                            color = colors.brownDeep
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = colors.brownPrimary.copy(alpha = 0.1f)
                ) {
                    FavoriteActionButton(
                        onClick = onFavorite,
                        onLongClick = onFavoriteLongPress,
                        tint = colors.brownDeep,
                        iconSize = 22,
                        modifier = Modifier.size(42.dp),
                        filled = isFavorited
                    )
                }

                Surface(
                    onClick = onShare,
                    shape = RoundedCornerShape(12.dp),
                    color = colors.brownPrimary.copy(alpha = 0.1f)
                ) {
                    Box(Modifier.padding(10.dp), contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = YamiboIcons.Share,
                            contentDescription = i18n("分享"),
                            modifier = Modifier.size(22.dp),
                            tint = colors.brownDeep
                        )
                    }
                }

                Surface(
                    onClick = onExportTxt,
                    shape = RoundedCornerShape(12.dp),
                    color = colors.brownPrimary.copy(alpha = 0.1f)
                ) {
                    Box(Modifier.padding(10.dp), contentAlignment = Alignment.Center) {
                        Text(
                            text = "TXT",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.brownDeep
                        )
                    }
                }

                Surface(
                    onClick = onContinueRead,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    color = colors.brownDeep
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (readingProgressText != null) i18n("繼續閱讀") else i18n("開始閱讀"),
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )

                        if (readingProgressText != null) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = readingProgressText,
                                modifier = Modifier.weight(1f),
                                color = Color.White.copy(alpha = 0.65f),
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.width(8.dp))
                        } else {
                            Spacer(Modifier.weight(1f))
                        }

                        Text(text = "▶", color = Color.White, fontSize = 16.sp)
                    }
                }
            }

            Text(
                text = i18n("星星按鈕可直接收藏，長按可指定小集合"),
                modifier = Modifier.padding(top = 8.dp),
                fontSize = 10.sp,
                color = colors.brownPrimary.copy(alpha = 0.45f)
            )
        }
    }
}

@Composable
private fun CopyableLabel(
    text: String,
    fontSize: androidx.compose.ui.unit.TextUnit,
    fontWeight: FontWeight,
    color: Color,
    onClick: () -> Unit = {},
    onCopy: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(targetValue = if (isPressed) 0.96f else 1f)

    Text(
        text = text,
        fontSize = fontSize,
        fontWeight = fontWeight,
        color = color,
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = onCopy
            )
    )
}

package me.thenano.yamibo.yamibo_app.thread.detail.components

import YamiboIcons
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.littlesurvival.YamiboForum
import io.github.littlesurvival.dto.model.AttachmentType
import io.github.littlesurvival.dto.model.ThreadSummary
import me.thenano.yamibo.yamibo_app.forum.components.StatBadge
import me.thenano.yamibo.yamibo_app.components.user.UserAvatar
import me.thenano.yamibo.yamibo_app.navigation.LocalNavigator
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme
import me.thenano.yamibo.yamibo_app.i18n.i18n
import me.thenano.yamibo.yamibo_app.repository.download.DownloadQueueEntry
import me.thenano.yamibo.yamibo_app.repository.download.DownloadStatus
import me.thenano.yamibo.yamibo_app.thread.components.catalogDownloadLabel
import me.thenano.yamibo.yamibo_app.thread.components.catalogDownloadLabels
import me.thenano.yamibo.yamibo_app.userspace.IUserSpaceScreen

/** Thread card for the Tag Detail page */
@Composable
fun CatalogThreadCard(
    thread: ThreadSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isLastRead: Boolean = false,
    readingProgressText: String? = null,
    bookmarked: Boolean = false,
    read: Boolean = false,
    downloadEntry: DownloadQueueEntry? = null,
    onLongPress: (() -> Unit)? = null,
) {
    val colors = YamiboTheme.colors
    val navigator = LocalNavigator.current

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = colors.creamSurface,
        border = BorderStroke(0.5.dp, colors.brownPrimary.copy(alpha = 0.15f)),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .alpha(if (read) 0.6f else 1f)
            .pointerInput(thread.tid, onClick, onLongPress) {
                detectTapGestures(
                    onTap = { onClick() },
                    onLongPress = { onLongPress?.invoke() },
                )
            }
    ) {
        Column(
            modifier = Modifier
                .let {
                    if (isLastRead) {
                        it.drawBehind {
                            drawRect(
                                color = colors.orangeAccent,
                                size = size.copy(width = 4.dp.toPx())
                            )
                        }
                    } else it
                }
                .padding(14.dp)
        ) {
            // Author + Time (Top)
            Row(verticalAlignment = Alignment.CenterVertically) {
                var hasLeadingMetadata = false
                if (thread.author != null) {
                    UserAvatar(
                        thread.author!!.avatarUrl,
                        size = 22,
                        contentDescription = "Avatar",
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = thread.author!!.name,
                        fontSize = 12.sp,
                        color = colors.brownPrimary,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.clickable {
                            val author = thread.author!!
                            navigator.navigate(IUserSpaceScreen(author.uid, author.name))
                        }
                    )
                    hasLeadingMetadata = true
                }
                val lastUpdate = thread.lastUpdate
                if (lastUpdate != null) {
                    Text(
                        text = "${if (hasLeadingMetadata) " · " else ""}${lastUpdate.text}",
                        fontSize = 12.sp,
                        color = colors.textDark.copy(alpha = 0.4f)
                    )
                    hasLeadingMetadata = true
                }
                if (readingProgressText != null) {
                    Text(
                        text = "${if (hasLeadingMetadata) " · " else ""}$readingProgressText",
                        fontSize = 12.sp,
                        color = colors.orangeAccent,
                        fontWeight = FontWeight.Medium,
                    )
                }
                if (downloadEntry != null) {
                    val prefix = if (hasLeadingMetadata || readingProgressText != null) " · " else ""
                    Text(
                        text = prefix + catalogDownloadLabel(
                            entry = downloadEntry,
                            labels = catalogDownloadLabels(downloadingTextLabel = i18n("正在取得內容")),
                        ),
                        fontSize = 12.sp,
                        color = if (downloadEntry.status == DownloadStatus.Failed) colors.redAccent else colors.orangeAccent,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))

            // Title + Attachment (Middle)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Attachment type icon
                val attachIcon = when (thread.attachmentType) {
                    AttachmentType.Image -> "🖼"
                    AttachmentType.Other -> "📎"
                    else -> null
                }
                if (attachIcon != null) {
                    Text(
                        text = attachIcon,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(end = 6.dp)
                    )
                }

                // Poll icon
                if (thread.hasPoll) {
                    Text(
                        text = "📊",
                        fontSize = 14.sp,
                        modifier = Modifier.padding(end = 6.dp)
                    )
                }

                if (bookmarked) {
                    Icon(
                        imageVector = YamiboIcons.Bookmark,
                        contentDescription = null,
                        tint = colors.orangeAccent,
                        modifier = Modifier.size(12.dp).padding(end = 3.dp),
                    )
                    Spacer(Modifier.width(3.dp))
                }

                if (downloadEntry?.status == DownloadStatus.Downloaded) {
                    Icon(
                        imageVector = YamiboIcons.Downloaded,
                        contentDescription = i18n("已下載"),
                        tint = colors.brownPrimary,
                        modifier = Modifier.size(13.dp).padding(end = 3.dp),
                    )
                    Spacer(Modifier.width(3.dp))
                }

                Text(
                    text = thread.title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.textDark,
                    modifier = Modifier.weight(1f),
                    lineHeight = 20.sp
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Footer (Bottom)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // View count
                if (thread.viewCount != null) {
                    StatBadge(icon = YamiboIcons.Views, value = "${thread.viewCount}")
                    Spacer(Modifier.width(12.dp))
                }
                // Reply count
                if (thread.replyCount != null) {
                    StatBadge(icon = YamiboIcons.Comment, value = "${thread.replyCount}")
                }
                
                Spacer(Modifier.weight(1f))
                
                // Forum Name (Right)
                val forumName = thread.fid?.let { YamiboForum.toForumName(it) } ?: thread.tag
                if (forumName != null) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = colors.orangeAccent.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = "#$forumName",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = colors.textOnSurface
                        )
                    }
                }
            }
        }
    }
}

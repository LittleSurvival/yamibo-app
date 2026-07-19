package me.thenano.yamibo.yamibo_app.thread.detail.novel.components

import me.thenano.yamibo.yamibo_app.i18n.i18n

import YamiboIcons
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.littlesurvival.dto.page.Post
import me.thenano.yamibo.yamibo_app.components.text.rememberConvertedText
import me.thenano.yamibo.yamibo_app.repository.ChapterStateRepository
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme
import me.thenano.yamibo.yamibo_app.thread.components.chapterPercentProgressLabel

/** Expandable page section with post titles */
@Composable
internal fun PostPageSection(
    page: Int,
    isExpanded: Boolean,
    posts: List<Post>?,
    bookmarkedPostIds: Set<Long>,
    readPostIds: Set<Long>,
    chapterStates: Map<Long, ChapterStateRepository.Entry> = emptyMap(),
    isFirstPage: Boolean,
    onToggle: () -> Unit,
    onPostClick: (Post) -> Unit,
    onPostLongPress: (Post) -> Unit,
) {
    val colors = YamiboTheme.colors
    val rotation by
    animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        animationSpec = tween(300),
        label = "page_chevron"
    )

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        /** Page header bar */
        Surface(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            shape = RoundedCornerShape(12.dp),
            color =
                if (isFirstPage) colors.brownDeep.copy(alpha = 0.08f)
                else colors.brownPrimary.copy(alpha = 0.06f),
            onClick = onToggle
        ) {
            Row(
                modifier =
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = i18n("第 {} 頁", page),
                    fontSize = 14.sp,
                    fontWeight = if (isFirstPage) FontWeight.Bold else FontWeight.Medium,
                    color = colors.brownDeep
                )

                /** Expand / collapse chevron, or ">" for future reader nav */
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "▼",
                        modifier = Modifier.graphicsLayer { rotationZ = rotation },
                        fontSize = 12.sp,
                        color = colors.brownPrimary.copy(alpha = 0.6f)
                    )
                }
            }
        }

        /** Expanded post list */
        AnimatedVisibility(
            visible = isExpanded,
            enter =
                expandVertically(animationSpec = tween(300)) +
                    fadeIn(animationSpec = tween(300)),
            exit =
                shrinkVertically(animationSpec = tween(300)) +
                    fadeOut(animationSpec = tween(300))
        ) {
            Column {
                if (posts == null) {
                    /** Loading indicator */
                    Box(
                        modifier = Modifier.fillMaxWidth().height(60.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = colors.brownDeep,
                            strokeWidth = 2.dp
                        )
                    }
                } else {
                    posts.forEach { post ->
                        val postId = post.pid.value.toLong()
                        val chapterState = chapterStates[postId]
                        val isRead = postId in readPostIds || chapterState?.read == true
                        PostTitleRow(
                            post = post,
                            bookmarked = postId in bookmarkedPostIds,
                            read = isRead,
                            progressText = chapterState?.chapterPercentProgressLabel(),
                            onClick = { onPostClick(post) },
                            onLongPress = { onPostLongPress(post) },
                        )
                    }
                }
            }
        }
    }
}
/** Single post title row (lightweight — just title) */
@Composable
private fun PostTitleRow(
    post: Post,
    bookmarked: Boolean,
    read: Boolean,
    progressText: String?,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    val colors = YamiboTheme.colors
    val displayTitle = rememberConvertedText(post.title.ifBlank { i18n("（無標題）") })

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp, horizontal = 4.dp)
            .alpha(if (read) 0.6f else 1f)
            .pointerInput(post.pid, onClick, onLongPress) {
                detectTapGestures(
                    onTap = { onClick() },
                    onLongPress = { onLongPress() },
                )
            },
        shape = RoundedCornerShape(10.dp),
        color = colors.creamSurface,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            /** Floor number badge */
            Surface(shape = RoundedCornerShape(6.dp), color = colors.brownDeep.copy(alpha = 0.1f)) {
                Text(
                    text = "${post.floor}#",
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.brownDeep
                )
            }
            Spacer(Modifier.width(10.dp))

            if (bookmarked) {
                Icon(
                    imageVector = YamiboIcons.Bookmark,
                    contentDescription = null,
                    tint = colors.orangeAccent,
                    modifier = Modifier.size(12.dp).padding(end = 3.dp),
                )
                Spacer(Modifier.width(3.dp))
            }

            /** Post title or fallback */
            Text(
                text = displayTitle,
                modifier = Modifier.weight(1f),
                fontSize = 13.sp,
                color = colors.textDark,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!read && progressText != null) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = progressText,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.orangeAccent,
                )
            }
        }
    }
}

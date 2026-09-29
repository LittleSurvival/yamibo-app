package me.thenano.yamibo.yamibo_app.thread.reader.components.post.impl

import me.thenano.yamibo.yamibo_app.i18n.i18n
import me.thenano.yamibo.yamibo_app.thread.image.platformImageAnimation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.littlesurvival.dto.page.PostComment
import me.thenano.yamibo.yamibo_app.LocalNovelReaderSettingsRepository
import me.thenano.yamibo.yamibo_app.navigation.LocalNavigator
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme
import me.thenano.yamibo.yamibo_app.util.state
import me.thenano.yamibo.yamibo_app.userspace.IUserSpaceScreen

@Composable
fun CommentRenderer(
    comments: List<PostComment>,
    modifier: Modifier = Modifier
) {
    val colors = YamiboTheme.colors
    val navigator = LocalNavigator.current
    val novelSettingsRepo = LocalNovelReaderSettingsRepository.current
    val fontSize = novelSettingsRepo.fontSize.state()
    val lineSpacing = novelSettingsRepo.lineSpacing.state()
    if (comments.isEmpty()) return

    Card(
        modifier = modifier.fillMaxWidth().padding(vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = colors.creamBackground),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = i18n("💬 點評"),
                    color = colors.brownPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }

            comments.forEachIndexed { index, comment ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    // Avatar
                    val avatarUrl = comment.user.avatarUrl ?: ""
                    if (avatarUrl.isNotEmpty()) {
                        AsyncImage(transform = ::platformImageAnimation,
                            model = avatarUrl,
                            contentDescription = "Avatar",
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .clickable {
                                    navigator.navigate(IUserSpaceScreen(comment.user.uid, comment.user.name))
                                },
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .clickable {
                                    navigator.navigate(IUserSpaceScreen(comment.user.uid, comment.user.name))
                                }
                                .background(colors.brownPrimary.copy(alpha = 0.2f))
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = comment.user.name,
                                color = colors.brownPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clickable {
                                    navigator.navigate(IUserSpaceScreen(comment.user.uid, comment.user.name))
                                }
                            )
                            Text(
                                text = comment.time.text,
                                color = colors.textDark.copy(alpha = 0.5f),
                                fontSize = 11.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = comment.message,
                            color = colors.textDark,
                            fontSize = (fontSize - 2).coerceAtLeast(10).sp,
                            lineHeight = ((fontSize - 2).coerceAtLeast(10) * lineSpacing).sp,
                        )
                    }
                }
                if (index < comments.size - 1) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 48.dp),
                        thickness = DividerDefaults.Thickness,
                        color = colors.brownLight.copy(alpha = 0.3f)
                    )
                }
            }
        }
    }
}


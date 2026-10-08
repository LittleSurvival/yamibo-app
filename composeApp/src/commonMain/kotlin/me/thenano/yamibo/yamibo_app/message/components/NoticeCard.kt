package me.thenano.yamibo.yamibo_app.message.components

import me.thenano.yamibo.yamibo_app.i18n.i18n

import YamiboIcons
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.littlesurvival.dto.page.NoticeItem
import io.github.littlesurvival.dto.value.UserId
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme
import me.thenano.yamibo.yamibo_app.thread.reader.components.post.impl.HtmlRenderer
import me.thenano.yamibo.yamibo_app.components.user.UserAvatar
import me.thenano.yamibo.yamibo_app.userspace.components.SmallActionButton

@Composable
internal fun NoticeCard(
    item: NoticeItem,
    onUserClick: (UserId) -> Unit,
    onAction: () -> Unit,
) {
    val colors = YamiboTheme.colors
    val noticeUserId = remember(item.avatarUrl, item.contentHtml) {
        item.avatarUrl?.toYamiboAvatarUserId() ?: item.contentHtml.toYamiboLinkedUserId()
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onAction,
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (item.avatarUrl.isNullOrBlank() && noticeUserId == null) {
            Box(
                modifier = Modifier
                    .width(42.dp)
                    .height(42.dp)
                    .background(colors.creamSurface, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = YamiboIcons.Sync,
                    contentDescription = i18n("系統消息"),
                    tint = colors.brownPrimary,
                    modifier = Modifier.width(22.dp).height(22.dp),
                )
            }
        } else {
            UserAvatar(
                item.avatarUrl,
                size = 42,
                modifier = if (noticeUserId != null) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onUserClick(noticeUserId) },
                    )
                } else {
                    Modifier
                },
                contentDescription = "Avatar",
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.timeInfo.text, color = colors.brownLight, fontSize = 12.sp)
            Spacer(Modifier.height(3.dp))
            HtmlRenderer(html = item.contentHtml, modifier = Modifier.fillMaxWidth())
            item.quote?.let {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    shape = RoundedCornerShape(4.dp),
                    color = colors.creamBackground,
                ) {
                    Text(it, modifier = Modifier.padding(10.dp), color = colors.textDark, fontSize = 13.sp)
                }
            }
        }
        SmallActionButton(i18n("屏蔽"), onAction)
    }
    HorizontalDivider(color = colors.brownLight.copy(alpha = 0.35f))
}

private fun String.toYamiboAvatarUserId(): UserId? {
    val normalized = replace('\\', '/')
    val match = YAMIBO_AVATAR_UID_REGEX.find(normalized) ?: return null
    val rawUid = match.groupValues.drop(1).joinToString("")
    return rawUid.toIntOrNull()?.takeIf { it > 0 }?.let(::UserId)
}

private fun String.toYamiboLinkedUserId(): UserId? {
    val rawUid = YAMIBO_SPACE_UID_QUERY_REGEX.find(this)?.groupValues?.getOrNull(1)
        ?: YAMIBO_SPACE_UID_PATH_REGEX.find(this)?.groupValues?.getOrNull(1)
    return rawUid?.toIntOrNull()?.takeIf { it > 0 }?.let(::UserId)
}

private val YAMIBO_AVATAR_UID_REGEX = Regex("""/avatar/(\d{3})/(\d{2})/(\d{2})/(\d{2})_avatar""")
private val YAMIBO_SPACE_UID_QUERY_REGEX = Regex("""[?&;]uid=(\d+)""")
private val YAMIBO_SPACE_UID_PATH_REGEX = Regex("""space-uid-(\d+)""")


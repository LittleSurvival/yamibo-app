package me.thenano.yamibo.yamibo_app.message.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.littlesurvival.dto.page.PrivateMessageItem
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme
import me.thenano.yamibo.yamibo_app.components.user.UserAvatar
import me.thenano.yamibo.yamibo_app.userspace.components.SmallBadge

@Composable
internal fun PrivateMessageCard(item: PrivateMessageItem, onUserClick: () -> Unit, onAction: () -> Unit) {
    val colors = YamiboTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onAction).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.clickable(onClick = onUserClick)) {
            UserAvatar(item.user.avatarUrl, size = 42)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.title, color = colors.textStrong, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                item.unreadCount?.let {
                    Spacer(Modifier.width(6.dp))
                    SmallBadge(it.toString())
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(item.message, color = colors.brownPrimary.copy(alpha = 0.65f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(item.timeInfo.text, color = colors.brownLight, fontSize = 12.sp)
    }
    HorizontalDivider(color = colors.brownLight.copy(alpha = 0.35f))
}

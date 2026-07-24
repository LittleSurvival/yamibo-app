package me.thenano.yamibo.yamibo_app.userspace.blog.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.littlesurvival.dto.model.BlogSummary
import io.github.littlesurvival.dto.model.User
import io.github.littlesurvival.dto.page.ManageButton
import me.thenano.yamibo.yamibo_app.components.controls.YamiboSmallActionButton
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme

@Composable
internal fun BlogCard(
    blog: BlogSummary,
    onClick: () -> Unit,
    onUserClick: (User) -> Unit,
    onActionClick: (ManageButton) -> Unit,
) {
    val colors = YamiboTheme.colors
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
        color = colors.creamSurface,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(0.5.dp, colors.brownLight.copy(alpha = 0.35f)),
        onClick = onClick,
    ) {
        Column(Modifier.padding(14.dp)) {
            UserLine(blog.author, blog.timeInfo.text, onUserClick)
            Spacer(Modifier.height(10.dp))
            Text(blog.title, color = colors.textStrong, fontSize = 18.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                blog.description,
                color = colors.brownPrimary.copy(alpha = 0.75f),
                fontSize = 13.sp,
                lineHeight = 19.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (blog.manageButtons.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    blog.manageButtons.forEach { action ->
                        YamiboSmallActionButton(
                            text = action.name,
                            onClick = { onActionClick(action) },
                        )
                    }
                }
            }
        }
    }
}

package me.thenano.yamibo.yamibo_app.thread.reader.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme

/**
 * Reusable banner button for comment navigation and "load more" actions.
 *
 * Design reference: cream background, brownPrimary border, centered icon+text.
 */
@Composable
internal fun CommentBanner(
    text: String,
    icon: String = "💬",
    onClick: () -> Unit,
    @Suppress("ModifierParameter") modifier: Modifier = Modifier
) {
    val colors = YamiboTheme.colors

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = colors.creamSurface,
        border = BorderStroke(1.dp, colors.brownPrimary.copy(alpha = 0.3f)),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = icon,
                fontSize = 18.sp
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = text,
                color = colors.brownPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "›",
                color = colors.brownPrimary.copy(alpha = 0.6f),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

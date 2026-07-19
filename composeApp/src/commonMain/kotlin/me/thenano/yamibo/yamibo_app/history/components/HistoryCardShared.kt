package me.thenano.yamibo.yamibo_app.history.components

import YamiboIcons
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme
import me.thenano.yamibo.yamibo_app.favorite.FavoriteActionButton
import me.thenano.yamibo.yamibo_app.i18n.i18n

@Composable
internal fun MangaHistoryDetails(
    title: String,
    threadTitle: String,
    progressText: String,
    modeLabel: String?,
    timingSummary: String,
    modifier: Modifier = Modifier,
) {
    val colors = YamiboTheme.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.textDark,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = threadTitle,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = colors.textDark.copy(alpha = 0.75f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = progressText,
            fontSize = 12.sp,
            color = colors.textDark.copy(alpha = 0.56f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!modeLabel.isNullOrBlank()) {
            Text(
                text = modeLabel,
                fontSize = 11.sp,
                color = colors.orangeAccent.copy(alpha = 0.9f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = timingSummary,
            fontSize = 12.sp,
            color = colors.textDark.copy(alpha = 0.48f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun HistoryCardActions(
    isFavorited: Boolean,
    onFavorite: () -> Unit,
    onFavoriteLongPress: (() -> Unit)?,
    onDelete: () -> Unit,
) {
    val colors = YamiboTheme.colors
    Column(
        verticalArrangement = Arrangement.spacedBy(0.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        FavoriteActionButton(
            onClick = onFavorite,
            onLongClick = onFavoriteLongPress,
            modifier = Modifier.size(32.dp),
            tint = colors.brownPrimary.copy(alpha = 0.75f),
            iconSize = 16,
            filled = isFavorited,
        )
        IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
            Icon(
                imageVector = YamiboIcons.Trashcan,
                contentDescription = i18n("刪除"),
                modifier = Modifier.size(16.dp),
                tint = colors.textDark.copy(alpha = 0.4f),
            )
        }
    }
}

package me.thenano.yamibo.yamibo_app.history.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.SubcomposeAsyncImage
import me.thenano.yamibo.yamibo_app.components.feedback.resolvedContentCoverUrl
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme
import me.thenano.yamibo.yamibo_app.i18n.i18n
import me.thenano.yamibo.yamibo_app.repository.FavoriteStoreRepository
import me.thenano.yamibo.yamibo_app.repository.ReadHistoryRepository
import me.thenano.yamibo.yamibo_app.util.rememberImageRequest
import org.jetbrains.compose.resources.painterResource
import yamibo_app.composeapp.generated.resources.Res
import yamibo_app.composeapp.generated.resources.book

@Composable
fun RssSearchHistoryCard(
    history: ReadHistoryRepository.RssSearchReadingHistory,
    timeLabel: String,
    progressText: String = i18n("目前第 {} / {} 頁", history.threadImagePageIndex + 1, history.threadImageTotalPages),
    modeLabel: String? = null,
    isSelectMode: Boolean = false,
    isSelected: Boolean = false,
    isFavorited: Boolean = false,
    onClick: () -> Unit,
    onCoverClick: () -> Unit,
    onDelete: () -> Unit,
    onFavorite: () -> Unit,
    onFavoriteLongPress: (() -> Unit)? = null,
) {
    val colors = YamiboTheme.colors
    val resolvedCoverUrl = resolvedContentCoverUrl(
        targetType = FavoriteStoreRepository.FavoriteTargetType.RssSearch,
        targetId = history.subscriptionId,
        fallback = history.coverUrl,
    )
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) 0.97f else 1f, tween(150))

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .scale(scale)
            .then(
                if (isSelected) Modifier.border(2.dp, colors.brownDeep, RoundedCornerShape(16.dp)) else Modifier
            )
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp, pressedElevation = 1.dp),
        colors = CardDefaults.cardColors(containerColor = colors.creamSurface),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Card(
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .width(92.dp)
                    .aspectRatio(0.72f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onCoverClick,
                    ),
                colors = CardDefaults.cardColors(containerColor = colors.brownLight.copy(alpha = 0.2f)),
            ) {
                if (!resolvedCoverUrl.isNullOrEmpty()) {
                    SubcomposeAsyncImage(
                        model = rememberImageRequest(url = resolvedCoverUrl),
                        contentDescription = "Cover Image",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(Res.drawable.book),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = colors.brownPrimary.copy(alpha = 0.5f),
                        )
                    }
                }
            }

            MangaHistoryDetails(
                title = history.subscriptionTitle,
                threadTitle = history.threadTitle,
                progressText = progressText,
                modeLabel = modeLabel,
                timingSummary = i18n("最近閱讀 {}", timeLabel),
                modifier = Modifier.weight(1f),
            )

            Spacer(Modifier.size(4.dp))

            if (!isSelectMode) {
                HistoryCardActions(
                    isFavorited = isFavorited,
                    onFavorite = onFavorite,
                    onFavoriteLongPress = onFavoriteLongPress,
                    onDelete = onDelete,
                )
            }
        }
    }
}

package me.thenano.yamibo.yamibo_app.thread.reader.components.overlay

import me.thenano.yamibo.yamibo_app.i18n.i18n

import YamiboIcons

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.thenano.yamibo.yamibo_app.favorite.FavoriteActionButton
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme

data class ReaderSinglePageProgress(
    val currentPage: Int,
    val totalPages: Int,
    val rtl: Boolean = false,
)

@Composable
fun ReaderBottomBar(
    visible: Boolean,
    isFavorited: Boolean,
    onReply: () -> Unit,
    onFavorite: () -> Unit,
    onFavoriteLongPress: (() -> Unit)? = null,
    onShare: () -> Unit,
    singlePageProgress: ReaderSinglePageProgress? = null,
    onSinglePageProgressChange: ((Int) -> Unit)? = null,
    onSinglePageProgressCommit: (() -> Unit)? = null,
    @Suppress("ModifierParameter") modifier: Modifier = Modifier
) {
    val colors = YamiboTheme.colors
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { it }),
        exit = slideOutVertically(targetOffsetY = { it }),
        modifier = modifier.fillMaxWidth()
    ) {
        Surface(
            color = colors.brownDeep,
            shadowElevation = 8.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .navigationBarsPadding()
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
            ) {
                if (singlePageProgress != null &&
                    singlePageProgress.totalPages > 1 &&
                    onSinglePageProgressChange != null
                ) {
                    val sliderDirection = if (singlePageProgress.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
                    var localSliderPage by remember(
                        singlePageProgress.currentPage,
                        singlePageProgress.totalPages,
                    ) {
                        mutableFloatStateOf(singlePageProgress.currentPage.toFloat())
                    }
                    CompositionLocalProvider(LocalLayoutDirection provides sliderDirection) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                        ) {
                            Text(
                                text = "${singlePageProgress.currentPage + 1}",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Slider(
                                value = localSliderPage.coerceIn(0f, (singlePageProgress.totalPages - 1).toFloat()),
                                onValueChange = { value ->
                                    val nextPage = value
                                        .coerceIn(0f, (singlePageProgress.totalPages - 1).toFloat())
                                        .toInt()
                                        .coerceIn(0, singlePageProgress.totalPages - 1)
                                    localSliderPage = nextPage.toFloat()
                                    if (nextPage != singlePageProgress.currentPage) {
                                        onSinglePageProgressChange(nextPage)
                                    }
                                },
                                onValueChangeFinished = {
                                    onSinglePageProgressCommit?.invoke()
                                },
                                valueRange = 0f..(singlePageProgress.totalPages - 1).toFloat(),
                                steps = (singlePageProgress.totalPages - 2).coerceAtLeast(0),
                                colors = SliderDefaults.colors(
                                    thumbColor = colors.brownPrimary,
                                    activeTrackColor = colors.brownPrimary,
                                    inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(36.dp)
                            )
                            Text(
                                text = "${singlePageProgress.totalPages}",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        onClick = onReply,
                        color = colors.creamSurface.copy(alpha = 0.92f),
                        shape = RoundedCornerShape(50),
                        modifier = Modifier
                            .weight(1f)
                            .height(38.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.CenterStart,
                            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)
                        ) {
                            Text(i18n("發送回覆"), color = colors.textDark.copy(alpha = 0.72f), fontSize = 15.sp)
                        }
                    }

                    FavoriteActionButton(
                        onClick = onFavorite,
                        onLongClick = onFavoriteLongPress,
                        modifier = Modifier.size(36.dp),
                        tint = Color.White,
                        iconSize = 28,
                        filled = isFavorited,
                    )

                    Surface(
                        onClick = onShare,
                        color = Color.Transparent
                    ) {
                        Icon(
                            imageVector = YamiboIcons.Share,
                            contentDescription = i18n("分享"),
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            }
        }
    }
}


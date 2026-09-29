package me.thenano.yamibo.yamibo_app.thread.detail.components

import YamiboIcons
import me.thenano.yamibo.yamibo_app.thread.image.platformImageAnimation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.SubcomposeAsyncImage
import me.thenano.yamibo.yamibo_app.favorite.FavoriteActionButton
import me.thenano.yamibo.yamibo_app.i18n.i18n
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme
import me.thenano.yamibo.yamibo_app.thread.detail.components.DetailNoteActionButton
import me.thenano.yamibo.yamibo_app.util.rememberImageRequest
import org.jetbrains.compose.resources.painterResource
import yamibo_app.composeapp.generated.resources.Res
import yamibo_app.composeapp.generated.resources.book

@Composable
fun CatalogDetailHeaderCard(
    tagName: String,
    displayTitle: String = "#$tagName",
    badgeText: String = i18n("#標籤漫畫"),
    coverUrl: String?,
    isMangaMode: Boolean,
    onMangaModeChange: (Boolean) -> Unit,
    dynamicCoverEnabled: Boolean,
    onDynamicCoverEnabledChange: (Boolean) -> Unit,
    longStripModeEnabled: Boolean,
    onLongStripModeEnabledChange: (Boolean) -> Unit,
    hasReadingHistory: Boolean,
    readingProgressText: String?,
    onContinueRead: () -> Unit,
    isFavorited: Boolean,
    onFavorite: () -> Unit,
    onFavoriteLongPress: (() -> Unit)? = null,
    onShare: () -> Unit,
    showDownloadAction: Boolean = false,
    onDownload: () -> Unit = {},
    noteContent: String = "",
    onNoteClick: () -> Unit = {},
) {
    val colors = YamiboTheme.colors

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(4.dp),
        colors = CardDefaults.cardColors(containerColor = colors.creamSurface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.width(100.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Card(
                        modifier = Modifier.size(width = 100.dp, height = 130.dp),
                        shape = RoundedCornerShape(12.dp),
                        elevation = CardDefaults.cardElevation(2.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = colors.brownPrimary.copy(alpha = 0.1f)
                        )
                    ) {
                        if (coverUrl != null) {
                            SubcomposeAsyncImage(transform = ::platformImageAnimation,
                                model = rememberImageRequest(url = coverUrl),
                                contentDescription = "cover",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Icon(
                                    painter = painterResource(Res.drawable.book),
                                    contentDescription = "cover",
                                    modifier = Modifier.size(48.dp),
                                    tint = colors.brownPrimary.copy(alpha = 0.4f)
                                )
                            }
                        }
                    }
                    DetailNoteActionButton(
                        hasNote = noteContent.isNotBlank(),
                        onClick = onNoteClick,
                    )
                }

                Spacer(Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = displayTitle,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.textDark
                    )

                    Spacer(Modifier.height(8.dp))

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = colors.brownDeep.copy(alpha = 0.12f)
                    ) {
                        Text(
                            text = badgeText,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            fontSize = 11.sp,
                            color = colors.brownDeep
                        )
                    }

                    Spacer(Modifier.height(10.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = i18n("漫畫模式"),
                            fontSize = 13.sp,
                            color = colors.brownPrimary.copy(alpha = 0.85f)
                        )
                        Spacer(Modifier.weight(1f))
                        Switch(
                            checked = isMangaMode,
                            onCheckedChange = onMangaModeChange,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = colors.brownDeep,
                                checkedTrackColor = colors.brownPrimary.copy(alpha = 0.45f)
                            ),
                            modifier = Modifier.height(24.dp)
                        )
                    }

                    Spacer(Modifier.height(10.dp))

                    if (isMangaMode) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = i18n("條漫模式"),
                                fontSize = 13.sp,
                                color = colors.brownPrimary.copy(alpha = 0.85f)
                            )
                            Spacer(Modifier.weight(1f))
                            Switch(
                                checked = longStripModeEnabled,
                                onCheckedChange = onLongStripModeEnabledChange,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = colors.brownDeep,
                                    checkedTrackColor = colors.brownPrimary.copy(alpha = 0.45f)
                                ),
                                modifier = Modifier.height(24.dp)
                            )
                        }

                        Spacer(Modifier.height(10.dp))
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = i18n("動態抓取封面"),
                            fontSize = 13.sp,
                            color = colors.brownPrimary.copy(alpha = 0.85f)
                        )
                        Spacer(Modifier.weight(1f))
                        Switch(
                            checked = dynamicCoverEnabled,
                            onCheckedChange = onDynamicCoverEnabledChange,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = colors.brownDeep,
                                checkedTrackColor = colors.brownPrimary.copy(alpha = 0.45f)
                            ),
                            modifier = Modifier.height(24.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = colors.brownPrimary.copy(alpha = 0.1f)
                ) {
                    FavoriteActionButton(
                        onClick = onFavorite,
                        onLongClick = onFavoriteLongPress,
                        tint = colors.brownDeep,
                        iconSize = 22,
                        modifier = Modifier.size(42.dp),
                        filled = isFavorited
                    )
                }

                Surface(
                    onClick = onShare,
                    shape = RoundedCornerShape(12.dp),
                    color = colors.brownPrimary.copy(alpha = 0.1f)
                ) {
                    Box(Modifier.padding(10.dp), contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = YamiboIcons.Share,
                            contentDescription = i18n("分享"),
                            modifier = Modifier.size(22.dp),
                            tint = colors.brownDeep
                        )
                    }
                }

                if (showDownloadAction) {
                    Surface(
                        onClick = onDownload,
                        shape = RoundedCornerShape(12.dp),
                        color = colors.brownPrimary.copy(alpha = 0.1f)
                    ) {
                        Box(Modifier.padding(10.dp), contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = YamiboIcons.Download,
                                contentDescription = i18n("下載"),
                                modifier = Modifier.size(22.dp),
                                tint = colors.brownDeep
                            )
                        }
                    }
                }

                Surface(
                    onClick = onContinueRead,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    color = colors.brownDeep
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (hasReadingHistory) i18n("繼續閱讀") else i18n("開始閱讀"),
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )

                        if (readingProgressText != null) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = readingProgressText,
                                modifier = Modifier.weight(1f),
                                color = Color.White.copy(alpha = 0.65f),
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.width(8.dp))
                        } else {
                            Spacer(Modifier.weight(1f))
                        }

                        Text(text = "▶", color = Color.White, fontSize = 16.sp)
                    }
                }
            }

            Text(
                text = i18n("星星按鈕可直接收藏，長按可指定小集合"),
                modifier = Modifier.padding(top = 8.dp),
                fontSize = 10.sp,
                color = colors.brownPrimary.copy(alpha = 0.45f)
            )
        }
    }
}

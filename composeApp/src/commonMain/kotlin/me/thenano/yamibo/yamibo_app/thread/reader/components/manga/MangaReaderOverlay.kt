package me.thenano.yamibo.yamibo_app.thread.reader.components.manga

import YamiboIcons
import me.thenano.yamibo.yamibo_app.components.navigation.NavigationBackSymbol
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.thenano.yamibo.yamibo_app.components.navigation.YamiboTopBar
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme
import me.thenano.yamibo.yamibo_app.i18n.i18n

/**
 * Manga reader overlay with TopBar, BottomBar (page navigator + settings button).
 * Includes a full-screen scrim that handles dismissal so buttons work correctly.
 */
@Composable
fun MangaReaderOverlay(
    visible: Boolean,
    title: String,
    currentPage: Int,
    totalPages: Int,
    isRtl: Boolean,
    onBack: () -> Unit,
    onPageChange: (Int) -> Unit,
    onSettings: () -> Unit,
    onDismiss: () -> Unit,
    onCatalog: (() -> Unit)? = null,
    onNavigateToThread: (() -> Unit)? = null,
    subtitle: String? = null,
    onShare: (() -> Unit)? = null,
    onRefresh: (() -> Unit)? = null,
    onDownload: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val colors = YamiboTheme.colors

    AnimatedVisibility(
        visible = visible,
        enter = EnterTransition.None,
        exit = ExitTransition.None,
        modifier = modifier.fillMaxSize(),
    ) {
        val scrimAlpha by transition.animateFloat(transitionSpec = { tween(150) }, label = "Reader scrim") {
            if (it == EnterExitState.Visible) 0.3f else 0f
        }
        Box(Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier.fillMaxSize()
                    .drawBehind { drawRect(Color.Black, alpha = scrimAlpha) }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    )
            )
            YamiboTopBar(
                title = title,
                subtitle = subtitle,
                titleFontSize = 16,
                onBack = onBack,
                systemBarsPriority = 30,
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth()
                    .animateEnterExit(
                        enter = slideInVertically(tween(150)) { -it },
                        exit = slideOutVertically(tween(150)) { -it },
                    ),
            )
            Surface(
                color = colors.brownDeep.copy(alpha = 0.95f),
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .animateEnterExit(
                        enter = slideInVertically(tween(150)) { it },
                        exit = slideOutVertically(tween(150)) { it },
                    ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                ) {
                    // Page navigator
                    if (totalPages > 1) {
                        // RTL layout direction for slider when in RTL reading mode
                        val layoutDir = if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
                        CompositionLocalProvider(LocalLayoutDirection provides layoutDir) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                // First page button
                                IconButton(
                                    onClick = { onPageChange(0) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                Text(NavigationBackSymbol, color = Color.White, fontSize = 14.sp)
                                }

                                // Current page
                                Text(
                                    text = "${currentPage + 1}",
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )

                                // Slider
                                Slider(
                                    value = currentPage.toFloat(),
                                    onValueChange = { onPageChange(it.toInt()) },
                                    valueRange = 0f..(totalPages - 1).toFloat(),
                                    steps = if (totalPages > 2) totalPages - 2 else 0,
                                    colors = SliderDefaults.colors(
                                        thumbColor = colors.brownPrimary,
                                        activeTrackColor = colors.brownPrimary,
                                        inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                                    ),
                                    modifier = Modifier.weight(1f)
                                )

                                // Total pages
                                Text(
                                    text = "$totalPages",
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )

                                // Last page button
                                IconButton(
                                    onClick = { onPageChange(totalPages - 1) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Text("▶", color = Color.White, fontSize = 14.sp)
                                }
                            }
                        }
                    }

                    // Bottom action buttons
                    Row(
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                    ) {
                        // Catalog button
                        if (onCatalog != null) {
                            IconButton(
                                onClick = onCatalog,
                                modifier = Modifier
                                    .size(44.dp)
                                    .background(Color.Transparent, CircleShape)
                            ) {
                                Text("☰", color = Color.White, fontSize = 24.sp)
                            }
                        }

                        // Settings button
                        IconButton(
                            onClick = onSettings,
                            modifier = Modifier
                                .size(44.dp)
                                .background(Color.Transparent, CircleShape)
                        ) {
                            Icon(
                                imageVector = YamiboIcons.Setting,
                                contentDescription = i18n("設定"),
                                tint = Color.White,
                                modifier = Modifier.size(36.dp)
                            )
                        }

                        if (onDownload != null) {
                            IconButton(onClick = onDownload) {
                                Icon(YamiboIcons.Download, contentDescription = i18n("下載漫畫"), tint = Color.White)
                            }
                        }

                        // Share button (copy URL)
                        if (onShare != null) {
                            IconButton(
                                onClick = onShare,
                                modifier = Modifier
                                    .size(44.dp)
                                    .background(Color.Transparent, CircleShape)
                            ) {
                                Icon(
                                    imageVector = YamiboIcons.Share,
                                    contentDescription = i18n("分享"),
                                    tint = Color.White,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }

                        if (onRefresh != null) {
                            IconButton(
                                onClick = onRefresh,
                                modifier = Modifier
                                    .size(44.dp)
                                    .background(Color.Transparent, CircleShape)
                            ) {
                                Icon(
                                    imageVector = YamiboIcons.Reload,
                                    contentDescription = i18n("重新整理"),
                                    tint = Color.White,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }

                        // Navigate to thread button
                        if (onNavigateToThread != null) {
                            IconButton(
                                onClick = onNavigateToThread,
                                modifier = Modifier
                                    .size(44.dp)
                                    .background(Color.Transparent, CircleShape)
                            ) {
                                Text("📖", fontSize = 22.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

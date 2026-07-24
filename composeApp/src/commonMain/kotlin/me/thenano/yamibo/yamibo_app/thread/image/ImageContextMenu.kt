package me.thenano.yamibo.yamibo_app.thread.image

import me.thenano.yamibo.yamibo_app.i18n.i18n

import YamiboIcons
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.LocalPlatformContext
import kotlinx.coroutines.launch
import me.thenano.yamibo.yamibo_app.LocalAuthRepository
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme

/**
 * Long-press context menu for images.
 * Shows Copy, Share, Save actions.
 */
expect fun imageContextMenuDialogProperties(): DialogProperties

private val MenuScrimColor = Color.Black.copy(alpha = 0.08f)

@Composable
fun ImageContextMenu(
    visible: Boolean,
    imageUrl: String,
    onSetAsCover: ((String) -> Unit)? = null,
    onSetAsCatalogCover: ((String) -> Unit)? = null,
    catalogCoverLabel: String? = null,
    onMessage: ((String) -> Unit)? = null,
    onDismiss: () -> Unit,
    @Suppress("ModifierParameter") modifier: Modifier = Modifier,
    isBottomSheet: Boolean = false
) {
    if (isBottomSheet) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MenuScrimColor)
                    .clickable(
                        indication = null,
                        interactionSource = null
                    ) { onDismiss() }
            ) {
                // Bottom sheet
                AnimatedVisibility(
                    visible = visible,
                    enter = slideInVertically(initialOffsetY = { it }),
                    exit = slideOutVertically(targetOffsetY = { it }),
                    modifier = Modifier.align(Alignment.BottomCenter)
                ) {
                    ContextMenuContainer(
                        imageUrl = imageUrl,
                        onSetAsCover = onSetAsCover,
                        onSetAsCatalogCover = onSetAsCatalogCover,
                        catalogCoverLabel = catalogCoverLabel,
                        onMessage = onMessage,
                        onDismiss = onDismiss,
                        isBottomSheet = true
                    )
                }
            }
        }
    } else {
        if (visible) {
            Dialog(
                onDismissRequest = onDismiss,
                properties = imageContextMenuDialogProperties()
            ) {
                Box(
                    modifier = modifier
                        .fillMaxSize()
                        .background(MenuScrimColor)
                        .clickable(
                            indication = null,
                            interactionSource = null
                        ) { onDismiss() },
                    contentAlignment = Alignment.BottomCenter
                ) {
                    ContextMenuContainer(
                        imageUrl = imageUrl,
                        onSetAsCover = onSetAsCover,
                        onSetAsCatalogCover = onSetAsCatalogCover,
                        catalogCoverLabel = catalogCoverLabel,
                        onMessage = onMessage,
                        onDismiss = onDismiss,
                        isBottomSheet = true
                    )
                }
            }
        }
    }
}

@Composable
private fun ContextMenuContainer(
    imageUrl: String,
    onSetAsCover: ((String) -> Unit)?,
    onSetAsCatalogCover: ((String) -> Unit)?,
    catalogCoverLabel: String?,
    onMessage: ((String) -> Unit)?,
    onDismiss: () -> Unit,
    isBottomSheet: Boolean = true
) {
    val colors = YamiboTheme.colors
    val context = LocalPlatformContext.current
    val authRepo = LocalAuthRepository.current
    val scope = rememberCoroutineScope()
    
    val cookie = authRepo.cookieStore.load() ?: ""
    val referer = "https://bbs.yamibo.com/"

    fun handleResult(result: ImageActionResult) {
        val message = result.errorMessage ?: result.successMessage ?: return
        onMessage?.invoke(message)
    }

    @Composable
    fun ContextMenuActions() {
        ContextMenuItem(
            icon = YamiboIcons.Copy,
            label = i18n("複製"),
            onClick = {
                scope.launch {
                    handleResult(copyImageToClipboard(context, imageUrl, cookie, referer))
                    onDismiss()
                }
            },
        )
        ContextMenuItem(
            icon = YamiboIcons.Share,
            label = i18n("分享"),
            onClick = {
                scope.launch {
                    handleResult(shareImageToApp(context, imageUrl, cookie, referer))
                    onDismiss()
                }
            },
        )
        ContextMenuItem(
            icon = YamiboIcons.Save,
            label = i18n("儲存"),
            onClick = {
                scope.launch {
                    handleResult(saveImageToGallery(context, imageUrl, cookie, referer))
                    onDismiss()
                }
            },
        )
        if (onSetAsCover != null) {
            ContextMenuItem(
                icon = YamiboIcons.StarOutline,
                label = i18n("設為封面"),
                onClick = {
                    onSetAsCover(imageUrl)
                    onDismiss()
                },
            )
        }
        if (onSetAsCatalogCover != null && catalogCoverLabel != null) {
            ContextMenuItem(
                icon = YamiboIcons.StarOutline,
                label = catalogCoverLabel,
                onClick = {
                    onSetAsCatalogCover(imageUrl)
                    onDismiss()
                },
            )
        }
    }

    Surface(
        color = colors.brownDeep,
        shape = if (isBottomSheet) RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp) else RoundedCornerShape(16.dp),
        modifier = if (isBottomSheet) {
            Modifier
                .fillMaxWidth()
                .clickable(
                    indication = null,
                    interactionSource = null
                ) {}
        } else {
            Modifier
                .wrapContentWidth()
                .padding(32.dp)
                .clickable(
                    indication = null,
                    interactionSource = null
                ) {}
        }
    ) {
        if (isBottomSheet) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 20.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ContextMenuActions()
                }
                Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)))
            }
        } else {
            Row(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ContextMenuActions()
            }
        }
    }
}

@Composable
private fun ContextMenuItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = Color.White,
            modifier = Modifier.size(28.dp)
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

package me.thenano.yamibo.yamibo_app.thread.reader.components.overlay

import me.thenano.yamibo.yamibo_app.i18n.i18n

import YamiboIcons
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme

/** Floating circle buttons for refresh and settings. */
@Composable
fun ReaderFloatButtons(
    visible: Boolean,
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
    onReaderMode: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val colors = YamiboTheme.colors

    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(24.dp),
        modifier = modifier
    ) {
        // Refresh & Settings: tied to overlay visibility
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(150))
        ) {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                if (onReaderMode != null) {
                    IconButton(onClick = onReaderMode, modifier = Modifier.size(56.dp)
                        .background(colors.brownPrimary.copy(alpha = 0.2f), CircleShape)) {
                        Icon(YamiboIcons.Book, contentDescription = i18n("閱讀模式"), tint = colors.brownPrimary)
                    }
                }
                IconButton(
                    onClick = onRefresh,
                    modifier = Modifier
                        .size(56.dp)
                        .background(colors.brownPrimary.copy(alpha = 0.2f), CircleShape)
                ) {
                    Icon(imageVector = YamiboIcons.Reload, contentDescription = i18n("重新整理"), tint = colors.brownPrimary, modifier = Modifier.size(24.dp))
                }
                IconButton(
                    onClick = onSettings,
                    modifier = Modifier
                        .size(56.dp)
                        .background(colors.brownPrimary.copy(alpha = 0.2f), CircleShape)
                ) {
                    Icon(imageVector = YamiboIcons.Setting, contentDescription = i18n("設定"), tint = colors.brownPrimary, modifier = Modifier.size(28.dp))
                }
            }
        }

    }
}

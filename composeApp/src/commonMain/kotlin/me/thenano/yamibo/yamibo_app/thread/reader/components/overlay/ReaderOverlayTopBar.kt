package me.thenano.yamibo.yamibo_app.thread.reader.components.overlay

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme
import me.thenano.yamibo.yamibo_app.thread.detail.novel.components.ThreadTopBar

@Composable
fun ReaderOverlayTopBar(
    visible: Boolean,
    title: String,
    onBack: () -> Unit,
    onCatalog: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = YamiboTheme.colors
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(tween(150), initialOffsetY = { -it }),
        exit = slideOutVertically(tween(150), targetOffsetY = { -it }),
        modifier = modifier.fillMaxWidth()
    ) {
        Surface(
            color = colors.brownDeep,
            shadowElevation = 4.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            ThreadTopBar(
                title = title,
                onBack = onBack,
                actions = {
                    IconButton(onClick = onCatalog) {
                        Text("☰", color = Color.White, fontSize = 22.sp)
                    }
                },
                windowInsets = WindowInsets.statusBars
            )
        }
    }
}
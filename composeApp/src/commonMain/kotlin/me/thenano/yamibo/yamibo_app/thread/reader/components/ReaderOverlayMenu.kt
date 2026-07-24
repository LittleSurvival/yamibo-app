package me.thenano.yamibo.yamibo_app.thread.reader.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.thenano.yamibo.yamibo_app.thread.reader.debug.DebugRecomposeProbe
import me.thenano.yamibo.yamibo_app.thread.reader.components.overlay.ReaderBottomBar
import me.thenano.yamibo.yamibo_app.thread.reader.components.overlay.ReaderFloatButtons
import me.thenano.yamibo.yamibo_app.thread.reader.components.overlay.ReaderOverlayTopBar
import me.thenano.yamibo.yamibo_app.thread.reader.components.overlay.ReaderSinglePageProgress

/**
 *  TopBar (CataLog)
 *
 *  BottomBar : (Reply, Share, Favorite)
 *
 *  Float Circle Button (Refresh & Settings)
 */
@Composable
internal fun ReaderOverlayMenu(
    visible: Boolean,
    title: String,
    isFavorited: Boolean,
    onBack: () -> Unit,
    onCatalog: () -> Unit,
    onFavorite: () -> Unit,
    onFavoriteLongPress: (() -> Unit)? = null,
    onShare: () -> Unit,
    onReply: () -> Unit,
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
    singlePageProgress: ReaderSinglePageProgress? = null,
    onSinglePageProgressChange: ((Int) -> Unit)? = null,
    onSinglePageProgressCommit: (() -> Unit)? = null,
    @Suppress("ModifierParameter") modifier: Modifier = Modifier
) {
    DebugRecomposeProbe("ReaderOverlayMenu", "visible=$visible")
    val floatButtonBottomPadding = if (singlePageProgress != null) 154.dp else 110.dp
    Box(modifier = modifier.fillMaxSize()) {
        // Animated top bar
        ReaderOverlayTopBar(
            visible = visible,
            title = title,
            onBack = onBack,
            onCatalog = onCatalog,
            modifier = Modifier.align(Alignment.TopCenter)
        )

        // Float Circle Buttons (Refresh & Settings)
        ReaderFloatButtons(
            visible = visible,
            onRefresh = onRefresh,
            onSettings = onSettings,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = floatButtonBottomPadding, end = 16.dp)
        )

        // Bottom action bar (Comment, Favorite, Share)
        ReaderBottomBar(
            visible = visible,
            isFavorited = isFavorited,
            onReply = onReply,
            onFavorite = onFavorite,
            onFavoriteLongPress = onFavoriteLongPress,
        onShare = onShare,
        singlePageProgress = singlePageProgress,
        onSinglePageProgressChange = onSinglePageProgressChange,
        onSinglePageProgressCommit = onSinglePageProgressCommit,
        modifier = Modifier.align(Alignment.BottomCenter)
    )
    }
}

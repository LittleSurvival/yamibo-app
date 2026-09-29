package me.thenano.yamibo.yamibo_app.components.controls

import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable internal actual fun Modifier.desktopDragScroll(state: ScrollableState, enabled: Boolean,
    reverseDirection: Boolean, flingBehavior: FlingBehavior) = this
@Composable internal actual fun Modifier.desktopRefreshShortcut(isRefreshing: Boolean, onRefresh: () -> Unit) = this

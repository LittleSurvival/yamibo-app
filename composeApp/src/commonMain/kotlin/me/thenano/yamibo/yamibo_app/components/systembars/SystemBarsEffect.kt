package me.thenano.yamibo.yamibo_app.components.systembars

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

internal val LocalSystemBarsActive = compositionLocalOf { true }

/** Retain screen state without letting an invisible screen control the host window. */
@Composable
internal fun SystemBarsScope(active: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalSystemBarsActive provides (LocalSystemBarsActive.current && active),
        content = content,
    )
}

/** Compare contrast ratios against an opaque, actually drawn system-bar background. */
internal fun useDarkSystemBarIcons(background: Color): Boolean {
    val luminance = background.luminance()
    return (luminance + 0.05f) / 0.05f >= 1.05f / (luminance + 0.05f)
}

@Composable
expect fun SystemBarsEffect(
    statusBarColor: Color,
    navigationBarColor: Color,
    priority: Int = 0,
    darkStatusBarIcons: Boolean? = null,
    darkNavigationBarIcons: Boolean? = null,
)

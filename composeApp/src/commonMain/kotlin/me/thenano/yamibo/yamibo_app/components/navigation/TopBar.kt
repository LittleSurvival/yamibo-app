package me.thenano.yamibo.yamibo_app.components.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.thenano.yamibo.yamibo_app.components.systembars.SystemBarsEffect
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme

/**
 * Standard Yamibo nested-page top bar.
 *
 * Use this for pages that live above a main tab screen and need the app's brown
 * header style, such as user-space subpages, blog reader, private-message
 * reader, WebView-like screens, and thread/detail screens. It centralizes the
 * status-bar padding, left back button sizing, title typography, and right-side
 * action alignment so feature pages do not drift visually.
 *
 * @param title Text shown in the header.
 * @param modifier Optional external modifier for the root surface.
 * @param applyStatusPadding Whether to apply status-bar padding. Set false only
 * when the parent already handles system insets.
 * @param titleAlign Use [TextAlign.Center] for chat/reader headers where the
 * title should be visually centered between actions.
 * @param titleFontSize Header title size. Most nested pages use 20sp; compact
 * chat headers can use 18sp.
 * @param onBack Optional back action. When null, no back affordance is shown.
 * @param actions Right-side icon/button content. Keep this short; large controls
 * should live below the top bar.
 */
@Composable
fun YamiboTopBar(
    title: String,
    modifier: Modifier = Modifier,
    applyStatusPadding: Boolean = true,
    titleAlign: TextAlign = TextAlign.Start,
    titleFontSize: Int = 20,
    subtitle: String? = null,
    navigationBarColor: Color? = null,
    systemBarsPriority: Int = 10,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = YamiboTheme.colors
    SystemBarsEffect(
        statusBarColor = colors.brownDeep,
        navigationBarColor = navigationBarColor ?: colors.creamBackground,
        priority = systemBarsPriority,
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.brownDeep),
    ) {
        if (applyStatusPadding) {
            androidx.compose.foundation.layout.Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsTopHeight(WindowInsets.statusBars),
            )
        }
        Surface(modifier = Modifier.fillMaxWidth(), color = colors.brownDeep, shadowElevation = 2.dp) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onBack != null) {
                    IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
                        Text(NavigationBackSymbol, color = Color.White, fontSize = 20.sp)
                    }
                }
                if (subtitle == null) {
                    Text(
                        text = title,
                        modifier = Modifier.weight(1f).padding(horizontal = if (titleAlign == TextAlign.Center) 8.dp else 4.dp),
                        color = Color.White,
                        fontSize = titleFontSize.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = titleAlign,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Column(
                        modifier = Modifier.weight(1f).padding(horizontal = if (titleAlign == TextAlign.Center) 8.dp else 4.dp),
                    ) {
                        Text(
                            text = title,
                            color = Color.White,
                            fontSize = titleFontSize.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = titleAlign,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = subtitle,
                            color = Color.White.copy(alpha = 0.68f),
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                    content = actions,
                )
            }
        }
    }
}

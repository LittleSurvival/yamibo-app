package me.thenano.yamibo.yamibo_app.components.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.thenano.yamibo.yamibo_app.components.systembars.SystemBarsEffect
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme

/**
 * Standard cream top bar for MainScreen tabs.
 *
 * Use this for top-level tabs such as Reading History, Messages, and Favorites
 * where the header sits directly below the system status bar instead of using
 * the nested brown [YamiboTopBar]. It fixes the content row to 60dp so titles,
 * chips, avatar buttons, and icon buttons keep the same vertical center while
 * switching tabs.
 *
 * @param title Main tab title.
 * @param modifier Optional root modifier.
 * @param actions Right-side actions. Keep items 36dp touch targets or compact
 * chips/buttons so they align to the standard center line.
 */
@Composable
fun YamiboMainTabTopBar(
    title: String,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = YamiboTheme.colors
    SystemBarsEffect(
        statusBarColor = colors.creamBackground,
        navigationBarColor = colors.navBarBg,
        priority = 10,
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.creamBackground),
    ) {
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsTopHeight(WindowInsets.statusBars),
        )
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp),
            color = colors.creamBackground,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textOnBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    content = actions,
                )
            }
        }
    }
}

@Composable
fun YamiboHomeTopBar(
    modifier: Modifier = Modifier,
    logo: @Composable () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = YamiboTheme.colors
    SystemBarsEffect(
        statusBarColor = colors.brownDeep,
        navigationBarColor = colors.navBarBg,
        priority = 10,
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.brownDeep)
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            colors.brownDeep,
                            colors.brownPrimary,
                            colors.creamBackground,
                        )
                    )
                )
                .padding(horizontal = 16.dp, vertical = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            logo()
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
                content = actions,
            )
        }
    }
}

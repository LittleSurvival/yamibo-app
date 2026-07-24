package me.thenano.yamibo.yamibo_app.thread.reader.components.overlay

import YamiboIcons
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import me.thenano.yamibo.yamibo_app.components.theme.YamiboColors
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme
import me.thenano.yamibo.yamibo_app.components.theme.contrastAgainst
import me.thenano.yamibo.yamibo_app.i18n.i18n
import kotlin.math.abs

/** Floating reader jump button that mirrors Yamibo mobile's top/bottom scroll control. */
@Composable
fun ReaderScrollJumpButton(
    visible: Boolean,
    pointsDown: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = YamiboTheme.colors
    val buttonColors = colors.readerScrollJumpButtonColors()
    val rotation by animateFloatAsState(
        targetValue = if (pointsDown) 180f else 0f,
        label = "reader_scroll_jump_button_rotation",
    )

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(buttonColors.background)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = YamiboIcons.ChevronUp,
                contentDescription = if (pointsDown) i18n("跳到底部") else i18n("跳到頂部"),
                tint = buttonColors.icon,
                modifier = Modifier
                    .size(24.dp)
                    .rotate(rotation),
            )
        }
    }
}

private data class ReaderScrollJumpButtonColors(
    val background: Color,
    val icon: Color,
)

private fun YamiboColors.readerScrollJumpButtonColors(): ReaderScrollJumpButtonColors {
    if (creamBackground == Color(0xFFFFF3D6)) {
        return ReaderScrollJumpButtonColors(
            background = Color(0xFFFFE6B7).copy(alpha = 0.92f),
            icon = brownDeep.copy(alpha = 0.58f),
        )
    }

    val targetContrast = minOf(
        Color(0xFFFFE6B7).contrastAgainst(Color(0xFFFFF3D6)),
        Color(0xFFFFE6B7).contrastAgainst(Color(0xFFFFF7E0)),
    )
    val backgroundCandidates = listOf(
        navBarBg,
        brownLight,
        announceBg,
        pinnedBg,
        brownPrimary,
        orangeAccent,
        creamSurface,
    ).distinct()
    val background = (backgroundCandidates.filter { color ->
        color.readerBackgroundContrast(this) >= 1.08
    }.ifEmpty {
        backgroundCandidates
    }).minBy { color ->
        abs(color.readerBackgroundContrast(this) - targetContrast)
    }.copy(alpha = 0.92f)

    val icon = listOf(
        textOnSurface,
        textDark,
        textStrong,
        brownDeep,
        Color.White,
        Color.Black,
    ).maxBy { it.contrastAgainst(background) }

    return ReaderScrollJumpButtonColors(
        background = background,
        icon = icon.copy(alpha = 0.82f),
    )
}

private fun Color.readerBackgroundContrast(colors: YamiboColors): Double =
    minOf(
        contrastAgainst(colors.creamBackground),
        contrastAgainst(colors.creamSurface),
    )

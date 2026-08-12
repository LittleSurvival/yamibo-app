package me.thenano.yamibo.yamibo_app.components.controls

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme

/**
 * Reusable low-emphasis filter/sort chip.
 *
 * Use for option triggers such as Favorite sort/layout, Forum filter/order,
 * UserSpace blog filters, reader settings chips, and other compact controls
 * that open a dialog or immediately select a mode. For primary actions, use
 * [YamiboPrimaryButton] instead.
 *
 * @param text Label rendered inside the chip.
 * @param onClick Called when the chip is tapped.
 * @param selected Whether the chip should use the selected/brown-filled style.
 */
@Composable
fun YamiboActionChip(
    text: String,
    onClick: () -> Unit,
    selected: Boolean = false,
    enabled: Boolean = true,
) {
    val colors = YamiboTheme.colors
    val shape = RoundedCornerShape(10.dp)
    Text(
        text,
        color = if (selected) colors.textOnDeepHigh else colors.textOnTint,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .alpha(if (enabled) 1f else 0.4f)
            .clip(shape)
            .background(if (selected) colors.brownDeep else colors.brownPrimary.copy(alpha = 0.12f))
            .border(BorderStroke(1.dp, colors.brownPrimary.copy(alpha = 0.12f)), shape)
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 10.dp, vertical = 7.dp),
    )
}

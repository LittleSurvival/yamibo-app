package me.thenano.yamibo.yamibo_app.components.controls

import me.thenano.yamibo.yamibo_app.i18n.i18n

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme

/**
 * Single option row used by [YamiboSingleSelectDialog] and
 * [YamiboMultiSelectDialog].
 *
 * Use directly only when a selector is embedded in a custom dialog. Prefer the
 * dialog composables for full popout screens so selected row spacing, color,
 * and typography stay consistent across the app.
 */
@Composable
fun YamiboSingleSelectRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    selectedText: String = i18n("已選擇"),
    enabled: Boolean = true,
) {
    val colors = YamiboTheme.colors
    Surface(
        onClick = { if (enabled) onClick() },
        shape = RoundedCornerShape(12.dp),
        color = if (selected && enabled) colors.brownPrimary.copy(alpha = 0.12f) else Color.Transparent,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                color = if (enabled) colors.textDark else colors.textDark.copy(alpha = 0.42f),
                fontSize = 14.sp,
                modifier = Modifier.weight(1f),
            )
            if (selected) {
                Text(selectedText, color = colors.textStrong, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

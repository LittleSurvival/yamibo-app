package me.thenano.yamibo.yamibo_app.profile.settings.bound

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.thenano.yamibo.yamibo_app.LocalMangaReaderSettingsRepository
import me.thenano.yamibo.yamibo_app.LocalNovelReaderSettingsRepository
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme
import me.thenano.yamibo.yamibo_app.i18n.i18n
import me.thenano.yamibo.yamibo_app.util.state

@Composable
fun MangaReverseTouchZonesSetting(
    dark: Boolean = false,
    onValueChanged: () -> Unit = {},
) {
    val repository = LocalMangaReaderSettingsRepository.current
    val enabled = repository.reverseTouchZones.state()
    ReverseTouchZonesSetting(
        enabled = enabled,
        dark = dark,
        onEnabledChange = {
            repository.reverseTouchZones.setValue(it)
            onValueChanged()
        },
    )
}

@Composable
fun ThreadReaderReverseTouchZonesSetting() {
    val repository = LocalNovelReaderSettingsRepository.current
    val enabled = repository.threadReverseTouchZones.state()
    ReverseTouchZonesSetting(
        enabled = enabled,
        dark = false,
        onEnabledChange = { repository.threadReverseTouchZones.setValue(it) },
    )
}

@Composable
private fun ReverseTouchZonesSetting(
    enabled: Boolean,
    dark: Boolean,
    onEnabledChange: (Boolean) -> Unit,
) {
    val colors = YamiboTheme.colors
    val textColor = if (dark) Color.White.copy(alpha = 0.8f) else colors.textDark

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onEnabledChange(!enabled) }
            .padding(vertical = 12.dp, horizontal = if (dark) 0.dp else 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = i18n("反轉觸控區域"),
            color = textColor,
            fontSize = if (dark) 13.sp else 16.sp,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = enabled,
            onCheckedChange = onEnabledChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = if (dark) colors.brownPrimary else colors.brownDeep,
                checkedTrackColor = colors.brownPrimary.copy(alpha = 0.5f),
                uncheckedThumbColor = if (dark) Color.White.copy(alpha = 0.65f) else colors.textDark.copy(alpha = 0.5f),
                uncheckedTrackColor = if (dark) Color.White.copy(alpha = 0.2f) else colors.brownLight.copy(alpha = 0.3f),
            ),
        )
    }
}

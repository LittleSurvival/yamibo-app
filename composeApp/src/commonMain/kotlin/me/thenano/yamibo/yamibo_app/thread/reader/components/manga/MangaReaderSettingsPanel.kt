package me.thenano.yamibo.yamibo_app.thread.reader.components.manga

import me.thenano.yamibo.yamibo_app.i18n.i18n

import me.thenano.yamibo.yamibo_app.i18n.localizedLabel

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme
import me.thenano.yamibo.yamibo_app.profile.settings.bound.MangaReverseTouchZonesSetting
import me.thenano.yamibo.yamibo_app.repository.settings.EffectiveReadingModeSource
import me.thenano.yamibo.yamibo_app.repository.settings.ReadingMode
import me.thenano.yamibo.yamibo_app.repository.settings.TouchZoneLayout

/**
 * Settings panel for the manga reader.
 * Slides up from the bottom and contains reading mode and touch zone layout options.
 */
@Composable
fun MangaReaderSettingsPanel(
    visible: Boolean,
    currentReadingMode: ReadingMode,
    currentTouchZoneLayout: TouchZoneLayout,
    readingModeSource: EffectiveReadingModeSource,
    threadModeOverrideEnabled: Boolean,
    onReadingModeChange: (ReadingMode) -> Unit,
    onThreadModeOverrideEnabledChange: (Boolean) -> Unit,
    onTouchZoneLayoutChange: (TouchZoneLayout) -> Unit,
    onReverseTouchZonesChanged: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = YamiboTheme.colors

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { it }),
        exit = slideOutVertically(targetOffsetY = { it }),
        modifier = modifier
    ) {
        Surface(
            color = colors.brownDeep,
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            modifier = Modifier
                .fillMaxWidth()
                // Prevent touches from falling through to the scrim
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {}
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                val lockedByTag = readingModeSource == EffectiveReadingModeSource.CatalogLongStrip
                // Reading mode
                SectionTitle(i18n("閱讀模式"))
                if (lockedByTag) {
                    Text(
                        text = i18n("由標籤條漫模式鎖定"),
                        color = Color.White.copy(alpha = 0.72f),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(bottom = 10.dp)
                    )
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Text(
                            text = i18n("為此作品鎖定閱讀模式"),
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 13.sp,
                        )
                        Switch(
                            checked = threadModeOverrideEnabled,
                            onCheckedChange = onThreadModeOverrideEnabledChange,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = colors.brownPrimary,
                                checkedTrackColor = colors.brownPrimary.copy(alpha = 0.45f),
                            ),
                        )
                    }
                }
                ChipGroup(
                    options = ReadingMode.entries.toList(),
                    selectedOption = currentReadingMode,
                    labelExtractor = { it.localizedLabel() },
                    enabled = !lockedByTag,
                    onSelect = onReadingModeChange
                )

                Spacer(Modifier.height(20.dp))

                // Touch zone layout
                SectionTitle(i18n("輕觸區域"))
                ChipGroup(
                    options = TouchZoneLayout.entries.toList(),
                    selectedOption = currentTouchZoneLayout,
                    labelExtractor = { it.localizedLabel() },
                    onSelect = onTouchZoneLayoutChange
                )

                MangaReverseTouchZonesSetting(
                    dark = true,
                    onValueChanged = onReverseTouchZonesChanged,
                )

                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        color = Color.White,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 10.dp)
    )
}

@Composable
private fun <T> ChipGroup(
    options: List<T>,
    selectedOption: T,
    labelExtractor: (T) -> String,
    enabled: Boolean = true,
    onSelect: (T) -> Unit
) {
    // Use FlowRow-like layout with wrapping
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val chunked = options.chunked(3)
        chunked.forEach { rowItems ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                rowItems.forEach { option ->
                    Chip(
                        option = option,
                        isSelected = option == selectedOption,
                        label = labelExtractor(option),
                        enabled = enabled,
                        onSelect = onSelect
                    )
                }
            }
        }
    }
}

@Composable
private fun <T> Chip(
    option: T,
    isSelected: Boolean,
    label: String,
    enabled: Boolean,
    onSelect: (T) -> Unit
) {
    val colors = YamiboTheme.colors
    val bgColor = when {
        isSelected -> colors.brownPrimary
        enabled -> Color.White.copy(alpha = 0.1f)
        else -> Color.White.copy(alpha = 0.06f)
    }
    val textColor = when {
        isSelected -> Color.White
        enabled -> Color.White.copy(alpha = 0.8f)
        else -> Color.White.copy(alpha = 0.42f)
    }

    Text(
        text = label,
        color = textColor,
        fontSize = 13.sp,
        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bgColor)
            .clickable(enabled = enabled) { onSelect(option) }
            .padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

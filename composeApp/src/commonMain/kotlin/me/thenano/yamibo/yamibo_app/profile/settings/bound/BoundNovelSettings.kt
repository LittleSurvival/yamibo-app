package me.thenano.yamibo.yamibo_app.profile.settings.bound

import me.thenano.yamibo.yamibo_app.i18n.i18n

import me.thenano.yamibo.yamibo_app.i18n.localizedLabel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.thenano.yamibo.yamibo_app.LocalNovelReaderSettingsRepository
import me.thenano.yamibo.yamibo_app.profile.settings.components.SettingsChipRow
import me.thenano.yamibo.yamibo_app.profile.settings.components.SettingsSlider
import me.thenano.yamibo.yamibo_app.repository.settings.ReaderChineseConversionOption
import me.thenano.yamibo.yamibo_app.repository.settings.ReaderScrollButtonDisplayMode
import me.thenano.yamibo.yamibo_app.repository.settings.ReaderScrollButtonJumpTarget
import me.thenano.yamibo.yamibo_app.repository.settings.ThreadReaderMode
import me.thenano.yamibo.yamibo_app.repository.settings.TouchZoneLayout
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme
import me.thenano.yamibo.yamibo_app.thread.reader.components.post.impl.HtmlRenderer
import me.thenano.yamibo.yamibo_app.util.state

import kotlin.math.roundToInt

private val PREVIEW_TEXT: String
    get() = i18n("我是YamiboApp的作者TheNano，這是一個個人獨立開發的第三方開源App<br>")

@Composable
fun NovelReaderPreviewSetting() {
    val colors = YamiboTheme.colors
    val novelSettingsRepo = LocalNovelReaderSettingsRepository.current
    val contentWidthFraction = novelSettingsRepo.contentWidthFraction.state()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.creamSurface)
            .padding(16.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(contentWidthFraction)
        ) {
            HtmlRenderer(html = PREVIEW_TEXT)
        }
    }
}

@Composable
fun NovelFontSizeSetting() {
    val novelSettingsRepo = LocalNovelReaderSettingsRepository.current
    val fontSize = novelSettingsRepo.fontSize.state()

    SettingsSlider(
        label = i18n("文字大小"),
        value = fontSize.toFloat(),
        valueRange = 10f..40f,
        steps = 29,
        valueDisplay = { "${it.toInt()} sp" },
        onValueChange = { novelSettingsRepo.fontSize.setValue(it.toInt()) }
    )
}

@Composable
fun NovelLineSpacingSetting() {
    val novelSettingsRepo = LocalNovelReaderSettingsRepository.current
    val lineSpacing = novelSettingsRepo.lineSpacing.state()

    SettingsSlider(
        label = i18n("行距比例"),
        value = lineSpacing,
        valueRange = 1.0f..3.0f,
        steps = 39,
        valueDisplay = { "${(it * 100f).roundToInt() / 100f}x" },
        onValueChange = { novelSettingsRepo.lineSpacing.setValue(it) }
    )
}

@Composable
fun NovelContentWidthSetting() {
    val novelSettingsRepo = LocalNovelReaderSettingsRepository.current
    val contentWidthFraction = novelSettingsRepo.contentWidthFraction.state()

    SettingsSlider(
        label = i18n("內容寬度"),
        value = contentWidthFraction,
        valueRange = 0.6f..1.0f,
        steps = 39,
        valueDisplay = { "${(it * 100f).roundToInt()}%" },
        onValueChange = { novelSettingsRepo.contentWidthFraction.setValue(it) }
    )
}

@Composable
fun NovelSystemBarsBackgroundSetting() {
    val colors = YamiboTheme.colors
    val novelSettingsRepo = LocalNovelReaderSettingsRepository.current
    val enabled = novelSettingsRepo.keepSystemBarsBackground.state()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { novelSettingsRepo.keepSystemBarsBackground.setValue(!enabled) }
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = i18n("保留系統列背景"),
                fontSize = 16.sp,
                color = colors.textDark,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = i18n("在狀態列與底部導覽列後方使用閱讀背景，避免透明系統列蓋住內容。"),
                fontSize = 13.sp,
                color = colors.textDark.copy(alpha = 0.6f),
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = { novelSettingsRepo.keepSystemBarsBackground.setValue(it) },
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.brownDeep,
                checkedTrackColor = colors.brownPrimary.copy(alpha = 0.5f),
                uncheckedThumbColor = colors.textDark.copy(alpha = 0.5f),
                uncheckedTrackColor = colors.brownLight.copy(alpha = 0.3f),
            ),
        )
    }
}

@Composable
fun NovelChineseConversionSetting() {
    val novelSettingsRepo = LocalNovelReaderSettingsRepository.current
    val chineseConversion = novelSettingsRepo.chineseConversion.state()

    SettingsChipRow(
        options = ReaderChineseConversionOption.entries.map { it to it.localizedLabel() },
        selectedValue = chineseConversion,
        onSelect = { novelSettingsRepo.chineseConversion.setValue(it) },
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

@Composable
fun ThreadReaderModeSetting() {
    val novelSettingsRepo = LocalNovelReaderSettingsRepository.current
    val readerMode = novelSettingsRepo.threadReaderMode.state()

    SettingsChipRow(
        options = ThreadReaderMode.entries.map { it to it.localizedLabel() },
        selectedValue = readerMode,
        onSelect = { novelSettingsRepo.threadReaderMode.setValue(it) },
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

@Composable
fun ThreadReaderTouchZoneSetting() {
    val novelSettingsRepo = LocalNovelReaderSettingsRepository.current
    val touchZone = novelSettingsRepo.threadTouchZone.state()

    SettingsChipRow(
        options = TouchZoneLayout.entries.map { it to it.localizedLabel() },
        selectedValue = touchZone,
        onSelect = { novelSettingsRepo.threadTouchZone.setValue(it) },
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

@Composable
fun NovelScrollButtonDisplayModeSetting() {
    val novelSettingsRepo = LocalNovelReaderSettingsRepository.current
    val displayMode = novelSettingsRepo.scrollButtonDisplayMode.state()

    SettingsChipRow(
        options = ReaderScrollButtonDisplayMode.entries.map { it to it.localizedLabel() },
        selectedValue = displayMode,
        onSelect = { novelSettingsRepo.scrollButtonDisplayMode.setValue(it) },
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

@Composable
fun NovelScrollButtonThresholdSetting() {
    val novelSettingsRepo = LocalNovelReaderSettingsRepository.current
    val threshold = novelSettingsRepo.scrollButtonDirectionThreshold.state()

    SettingsSlider(
        label = i18n("方向切換距離"),
        value = threshold.toFloat(),
        valueRange = 100f..2000f,
        steps = 37,
        valueDisplay = { "${it.toInt()} px" },
        onValueChange = { novelSettingsRepo.scrollButtonDirectionThreshold.setValue(it.toInt()) },
    )
}

@Composable
fun NovelScrollButtonJumpTargetSetting() {
    val novelSettingsRepo = LocalNovelReaderSettingsRepository.current
    val jumpTarget = novelSettingsRepo.scrollButtonJumpTarget.state()

    SettingsChipRow(
        options = ReaderScrollButtonJumpTarget.entries.map { it to it.localizedLabel() },
        selectedValue = jumpTarget,
        onSelect = { novelSettingsRepo.scrollButtonJumpTarget.setValue(it) },
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

@Composable
fun NovelPageProgressHintSetting() {
    val colors = YamiboTheme.colors
    val novelSettingsRepo = LocalNovelReaderSettingsRepository.current
    val enabled = novelSettingsRepo.showPageProgressHint.state()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { novelSettingsRepo.showPageProgressHint.setValue(!enabled) }
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = i18n("顯示閱讀進度提示"),
                fontSize = 16.sp,
                color = colors.textDark,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = i18n("在右下角以小字顯示目前頁內閱讀進度。"),
                fontSize = 13.sp,
                color = colors.textDark.copy(alpha = 0.6f),
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = { novelSettingsRepo.showPageProgressHint.setValue(it) },
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.brownDeep,
                checkedTrackColor = colors.brownPrimary.copy(alpha = 0.5f),
                uncheckedThumbColor = colors.textDark.copy(alpha = 0.5f),
                uncheckedTrackColor = colors.brownLight.copy(alpha = 0.3f),
            ),
        )
    }
}

private fun ReaderScrollButtonDisplayMode.localizedLabel(): String = when (this) {
    ReaderScrollButtonDisplayMode.ALWAYS -> i18n("總是顯示")
    ReaderScrollButtonDisplayMode.WHEN_USER_SLIDE -> i18n("滑動時顯示")
    ReaderScrollButtonDisplayMode.NEVER -> i18n("不顯示")
}

private fun ReaderScrollButtonJumpTarget.localizedLabel(): String = when (this) {
    ReaderScrollButtonJumpTarget.PAGE_EDGE -> i18n("該頁頂/底部")
    ReaderScrollButtonJumpTarget.POST_EDGE -> i18n("該樓頂/底部")
}


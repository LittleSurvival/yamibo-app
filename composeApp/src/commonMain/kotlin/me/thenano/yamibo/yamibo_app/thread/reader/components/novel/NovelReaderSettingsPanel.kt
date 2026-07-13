package me.thenano.yamibo.yamibo_app.thread.reader.components.novel

import me.thenano.yamibo.yamibo_app.i18n.i18n

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.thenano.yamibo.yamibo_app.repository.settings.AppSettingsRepository
import me.thenano.yamibo.yamibo_app.profile.settings.components.ThemeSelectorContent
import me.thenano.yamibo.yamibo_app.profile.settings.bound.NovelChineseConversionSetting
import me.thenano.yamibo.yamibo_app.profile.settings.bound.NovelFontSizeSetting
import me.thenano.yamibo.yamibo_app.profile.settings.bound.NovelLineSpacingSetting
import me.thenano.yamibo.yamibo_app.profile.settings.bound.NovelPageProgressHintSetting
import me.thenano.yamibo.yamibo_app.profile.settings.bound.NovelScrollButtonDisplayModeSetting
import me.thenano.yamibo.yamibo_app.profile.settings.bound.NovelScrollButtonJumpTargetSetting
import me.thenano.yamibo.yamibo_app.profile.settings.bound.NovelScrollButtonThresholdSetting
import me.thenano.yamibo.yamibo_app.profile.settings.bound.NovelSystemBarsBackgroundSetting
import me.thenano.yamibo.yamibo_app.profile.settings.bound.MangaTouchZoneSetting
import me.thenano.yamibo.yamibo_app.profile.settings.bound.ReaderFontSelectorSetting
import me.thenano.yamibo.yamibo_app.profile.settings.bound.ThreadReaderModeSetting
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme
import me.thenano.yamibo.yamibo_app.util.state

@Composable
fun NovelReaderSettingsPanel(
    visible: Boolean,
    appSettingsRepo: AppSettingsRepository,
    modifier: Modifier = Modifier
) {
    val colors = YamiboTheme.colors
    val themeMode = appSettingsRepo.themeMode.state()
    val themeScheme = appSettingsRepo.themeScheme.state()

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { it }),
        exit = slideOutVertically(targetOffsetY = { it }),
        modifier = modifier
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val panelMaxHeight = maxHeight * 0.6f

            Surface(
                color = colors.creamSurface,
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = panelMaxHeight)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {}
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 20.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    // Typography Section
                    SectionTitle(i18n("文字排版"), color = colors.textDark)

                    Spacer(Modifier.height(8.dp))
                    NovelFontSizeSetting()

                    Spacer(Modifier.height(16.dp))
                    NovelLineSpacingSetting()

                    Spacer(Modifier.height(16.dp))
                    ReaderFontSelectorSetting()

                    Spacer(Modifier.height(16.dp))
                    NovelSystemBarsBackgroundSetting()

                    Spacer(Modifier.height(24.dp))

                    SectionTitle(i18n("簡繁轉換"), color = colors.textDark)
                    NovelChineseConversionSetting()

                    Spacer(Modifier.height(24.dp))

                    SectionTitle("${i18n("閱讀模式")} (beta測試)", color = colors.textDark)
                    ThreadReaderModeSetting()

                    Spacer(Modifier.height(24.dp))

                    SectionTitle(i18n("觸控分區"), color = colors.textDark)
                    MangaTouchZoneSetting()

                    Spacer(Modifier.height(24.dp))

                    SectionTitle(i18n("浮動跳轉按鈕"), color = colors.textDark)
                    NovelScrollButtonDisplayModeSetting()

                    Spacer(Modifier.height(16.dp))
                    NovelScrollButtonThresholdSetting()

                    Spacer(Modifier.height(16.dp))
                    NovelScrollButtonJumpTargetSetting()

                    Spacer(Modifier.height(24.dp))

                    SectionTitle(i18n("閱讀進度"), color = colors.textDark)
                    NovelPageProgressHintSetting()

                    Spacer(Modifier.height(24.dp))

                    // Theme Section
                    ThemeSelectorContent(
                        currentMode = themeMode,
                        currentScheme = themeScheme,
                        onModeChange = { appSettingsRepo.themeMode.setValue(it) },
                        onSchemeChange = { appSettingsRepo.themeScheme.setValue(it) }
                    )

                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String, color: Color) {
    Text(
        text = text,
        color = color,
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 12.dp)
    )
}

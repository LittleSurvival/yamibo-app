package me.thenano.yamibo.yamibo_app.profile.settings.bound

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import me.thenano.yamibo.yamibo_app.LocalAppSettingsRepository
import me.thenano.yamibo.yamibo_app.LocalFontRepository
import me.thenano.yamibo.yamibo_app.components.font.getFontFamily
import me.thenano.yamibo.yamibo_app.LocalNovelReaderSettingsRepository
import me.thenano.yamibo.yamibo_app.components.controls.YamiboActionChip
import me.thenano.yamibo.yamibo_app.i18n.i18n
import me.thenano.yamibo.yamibo_app.repository.font.FontLoadResult
import me.thenano.yamibo.yamibo_app.repository.font.LoadedFont
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme
import me.thenano.yamibo.yamibo_app.util.state

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun FontManagementSetting(
    feedbackController: me.thenano.yamibo.yamibo_app.feedback.AppFeedbackController,
    modifier: Modifier = Modifier,
) {
    val fontRepository = LocalFontRepository.current
    val fonts by fontRepository.fonts.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    val colors = YamiboTheme.colors

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = i18n("載入 .ttf 或 .otf 字體後，可分別套用到 App 介面與小說閱讀器。"),
            color = colors.textDark.copy(alpha = 0.66f),
            fontSize = 13.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(10.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 4.dp),
        ) {
            FontFilePickerButton(
                enabled = fontRepository.platformSupportsFontLoading,
                onPicked = { sourceUri, displayName ->
                    coroutineScope.launch {
                        when (val result = fontRepository.loadFontFile(sourceUri, displayName)) {
                            is FontLoadResult.Success -> feedbackController.post(
                                i18n("已載入字體：{}", result.font.name)
                            )

                            is FontLoadResult.Unsupported -> feedbackController.post(i18n(result.message))
                            is FontLoadResult.Failure -> feedbackController.post(i18n(result.message))
                        }
                    }
                },
                onUnavailable = {
                    coroutineScope.launch {
                        feedbackController.post(
                            i18n(fontRepository.platformUnavailableMessage ?: "此平台暫不支援載入字體")
                        )
                    }
                },
            )
            fonts.forEach { font ->
                YamiboActionChip(
                    text = i18n("刪除 {}", font.name),
                    onClick = {
                        fontRepository.deleteFont(font.id)
                        coroutineScope.launch {
                            feedbackController.post(i18n("已刪除字體：{}", font.name))
                        }
                    },
                )
            }
        }
    }
}

@Composable
fun AppFontSelectorSetting() {
    val appSettingsRepository = LocalAppSettingsRepository.current
    val selectedId = appSettingsRepository.appFontId.state()
    FontSelectorSetting(
        title = i18n("選擇 App 字體"),
        selectedId = selectedId,
        onSelect = { appSettingsRepository.appFontId.setValue(it) },
    )
}

@Composable
fun ReaderFontSelectorSetting(showTip: Boolean = true) {
    val readerSettingsRepository = LocalNovelReaderSettingsRepository.current
    val selectedId = readerSettingsRepository.readerFontId.state()
    FontSelectorSetting(
        title = i18n("選擇閱讀器字體"),
        selectedId = selectedId,
        onSelect = { readerSettingsRepository.readerFontId.setValue(it) },
        tip = if (showTip) i18n("可在外觀新增字體") else null,
    )
}

@Composable
private fun FontSelectorSetting(
    title: String,
    selectedId: String,
    onSelect: (String) -> Unit,
    tip: String? = null,
) {
    val fontRepository = LocalFontRepository.current
    val fonts by fontRepository.fonts.collectAsState()
    val colors = YamiboTheme.colors
    val options = listOf<LoadedFont?>(null) + fonts
    val selectedFont = options.firstOrNull { it.orEmptyId() == selectedId }
    var showDialog by rememberSaveable { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Text(
            text = title,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = colors.textDark,
        )
        Spacer(Modifier.height(6.dp))
        FontSelectorTriggerCard(
            label = selectedFont?.name ?: i18n("系統預設"),
            fontFamily = selectedFont?.let { fontRepository.getFontFamily(it.id) },
            onClick = { showDialog = true },
        )
        if (tip != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = tip,
                color = colors.textDark.copy(alpha = 0.58f),
                fontSize = 12.sp,
                lineHeight = 16.sp,
            )
        }
    }

    if (showDialog) {
        FontSelectorDialog(
            title = title,
            options = options,
            selectedId = selectedId,
            onDismiss = { showDialog = false },
            onSelect = { id ->
                onSelect(id)
                showDialog = false
            },
        )
    }
}

@Composable
private fun FontSelectorTriggerCard(
    label: String,
    fontFamily: FontFamily?,
    onClick: () -> Unit,
) {
    val colors = YamiboTheme.colors
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = colors.creamSurface,
        border = BorderStroke(1.dp, colors.brownPrimary.copy(alpha = 0.14f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    color = colors.textStrong,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = fontFamily,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = i18n("點擊選擇字體並預覽效果"),
                    color = colors.textDark.copy(alpha = 0.58f),
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    fontFamily = fontFamily,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = ">",
                color = colors.textOnTint,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}

@Composable
private fun FontSelectorDialog(
    title: String,
    options: List<LoadedFont?>,
    selectedId: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val colors = YamiboTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                color = colors.textStrong,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(options) { font ->
                    val id = font.orEmptyId()
                    FontPreviewCard(
                        font = font,
                        selected = id == selectedId,
                        onClick = { onSelect(id) },
                    )
                }
            }
        },
        confirmButton = {
            YamiboActionChip(i18n("關閉"), onDismiss)
        },
        dismissButton = {},
        containerColor = colors.creamSurface,
        titleContentColor = colors.textStrong,
        textContentColor = colors.textDark,
    )
}

@Composable
private fun FontPreviewCard(
    font: LoadedFont?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val fontRepository = LocalFontRepository.current
    val colors = YamiboTheme.colors
    val fontFamily = font?.let { fontRepository.getFontFamily(it.id) }
    val selectedBorder = colors.brownPrimary.copy(alpha = 0.45f)
    val normalBorder = colors.brownPrimary.copy(alpha = 0.12f)

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (selected) colors.brownPrimary.copy(alpha = 0.12f) else colors.creamBackground,
        border = BorderStroke(1.dp, if (selected) selectedBorder else normalBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .clickable(onClick = onClick)
                .padding(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = font?.name ?: i18n("系統預設"),
                        color = colors.textStrong,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (font != null) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = font.fileName,
                            color = colors.textDark.copy(alpha = 0.56f),
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (selected) {
                    Text(
                        text = i18n("已選擇"),
                        color = colors.textStrong,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = colors.creamSurface.copy(alpha = 0.72f),
                border = BorderStroke(1.dp, colors.brownPrimary.copy(alpha = 0.08f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Text(
                        text = i18n("百合會論壇 Yamibo App 字體預覽"),
                        color = colors.textDark,
                        fontSize = 16.sp,
                        lineHeight = 22.sp,
                        fontFamily = fontFamily,
                    )
                }
            }
        }
    }
}

private fun LoadedFont?.orEmptyId(): String = this?.id.orEmpty()

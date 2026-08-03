package me.thenano.yamibo.yamibo_app.profile.settings.backup

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import me.thenano.yamibo.yamibo_app.LocalAppSettingsRepository
import me.thenano.yamibo.yamibo_app.LocalBackupRepository
import me.thenano.yamibo.yamibo_app.LocalBackupScheduler
import me.thenano.yamibo.yamibo_app.Logger
import me.thenano.yamibo.yamibo_app.components.navigation.YamiboTopBar
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme
import me.thenano.yamibo.yamibo_app.i18n.i18n
import me.thenano.yamibo.yamibo_app.i18n.localizedLabel
import me.thenano.yamibo.yamibo_app.navigation.LocalNavigator
import me.thenano.yamibo.yamibo_app.profile.settings.components.SettingsChipRow
import me.thenano.yamibo.yamibo_app.repository.BackupRepository
import me.thenano.yamibo.yamibo_app.repository.settings.BackupInterval
import me.thenano.yamibo.yamibo_app.util.formatStorageSize
import me.thenano.yamibo.yamibo_app.util.state
import kotlin.math.roundToInt

@Composable
internal fun BackupSettingsScreen() {
    val colors = YamiboTheme.colors
    val navigator = LocalNavigator.current
    val repository = LocalBackupRepository.current
    val scheduler = LocalBackupScheduler.current
    val appSettingsRepository = LocalAppSettingsRepository.current
    val feedbackController = me.thenano.yamibo.yamibo_app.LocalAppFeedbackController.current
    val coroutineScope = rememberCoroutineScope()
    val backupInterval = appSettingsRepository.backupInterval.state()
    val maxAutoFiles = appSettingsRepository.backupMaxAutoFiles.state()

    var folderLabel by remember { mutableStateOf<String?>(null) }
    var storageBytes by remember { mutableLongStateOf(0L) }
    var backupFiles by remember { mutableStateOf<List<BackupRepository.BackupFileInfo>>(emptyList()) }
    var working by remember { mutableStateOf(false) }
    var pendingRestoreUri by remember { mutableStateOf<String?>(null) }
    var showCreateBackupDialog by remember { mutableStateOf(false) }

    suspend fun refresh() {
        folderLabel = repository.getSelectedFolderLabel()
        storageBytes = repository.getBackupStorageBytes()
        backupFiles = repository.listBackupFiles()
    }

    val fileActions = rememberBackupFileActions(
        onFolderSelected = { uri ->
            coroutineScope.launch {
                repository.setSelectedFolder(uri)
                    .onSuccess {
                        refresh()
                        feedbackController.post(i18n("已選擇備份資料夾"))
                    }
                    .onFailure { error ->
                        Logger.e("BackupSettingsScreen", "Failed to select backup folder", error)
                        feedbackController.post(error.message ?: i18n("無法選擇備份資料夾"))
                    }
            }
        },
        onBackupPicked = { uri -> pendingRestoreUri = uri },
    )

    LaunchedEffect(Unit) { refresh() }

    Scaffold(
        topBar = {
            YamiboTopBar(
                title = i18n("本地資料備份"),
                titleFontSize = 18,
                onBack = { navigator.pop() },
            )
        },
        containerColor = colors.creamBackground,
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            BackupInfoCard(
                folderLabel = folderLabel,
                storageBytes = storageBytes,
                backupCount = backupFiles.size,
                onSelectFolder = fileActions.selectFolder,
            )
            Text(
                text = i18n("備份範圍包含收藏、設定、筆記、書籤、歷史進度與更新紀錄。"),
                fontSize = 13.sp,
                color = colors.textDark.copy(alpha = 0.68f),
            )

            BackupSettingCard(
                interval = backupInterval,
                onIntervalChange = { interval ->
                    appSettingsRepository.backupInterval.setValue(interval)
                    coroutineScope.launch { scheduler.schedule(interval) }
                },
                maxAutoFiles = maxAutoFiles,
                onMaxAutoFilesChange = { appSettingsRepository.backupMaxAutoFiles.setValue(it) },
            )

            BackupActionCard(
                working = working,
                onCreateBackup = { showCreateBackupDialog = true },
                onLoadBackup = fileActions.pickBackupFile,
            )

            if (backupFiles.isNotEmpty()) {
                BackupFileListCard(backupFiles)
            }
        }
    }

    pendingRestoreUri?.let { uri ->
        RestoreModeDialog(
            onDismiss = { pendingRestoreUri = null },
            onSelect = { mode ->
                pendingRestoreUri = null
                coroutineScope.launch {
                    working = true
                    repository.restoreBackup(uri, mode)
                        .onSuccess {
                            refresh()
                            feedbackController.post(restoreSummaryText(it))
                        }
                        .onFailure { error ->
                            Logger.e("BackupSettingsScreen", "Failed to restore backup", error)
                            feedbackController.post(error.message ?: i18n("還原備份失敗"))
                        }
                    working = false
                }
            },
        )
    }

    if (showCreateBackupDialog) {
        CreateBackupDialog(
            onDismiss = { showCreateBackupDialog = false },
            onConfirm = { name ->
                showCreateBackupDialog = false
                coroutineScope.launch {
                    working = true
                    repository.createBackup(automatic = false, customName = name.takeIf { it.isNotBlank() })
                        .onSuccess {
                            refresh()
                            feedbackController.post(i18n("已建立備份：{}", it.name))
                        }
                        .onFailure { error ->
                            Logger.e("BackupSettingsScreen", "Failed to create backup", error)
                            feedbackController.post(error.message ?: i18n("建立備份失敗"))
                        }
                    working = false
                }
            },
        )
    }
}

internal fun restoreSummaryText(summary: BackupRepository.RestoreSummary): String {
    val base = i18n(
        "還原完成：收藏 {}，設定 {}，歷史進度 {}，更新紀錄 {}",
        summary.favorites,
        summary.settings,
        summary.readingHistory,
        summary.updateRecords,
    )
    return if (summary.skippedRecords > 0) {
        "$base；${i18n("略過無法對應的輔助紀錄 {}", summary.skippedRecords)}"
    } else {
        base
    }
}

@Composable
private fun BackupInfoCard(
    folderLabel: String?,
    storageBytes: Long,
    backupCount: Int,
    onSelectFolder: () -> Unit,
) {
    val colors = YamiboTheme.colors
    BackupCard {
        Text(i18n("備份資料夾"), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.textStrong)
        Spacer(Modifier.height(6.dp))
        Text(
            text = folderLabel ?: i18n("尚未選擇備份資料夾"),
            fontSize = 13.sp,
            color = colors.textDark.copy(alpha = 0.7f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = i18n("已使用 {}，{} 個備份檔", formatStorageSize(storageBytes), backupCount),
                fontSize = 13.sp,
                color = colors.textDark.copy(alpha = 0.65f),
                modifier = Modifier.weight(1f),
            )
            SmallBackupButton(text = i18n("選擇資料夾"), onClick = onSelectFolder)
        }
    }
}

@Composable
private fun BackupSettingCard(
    interval: BackupInterval,
    onIntervalChange: (BackupInterval) -> Unit,
    maxAutoFiles: Int,
    onMaxAutoFilesChange: (Int) -> Unit,
) {
    val colors = YamiboTheme.colors
    BackupCard {
        Text(
            text = i18n("自動備份"),
            fontSize = 13.sp,
            color = colors.textDark.copy(alpha = 0.5f),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = i18n("定期自動備份"),
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = colors.textDark,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(8.dp))
        SettingsChipRow(
            options = BackupInterval.entries.map { it to it.localizedLabel() },
            selectedValue = interval,
            onSelect = onIntervalChange,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(24.dp))
        Text(i18n("最多自動備份檔案數量：{}", maxAutoFiles), fontSize = 14.sp, color = colors.textDark)
        Slider(
            value = maxAutoFiles.toFloat(),
            onValueChange = { onMaxAutoFilesChange(it.roundToInt().coerceIn(1, 10)) },
            valueRange = 1f..10f,
            steps = 8,
            colors = SliderDefaults.colors(
                thumbColor = colors.brownDeep,
                activeTrackColor = colors.brownPrimary,
                inactiveTrackColor = colors.brownLight.copy(alpha = 0.45f),
            ),
        )
    }
}

@Composable
private fun BackupActionCard(
    working: Boolean,
    onCreateBackup: () -> Unit,
    onLoadBackup: () -> Unit,
) {
    BackupCard {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            BackupActionButton(
                text = if (working) i18n("處理中...") else i18n("建立備份"),
                primary = true,
                enabled = !working,
                modifier = Modifier.weight(1f),
                onClick = onCreateBackup,
            )
            BackupActionButton(
                text = i18n("載入備份"),
                primary = false,
                enabled = !working,
                modifier = Modifier.weight(1f),
                onClick = onLoadBackup,
            )
        }
    }
}

@Composable
private fun BackupFileListCard(files: List<BackupRepository.BackupFileInfo>) {
    val colors = YamiboTheme.colors
    BackupCard {
        Text(i18n("備份檔案"), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.textStrong)
        Spacer(Modifier.height(8.dp))
        files.sortedByDescending { it.modifiedAt ?: 0L }.take(8).forEach { file ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(file.name, fontSize = 13.sp, color = colors.textDark, modifier = Modifier.weight(1f))
                Text(formatStorageSize(file.bytes), fontSize = 12.sp, color = colors.textDark.copy(alpha = 0.6f))
            }
        }
    }
}

@Composable
private fun CreateBackupDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val colors = YamiboTheme.colors
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.creamBackground,
        titleContentColor = colors.textDark,
        title = { Text(i18n("建立備份"), fontWeight = FontWeight.Bold) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(i18n("備份名稱")) },
                    placeholder = { Text(i18n("不輸入將使用自動生成名稱")) },
                    supportingText = {
                        Text(
                            text = i18n("自動生成格式：YamiboApp-YYYYMMDD-HHmmss.yamibobak"),
                            color = colors.textDark.copy(alpha = 0.58f),
                        )
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.brownDeep,
                        unfocusedBorderColor = colors.brownPrimary.copy(alpha = 0.35f),
                        focusedLabelColor = colors.brownDeep,
                        cursorColor = colors.brownDeep,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            SmallBackupButton(text = i18n("建立"), onClick = { onConfirm(name.trim()) })
        },
        dismissButton = {
            SmallBackupButton(text = i18n("取消"), onClick = onDismiss)
        },
    )
}

@Composable
private fun RestoreModeDialog(
    onDismiss: () -> Unit,
    onSelect: (BackupRepository.RestoreMode) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = YamiboTheme.colors.creamBackground,
        titleContentColor = YamiboTheme.colors.textDark,
        title = { Text(i18n("選擇還原方式"), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RestoreOption(i18n("合併新增"), i18n("保留現有資料，只加入不存在的收藏與狀態")) {
                    onSelect(BackupRepository.RestoreMode.Merge)
                }
                RestoreOption(i18n("完全覆蓋"), i18n("清空現有設定與收藏狀態後再還原")) {
                    onSelect(BackupRepository.RestoreMode.Overwrite)
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            SmallBackupButton(text = i18n("取消"), onClick = onDismiss)
        },
    )
}

@Composable
private fun RestoreOption(title: String, subtitle: String, onClick: () -> Unit) {
    val colors = YamiboTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .background(colors.brownLight.copy(alpha = 0.18f))
            .padding(12.dp),
    ) {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.textDark)
        Spacer(Modifier.height(3.dp))
        Text(subtitle, fontSize = 12.sp, color = colors.textDark.copy(alpha = 0.64f))
    }
}

@Composable
private fun BackupCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(YamiboTheme.colors.creamSurface)
            .padding(16.dp),
        content = content,
    )
}

@Composable
private fun SmallBackupButton(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        fontSize = 13.sp,
        color = YamiboTheme.colors.brownDeep,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .background(YamiboTheme.colors.brownLight.copy(alpha = 0.26f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
private fun BackupActionButton(
    text: String,
    primary: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = YamiboTheme.colors
    Box(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    !enabled -> colors.brownLight.copy(alpha = 0.45f)
                    primary -> colors.brownDeep
                    else -> colors.creamBackground
                }
            )
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (primary && enabled) Color.White else colors.brownDeep,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

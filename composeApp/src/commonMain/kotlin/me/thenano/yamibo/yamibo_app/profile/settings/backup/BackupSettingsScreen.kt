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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import me.thenano.yamibo.yamibo_app.LocalAppSettingsRepository
import me.thenano.yamibo.yamibo_app.LocalBackupRepository
import me.thenano.yamibo.yamibo_app.LocalBackupScheduler
import me.thenano.yamibo.yamibo_app.LocalPanCloudAccountRepository
import me.thenano.yamibo.yamibo_app.LocalPanCloudBackupRepository
import me.thenano.yamibo.yamibo_app.Logger
import me.thenano.yamibo.yamibo_app.components.navigation.YamiboTopBar
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme
import me.thenano.yamibo.yamibo_app.i18n.i18n
import me.thenano.yamibo.yamibo_app.i18n.localizedLabel
import me.thenano.yamibo.yamibo_app.navigation.LocalNavigator
import me.thenano.yamibo.yamibo_app.profile.settings.components.SettingsChipRow
import me.thenano.yamibo.yamibo_app.repository.BackupRepository
import me.thenano.yamibo.yamibo_app.repository.pancloud.PanCloudApiException
import me.thenano.yamibo.yamibo_app.repository.settings.BackupInterval
import me.thenano.yamibo.yamibo_app.util.formatStorageSize
import me.thenano.yamibo.yamibo_app.util.state
import me.thenano.yamibo.yamibo_app.profile.settings.cloud.CloudAuthMode
import me.thenano.yamibo.yamibo_app.profile.settings.cloud.CloudLoginDialog
import kotlin.math.roundToInt

@Composable
internal fun BackupSettingsScreen() {
    val colors = YamiboTheme.colors
    val navigator = LocalNavigator.current
    val repository = LocalBackupRepository.current
    val cloudRepository = LocalPanCloudBackupRepository.current
    val cloudAccount = LocalPanCloudAccountRepository.current
    val scheduler = LocalBackupScheduler.current
    val appSettingsRepository = LocalAppSettingsRepository.current
    val feedbackController = me.thenano.yamibo.yamibo_app.LocalAppFeedbackController.current
    val coroutineScope = rememberCoroutineScope()
    val backupInterval = appSettingsRepository.backupInterval.state()
    val maxAutoFiles = appSettingsRepository.backupMaxAutoFiles.state()

    var folderLabel by remember { mutableStateOf<String?>(null) }
    var storageBytes by remember { mutableLongStateOf(0L) }
    var backupFiles by remember { mutableStateOf<List<BackupRepository.BackupFileInfo>>(emptyList()) }
    var cloudLoggedIn by remember { mutableStateOf(false) }
    var cloudUsername by remember { mutableStateOf<String?>(null) }
    var cloudStorageBytes by remember { mutableLongStateOf(0L) }
    var cloudBackupFiles by remember { mutableStateOf<List<BackupRepository.BackupFileInfo>>(emptyList()) }
    var backupToCloud by remember { mutableStateOf(appSettingsRepository.backupToCloudEnabled.getValue()) }
    var working by remember { mutableStateOf(false) }
    var pendingRestoreUri by remember { mutableStateOf<String?>(null) }
    var pendingRestoreCloud by remember { mutableStateOf<BackupRepository.BackupFileInfo?>(null) }
    var showCreateBackupDialog by remember { mutableStateOf(false) }
    var showCloudLogin by remember { mutableStateOf(false) }

    suspend fun refresh() {
        cloudAccount.restoreSession()
        folderLabel = repository.getSelectedFolderLabel()
        storageBytes = repository.getBackupStorageBytes()
        backupFiles = repository.listBackupFiles()
        cloudLoggedIn = cloudAccount.status.loggedIn
        cloudUsername = cloudAccount.status.username
        if (cloudLoggedIn) {
            cloudStorageBytes = cloudRepository.getBackupStorageBytes()
            cloudBackupFiles = cloudRepository.listBackupFiles()
        } else {
            cloudStorageBytes = 0L
            cloudBackupFiles = emptyList()
        }
    }

    fun createBackup(name: String?) {
        coroutineScope.launch {
            working = true
            val customName = name?.takeIf { it.isNotBlank() }
            val local = repository.createBackup(automatic = false, customName = customName)
            val cloud = if (backupToCloud && cloudLoggedIn) {
                cloudRepository.createBackup(automatic = false, customName = customName)
            } else {
                null
            }
            refresh()
            when {
                local.isSuccess && cloud?.isSuccess != false ->
                    feedbackController.post(i18n("已建立備份：{}", local.getOrThrow().name))
                local.isFailure ->
                    feedbackController.post(local.exceptionOrNull()?.message ?: i18n("建立備份失敗"))
                cloud?.isFailure == true ->
                    feedbackController.post(
                        i18n("網盤備份失敗：{}", cloud.exceptionOrNull()?.message ?: ""),
                    )
            }
            working = false
        }
    }

    fun submitCloudAuth(username: String, password: String, mode: CloudAuthMode) {
        if (username.isBlank() || password.isBlank()) {
            feedbackController.post(i18n("請輸入帳號與密碼"))
            return
        }
        coroutineScope.launch {
            working = true
            val result = when (mode) {
                CloudAuthMode.Login -> cloudAccount.login(username.trim(), password)
                CloudAuthMode.Register -> cloudAccount.register(username.trim(), password)
            }
            result
                .onSuccess {
                    refresh()
                    feedbackController.post(i18n("已登入網盤：{}", username.trim()))
                }
                .onFailure { error ->
                    if (error is PanCloudApiException && error.statusCode == 409) {
                        feedbackController.post(i18n("帳戶已存在，請改用登入"))
                    } else {
                        feedbackController.post(error.message ?: i18n("網盤操作失敗"))
                    }
                }
            working = false
        }
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

            CloudBackupCard(
                loggedIn = cloudLoggedIn,
                username = cloudUsername,
                storageBytes = cloudStorageBytes,
                backupCount = cloudBackupFiles.size,
                backupToCloud = backupToCloud,
                onToggleBackupToCloud = { enabled ->
                    backupToCloud = enabled
                    appSettingsRepository.backupToCloudEnabled.setValue(enabled)
                },
                onLogin = { showCloudLogin = true },
                onLogout = {
                    coroutineScope.launch {
                        cloudAccount.logout()
                        refresh()
                        feedbackController.post(i18n("已登出網盤"))
                    }
                },
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
                BackupFileListCard(
                    title = i18n("本地備份檔案"),
                    files = backupFiles,
                    onRestore = { pendingRestoreUri = it.uri },
                )
            }

            if (cloudLoggedIn && cloudBackupFiles.isNotEmpty()) {
                BackupFileListCard(
                    title = i18n("網盤備份檔案"),
                    files = cloudBackupFiles,
                    onRestore = { pendingRestoreCloud = it },
                )
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

    pendingRestoreCloud?.let { file ->
        RestoreModeDialog(
            onDismiss = { pendingRestoreCloud = null },
            onSelect = { mode ->
                pendingRestoreCloud = null
                coroutineScope.launch {
                    working = true
                    cloudRepository.restoreBackup(file.uri, mode)
                        .onSuccess {
                            refresh()
                            feedbackController.post(restoreSummaryText(it))
                        }
                        .onFailure { error ->
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
                createBackup(name)
            },
        )
    }

    if (showCloudLogin) {
        CloudLoginDialog(
            working = working,
            onDismiss = { showCloudLogin = false },
            onSubmit = { username, password, mode ->
                showCloudLogin = false
                submitCloudAuth(username, password, mode)
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
private fun CloudBackupCard(
    loggedIn: Boolean,
    username: String?,
    storageBytes: Long,
    backupCount: Int,
    backupToCloud: Boolean,
    onToggleBackupToCloud: (Boolean) -> Unit,
    onLogin: () -> Unit,
    onLogout: () -> Unit,
) {
    val colors = YamiboTheme.colors
    BackupCard {
        Text(i18n("網盤雲端備份"), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.textStrong)
        Spacer(Modifier.height(6.dp))
        if (loggedIn) {
            Text(
                text = i18n("網盤：{}", username ?: ""),
                fontSize = 13.sp,
                color = colors.textDark.copy(alpha = 0.7f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = i18n("已使用 {}，{} 個備份檔", formatStorageSize(storageBytes), backupCount),
                fontSize = 13.sp,
                color = colors.textDark.copy(alpha = 0.65f),
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = i18n("備份到網盤"),
                    fontSize = 14.sp,
                    color = colors.textDark,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = backupToCloud,
                    onCheckedChange = onToggleBackupToCloud,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = colors.brownDeep,
                        checkedTrackColor = colors.brownPrimary,
                    ),
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = i18n("備份檔案將上傳至網盤的 yamibo 資料夾"),
                    fontSize = 12.sp,
                    color = colors.textDark.copy(alpha = 0.55f),
                    modifier = Modifier.weight(1f),
                )
                SmallBackupButton(text = i18n("登出"), onClick = onLogout)
            }
        } else {
            Text(
                text = i18n("網盤：未登入"),
                fontSize = 13.sp,
                color = colors.textDark.copy(alpha = 0.7f),
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = i18n("登入網盤帳戶，將備份上傳至 yamibo 資料夾"),
                    fontSize = 12.sp,
                    color = colors.textDark.copy(alpha = 0.55f),
                    modifier = Modifier.weight(1f),
                )
                SmallBackupButton(text = i18n("登入"), onClick = onLogin)
            }
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
private fun BackupFileListCard(
    title: String,
    files: List<BackupRepository.BackupFileInfo>,
    onRestore: (BackupRepository.BackupFileInfo) -> Unit,
) {
    val colors = YamiboTheme.colors
    BackupCard {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.textStrong)
        Spacer(Modifier.height(8.dp))
        files.sortedByDescending { it.modifiedAt ?: 0L }.take(8).forEach { file ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = file.name,
                    fontSize = 13.sp,
                    color = colors.textDark,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(formatStorageSize(file.bytes), fontSize = 12.sp, color = colors.textDark.copy(alpha = 0.6f))
                Spacer(Modifier.width(8.dp))
                SmallBackupButton(text = i18n("還原"), onClick = { onRestore(file) })
            }
        }
    }
}

@Composable
internal fun CreateBackupDialog(
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
                    colors = backupTextFieldColors(),
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
internal fun RestoreModeDialog(
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
internal fun BackupCard(content: @Composable ColumnScope.() -> Unit) {
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
internal fun SmallBackupButton(text: String, onClick: () -> Unit) {
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

@Composable
private fun backupTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = YamiboTheme.colors.brownDeep,
    unfocusedBorderColor = YamiboTheme.colors.brownPrimary.copy(alpha = 0.35f),
    focusedLabelColor = YamiboTheme.colors.brownDeep,
    cursorColor = YamiboTheme.colors.brownDeep,
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
)

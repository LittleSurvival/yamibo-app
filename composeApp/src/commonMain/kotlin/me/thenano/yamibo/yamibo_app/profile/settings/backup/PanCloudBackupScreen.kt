package me.thenano.yamibo.yamibo_app.profile.settings.backup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import me.thenano.yamibo.yamibo_app.LocalAppFeedbackController
import me.thenano.yamibo.yamibo_app.LocalAppSettingsRepository
import me.thenano.yamibo.yamibo_app.LocalPanCloudAccountRepository
import me.thenano.yamibo.yamibo_app.LocalPanCloudBackupRepository
import me.thenano.yamibo.yamibo_app.LocalPanCloudBackupScheduler
import me.thenano.yamibo.yamibo_app.components.navigation.YamiboTopBar
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme
import me.thenano.yamibo.yamibo_app.i18n.i18n
import me.thenano.yamibo.yamibo_app.navigation.LocalNavigator
import me.thenano.yamibo.yamibo_app.repository.BackupRepository
import me.thenano.yamibo.yamibo_app.repository.pancloud.PanCloudApiException
import me.thenano.yamibo.yamibo_app.util.formatStorageSize

private enum class PanCloudAuthMode { Login, Register }

@Composable
internal fun PanCloudBackupScreen() {
    val colors = YamiboTheme.colors
    val navigator = LocalNavigator.current
    val repository = LocalPanCloudBackupRepository.current
    val accountRepository = LocalPanCloudAccountRepository.current
    val appSettingsRepository = LocalAppSettingsRepository.current
    val scheduler = LocalPanCloudBackupScheduler.current
    val feedbackController = LocalAppFeedbackController.current
    val coroutineScope = rememberCoroutineScope()

    var loggedIn by remember { mutableStateOf(accountRepository.status.loggedIn) }
    var username by remember { mutableStateOf(accountRepository.status.username ?: "") }
    var storageBytes by remember { mutableLongStateOf(0L) }
    var backupFiles by remember { mutableStateOf<List<BackupRepository.BackupFileInfo>>(emptyList()) }
    var working by remember { mutableStateOf(false) }
    var pendingRestore by remember { mutableStateOf<BackupRepository.BackupFileInfo?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var autoBackupEnabled by remember { mutableStateOf(appSettingsRepository.panCloudAutoBackupEnabled.getValue()) }

    // 登录表单
    var inputUsername by remember { mutableStateOf("") }
    var inputPassword by remember { mutableStateOf("") }
    var authMode by remember { mutableStateOf(PanCloudAuthMode.Login) }

    suspend fun refresh() {
        val status = accountRepository.status
        loggedIn = status.loggedIn
        if (status.username != null) username = status.username ?: ""
        if (loggedIn) {
            backupFiles = repository.listBackupFiles()
            storageBytes = repository.getBackupStorageBytes()
        } else {
            backupFiles = emptyList()
            storageBytes = 0L
        }
    }

    fun submitAuth() {
        val name = inputUsername.trim()
        val password = inputPassword
        if (name.isBlank() || password.isBlank()) {
            feedbackController.post(i18n("請輸入帳號與密碼"))
            return
        }
        coroutineScope.launch {
            working = true
            val result = when (authMode) {
                PanCloudAuthMode.Login -> accountRepository.login(name, password)
                PanCloudAuthMode.Register -> accountRepository.register(name, password)
            }
            result
                .onSuccess {
                    refresh()
                    feedbackController.post(i18n("已登入網盤：{}", name))
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

    LaunchedEffect(Unit) { refresh() }

    Scaffold(
        topBar = {
            YamiboTopBar(
                title = i18n("網盤雲端備份"),
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
            if (!loggedIn) {
                LoginCard(
                    mode = authMode,
                    username = inputUsername,
                    password = inputPassword,
                    working = working,
                    onUsernameChange = { inputUsername = it },
                    onPasswordChange = { inputPassword = it },
                    onSwitchMode = { authMode = if (authMode == PanCloudAuthMode.Login) PanCloudAuthMode.Register else PanCloudAuthMode.Login },
                    onSubmit = ::submitAuth,
                )
            } else {
                AccountCard(
                    username = username,
                    storageBytes = storageBytes,
                    backupCount = backupFiles.size,
                    autoBackupEnabled = autoBackupEnabled,
                    working = working,
                    onToggleAutoBackup = { enabled ->
                        autoBackupEnabled = enabled
                        appSettingsRepository.panCloudAutoBackupEnabled.setValue(enabled)
                        coroutineScope.launch {
                            if (enabled) {
                                scheduler.schedule(appSettingsRepository.backupInterval.getValue())
                            } else {
                                scheduler.cancel()
                            }
                        }
                    },
                    onCreateBackup = { showCreateDialog = true },
                    onLogout = {
                        coroutineScope.launch {
                            accountRepository.logout()
                            refresh()
                            feedbackController.post(i18n("已登出網盤"))
                        }
                    },
                )

                if (backupFiles.isNotEmpty()) {
                    CloudBackupFileListCard(
                        files = backupFiles,
                        onRestore = { pendingRestore = it },
                    )
                }
            }
        }
    }

    pendingRestore?.let { file ->
        RestoreModeDialog(
            onDismiss = { pendingRestore = null },
            onSelect = { mode ->
                pendingRestore = null
                coroutineScope.launch {
                    working = true
                    repository.restoreBackup(file.uri, mode)
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

    if (showCreateDialog) {
        CreateBackupDialog(
            onDismiss = { showCreateDialog = false },
            onConfirm = { name ->
                showCreateDialog = false
                coroutineScope.launch {
                    working = true
                    repository.createBackup(automatic = false, customName = name.takeIf { it.isNotBlank() })
                        .onSuccess {
                            refresh()
                            feedbackController.post(i18n("已建立備份：{}", it.name))
                        }
                        .onFailure { error ->
                            feedbackController.post(error.message ?: i18n("建立備份失敗"))
                        }
                    working = false
                }
            },
        )
    }
}

@Composable
private fun LoginCard(
    mode: PanCloudAuthMode,
    username: String,
    password: String,
    working: Boolean,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSwitchMode: () -> Unit,
    onSubmit: () -> Unit,
) {
    val colors = YamiboTheme.colors
    BackupCard {
        Text(i18n("登入網盤帳戶"), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.textStrong)
        Spacer(Modifier.height(6.dp))
        Text(
            text = i18n("備份檔案將上傳至網盤的 yamibo 資料夾，並可跨裝置恢復。"),
            fontSize = 13.sp,
            color = colors.textDark.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = username,
            onValueChange = onUsernameChange,
            singleLine = true,
            label = { Text(i18n("帳號")) },
            enabled = !working,
            colors = backupTextFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            singleLine = true,
            label = { Text(i18n("密碼")) },
            visualTransformation = PasswordVisualTransformation(),
            enabled = !working,
            colors = backupTextFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            SmallBackupButton(
                text = if (working) i18n("處理中...") else if (mode == PanCloudAuthMode.Login) i18n("登入") else i18n("註冊"),
                onClick = onSubmit,
            )
            SmallBackupButton(
                text = if (mode == PanCloudAuthMode.Login) i18n("改用註冊") else i18n("改用登入"),
                onClick = onSwitchMode,
            )
        }
    }
}

@Composable
private fun AccountCard(
    username: String,
    storageBytes: Long,
    backupCount: Int,
    autoBackupEnabled: Boolean,
    working: Boolean,
    onToggleAutoBackup: (Boolean) -> Unit,
    onCreateBackup: () -> Unit,
    onLogout: () -> Unit,
) {
    val colors = YamiboTheme.colors
    BackupCard {
        Text(i18n("帳戶：{}", username), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.textStrong)
        Spacer(Modifier.height(6.dp))
        Text(
            text = i18n("已使用 {}，{} 個備份檔", formatStorageSize(storageBytes), backupCount),
            fontSize = 13.sp,
            color = colors.textDark.copy(alpha = 0.65f),
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = i18n("自動備份"),
                fontSize = 14.sp,
                color = colors.textDark,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = autoBackupEnabled,
                onCheckedChange = onToggleAutoBackup,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = colors.brownDeep,
                    checkedTrackColor = colors.brownPrimary,
                ),
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            SmallBackupButton(
                text = if (working) i18n("處理中...") else i18n("建立備份"),
                onClick = onCreateBackup,
            )
            SmallBackupButton(text = i18n("登出"), onClick = onLogout)
        }
    }
}

@Composable
private fun CloudBackupFileListCard(
    files: List<BackupRepository.BackupFileInfo>,
    onRestore: (BackupRepository.BackupFileInfo) -> Unit,
) {
    val colors = YamiboTheme.colors
    BackupCard {
        Text(i18n("網盤備份檔案"), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.textStrong)
        Spacer(Modifier.height(4.dp))
        Text(
            text = i18n("點擊檔案可還原"),
            fontSize = 12.sp,
            color = colors.textDark.copy(alpha = 0.55f),
        )
        Spacer(Modifier.height(8.dp))
        files.sortedByDescending { it.modifiedAt ?: 0L }.take(20).forEach { file ->
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
                Spacer(Modifier.height(0.dp))
                SmallBackupButton(text = i18n("還原"), onClick = { onRestore(file) })
            }
        }
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

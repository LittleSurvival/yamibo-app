package me.thenano.yamibo.yamibo_app.profile.settings.update

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch
import me.thenano.yamibo.yamibo_app.AppVersion
import me.thenano.yamibo.yamibo_app.LocalAppSettingsRepository
import me.thenano.yamibo.yamibo_app.LocalAppUpdateRepository
import me.thenano.yamibo.yamibo_app.components.controls.YamiboSingleSelectDialog
import me.thenano.yamibo.yamibo_app.components.navigation.YamiboTopBar
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme
import me.thenano.yamibo.yamibo_app.i18n.i18n
import me.thenano.yamibo.yamibo_app.i18n.localizedLabel
import me.thenano.yamibo.yamibo_app.navigation.LocalNavigator
import me.thenano.yamibo.yamibo_app.repository.appupdate.*
import me.thenano.yamibo.yamibo_app.repository.settings.AppUpdateLaunchCheckThreshold
import me.thenano.yamibo.yamibo_app.util.state

private const val ShowUpdatePromptPreviewButton = false

@Composable
internal fun AppUpdateScreen() {
    val colors = YamiboTheme.colors
    val navigator = LocalNavigator.current
    val repository = LocalAppUpdateRepository.current
    val appSettingsRepository = LocalAppSettingsRepository.current
    val coroutineScope = rememberCoroutineScope()
    val downloadState by repository.downloadState.collectAsState()
    val launchCheckThreshold = appSettingsRepository.appUpdateLaunchCheckThreshold.state()

    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<AppUpdateCheckResult?>(null) }
    var showThresholdDialog by remember { mutableStateOf(false) }
    var showUpdatePromptPreview by remember { mutableStateOf(false) }
    val release = (result as? AppUpdateCheckResult.UpdateAvailable)?.release
        ?: (result as? AppUpdateCheckResult.Ignored)?.release

    Scaffold(
        topBar = {
            YamiboTopBar(
                title = i18n("App 更新"),
                titleFontSize = 18,
                onBack = { navigator.pop() },
            )
        },
        containerColor = colors.creamBackground,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.creamBackground)
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AppUpdateStatusCard(
                checking = checking,
                result = result,
                downloadState = downloadState,
                release = release,
            )

            AppUpdateLaunchThresholdCard(
                selected = launchCheckThreshold,
                onClick = { showThresholdDialog = true },
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedButton(
                    onClick = {
                        coroutineScope.launch {
                            checking = true
                            result = repository.checkForUpdate(force = true)
                            checking = false
                        }
                    },
                    enabled = !checking && downloadState !is AppUpdateDownloadState.Running,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.textStrong),
                ) {
                    Text(if (checking) i18n("檢查中...") else i18n("檢查更新"))
                }
                release?.let { available ->
                    val buttonText = when (downloadState) {
                        is AppUpdateDownloadState.PermissionRequired -> i18n("安裝更新")
                        else -> i18n("下載更新")
                    }
                    OutlinedButton(
                        onClick = {
                            coroutineScope.launch {
                                repository.downloadAndInstall(available)
                            }
                        },
                        enabled = downloadState !is AppUpdateDownloadState.Running && available.asset != null,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.textStrong),
                    ) {
                        Text(buttonText)
                    }
                    OutlinedButton(
                        onClick = { repository.openReleasePage(available) },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.textStrong),
                    ) {
                        Text(i18n("發布頁"))
                    }
                }
                if (ShowUpdatePromptPreviewButton) {
                    OutlinedButton(
                        onClick = { showUpdatePromptPreview = true },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.textStrong),
                    ) {
                        Text(i18n("預覽更新彈窗"))
                    }
                }
            }
        }
    }

    if (showThresholdDialog) {
        AppUpdateLaunchThresholdDialog(
            selected = launchCheckThreshold,
            onSelect = {
                appSettingsRepository.appUpdateLaunchCheckThreshold.setValue(it)
                showThresholdDialog = false
            },
            onDismiss = { showThresholdDialog = false },
        )
    }
    if (showUpdatePromptPreview) {
        PreviewUpdatePromptDialog(onDismiss = { showUpdatePromptPreview = false })
    }
}

@Composable
private fun PreviewUpdatePromptDialog(onDismiss: () -> Unit) {
    val release = AppUpdateRelease(
        source = AppUpdateSource(
            name = "Preview",
            manifestUrl = "preview://app-update",
        ),
        channel = "stable",
        versionName = "0.0.2",
        versionCode = 3,
        minVersionCode = null,
        releaseNotes = "",
        releaseUrl = "https://github.com/LittleSurvival/yamibo-app/releases",
        asset = AppUpdateAsset(
            type = "universal-apk",
            url = "https://github.com/LittleSurvival/yamibo-app/releases/download/2/yamibo-stable-v0.0.1.apk",
            sha256 = null,
            size = null,
        ),
        changelogText = """
            # stable-v0.0.2

            - 顯示新版本完整名稱。
            - changelog 會直接顯示在 app 內，而不是只提供超連結。
            - 下載完成後沿用 Android 系統安裝流程。
            - 測試長文以觸發捲軸：
              這是一段為了測試更新日誌彈窗所加入的較長文字。我們需要確保當更新日誌的內容長度超過限制時，視窗會維持固定高度、允許獨立捲動，並清楚顯示目前的捲動位置。使用者仍可隨時選擇立即更新、手動更新或稍後處理。
        """.trimIndent(),
    )

    Dialog(onDismissRequest = onDismiss) {
        AppUpdatePromptContent(
            release = release,
            onPrimaryClick = onDismiss,
            onManualClick = onDismiss,
            onLaterClick = onDismiss,
        )
    }
}

@Composable
private fun AppUpdateStatusCard(
    checking: Boolean,
    result: AppUpdateCheckResult?,
    downloadState: AppUpdateDownloadState,
    release: AppUpdateRelease?,
) {
    val colors = YamiboTheme.colors
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = colors.creamSurface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = when {
                    checking -> i18n("正在檢查更新")
                    result == null -> i18n("尚未檢查更新")
                    result is AppUpdateCheckResult.UpdateAvailable -> i18n("發現新版本 {}", result.release.fullVersionName())
                    result is AppUpdateCheckResult.Ignored -> i18n("已忽略版本 {}", result.release.fullVersionName())
                    result is AppUpdateCheckResult.Preparing -> i18n("新版本 {} 正在準備中", result.displayVersionLabel())
                    result is AppUpdateCheckResult.UpToDate -> i18n("目前已是最新版本：{}", AppVersion.displayName)
                    result is AppUpdateCheckResult.Failed -> i18n("檢查更新失敗")
                    else -> i18n("尚未檢查更新")
                },
                color = colors.textDark,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (checking) {
                CircularProgressIndicator(color = colors.brownPrimary)
            }
            if (result is AppUpdateCheckResult.Failed) {
                Text(result.message, color = colors.textDark.copy(alpha = 0.68f), fontSize = 13.sp)
            }
            if (result is AppUpdateCheckResult.Preparing) {
                Text(
                    text = i18n("更新檔案尚未發布完成，請稍後再試。來源：{}", result.sourceName),
                    color = colors.textDark.copy(alpha = 0.68f),
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
            }
            release?.let {
                Text(
                    text = i18n("來源：{}", it.source.name),
                    color = colors.textDark.copy(alpha = 0.62f),
                    fontSize = 12.sp,
                )
                val changelog = it.changelogContent()
                if (changelog.isNotBlank()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 180.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = changelog,
                            color = colors.textDark.copy(alpha = 0.78f),
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                        )
                    }
                }
            }
            DownloadProgress(downloadState)
        }
    }
}

@Composable
private fun DownloadProgress(state: AppUpdateDownloadState) {
    val colors = YamiboTheme.colors
    when (state) {
        AppUpdateDownloadState.Idle -> Unit
        is AppUpdateDownloadState.Running -> {
            val progress = state.totalBytes?.takeIf { it > 0L }?.let {
                state.downloadedBytes.toFloat() / it.toFloat()
            }
            LinearProgressIndicator(
                progress = { progress ?: 0f },
                modifier = Modifier.fillMaxWidth(),
                color = colors.brownPrimary,
                trackColor = colors.brownLight.copy(alpha = 0.25f),
            )
            val downloadedMb = (state.downloadedBytes.toDouble() / (1024 * 1024) * 100).toLong().toDouble() / 100
            val totalBytes = state.totalBytes
            val progressText = if (totalBytes != null && totalBytes > 0L) {
                val totalMb = (totalBytes.toDouble() / (1024 * 1024) * 100).toLong().toDouble() / 100
                i18n("正在下載更新... ({} MB / {} MB)", downloadedMb.toString(), totalMb.toString())
            } else {
                i18n("正在下載更新... ({} MB / 未知)", downloadedMb.toString())
            }
            Text(
                text = progressText,
                color = colors.textDark.copy(alpha = 0.7f),
                fontSize = 12.sp,
            )
        }
        is AppUpdateDownloadState.Completed -> Text(
            text = i18n("下載完成，已打開系統安裝流程。"),
            color = colors.textDark.copy(alpha = 0.7f),
            fontSize = 12.sp,
        )
        is AppUpdateDownloadState.PermissionRequired -> Text(
            text = i18n("需要允許此 app 安裝未知來源應用，完成授權後請重新點擊下載。"),
            color = colors.textDark.copy(alpha = 0.7f),
            fontSize = 12.sp,
        )
        is AppUpdateDownloadState.Failed -> Text(
            text = i18n("下載失敗：{}", state.message),
            color = colors.textDark.copy(alpha = 0.7f),
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun AppUpdateLaunchThresholdCard(
    selected: AppUpdateLaunchCheckThreshold,
    onClick: () -> Unit,
) {
    val colors = YamiboTheme.colors
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = colors.creamSurface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = i18n("啟動檢查更新間隔"),
                    color = colors.textDark,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = i18n("啟動 app 後在背景檢查更新；未超過間隔不會發出網路請求。"),
                    color = colors.textDark.copy(alpha = 0.62f),
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
            }
            Box(
                modifier = Modifier
                    .background(
                        color = colors.brownLight.copy(alpha = 0.18f),
                        shape = RoundedCornerShape(8.dp),
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClick,
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = appUpdateLaunchThresholdLabel(selected),
                    color = colors.textOnBackground,
                    fontSize = 14.sp,
                )
            }
        }
    }
}

@Composable
private fun AppUpdateLaunchThresholdDialog(
    selected: AppUpdateLaunchCheckThreshold,
    onSelect: (AppUpdateLaunchCheckThreshold) -> Unit,
    onDismiss: () -> Unit,
) {
    YamiboSingleSelectDialog(
        title = i18n("啟動檢查更新間隔"),
        options = AppUpdateLaunchCheckThreshold.entries,
        selected = selected,
        onDismiss = onDismiss,
        onSelect = onSelect,
        label = { appUpdateLaunchThresholdLabel(it) },
        dismissOnSelect = true,
    )
}

@Composable
private fun appUpdateLaunchThresholdLabel(option: AppUpdateLaunchCheckThreshold): String = when (option) {
    AppUpdateLaunchCheckThreshold.MANUAL -> i18n("不自動檢查")
    else -> requireNotNull(option.fixedInterval).localizedLabel()
}

private fun AppUpdateCheckResult.Preparing.displayVersionLabel(): String = "$channel-v$versionName"

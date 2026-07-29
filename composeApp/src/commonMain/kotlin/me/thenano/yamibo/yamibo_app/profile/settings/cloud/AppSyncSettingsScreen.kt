package me.thenano.yamibo.yamibo_app.profile.settings.cloud

import YamiboIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import me.thenano.yamibo.yamibo_app.components.controls.YamiboActionChip
import me.thenano.yamibo.yamibo_app.components.navigation.YamiboTopBar
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme
import me.thenano.yamibo.yamibo_app.i18n.i18n
import me.thenano.yamibo.yamibo_app.navigation.LocalNavigator

@Composable
internal fun AppSyncSettingsScreen(
    controller: CloudSyncUiController = StubCloudSyncUiController,
) {
    val navigator = LocalNavigator.current
    val state by controller.state.collectAsState()

    AppSyncSettingsContent(
        state = state,
        onBack = { navigator.pop() },
        onRefresh = controller::refresh,
        onUpload = controller::upload,
        onLoad = controller::load,
        onDismissApplyChoice = controller::dismissApplyChoice,
        onMerge = controller::merge,
        onOverwrite = controller::overwrite,
        onDeleteCloud = controller::deleteCloudData,
        onAutomaticEnabledChange = controller::setAutomaticEnabled,
        onSyncNow = controller::syncNow,
    )
}

@Composable
internal fun AppSyncSettingsContent(
    state: CloudSyncUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onUpload: () -> Unit,
    onLoad: () -> Unit,
    onDismissApplyChoice: () -> Unit,
    onMerge: () -> Unit,
    onOverwrite: () -> Unit,
    onDeleteCloud: () -> Unit,
    onAutomaticEnabledChange: (Boolean) -> Unit,
    onSyncNow: () -> Unit,
) {
    val colors = YamiboTheme.colors
    var detailsExpanded by remember { mutableStateOf(true) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            YamiboTopBar(
                title = i18n("雲端同步"),
                titleFontSize = 18,
                onBack = onBack,
            )
        },
        containerColor = colors.creamBackground,
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .testTag("app_sync_screen"),
        ) {
            CloudStatusRow(state = state, onRefresh = onRefresh)
            state.notice?.let { notice ->
                CloudSyncInlineNotice(notice)
            }
            HorizontalDivider(color = colors.brownLight.copy(alpha = 0.2f))
            AutomaticSyncSection(
                state = state,
                onEnabledChange = onAutomaticEnabledChange,
                onSyncNow = onSyncNow,
            )
            HorizontalDivider(color = colors.brownLight.copy(alpha = 0.2f))
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = i18n("同步範圍"),
                    color = colors.textStrong,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = i18n("每次同步都包含全部設定、收藏與閱讀紀錄。"),
                    color = colors.textDark.copy(alpha = 0.68f),
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(4.dp))
                CloudActionButton(
                    text = operationText(state.operation, CloudSyncOperation.Uploading)
                        ?: i18n("上傳目前資料"),
                    icon = YamiboIcons.Backup,
                    primary = true,
                    enabled = state.actionsAvailable && !state.isBusy,
                    testTag = "app_sync_upload",
                    onClick = onUpload,
                )
                CloudActionButton(
                    text = operationText(state.operation, CloudSyncOperation.Loading)
                        ?: i18n("載入雲端備份"),
                    icon = YamiboIcons.Download,
                    primary = false,
                    enabled = state.actionsAvailable && state.cloudDataExists && !state.isBusy,
                    testTag = "app_sync_load",
                    onClick = onLoad,
                )
            }
            HorizontalDivider(color = colors.brownLight.copy(alpha = 0.2f))
            SyncDetailsSection(
                expanded = detailsExpanded,
                details = state.details,
                onExpandedChange = { detailsExpanded = it },
            )
            HorizontalDivider(color = colors.brownLight.copy(alpha = 0.2f))
            TextButton(
                onClick = { showDeleteConfirmation = true },
                enabled = state.actionsAvailable && state.cloudDataExists && !state.isBusy,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 18.dp)
                    .testTag("app_sync_delete_cloud"),
            ) {
                val enabled = state.actionsAvailable && state.cloudDataExists && !state.isBusy
                Text(
                    text = i18n("清除雲端資料"),
                    color = MaterialTheme.colorScheme.error.copy(alpha = if (enabled) 1f else 0.4f),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }

    if (state.pendingApplyChoice && !state.isBusy) {
        ApplyModeDialog(
            onDismiss = onDismissApplyChoice,
            onMerge = onMerge,
            onOverwrite = onOverwrite,
        )
    }
    if (showDeleteConfirmation) {
        DeleteCloudDataDialog(
            onDismiss = { showDeleteConfirmation = false },
            onConfirm = {
                showDeleteConfirmation = false
                onDeleteCloud()
            },
        )
    }
}

@Composable
private fun CloudStatusRow(
    state: CloudSyncUiState,
    onRefresh: () -> Unit,
) {
    val colors = YamiboTheme.colors
    val busy = state.status == CloudSyncStatus.Checking ||
        state.operation == CloudSyncOperation.Refreshing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(88.dp)
            .padding(horizontal = 20.dp)
            .testTag("app_sync_status"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = colors.brownPrimary,
                )
            } else {
                Icon(
                    imageVector = YamiboIcons.Sync,
                    contentDescription = null,
                    tint = statusColor(state.status),
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Spacer(Modifier.size(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = i18n("雲端備份狀態"),
                fontSize = 13.sp,
                color = colors.textDark.copy(alpha = 0.58f),
            )
            Text(
                text = if (busy) i18n("處理中...") else i18n(state.statusHeadline),
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = colors.textStrong,
            )
            Text(
                text = i18n(state.statusSupport),
                fontSize = 12.sp,
                color = colors.textDark.copy(alpha = 0.62f),
                maxLines = 1,
            )
        }
        IconButton(
            onClick = onRefresh,
            enabled = state.actionsAvailable && !state.isBusy,
            modifier = Modifier
                .size(48.dp)
                .testTag("app_sync_refresh"),
        ) {
            Icon(
                imageVector = YamiboIcons.Reload,
                contentDescription = i18n("重新檢查雲端備份"),
                tint = colors.brownPrimary.copy(
                    alpha = if (state.actionsAvailable && !state.isBusy) 1f else 0.35f,
                ),
            )
        }
    }
}

@Composable
private fun CloudSyncInlineNotice(notice: CloudSyncNotice) {
    val colors = YamiboTheme.colors
    val color = when (notice.severity) {
        CloudSyncNoticeSeverity.Info -> colors.brownPrimary
        CloudSyncNoticeSeverity.Success -> Color(0xFF3E7A4C)
        CloudSyncNoticeSeverity.Warning -> colors.orangeAccent
        CloudSyncNoticeSeverity.Error -> colors.redAccent
    }
    Text(
        text = notice.message,
        color = color,
        fontSize = 12.sp,
        modifier = Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.09f))
            .padding(horizontal = 20.dp, vertical = 10.dp)
            .testTag("app_sync_inline_notice"),
    )
}

@Composable
private fun AutomaticSyncSection(
    state: CloudSyncUiState,
    onEnabledChange: (Boolean) -> Unit,
    onSyncNow: () -> Unit,
) {
    val colors = YamiboTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 18.dp)
            .testTag("app_sync_automatic_section"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = i18n("自動同步"),
                    color = colors.textStrong,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = i18n(state.automaticStatus),
                    color = colors.textDark.copy(alpha = 0.68f),
                    fontSize = 13.sp,
                )
            }
            Switch(
                checked = state.automaticEnabled,
                onCheckedChange = onEnabledChange,
                enabled = state.automaticAvailable && !state.isBusy,
                modifier = Modifier.testTag("app_sync_automatic_toggle"),
                colors = SwitchDefaults.colors(
                    checkedThumbColor = colors.brownDeep,
                    checkedTrackColor = colors.brownPrimary.copy(alpha = 0.5f),
                    uncheckedThumbColor = colors.textDark.copy(alpha = 0.5f),
                    uncheckedTrackColor = colors.brownLight.copy(alpha = 0.3f),
                ),
            )
        }
        CloudActionButton(
            text = i18n("立即同步"),
            icon = YamiboIcons.Sync,
            primary = false,
            enabled = state.automaticAvailable && !state.isBusy,
            testTag = "app_sync_sync_now",
            onClick = onSyncNow,
        )
    }
}

@Composable
private fun SyncDetailsSection(
    expanded: Boolean,
    details: List<CloudSyncDetail>,
    onExpandedChange: (Boolean) -> Unit,
) {
    val colors = YamiboTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("app_sync_details"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onExpandedChange(!expanded) }
                .padding(horizontal = 20.dp)
                .testTag("app_sync_details_toggle"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = i18n("同步詳情"),
                color = colors.textStrong,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = YamiboIcons.ChevronUp,
                contentDescription = if (expanded) i18n("收合同步詳情") else i18n("展開同步詳情"),
                tint = colors.brownPrimary,
                modifier = Modifier
                    .size(20.dp)
                    .graphicsLayer { rotationZ = if (expanded) 0f else 180f },
            )
        }
        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, bottom = 16.dp)
                    .testTag("app_sync_details_content"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                details.take(5).forEach { detail ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = i18n(detail.label),
                            color = colors.textDark.copy(alpha = 0.58f),
                            fontSize = 12.sp,
                            modifier = Modifier.weight(0.32f),
                        )
                        Text(
                            text = i18n(detail.value),
                            color = colors.textStrong,
                            fontSize = 12.sp,
                            modifier = Modifier.weight(0.68f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CloudActionButton(
    text: String,
    icon: ImageVector,
    primary: Boolean,
    enabled: Boolean,
    testTag: String,
    onClick: () -> Unit,
) {
    val colors = YamiboTheme.colors
    val contentColor = when {
        !enabled -> colors.textDark.copy(alpha = 0.38f)
        primary -> colors.textOnDeepHigh
        else -> colors.textOnSurface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    !enabled -> colors.brownLight.copy(alpha = 0.35f)
                    primary -> colors.brownDeep
                    else -> colors.creamSurface
                },
            )
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.size(10.dp))
        Text(
            text = text,
            color = contentColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun ApplyModeDialog(
    onDismiss: () -> Unit,
    onMerge: () -> Unit,
    onOverwrite: () -> Unit,
) {
    val colors = YamiboTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.creamSurface,
        titleContentColor = colors.textStrong,
        textContentColor = colors.textDark,
        title = { Text(i18n("選擇套用方式"), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ApplyModeOption(
                    title = i18n("合併"),
                    subtitle = i18n("保留較新的本機或雲端紀錄，不讓舊紀錄覆蓋新紀錄。"),
                    onClick = onMerge,
                )
                ApplyModeOption(
                    title = i18n("覆蓋"),
                    subtitle = i18n("以雲端備份替換全部同步資料，下一步會再次確認。"),
                    onClick = onOverwrite,
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            YamiboActionChip(text = i18n("取消"), onClick = onDismiss)
        },
    )
}

@Composable
private fun ApplyModeOption(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    val colors = YamiboTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.brownLight.copy(alpha = 0.18f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, color = colors.textStrong, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(subtitle, color = colors.textDark.copy(alpha = 0.66f), fontSize = 12.sp)
    }
}

@Composable
private fun DeleteCloudDataDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val colors = YamiboTheme.colors
    var secondStep by remember { mutableStateOf(false) }
    var remainingSeconds by remember { mutableStateOf(5) }
    LaunchedEffect(secondStep) {
        if (secondStep) {
            remainingSeconds = 5
            while (remainingSeconds > 0) {
                delay(1_000)
                remainingSeconds -= 1
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.creamSurface,
        titleContentColor = colors.textStrong,
        textContentColor = colors.textDark,
        title = {
            Text(
                text = if (secondStep) i18n("再次確認清除雲端資料") else i18n("清除雲端資料"),
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Text(
                text = if (secondStep) {
                    i18n("這是最後確認。清除後無法從雲端復原。")
                } else {
                    i18n("確定要清除雲端同步資料嗎？")
                },
            )
        },
        confirmButton = {
            val enabled = !secondStep || remainingSeconds == 0
            Box(
                modifier = Modifier.graphicsLayer { alpha = if (enabled) 1f else 0.4f },
            ) {
                YamiboActionChip(
                    text = when {
                        !secondStep -> i18n("繼續")
                        remainingSeconds > 0 -> i18n("{} 秒後可確認", remainingSeconds)
                        else -> i18n("確認清除")
                    },
                    selected = secondStep,
                    onClick = {
                        if (!enabled) return@YamiboActionChip
                        if (secondStep) onConfirm() else secondStep = true
                    },
                )
            }
        },
        dismissButton = {
            YamiboActionChip(text = i18n("取消"), onClick = onDismiss)
        },
    )
}

@Composable
private fun statusColor(status: CloudSyncStatus): Color = when (status) {
    CloudSyncStatus.Checking -> YamiboTheme.colors.brownPrimary
    CloudSyncStatus.Available -> Color(0xFF3E7A4C)
    CloudSyncStatus.Missing -> YamiboTheme.colors.brownPrimary
    CloudSyncStatus.Unavailable -> YamiboTheme.colors.redAccent
}

@Composable
private fun operationText(
    operation: CloudSyncOperation,
    target: CloudSyncOperation,
): String? = if (operation == target) i18n("處理中...") else null

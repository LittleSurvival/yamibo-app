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
import androidx.compose.runtime.rememberCoroutineScope
import me.thenano.yamibo.yamibo_app.LocalAppSyncService
import me.thenano.yamibo.yamibo_app.LocalAppSyncBackgroundScheduler
import me.thenano.yamibo.yamibo_app.components.controls.YamiboActionChip
import me.thenano.yamibo.yamibo_app.components.navigation.YamiboTopBar
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme
import me.thenano.yamibo.yamibo_app.i18n.i18n
import me.thenano.yamibo.yamibo_app.navigation.LocalNavigator

@Composable
internal fun AppSyncSettingsScreen(
    controller: CloudSyncUiController? = null,
) {
    val navigator = LocalNavigator.current
    val service = LocalAppSyncService.current
    val scheduler = LocalAppSyncBackgroundScheduler.current
    val scope = rememberCoroutineScope()
    val activeController = controller ?: remember(service, scope, scheduler) {
        service?.let { AppSyncCloudUiController(it, scope, scheduler) } ?: StubCloudSyncUiController
    }
    val state by activeController.state.collectAsState()

    AppSyncSettingsContent(
        state = state,
        onBack = { navigator.pop() },
        onRefresh = activeController::refresh,
        onDeleteCloud = activeController::deleteCloudData,
        onAutomaticEnabledChange = activeController::setAutomaticEnabled,
        onSyncNow = activeController::syncNow,
        onRequestForce = activeController::requestForceOverride,
        onConfirmForce = activeController::confirmForceOverride,
        onClearForcePreview = activeController::clearForcePreview,
    )
}

@Composable
internal fun AppSyncSettingsContent(
    state: CloudSyncUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onDeleteCloud: () -> Unit,
    onAutomaticEnabledChange: (Boolean) -> Unit,
    onSyncNow: () -> Unit,
    onRequestForce: (CloudSyncForceDirection) -> Unit,
    onConfirmForce: (CloudSyncForcePreview) -> Unit,
    onClearForcePreview: () -> Unit,
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
                    text = i18n("同步資料"),
                    color = colors.textStrong,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = i18n("設定、收藏與閱讀紀錄會以操作紀錄自動合併；不會以空白或舊快照覆蓋雲端。"),
                    color = colors.textDark.copy(alpha = 0.68f),
                    fontSize = 13.sp,
                )
            }
            HorizontalDivider(color = colors.brownLight.copy(alpha = 0.2f))
            SyncDetailsSection(
                expanded = detailsExpanded,
                details = state.details,
                changes = state.changes,
                onExpandedChange = { detailsExpanded = it },
            )
            HorizontalDivider(color = colors.brownLight.copy(alpha = 0.2f))
            ManualOverrideSection(
                state = state,
                onRequestForce = onRequestForce,
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
                    color = colors.redAccent.copy(alpha = if (enabled) 1f else 0.6f),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
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
    state.forcePreview?.let { preview ->
        ForceOverrideDialog(
            preview = preview,
            onDismiss = onClearForcePreview,
            onConfirm = { onConfirmForce(preview) },
        )
    }
}

@Composable
private fun ManualOverrideSection(
    state: CloudSyncUiState,
    onRequestForce: (CloudSyncForceDirection) -> Unit,
) {
    val colors = YamiboTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 18.dp)
            .testTag("app_sync_manual_override"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = i18n("進階資料操作"),
            color = colors.textStrong,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = i18n("僅在一般同步無法解決資料差異時使用。確認前會重新比較本機與雲端。"),
            color = colors.textDark.copy(alpha = 0.68f),
            fontSize = 13.sp,
        )
        CloudActionButton(
            text = if (state.forcePreviewLoading) i18n("正在比較資料...") else i18n("強制上傳本機資料"),
            icon = YamiboIcons.Sync,
            primary = false,
            enabled = state.actionsAvailable && !state.isBusy && !state.forcePreviewLoading,
            testTag = "app_sync_force_push",
            onClick = { onRequestForce(CloudSyncForceDirection.Push) },
        )
        CloudActionButton(
            text = if (state.forcePreviewLoading) i18n("正在比較資料...") else i18n("強制載入雲端資料"),
            icon = YamiboIcons.Download,
            primary = false,
            enabled = state.actionsAvailable && !state.isBusy && !state.forcePreviewLoading,
            testTag = "app_sync_force_pull",
            onClick = { onRequestForce(CloudSyncForceDirection.Pull) },
        )
        state.forceError?.let {
            Text(
                text = i18n(it),
                color = colors.redAccent,
                fontSize = 12.sp,
                modifier = Modifier.testTag("app_sync_force_error"),
            )
        }
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
        CloudSyncNoticeSeverity.Success -> colors.brownPrimary
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
    changes: List<CloudSyncChangeDetail>,
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
                details.forEach { detail ->
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
                if (changes.isNotEmpty()) {
                    HorizontalDivider(color = colors.brownLight.copy(alpha = 0.2f))
                    changes.forEach { change ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = i18n(change.direction),
                                color = colors.textDark.copy(alpha = 0.58f),
                                fontSize = 12.sp,
                                modifier = Modifier.weight(0.32f),
                            )
                            Text(
                                text = i18n("{}：{}", change.module, change.summary),
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
                    enabled = enabled,
                    onClick = {
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
private fun ForceOverrideDialog(
    preview: CloudSyncForcePreview,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val colors = YamiboTheme.colors
    var remainingSeconds by remember(preview.token) { mutableStateOf(10) }
    LaunchedEffect(preview.token) {
        remainingSeconds = 10
        while (remainingSeconds > 0) {
            delay(1_000)
            remainingSeconds -= 1
        }
    }
    val isPush = preview.direction == CloudSyncForceDirection.Push
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.creamSurface,
        titleContentColor = colors.textStrong,
        textContentColor = colors.textDark,
        title = {
            Text(
                text = if (isPush) i18n("確認強制上傳") else i18n("確認強制載入"),
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = if (isPush) {
                        i18n("本機資料將成為權威；雲端獨有資料會被明確刪除。")
                    } else {
                        i18n("雲端資料將成為權威；未上傳的本機差異會被捨棄。")
                    },
                    color = colors.redAccent,
                    fontSize = 13.sp,
                )
                if (preview.differences.isEmpty()) {
                    Text(i18n("本機與雲端目前沒有差異。"), fontSize = 13.sp)
                } else {
                    preview.differences.forEach { difference ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = i18n(difference.module),
                                color = colors.textStrong,
                                fontSize = 12.sp,
                                modifier = Modifier.weight(0.38f),
                            )
                            Text(
                                text = i18n(difference.summary),
                                color = colors.textDark,
                                fontSize = 12.sp,
                                modifier = Modifier.weight(0.62f),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            YamiboActionChip(
                text = if (remainingSeconds > 0) {
                    i18n("{} 秒後可確認", remainingSeconds)
                } else {
                    if (isPush) i18n("確認強制上傳") else i18n("確認強制載入")
                },
                selected = true,
                enabled = forceConfirmationEnabled(remainingSeconds),
                onClick = onConfirm,
            )
        },
        dismissButton = {
            YamiboActionChip(text = i18n("取消"), onClick = onDismiss)
        },
    )
}

internal fun forceConfirmationEnabled(remainingSeconds: Int): Boolean =
    remainingSeconds <= 0

@Composable
private fun statusColor(status: CloudSyncStatus): Color = when (status) {
    CloudSyncStatus.Checking -> YamiboTheme.colors.brownPrimary
    CloudSyncStatus.Available -> YamiboTheme.colors.brownPrimary
    CloudSyncStatus.Missing -> YamiboTheme.colors.brownPrimary
    CloudSyncStatus.Unavailable -> YamiboTheme.colors.redAccent
}

@Composable
private fun operationText(
    operation: CloudSyncOperation,
    target: CloudSyncOperation,
): String? = if (operation == target) i18n("處理中...") else null

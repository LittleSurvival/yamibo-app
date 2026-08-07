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
import me.thenano.yamibo.yamibo_app.i18n.localizedLabel
import me.thenano.yamibo.yamibo_app.navigation.LocalNavigator
import me.thenano.yamibo.yamibo_app.profile.settings.components.SettingsChipRow
import me.thenano.yamibo.yamibo_app.repository.appsync.AppSyncChangeAction
import me.thenano.yamibo.yamibo_app.repository.appsync.AppSyncChangeDirection
import me.thenano.yamibo.yamibo_app.repository.appsync.AppSyncJournalRetirementMessage
import me.thenano.yamibo.yamibo_app.repository.appsync.AppSyncServicePhase
import me.thenano.yamibo.yamibo_app.repository.appsync.AppSyncStatusMessage
import me.thenano.yamibo.yamibo_app.util.time.FixedScheduleInterval
import kotlin.time.Duration.Companion.milliseconds

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
        onClearCloudLinkCache = activeController::clearCloudLinkCache,
        onDeleteCloud = activeController::deleteCloudData,
        onAutomaticEnabledChange = activeController::setAutomaticEnabled,
        onSyncOnAppStartChange = activeController::setSyncOnAppStart,
        onSyncOnForegroundExitChange = activeController::setSyncOnForegroundExit,
        onPeriodicIntervalChange = activeController::setPeriodicInterval,
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
    onClearCloudLinkCache: () -> Unit,
    onDeleteCloud: () -> Unit,
    onAutomaticEnabledChange: (Boolean) -> Unit,
    onSyncOnAppStartChange: (Boolean) -> Unit,
    onSyncOnForegroundExitChange: (Boolean) -> Unit,
    onPeriodicIntervalChange: (FixedScheduleInterval) -> Unit,
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
                .padding(20.dp)
                .testTag("app_sync_screen"),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CloudCard {
                CloudStatusRow(state = state, onRefresh = onRefresh)
            }
            state.notice?.let { notice ->
                CloudSyncInlineNotice(notice)
            }
            CloudCard {
                AutomaticSyncSection(
                    state = state,
                    onEnabledChange = onAutomaticEnabledChange,
                    onSyncOnAppStartChange = onSyncOnAppStartChange,
                    onSyncOnForegroundExitChange = onSyncOnForegroundExitChange,
                    onPeriodicIntervalChange = onPeriodicIntervalChange,
                    onSyncNow = onSyncNow,
                )
            }
            CloudCard {
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
            CloudCard(contentPadding = 0.dp) {
                SyncDetailsSection(
                    expanded = detailsExpanded,
                    details = state.details,
                    changes = state.changes,
                    onExpandedChange = { detailsExpanded = it },
                )
            }
            CloudCard(contentPadding = 0.dp) {
                ManualOverrideSection(
                    state = state,
                    onRequestForce = onRequestForce,
                )
            }
            CloudCard(contentPadding = 0.dp) {
                TextButton(
                    onClick = onClearCloudLinkCache,
                    enabled = state.actionsAvailable && !state.isBusy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .testTag("app_sync_clear_link_cache"),
                ) {
                    Text(
                        text = i18n("清除雲端連結紀錄快取"),
                        color = colors.textStrong.copy(
                            alpha = if (state.actionsAvailable && !state.isBusy) 1f else 0.6f,
                        ),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                TextButton(
                    onClick = { showDeleteConfirmation = true },
                    enabled = state.actionsAvailable && state.cloudDataExists && !state.isBusy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp)
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
            .padding(16.dp)
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
        state.forceError?.let { error ->
            Text(
                text = cloudSyncForceErrorText(error),
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
            .height(80.dp)
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
                text = if (busy) {
                    i18n("處理中...")
                } else {
                    cloudSyncStatusHeadline(state.phase)
                },
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = colors.textStrong,
            )
            Text(
                text = appSyncStatusMessageText(state.statusMessage),
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
        text = appSyncStatusMessageText(notice.message),
        color = color,
        fontSize = 12.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.09f))
            .padding(16.dp)
            .testTag("app_sync_inline_notice"),
    )
}

@Composable
private fun AutomaticSyncSection(
    state: CloudSyncUiState,
    onEnabledChange: (Boolean) -> Unit,
    onSyncOnAppStartChange: (Boolean) -> Unit,
    onSyncOnForegroundExitChange: (Boolean) -> Unit,
    onPeriodicIntervalChange: (FixedScheduleInterval) -> Unit,
    onSyncNow: () -> Unit,
) {
    val colors = YamiboTheme.colors
    val childEnabled = state.automaticEnabled && state.automaticAvailable && !state.isBusy
    Column(
        modifier = Modifier
            .fillMaxWidth()
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
                    text = state.automaticStatus.localizedLabel(),
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
        AutomaticSyncOptionSwitch(
            title = i18n("App 啟動時同步"),
            checked = state.syncOnAppStart,
            enabled = childEnabled,
            testTag = "app_sync_on_start_toggle",
            onCheckedChange = onSyncOnAppStartChange,
        )
        AutomaticSyncOptionSwitch(
            title = i18n("離開前台時同步"),
            checked = state.syncOnForegroundExit,
            enabled = childEnabled,
            testTag = "app_sync_on_foreground_exit_toggle",
            onCheckedChange = onSyncOnForegroundExitChange,
        )
        Text(
            text = i18n("背景同步週期"),
            color = colors.textStrong.copy(alpha = if (childEnabled) 1f else 0.42f),
            fontSize = 14.sp,
            modifier = Modifier
                .padding(top = 4.dp)
                .testTag("app_sync_periodic_interval"),
        )
        SettingsChipRow(
            options = state.periodicIntervalOptions.map { it to it.localizedLabel() },
            selectedValue = state.periodicInterval,
            onSelect = { if (childEnabled) onPeriodicIntervalChange(it) },
            modifier = Modifier.graphicsLayer {
                alpha = if (childEnabled) 1f else 0.42f
            },
        )
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
private fun AutomaticSyncOptionSwitch(
    title: String,
    checked: Boolean,
    enabled: Boolean,
    testTag: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    val colors = YamiboTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            color = colors.textStrong.copy(alpha = if (enabled) 1f else 0.42f),
            fontSize = 14.sp,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            modifier = Modifier.testTag(testTag),
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
                .padding(horizontal = 16.dp)
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
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                    .testTag("app_sync_details_content"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                details.forEach { detail ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = detail.label.localizedLabel(),
                            color = colors.textDark.copy(alpha = 0.58f),
                            fontSize = 12.sp,
                            modifier = Modifier.weight(0.32f),
                        )
                        Text(
                            text = cloudSyncDetailValueText(detail.value),
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
                                text = cloudSyncDirectionText(change.direction),
                                color = colors.textDark.copy(alpha = 0.58f),
                                fontSize = 12.sp,
                                modifier = Modifier.weight(0.32f),
                            )
                            Text(
                                text = buildString {
                                    append(
                                        i18n(
                                            "{}：{}",
                                            change.module.localizedLabel(),
                                            cloudSyncActionText(change.action, change.count),
                                        ),
                                    )
                                    if (change.details.isNotEmpty()) {
                                        appendLine()
                                        append(change.details.joinToString(i18n("、")))
                                    }
                                    if (change.remainingDetailCount > 0) {
                                        append(
                                            i18n(
                                                "，另有 {} 筆",
                                                change.remainingDetailCount.toString(),
                                            ),
                                        )
                                    }
                                },
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
private fun CloudCard(
    contentPadding: androidx.compose.ui.unit.Dp = 16.dp,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(YamiboTheme.colors.creamSurface)
            .padding(contentPadding),
        content = content,
    )
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
                delay(1_000.milliseconds)
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
            delay(1_000.milliseconds)
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
                                text = difference.module.localizedLabel(),
                                color = colors.textStrong,
                                fontSize = 12.sp,
                                modifier = Modifier.weight(0.38f),
                            )
                            Text(
                                text = buildString {
                                    append(cloudSyncForceDifferenceText(difference))
                                    if (difference.details.isNotEmpty()) {
                                        appendLine()
                                        append(difference.details.joinToString(i18n("、")))
                                    }
                                    if (difference.remainingDetailCount > 0) {
                                        append(
                                            i18n(
                                                "，另有 {} 筆",
                                                difference.remainingDetailCount.toString(),
                                            ),
                                        )
                                    }
                                },
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

private fun cloudSyncStatusHeadline(phase: AppSyncServicePhase?): String = when (phase) {
    null -> i18n("同步核心尚未連接")
    AppSyncServicePhase.Disabled -> i18n("尚未啟用")
    AppSyncServicePhase.BootstrapRequired -> i18n("需要安全載入")
    AppSyncServicePhase.Running -> i18n("同步中")
    AppSyncServicePhase.Active -> i18n("同步就緒")
    AppSyncServicePhase.PausedAuth -> i18n("登入狀態需要刷新")
    AppSyncServicePhase.PausedProvider -> i18n("雲端暫時無法使用")
    AppSyncServicePhase.Quarantined -> i18n("有資料需要檢查")
    AppSyncServicePhase.RetryPending -> i18n("等待重試")
}

private fun appSyncStatusMessageText(message: AppSyncStatusMessage): String = when (message) {
    AppSyncStatusMessage.NotStarted -> i18n("尚未開始同步")
    AppSyncStatusMessage.CoreNotAvailable -> i18n("同步核心目前無法使用")
    AppSyncStatusMessage.QuarantinedRefresh ->
        i18n("同步資料已隔離；重新檢查不會修改本機資料")
    AppSyncStatusMessage.QuarantinedManualSync ->
        i18n("同步資料已隔離；手動同步不會修改本機資料")
    AppSyncStatusMessage.UnexpectedFailure ->
        i18n("同步發生未預期錯誤，已保留待同步操作並排定重試")
    AppSyncStatusMessage.AutomaticSyncScheduled -> i18n("已排定自動同步")
    AppSyncStatusMessage.AutomaticSyncDisabled -> i18n("自動同步已關閉")
    AppSyncStatusMessage.ScheduleUpdated -> i18n("自動同步排程設定已更新")
    AppSyncStatusMessage.AppStartupSyncScheduled -> i18n("已排定 App 啟動同步")
    AppSyncStatusMessage.ForegroundExitSyncScheduled -> i18n("已排定離開前台同步")
    AppSyncStatusMessage.ClearingCloudData -> i18n("正在驗證並清除雲端同步資料")
    is AppSyncStatusMessage.CloudDataCleared ->
        i18n("已清除 {} 筆雲端同步資料；本機資料已排入安全重建", message.count)
    AppSyncStatusMessage.CloudResetAuthExpired ->
        i18n("登入狀態已過期；重新整理登入後會先載入仍存活的雲端資料")
    is AppSyncStatusMessage.CloudResetIncomplete ->
        i18n("雲端暫時無法使用")
    is AppSyncStatusMessage.CloudLinkCacheCleared ->
        i18n("已清除 {} 筆雲端連結紀錄；下次同步會重新驗證最新索引", message.count)
    AppSyncStatusMessage.ForcePushRunning -> i18n("正在執行強制上傳並驗證雲端結果")
    AppSyncStatusMessage.ForcePullRunning -> i18n("正在載入並套用已驗證的雲端狀態")
    is AppSyncStatusMessage.ForcePullCompleted ->
        i18n("強制載入完成：已套用 {} 項差異", message.count)
    AppSyncStatusMessage.ForcePreviewStale ->
        i18n("本機或雲端資料已變更，請重新檢視差異後再確認")
    AppSyncStatusMessage.SafeLoadRunning ->
        i18n("正在安全載入雲端紀錄，本階段不會上傳本機資料")
    is AppSyncStatusMessage.SafeLoadCompleted ->
        i18n("安全載入完成，套用 {} 筆操作", message.count)
    is AppSyncStatusMessage.SafeLoadCompletedWithSkippedRssHistory ->
        i18n(
            "安全載入完成，套用 {} 筆操作；保留 {} 筆無法解析來源的舊 RSS 閱讀紀錄於本機",
            message.appliedCount,
            message.skippedCount,
        )
    AppSyncStatusMessage.SyncRunning -> i18n("正在同步操作紀錄")
    is AppSyncStatusMessage.SyncCompleted ->
        i18n(
            "同步完成：接收 {}、確認 {}",
            message.receivedCount,
            message.acknowledgedCount,
        )
    AppSyncStatusMessage.SyncAlreadyRunning -> i18n("已有同步工作執行中")
    AppSyncStatusMessage.AuthenticationExpired -> i18n("登入狀態已過期，請先刷新登入狀態")
    // External values are provider/engine diagnostics, not localization keys. Keep them in the
    // service status for logs and tests, but never leak untranslated implementation text into UI.
    is AppSyncStatusMessage.External -> i18n("同步失敗")
}

private fun cloudSyncDetailValueText(value: CloudSyncDetailValue): String = when (value) {
    is CloudSyncDetailValue.Phase -> cloudSyncPhaseText(value.value)
    is CloudSyncDetailValue.Timestamp -> value.value
    is CloudSyncDetailValue.Automatic -> value.value.localizedLabel()
    is CloudSyncDetailValue.Count -> value.value.toString()
    is CloudSyncDetailValue.Journal -> appSyncJournalRetirementText(value.value)
    is CloudSyncDetailValue.StatusMessage -> appSyncStatusMessageText(value.value)
    CloudSyncDetailValue.NoRecord -> i18n("尚無紀錄")
}

private fun cloudSyncPhaseText(phase: AppSyncServicePhase): String = when (phase) {
    AppSyncServicePhase.Disabled -> i18n("停用")
    AppSyncServicePhase.BootstrapRequired -> i18n("等待安全載入")
    AppSyncServicePhase.Running -> i18n("執行中")
    AppSyncServicePhase.Active -> i18n("已收斂")
    AppSyncServicePhase.PausedAuth -> i18n("登入暫停")
    AppSyncServicePhase.PausedProvider -> i18n("供應端暫停")
    AppSyncServicePhase.Quarantined -> i18n("隔離")
    AppSyncServicePhase.RetryPending -> i18n("等待重試")
}

private fun appSyncJournalRetirementText(message: AppSyncJournalRetirementMessage): String =
    when (message) {
        is AppSyncJournalRetirementMessage.Observed ->
            i18n("已驗證 {} 個 Journal，尚無可清理項目", message.journalCount)
        is AppSyncJournalRetirementMessage.Candidate ->
            i18n("發現 {} 個安全清理候選；目前為只觀察模式", message.count)
        is AppSyncJournalRetirementMessage.Pending ->
            i18n("Journal 清理程序等待下一次重新驗證：{}", i18n("雲端暫時無法使用"))
        AppSyncJournalRetirementMessage.Completed ->
            i18n("已完成一個非活躍 Journal 的安全清理")
        AppSyncJournalRetirementMessage.PausedAuth ->
            i18n("登入狀態不足，Journal 清理已暫停")
        AppSyncJournalRetirementMessage.AlreadyRunning ->
            i18n("已有同步或 Journal 維護工作執行中")
        // Retirement diagnostics may contain provider text and therefore stay out of the UI.
        is AppSyncJournalRetirementMessage.External -> i18n("雲端暫時無法使用")
    }

private fun cloudSyncDirectionText(direction: AppSyncChangeDirection): String = when (direction) {
    AppSyncChangeDirection.Received -> i18n("從雲端套用")
    AppSyncChangeDirection.Uploaded -> i18n("上傳至雲端")
}

private fun cloudSyncActionText(action: AppSyncChangeAction, count: Int): String = when (action) {
    AppSyncChangeAction.Added -> i18n("新增 {}", count)
    AppSyncChangeAction.Updated -> i18n("更新 {}", count)
    AppSyncChangeAction.Deleted -> i18n("刪除 {}", count)
    AppSyncChangeAction.Enabled -> i18n("開啟 {}", count)
    AppSyncChangeAction.Disabled -> i18n("關閉 {}", count)
    AppSyncChangeAction.Read -> i18n("標為已讀 {}", count)
    AppSyncChangeAction.Dismissed -> i18n("忽略 {}", count)
}

private fun cloudSyncForceDifferenceText(difference: CloudSyncForceDifference): String =
    buildList {
        if (difference.added > 0) add(i18n("新增 {}", difference.added))
        if (difference.updated > 0) add(i18n("更新 {}", difference.updated))
        if (difference.deleted > 0) add(i18n("刪除 {}", difference.deleted))
        if (difference.enabled > 0) add(i18n("開啟 {}", difference.enabled))
        if (difference.disabled > 0) add(i18n("關閉 {}", difference.disabled))
    }.joinToString(i18n("、"))

private fun cloudSyncForceErrorText(error: CloudSyncForceError): String = when (error) {
    CloudSyncForceError.StalePreview ->
        i18n("本機或雲端資料已變更，請重新檢視差異後再確認")
    CloudSyncForceError.CoreUnavailable -> i18n("同步核心尚未啟用")
    CloudSyncForceError.AuthenticationExpired -> i18n("登入狀態已過期，請先刷新登入狀態")
    // Force-operation diagnostics remain available to the controller, while UI gets a stable key.
    is CloudSyncForceError.External -> i18n("同步失敗")
}

@Composable
private fun statusColor(status: CloudSyncStatus): Color = when (status) {
    CloudSyncStatus.Checking -> YamiboTheme.colors.brownPrimary
    CloudSyncStatus.Available -> YamiboTheme.colors.brownPrimary
    CloudSyncStatus.Missing -> YamiboTheme.colors.brownPrimary
    CloudSyncStatus.Unavailable -> YamiboTheme.colors.redAccent
}

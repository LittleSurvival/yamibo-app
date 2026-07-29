package me.thenano.yamibo.yamibo_app.profile.settings.cloud

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import me.thenano.yamibo.yamibo_app.repository.appsync.AppSyncService
import me.thenano.yamibo.yamibo_app.repository.appsync.AppSyncServicePhase
import me.thenano.yamibo.yamibo_app.repository.appsync.AppSyncServiceStatus
import me.thenano.yamibo.yamibo_app.repository.appsync.AppSyncChangeAction
import me.thenano.yamibo.yamibo_app.repository.appsync.AppSyncChangeDirection
import me.thenano.yamibo.yamibo_app.repository.appsync.AppSyncForceApplyResult
import me.thenano.yamibo.yamibo_app.repository.appsync.AppSyncForceDirection
import me.thenano.yamibo.yamibo_app.repository.appsync.AppSyncForcePreview
import me.thenano.yamibo.yamibo_app.repository.appsync.AppSyncForcePreviewResult
import me.thenano.yamibo.yamibo_app.appsync.AppSyncBackgroundScheduler
import me.thenano.yamibo.yamibo_app.util.time.formatDateTime

internal enum class CloudSyncStatus {
    Checking,
    Available,
    Missing,
    Unavailable,
}

internal enum class CloudSyncOperation {
    Idle,
    Refreshing,
    Uploading,
    Loading,
    Syncing,
    Deleting,
}

internal enum class CloudSyncNoticeSeverity {
    Info,
    Success,
    Warning,
    Error,
}

internal data class CloudSyncNotice(
    val message: String,
    val severity: CloudSyncNoticeSeverity,
)

internal data class CloudSyncDetail(
    val label: String,
    val value: String,
)

internal data class CloudSyncChangeDetail(
    val direction: String,
    val module: String,
    val summary: String,
)

internal enum class CloudSyncForceDirection {
    Push,
    Pull,
}

internal data class CloudSyncForceDifference(
    val domainId: String,
    val module: String,
    val summary: String,
    val added: Int,
    val updated: Int,
    val deleted: Int,
    val enabled: Int,
    val disabled: Int,
)

internal data class CloudSyncForcePreview(
    val direction: CloudSyncForceDirection,
    val token: String,
    val differences: List<CloudSyncForceDifference>,
)

internal data class CloudSyncUiState(
    val status: CloudSyncStatus = CloudSyncStatus.Unavailable,
    val statusHeadline: String = "同步核心尚未連接",
    val statusSupport: String = "介面已就緒，雲端同步功能將由新架構提供",
    val operation: CloudSyncOperation = CloudSyncOperation.Idle,
    val automaticEnabled: Boolean = false,
    val automaticAvailable: Boolean = false,
    val automaticStatus: String = "背景同步尚未提供",
    val actionsAvailable: Boolean = false,
    val cloudDataExists: Boolean = false,
    val notice: CloudSyncNotice? = null,
    val changes: List<CloudSyncChangeDetail> = emptyList(),
    val forcePreview: CloudSyncForcePreview? = null,
    val forcePreviewLoading: Boolean = false,
    val forceError: String? = null,
    val details: List<CloudSyncDetail> = listOf(
        CloudSyncDetail("雲端備份", "尚未連接"),
        CloudSyncDetail("最後驗證", "尚無紀錄"),
        CloudSyncDetail("自動同步", "尚未提供"),
        CloudSyncDetail("本機變更", "尚未檢查"),
        CloudSyncDetail("最近結果", "尚無紀錄"),
    ),
) {
    val isBusy: Boolean
        get() = operation != CloudSyncOperation.Idle
}

internal interface CloudSyncUiController {
    val state: StateFlow<CloudSyncUiState>

    fun refresh()
    fun deleteCloudData()
    fun setAutomaticEnabled(enabled: Boolean)
    fun syncNow()
    fun requestForceOverride(direction: CloudSyncForceDirection)
    fun confirmForceOverride(preview: CloudSyncForcePreview)
    fun clearForcePreview()
}

internal object StubCloudSyncUiController : CloudSyncUiController {
    override val state = MutableStateFlow(CloudSyncUiState())

    override fun refresh() = Unit
    override fun deleteCloudData() = Unit
    override fun setAutomaticEnabled(enabled: Boolean) = Unit
    override fun syncNow() = Unit
    override fun requestForceOverride(direction: CloudSyncForceDirection) = Unit
    override fun confirmForceOverride(preview: CloudSyncForcePreview) = Unit
    override fun clearForcePreview() = Unit
}

internal class AppSyncCloudUiController(
    private val service: AppSyncService,
    private val scope: CoroutineScope,
    private val scheduler: AppSyncBackgroundScheduler?,
) : CloudSyncUiController {
    private var serviceState = service.currentStatus()
    private var forcePreview: CloudSyncForcePreview? = null
    private var forcePreviewLoading = false
    private var forceError: String? = null
    private val mutableState = MutableStateFlow(
        serviceState.toUiState(backgroundSchedulerAvailable = scheduler != null),
    )
    override val state: StateFlow<CloudSyncUiState> = mutableState

    init {
        service.status.onEach {
            serviceState = it
            publishState()
        }.launchIn(scope)
    }

    override fun refresh() {
        scope.launch { service.refresh(forceDiscovery = true) }
    }

    override fun deleteCloudData() {
        scope.launch { service.deleteCloudData() }
    }

    override fun setAutomaticEnabled(enabled: Boolean) {
        if (enabled && scheduler == null) return
        service.setAutomaticEnabled(enabled)
        scope.launch { scheduler?.setEnabled(enabled) }
    }

    override fun syncNow() {
        scope.launch { service.synchronizeNow() }
    }

    override fun requestForceOverride(direction: CloudSyncForceDirection) {
        if (forcePreviewLoading) return
        forcePreview = null
        forceError = null
        forcePreviewLoading = true
        publishState()
        scope.launch {
            when (
                val result = service.previewForceOverride(
                    if (direction == CloudSyncForceDirection.Push) {
                        AppSyncForceDirection.Push
                    } else {
                        AppSyncForceDirection.Pull
                    },
                )
            ) {
                is AppSyncForcePreviewResult.Ready -> {
                    forcePreview = result.preview.toUi()
                    forceError = null
                }
                is AppSyncForcePreviewResult.Failed -> {
                    forcePreview = null
                    forceError = result.reason
                }
            }
            forcePreviewLoading = false
            publishState()
        }
    }

    override fun confirmForceOverride(preview: CloudSyncForcePreview) {
        if (forcePreviewLoading || preview != forcePreview) return
        forcePreview = null
        forceError = null
        forcePreviewLoading = true
        publishState()
        scope.launch {
            when (val result = service.applyForceOverride(preview.toService())) {
                is AppSyncForceApplyResult.Applied -> forceError = null
                AppSyncForceApplyResult.StalePreview ->
                    forceError = "本機或雲端資料已變更，請重新檢視差異後再確認"
                is AppSyncForceApplyResult.Failed -> forceError = result.reason
            }
            forcePreviewLoading = false
            publishState()
        }
    }

    override fun clearForcePreview() {
        forcePreview = null
        publishState()
    }

    private fun publishState() {
        mutableState.value = serviceState
            .toUiState(backgroundSchedulerAvailable = scheduler != null)
            .copy(
                forcePreview = forcePreview,
                forcePreviewLoading = forcePreviewLoading,
                forceError = forceError,
            )
    }
}

private fun AppSyncForcePreview.toUi() = CloudSyncForcePreview(
    direction = if (direction == AppSyncForceDirection.Push) {
        CloudSyncForceDirection.Push
    } else {
        CloudSyncForceDirection.Pull
    },
    token = token,
    differences = differences.map { difference ->
        CloudSyncForceDifference(
            domainId = difference.domainId,
            module = moduleLabel(difference.domainId),
            summary = buildList {
                if (difference.added > 0) add("新增 ${difference.added}")
                if (difference.updated > 0) add("更新 ${difference.updated}")
                if (difference.deleted > 0) add("刪除 ${difference.deleted}")
                if (difference.enabled > 0) add("開啟 ${difference.enabled}")
                if (difference.disabled > 0) add("關閉 ${difference.disabled}")
            }.joinToString("、"),
            added = difference.added,
            updated = difference.updated,
            deleted = difference.deleted,
            enabled = difference.enabled,
            disabled = difference.disabled,
        )
    },
)

private fun CloudSyncForcePreview.toService() = AppSyncForcePreview(
    direction = if (direction == CloudSyncForceDirection.Push) {
        AppSyncForceDirection.Push
    } else {
        AppSyncForceDirection.Pull
    },
    token = token,
    differences = differences.map {
        me.thenano.yamibo.yamibo_app.repository.appsync.AppSyncForceDifference(
            domainId = it.domainId,
            added = it.added,
            updated = it.updated,
            deleted = it.deleted,
            enabled = it.enabled,
            disabled = it.disabled,
        )
    },
)

internal fun AppSyncServiceStatus.toUiState(
    backgroundSchedulerAvailable: Boolean,
): CloudSyncUiState {
    val busy = phase == AppSyncServicePhase.Running
    val available = phase == AppSyncServicePhase.Active
    val needsAttention = phase in setOf(
        AppSyncServicePhase.PausedAuth,
        AppSyncServicePhase.PausedProvider,
        AppSyncServicePhase.Quarantined,
        AppSyncServicePhase.RetryPending,
    )
    return CloudSyncUiState(
        status = when {
            busy -> CloudSyncStatus.Checking
            available -> CloudSyncStatus.Available
            phase == AppSyncServicePhase.BootstrapRequired -> CloudSyncStatus.Missing
            else -> CloudSyncStatus.Unavailable
        },
        statusHeadline = when (phase) {
            AppSyncServicePhase.Disabled -> "尚未啟用"
            AppSyncServicePhase.BootstrapRequired -> "需要安全載入"
            AppSyncServicePhase.Running -> "同步中"
            AppSyncServicePhase.Active -> "同步就緒"
            AppSyncServicePhase.PausedAuth -> "登入狀態需要刷新"
            AppSyncServicePhase.PausedProvider -> "雲端暫時無法使用"
            AppSyncServicePhase.Quarantined -> "有資料需要檢查"
            AppSyncServicePhase.RetryPending -> "等待重試"
        },
        statusSupport = message,
        operation = if (busy) CloudSyncOperation.Syncing else CloudSyncOperation.Idle,
        automaticEnabled = automaticEnabled,
        automaticAvailable = backgroundSchedulerAvailable &&
            phase in setOf(
                AppSyncServicePhase.Active,
                AppSyncServicePhase.BootstrapRequired,
                AppSyncServicePhase.RetryPending,
            ),
        automaticStatus = when {
            !backgroundSchedulerAvailable -> "此平台尚未提供背景同步"
            automaticEnabled -> "已啟用"
            else -> "已關閉"
        },
        actionsAvailable = !busy,
        cloudDataExists = available || lastVerifiedAtEpochMillis != null,
        notice = if (needsAttention) {
            CloudSyncNotice(message, CloudSyncNoticeSeverity.Warning)
        } else {
            null
        },
        details = listOf(
            CloudSyncDetail("同步狀態", statusLabel(phase)),
            CloudSyncDetail(
                "最後驗證",
                lastVerifiedAtEpochMillis?.let(::formatDateTime) ?: "尚無紀錄",
            ),
            CloudSyncDetail(
                "自動同步",
                when {
                    !backgroundSchedulerAvailable -> "此平台尚未提供背景同步"
                    automaticEnabled -> "已啟用"
                    else -> "已關閉"
                },
            ),
            CloudSyncDetail("待上傳操作", pendingOperationCount.toString()),
            CloudSyncDetail("最近結果", message),
        ),
        changes = changeSummaries.map {
            CloudSyncChangeDetail(
                direction = when (it.direction) {
                    AppSyncChangeDirection.Received -> "從雲端套用"
                    AppSyncChangeDirection.Uploaded -> "上傳至雲端"
                },
                module = moduleLabel(it.domainId),
                summary = "${actionLabel(it.action)} ${it.count}",
            )
        },
    )
}

private fun moduleLabel(domainId: String): String = when (domainId) {
    "settings" -> "設定"
    "favorite.item" -> "收藏項目"
    "favorite.category" -> "收藏分類"
    "favorite.collection" -> "收藏集合"
    "favorite.item-category" -> "收藏分類歸屬"
    "favorite.item-collection" -> "收藏集合歸屬"
    "detail-note" -> "詳細備註"
    "bookmark" -> "書籤"
    "reading.thread" -> "文章閱讀進度"
    "reading.image" -> "圖片閱讀進度"
    "reading.tag-manga" -> "標籤漫畫進度"
    "reading.time" -> "閱讀時間"
    "favorite.update-event" -> "最近更新"
    "favorite.update-fid-filter" -> "版塊更新範圍"
    "favorite.update-category-filter" -> "分類更新範圍"
    else -> domainId
}

private fun actionLabel(action: AppSyncChangeAction): String = when (action) {
    AppSyncChangeAction.Added -> "新增"
    AppSyncChangeAction.Updated -> "更新"
    AppSyncChangeAction.Deleted -> "刪除"
    AppSyncChangeAction.Enabled -> "開啟"
    AppSyncChangeAction.Disabled -> "關閉"
    AppSyncChangeAction.Read -> "標為已讀"
    AppSyncChangeAction.Dismissed -> "忽略"
}

private fun statusLabel(phase: AppSyncServicePhase): String = when (phase) {
    AppSyncServicePhase.Disabled -> "停用"
    AppSyncServicePhase.BootstrapRequired -> "等待安全載入"
    AppSyncServicePhase.Running -> "執行中"
    AppSyncServicePhase.Active -> "已收斂"
    AppSyncServicePhase.PausedAuth -> "登入暫停"
    AppSyncServicePhase.PausedProvider -> "供應端暫停"
    AppSyncServicePhase.Quarantined -> "隔離"
    AppSyncServicePhase.RetryPending -> "等待重試"
}

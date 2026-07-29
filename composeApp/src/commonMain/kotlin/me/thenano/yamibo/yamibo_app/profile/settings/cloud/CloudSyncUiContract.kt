package me.thenano.yamibo.yamibo_app.profile.settings.cloud

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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
    val pendingApplyChoice: Boolean = false,
    val notice: CloudSyncNotice? = null,
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
    fun upload()
    fun load()
    fun dismissApplyChoice()
    fun merge()
    fun overwrite()
    fun deleteCloudData()
    fun setAutomaticEnabled(enabled: Boolean)
    fun syncNow()
}

internal object StubCloudSyncUiController : CloudSyncUiController {
    override val state = MutableStateFlow(CloudSyncUiState())

    override fun refresh() = Unit
    override fun upload() = Unit
    override fun load() = Unit
    override fun dismissApplyChoice() = Unit
    override fun merge() = Unit
    override fun overwrite() = Unit
    override fun deleteCloudData() = Unit
    override fun setAutomaticEnabled(enabled: Boolean) = Unit
    override fun syncNow() = Unit
}

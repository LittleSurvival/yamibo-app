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
import me.thenano.yamibo.yamibo_app.repository.appsync.AppSyncStatusMessage
import me.thenano.yamibo.yamibo_app.repository.appsync.AppSyncJournalRetirementMessage
import me.thenano.yamibo.yamibo_app.repository.appsync.AppSyncChangeAction
import me.thenano.yamibo.yamibo_app.repository.appsync.AppSyncChangeDirection
import me.thenano.yamibo.yamibo_app.repository.appsync.AppSyncForceApplyResult
import me.thenano.yamibo.yamibo_app.repository.appsync.AppSyncForceDirection
import me.thenano.yamibo.yamibo_app.repository.appsync.AppSyncForceFailureKind
import me.thenano.yamibo.yamibo_app.repository.appsync.AppSyncForcePreview
import me.thenano.yamibo.yamibo_app.repository.appsync.AppSyncForcePreviewResult
import me.thenano.yamibo.yamibo_app.appsync.AppSyncBackgroundScheduler
import me.thenano.yamibo.yamibo_app.i18n.i18n
import me.thenano.yamibo.yamibo_app.util.time.formatDateTime
import me.thenano.yamibo.yamibo_app.repository.appsync.AppSyncPeriodicIntervals
import me.thenano.yamibo.yamibo_app.util.time.FixedScheduleInterval

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
    val message: AppSyncStatusMessage,
    val severity: CloudSyncNoticeSeverity,
)

internal enum class CloudSyncDetailLabel {
    SyncStatus,
    LastVerified,
    AutomaticSync,
    PendingUploads,
    JournalCleanup,
    LatestResult,
    ;

    fun localizedLabel(): String = when (this) {
        SyncStatus -> i18n("同步狀態")
        LastVerified -> i18n("最後驗證")
        AutomaticSync -> i18n("自動同步")
        PendingUploads -> i18n("待上傳操作")
        JournalCleanup -> i18n("Journal 清理")
        LatestResult -> i18n("最近結果")
    }
}

internal sealed interface CloudSyncDetailValue {
    data class Phase(val value: AppSyncServicePhase) : CloudSyncDetailValue
    data class Timestamp(val value: String) : CloudSyncDetailValue
    data class Automatic(val value: CloudSyncAutomaticStatus) : CloudSyncDetailValue
    data class Count(val value: Int) : CloudSyncDetailValue
    data class Journal(val value: AppSyncJournalRetirementMessage) : CloudSyncDetailValue
    data class StatusMessage(val value: AppSyncStatusMessage) : CloudSyncDetailValue
    data object NoRecord : CloudSyncDetailValue
}

internal data class CloudSyncDetail(
    val label: CloudSyncDetailLabel,
    val value: CloudSyncDetailValue,
)

internal enum class CloudSyncAutomaticStatus {
    Unsupported,
    Enabled,
    Disabled,
    ;

    fun localizedLabel(): String = when (this) {
        Unsupported -> i18n("此平台尚未提供背景同步")
        Enabled -> i18n("已啟用")
        Disabled -> i18n("已關閉")
    }
}

internal enum class CloudSyncModuleKind(val domainId: String?) {
    Settings("settings"),
    FavoriteItem("favorite.item"),
    RssSubscription("rss.search-subscription"),
    FavoriteCategory("favorite.category"),
    FavoriteCollection("favorite.collection"),
    FavoriteItemCategory("favorite.item-category"),
    FavoriteItemCollection("favorite.item-collection"),
    DetailNote("detail-note"),
    Bookmark("bookmark"),
    ReadingThread("reading.thread"),
    ReadingImage("reading.image"),
    ReadingTagManga("reading.tag-manga"),
    ReadingTagCatalog("reading.tag-catalog"),
    ReadingRssSearch("reading.rss-search"),
    ReadingRssCatalog("reading.rss-catalog"),
    ReadingTime("reading.time"),
    FavoriteUpdateEvent("favorite.update-event"),
    FavoriteUpdateFidFilter("favorite.update-fid-filter"),
    FavoriteUpdateCategoryFilter("favorite.update-category-filter"),
    Unknown(null),
    ;

    companion object {
        fun fromDomainId(domainId: String): CloudSyncModuleKind =
            entries.firstOrNull { it.domainId == domainId } ?: Unknown
    }

    fun localizedLabel(unknownDomainId: String): String = when (this) {
        Settings -> i18n("設定")
        FavoriteItem -> i18n("收藏項目")
        RssSubscription -> i18n("RSS 訂閱")
        FavoriteCategory -> i18n("收藏分類")
        FavoriteCollection -> i18n("收藏集合")
        FavoriteItemCategory -> i18n("收藏分類歸屬")
        FavoriteItemCollection -> i18n("收藏集合歸屬")
        DetailNote -> i18n("詳細備註")
        Bookmark -> i18n("書籤")
        ReadingThread -> i18n("文章閱讀進度")
        ReadingImage -> i18n("圖片閱讀進度")
        ReadingTagManga -> i18n("標籤漫畫進度")
        ReadingTagCatalog -> i18n("標籤目錄進度")
        ReadingRssSearch -> i18n("RSS 搜尋進度")
        ReadingRssCatalog -> i18n("RSS 目錄進度")
        ReadingTime -> i18n("閱讀時間")
        FavoriteUpdateEvent -> i18n("最近更新")
        FavoriteUpdateFidFilter -> i18n("版塊更新範圍")
        FavoriteUpdateCategoryFilter -> i18n("分類更新範圍")
        Unknown -> unknownDomainId
    }
}

internal data class CloudSyncModule(
    val kind: CloudSyncModuleKind,
    val domainId: String,
) {
    fun localizedLabel(): String = kind.localizedLabel(domainId)
}

internal data class CloudSyncChangeDetail(
    val direction: AppSyncChangeDirection,
    val module: CloudSyncModule,
    val action: AppSyncChangeAction,
    val count: Int,
    val details: List<String> = emptyList(),
    val remainingDetailCount: Int = 0,
)

internal enum class CloudSyncForceDirection {
    Push,
    Pull,
}

internal data class CloudSyncForceDifference(
    val domainId: String,
    val module: CloudSyncModule,
    val added: Int,
    val updated: Int,
    val deleted: Int,
    val enabled: Int,
    val disabled: Int,
    val details: List<String> = emptyList(),
    val remainingDetailCount: Int = 0,
)

internal data class CloudSyncForcePreview(
    val direction: CloudSyncForceDirection,
    val token: String,
    val differences: List<CloudSyncForceDifference>,
)

internal sealed interface CloudSyncForceError {
    data object StalePreview : CloudSyncForceError
    data object CoreUnavailable : CloudSyncForceError
    data object AuthenticationExpired : CloudSyncForceError
    /** Diagnostic-only value. The screen replaces it with a stable localized error message. */
    data class External(val value: String) : CloudSyncForceError
}

internal data class CloudSyncUiState(
    val status: CloudSyncStatus = CloudSyncStatus.Unavailable,
    val phase: AppSyncServicePhase? = null,
    val statusMessage: AppSyncStatusMessage = AppSyncStatusMessage.CoreNotAvailable,
    val operation: CloudSyncOperation = CloudSyncOperation.Idle,
    val automaticEnabled: Boolean = false,
    val automaticAvailable: Boolean = false,
    val automaticStatus: CloudSyncAutomaticStatus = CloudSyncAutomaticStatus.Unsupported,
    val syncOnAppStart: Boolean = false,
    val syncOnForegroundExit: Boolean = false,
    val periodicInterval: FixedScheduleInterval = FixedScheduleInterval.Hours6,
    val periodicIntervalOptions: List<FixedScheduleInterval> = AppSyncPeriodicIntervals,
    val actionsAvailable: Boolean = false,
    val cloudDataExists: Boolean = false,
    val notice: CloudSyncNotice? = null,
    val changes: List<CloudSyncChangeDetail> = emptyList(),
    val forcePreview: CloudSyncForcePreview? = null,
    val forcePreviewLoading: Boolean = false,
    val forceError: CloudSyncForceError? = null,
    val details: List<CloudSyncDetail> = listOf(
        CloudSyncDetail(
            CloudSyncDetailLabel.SyncStatus,
            CloudSyncDetailValue.StatusMessage(AppSyncStatusMessage.CoreNotAvailable),
        ),
        CloudSyncDetail(CloudSyncDetailLabel.LastVerified, CloudSyncDetailValue.NoRecord),
        CloudSyncDetail(
            CloudSyncDetailLabel.AutomaticSync,
            CloudSyncDetailValue.Automatic(CloudSyncAutomaticStatus.Unsupported),
        ),
        CloudSyncDetail(CloudSyncDetailLabel.PendingUploads, CloudSyncDetailValue.Count(0)),
        CloudSyncDetail(CloudSyncDetailLabel.LatestResult, CloudSyncDetailValue.NoRecord),
    ),
) {
    val isBusy: Boolean
        get() = operation != CloudSyncOperation.Idle
}

internal interface CloudSyncUiController {
    val state: StateFlow<CloudSyncUiState>

    fun refresh()
    fun clearCloudLinkCache()
    fun deleteCloudData()
    fun setAutomaticEnabled(enabled: Boolean)
    fun setSyncOnAppStart(enabled: Boolean)
    fun setSyncOnForegroundExit(enabled: Boolean)
    fun setPeriodicInterval(interval: FixedScheduleInterval)
    fun syncNow()
    fun requestForceOverride(direction: CloudSyncForceDirection)
    fun confirmForceOverride(preview: CloudSyncForcePreview)
    fun clearForcePreview()
}

internal object StubCloudSyncUiController : CloudSyncUiController {
    override val state = MutableStateFlow(CloudSyncUiState())

    override fun refresh() = Unit
    override fun clearCloudLinkCache() = Unit
    override fun deleteCloudData() = Unit
    override fun setAutomaticEnabled(enabled: Boolean) = Unit
    override fun setSyncOnAppStart(enabled: Boolean) = Unit
    override fun setSyncOnForegroundExit(enabled: Boolean) = Unit
    override fun setPeriodicInterval(interval: FixedScheduleInterval) = Unit
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
    private var forceError: CloudSyncForceError? = null
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
        scope.launch { service.refresh(forceDiscovery = false) }
    }

    override fun clearCloudLinkCache() {
        service.clearCloudLinkCache()
    }

    override fun deleteCloudData() {
        scope.launch { service.deleteCloudData() }
    }

    override fun setAutomaticEnabled(enabled: Boolean) {
        if (enabled && scheduler == null) return
        service.setAutomaticEnabled(enabled)
        scheduler?.setEnabled(enabled, service.currentStatus().scheduleSettings.periodicInterval)
    }

    override fun setSyncOnAppStart(enabled: Boolean) {
        updateScheduleSettings {
            it.copy(syncOnAppStart = enabled)
        }
    }

    override fun setSyncOnForegroundExit(enabled: Boolean) {
        updateScheduleSettings {
            it.copy(syncOnForegroundExit = enabled)
        }
    }

    override fun setPeriodicInterval(interval: FixedScheduleInterval) {
        updateScheduleSettings {
            it.copy(periodicInterval = interval)
        }
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
                    forceError = result.toUiError()
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
            forceError = when (val result = service.applyForceOverride(preview.toService())) {
                is AppSyncForceApplyResult.Applied -> null
                AppSyncForceApplyResult.StalePreview ->
                    CloudSyncForceError.StalePreview

                is AppSyncForceApplyResult.Failed ->
                    result.toUiError()
            }
            forcePreviewLoading = false
            publishState()
        }
    }

    override fun clearForcePreview() {
        forcePreview = null
        publishState()
    }

    private fun updateScheduleSettings(
        transform: (me.thenano.yamibo.yamibo_app.repository.appsync.AppSyncScheduleSettings) ->
            me.thenano.yamibo.yamibo_app.repository.appsync.AppSyncScheduleSettings,
    ) {
        val settings = transform(service.currentStatus().scheduleSettings)
        service.setScheduleSettings(settings)
        scheduler?.setEnabled(service.currentStatus().automaticEnabled, settings.periodicInterval)
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
            module = cloudSyncModule(difference.domainId),
            added = difference.added,
            updated = difference.updated,
            deleted = difference.deleted,
            enabled = difference.enabled,
            disabled = difference.disabled,
            details = difference.details,
            remainingDetailCount = difference.remainingDetailCount,
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
            details = it.details,
            remainingDetailCount = it.remainingDetailCount,
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
        phase = phase,
        statusMessage = presentationMessage,
        operation = if (busy) CloudSyncOperation.Syncing else CloudSyncOperation.Idle,
        automaticEnabled = automaticEnabled,
        automaticAvailable = backgroundSchedulerAvailable &&
            phase in setOf(
                AppSyncServicePhase.Active,
                AppSyncServicePhase.BootstrapRequired,
                AppSyncServicePhase.RetryPending,
            ),
        automaticStatus = when {
            !backgroundSchedulerAvailable -> CloudSyncAutomaticStatus.Unsupported
            automaticEnabled -> CloudSyncAutomaticStatus.Enabled
            else -> CloudSyncAutomaticStatus.Disabled
        },
        syncOnAppStart = scheduleSettings.syncOnAppStart,
        syncOnForegroundExit = scheduleSettings.syncOnForegroundExit,
        periodicInterval = scheduleSettings.periodicInterval,
        actionsAvailable = !busy,
        cloudDataExists = available || lastVerifiedAtEpochMillis != null,
        notice = if (needsAttention) {
            CloudSyncNotice(presentationMessage, CloudSyncNoticeSeverity.Warning)
        } else {
            null
        },
        details = buildList {
            add(
                CloudSyncDetail(
                    CloudSyncDetailLabel.SyncStatus,
                    CloudSyncDetailValue.Phase(phase),
                ),
            )
            add(
                CloudSyncDetail(
                    CloudSyncDetailLabel.LastVerified,
                    lastVerifiedAtEpochMillis
                        ?.let(::formatDateTime)
                        ?.let(CloudSyncDetailValue::Timestamp)
                        ?: CloudSyncDetailValue.NoRecord,
                ),
            )
            add(
                CloudSyncDetail(
                    CloudSyncDetailLabel.AutomaticSync,
                    CloudSyncDetailValue.Automatic(
                        when {
                            !backgroundSchedulerAvailable -> CloudSyncAutomaticStatus.Unsupported
                            automaticEnabled -> CloudSyncAutomaticStatus.Enabled
                            else -> CloudSyncAutomaticStatus.Disabled
                        },
                    ),
                ),
            )
            add(
                CloudSyncDetail(
                    CloudSyncDetailLabel.PendingUploads,
                    CloudSyncDetailValue.Count(pendingOperationCount),
                ),
            )
            journalRetirementStatus?.let {
                add(
                    CloudSyncDetail(
                        CloudSyncDetailLabel.JournalCleanup,
                        CloudSyncDetailValue.Journal(it.presentationMessage),
                    ),
                )
            }
            add(
                CloudSyncDetail(
                    CloudSyncDetailLabel.LatestResult,
                    CloudSyncDetailValue.StatusMessage(presentationMessage),
                ),
            )
        },
        changes = changeSummaries.map {
            CloudSyncChangeDetail(
                direction = it.direction,
                module = cloudSyncModule(it.domainId),
                action = it.action,
                count = it.count,
                details = it.details,
                remainingDetailCount = it.remainingDetailCount,
            )
        },
    )
}

private fun cloudSyncModule(domainId: String): CloudSyncModule = CloudSyncModule(
    kind = CloudSyncModuleKind.fromDomainId(domainId),
    domainId = domainId,
)

private fun AppSyncForcePreviewResult.Failed.toUiError(): CloudSyncForceError =
    forceError(kind, reason)

private fun AppSyncForceApplyResult.Failed.toUiError(): CloudSyncForceError =
    forceError(kind, reason)

private fun forceError(kind: AppSyncForceFailureKind, reason: String): CloudSyncForceError =
    when (kind) {
        AppSyncForceFailureKind.CoreUnavailable -> CloudSyncForceError.CoreUnavailable
        AppSyncForceFailureKind.AuthenticationExpired -> CloudSyncForceError.AuthenticationExpired
        AppSyncForceFailureKind.External -> CloudSyncForceError.External(reason)
    }

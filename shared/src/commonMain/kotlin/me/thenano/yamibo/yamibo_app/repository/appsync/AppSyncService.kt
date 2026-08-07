package me.thenano.yamibo.yamibo_app.repository.appsync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import me.thenano.yamibo.yamibo_app.Database
import me.thenano.yamibo.yamibo_app.Logger
import me.thenano.yamibo.yamibo_app.repository.AuthRepository
import me.thenano.yamibo.yamibo_app.repository.BookMarkRepository
import me.thenano.yamibo.yamibo_app.repository.DetailNoteRepository
import me.thenano.yamibo.yamibo_app.repository.FavoriteStoreRepository
import me.thenano.yamibo.yamibo_app.repository.FavoriteUpdateRepository
import me.thenano.yamibo.yamibo_app.repository.ReadHistoryRepository
import me.thenano.yamibo.yamibo_app.repository.RssSearchSubscriptionRepository
import me.thenano.yamibo.yamibo_app.repository.ForumRepository
import me.thenano.yamibo.yamibo_app.repository.TagRepository
import me.thenano.yamibo.yamibo_app.repository.ThreadRepository
import me.thenano.yamibo.yamibo_app.repository.appsync.domain.stableAppSyncFingerprint
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncBootstrapResult
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncBootstrapMode
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.BackupSnapshotMigrationPlanner
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.BootstrapCoordinator
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.CapturedBootstrapSnapshot
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.CheckpointCoordinator
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.CheckpointProjection
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.CheckpointCreationResult
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.DatabaseSyncDomainMaterializer
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.OperationSyncEngine
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.OperationSyncResult
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.OperationReducer
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.OperationChangeAction
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.OperationChangeDirection
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.OperationChangeSummary
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.ManualSyncApplyResult
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.ManualSyncOverrideCoordinator
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.ManualSyncOverrideDirection
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.ManualSyncOverridePreview
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.ManualSyncPreviewResult
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.JournalRetirementCoordinator
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncJournalRetirementMaintenanceResult
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.SqlDelightSyncDomainStateAdapter
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.LocalProjectionRepairPlanner
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncBootstrapRollbackSnapshot
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncInstallationState
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncOperationLifecycle
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncAccountBinding
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncIdentityGenerator
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperationOrigin
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.YamiboAppSyncBlogProvider
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.YamiboAppSyncJournalRemote
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncCloudResetResult
import me.thenano.yamibo.yamibo_app.repository.bookmark.BookMarkRepositoryImpl
import me.thenano.yamibo.yamibo_app.repository.backup.BackupRepositoryImpl
import me.thenano.yamibo.yamibo_app.repository.backup.CloudBackupPayloadCodec
import me.thenano.yamibo.yamibo_app.repository.detailnote.DetailNoteRepositoryImpl
import me.thenano.yamibo.yamibo_app.repository.favorite.FavoriteStoreRepositoryImpl
import me.thenano.yamibo.yamibo_app.repository.favorite.FavoriteUpdateRepositoryImpl
import me.thenano.yamibo.yamibo_app.repository.rss.RssSearchSubscriptionRepositoryImpl
import me.thenano.yamibo.yamibo_app.repository.settings.core.BoolSetting
import me.thenano.yamibo.yamibo_app.repository.settings.core.EnumSetting
import me.thenano.yamibo.yamibo_app.repository.settings.core.FloatSetting
import me.thenano.yamibo.yamibo_app.repository.settings.core.IntSetting
import me.thenano.yamibo.yamibo_app.repository.settings.core.SettingsRegistry
import me.thenano.yamibo.yamibo_app.repository.settings.core.StringSetting
import me.thenano.yamibo.yamibo_app.store.appsync.SqlDelightAppSyncOperationStore
import me.thenano.yamibo.yamibo_app.store.appsync.SqlDelightAppSyncRemoteBlogStore
import me.thenano.yamibo.yamibo_app.store.settings.SettingsStore
import me.thenano.yamibo.yamibo_app.util.time.currentTimeMillis

enum class AppSyncServicePhase {
    Disabled,
    BootstrapRequired,
    Running,
    Active,
    PausedAuth,
    PausedProvider,
    Quarantined,
    RetryPending,
}

data class AppSyncServiceStatus(
    val phase: AppSyncServicePhase,
    val automaticEnabled: Boolean,
    val pendingOperationCount: Int,
    val lastVerifiedAtEpochMillis: Long?,
    /**
     * Diagnostic text for logs, reliability evidence, and tests only.
     *
     * UI code must never render this field because it can contain provider exception text.
     * Render [presentationMessage] through the app i18n layer instead.
     */
    val message: String,
    val presentationMessage: AppSyncStatusMessage = AppSyncStatusMessage.External(message),
    val changeSummaries: List<AppSyncChangeSummary> = emptyList(),
    val scheduleSettings: AppSyncScheduleSettings = AppSyncScheduleSettings(),
    val pendingTriggerGeneration: Long? = null,
    val journalRetirementStatus: AppSyncJournalRetirementStatus? = null,
)

sealed interface AppSyncStatusMessage {
    data object NotStarted : AppSyncStatusMessage
    data object CoreNotAvailable : AppSyncStatusMessage
    data object QuarantinedRefresh : AppSyncStatusMessage
    data object QuarantinedManualSync : AppSyncStatusMessage
    data object UnexpectedFailure : AppSyncStatusMessage
    data object AutomaticSyncScheduled : AppSyncStatusMessage
    data object AutomaticSyncDisabled : AppSyncStatusMessage
    data object ScheduleUpdated : AppSyncStatusMessage
    data object AppStartupSyncScheduled : AppSyncStatusMessage
    data object ForegroundExitSyncScheduled : AppSyncStatusMessage
    data object ClearingCloudData : AppSyncStatusMessage
    data class CloudDataCleared(val count: Int) : AppSyncStatusMessage
    data object CloudResetAuthExpired : AppSyncStatusMessage
    data class CloudResetIncomplete(val reason: String) : AppSyncStatusMessage
    data class CloudLinkCacheCleared(val count: Int) : AppSyncStatusMessage
    data object ForcePushRunning : AppSyncStatusMessage
    data object ForcePullRunning : AppSyncStatusMessage
    data class ForcePullCompleted(val count: Int) : AppSyncStatusMessage
    data object ForcePreviewStale : AppSyncStatusMessage
    data object SafeLoadRunning : AppSyncStatusMessage
    data class SafeLoadCompleted(val count: Int) : AppSyncStatusMessage
    data class SafeLoadCompletedWithSkippedRssHistory(
        val appliedCount: Int,
        val skippedCount: Int,
    ) : AppSyncStatusMessage
    data object SyncRunning : AppSyncStatusMessage
    data class SyncCompleted(
        val receivedCount: Int,
        val acknowledgedCount: Int,
    ) : AppSyncStatusMessage
    data object SyncAlreadyRunning : AppSyncStatusMessage
    data object AuthenticationExpired : AppSyncStatusMessage
    /** Carries diagnostics across layers; the UI intentionally replaces it with a localized key. */
    data class External(val value: String) : AppSyncStatusMessage
}

data class AppSyncJournalRetirementStatus(
    val state: AppSyncJournalRetirementState,
    /** Diagnostic-only text. UI must render [presentationMessage], never this value. */
    val message: String,
    val presentationMessage: AppSyncJournalRetirementMessage =
        AppSyncJournalRetirementMessage.External(message),
)

sealed interface AppSyncJournalRetirementMessage {
    data class Observed(val journalCount: Int) : AppSyncJournalRetirementMessage
    data class Candidate(val count: Int) : AppSyncJournalRetirementMessage
    data class Pending(val stage: String) : AppSyncJournalRetirementMessage
    data object Completed : AppSyncJournalRetirementMessage
    data object PausedAuth : AppSyncJournalRetirementMessage
    data object AlreadyRunning : AppSyncJournalRetirementMessage
    /** Carries diagnostics across layers; the UI intentionally replaces it with a localized key. */
    data class External(val value: String) : AppSyncJournalRetirementMessage
}

enum class AppSyncJournalRetirementState {
    Observed,
    Candidate,
    Blocked,
    Pending,
    Completed,
    RetryPending,
    PausedAuth,
}

enum class AppSyncChangeDirection {
    Received,
    Uploaded,
}

enum class AppSyncChangeAction {
    Added,
    Updated,
    Deleted,
    Enabled,
    Disabled,
    Read,
    Dismissed,
}

data class AppSyncChangeSummary(
    val direction: AppSyncChangeDirection,
    val domainId: String,
    val action: AppSyncChangeAction,
    val count: Int,
    val details: List<String> = emptyList(),
    val remainingDetailCount: Int = 0,
)

enum class AppSyncForceDirection {
    Push,
    Pull,
}

data class AppSyncForceDifference(
    val domainId: String,
    val added: Int,
    val updated: Int,
    val deleted: Int,
    val enabled: Int,
    val disabled: Int,
    val details: List<String> = emptyList(),
    val remainingDetailCount: Int = 0,
)

data class AppSyncForcePreview(
    val direction: AppSyncForceDirection,
    val token: String,
    val differences: List<AppSyncForceDifference>,
)

sealed interface AppSyncForcePreviewResult {
    data class Ready(val preview: AppSyncForcePreview) : AppSyncForcePreviewResult
    data class Failed(
        val reason: String,
        val kind: AppSyncForceFailureKind = AppSyncForceFailureKind.External,
    ) : AppSyncForcePreviewResult
}

sealed interface AppSyncForceApplyResult {
    data class Applied(val status: AppSyncServiceStatus) : AppSyncForceApplyResult
    data object StalePreview : AppSyncForceApplyResult
    data class Failed(
        val reason: String,
        val kind: AppSyncForceFailureKind = AppSyncForceFailureKind.External,
    ) : AppSyncForceApplyResult
}

enum class AppSyncForceFailureKind {
    CoreUnavailable,
    AuthenticationExpired,
    External,
}

internal data class PendingReliabilityDemand(
    val runId: String,
    val startedAtEpochMillis: Long,
    val retryCount: Long,
)

internal data class ReliabilityDemandContext(
    val runId: String,
    val trigger: String,
    val startedAtEpochMillis: Long,
    val retryCount: Long,
)

private sealed interface LocalProjectionRepairResult {
    data class Ready(
        val snapshot: me.thenano.yamibo.yamibo_app.repository.backup.YamiboBackupFile,
        val repairedOperationCount: Int,
    ) : LocalProjectionRepairResult

    data class Failed(val reason: String) : LocalProjectionRepairResult
}

internal fun nextReliabilityDemand(
    trigger: String,
    nowEpochMillis: Long,
    deviceEpoch: String?,
    pending: PendingReliabilityDemand?,
): ReliabilityDemandContext = if (pending != null) {
    ReliabilityDemandContext(
        runId = pending.runId,
        trigger = trigger,
        startedAtEpochMillis = pending.startedAtEpochMillis,
        retryCount = pending.retryCount + 1,
    )
} else {
    ReliabilityDemandContext(
        runId = "run-${
            stableAppSyncFingerprint("$trigger:$nowEpochMillis:$deviceEpoch").take(24)
        }",
        trigger = trigger,
        startedAtEpochMillis = nowEpochMillis,
        retryCount = 0,
    )
}

internal fun shouldForcePushAfterBootstrap(
    mode: AppSyncBootstrapMode?,
): Boolean = mode == AppSyncBootstrapMode.Seed

private data class AppSyncBootstrapOutcome(
    val status: AppSyncServiceStatus,
    val mode: AppSyncBootstrapMode?,
)

/**
 * App-scoped entry point. Platform schedulers and the UI call this service;
 * synchronization policy remains in the shared engine.
 */
class AppSyncService(
    private val db: Database,
    private val settingsStore: SettingsStore,
    private val authRepository: AuthRepository,
    private val nowMillis: () -> Long = ::currentTimeMillis,
) {
    private val store = SqlDelightAppSyncOperationStore(db)
    private val domainState = SqlDelightSyncDomainStateAdapter(
        db = db,
        materializer = DatabaseSyncDomainMaterializer(db, settingsStore),
        nowMillis = nowMillis,
    )
    private val remote = YamiboAppSyncJournalRemote(
        provider = YamiboAppSyncBlogProvider(
            cookieStore = authRepository.cookieStore,
            yamiboClient = authRepository.yamiboClient,
        ),
        store = SqlDelightAppSyncRemoteBlogStore(db),
        nowMillis = nowMillis,
        retirementIntents = store::retirementIntents,
    )
    private var localSnapshotSource: BackupRepositoryImpl? = null
    private val migrationPlanner = BackupSnapshotMigrationPlanner()
    private val localProjectionRepairPlanner = LocalProjectionRepairPlanner()
    private val rollbackSnapshotCodec = CloudBackupPayloadCodec()
    private val bootstrap = BootstrapCoordinator(
        store,
        remote,
        domainState,
        nowMillis = nowMillis,
        captureLocalSnapshot = {
            val source = checkNotNull(localSnapshotSource) {
                "Backup snapshot source is not configured"
            }
            val snapshot = source.createAppSyncSnapshot()
            val migration = migrationPlanner.planWithDiagnostics(snapshot)
            CapturedBootstrapSnapshot(
                migrationDrafts = migration.drafts,
                encodedRollbackSnapshot = rollbackSnapshotCodec.encode(snapshot).getOrThrow(),
                skippedOrphanRssHistoryCount = migration.skippedOrphanRssHistoryCount,
            )
        },
    )
    private val engine = OperationSyncEngine(
        store = store,
        remote = remote,
        domainState = domainState,
        nowMillis = nowMillis,
        ownerId = { SyncIdentityGenerator.writerNonce().value },
    )
    private val manualOverride = ManualSyncOverrideCoordinator(
        store = store,
        remote = remote,
        domainState = domainState,
        captureAuthoritativeLocalDrafts = {
            db.transactionWithResult {
                migrationPlanner.plan(
                    checkNotNull(localSnapshotSource) {
                        "Backup snapshot source is not configured"
                    }.createAppSyncSnapshot(),
                )
            }
        },
        nowMillis = nowMillis,
    )
    private val checkpointCoordinator = CheckpointCoordinator(
        store = store,
        remote = remote,
        captureProjection = {
            db.transactionWithResult {
                CheckpointProjection(
                    coverage = store.causalContext(),
                    entities = domainState.currentState().values.toList(),
                    snapshot = checkNotNull(localSnapshotSource) {
                        "Backup snapshot source is not configured"
                    }.createAppSyncSnapshot(),
                    pendingOperationCount = store.pendingOperations().size,
                    acknowledgedOperationCount = store.allOutboxOperations().count {
                        it.second == AppSyncOperationLifecycle.Acknowledged
                    },
                )
            }
        },
        nowMillis = nowMillis,
    )
    private val journalRetirementCoordinator = JournalRetirementCoordinator(
        store = store,
        remote = remote,
        nowMillis = nowMillis,
        ownerId = { SyncIdentityGenerator.writerNonce().value },
    )
    internal val mutationRecorder = AppSyncMutationRecorder(
        enabled = true,
        store = store,
        domainState = domainState,
        nowMillis = nowMillis,
    )
    private val mutableStatus: MutableStateFlow<AppSyncServiceStatus>

    val status: StateFlow<AppSyncServiceStatus>
        get() = mutableStatus.asStateFlow()

    init {
        val generation = settingsStore.getString(DATABASE_GENERATION_KEY, "").ifBlank {
            SyncIdentityGenerator.writerNonce().value.also {
                settingsStore.putString(DATABASE_GENERATION_KEY, it)
            }
        }
        val installation = store.initialize(generation)
        backfillStableContainerIds(db)
        backfillFavoriteUpdateSyncState(db)
        store.reconcileResolvedStateCoverage()
        domainState.reconcileProjections()
        mutableStatus = MutableStateFlow(
            statusFor(
                installation.state,
                "尚未開始同步",
                AppSyncStatusMessage.NotStarted,
            ),
        )
    }

    fun operationRecordingSettingsStore(
        db: Database,
        delegate: SettingsStore,
    ): SettingsStore = OperationRecordingSettingsStore(db, delegate, mutationRecorder)

    fun detailNoteRepository(db: Database): DetailNoteRepository =
        DetailNoteRepositoryImpl(db, mutationRecorder)

    fun bookMarkRepository(db: Database): BookMarkRepository =
        BookMarkRepositoryImpl(db, mutationRecorder)

    fun favoriteStoreRepository(db: Database): FavoriteStoreRepository =
        FavoriteStoreRepositoryImpl(db, mutationRecorder)

    fun rssSearchSubscriptionRepository(
        db: Database,
        authRepository: AuthRepository,
        forumRepository: ForumRepository,
    ): RssSearchSubscriptionRepository = RssSearchSubscriptionRepositoryImpl(
        db = db,
        authRepository = authRepository,
        forumRepository = forumRepository,
        mutationRecorder = mutationRecorder,
    )

    fun favoriteUpdateRepository(
        db: Database,
        localFavoriteRepository: FavoriteStoreRepository,
        threadRepository: ThreadRepository,
        tagRepository: TagRepository,
        rssSearchSubscriptionRepository: RssSearchSubscriptionRepository,
    ): FavoriteUpdateRepository = FavoriteUpdateRepositoryImpl(
        db = db,
        localFavoriteRepository = localFavoriteRepository,
        threadRepository = threadRepository,
        tagRepository = tagRepository,
        rssSearchSubscriptionRepository = rssSearchSubscriptionRepository,
        mutationRecorder = mutationRecorder,
    )

    fun readHistoryRepository(delegate: ReadHistoryRepository): ReadHistoryRepository =
        OperationRecordingReadHistoryRepository(delegate, mutationRecorder)

    fun registerSyncableSettings(registries: List<SettingsRegistry>) {
        db.transaction {
            registries.flatMap { it.exportableSettingItems }
                .distinctBy { it.storageKey }
                .filterNot { isAppSyncLocalOnlySetting(it.storageKey) }
                .filter { settingsStore.hasKey(it.storageKey) }
                .forEach { setting ->
                    if (db.appSyncOperationQueries.getSyncSettingValue(setting.storageKey)
                            .executeAsOneOrNull() != null
                    ) {
                        return@forEach
                    }
                    val (type, value) = when (setting) {
                        is IntSetting -> "int" to settingsStore.getInt(setting.storageKey, setting.default).toString()
                        is FloatSetting -> "float" to settingsStore.getFloat(setting.storageKey, setting.default).toString()
                        is BoolSetting -> "bool" to settingsStore.getBoolean(setting.storageKey, setting.default).toString()
                        is StringSetting ->
                            "string" to settingsStore.getString(setting.storageKey, setting.default)
                        is EnumSetting<*> ->
                            "enum" to settingsStore.getString(setting.storageKey, setting.default.name)
                        else -> return@forEach
                    }
                    db.appSyncOperationQueries.upsertSyncSettingValue(
                        settingKey = setting.storageKey,
                        type = type,
                        value_ = value,
                        winnerOperationId = PENDING_SETTINGS_MIGRATION_WINNER,
                        updatedAtEpochMillis = 0,
                    )
                    db.appSyncOperationQueries.recordKnownSyncSettingKey(setting.storageKey)
                }
        }
    }

    fun registerLocalSnapshotSource(repository: BackupRepositoryImpl) {
        localSnapshotSource = repository
    }

    suspend fun refresh(forceDiscovery: Boolean = false): AppSyncServiceStatus {
        val binding = currentAccountBinding() ?: return pausedAuth("foreground_refresh")
        val installation = store.installation()
        return when {
            installation == null || installation.accountBinding != binding ->
                bootstrapForForeground(binding)
            installation.state.blocksRegularSync() ->
                statusFor(
                    AppSyncInstallationState.Quarantined,
                    "同步資料已隔離；重新檢查不會修改本機資料",
                    AppSyncStatusMessage.QuarantinedRefresh,
                )
            installation.state.requiresBootstrapForSync() ->
                bootstrapForForeground(binding)
            else -> {
                resumeRetryableInstallation(installation.state)
                synchronize(binding, forceDiscovery, trigger = "foreground_refresh")
            }
        }
    }

    private suspend fun bootstrapForForeground(
        binding: SyncAccountBinding,
    ): AppSyncServiceStatus {
        val outcome = bootstrapOutcome(binding, forceDiscovery = true)
        return if (shouldForcePushAfterBootstrap(outcome.mode)) {
            forcePushSeedToEmptyCloud()
        } else {
            outcome.status
        }
    }

    private suspend fun forcePushSeedToEmptyCloud(): AppSyncServiceStatus {
        val preview = when (val result = previewForceOverride(AppSyncForceDirection.Push)) {
            is AppSyncForcePreviewResult.Ready -> result.preview
            is AppSyncForcePreviewResult.Failed -> return forcePushSeedFailure(
                reason = result.reason,
                kind = result.kind,
            )
        }
        return when (val result = applyForceOverride(preview)) {
            is AppSyncForceApplyResult.Applied -> result.status
            AppSyncForceApplyResult.StalePreview -> forcePushSeedFailure(
                "雲端資料在強制上傳前已變更，已停止自動覆蓋",
            )
            is AppSyncForceApplyResult.Failed -> forcePushSeedFailure(
                reason = result.reason,
                kind = result.kind,
            )
        }
    }

    private fun forcePushSeedFailure(
        reason: String,
        kind: AppSyncForceFailureKind = AppSyncForceFailureKind.External,
    ): AppSyncServiceStatus = if (kind == AppSyncForceFailureKind.AuthenticationExpired) {
        pausedAuth("foreground_seed_force_push")
    } else {
        statusFor(
            state = requireNotNull(store.installation()).state,
            message = reason,
            phaseOverride = AppSyncServicePhase.RetryPending,
        ).also { mutableStatus.value = it }
    }

    suspend fun synchronizeNow(
        forceDiscovery: Boolean = false,
        trigger: String = "manual",
    ): AppSyncServiceStatus {
        val binding = currentAccountBinding() ?: return pausedAuth(trigger)
        val demand = beginReliabilityDemand(trigger)
        return try {
            val installation = store.installation()
            if (installation == null || installation.accountBinding != binding) {
                val bootstrapped = bootstrap(binding, forceDiscovery = true)
                if (bootstrapped.phase != AppSyncServicePhase.Active) {
                    return finishReliabilityDemand(demand, bootstrapped)
                }
            } else if (installation.state.blocksRegularSync()) {
                return finishReliabilityDemand(
                    demand,
                    statusFor(
                        AppSyncInstallationState.Quarantined,
                        "同步資料已隔離；手動同步不會修改本機資料",
                        AppSyncStatusMessage.QuarantinedManualSync,
                    ),
                )
            } else if (installation.state.requiresBootstrapForSync()) {
                val bootstrapped = bootstrap(binding, forceDiscovery = true)
                if (bootstrapped.phase != AppSyncServicePhase.Active) {
                    return finishReliabilityDemand(demand, bootstrapped)
                }
            } else {
                resumeRetryableInstallation(installation.state)
            }
            synchronize(binding, forceDiscovery, trigger, demand)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Logger.e(
                APPSYNC_LOG_TAG,
                "Synchronization crashed trigger=$trigger; pending operations were preserved",
                error,
            )
            val status = statusFor(
                requireNotNull(store.installation()).state,
                "同步發生未預期錯誤，已保留待同步操作並排定重試",
                AppSyncStatusMessage.UnexpectedFailure,
                AppSyncServicePhase.RetryPending,
            )
            mutableStatus.value = status
            finishReliabilityDemand(demand, status)
        }
    }

    fun setAutomaticEnabled(enabled: Boolean) {
        store.setAutomaticEnabled(enabled)
        val installation = requireNotNull(store.installation())
        mutableStatus.value = statusFor(
            installation.state,
            if (enabled) "已排定自動同步" else "自動同步已關閉",
            if (enabled) {
                AppSyncStatusMessage.AutomaticSyncScheduled
            } else {
                AppSyncStatusMessage.AutomaticSyncDisabled
            },
            changeSummaries = mutableStatus.value.changeSummaries,
        )
    }

    fun setScheduleSettings(settings: AppSyncScheduleSettings) {
        store.setScheduleSettings(settings)
        val installation = requireNotNull(store.installation())
        mutableStatus.value = statusFor(
            installation.state,
            "自動同步排程設定已更新",
            AppSyncStatusMessage.ScheduleUpdated,
            changeSummaries = mutableStatus.value.changeSummaries,
        )
    }

    fun requestAutomaticTrigger(trigger: AppSyncAutomaticTrigger): Long? {
        val generation = store.requestAutomaticTrigger(trigger)
        if (generation != null) {
            val installation = requireNotNull(store.installation())
            mutableStatus.value = statusFor(
                installation.state,
                when (trigger) {
                    AppSyncAutomaticTrigger.AppStartup -> "已排定 App 啟動同步"
                    AppSyncAutomaticTrigger.ForegroundExit -> "已排定離開前台同步"
                },
                when (trigger) {
                    AppSyncAutomaticTrigger.AppStartup ->
                        AppSyncStatusMessage.AppStartupSyncScheduled
                    AppSyncAutomaticTrigger.ForegroundExit ->
                        AppSyncStatusMessage.ForegroundExitSyncScheduled
                },
                changeSummaries = mutableStatus.value.changeSummaries,
            )
        }
        return generation
    }

    fun pendingAutomaticTriggerGeneration(): Long? =
        store.installation()?.let { installation ->
            installation.requestedTriggerGeneration
                .takeIf { it > installation.accountedTriggerGeneration }
        }

    fun accountAutomaticTrigger(upToGeneration: Long) {
        store.accountAutomaticTrigger(upToGeneration)
        val installation = requireNotNull(store.installation())
        mutableStatus.value = statusFor(
            installation.state,
            mutableStatus.value.message,
            mutableStatus.value.presentationMessage,
            changeSummaries = mutableStatus.value.changeSummaries,
        )
    }

    suspend fun deleteCloudData(): AppSyncServiceStatus {
        val binding = currentAccountBinding() ?: return pausedAuth("cloud_reset")
        val formHash = authRepository.currentUser()?.formHash
            ?: return pausedAuth("cloud_reset")

        // Enter pull-only bootstrap before the first remote delete. If the process
        // stops during a partial purge, surviving cloud data is loaded before any
        // local migration operation can be published.
        store.prepareForCloudReset()
        mutableStatus.value = mutableStatus.value.copy(
            phase = AppSyncServicePhase.Running,
            message = "正在驗證並清除雲端同步資料",
            presentationMessage = AppSyncStatusMessage.ClearingCloudData,
        )
        val status = when (val result = remote.deleteAllVerifiedSyncData(binding, formHash)) {
            is AppSyncCloudResetResult.Verified -> {
                val bootstrapped = bootstrap(binding, forceDiscovery = true)
                if (bootstrapped.phase == AppSyncServicePhase.Active) {
                    bootstrapped.copy(
                        message = "已清除 ${result.deletedBlogCount} 筆雲端同步資料；本機資料已排入安全重建",
                        presentationMessage = AppSyncStatusMessage.CloudDataCleared(
                            result.deletedBlogCount,
                        ),
                    )
                } else {
                    bootstrapped
                }
            }
            AppSyncCloudResetResult.FormExpired -> {
                store.updateState(AppSyncInstallationState.PausedAuth)
                statusFor(
                    AppSyncInstallationState.PausedAuth,
                    "登入狀態已過期；重新整理登入後會先載入仍存活的雲端資料",
                    AppSyncStatusMessage.CloudResetAuthExpired,
                )
            }
            is AppSyncCloudResetResult.RetryableFailure -> statusFor(
                AppSyncInstallationState.Unbound,
                "雲端清除未完整確認：${result.reason}；下次會先安全載入再繼續",
                AppSyncStatusMessage.CloudResetIncomplete(result.reason),
                AppSyncServicePhase.RetryPending,
            )
            is AppSyncCloudResetResult.TerminalFailure -> {
                store.updateState(AppSyncInstallationState.Quarantined)
                statusFor(AppSyncInstallationState.Quarantined, result.reason)
            }
        }
        mutableStatus.value = status
        return status
    }

    fun clearCloudLinkCache(): AppSyncServiceStatus {
        val binding = currentAccountBinding() ?: return pausedAuth("clear_cloud_link_cache")
        if (mutableStatus.value.phase == AppSyncServicePhase.Running) {
            return mutableStatus.value
        }
        val cleared = remote.clearLinkCache(binding)
        val installation = requireNotNull(store.installation())
        return statusFor(
            installation.state,
            "已清除 $cleared 筆雲端連結紀錄；下次同步會重新驗證最新索引",
            AppSyncStatusMessage.CloudLinkCacheCleared(cleared),
            changeSummaries = mutableStatus.value.changeSummaries,
        ).also { mutableStatus.value = it }
    }

    suspend fun previewForceOverride(
        direction: AppSyncForceDirection,
    ): AppSyncForcePreviewResult {
        val binding = currentAccountBinding()
            ?: return AppSyncForcePreviewResult.Failed(
                "登入狀態已過期，請先刷新登入狀態",
                AppSyncForceFailureKind.AuthenticationExpired,
            )
        val internalDirection = direction.toInternal()
        return when (val result = manualOverride.preview(binding, internalDirection)) {
            is ManualSyncPreviewResult.Ready -> AppSyncForcePreviewResult.Ready(
                result.preview.toPublic(),
            )
            is ManualSyncPreviewResult.Failed -> {
                Logger.w(
                    APPSYNC_LOG_TAG,
                    "Force override preview failed direction=${direction.name} reason=${result.reason}",
                )
                AppSyncForcePreviewResult.Failed(result.reason)
            }
        }
    }

    suspend fun applyForceOverride(
        preview: AppSyncForcePreview,
    ): AppSyncForceApplyResult {
        val binding = currentAccountBinding()
            ?: return AppSyncForceApplyResult.Failed(
                "登入狀態已過期，請先刷新登入狀態",
                AppSyncForceFailureKind.AuthenticationExpired,
            )
        if (preview.direction == AppSyncForceDirection.Push &&
            authRepository.currentUser()?.formHash == null
        ) {
            return AppSyncForceApplyResult.Failed(
                "登入狀態已過期，請先刷新登入狀態",
                AppSyncForceFailureKind.AuthenticationExpired,
            )
        }
        if (preview.direction == AppSyncForceDirection.Pull) {
            val installation = requireNotNull(store.installation())
            try {
                val rollbackSnapshot = db.transactionWithResult {
                    checkNotNull(localSnapshotSource) {
                        "Backup snapshot source is not configured"
                    }.createAppSyncSnapshot()
                }
                store.saveBootstrapRollbackSnapshot(
                    AppSyncBootstrapRollbackSnapshot(
                        accountBinding = binding,
                        databaseGeneration = installation.databaseGeneration,
                        encodedSnapshot = rollbackSnapshotCodec.encode(rollbackSnapshot).getOrThrow(),
                        createdAtEpochMillis = nowMillis(),
                    ),
                )
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                return AppSyncForceApplyResult.Failed(
                    "Local rollback snapshot failed: ${error.message ?: error::class.simpleName}",
                )
            }
        }
        mutableStatus.value = mutableStatus.value.copy(
            phase = AppSyncServicePhase.Running,
            message = if (preview.direction == AppSyncForceDirection.Push) {
                "正在執行強制上傳並驗證雲端結果"
            } else {
                "正在載入並套用已驗證的雲端狀態"
            },
            presentationMessage = if (preview.direction == AppSyncForceDirection.Push) {
                AppSyncStatusMessage.ForcePushRunning
            } else {
                AppSyncStatusMessage.ForcePullRunning
            },
        )
        return when (val result = manualOverride.apply(binding, preview.toInternal())) {
            is ManualSyncApplyResult.Applied -> {
                val status = if (preview.direction == AppSyncForceDirection.Push) {
                    synchronize(
                        binding = binding,
                        forceDiscovery = false,
                        trigger = "manual_force_push",
                        auditLocalProjection = false,
                        forcePushWhenCloudEmpty = false,
                    )
                } else {
                    statusFor(
                        AppSyncInstallationState.Active,
                        "強制載入完成：已套用 ${result.operationCount} 項差異",
                        AppSyncStatusMessage.ForcePullCompleted(result.operationCount),
                        changeSummaries = preview.differences.flatMap { difference ->
                            difference.toChangeSummaries(AppSyncChangeDirection.Received)
                        },
                    ).also { mutableStatus.value = it }
                }
                AppSyncForceApplyResult.Applied(status)
            }
            ManualSyncApplyResult.StalePreview -> {
                mutableStatus.value = currentStatus().copy(
                    message = "本機或雲端資料已變更，請重新檢視差異後再確認",
                    presentationMessage = AppSyncStatusMessage.ForcePreviewStale,
                )
                AppSyncForceApplyResult.StalePreview
            }
            is ManualSyncApplyResult.Failed -> {
                Logger.w(
                    APPSYNC_LOG_TAG,
                    "Force override apply failed direction=${preview.direction.name} reason=${result.reason}",
                )
                mutableStatus.value = currentStatus().copy(
                    message = result.reason,
                    presentationMessage = AppSyncStatusMessage.External(result.reason),
                )
                AppSyncForceApplyResult.Failed(result.reason)
            }
        }
    }

    fun currentStatus(): AppSyncServiceStatus {
        val installation = requireNotNull(store.installation())
        return statusFor(
            installation.state,
            mutableStatus.value.message,
            mutableStatus.value.presentationMessage,
        )
            .copy(
                changeSummaries = mutableStatus.value.changeSummaries,
                journalRetirementStatus = mutableStatus.value.journalRetirementStatus,
            )
            .also { mutableStatus.value = it }
    }

    private suspend fun bootstrap(
        binding: SyncAccountBinding,
        forceDiscovery: Boolean,
    ): AppSyncServiceStatus = bootstrapOutcome(binding, forceDiscovery).status

    private suspend fun bootstrapOutcome(
        binding: SyncAccountBinding,
        forceDiscovery: Boolean,
    ): AppSyncBootstrapOutcome {
        mutableStatus.value = mutableStatus.value.copy(
            phase = AppSyncServicePhase.Running,
            message = "正在安全載入雲端紀錄，本階段不會上傳本機資料",
            presentationMessage = AppSyncStatusMessage.SafeLoadRunning,
        )
        val result = bootstrap.bootstrap(binding, forceDiscovery)
        val status = when (result) {
            is AppSyncBootstrapResult.Ready -> {
                val skipped = result.skippedOrphanRssHistoryCount
                statusFor(
                    AppSyncInstallationState.Active,
                    if (skipped == 0) {
                        "安全載入完成，套用 ${result.appliedOperationCount} 筆操作"
                    } else {
                        "安全載入完成，套用 ${result.appliedOperationCount} 筆操作；" +
                            "保留 $skipped 筆無法解析來源的舊 RSS 閱讀紀錄於本機"
                    },
                    if (skipped == 0) {
                        AppSyncStatusMessage.SafeLoadCompleted(result.appliedOperationCount)
                    } else {
                        AppSyncStatusMessage.SafeLoadCompletedWithSkippedRssHistory(
                            result.appliedOperationCount,
                            skipped,
                        )
                    },
                    changeSummaries = result.changes.map(OperationChangeSummary::toPublic),
                )
            }
            is AppSyncBootstrapResult.RetryableFailure -> statusFor(
                AppSyncInstallationState.Bootstrapping,
                result.reason,
                phaseOverride = AppSyncServicePhase.RetryPending,
            )
            is AppSyncBootstrapResult.Paused -> {
                val state = requireNotNull(store.installation()).state
                statusFor(state, result.reason)
            }
        }.also { mutableStatus.value = it }
        return AppSyncBootstrapOutcome(
            status = status,
            mode = (result as? AppSyncBootstrapResult.Ready)?.mode,
        )
    }

    private suspend fun synchronize(
        binding: SyncAccountBinding,
        forceDiscovery: Boolean,
        trigger: String,
        existingDemand: ReliabilityDemandContext? = null,
        auditLocalProjection: Boolean = true,
        forcePushWhenCloudEmpty: Boolean = true,
    ): AppSyncServiceStatus {
        val demand = existingDemand ?: beginReliabilityDemand(trigger)
        mutableStatus.value = mutableStatus.value.copy(
            phase = AppSyncServicePhase.Running,
            message = "正在同步操作紀錄",
            presentationMessage = AppSyncStatusMessage.SyncRunning,
        )
        if (auditLocalProjection && localSnapshotSource != null) {
            when (val audit = repairLocalProjection(binding)) {
                is LocalProjectionRepairResult.Ready -> Unit
                is LocalProjectionRepairResult.Failed -> {
                    logSyncFailure(trigger, "local_projection_audit_failed", audit.reason)
                    val status = statusFor(
                        requireNotNull(store.installation()).state,
                        audit.reason,
                        phaseOverride = AppSyncServicePhase.RetryPending,
                    )
                    mutableStatus.value = status
                    return finishReliabilityDemand(demand, status)
                }
            }
        }
        val result = engine.synchronize(
            accountBinding = binding,
            formHash = authRepository.currentUser()?.formHash,
            forceDiscovery = forceDiscovery,
            detectEmptyCloud = forcePushWhenCloudEmpty,
        )
        logSyncResult(trigger, result)
        if (result is OperationSyncResult.EmptyCloud) {
            val status = forcePushSeedToEmptyCloud()
            return finishReliabilityDemand(demand, status)
        }
        var checkpointResult: CheckpointCreationResult? = null
        var retirementResult: AppSyncJournalRetirementMaintenanceResult? = null
        if (result is OperationSyncResult.Converged) {
            authRepository.currentUser()?.formHash?.let { formHash ->
                if (localSnapshotSource != null) {
                    checkpointResult = checkpointCoordinator.createIfNeeded(binding, formHash)
                }
                if (
                    checkpointResult !is CheckpointCreationResult.StoragePressure &&
                    checkpointResult !is CheckpointCreationResult.RetryableFailure &&
                    checkpointResult !is CheckpointCreationResult.Paused
                ) {
                    retirementResult = journalRetirementCoordinator.maintain(
                        accountBinding = binding,
                        formHash = formHash,
                        force = forceDiscovery,
                        allowDelete = settingsStore.getBoolean(
                            JOURNAL_RETIREMENT_DELETE_ENABLED_KEY,
                            false,
                        ),
                    )
                }
            }
        }
        val retirementStatus = retirementResult?.toPublicStatus()
        val status = when (checkpointResult) {
            is CheckpointCreationResult.StoragePressure -> {
                val converged = result as OperationSyncResult.Converged
                statusFor(
                    AppSyncInstallationState.Active,
                    "同步完成：接收 ${converged.appliedRemoteCount}、確認 " +
                        "${converged.acknowledgedLocalCount}；checkpoint 因容量限制延後",
                    AppSyncStatusMessage.SyncCompleted(
                        receivedCount = converged.appliedRemoteCount,
                        acknowledgedCount = converged.acknowledgedLocalCount,
                    ),
                    changeSummaries = converged.changes.map(OperationChangeSummary::toPublic),
                )
            }
            is CheckpointCreationResult.RetryableFailure -> statusFor(
                requireNotNull(store.installation()).state,
                checkpointResult.reason,
                phaseOverride = AppSyncServicePhase.RetryPending,
            )
            else -> when (result) {
                OperationSyncResult.EmptyCloud -> error(
                    "EmptyCloud is handled before checkpoint and status processing",
                )
                is OperationSyncResult.Converged -> statusFor(
                    AppSyncInstallationState.Active,
                    "同步完成：接收 ${result.appliedRemoteCount}、確認 ${result.acknowledgedLocalCount}",
                    AppSyncStatusMessage.SyncCompleted(
                        receivedCount = result.appliedRemoteCount,
                        acknowledgedCount = result.acknowledgedLocalCount,
                    ),
                    changeSummaries = result.changes.map(OperationChangeSummary::toPublic),
                    journalRetirementStatus = retirementStatus,
                )
                is OperationSyncResult.PausedAuth ->
                    statusFor(AppSyncInstallationState.PausedAuth, result.reason)
                is OperationSyncResult.PausedProvider ->
                    statusFor(AppSyncInstallationState.PausedProvider, result.reason)
                is OperationSyncResult.StoragePressure ->
                    statusFor(AppSyncInstallationState.PausedProvider, result.reason)
                is OperationSyncResult.RebootstrapRequired ->
                    statusFor(AppSyncInstallationState.RebootstrapRequired, result.reason)
                is OperationSyncResult.RetryScheduled -> statusFor(
                    requireNotNull(store.installation()).state,
                    result.reason,
                    phaseOverride = AppSyncServicePhase.RetryPending,
                )
                OperationSyncResult.AlreadyRunning -> statusFor(
                    requireNotNull(store.installation()).state,
                    "已有同步工作執行中",
                    AppSyncStatusMessage.SyncAlreadyRunning,
                    AppSyncServicePhase.RetryPending,
                )
            }
        }
        mutableStatus.value = status
        return finishReliabilityDemand(demand, status)
    }

    /**
     * UI messages intentionally expose only localized, user-actionable summaries. The engine
     * reason must still be written to the platform log so provider failures, verification
     * failures, and invalid remote state can be diagnosed without leaking untranslated backend
     * text into the UI. Do not remove this when changing AppSyncStatusMessage presentation.
     */
    private fun logSyncResult(trigger: String, result: OperationSyncResult) {
        val pending = store.pendingOperations().size
        when (result) {
            is OperationSyncResult.Converged -> Logger.i(
                APPSYNC_LOG_TAG,
                "Synchronization converged trigger=$trigger " +
                    "received=${result.appliedRemoteCount} acknowledged=${result.acknowledgedLocalCount} " +
                    "pending=$pending",
            )
            OperationSyncResult.EmptyCloud -> Logger.i(
                APPSYNC_LOG_TAG,
                "Verified empty cloud trigger=$trigger; starting authoritative local seed",
            )
            OperationSyncResult.AlreadyRunning -> Logger.w(
                APPSYNC_LOG_TAG,
                "Synchronization deferred trigger=$trigger reason=already_running pending=$pending",
            )
            is OperationSyncResult.PausedAuth -> logSyncFailure(trigger, "paused_auth", result.reason)
            is OperationSyncResult.PausedProvider ->
                logSyncFailure(trigger, "paused_provider", result.reason)
            is OperationSyncResult.StoragePressure ->
                logSyncFailure(trigger, "storage_pressure", result.reason)
            is OperationSyncResult.RebootstrapRequired ->
                logSyncFailure(trigger, "rebootstrap_required", result.reason)
            is OperationSyncResult.RetryScheduled ->
                logSyncFailure(trigger, "retry_scheduled", result.reason)
        }
    }

    private fun logSyncFailure(trigger: String, outcome: String, reason: String) {
        Logger.w(
            APPSYNC_LOG_TAG,
            "Synchronization did not converge trigger=$trigger outcome=$outcome " +
                "pending=${store.pendingOperations().size} reason=$reason",
        )
    }

    private fun repairLocalProjection(
        binding: SyncAccountBinding,
    ): LocalProjectionRepairResult {
        val source = localSnapshotSource
            ?: return LocalProjectionRepairResult.Failed(
                "Local data safety audit is unavailable because the snapshot source is not configured",
            )
        return try {
            val captured = db.transactionWithResult {
                val snapshot = source.createAppSyncSnapshot()
                Triple(snapshot, migrationPlanner.plan(snapshot), domainState.currentState())
            }
            val snapshot = captured.first
            val localDrafts = captured.second
            val repairs = localProjectionRepairPlanner.plan(localDrafts, captured.third)
            if (repairs.isNotEmpty()) {
                val installation = requireNotNull(store.installation())
                check(installation.accountBinding == binding) {
                    "Local data belongs to a different AppSync account"
                }
                store.appendLocalOperations(
                    accountBinding = binding,
                    drafts = repairs,
                    causalContext = store.causalContext(),
                    createdAtEpochMillis = nowMillis(),
                    origin = SyncOperationOrigin.Migration,
                ) { operations ->
                    val reduction = OperationReducer().reduce(domainState.currentState(), operations)
                    check(reduction.quarantined.isEmpty()) {
                        "Local projection repair produced invalid operations"
                    }
                    domainState.applyWithinTransaction(reduction)
                }
                domainState.reconcileProjections()
            }
            check(localProjectionRepairPlanner.isRepresented(localDrafts, domainState.currentState())) {
                "Local projection still differs after safe repair"
            }
            LocalProjectionRepairResult.Ready(snapshot, repairs.size)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            LocalProjectionRepairResult.Failed(
                "Local data safety audit failed: ${error.message ?: error::class.simpleName}",
            )
        }
    }

    private fun beginReliabilityDemand(trigger: String): ReliabilityDemandContext {
        val latest = db.appSyncOperationQueries.getLatestReliabilityRun()
            .executeAsOneOrNull()
        val pending = latest
            ?.takeIf { it.outcome == "RETRY" }
            ?.let {
                PendingReliabilityDemand(
                    runId = it.runId,
                    startedAtEpochMillis = it.startedAtEpochMillis,
                    retryCount = it.retryCount,
                )
            }
        val demand = nextReliabilityDemand(
            trigger = trigger,
            nowEpochMillis = nowMillis(),
            deviceEpoch = store.installation()?.deviceEpoch?.value,
            pending = pending,
        )
        val startingCoverage = store.causalContext().asStableMap()
        db.appSyncOperationQueries.upsertReliabilityRun(
            runId = demand.runId,
            trigger = demand.trigger,
            phase = "RUNNING",
            outcome = null,
            eligible = 1,
            exclusionReason = null,
            retryCount = demand.retryCount,
            pendingCount = store.pendingOperations().size.toLong(),
            quarantineCount = 0,
            startedAtEpochMillis = demand.startedAtEpochMillis,
            completedAtEpochMillis = null,
            nextRetryAtEpochMillis = null,
            durationMillis = null,
            causalReplicaCount = startingCoverage.size.toLong(),
            causalCoverageHash = coverageHash(startingCoverage),
        )
        return demand
    }

    private fun finishReliabilityDemand(
        demand: ReliabilityDemandContext,
        status: AppSyncServiceStatus,
    ): AppSyncServiceStatus {
        val retry = status.phase == AppSyncServicePhase.RetryPending
        val nextRetryAt = if (retry) {
            val exponent = demand.retryCount.coerceAtMost(6).toInt()
            val base = 30_000L * (1L shl exponent)
            val jitter = stableAppSyncFingerprint(demand.runId).take(4).toLong(16) % 10_000L
            nowMillis() + base.coerceAtMost(30L * 60 * 1_000) + jitter
        } else {
            null
        }
        val completedAt = nowMillis()
        val finalCoverage = store.causalContext().asStableMap()
        db.appSyncOperationQueries.upsertReliabilityRun(
            runId = demand.runId,
            trigger = demand.trigger,
            phase = status.phase.name.uppercase(),
            outcome = reliabilityOutcomeFor(status.phase),
            eligible = if (status.phase == AppSyncServicePhase.PausedAuth) 0 else 1,
            exclusionReason = if (status.phase == AppSyncServicePhase.PausedAuth) status.message else null,
            retryCount = demand.retryCount,
            pendingCount = status.pendingOperationCount.toLong(),
            quarantineCount = if (status.phase == AppSyncServicePhase.Quarantined) 1 else 0,
            startedAtEpochMillis = demand.startedAtEpochMillis,
            completedAtEpochMillis = completedAt,
            nextRetryAtEpochMillis = nextRetryAt,
            durationMillis = (completedAt - demand.startedAtEpochMillis).coerceAtLeast(0L),
            causalReplicaCount = finalCoverage.size.toLong(),
            causalCoverageHash = coverageHash(finalCoverage),
        )
        return status
    }

    private fun coverageHash(coverage: Map<String, Long>): String =
        stableAppSyncFingerprint(
            coverage.entries
                .sortedBy { it.key }
                .joinToString(";") { (replica, sequence) -> "$replica=$sequence" },
        )

    private fun currentAccountBinding(): SyncAccountBinding? =
        authRepository.currentUser()?.uid?.value?.toString()?.let {
            SyncAccountBinding(stableAppSyncFingerprint("yamibo-account:$it"))
        }

    private fun pausedAuth(trigger: String): AppSyncServiceStatus {
        store.updateState(AppSyncInstallationState.PausedAuth)
        val at = nowMillis()
        val coverage = store.causalContext().asStableMap()
        db.appSyncOperationQueries.upsertReliabilityRun(
            runId = "run-${stableAppSyncFingerprint("$trigger:$at:excluded-auth").take(24)}",
            trigger = trigger,
            phase = AppSyncServicePhase.PausedAuth.name.uppercase(),
            outcome = "EXCLUDED_AUTH",
            eligible = 0,
            exclusionReason = "LOGIN_UNAVAILABLE",
            retryCount = 0,
            pendingCount = store.pendingOperations().size.toLong(),
            quarantineCount = 0,
            startedAtEpochMillis = at,
            completedAtEpochMillis = at,
            nextRetryAtEpochMillis = null,
            durationMillis = 0,
            causalReplicaCount = coverage.size.toLong(),
            causalCoverageHash = coverageHash(coverage),
        )
        return statusFor(
            AppSyncInstallationState.PausedAuth,
            "登入狀態已過期，請先刷新登入狀態",
            AppSyncStatusMessage.AuthenticationExpired,
        ).also { mutableStatus.value = it }
    }

    private fun statusFor(
        state: AppSyncInstallationState,
        message: String,
        presentationMessage: AppSyncStatusMessage = AppSyncStatusMessage.External(message),
        phaseOverride: AppSyncServicePhase? = null,
        changeSummaries: List<AppSyncChangeSummary> = emptyList(),
        journalRetirementStatus: AppSyncJournalRetirementStatus? = null,
    ): AppSyncServiceStatus {
        val installation = requireNotNull(store.installation())
        val phase = phaseOverride ?: when (state) {
            AppSyncInstallationState.Unbound,
            AppSyncInstallationState.Bootstrapping,
            AppSyncInstallationState.RebootstrapRequired,
            -> AppSyncServicePhase.BootstrapRequired
            AppSyncInstallationState.Active -> AppSyncServicePhase.Active
            AppSyncInstallationState.PausedAuth -> AppSyncServicePhase.PausedAuth
            AppSyncInstallationState.PausedProvider -> AppSyncServicePhase.PausedProvider
            AppSyncInstallationState.Quarantined -> AppSyncServicePhase.Quarantined
        }
        return AppSyncServiceStatus(
            phase = phase,
            automaticEnabled = installation.automaticEnabled,
            pendingOperationCount = store.pendingOperations().size,
            lastVerifiedAtEpochMillis = installation.lastVerifiedHeartbeatAt,
            message = message,
            presentationMessage = presentationMessage,
            changeSummaries = changeSummaries,
            scheduleSettings = installation.scheduleSettings,
            pendingTriggerGeneration = installation.requestedTriggerGeneration.takeIf {
                it > installation.accountedTriggerGeneration
            },
            journalRetirementStatus = journalRetirementStatus,
        )
    }

    private fun resumeRetryableInstallation(state: AppSyncInstallationState) {
        if (
            state == AppSyncInstallationState.PausedAuth ||
            state == AppSyncInstallationState.PausedProvider
        ) {
            store.updateState(AppSyncInstallationState.Active)
        }
    }

    private fun backfillStableContainerIds(db: Database) {
        db.transaction {
            db.localFavoriteCategoryQueries.getAll().executeAsList()
                .filter { it.syncId == null }
                .forEach {
                    db.localFavoriteCategoryQueries.setSyncId(
                        SyncIdentityGenerator.stableEntityId().value,
                        it.id,
                    )
                }
            db.localFavoriteCollectionQueries.getAll().executeAsList()
                .filter { it.syncId == null }
                .forEach {
                    db.localFavoriteCollectionQueries.setSyncId(
                        SyncIdentityGenerator.stableEntityId().value,
                        it.id,
                    )
                }
        }
    }

    private companion object {
        const val APPSYNC_LOG_TAG = "AppSyncService"
        const val DATABASE_GENERATION_KEY = "appsync.database_generation"
        const val JOURNAL_RETIREMENT_DELETE_ENABLED_KEY =
            "appsync.journal_retirement_delete_enabled"
    }
}

internal fun AppSyncInstallationState.requiresBootstrapForSync(): Boolean = when (this) {
    AppSyncInstallationState.Unbound,
    AppSyncInstallationState.Bootstrapping,
    AppSyncInstallationState.RebootstrapRequired,
    -> true
    AppSyncInstallationState.Active,
    AppSyncInstallationState.PausedAuth,
    AppSyncInstallationState.PausedProvider,
    AppSyncInstallationState.Quarantined,
    -> false
}

private fun AppSyncForceDirection.toInternal(): ManualSyncOverrideDirection = when (this) {
    AppSyncForceDirection.Push -> ManualSyncOverrideDirection.ForcePush
    AppSyncForceDirection.Pull -> ManualSyncOverrideDirection.ForcePull
}

private fun ManualSyncOverridePreview.toPublic() = AppSyncForcePreview(
    direction = when (direction) {
        ManualSyncOverrideDirection.ForcePush -> AppSyncForceDirection.Push
        ManualSyncOverrideDirection.ForcePull -> AppSyncForceDirection.Pull
    },
    token = token,
    differences = differences.map {
        AppSyncForceDifference(
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

private fun AppSyncForcePreview.toInternal() = ManualSyncOverridePreview(
    direction = direction.toInternal(),
    token = token,
    differences = differences.map {
        me.thenano.yamibo.yamibo_app.repository.appsync.engine.ManualSyncDifference(
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

private fun AppSyncForceDifference.toChangeSummaries(
    direction: AppSyncChangeDirection,
): List<AppSyncChangeSummary> = buildList {
    fun add(action: AppSyncChangeAction, count: Int) {
        if (count > 0) add(AppSyncChangeSummary(direction, domainId, action, count))
    }
    add(AppSyncChangeAction.Added, added)
    add(AppSyncChangeAction.Updated, updated)
    add(AppSyncChangeAction.Deleted, deleted)
    add(AppSyncChangeAction.Enabled, enabled)
    add(AppSyncChangeAction.Disabled, disabled)
}

private fun OperationChangeSummary.toPublic() = AppSyncChangeSummary(
    direction = when (direction) {
        OperationChangeDirection.Received -> AppSyncChangeDirection.Received
        OperationChangeDirection.Uploaded -> AppSyncChangeDirection.Uploaded
    },
    domainId = domainId,
    action = when (action) {
        OperationChangeAction.Added -> AppSyncChangeAction.Added
        OperationChangeAction.Updated -> AppSyncChangeAction.Updated
        OperationChangeAction.Deleted -> AppSyncChangeAction.Deleted
        OperationChangeAction.Enabled -> AppSyncChangeAction.Enabled
        OperationChangeAction.Disabled -> AppSyncChangeAction.Disabled
        OperationChangeAction.Read -> AppSyncChangeAction.Read
        OperationChangeAction.Dismissed -> AppSyncChangeAction.Dismissed
    },
    count = count,
    details = details,
    remainingDetailCount = remainingDetailCount,
)

private fun AppSyncJournalRetirementMaintenanceResult.toPublicStatus():
    AppSyncJournalRetirementStatus? = when (this) {
    AppSyncJournalRetirementMaintenanceResult.NotDue -> null
    is AppSyncJournalRetirementMaintenanceResult.Observed ->
        AppSyncJournalRetirementStatus(
            AppSyncJournalRetirementState.Observed,
            "已驗證 $journalCount 個 journal，尚無可退休項目",
            AppSyncJournalRetirementMessage.Observed(journalCount),
        )
    is AppSyncJournalRetirementMaintenanceResult.Candidate ->
        AppSyncJournalRetirementStatus(
            AppSyncJournalRetirementState.Candidate,
            "發現 $count 個安全退休候選；目前為只觀察模式",
            AppSyncJournalRetirementMessage.Candidate(count),
        )
    is AppSyncJournalRetirementMaintenanceResult.Blocked ->
        AppSyncJournalRetirementStatus(
            AppSyncJournalRetirementState.Blocked,
            reason,
        )
    is AppSyncJournalRetirementMaintenanceResult.Pending ->
        AppSyncJournalRetirementStatus(
            AppSyncJournalRetirementState.Pending,
            "journal 退休程序等待下一次重新驗證：${stage.name}",
            AppSyncJournalRetirementMessage.Pending(stage.name),
        )
    AppSyncJournalRetirementMaintenanceResult.Completed ->
        AppSyncJournalRetirementStatus(
            AppSyncJournalRetirementState.Completed,
            "已完成一個 inactive journal 的安全退休",
            AppSyncJournalRetirementMessage.Completed,
        )
    AppSyncJournalRetirementMaintenanceResult.PausedAuth ->
        AppSyncJournalRetirementStatus(
            AppSyncJournalRetirementState.PausedAuth,
            "登入狀態不足，journal 退休已暫停",
            AppSyncJournalRetirementMessage.PausedAuth,
        )
    is AppSyncJournalRetirementMaintenanceResult.RetryableFailure ->
        AppSyncJournalRetirementStatus(
            AppSyncJournalRetirementState.RetryPending,
            reason,
        )
    is AppSyncJournalRetirementMaintenanceResult.TerminalFailure ->
        AppSyncJournalRetirementStatus(
            AppSyncJournalRetirementState.Blocked,
            reason,
        )
    AppSyncJournalRetirementMaintenanceResult.AlreadyRunning ->
        AppSyncJournalRetirementStatus(
            AppSyncJournalRetirementState.Pending,
            "已有同步或 journal 維護工作執行中",
            AppSyncJournalRetirementMessage.AlreadyRunning,
        )
}

internal fun AppSyncInstallationState.blocksRegularSync(): Boolean =
    this == AppSyncInstallationState.Quarantined

internal fun reliabilityOutcomeFor(phase: AppSyncServicePhase): String = when (phase) {
    AppSyncServicePhase.Active -> "CONVERGED"
    AppSyncServicePhase.RetryPending,
    AppSyncServicePhase.Running,
    AppSyncServicePhase.BootstrapRequired,
    -> "RETRY"
    AppSyncServicePhase.PausedAuth,
    AppSyncServicePhase.Disabled,
    -> "EXCLUDED_AUTH"
    AppSyncServicePhase.PausedProvider,
    AppSyncServicePhase.Quarantined,
    -> "MANUAL_INTERVENTION"
}

package me.thenano.yamibo.yamibo_app.repository.appsync.engine

import kotlinx.coroutines.CancellationException
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncInstallationState
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncBootstrapRollbackSnapshot
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncOperationLifecycle
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncVerifiedCheckpoint
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncAccountBinding
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncCausalContext
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperationOrigin
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncReplicaKey
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.resolvedPublishedThroughSequence
import me.thenano.yamibo.yamibo_app.store.appsync.AppSyncOperationStore
import me.thenano.yamibo.yamibo_app.store.appsync.LocalSyncOperationDraft

internal sealed interface AppSyncBootstrapResult {
    data class Ready(
        val mode: AppSyncBootstrapMode,
        val appliedOperationCount: Int,
        val changes: List<OperationChangeSummary>,
        val skippedOrphanRssHistoryCount: Int = 0,
    ) : AppSyncBootstrapResult
    data class RetryableFailure(val reason: String) : AppSyncBootstrapResult
    data class Paused(val reason: String) : AppSyncBootstrapResult
}

internal enum class AppSyncBootstrapMode {
    Seed,
    Join,
    Active,
}

internal data class CapturedBootstrapSnapshot(
    val migrationDrafts: List<LocalSyncOperationDraft>,
    val encodedRollbackSnapshot: String,
    val skippedOrphanRssHistoryCount: Int = 0,
)

internal class BootstrapCoordinator(
    private val store: AppSyncOperationStore,
    private val remote: AppSyncJournalRemote,
    private val domainState: SyncDomainStateAdapter,
    private val reducer: OperationReducer = OperationReducer(),
    private val localProjectionRepairPlanner: LocalProjectionRepairPlanner =
        LocalProjectionRepairPlanner(),
    private val nowMillis: () -> Long,
    private val inactiveAfterMillis: Long = 90L * 24 * 60 * 60 * 1_000,
    private val captureLocalSnapshot: () -> CapturedBootstrapSnapshot = {
        CapturedBootstrapSnapshot(emptyList(), "empty")
    },
) {
    suspend fun bootstrap(
        accountBinding: SyncAccountBinding,
        forceDiscovery: Boolean = true,
    ): AppSyncBootstrapResult {
        val installation = store.installation()
            ?: return AppSyncBootstrapResult.Paused("Installation is not initialized")
        val heartbeat = installation.lastVerifiedHeartbeatAt
        val inactive = heartbeat != null && nowMillis() - heartbeat > inactiveAfterMillis
        val accountChanged = installation.accountBinding != null &&
            installation.accountBinding != accountBinding
        if (accountChanged) {
            store.updateState(AppSyncInstallationState.RebootstrapRequired)
        }
        store.updateState(AppSyncInstallationState.Bootstrapping)

        val cloud = when (val result = remote.loadJournals(accountBinding, forceDiscovery)) {
            is AppSyncJournalLoadResult.Success -> result
            AppSyncJournalLoadResult.NotLoggedIn -> {
                store.updateState(AppSyncInstallationState.PausedAuth)
                return AppSyncBootstrapResult.Paused("Yamibo login is unavailable")
            }
            is AppSyncJournalLoadResult.RetryableFailure ->
                return AppSyncBootstrapResult.RetryableFailure(result.reason)
            is AppSyncJournalLoadResult.TerminalFailure -> {
                store.updateState(AppSyncInstallationState.PausedProvider)
                return AppSyncBootstrapResult.Paused(result.reason)
            }
        }
        val checkpoint = cloud.checkpoints.maxWithOrNull(
            compareBy(
                { it.envelope.payload.coverage.asStableMap().values.sum() },
                { it.envelope.payload.createdAtEpochMillis },
                { it.envelope.payload.checkpointId },
            ),
        )
        if (inactive && checkpoint == null) {
            store.updateState(AppSyncInstallationState.PausedProvider)
            return AppSyncBootstrapResult.Paused(
                "A verified checkpoint is required before a device inactive for 90 days can publish",
            )
        }
        if (inactive && checkpoint != null) {
            val oldReplica = SyncReplicaKey(installation.deviceId, installation.deviceEpoch)
            val remotePublishedThrough = cloud.journals
                .firstOrNull {
                    it.payload.deviceId == installation.deviceId &&
                        it.payload.deviceEpoch == installation.deviceEpoch
                }
                ?.payload
                ?.resolvedPublishedThroughSequence()
                ?: 0L
            val requiredSequence = maxOf(
                store.causalContext()[oldReplica],
                remotePublishedThrough,
            )
            if (checkpoint.envelope.payload.coverage[oldReplica] < requiredSequence) {
                store.updateState(AppSyncInstallationState.PausedProvider)
                return AppSyncBootstrapResult.Paused(
                    "Verified checkpoint does not cover the inactive device epoch",
                )
            }
        }

        val checkpointCoverage = checkpoint?.envelope?.payload?.coverage ?: SyncCausalContext()
        val initialState = checkpoint?.envelope?.payload?.resolvedEntities
            ?.associateBy { it.key }
            ?: emptyMap()
        val cloudOperations = cloud.journals
            .asSequence()
            .flatMap { it.payload.operations.asSequence() }
            .filterNot(checkpointCoverage::includes)
            .distinctBy { it.operationId }
            .toList()
        val cloudReduction = reducer.reduce(initialState, cloudOperations)
        if (cloudReduction.quarantined.isNotEmpty()) {
            store.updateState(AppSyncInstallationState.Quarantined)
            return AppSyncBootstrapResult.Paused(
                "Bootstrap contains ${cloudReduction.quarantined.size} quarantined operation(s)",
            )
        }
        val cloudCoverage = cloudOperations.fold(checkpointCoverage) { current, operation ->
            current.advance(operation.replicaKey, operation.sequence)
        }
        val requiresAdmission = installation.accountBinding == null ||
            accountChanged || inactive ||
            installation.state == AppSyncInstallationState.RebootstrapRequired
        val cloudEstablished = checkpoint != null || cloudOperations.isNotEmpty()
        val mode = when {
            !requiresAdmission -> AppSyncBootstrapMode.Active
            installation.accountBinding == null && !cloudEstablished -> AppSyncBootstrapMode.Seed
            else -> AppSyncBootstrapMode.Join
        }
        val capturedSnapshot = if (mode == AppSyncBootstrapMode.Active) {
            null
        } else {
            try {
                captureLocalSnapshot()
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                return AppSyncBootstrapResult.Paused(
                    "Local bootstrap snapshot failed: ${error.message ?: error::class.simpleName}",
                )
            }
        }
        val capturesMigration = mode == AppSyncBootstrapMode.Seed ||
            (mode == AppSyncBootstrapMode.Join && installation.accountBinding == null)
        if (capturesMigration) {
            try {
                val migrationDrafts = if (mode == AppSyncBootstrapMode.Join) {
                    localProjectionRepairPlanner.plan(
                        requireNotNull(capturedSnapshot).migrationDrafts,
                        cloudReduction.entities,
                    )
                } else {
                    requireNotNull(capturedSnapshot).migrationDrafts
                }
                store.captureBootstrapMigration(
                    accountBinding = accountBinding,
                    drafts = migrationDrafts,
                    causalContext = cloudCoverage,
                    createdAtEpochMillis = nowMillis(),
                )
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                return AppSyncBootstrapResult.Paused(
                    "Local migration persistence failed: ${error.message ?: error::class.simpleName}",
                )
            }
        }
        if (mode == AppSyncBootstrapMode.Join) {
            try {
                store.saveBootstrapRollbackSnapshot(
                    AppSyncBootstrapRollbackSnapshot(
                        accountBinding = accountBinding,
                        databaseGeneration = installation.databaseGeneration,
                        encodedSnapshot = requireNotNull(capturedSnapshot).encodedRollbackSnapshot,
                        createdAtEpochMillis = nowMillis(),
                    ),
                )
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                return AppSyncBootstrapResult.Paused(
                    "Local rollback snapshot failed: ${error.message ?: error::class.simpleName}",
                )
            }
        }

        val currentInstallation = requireNotNull(store.installation())
        val pendingLocalOperations = if (
            mode == AppSyncBootstrapMode.Join && installation.accountBinding != null
        ) {
            emptyList()
        } else {
            store.allOutboxOperations()
                .asSequence()
                .filter { (operation, lifecycle) ->
                    operation.accountBinding == accountBinding &&
                        operation.deviceId == currentInstallation.deviceId &&
                        operation.deviceEpoch == currentInstallation.deviceEpoch &&
                        lifecycle in setOf(
                            AppSyncOperationLifecycle.PendingLocal,
                            AppSyncOperationLifecycle.PublishedUnverified,
                        )
                }
                .map { it.first }
                .filter {
                    mode == AppSyncBootstrapMode.Active ||
                        it.origin == SyncOperationOrigin.Migration
                }
                .toList()
        }
        val combinedOperations = cloudOperations + pendingLocalOperations
        val reduction = reducer.reduce(initialState, combinedOperations)
        if (reduction.quarantined.isNotEmpty()) {
            store.updateState(AppSyncInstallationState.Quarantined)
            return AppSyncBootstrapResult.Paused(
                "Bootstrap contains ${reduction.quarantined.size} quarantined operation(s)",
            )
        }
        val coverage = combinedOperations.fold(checkpointCoverage) { current, operation ->
            current.advance(operation.replicaKey, operation.sequence)
        }
        val cloudOperationIds = cloud.journals
            .asSequence()
            .flatMap { it.payload.operations.asSequence() }
            .mapTo(linkedSetOf()) { it.operationId }
        val appliedAt = nowMillis()
        val checkpointRecord = checkpoint?.let { loaded ->
            val envelope = loaded.envelope
            AppSyncVerifiedCheckpoint(
                checkpointId = envelope.payload.checkpointId,
                blogId = loaded.remoteId.toLongOrNull(),
                coverage = envelope.payload.coverage,
                payloadFingerprint = envelope.fingerprint,
                createdAtEpochMillis = envelope.payload.createdAtEpochMillis,
                verifiedAtEpochMillis = appliedAt,
            )
        }
        try {
            store.completeBootstrap(
                accountBinding = accountBinding,
                result = reduction,
                coverage = coverage,
                cloudOperationIds = cloudOperationIds,
                appliedAtEpochMillis = appliedAt,
                rotateDeviceEpoch = mode == AppSyncBootstrapMode.Join &&
                    installation.accountBinding != null,
                checkpoint = checkpointRecord,
                domainMutation = {
                    domainState.adoptCheckpointWithinTransaction(it.entities.values)
                },
            )
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            return AppSyncBootstrapResult.RetryableFailure(
                "Bootstrap transaction failed: ${error.message ?: error::class.simpleName}",
            )
        }
        domainState.reconcileProjections()
        return AppSyncBootstrapResult.Ready(
            mode = mode,
            appliedOperationCount = reduction.appliedOperations.size,
            changes = summarizeWinningOperations(
                received = reduction.appliedOperations,
                uploaded = emptyList(),
                state = domainState.currentState(),
            ),
            skippedOrphanRssHistoryCount = capturedSnapshot?.skippedOrphanRssHistoryCount ?: 0,
        )
    }
}

package me.thenano.yamibo.yamibo_app.repository.appsync.engine

import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncInstallationState
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncAccountBinding
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncCausalContext
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperationOrigin
import me.thenano.yamibo.yamibo_app.store.appsync.AppSyncOperationStore
import me.thenano.yamibo.yamibo_app.store.appsync.LocalSyncOperationDraft

internal sealed interface AppSyncBootstrapResult {
    data class Ready(
        val appliedOperationCount: Int,
        val changes: List<OperationChangeSummary>,
    ) : AppSyncBootstrapResult
    data class RetryableFailure(val reason: String) : AppSyncBootstrapResult
    data class Paused(val reason: String) : AppSyncBootstrapResult
}

internal class BootstrapCoordinator(
    private val store: AppSyncOperationStore,
    private val remote: AppSyncJournalRemote,
    private val domainState: SyncDomainStateAdapter,
    private val reducer: OperationReducer = OperationReducer(),
    private val nowMillis: () -> Long,
    private val inactiveAfterMillis: Long = 90L * 24 * 60 * 60 * 1_000,
    private val captureLocalMigrationDrafts: () -> List<LocalSyncOperationDraft> = { emptyList() },
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
        val localMigrationDrafts = if (installation.accountBinding == null) {
            try {
                captureLocalMigrationDrafts()
            } catch (error: Throwable) {
                return AppSyncBootstrapResult.Paused(
                    "Local migration capture failed: ${error.message ?: error::class.simpleName}",
                )
            }
        } else {
            emptyList()
        }

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
        val checkpoint = cloud.checkpoints
            .maxWithOrNull(
                compareBy<LoadedAppSyncCheckpoint>(
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
        val checkpointCoverage = checkpoint?.envelope?.payload?.coverage ?: SyncCausalContext()
        val initialState = checkpoint?.envelope?.payload?.resolvedEntities
            ?.associateBy { it.key }
            ?: domainState.currentState()

        val operations = cloud.journals
            .asSequence()
            .flatMap { it.payload.operations.asSequence() }
            .filterNot(checkpointCoverage::includes)
            .filterNot { store.isApplied(it.operationId) }
            .distinctBy { it.operationId }
            .toList()
        val reduction = reducer.reduce(initialState, operations)
        if (reduction.quarantined.isNotEmpty()) {
            store.updateState(AppSyncInstallationState.Quarantined)
            return AppSyncBootstrapResult.Paused(
                "Bootstrap contains ${reduction.quarantined.size} quarantined operation(s)",
            )
        }
        if (checkpoint != null) {
            val envelope = checkpoint.envelope
            store.adoptCheckpoint(
                checkpointId = envelope.payload.checkpointId,
                blogId = checkpoint.remoteId.toLongOrNull(),
                coverage = envelope.payload.coverage,
                payloadFingerprint = envelope.fingerprint,
                createdAtEpochMillis = envelope.payload.createdAtEpochMillis,
                verifiedAtEpochMillis = nowMillis(),
                laterReduction = reduction,
                domainMutation = {
                    domainState.adoptCheckpointWithinTransaction(it.entities.values)
                },
            )
        } else {
            store.applyRemoteReduction(
                reduction,
                nowMillis(),
                domainState::applyWithinTransaction,
            )
        }
        domainState.reconcileProjections()

        if (inactive || accountChanged || installation.accountBinding == null) {
            store.rotateDeviceEpoch(accountBinding, AppSyncInstallationState.Active)
        } else {
            store.bindAccount(accountBinding, AppSyncInstallationState.Active)
        }
        val cloudEntityIdentities = domainState.currentState().keys
            .mapTo(hashSetOf()) { it.domainId to it.entityId }
        val admissibleLocalMigrations = localMigrationDrafts.filter { draft ->
            draft.domainId to draft.entityId !in cloudEntityIdentities
        }
        if (admissibleLocalMigrations.isNotEmpty()) {
            store.appendLocalOperations(
                accountBinding = accountBinding,
                drafts = admissibleLocalMigrations,
                causalContext = store.causalContext(),
                createdAtEpochMillis = nowMillis(),
                origin = SyncOperationOrigin.Migration,
            ) { migrationOperations ->
                val migrationReduction = reducer.reduce(
                    domainState.currentState(),
                    migrationOperations,
                )
                check(migrationReduction.quarantined.isEmpty()) {
                    "Local migration produced quarantined operations"
                }
                domainState.applyWithinTransaction(migrationReduction)
            }
            domainState.reconcileProjections()
        }
        return AppSyncBootstrapResult.Ready(
            appliedOperationCount = reduction.appliedOperations.size,
            changes = summarizeWinningOperations(
                received = reduction.appliedOperations,
                uploaded = emptyList(),
                state = domainState.currentState(),
            ),
        )
    }
}

package me.thenano.yamibo.yamibo_app.repository.appsync.engine

import me.thenano.yamibo.yamibo_app.Database
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncInstallationState
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncRecoveryPhase
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncAccountBinding
import me.thenano.yamibo.yamibo_app.store.appsync.AppSyncOperationStore
import me.thenano.yamibo.yamibo_app.store.appsync.SqlDelightAppSyncRecoveryStore

/** Called under the engine run lease, after cloud validation and cohort observation.
 * The SQL transaction freezes source identity and transport together; no remote writes occur here.
 */
internal class AppSyncNativeJournalStarter(
    private val db: Database,
    private val operations: AppSyncOperationStore,
    private val recovery: SqlDelightAppSyncRecoveryStore,
    private val state: SqlDelightCanonicalCheckpointState,
    private val activator: AppSyncCanonicalCheckpointActivator,
    private val nowMillis: () -> Long,
    private val canWrite: () -> Boolean,
) {
    fun start(account: SyncAccountBinding, cloud: AppSyncCanonicalCloudPlan.Ready): Result<String> = runCatching {
        check(canWrite()) { "Native writer rollout is not available" }
        require(recovery.recoverySession(account)?.phase.let { it == null || it == AppSyncRecoveryPhase.Completed }) {
            "An unfinished recovery already owns this account"
        }
        val activation = activator.activate(cloud.checkpoint, cloud.canonicalOperations, cloud.legacyOperations)
        check(activation is AppSyncCanonicalActivationResult.Applied && activation.settingsReconciled) {
            "Canonical cloud activation must complete before publication"
        }
        db.transactionWithResult {
            check(canWrite()) { "Native writer rollout changed during activation" }
            val installation = requireNotNull(operations.installation())
            require(installation.accountBinding == account && installation.state == AppSyncInstallationState.Active)
            require(recovery.recoverySession(account)?.phase.let { it == null || it == AppSyncRecoveryPhase.Completed })
            val row = requireNotNull(db.appSyncCanonicalStateQueries.getState().executeAsOneOrNull())
            require(row.settingsReconciliationPending == 0L)
            val local = requireNotNull(state.read(account.value))
            val baseline = AppSyncCanonicalJournalBaseline.prepare(installation, cloud).getOrThrow()
            val pending = operations.pendingOperations().filter { it.accountBinding == account }
            require(pending.all { it.deviceId == installation.deviceId && it.deviceEpoch == installation.deviceEpoch })
            // Only the indexed checkpoint, never local overlay coverage, authorizes this acknowledgement.
            val (covered, uncovered) = pending.partition {
                it.sequence.value <= (cloud.checkpoint.document.coverage[it.replicaKey.stableKey] ?: 0L)
            }
            val prepared = AppSyncCanonicalJournalPreparation().prepare(installation, local, baseline, uncovered,
                listOf(cloud.checkpoint), nowMillis(), "unknown")
            check(prepared is AppSyncCanonicalJournalPreparationResult.Ready) { "Native journal preparation requires attention" }
            check(canWrite()) { "Native writer rollout changed during preparation" }
            val session = recovery.createOrResumeSegmentedJournal(account,
                prepared.sourceOperationIds.map { it.value }.toSet(), prepared.fingerprint, nowMillis())
            recovery.pinPayload(session.sessionId, "Journal", "${installation.deviceId.value}:${installation.deviceEpoch.value}", 3) {
                prepared.envelope
            }
            operations.markAcknowledged(covered.map { it.operationId }.toSet(), nowMillis())
            session.sessionId
        }
    }
}

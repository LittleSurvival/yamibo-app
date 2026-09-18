package me.thenano.yamibo.yamibo_app.repository.appsync.engine

import me.thenano.yamibo.yamibo_app.Database
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncOperationLifecycle
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncCausalContext
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperation
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncVerifiedCanonicalCheckpoint
import me.thenano.yamibo.yamibo_app.repository.appsync.schema.AppSyncCanonicalCheckpointCodec
import me.thenano.yamibo.yamibo_app.repository.appsync.schema.AppSyncCanonicalOperationBlock
import me.thenano.yamibo.yamibo_app.store.appsync.AppSyncOperationStore
import me.thenano.yamibo.yamibo_app.store.appsync.SqlDelightAppSyncRecoveryStore
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncRecoveryMode
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncRecoveryPhase
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncVerifiedCheckpoint

internal sealed interface AppSyncCanonicalActivationResult {
    data class Applied(val pendingOperationCount: Int, val excludedCount: Int, val noOpCount: Int,
        val settingsReconciled: Boolean, val removedLocalRows: Int = 0,
        val removedLocalPayloadBytes: Long = 0) : AppSyncCanonicalActivationResult
    data class NeedsAttention(val reason: String,
        val mergeFailure: AppSyncPendingMergeFailure? = null) : AppSyncCanonicalActivationResult
}

/** Atomically installs index-verified remote state plus still-local edits. Generic activation
 * never acknowledges sources; journal recovery acknowledges only after settings reconciliation.
 * Local overlay coverage is never attributed to the verified remote checkpoint.
 */
internal class AppSyncCanonicalCheckpointActivator(
    private val db: Database,
    private val operations: AppSyncOperationStore,
    private val state: SqlDelightCanonicalCheckpointState,
    private val materializer: DatabaseSyncDomainMaterializer,
    private val nowMillis: () -> Long,
    private val merger: AppSyncCanonicalPendingMerge = AppSyncCanonicalPendingMerge(),
) {
    /** Install the current cloud plus the frozen, index-verified journal before acknowledging
     * its sources. External settings failure leaves the session resumable and unacknowledged.
     */
    fun activateJournalRecovery(recovery: SqlDelightAppSyncRecoveryStore, sessionId: String,
        cloud: AppSyncCanonicalCloudPlan.Ready): AppSyncCanonicalActivationResult = try {
        val session = requireNotNull(recovery.session(sessionId))
        require(session.mode == AppSyncRecoveryMode.SegmentedJournal && recovery.usesNativeTransport(sessionId))
        require(operations.installation()?.accountBinding == session.accountBinding)
        if (session.phase == AppSyncRecoveryPhase.Completed && session.indexCommitted) {
            AppSyncCanonicalActivationResult.Applied(operations.pendingOperations().count { it.accountBinding == session.accountBinding }, 0, 0, true)
        } else {
            val frozen = recovery.nativeJournalForActivation(sessionId)
            require(cloud.checkpoint.document.accountBinding == session.accountBinding.value &&
                cloud.canonicalOperations.accountBinding == session.accountBinding.value)
            val allOperations = cloud.canonicalOperations.operations + frozen.document.block.operations
            require(allOperations.groupBy { Triple(it.deviceId, it.deviceEpoch, it.sequence) }.values.all { it.distinct().size == 1 })
            val allProofs = cloud.canonicalOperations.authorizations + frozen.document.block.authorizations
            require(allProofs.groupBy { it.authorizationId }.values.all { it.distinct().size == 1 })
            val block = AppSyncCanonicalOperationBlock(session.accountBinding.value, allOperations.distinct(), allProofs.distinct())
            val required = frozen.document.observed.toMutableMap()
            val ownReplica = "${frozen.document.deviceId}:${frozen.document.deviceEpoch}"
            required[ownReplica] = maxOf(required[ownReplica] ?: 0L, frozen.document.lastSequence,
                frozen.document.publishedThroughSequence ?: 0L)
            for (coverage in frozen.document.acknowledgements.map { it.coverage } +
                frozen.document.block.operations.map { it.causalContext }) {
                for ((replica, sequence) in coverage) required[replica] = maxOf(required[replica] ?: 0L, sequence)
            }
            val result = activate(cloud.checkpoint, block, cloud.legacyOperations, requiredCoverage = required, beforeDatabaseActivation = {
                require(recovery.nativeJournalForActivation(sessionId) == frozen)
            })
            if (result is AppSyncCanonicalActivationResult.Applied && result.settingsReconciled) {
                db.transaction {
                    require(recovery.nativeJournalForActivation(sessionId) == frozen)
                    val row = requireNotNull(db.appSyncCanonicalStateQueries.getState().executeAsOneOrNull())
                    require(row.settingsReconciliationPending == 0L)
                    val head = requireNotNull(state.read(session.accountBinding.value))
                    require(required.all { (replica, sequence) -> (head.coverage[replica] ?: 0L) >= sequence })
                    recovery.activateCommittedSession(sessionId, nowMillis())
                }
            }
            result
        }
    } catch (_: Exception) { AppSyncCanonicalActivationResult.NeedsAttention("Native journal activation could not complete") }

    /** Recovery remains ActivatingLocal until both the SQL projection and external settings
     * are installed. Re-entry rebuilds the overlay from current pending edits after a crash.
     */
    fun activateRecovery(recovery: SqlDelightAppSyncRecoveryStore, sessionId: String,
        cloud: AppSyncCanonicalCloudPlan.Ready? = null): AppSyncCanonicalActivationResult = try {
        val session = requireNotNull(recovery.session(sessionId))
        require(session.mode == AppSyncRecoveryMode.SegmentedCheckpoint && recovery.usesNativeTransport(sessionId))
        require(operations.installation()?.accountBinding == session.accountBinding)
        if (session.phase == AppSyncRecoveryPhase.Completed && session.indexCommitted) {
            AppSyncCanonicalActivationResult.Applied(operations.pendingOperations().count { it.accountBinding == session.accountBinding }, 0, 0, true)
        } else {
            val verified = recovery.nativeCheckpointForActivation(sessionId)
            val plan = cloud?.let { AppSyncCanonicalRecoveryPlanner().prepare(verified, it) }
            require(plan !is AppSyncCanonicalCloudPlan.NeedsAttention) { "Recovery and current cloud cannot be reconciled" }
            val ready = plan as? AppSyncCanonicalCloudPlan.Ready
            val result = activate(ready?.checkpoint ?: verified, ready?.canonicalOperations, ready?.legacyOperations.orEmpty(),
                additionalVerifiedCheckpoint = verified, beforeDatabaseActivation = {
                val current = recovery.nativeCheckpointForActivation(sessionId)
                require(current.blogId == verified.blogId && current.fingerprint == verified.fingerprint &&
                    current.indexFingerprint == verified.indexFingerprint)
            })
            if (result is AppSyncCanonicalActivationResult.Applied && result.settingsReconciled)
                recovery.completeNativeCheckpointActivation(sessionId, nowMillis())
            result
        }
    } catch (_: Exception) { AppSyncCanonicalActivationResult.NeedsAttention("Native checkpoint activation could not complete") }

    fun activate(verified: AppSyncVerifiedCanonicalCheckpoint,
        remoteOperations: AppSyncCanonicalOperationBlock? = null,
        legacyRemoteOperations: List<SyncOperation> = emptyList(),
        additionalVerifiedCheckpoint: AppSyncVerifiedCanonicalCheckpoint? = null,
        requiredCoverage: Map<String, Long> = emptyMap(),
        beforeDatabaseActivation: () -> Unit = {}): AppSyncCanonicalActivationResult {
        var prepared: AppSyncCanonicalPendingMergeResult.Ready? = null
        var failed: AppSyncCanonicalActivationResult.NeedsAttention? = null
        var pendingCount = 0
        try {
            db.transaction {
                beforeDatabaseActivation()
                val checkpoint = verified.document
                if (operations.installation()?.accountBinding?.value != checkpoint.accountBinding) {
                    failed = AppSyncCanonicalActivationResult.NeedsAttention("Canonical activation account mismatch")
                    return@transaction
                }
                // Read inside the same transaction as activation so concurrent local appends
                // cannot fall between snapshot preparation and projection replacement.
                // Other accounts and discarded/superseded bodies remain audit evidence, never local overlays.
                // Acknowledged/compacted sources can still cover gaps in an older checkpoint.
                val sources = operations.allOutboxOperations()
                    .filter { (source, _) -> source.accountBinding.value == checkpoint.accountBinding }.filterNot { (_, lifecycle) ->
                    lifecycle == AppSyncOperationLifecycle.DiscardedByForcePull ||
                        lifecycle == AppSyncOperationLifecycle.DiscardedByRebootstrap ||
                        lifecycle == AppSyncOperationLifecycle.SupersededByRecovery
                }.map { it.first } + legacyRemoteOperations
                pendingCount = operations.pendingOperations().count { it.accountBinding.value == checkpoint.accountBinding }
                val merged = merger.prepare(checkpoint, sources, checkpoint.checkpointId, checkpoint.createdAtEpochMillis,
                    remoteOperations)
                if (merged is AppSyncCanonicalPendingMergeResult.NeedsAttention) {
                    failed = AppSyncCanonicalActivationResult.NeedsAttention("Canonical pending merge needs attention", merged.reason)
                    return@transaction
                }
                val ready = merged as AppSyncCanonicalPendingMergeResult.Ready
                if (requiredCoverage.any { (replica, sequence) -> (ready.checkpoint.coverage[replica] ?: 0L) < sequence }) {
                    failed = AppSyncCanonicalActivationResult.NeedsAttention("Canonical activation is missing required published history")
                    return@transaction
                }
                val previous = state.read(checkpoint.accountBinding)
                if (previous != null && previous.coverage.any { (replica, sequence) ->
                        (ready.checkpoint.coverage[replica] ?: 0L) < sequence }) {
                    failed = AppSyncCanonicalActivationResult.NeedsAttention("Canonical activation would lose existing coverage")
                    return@transaction
                }
                // A local overlay has a separate identity; it must never masquerade as the
                // indexed remote artifact when another local edit arrives during a retry.
                val localId = "local:" + AppSyncCanonicalCheckpointCodec().encode(ready.checkpoint.copy(checkpointId = "local")).sha256().hex()
                val local = ready.checkpoint.copy(checkpointId = localId)
                val proofs = remoteOperations?.authorizations.orEmpty().associateBy { it.authorizationId }
                val receipts = (sources + remoteOperations?.operations.orEmpty().map {
                    it.toLegacyOperationView(checkpoint.accountBinding, it.authorizationId?.let(proofs::get))
                }).distinctBy { it.operationId }
                operations.adoptCheckpoint(checkpoint.checkpointId, verified.blogId,
                    SyncCausalContext(checkpoint.coverage), verified.fingerprint,
                    checkpoint.createdAtEpochMillis, nowMillis(),
                    OperationReductionResult(emptyMap(), ready.conflicts, emptyList(), receipts),
                ) { state.replace(checkpoint.accountBinding, local) }
                additionalVerifiedCheckpoint?.let { evidence ->
                    require(evidence.document.accountBinding == checkpoint.accountBinding)
                    require(evidence.document.coverage.all { (replica, sequence) ->
                        (ready.checkpoint.coverage[replica] ?: 0L) >= sequence })
                    operations.saveVerifiedCheckpoint(AppSyncVerifiedCheckpoint(evidence.document.checkpointId, evidence.blogId,
                        SyncCausalContext(evidence.document.coverage), evidence.fingerprint,
                        evidence.document.createdAtEpochMillis, nowMillis()))
                }
                prepared = ready
            }
        } catch (_: Exception) {
            return AppSyncCanonicalActivationResult.NeedsAttention("Canonical activation transaction failed")
        }
        failed?.let { return it }
        val ready = requireNotNull(prepared)
        // Preferences are external to SQLite. A failure here must not be reported as rollback;
        // replay will reconcile again after finding the already committed canonical state.
        val reconciled = state.reconcileSettings(verified.document.accountBinding)
        val pruned = if (reconciled) try {
            me.thenano.yamibo.yamibo_app.repository.appsync.cleanup.AppSyncCanonicalLocalPruner(db, state)
                .prune(verified, nowMillis())
        } catch (_: Exception) {
            return AppSyncCanonicalActivationResult.NeedsAttention("Canonical state installed; local reclamation must be retried")
        } else null
        return AppSyncCanonicalActivationResult.Applied(pendingCount, ready.excludedCount, ready.noOpCount, reconciled,
            pruned?.removedRows ?: 0, pruned?.removedPayloadBytes ?: 0)
    }
}

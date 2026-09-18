package me.thenano.yamibo.yamibo_app.repository.appsync.engine

import me.thenano.yamibo.yamibo_app.Database
import me.thenano.yamibo.yamibo_app.repository.appsync.schema.AppSyncCanonicalCheckpoint
import me.thenano.yamibo.yamibo_app.repository.appsync.schema.AppSyncCanonicalCheckpointCodec
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperation
import okio.ByteString.Companion.toByteString

/** Local checkpoint state, not remote verification or an acknowledgement/cleanup authority.
 * The engine must first merge pending local operations and verify remote activation evidence.
 * Stores the operation table once as raw canonical bytes; no Base64 or nested compression.
 */
internal class SqlDelightCanonicalCheckpointState(
    private val db: Database,
    private val materializer: DatabaseSyncDomainMaterializer,
    private val codec: AppSyncCanonicalCheckpointCodec = AppSyncCanonicalCheckpointCodec(),
) {
    /** Records already-materialized local edits without replaying database projections.
     * Call from the outbox/local-mutation transaction, once for the entire command. A failed
     * merge leaves canonical state untouched; the caller must retain sources and surface it.
     * No outbox acknowledgement, remote checkpoint evidence or cleanup authority is created.
     */
    fun recordLocalBatch(expectedAccount: String, operations: List<SyncOperation>): AppSyncCanonicalLocalUpdate {
        var result: AppSyncCanonicalLocalUpdate = AppSyncCanonicalLocalUpdate.NotActivated
        db.transaction {
            val previous = read(expectedAccount) ?: return@transaction
            val installation = db.appSyncOperationQueries.getInstallation().executeAsOneOrNull()
            require(installation?.accountBinding == expectedAccount) { "Canonical installation account mismatch" }
            when (val merged = AppSyncCanonicalPendingMerge().prepare(previous, operations,
                previous.checkpointId, previous.createdAtEpochMillis)) {
                is AppSyncCanonicalPendingMergeResult.NeedsAttention -> {
                    result = AppSyncCanonicalLocalUpdate.NeedsAttention(merged)
                }
                is AppSyncCanonicalPendingMergeResult.Ready -> {
                    val changed = merged.checkpoint != previous
                    if (changed) {
                        // Do not hash the previous identity into the next identity: equal
                        // content reached by different command batching must converge.
                        val identity = "local:" + codec.encode(merged.checkpoint.copy(checkpointId = "local")).sha256().hex()
                        val checkpoint = merged.checkpoint.copy(checkpointId = identity)
                        val bytes = codec.encode(checkpoint)
                        db.appSyncCanonicalStateQueries.putState(expectedAccount, identity,
                            bytes.toByteArray(), bytes.sha256().hex())
                    }
                    result = AppSyncCanonicalLocalUpdate.Recorded(changed, merged.excludedCount,
                        merged.noOpCount, merged.conflicts)
                }
            }
        }
        return result
    }

    fun read(expectedAccount: String): AppSyncCanonicalCheckpoint? {
        val row = db.appSyncCanonicalStateQueries.getState().executeAsOneOrNull() ?: return null
        require(row.accountBinding == expectedAccount) { "Canonical state account mismatch" }
        val bytes = row.canonicalPayload.toByteString()
        require(bytes.sha256().hex() == row.canonicalSha256) { "Canonical state integrity failure" }
        return codec.decode(expectedAccount, row.checkpointId, bytes)
    }

    /** Resume external preference reconciliation from the latest head before snapshot audit.
     * Keep the marker across local edits and process death; never replay a stale settings mirror.
     */
    fun reconcileSettings(expectedAccount: String): Boolean = try {
        db.transactionWithResult {
            val row = db.appSyncCanonicalStateQueries.getState().executeAsOneOrNull()
                ?: return@transactionWithResult true
            require(row.accountBinding == expectedAccount) { "Canonical settings account mismatch" }
            if (row.settingsReconciliationPending == 0L) return@transactionWithResult true
            val current = requireNotNull(read(expectedAccount))
            val settings = current.entities.filter { it.domainId == 1 }
            val proofIds = settings.flatMap { it.fields.values + listOfNotNull(it.relation, it.tombstone) }
                .mapNotNullTo(hashSetOf()) { it.authorizationId }
            materializer.applyCanonicalProjections(expectedAccount,
                current.copy(entities = settings, authorizations = current.authorizations.filter { it.authorizationId in proofIds }))
            materializer.reconcileProjections()
            db.appSyncCanonicalStateQueries.markSettingsReconciled()
            true
        }
    } catch (_: Exception) { false }

    /** Replaces local materialized data and provenance in one SQLite transaction.
     * External preferences must be reconciled after the enclosing engine transaction commits.
     * Returns false for an identical already-applied checkpoint without touching local edits.
     */
    fun replace(expectedAccount: String, checkpoint: AppSyncCanonicalCheckpoint): Boolean {
        require(checkpoint.accountBinding == expectedAccount) { "Canonical state account mismatch" }
        val bytes = codec.encode(checkpoint)
        var changed = false
        db.transaction {
            val installation = db.appSyncOperationQueries.getInstallation().executeAsOneOrNull()
            require(installation?.accountBinding == null || installation.accountBinding == expectedAccount) {
                "Canonical installation account mismatch"
            }
            val previous = read(expectedAccount)
            if (previous?.checkpointId == checkpoint.checkpointId) {
                require(codec.encode(previous) == bytes) { "Canonical checkpoint identity collision" }
                return@transaction
            }
            require(previous == null || previous.coverage.all { (replica, sequence) ->
                (checkpoint.coverage[replica] ?: 0L) >= sequence
            }) { "Canonical checkpoint coverage regression" }
            materializer.preserveLocalPresentationDuring {
                materializer.clearSyncableData()
                materializer.applyCanonicalProjections(expectedAccount, checkpoint)
                db.appSyncCanonicalStateQueries.putState(expectedAccount, checkpoint.checkpointId,
                    bytes.toByteArray(), bytes.sha256().hex())
                db.appSyncCanonicalStateQueries.markSettingsPending()
            }
            changed = true
        }
        return changed
    }
}

internal sealed interface AppSyncCanonicalLocalUpdate {
    data object NotActivated : AppSyncCanonicalLocalUpdate
    data class Recorded(val changed: Boolean, val excludedCount: Int, val noOpCount: Int,
        val conflicts: List<SyncConflictRecord>) : AppSyncCanonicalLocalUpdate
    data class NeedsAttention(val failure: AppSyncCanonicalPendingMergeResult.NeedsAttention) : AppSyncCanonicalLocalUpdate
}

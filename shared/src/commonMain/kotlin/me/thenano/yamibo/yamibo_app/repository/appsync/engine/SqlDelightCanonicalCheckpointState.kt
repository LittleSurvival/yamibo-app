package me.thenano.yamibo.yamibo_app.repository.appsync.engine

import me.thenano.yamibo.yamibo_app.Database
import me.thenano.yamibo.yamibo_app.repository.appsync.schema.AppSyncCanonicalCheckpoint
import me.thenano.yamibo.yamibo_app.repository.appsync.schema.AppSyncCanonicalCheckpointCodec
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
    fun read(expectedAccount: String): AppSyncCanonicalCheckpoint? {
        val row = db.appSyncCanonicalStateQueries.getState().executeAsOneOrNull() ?: return null
        require(row.accountBinding == expectedAccount) { "Canonical state account mismatch" }
        val bytes = row.canonicalPayload.toByteString()
        require(bytes.sha256().hex() == row.canonicalSha256) { "Canonical state integrity failure" }
        return codec.decode(expectedAccount, row.checkpointId, bytes)
    }

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
            }
            changed = true
        }
        return changed
    }
}

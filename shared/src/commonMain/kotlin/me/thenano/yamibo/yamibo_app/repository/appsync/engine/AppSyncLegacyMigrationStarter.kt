package me.thenano.yamibo.yamibo_app.repository.appsync.engine

import me.thenano.yamibo.yamibo_app.Database
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncRecoveryPhase
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncAccountBinding
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.*
import me.thenano.yamibo.yamibo_app.repository.appsync.schema.AppSyncCanonicalCheckpointCodec
import me.thenano.yamibo.yamibo_app.store.appsync.*

/** Run under the engine lease. Freeze a first native checkpoint without applying its state
 * or acknowledging any source. Existing unfinished work must resume its original payload.
 */
internal class AppSyncLegacyMigrationStarter(
    private val db: Database,
    private val operations: AppSyncOperationStore,
    private val recovery: SqlDelightAppSyncRecoveryStore,
    private val nowMillis: () -> Long,
    private val canWrite: () -> Boolean,
) {
    fun start(account: SyncAccountBinding, cloud: AppSyncJournalLoadResult.Success): Result<String> = runCatching {
        check(canWrite())
        db.transactionWithResult {
            require(recovery.recoverySession(account)?.phase.let { it == null || it == AppSyncRecoveryPhase.Completed })
            // An existing native head must use native cloud recovery, never rebootstrap from v2.
            require(db.appSyncCanonicalStateQueries.getState().executeAsOneOrNull() == null)
            val installation = requireNotNull(operations.installation())
            val pending = operations.pendingOperations().filter { it.accountBinding == account }
            val timestamp = nowMillis()
            val prepared = AppSyncLegacyCloudMigration().prepare(account, installation, cloud, pending, "candidate", timestamp)
            require(prepared is AppSyncLegacyCloudMigrationResult.Ready) { "Legacy migration requires attention" }
            val hash = AppSyncCanonicalCheckpointCodec().encode(prepared.checkpoint).sha256().hex()
            val checkpoint = prepared.checkpoint.copy(checkpointId = "v3-$hash")
            val codec = AppSyncV3DocumentCodec()
            val envelope = codec.encodeCheckpoint(checkpoint)
            val read = codec.discover(envelope, account.value, AppSyncV3PayloadKind.Checkpoint) as? AppSyncV3DocumentRead.Checkpoint
            require(read?.document == checkpoint)
            check(canWrite())
            val session = recovery.createOrResumeSegmentedCheckpoint(account, checkpoint.checkpointId,
                requireNotNull(read).metadata.canonicalFingerprint, timestamp)
            recovery.pinPayload(session.sessionId, "Checkpoint", checkpoint.checkpointId, 3) { envelope }
            recovery.freezeLegacyMigrationSource(session.sessionId, prepared.source)
            session.sessionId
        }
    }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
}

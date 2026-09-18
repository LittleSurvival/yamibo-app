package me.thenano.yamibo.yamibo_app.repository.appsync.cleanup

import me.thenano.yamibo.yamibo_app.Database
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.SqlDelightCanonicalCheckpointState
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncVerifiedCanonicalCheckpoint
import me.thenano.yamibo.yamibo_app.repository.appsync.schema.AppSyncCanonicalCheckpointCodec
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncRecoveryPhase
import me.thenano.yamibo.yamibo_app.store.appsync.SqlDelightAppSyncRecoveryStore

internal data class AppSyncLocalPruneResult(val removedRows: Int, val removedPayloadBytes: Long, val hasMore: Boolean = false)

/** Logical payload reclamation only. Does not VACUUM, delete cloud data or remove replay receipts.
 * SQL deletion and payload-free audit commit together; rollback preserves both source and evidence.
 */
internal class AppSyncCanonicalLocalPruner(private val db: Database,
    private val state: SqlDelightCanonicalCheckpointState) {
    fun prune(verified: AppSyncVerifiedCanonicalCheckpoint, now: Long, maximumRows: Int = 128): AppSyncLocalPruneResult =
        pruneVerified(verified, now, maximumRows, null)

    fun pruneRecoveryCheckpoint(sessionId: String, now: Long, maximumRows: Int = 128): AppSyncLocalPruneResult =
        db.transactionWithResult {
            val recovery = SqlDelightAppSyncRecoveryStore(db)
            require(recovery.session(sessionId)?.phase == AppSyncRecoveryPhase.Cleaning)
            val verified = recovery.nativeCheckpointForActivation(sessionId)
            val result = pruneVerified(verified, now, maximumRows, sessionId)
            recovery.transition(sessionId, AppSyncRecoveryPhase.Cleaning,
                if (result.hasMore) AppSyncRecoveryPhase.Cleaning else AppSyncRecoveryPhase.Completed, now,
                retryCount = 0, retryIdentity = null)
            result
        }

    private fun pruneVerified(verified: AppSyncVerifiedCanonicalCheckpoint, now: Long, maximumRows: Int,
        cleaningSessionId: String?): AppSyncLocalPruneResult =
        db.transactionWithResult {
            require(now >= 0 && maximumRows in 1..128)
            val account = verified.document.accountBinding
            val operations = db.appSyncOperationQueries
            require(operations.getInstallation().executeAsOneOrNull()?.accountBinding == account)
            // Active recovery may pin a different physical alias of the same checkpoint.
            // Generic cleanup does nothing; its dedicated Cleaning entry verifies frozen evidence.
            val recovery = operations.getRecoverySessionByAccount(account).executeAsOneOrNull()
            if (recovery != null && recovery.phase != "COMPLETED" &&
                !(recovery.sessionId == cleaningSessionId && recovery.phase == "CLEANING"))
                return@transactionWithResult AppSyncLocalPruneResult(0, 0)
            // The verified document is mutable through caller-owned collections; recheck its digest.
            require(AppSyncCanonicalCheckpointCodec().encode(verified.document).sha256().hex() == verified.fingerprint)
            val saved = requireNotNull(operations.getCheckpoint(verified.document.checkpointId).executeAsOneOrNull())
            require(saved.state == "VERIFIED" && saved.blogId == verified.blogId && saved.payloadFingerprint == verified.fingerprint)
            val row = requireNotNull(db.appSyncCanonicalStateQueries.getState().executeAsOneOrNull())
            require(row.accountBinding == account && row.settingsReconciliationPending == 0L)
            val head = requireNotNull(state.read(account))
            require(verified.document.coverage.all { (replica, sequence) -> (head.coverage[replica] ?: 0L) >= sequence })
            val queries = db.appSyncLocalPruneQueries
            if (now >= RETENTION_MILLIS) queries.expireAudit(now - RETENTION_MILLIS)
            var removed = 0
            var bytes = 0L
            for ((replica, sequence) in verified.document.coverage.entries.sortedBy { it.key }) {
                if (removed == maximumRows) break
                val candidates = queries.getCandidates(account, replica, sequence, (maximumRows - removed).toLong()).executeAsList()
                if (candidates.isEmpty()) continue
                queries.deleteCandidates(candidates.map { it.operationId })
                removed += candidates.size
                bytes += candidates.sumOf { it.payloadBytes }
            }
            if (removed > 0) {
                queries.recordAudit(account, verified.fingerprint, now)
                queries.addAudit(removed.toLong(), bytes, now, account, verified.fingerprint)
            }
            val hasMore = verified.document.coverage.any { (replica, sequence) ->
                queries.getCandidates(account, replica, sequence, 1).executeAsList().isNotEmpty()
            }
            AppSyncLocalPruneResult(removed, bytes, hasMore)
        }

    companion object { const val RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1000 }
}

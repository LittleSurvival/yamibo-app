package me.thenano.yamibo.yamibo_app.repository.appsync.cleanup

import me.thenano.yamibo.yamibo_app.Database
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.SqlDelightCanonicalCheckpointState
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.*
import me.thenano.yamibo.yamibo_app.repository.appsync.domain.stableAppSyncFingerprint
import okio.ByteString.Companion.encodeUtf8
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
            if (result.hasMore) {
                recovery.transition(sessionId, AppSyncRecoveryPhase.Cleaning, AppSyncRecoveryPhase.Cleaning, now,
                    retryCount = 0, retryIdentity = null)
                result
            } else result.copy(removedPayloadBytes = result.removedPayloadBytes +
                recovery.completeNativeCheckpointCleanup(sessionId, now))
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
            val frozenBytes = if (cleaningSessionId == null)
                SqlDelightAppSyncRecoveryStore(db).pruneCompletedNativeJournal(verified) else 0L
            val retained = pruneRetained(verified, now)
            AppSyncLocalPruneResult(removed, bytes + frozenBytes + retained.first, hasMore || retained.second)
        }

    /** Runs only inside the validated checkpoint/head transaction above. Each retained body
     * is decoded separately; the persistent per-checkpoint cursor prevents uncovered rows
     * from starving later rows or causing an endless Cleaning loop.
     */
    private fun pruneRetained(verified: AppSyncVerifiedCanonicalCheckpoint, now: Long): Pair<Long, Boolean> {
        val account = verified.document.accountBinding
        val queries = db.appSyncRetainedJournalQueries
        var removed = 0L
        var bytes = 0L
        for (id in queries.getUnchecked(account, verified.fingerprint, 8).executeAsList()) {
            val row = queries.getBySession(id).executeAsOne()
            require(row.accountBinding == account && row.rootBlogId > 0 && row.verifiedIndexBlogId > 0 &&
                row.rootBlogId != row.verifiedIndexBlogId)
            require(stableAppSyncFingerprint(row.canonicalEnvelope) == row.envelopeFingerprint &&
                row.indexIntentBody.encodeUtf8().sha256().hex() == row.indexIntentSha256)
            val read = AppSyncV3DocumentCodec().discover(row.canonicalEnvelope, account, AppSyncV3PayloadKind.Journal)
                as? AppSyncV3DocumentRead.Journal
            requireNotNull(read)
            require(read.metadata.identity == row.payloadIdentity)
            val index = (AppSyncIndexEnvelopeCodec().validate(row.indexIntentBody) as? AppSyncIndexValidation.Valid)?.envelope
            requireNotNull(index)
            require(index.payload.accountBinding.value == account && index.fingerprint == row.verifiedIndexFingerprint)
            val reference = index.payload.journals.distinct().singleOrNull { it.replicaKey == row.payloadIdentity }
            require(reference?.blogId?.toLong() == row.rootBlogId && reference.fingerprint == read.metadata.canonicalFingerprint)
            val journal = read.document
            val required = journal.observed.toMutableMap()
            val own = "${journal.deviceId}:${journal.deviceEpoch}"
            required[own] = maxOf(required[own] ?: 0L, journal.lastSequence, journal.publishedThroughSequence ?: 0L)
            for (coverage in journal.acknowledgements.map { it.coverage } + journal.block.operations.map { it.causalContext }) {
                coverage.forEach { (replica, sequence) -> required[replica] = maxOf(required[replica] ?: 0L, sequence) }
            }
            if (required.all { (replica, sequence) -> (verified.document.coverage[replica] ?: 0L) >= sequence }) {
                bytes += row.canonicalEnvelope.encodeUtf8().size.toLong() + row.indexIntentBody.encodeUtf8().size
                queries.deleteCovered(id)
                removed++
            } else queries.markChecked(verified.fingerprint, id)
        }
        if (removed > 0) {
            db.appSyncLocalPruneQueries.recordAudit(account, verified.fingerprint, now)
            db.appSyncLocalPruneQueries.addRetainedAudit(removed, bytes, now, account, verified.fingerprint)
        }
        return bytes to queries.getUnchecked(account, verified.fingerprint, 1).executeAsList().isNotEmpty()
    }

    companion object { const val RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1000 }
}

package me.thenano.yamibo.yamibo_app.store.appsync

import me.thenano.yamibo.yamibo_app.Database
import me.thenano.yamibo.yamibo_app.AppSyncRecoveryWork
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncRecoveryPhase
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncRecoverySession
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncAccountBinding

/** Device-local scheduling evidence; contains no synchronized user payload. */
data class AppSyncRecoveryWorkRequest(val sessionId: String, val requestId: String,
    val notBeforeEpochMillis: Long, val enqueuedAtEpochMillis: Long?, val startedAtEpochMillis: Long?)

internal class SqlDelightAppSyncRecoveryWorkStore(private val db: Database) {
    private val queries = db.appSyncRecoveryWorkQueries
    private val recovery = SqlDelightAppSyncRecoveryStore(db)

    fun current(account: SyncAccountBinding): AppSyncRecoveryWorkRequest? {
        val session = recovery.recoverySession(account) ?: return null
        return current(session)?.toRequest()
    }

    fun prepare(account: SyncAccountBinding, proposedId: String, now: Long): AppSyncRecoveryWorkRequest? = db.transactionWithResult {
        require(now >= 0 && now <= Long.MAX_VALUE - 30_000)
        require(proposedId.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")))
        val session = recovery.recoverySession(account) ?: return@transactionWithResult null
        if (!session.active()) return@transactionWithResult null
        val existing = current(session)
        if (existing != null && existing.startedAtEpochMillis == null) return@transactionWithResult existing.toRequest()
        val previous = queries.getForSession(session.sessionId).executeAsOneOrNull()
        val predecessor = when {
            previous == null || previous.retiredAtEpochMillis != null -> null
            previous.startedAtEpochMillis != null -> previous.requestId
            previous.enqueuedAtEpochMillis == null -> previous.predecessorRequestId
            else -> null
        }
        queries.prepare(session.sessionId, proposedId, predecessor, key(session), session.nextRetryAtEpochMillis ?: now + 30_000, now)
        requireNotNull(current(session)).toRequest()
    }

    fun markEnqueued(requestId: String, now: Long): Boolean = updateCurrent(requestId, now) {
        queries.markEnqueued(now, requestId)
    }

    fun markStarted(requestId: String, now: Long): Boolean = updateCurrent(requestId, now) {
        queries.markStarted(now, requestId)
    }

    /** WorkManager may restart the executing predecessor after it persisted a new retry
     * state but before it enqueued a successor. Only that still-retained, started UUID may
     * bridge the changed key; an unstarted stale request must never acquire this privilege.
     */
    fun begin(account: SyncAccountBinding, requestId: String, now: Long): Boolean = db.transactionWithResult {
        require(now >= 0)
        val row = queries.getForExecution(requestId, requestId).executeAsOneOrNull() ?: return@transactionWithResult false
        val session = recovery.session(row.sessionId) ?: return@transactionWithResult false
        if (session.accountBinding != account || !session.active() || row.retiredAtEpochMillis != null)
            return@transactionWithResult false
        if (row.requestId != requestId) {
            return@transactionWithResult row.predecessorRequestId == requestId &&
                row.enqueuedAtEpochMillis == null && row.startedAtEpochMillis == null
        }
        if (current(session)?.requestId != requestId && row.startedAtEpochMillis == null)
            return@transactionWithResult false
        queries.markStarted(now, requestId)
        true
    }

    fun retire(requestId: String, now: Long) {
        require(now >= 0)
        queries.retire(now, requestId)
    }

    private fun updateCurrent(requestId: String, now: Long, update: () -> Unit): Boolean = db.transactionWithResult {
        require(now >= 0)
        val row = queries.getForRequest(requestId).executeAsOneOrNull() ?: return@transactionWithResult false
        val session = recovery.session(row.sessionId) ?: return@transactionWithResult false
        if (current(session)?.requestId != requestId) return@transactionWithResult false
        update()
        true
    }

    private fun current(session: AppSyncRecoverySession): AppSyncRecoveryWork? {
        if (!session.active()) return null
        val row = queries.getForSession(session.sessionId).executeAsOneOrNull() ?: return null
        return row.takeIf { it.retryKey == key(session) && it.retiredAtEpochMillis == null &&
            (session.nextRetryAtEpochMillis == null || it.notBeforeEpochMillis == session.nextRetryAtEpochMillis) }
    }

    private fun key(session: AppSyncRecoverySession) = listOf(session.generationId, session.phase.name,
        session.replacementFingerprint, session.retryIdentity.orEmpty(), session.retryCount.toString()).joinToString(":")
    private fun AppSyncRecoverySession.active() = phase != AppSyncRecoveryPhase.Completed && phase != AppSyncRecoveryPhase.NeedsAttention
    private fun AppSyncRecoveryWork.toRequest() = AppSyncRecoveryWorkRequest(sessionId, requestId, notBeforeEpochMillis,
        enqueuedAtEpochMillis, startedAtEpochMillis)
}

package me.thenano.yamibo.yamibo_app.repository.appsync.engine

import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncCausalContext
import me.thenano.yamibo.yamibo_app.store.appsync.AppSyncOperationStore

internal class CompactionCoordinator(
    private val store: AppSyncOperationStore,
    private val nowMillis: () -> Long,
    private val inactiveAfterMillis: Long = 90L * 24 * 60 * 60 * 1_000,
) {
    fun compactIfSafe(journals: List<LoadedAppSyncJournal>): SyncCausalContext? {
        val checkpoint = store.verifiedCheckpoints().maxWithOrNull(
            compareBy(
                { it.coverage.asStableMap().values.sum() },
                { it.createdAtEpochMillis },
                { it.checkpointId },
            ),
        ) ?: return null
        val active = journals.filter {
            nowMillis() - it.payload.heartbeatAtEpochMillis <= inactiveAfterMillis
        }
        if (active.isEmpty()) return null
        val fullyAcknowledged = active.all { journal ->
            journal.payload.observed.covers(checkpoint.coverage) &&
                journal.payload.checkpointAcknowledgements.any {
                    it.checkpointId == checkpoint.checkpointId &&
                        it.coverage == checkpoint.coverage
                }
        }
        if (!fullyAcknowledged) return null

        val covered = store.allOutboxOperations()
            .map { it.first }
            .filter(checkpoint.coverage::includes)
            .mapTo(linkedSetOf()) { it.operationId }
        store.markCompacted(covered)
        return checkpoint.coverage
    }

    private fun SyncCausalContext.covers(other: SyncCausalContext): Boolean {
        val mine = asStableMap()
        return other.asStableMap().all { (replica, sequence) ->
            (mine[replica] ?: 0L) >= sequence
        }
    }
}

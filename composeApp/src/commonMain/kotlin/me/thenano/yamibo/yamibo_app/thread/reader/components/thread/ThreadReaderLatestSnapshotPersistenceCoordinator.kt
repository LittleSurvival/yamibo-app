package me.thenano.yamibo.yamibo_app.thread.reader.components.thread

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.milliseconds

/**
 * Coalesces a burst of UI snapshots into one serialized durable write.
 *
 * Snapshot capture stays with the caller; this coordinator only owns immutable values and never
 * reads Compose state from its delayed job.
 */
internal class ThreadReaderLatestSnapshotPersistenceCoordinator<T, K>(
    private val scope: CoroutineScope,
    private val quietPeriodMillis: Long,
    private val semanticKey: (T) -> K,
    private val persist: suspend (T) -> Unit,
    private val waitForQuietPeriod: suspend (Long) -> Unit = { delay(it.milliseconds) },
) {
    private val stateMutex = Mutex()
    private val writeMutex = Mutex()
    private var pending: T? = null
    private var scheduledWrite: Job? = null
    private var lastPersistedKey: K? = null
    private var hasPersisted = false

    suspend fun submit(snapshot: T) {
        stateMutex.withLock {
            val key = semanticKey(snapshot)
            val pendingKey = pending?.let(semanticKey)
            if (pendingKey == key || (pending == null && hasPersisted && lastPersistedKey == key)) {
                return
            }
            pending = snapshot
            scheduledWrite?.cancel()
            scheduledWrite = scope.launch {
                waitForQuietPeriod(quietPeriodMillis)
                persistPending(cancelScheduledWrite = false)
            }
        }
    }

    suspend fun flush() {
        persistPending(cancelScheduledWrite = true)
    }

    suspend fun cancelPending() {
        stateMutex.withLock {
            scheduledWrite?.cancel()
            scheduledWrite = null
            pending = null
        }
    }

    private suspend fun persistPending(cancelScheduledWrite: Boolean) {
        val snapshot = stateMutex.withLock {
            if (cancelScheduledWrite) scheduledWrite?.cancel()
            scheduledWrite = null
            pending.also { pending = null }
        } ?: return
        val key = semanticKey(snapshot)
        writeMutex.withLock {
            val duplicate = stateMutex.withLock { hasPersisted && lastPersistedKey == key }
            if (duplicate) return
            persist(snapshot)
            stateMutex.withLock {
                lastPersistedKey = key
                hasPersisted = true
            }
        }
    }
}
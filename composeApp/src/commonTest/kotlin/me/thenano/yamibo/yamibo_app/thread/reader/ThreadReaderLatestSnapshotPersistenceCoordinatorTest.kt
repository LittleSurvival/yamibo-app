package me.thenano.yamibo.yamibo_app.thread.reader

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import me.thenano.yamibo.yamibo_app.thread.reader.components.thread.ThreadReaderLatestSnapshotPersistenceCoordinator
import kotlin.test.Test
import kotlin.test.assertEquals

class ThreadReaderLatestSnapshotPersistenceCoordinatorTest {
    @Test
    fun burstKeepsOnlyLatestSemanticSnapshot() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val gate = CompletableDeferred<Unit>()
        val writes = mutableListOf<Snapshot>()
        val coordinator = ThreadReaderLatestSnapshotPersistenceCoordinator<Snapshot, Int>(
            scope = scope,
            quietPeriodMillis = 1_000,
            semanticKey = Snapshot::position,
            persist = { writes += it },
            waitForQuietPeriod = { gate.await() },
        )

        coordinator.submit(Snapshot(position = 1, timestamp = 10))
        coordinator.submit(Snapshot(position = 2, timestamp = 20))
        coordinator.submit(Snapshot(position = 3, timestamp = 30))
        gate.complete(Unit)
        yield()

        assertEquals(listOf(Snapshot(position = 3, timestamp = 30)), writes)
        scope.cancel()
    }

    @Test
    fun explicitFlushPersistsLatestAndSkipsTimestampOnlyDuplicate() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val writes = mutableListOf<Snapshot>()
        val coordinator = ThreadReaderLatestSnapshotPersistenceCoordinator(
            scope = scope,
            quietPeriodMillis = 1_000,
            semanticKey = Snapshot::position,
            persist = { writes += it },
            waitForQuietPeriod = { CompletableDeferred<Unit>().await() },
        )

        coordinator.submit(Snapshot(position = 7, timestamp = 10))
        coordinator.flush()
        coordinator.submit(Snapshot(position = 7, timestamp = 99))
        coordinator.flush()

        assertEquals(listOf(Snapshot(position = 7, timestamp = 10)), writes)
        scope.cancel()
    }

    @Test
    fun writesAreSerializedAndNewerSnapshotFinishesLast() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val firstWriteGate = CompletableDeferred<Unit>()
        val writes = mutableListOf<Int>()
        val coordinator = ThreadReaderLatestSnapshotPersistenceCoordinator<Int, Int>(
            scope = scope,
            quietPeriodMillis = 1_000,
            semanticKey = { it },
            persist = { value ->
                if (value == 1) firstWriteGate.await()
                writes += value
            },
            waitForQuietPeriod = { CompletableDeferred<Unit>().await() },
        )

        coordinator.submit(1)
        val firstFlush = launch { coordinator.flush() }
        yield()
        coordinator.submit(2)
        val secondFlush = launch { coordinator.flush() }
        yield()
        assertEquals(emptyList(), writes)

        firstWriteGate.complete(Unit)
        firstFlush.join()
        secondFlush.join()

        assertEquals(listOf(1, 2), writes)
        scope.cancel()
    }

    @Test
    fun cancellationDropsOnlyTheUnflushedSnapshot() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val writes = mutableListOf<Int>()
        val coordinator = ThreadReaderLatestSnapshotPersistenceCoordinator<Int, Int>(
            scope = scope,
            quietPeriodMillis = 1_000,
            semanticKey = { it },
            persist = { writes += it },
            waitForQuietPeriod = { CompletableDeferred<Unit>().await() },
        )

        coordinator.submit(1)
        coordinator.flush()
        coordinator.submit(2)
        coordinator.cancelPending()
        coordinator.flush()

        assertEquals(listOf(1), writes)
        scope.cancel()
    }

    private data class Snapshot(val position: Int, val timestamp: Long)
}

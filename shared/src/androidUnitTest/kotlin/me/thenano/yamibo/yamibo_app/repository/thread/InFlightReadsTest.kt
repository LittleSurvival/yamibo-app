package me.thenano.yamibo.yamibo_app.repository.thread

import kotlinx.coroutines.*
import kotlin.test.*

class InFlightReadsTest {
    @Test fun twoReadersShareOneAttemptWhenFirstLeaves() = runBlocking {
        val reads = InFlightReads<String, Int>()
        val started = CompletableDeferred<Unit>()
        val reply = CompletableDeferred<Int>()
        var calls = 0
        val first = async { reads.read("A/page-2") { calls++; started.complete(Unit); reply.await() } }
        started.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            reads.read("A/page-2") { error("duplicate transfer") }
        }
        first.cancelAndJoin()
        reply.complete(17)
        assertEquals(17, second.await())
        assertEquals(1, calls)
    }

    @Test fun lastReaderCancelsThePendingJobAndRetryStartsFresh() = runBlocking {
        val reads = InFlightReads<String, Int>()
        val started = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val reader = launch {
            reads.read("page") {
                try { started.complete(Unit); awaitCancellation() } finally { stopped.complete(Unit) }
            }
        }
        started.await()
        reader.cancelAndJoin()
        withTimeout(2000) { stopped.await() }
        assertEquals(9, reads.read("page") { 9 })
    }

    @Test fun differentAccountsCanFinishIndependently() = runBlocking {
        val reads = InFlightReads<String, String>()
        val started = CompletableDeferred<Unit>()
        val reply = CompletableDeferred<String>()
        val first = async { reads.read("A") { started.complete(Unit); reply.await() } }
        started.await()
        assertEquals("other", withTimeout(2000) { reads.read("B") { "other" } })
        reply.complete("first")
        assertEquals("first", first.await())
    }
}

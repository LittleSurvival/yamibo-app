package me.thenano.yamibo.yamibo_app.repository.thread

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Identical readers share the pending result. The last departing reader cancels the request. */
internal class InFlightReads<K, V> {
    private class Pending<V>(val result: Deferred<V>, var readers: Int = 0)
    private val mutex = Mutex()
    private val requests = mutableMapOf<K, Pending<V>>()

    suspend fun read(key: K, fetch: suspend () -> V): V {
        val context = currentCoroutineContext()
        val pending = mutex.withLock {
            requests.getOrPut(key) {
                Pending(CoroutineScope(context.minusKey(Job)).async(start = CoroutineStart.LAZY) { fetch() })
            }.also { it.readers++ }
        }
        try {
            return pending.result.await()
        } finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    if (--pending.readers == 0) {
                        requests.remove(key)
                        pending.result.cancel()
                    }
                }
            }
        }
    }
}

package me.thenano.yamibo.yamibo_app.components.controls

import kotlinx.coroutines.*
import kotlin.test.*

class RefreshRequestTest {
    @Test fun successAndFailureAlwaysFinishButOnlyFailureReports() = runBlocking {
        val events = mutableListOf<String>()
        launchRefresh({ events += "finished" }, { events += "failed" }) { events += "loaded" }.join()
        assertEquals(listOf("loaded", "finished"), events)
        events.clear()
        launchRefresh({ events += "finished" }, { events += "failed" }) {
            throw IllegalStateException("simulated cache write failure")
        }.join()
        assertEquals(listOf("failed", "finished"), events)
    }

    @Test fun cancellationFinishesWithoutReportingNetworkFailure() = runBlocking {
        var finished = 0
        var failed = 0
        val job = launchRefresh({ finished++ }, { failed++ }) { awaitCancellation() }
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(1, finished)
        assertEquals(0, failed)
    }

    @Test fun alreadyCancelledScopeStillClearsRefreshWithoutStartingRequest() = runBlocking {
        val parent = Job().apply { cancel() }
        val scope = CoroutineScope(parent + Dispatchers.Default)
        var finished = 0
        var started = 0
        var failed = 0
        val job = scope.launchRefresh({ finished++ }, { failed++ }) { started++ }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(1, finished)
        assertEquals(0, started)
        assertEquals(0, failed)
    }
}

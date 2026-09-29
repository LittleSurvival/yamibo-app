package me.thenano.yamibo.yamibo_app.desktop

import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import me.thenano.yamibo.yamibo_app.store.settings.DesktopSettingsStore

class DesktopJobsTest {
    @Test fun freshProcessStateRecoversOverdueDeadlineWithoutCatchUpBurst() = runBlocking {
        val directory = Files.createTempDirectory("yamibo-desktop-jobs-restart-")
        val path = directory.resolve("settings.properties")
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val firstSettings = DesktopSettingsStore(path)
            firstSettings.putString("desktop.next.check", "1")
            var runs = 0
            val failures = mutableListOf<String>()
            val first = DesktopJobs(firstScope, firstSettings, failures::add)
            first.schedule("check", 60_000) { runs++ }
            assertEquals(1, runs)
            val next = firstSettings.getString("desktop.next.check", "")
            firstScope.coroutineContext[Job]!!.cancelAndJoin()
            assertTrue(first.running.value.isEmpty())

            val secondSettings = DesktopSettingsStore(path)
            val second = DesktopJobs(secondScope, secondSettings, failures::add)
            second.schedule("check", 60_000) { runs++ }
            assertEquals(1, runs)
            assertEquals(next, secondSettings.getString("desktop.next.check", ""))
            assertEquals(setOf("check"), second.running.value)
            assertTrue(failures.isEmpty())
        } finally {
            firstScope.coroutineContext[Job]!!.cancelAndJoin()
            secondScope.coroutineContext[Job]!!.cancelAndJoin()
            Files.deleteIfExists(path)
            Files.delete(directory)
        }
    }

    private fun withJobs(dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
        test: (DesktopJobs, DesktopSettingsStore, CoroutineScope, MutableList<String>) -> Unit) {
        val directory = Files.createTempDirectory("yamibo-desktop-jobs-")
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val failures = mutableListOf<String>()
        try {
            val settings = DesktopSettingsStore(directory.resolve("settings.properties"))
            test(DesktopJobs(scope, settings, failures::add), settings, scope, failures)
        } finally {
            scope.cancel()
            Files.deleteIfExists(directory.resolve("settings.properties"))
            Files.delete(directory)
        }
    }

    @Test fun startingAfterShutdownDoesNotLeaveRunningEntries() = withJobs { jobs, _, scope, failures ->
        scope.cancel()
        jobs.start("late") { error("must not execute") }
        jobs.schedule("late-periodic", 60_000) { error("must not execute") }
        assertTrue(jobs.running.value.isEmpty())
        assertTrue(failures.isEmpty())
    }

    @Test fun shutdownBeforeDispatchRemovesQueuedWork() = runTest {
        withJobs(StandardTestDispatcher(testScheduler)) { jobs, _, scope, failures ->
            jobs.start("queued") { error("must not execute") }
            assertEquals(setOf("queued"), jobs.running.value)
            scope.cancel()
            testScheduler.runCurrent()
            assertTrue(jobs.running.value.isEmpty())
            assertTrue(failures.isEmpty())
        }
    }

    @Test fun duplicateStartIsSuppressedAndProcessCancellationStopsWork() = withJobs { jobs, _, scope, _ ->
        var started = 0
        var stopped = 0
        val action: suspend () -> Unit = { started++; try { awaitCancellation() } finally { stopped++ } }
        jobs.start("download", action)
        jobs.start("download", action)
        assertEquals(1, started)
        assertEquals(setOf("download"), jobs.running.value)
        scope.cancel()
        assertEquals(1, stopped)
        assertTrue(jobs.running.value.isEmpty())
    }

    @Test fun overdueScheduleRunsOnceAndRetainsNextDeadlineAcrossRestart() = withJobs { jobs, settings, _, _ ->
        var runs = 0
        settings.putString("desktop.next.check", "1")
        jobs.schedule("check", 60_000) { runs++ }
        jobs.schedule("check", 60_000) { runs++ }
        assertEquals(1, runs)
        val next = settings.getString("desktop.next.check", "").toLong()
        assertTrue(next > System.currentTimeMillis())
        jobs.cancel("check")
        jobs.schedule("check", 60_000) { runs++ }
        assertEquals(1, runs)
        assertEquals(next.toString(), settings.getString("desktop.next.check", ""))
    }

    @Test fun firstRegistrationPersistsDeadlineAndFailureDoesNotKillScheduler() = withJobs { jobs, settings, _, failures ->
        jobs.schedule("new", 60_000) { error("not due") }
        assertTrue(settings.hasKey("desktop.next.new"))
        settings.putString("desktop.next.retry", "1")
        jobs.schedule("retry", 60_000) { error("network failure") }
        assertEquals(listOf("retry"), failures)
        assertTrue("retry" in jobs.running.value)
        jobs.schedule("retry", null) { error("disabled") }
        assertFalse("retry" in jobs.running.value)
    }
}

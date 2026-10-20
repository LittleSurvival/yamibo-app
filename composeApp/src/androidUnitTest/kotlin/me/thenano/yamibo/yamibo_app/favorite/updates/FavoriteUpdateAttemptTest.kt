package me.thenano.yamibo.yamibo_app.favorite.updates

import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.thenano.yamibo.yamibo_app.repository.FavoriteUpdateRepository
import me.thenano.yamibo.yamibo_app.repository.FavoriteUpdateRepository.*

@OptIn(ExperimentalCoroutinesApi::class)
class FavoriteUpdateAttemptTest {
    @Test
    fun budgetExpiryRetainsCheckpointAndNextAttemptCompletes() = runTest {
        val repository = FakeRepository()
        val first = runFavoriteUpdateAttempt(repository, "run", { testScheduler.currentTime }, {}, 150)
        assertEquals(FavoriteUpdateAttemptResult.RETRY, first)
        assertEquals(RunStatus.RUNNING, repository.snapshot.status)
        assertEquals(1, repository.snapshot.completedCount)

        val second = runFavoriteUpdateAttempt(repository, "run", { testScheduler.currentTime }, {}, 1_000)
        assertEquals(FavoriteUpdateAttemptResult.COMPLETED, second)
        assertEquals(3, repository.snapshot.completedCount)
        assertEquals(listOf(0, 1, 2), repository.checkedItems)
    }

    @Test
    fun osCancellationLeavesRunResumable() = runTest {
        val repository = FakeRepository()
        val job = launch {
            runFavoriteUpdateAttempt(repository, "run", { testScheduler.currentTime }, {})
        }
        advanceTimeBy(150)
        job.cancelAndJoin()
        assertEquals(RunStatus.RUNNING, repository.snapshot.status)
        assertEquals(1, repository.snapshot.completedCount)
        assertEquals(FavoriteUpdateAttemptResult.COMPLETED,
            runFavoriteUpdateAttempt(repository, "run", { testScheduler.currentTime }, {}))
    }

    @Test
    fun userStoppedRunsAreNotRestartedByRetry() = runTest {
        for (status in listOf(RunStatus.CANCELED, RunStatus.INTERRUPTED, RunStatus.FAILED)) {
            val repository = FakeRepository().apply { snapshot = snapshot.copy(status = status) }
            assertEquals(FavoriteUpdateAttemptResult.STOPPED,
                runFavoriteUpdateAttempt(repository, "run", { testScheduler.currentTime }, {}))
            assertEquals(emptyList(), repository.checkedItems)
        }
    }

    @Test
    fun progressFailurePropagatesToCallerInsteadOfUncaughtChildException() = runTest {
        val repository = FakeRepository()
        assertFailsWith<IllegalStateException> {
            runFavoriteUpdateAttempt(repository, "run", { testScheduler.currentTime },
                { error("notification failure") })
        }
        assertEquals(emptyList(), repository.checkedItems)
    }

    @Test
    fun progressCollectorStopsBeforeCompletionIsReported() = runTest {
        val repository = FakeRepository()
        var progressCalls = 0
        assertEquals(FavoriteUpdateAttemptResult.COMPLETED,
            runFavoriteUpdateAttempt(repository, "run", { testScheduler.currentTime }, { progressCalls++ }))
        val before = progressCalls
        repository.state.value = RunState.Running(repository.snapshot.copy(status = RunStatus.RUNNING))
        runCurrent()
        assertEquals(before, progressCalls)
    }

    @Test
    fun alreadyCompletedRunDoesNotExecuteAgain() = runTest {
        val repository = FakeRepository().apply { snapshot = snapshot.copy(status = RunStatus.COMPLETED) }
        assertEquals(FavoriteUpdateAttemptResult.COMPLETED,
            runFavoriteUpdateAttempt(repository, "run", { testScheduler.currentTime }, {}))
        assertEquals(emptyList(), repository.checkedItems)
    }

    private class FakeRepository : FavoriteUpdateRepository by unused() {
        var snapshot = RunSnapshot(
            "run", RunStatus.RUNNING, RunPhase.CHECKING, 0, 0, null,
            3, 0, 0, 0, 0, null, null, null, null,
        )
        override val state = MutableStateFlow<RunState>(RunState.Running(snapshot))
        val checkedItems = mutableListOf<Int>()

        override suspend fun getRunSnapshot(runId: String) = snapshot

        override suspend fun runUpdate(runId: String) {
            while (snapshot.completedCount < 3) {
                delay(100)
                checkedItems += snapshot.completedCount
                snapshot = snapshot.copy(completedCount = snapshot.completedCount + 1)
                state.value = RunState.Running(snapshot)
            }
            snapshot = snapshot.copy(status = RunStatus.COMPLETED)
            state.value = RunState.Completed(snapshot)
        }
    }

    companion object {
        private fun unused(): FavoriteUpdateRepository = Proxy.newProxyInstance(
            FavoriteUpdateRepository::class.java.classLoader,
            arrayOf(FavoriteUpdateRepository::class.java),
        ) { _, method, _ -> error("Unexpected call: ${method.name}") } as FavoriteUpdateRepository
    }
}

package me.thenano.yamibo.yamibo_app.task

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.thenano.yamibo.yamibo_app.feedback.AppFeedbackController
import me.thenano.yamibo.yamibo_app.feedback.AppFeedbackEvent
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertNull
import kotlin.coroutines.CoroutineContext

class AppTaskManagerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val feedback = AppFeedbackController()
    private val manager = AppTaskManager(scope, feedback)

    @AfterTest
    fun tearDown() {
        scope.cancel()
        feedback.close()
    }

    @Test
    fun registersSynchronouslyAndRunsWithoutObserver() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        var calls = 0

        val handle = manager.submit(AppTaskKey("download:1")) {
            calls++
            gate.await()
            AppTaskResult.Success()
        }

        assertEquals(handle.submissionId, manager.state(handle.key)?.submissionId)
        gate.complete(Unit)
        handle.state.first { it is AppTaskState.Succeeded }
        assertEquals(1, calls)
    }

    @Test
    fun keepExistingInvokesMutationOnce() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val key = AppTaskKey("favorite-sync:42")
        val first = manager.submit(key) {
            calls++
            gate.await()
            AppTaskResult.Success()
        }
        val second = manager.submit(key) {
            calls++
            AppTaskResult.Success()
        }

        assertSame(first, second)
        gate.complete(Unit)
        first.state.first { it is AppTaskState.Succeeded }
        assertEquals(1, calls)
    }

    @Test
    fun resultFeedbackIsPostedExactlyOnce() = runBlocking {
        val handle = manager.submit(AppTaskKey("backup:create")) {
            AppTaskResult.Success(AppFeedbackEvent("done"))
        }

        handle.state.first { it is AppTaskState.Succeeded }
        assertEquals("done", feedback.deliveries.first().event.message)
    }

    @Test
    fun explicitCancellationHasDistinctTerminalState() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val handle = manager.submit(AppTaskKey("cancel-me")) {
            gate.await()
            AppTaskResult.Success()
        }
        handle.state.first { it is AppTaskState.Running }

        handle.cancel()

        assertIs<AppTaskState.Canceled>(handle.state.first { it is AppTaskState.Canceled })
        Unit
    }

    @Test
    fun allowParallelRequiresUniqueInstanceAndRunsBoth() = runBlocking {
        var calls = 0
        val first = manager.submit(
            key = AppTaskKey("message-send:7"),
            duplicatePolicy = AppTaskDuplicatePolicy.AllowParallel,
            instanceId = "a",
        ) {
            calls++
            AppTaskResult.Success()
        }
        val second = manager.submit(
            key = AppTaskKey("message-send:7"),
            duplicatePolicy = AppTaskDuplicatePolicy.AllowParallel,
            instanceId = "b",
        ) {
            calls++
            AppTaskResult.Success()
        }

        first.state.first { it is AppTaskState.Succeeded }
        second.state.first { it is AppTaskState.Succeeded }
        assertEquals(2, calls)
    }

    @Test
    fun exceptionIsIsolatedAsFailedState() = runBlocking {
        val handle = manager.submit(AppTaskKey("failing-task")) {
            error("boom")
        }

        val failed = assertIs<AppTaskState.Failed>(handle.state.first { it is AppTaskState.Failed })

        assertEquals("boom", failed.message)
        assertIs<IllegalStateException>(failed.cause)
        Unit
    }

    @Test
    fun replacePendingCancelsQueuedSubmissionWithoutReplacingPublicState() = runBlocking {
        val dispatcher = HoldingDispatcher()
        val queuedScope = CoroutineScope(SupervisorJob() + dispatcher)
        val queuedFeedback = AppFeedbackController()
        val queuedManager = AppTaskManager(queuedScope, queuedFeedback)
        var firstCalls = 0
        var secondCalls = 0
        val key = AppTaskKey("replace-pending")

        val first = queuedManager.submit(key) {
            firstCalls++
            AppTaskResult.Success()
        }
        val second = queuedManager.submit(key, AppTaskDuplicatePolicy.ReplacePending) {
            secondCalls++
            AppTaskResult.Success()
        }
        dispatcher.runAll()

        assertIs<AppTaskState.Canceled>(first.state.value)
        assertIs<AppTaskState.Succeeded>(second.state.value)
        assertSame(second.state.value, queuedManager.state(key))
        assertEquals(0, firstCalls)
        assertEquals(1, secondCalls)
        queuedScope.cancel()
        queuedFeedback.close()
    }

    @Test
    fun registryRetainsOnlyRecentTerminalTasksWhileHandlesKeepState() {
        val dispatcher = HoldingDispatcher()
        val queuedScope = CoroutineScope(SupervisorJob() + dispatcher)
        val queuedFeedback = AppFeedbackController()
        val queuedManager = AppTaskManager(queuedScope, queuedFeedback)

        val handles = (0 until 160).map { index ->
            queuedManager.submit(AppTaskKey("completed-$index")) { AppTaskResult.Success() }
        }
        dispatcher.runAll()

        assertEquals(128, queuedManager.tasks.value.size)
        assertNull(queuedManager.state(AppTaskKey("completed-0")))
        assertIs<AppTaskState.Succeeded>(queuedManager.state(AppTaskKey("completed-159")))
        assertIs<AppTaskState.Succeeded>(handles.first().state.value)
        queuedScope.cancel()
        queuedFeedback.close()
    }

    private class HoldingDispatcher : CoroutineDispatcher() {
        private val blocks = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            blocks.addLast(block)
        }

        fun runAll() {
            while (blocks.isNotEmpty()) {
                blocks.removeFirst().run()
            }
        }
    }
}

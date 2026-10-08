package me.thenano.yamibo.yamibo_app.message

import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import io.github.littlesurvival.core.YamiboResult
import io.github.littlesurvival.dto.page.PrivateMessagePage
import io.github.littlesurvival.dto.value.PrivateMessageId
import io.github.littlesurvival.dto.value.UserId
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import me.thenano.yamibo.yamibo_app.feedback.AppFeedbackController
import me.thenano.yamibo.yamibo_app.message.controller.PrivateMessageController
import me.thenano.yamibo.yamibo_app.message.controller.PrivateMessageSendEffect
import me.thenano.yamibo.yamibo_app.message.controller.PrivateMessageState
import me.thenano.yamibo.yamibo_app.task.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PrivateMessageControllerTest {
    private val original = PrivateMessagePage(UserId(2), "Conversation", PrivateMessageId(3), emptyList())
    private val updated = original.copy(title = "Updated conversation")
    private val taskKey = AppTaskKey("message-send:${original.pmId}")

    private class NoOpApplier : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun onClear() = Unit
    }

    private class Harness(scope: TestScope, content: @Composable () -> Unit) {
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(scope.coroutineContext + clock)
        val runner = scope.launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(NoOpApplier(), recomposer).apply { setContent(content) }
        fun close() { composition.dispose(); recomposer.cancel(); runner.cancel() }
    }

    private fun TestScope.recompose(harness: Harness) {
        repeat(4) {
            Snapshot.sendApplyNotifications()
            runCurrent()
            harness.clock.sendFrame(testScheduler.currentTime * 1_000_000)
            runCurrent()
        }
    }

    @Test
    fun oldStateDependentEffectCancelsItsOwnRefresh() = runTest {
        var state by mutableStateOf<PrivateMessageState>(PrivateMessageState.Success(original))
        val gate = CompletableDeferred<Unit>()
        var canceled = false
        val harness = Harness(this) {
            val key = (state as? PrivateMessageState.Success)?.page?.pmId
            LaunchedEffect(key) {
                if (key != null) {
                    state = PrivateMessageState.Loading
                    try { gate.await() } catch (error: CancellationException) {
                        canceled = true
                        // Mirrors API clients that turn cancellation into a failure value.
                        state = PrivateMessageState.Error(error.message.orEmpty())
                    }
                }
            }
        }
        try {
            recompose(harness)
            assertTrue(canceled)
            assertIs<PrivateMessageState.Error>(state)
        } finally { harness.close() }
    }

    @Test
    fun successfulSendSurvivesRecompositionAndIsConsumedOnce() = runTest {
        val gate = CompletableDeferred<YamiboResult<PrivateMessagePage>>()
        var reads = 0
        var posts = 0
        var renders = 0
        val feedback = AppFeedbackController()
        val tasks = AppTaskManager(backgroundScope, feedback)
        val controller =
            PrivateMessageController({ original }, { reads++; gate.await() }, { _, _ -> fail("Unexpected error") })
        controller.input = "Draft"
        var tick by mutableIntStateOf(0)
        val harness = Harness(this) {
            val allTasks by tasks.tasks.collectAsState()
            controller.state
            controller.refreshing
            tick
            renders++
            PrivateMessageSendEffect(controller, controller.sendTaskKey?.let(allTasks::get))
        }
        try {
            tasks.submit(taskKey) { posts++; AppTaskResult.Success() }
            recompose(harness)
            assertTrue(controller.refreshing)
            assertEquals("", controller.input)
            assertEquals(original, (controller.state as PrivateMessageState.Success).page)
            assertEquals(taskKey, controller.sendTaskKey)
            val before = renders
            tick++
            recompose(harness)
            assertTrue(renders > before)
            gate.complete(YamiboResult.Success(updated))
            recompose(harness)
            assertEquals(updated, (controller.state as PrivateMessageState.Success).page)
            assertFalse(controller.refreshing)
            controller.input = "Next draft"
            tick++
            recompose(harness)
            assertEquals("Next draft", controller.input)
            assertEquals(1, posts)
            assertEquals(1, reads)
        } finally { harness.close(); feedback.close() }
    }

    @Test
    fun refreshFailureKeepsConversationAndRetryDoesNotRepeatSendHandling() = runTest {
        val errors = mutableListOf<Pair<Boolean, String>>()
        var reads = 0
        val controller = PrivateMessageController({ original }, {
            reads++
            if (reads == 1) YamiboResult.Failure("offline") else YamiboResult.Success(updated)
        }, { sent, reason -> errors += sent to reason })
        controller.input = "Draft"
        val success = AppTaskState.Succeeded(taskKey, 1)
        controller.handleSendSuccess(success)
        assertEquals(listOf(true to "offline"), errors)
        assertEquals(original, (controller.state as PrivateMessageState.Success).page)
        controller.input = "Next draft"
        controller.loadPage(preferCache = false)
        controller.handleSendSuccess(success)
        assertEquals(2, reads)
        assertEquals("Next draft", controller.input)
        assertEquals(updated, (controller.state as PrivateMessageState.Success).page)
    }

    @Test
    fun failedSendKeepsDraftAndDoesNotRefresh() = runTest {
        val controller = PrivateMessageController({ original }, { fail("Must not refresh") }, { _, _ -> fail() })
        controller.input = "Unsent draft"
        val harness = Harness(this) {
            PrivateMessageSendEffect(
                controller,
                AppTaskState.Failed(taskKey, 1, "offline", null)
            )
        }
        try { recompose(harness); assertEquals("Unsent draft", controller.input) }
        finally { harness.close() }
    }

    @Test
    fun leavingCompositionDoesNotShowCancellationFailure() = runTest {
        val gate = CompletableDeferred<Unit>()
        var canceled = false
        val controller = PrivateMessageController({ original }, {
            try {
                gate.await(); YamiboResult.Success(updated)
            } catch (e: CancellationException) {
                canceled = true; YamiboResult.Failure("remember scope canceled", e)
            }
        }, { _, _ -> fail("Cancellation must not show feedback") })
        val harness = Harness(this) { PrivateMessageSendEffect(controller, AppTaskState.Succeeded(taskKey, 1)) }
        recompose(harness)
        assertTrue(controller.refreshing)
        harness.close()
        runCurrent()
        assertTrue(canceled)
        assertEquals(original, (controller.state as PrivateMessageState.Success).page)
        assertFalse(controller.refreshing)
    }

    @Test
    fun switchingConversationDisposesOldRefreshWithoutTouchingNewDraft() = runTest {
        val gate = CompletableDeferred<Unit>()
        var canceled = false
        val old = PrivateMessageController({ original }, {
            try {
                gate.await()
            } catch (_: CancellationException) {
                canceled = true
            }
            YamiboResult.Success(updated)
        }, { _, _ -> fail("Old conversation must stay silent") })
        val otherPage = original.copy(toUser = UserId(4), pmId = PrivateMessageId(5))
        val other = PrivateMessageController({ otherPage }, { fail("No send in new conversation") }, { _, _ -> fail() })
        other.input = "New conversation draft"
        var selected by mutableStateOf(old)
        val harness = Harness(this) {
            key(selected) {
                PrivateMessageSendEffect(selected, if (selected === old) AppTaskState.Succeeded(taskKey, 1) else null)
            }
        }
        try {
            recompose(harness)
            assertTrue(old.refreshing)
            selected = other
            recompose(harness)
            assertTrue(canceled)
            assertEquals(original, (old.state as PrivateMessageState.Success).page)
            assertEquals(otherPage, (other.state as PrivateMessageState.Success).page)
            assertEquals("New conversation draft", other.input)
        } finally { harness.close() }
    }

    @Test
    fun newerRequestWinsEvenWhenOldSourceReturnsAfterCancellation() = runTest {
        val gate = CompletableDeferred<Unit>()
        val controller = PrivateMessageController({ original }, { page ->
            if (page == 1) {
                try {
                    gate.await()
                } catch (_: CancellationException) {
                }
                YamiboResult.Success(original.copy(title = "Stale"))
            } else YamiboResult.Success(updated)
        }, { _, _ -> fail("Stale request must not show feedback") })
        val old = launch { controller.loadPage(1, false) }
        runCurrent()
        controller.loadPage(2, false)
        old.join()
        assertEquals(updated, (controller.state as PrivateMessageState.Success).page)
        assertEquals(2, controller.currentPage)
        assertFalse(controller.refreshing)
    }

    @Test
    fun initialFailureUsesErrorScreenAndManualRefreshPreservesContent() = runTest {
        val initial = PrivateMessageController({ null }, { YamiboResult.Failure("offline") }, { _, _ -> fail() })
        initial.loadPage()
        assertEquals(PrivateMessageState.Error("offline"), initial.state)
        val errors = mutableListOf<Boolean>()
        val existing =
            PrivateMessageController({ original }, { YamiboResult.Failure("offline") }, { sent, _ -> errors += sent })
        existing.loadPage(preferCache = false)
        assertEquals(listOf(false), errors)
        assertEquals(original, (existing.state as PrivateMessageState.Success).page)
    }
}

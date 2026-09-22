package me.thenano.yamibo.yamibo_app.thread.reader.components.manga

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.BroadcastFrameClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImagePageAnimationOwnerTest {
    @Test
    fun committedTakeoverPreservesBothPagePositionsInEitherDirection() {
        for (side in listOf(-1, 1)) {
            val oldOffset = -side * 600f
            val newOffset = rebaseImagePageDragOffset(oldOffset, side, 1000)
            assertEquals(oldOffset + side * 1000, newOffset)
            assertEquals(oldOffset, newOffset - side * 1000)
        }
    }

    @Test
    fun takeoverCancelsAnimationWithoutResettingVisibleOffsetOrCommitting() = runBlocking {
        val clock = BroadcastFrameClock()
        val animationScope = CoroutineScope(coroutineContext + clock)
        val owner = ImagePageAnimationOwner()
        var offset = -400f
        var commits = 0
        var cleanups = 0
        owner.launch(animationScope, onFinished = { cleanups++; offset = 0f }) {
            Animatable(offset).animateTo(-1000f, tween(180)) { offset = value }
            commits++
        }
        yield()
        clock.sendFrame(0L)
        yield()
        clock.sendFrame(90_000_000L)
        yield()
        assertTrue(offset < -400f && offset > -1000f)
        val visibleOffset = offset

        owner.cancel()
        yield()
        clock.sendFrame(300_000_000L)
        yield()
        assertEquals(visibleOffset, offset)
        assertEquals(0, commits)
        assertEquals(0, cleanups)
    }

    @Test
    fun oldHandoffCleanupCannotClearNextDrag() = runBlocking {
        val owner = ImagePageAnimationOwner()
        val handoff = CompletableDeferred<Unit>()
        val nextFinish = CompletableDeferred<Unit>()
        var offset = -1000f
        var page = 0
        var oldCleanup = 0
        var newCleanup = 0
        owner.launch(this, onFinished = { oldCleanup++; offset = 0f }) {
            page = 1
            handoff.await()
        }
        yield()
        assertEquals(1, page)
        owner.cancel()
        offset = -200f
        owner.launch(this, onFinished = { newCleanup++; offset = 0f }) {
            nextFinish.await()
            page = 2
        }
        yield()
        handoff.complete(Unit)
        yield()
        assertEquals(-200f, offset)
        assertEquals(0, oldCleanup)
        assertEquals(1, page)
        nextFinish.complete(Unit)
        yield()
        assertEquals(2, page)
        assertEquals(1, newCleanup)
        assertEquals(0f, offset)
    }

    @Test
    fun scopeDisposalCleansUpCurrentAnimationOnce() = runBlocking {
        val parent = Job()
        val owner = ImagePageAnimationOwner()
        var cleanup = 0
        owner.launch(CoroutineScope(coroutineContext + parent), onFinished = { cleanup++ }) {
            awaitCancellation()
        }
        yield()
        parent.cancel()
        parent.join()
        assertEquals(1, cleanup)
    }

    @Test
    fun repeatedNavigationCancellationPreventsStaleCommit() = runBlocking {
        val owner = ImagePageAnimationOwner()
        val finish = CompletableDeferred<Unit>()
        var commits = 0
        var cleanups = 0
        owner.launch(this, onFinished = { cleanups++ }) { finish.await(); commits++ }
        yield()
        owner.cancel()
        owner.cancel()
        finish.complete(Unit)
        yield()
        assertEquals(0, commits)
        assertEquals(0, cleanups)
    }
}

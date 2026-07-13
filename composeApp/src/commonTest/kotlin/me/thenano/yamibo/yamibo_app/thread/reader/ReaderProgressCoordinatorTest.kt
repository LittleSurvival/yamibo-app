package me.thenano.yamibo.yamibo_app.thread.reader

import me.thenano.yamibo.yamibo_app.thread.reader.components.overlay.ReaderProgressGeometry
import me.thenano.yamibo.yamibo_app.thread.reader.components.overlay.ReaderScrollSession
import me.thenano.yamibo.yamibo_app.thread.reader.components.overlay.calculateReaderProgress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReaderProgressCoordinatorTest {
    @Test
    fun longPostUsesTopAndBottomAlignment() {
        val viewportTop = 0
        val viewportBottom = 1000

        assertEquals(
            0,
            calculateReaderProgress(geometry(top = 0, bottom = 3000), viewportTop, viewportBottom)?.progressPercent,
        )
        assertEquals(
            50,
            calculateReaderProgress(geometry(top = -1000, bottom = 2000), viewportTop, viewportBottom)?.progressPercent,
        )
        val complete = calculateReaderProgress(
            geometry(top = -2000, bottom = 1000),
            viewportTop,
            viewportBottom,
        )
        assertEquals(100, complete?.progressPercent)
        assertTrue(complete?.read == true)
    }

    @Test
    fun longPostNeverRoundsToCompleteBeforeBottomAlignment() {
        val result = calculateReaderProgress(
            geometry(top = -1999, bottom = 1001),
            viewportTop = 0,
            viewportBottom = 1000,
        )

        assertEquals(99, result?.progressPercent)
        assertEquals(false, result?.read)
    }

    @Test
    fun shortPostIsReadOnlyWhenFullyVisible() {
        assertNull(calculateReaderProgress(geometry(top = -1, bottom = 499), 0, 1000))
        assertNull(calculateReaderProgress(geometry(top = 501, bottom = 1001), 0, 1000))
        val complete = calculateReaderProgress(geometry(top = 250, bottom = 750), 0, 1000)
        assertEquals(100, complete?.progressPercent)
        assertTrue(complete?.read == true)
    }

    @Test
    fun passedShortPostCanBeCommittedAtIdleBoundary() {
        val result = calculateReaderProgress(
            geometry = geometry(top = -200, bottom = 300),
            viewportTop = 0,
            viewportBottom = 1000,
            allowPassedShortPost = true,
        )

        assertEquals(100, result?.progressPercent)
        assertTrue(result?.read == true)
        assertNull(
            calculateReaderProgress(
                geometry = geometry(top = -600, bottom = -100),
                viewportTop = 0,
                viewportBottom = 1000,
                allowPassedShortPost = true,
            )
        )
    }

    @Test
    fun longPostIsIgnoredBeforeItsTopReachesViewportTop() {
        assertNull(calculateReaderProgress(geometry(top = 1, bottom = 2001), 0, 1000))
    }

    @Test
    fun scrollSessionRetainsHighestCrossedIndex() {
        val session = ReaderScrollSession()
        val generation = session.start(currentIndex = 2)

        assertEquals(generation, session.activeGeneration())

        assertEquals(2..7, session.observe(8))
        assertEquals(generation, session.start(currentIndex = 5))
        assertNull(session.observe(5))

        assertTrue(session.finish(generation, currentIndex = 5)?.isEmpty() == true)
    }

    @Test
    fun staleScrollGenerationCannotFinishNewSession() {
        val session = ReaderScrollSession()
        val firstGeneration = session.start(currentIndex = 2)
        session.cancel(firstGeneration)
        val secondGeneration = session.start(currentIndex = 6)

        assertNull(session.finish(firstGeneration, currentIndex = 9))
        assertEquals(6..8, session.finish(secondGeneration, currentIndex = 9))
    }

    private fun geometry(top: Int, bottom: Int) = ReaderProgressGeometry(
        postId = 1L,
        title = "chapter",
        top = top,
        bottom = bottom,
    )
}

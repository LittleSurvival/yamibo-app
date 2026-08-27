package me.thenano.yamibo.yamibo_app.repository.appsync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import me.thenano.yamibo.yamibo_app.Database
import me.thenano.yamibo.yamibo_app.repository.appsync.cleanup.AppSyncCleanupCoordinator
import me.thenano.yamibo.yamibo_app.repository.appsync.cleanup.AppSyncCleanupDeleteResult
import me.thenano.yamibo.yamibo_app.repository.appsync.cleanup.AppSyncCleanupObservation
import me.thenano.yamibo.yamibo_app.repository.appsync.cleanup.AppSyncCleanupReachability
import me.thenano.yamibo.yamibo_app.repository.appsync.cleanup.AppSyncCleanupReachabilityAnalyzer
import me.thenano.yamibo.yamibo_app.repository.appsync.cleanup.AppSyncSegmentGenerationCandidate
import me.thenano.yamibo.yamibo_app.store.appsync.SqlDelightAppSyncCleanupObservationStore

class AppSyncCleanupCoordinatorTest {
    @Test
    fun reachabilityProtectsEveryAuthoritativeOwnerAndRejectsUnverifiedCandidates() {
        val candidates = (1L..5L).map { id ->
            AppSyncSegmentGenerationCandidate("g$id", id, "fp$id", listOf(id + 100), true)
        } + AppSyncSegmentGenerationCandidate("unverified", 6L, "fp6", listOf(106L), false)
        val orphans = AppSyncCleanupReachabilityAnalyzer.orphanCandidates(
            candidates,
            AppSyncCleanupReachability(
                indexedRootBlogIds = setOf(1L),
                activeRecoveryRootBlogIds = setOf(2L),
                pinnedCheckpointRootBlogIds = setOf(3L),
                retirementRootBlogIds = setOf(4L),
            ),
        )

        assertEquals(listOf("g5"), orphans.map { it.generationId })
    }

    @Test
    fun observationRequiresThreeDailyProofsSpanningSevenDaysAndResetsOnPayloadChange() {
        val store = store()
        val candidate = candidate(segmentCount = 2)
        val day = AppSyncCleanupObservation.MIN_OBSERVATION_INTERVAL_MILLIS
        assertEquals(1, store.observe(ACCOUNT, candidate, "index-a", 0L).observationCount)
        assertEquals(1, store.observe(ACCOUNT, candidate, "index-a", day / 2).observationCount)
        assertEquals(2, store.observe(ACCOUNT, candidate, "index-a", day).observationCount)
        val eligible = store.observe(ACCOUNT, candidate, "index-b", day * 7).also {
            assertTrue(it.isEligible(day * 7))
        }
        val changed = store.observe(
            ACCOUNT,
            candidate.copy(rootFingerprint = "changed"),
            "index-c",
            day * 8,
        )
        assertEquals(1, changed.observationCount)
        assertFalse(changed.isEligible(day * 8))
        assertTrue(eligible.blogIds.first() == candidate.rootBlogId)
    }

    @Test
    fun dryRunDoesNotDeleteAndEnabledCleanupCapsVerifiedDeletesAtTwenty() = runBlocking {
        val store = store()
        val candidate = candidate(segmentCount = 24)
        val day = AppSyncCleanupObservation.MIN_OBSERVATION_INTERVAL_MILLIS
        store.observe(ACCOUNT, candidate, "index", 0L)
        store.observe(ACCOUNT, candidate, "index", day)
        store.observe(ACCOUNT, candidate, "index", day * 7)
        val deleted = mutableListOf<Long>()
        val coordinator = AppSyncCleanupCoordinator(
            store = store,
            deleteBlog = { id -> deleted += id; AppSyncCleanupDeleteResult.Verified },
            nowMillis = { day * 7 },
        )

        val dryRun = coordinator.observeAndClean(
            ACCOUNT, listOf(candidate), AppSyncCleanupReachability(), "index", true, true,
        )
        assertEquals(20, dryRun.dryRunBlogCount)
        assertTrue(deleted.isEmpty())

        val first = coordinator.observeAndClean(
            ACCOUNT, listOf(candidate), AppSyncCleanupReachability(), "index", false, true,
        )
        assertEquals(20, first.deletedBlogCount)
        assertEquals(candidate.deletionOrder.take(20), deleted)
        val second = coordinator.observeAndClean(
            ACCOUNT, listOf(candidate), AppSyncCleanupReachability(), "index", false, true,
        )
        assertEquals(5, second.deletedBlogCount)
        assertTrue(store.observations(ACCOUNT).isEmpty())
    }

    private fun store(): SqlDelightAppSyncCleanupObservationStore {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        return SqlDelightAppSyncCleanupObservationStore(Database(driver))
    }

    private fun candidate(segmentCount: Int) = AppSyncSegmentGenerationCandidate(
        generationId = "generation",
        rootBlogId = 10L,
        rootFingerprint = "root-fingerprint",
        segmentBlogIds = (1..segmentCount).map { 100L + it },
        payloadVerified = true,
    )

    private companion object {
        const val ACCOUNT = "account"
    }
}

package me.thenano.yamibo.yamibo_app.repository.favorite

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.littlesurvival.core.YamiboResult
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import me.thenano.yamibo.yamibo_app.Database
import me.thenano.yamibo.yamibo_app.repository.FavoriteUpdateRepository.RunStatus
import me.thenano.yamibo.yamibo_app.repository.RssSearchSubscriptionRepository

class FavoriteUpdateResumeTest {
    @Test
    fun retryUsesPersistedIdentityAndSkipsAllPreviouslyProcessedOutcomes() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            Database.Schema.create(driver)
            val db = Database(driver)
            repeat(4) { index ->
                db.localFavoriteItemQueries.insertFavoriteItem(
                    "RssSearch", index + 1L, "RSS $index", null, null, null, null, 0,
                    index.toLong(), index.toLong(),
                )
            }
            val visited = mutableListOf<Long>()
            val rss = object : RssSearchSubscriptionRepository by unused() {
                override suspend fun refresh(subscriptionId: Long): YamiboResult<RssSearchSubscriptionRepository.RefreshSummary> {
                    visited += subscriptionId
                    return YamiboResult.Failure("fixture unavailable")
                }
            }
            fun repository() = FavoriteUpdateRepositoryImpl(
                db, FavoriteStoreRepositoryImpl(db), unused(), unused(), rss,
            )
            val runId = repository().startRun("work-request-id")
            val original = db.favoriteUpdateRunQueries.getByRunId(runId).executeAsOne()
            // The first three items have different outcomes. All three must be skipped on resume.
            db.favoriteUpdateRunQueries.upsertRun(
                runId, "RUNNING", "CHECKING", original.startedAt, original.updatedAt, null,
                4, 1, 1, 1, 0, null, null, null, null,
            )
            val restarted = repository()
            restarted.runUpdate(runId)
            val completed = restarted.getRunSnapshot(runId)!!
            assertEquals(listOf(1L), visited)
            assertEquals(RunStatus.COMPLETED, completed.status)
            assertEquals(4, completed.completedCount + completed.skippedCount + completed.failedCount)
            assertEquals("work-request-id", completed.runId)
        } finally {
            driver.close()
        }
    }

    private inline fun <reified T> unused(): T = Proxy.newProxyInstance(
        T::class.java.classLoader, arrayOf(T::class.java),
    ) { _, method, _ -> error("Unexpected call: ${method.name}") } as T
}

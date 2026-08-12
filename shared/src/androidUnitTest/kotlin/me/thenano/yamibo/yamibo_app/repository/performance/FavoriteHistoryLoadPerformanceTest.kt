package me.thenano.yamibo.yamibo_app.repository.performance

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import io.github.littlesurvival.dto.value.ForumId
import me.thenano.yamibo.yamibo_app.Database
import me.thenano.yamibo.yamibo_app.repository.AndroidReadHistoryRepository
import me.thenano.yamibo.yamibo_app.repository.ReadHistoryRepository
import me.thenano.yamibo.yamibo_app.repository.favorite.FavoriteStoreRepositoryImpl

class FavoriteHistoryLoadPerformanceTest {
    @Test
    fun largeSyntheticDatasetKeepsPrimaryQueriesBoundedAndResultsStable() = runBlocking {
        val rawDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val driver = CountingSqlDriver(rawDriver)
        Database.Schema.create(driver)
        val database = Database(driver)
        FavoriteHistoryPerformanceFixture.populate(database)

        assertEquals(
            FavoriteHistoryPerformanceFixture.FAVORITE_CATEGORY_COUNT.toLong(),
            database.localFavoriteCategoryQueries.countAll().executeAsOne(),
        )
        assertEquals(
            FavoriteHistoryPerformanceFixture.HISTORY_ENTRY_COUNT.toLong(),
            database.readingHistoryQueries.countAll().executeAsOne() +
                database.mangaTagReadingHistoryQueries.countAll().executeAsOne() +
                database.tagCatalogReadingHistoryQueries.countAll().executeAsOne() +
                database.rssSearchReadingHistoryQueries.countAll().executeAsOne() +
                database.rssCatalogReadingHistoryQueries.countAll().executeAsOne(),
        )

        val favoriteRepository = FavoriteStoreRepositoryImpl(database)
        driver.reset()
        val legacyFavoriteRuns = measureRepeated {
            database.localFavoriteItemQueries.getByCategoryId(1L).executeAsList()
            database.localFavoriteCollectionQueries.getByCategoryId(1L).executeAsList().forEach {
                database.localFavoriteItemQueries.getByCollectionId(it.id).executeAsList()
            }
        }
        val legacyFavoriteQueries = driver.queryCount / MEASURE_RUNS
        driver.reset()
        val favoriteRuns = measureRepeated {
            val content = favoriteRepository.getCategoryContent(1L)
            assertEquals(50, content.directItems.size)
            assertEquals(2, content.collections.size)
            assertEquals(50, content.collections.sumOf { it.items.size })
            assertEquals(content.directItems.sortedByDescending { it.lastFavoriteStatusUpdateAt }, content.directItems)
        }
        val favoriteQueries = driver.queryCount / MEASURE_RUNS
        assertTrue(favoriteQueries <= 3, "Favorite primary query count was $favoriteQueries")

        val historyRepository = AndroidReadHistoryRepository(database)
        val favoriteItems = favoriteRepository.getCategoryContent(1L).let { content ->
            content.directItems + content.collections.flatMap { it.items }
        }
        driver.reset()
        val lastVisits = historyRepository.getLastVisitTimes(
            favoriteItems.map { item ->
                ReadHistoryRepository.LastVisitLookup(
                    itemId = item.id,
                    targetType = item.targetType,
                    targetId = item.targetId,
                    authorId = item.authorId,
                )
            },
            isMangaMode = false,
        )
        assertEquals(favoriteItems.size, lastVisits.size)
        assertTrue(driver.queryCount <= 3, "Batched last-read query count was ${driver.queryCount}")

        driver.reset()
        val legacyHistoryRuns = measureRepeated {
            database.readingHistoryQueries.getAll().executeAsList()
            database.mangaTagReadingHistoryQueries.getAll().executeAsList()
            database.tagCatalogReadingHistoryQueries.getAll().executeAsList()
            database.rssSearchReadingHistoryQueries.getAll().executeAsList()
            database.rssCatalogReadingHistoryQueries.getAll().executeAsList()
        }
        val legacyHistoryQueries = driver.queryCount / MEASURE_RUNS
        driver.reset()
        val historyRuns = measureRepeated {
            val firstPage = historyRepository.getCombinedHistoryPage(page = 1, pageSize = 20)
            assertEquals(20, firstPage.size)
            assertEquals(firstPage.sortedByDescending { it.lastVisitTime }, firstPage)
        }
        val historyQueries = driver.queryCount / MEASURE_RUNS
        assertTrue(historyQueries <= 5, "History page query count was $historyQueries")
        assertEquals(8_500L, historyRepository.getCombinedHistoryCount())
        val representative = historyRepository.getCombinedHistoryPage(page = 1, pageSize = 8_500)
        assertTrue(representative.any { it is ReadHistoryRepository.ThreadReadingHistory })
        assertTrue(representative.any { it is ReadHistoryRepository.TagMangaReadingHistory })
        assertTrue(representative.any { it is ReadHistoryRepository.TagCatalogReadingHistory })
        assertTrue(representative.any { it is ReadHistoryRepository.RssSearchReadingHistory })
        assertTrue(representative.any { it is ReadHistoryRepository.RssCatalogReadingHistory })
        assertEquals(20, historyRepository.getCombinedHistoryPage(page = 425, pageSize = 20).size)
        assertTrue(historyRepository.getCombinedHistoryPage(page = 426, pageSize = 20).isEmpty())

        val tagFilter = setOf<ReadHistoryRepository.HistoryFilter>(ReadHistoryRepository.HistoryFilter.Tag)
        val rssFilter = setOf<ReadHistoryRepository.HistoryFilter>(ReadHistoryRepository.HistoryFilter.Rss)
        val forumFilter = setOf<ReadHistoryRepository.HistoryFilter>(
            ReadHistoryRepository.HistoryFilter.Forum(ForumId(1)),
        )
        assertEquals(3_000L, historyRepository.getCombinedHistoryCountByFilters(tagFilter))
        assertEquals(1_500L, historyRepository.getCombinedHistoryCountByFilters(rssFilter))
        assertEquals(160L, historyRepository.getCombinedHistoryCountByFilters(forumFilter))
        listOf(tagFilter, rssFilter, forumFilter).forEach { filters ->
            val pageOne = historyRepository.getCombinedHistoryPageByFilters(filters, page = 1, pageSize = 20)
            val pageTwo = historyRepository.getCombinedHistoryPageByFilters(filters, page = 2, pageSize = 20)
            assertEquals(20, pageOne.size)
            assertEquals(20, pageTwo.size)
            assertEquals(pageOne.sortedByDescending { it.lastVisitTime }, pageOne)
            assertTrue(pageOne.map { it.identity() }.toSet().intersect(pageTwo.map { it.identity() }.toSet()).isEmpty())
        }
        val search = historyRepository.searchCombinedHistory("Synthetic", page = 2, pageSize = 20)
        assertEquals(20, search.size)
        assertEquals(8_500L, historyRepository.searchCombinedHistoryCount("Synthetic"))
        assertTrue(historyRepository.searchCombinedHistory("does-not-exist", page = 1, pageSize = 20).isEmpty())
        assertEquals(0L, historyRepository.searchCombinedHistoryCount("does-not-exist"))

        val plan = rawDriver.queryPlan(
            "SELECT LocalFavoriteItemCollectionCrossRef.collectionId, LocalFavoriteItem.id " +
                "FROM LocalFavoriteItem " +
                "INNER JOIN LocalFavoriteItemCollectionCrossRef " +
                "ON LocalFavoriteItem.id = LocalFavoriteItemCollectionCrossRef.itemId " +
                "INNER JOIN LocalFavoriteCollection " +
                "ON LocalFavoriteCollection.id = LocalFavoriteItemCollectionCrossRef.collectionId " +
                "WHERE LocalFavoriteCollection.categoryId = 1"
        )
        assertTrue(plan.isNotEmpty())
        println(
            "FH_BASELINE|favoriteMedian=${legacyFavoriteRuns.median()}|" +
                "favoriteP95=${legacyFavoriteRuns.p95()}|favoriteQueries=$legacyFavoriteQueries|" +
                "historyMedian=${legacyHistoryRuns.median()}|historyP95=${legacyHistoryRuns.p95()}|" +
                "historyQueries=$legacyHistoryQueries|historyRows=${FavoriteHistoryPerformanceFixture.HISTORY_ENTRY_COUNT}",
        )
        println(
            "FH_FINAL|favoriteMedian=${favoriteRuns.median()}|favoriteP95=${favoriteRuns.p95()}|" +
                "favoriteQueries=$favoriteQueries|historyMedian=${historyRuns.median()}|" +
                "historyP95=${historyRuns.p95()}|historyQueries=$historyQueries|historyRows=20|" +
                "plan=${plan.joinToString(" ; ")}",
        )
    }
}

private fun ReadHistoryRepository.AnyReadingHistory.identity(): String = when (this) {
    is ReadHistoryRepository.ThreadReadingHistory -> "thread:${threadId.value}:$threadType:${authorId?.value}:$historyOrigin"
    is ReadHistoryRepository.ImageReadingHistory -> "image:${postId.value}"
    is ReadHistoryRepository.TagMangaReadingHistory -> "tag-manga:${tagId.value}"
    is ReadHistoryRepository.TagCatalogReadingHistory -> "tag-catalog:${tagId.value}"
    is ReadHistoryRepository.RssSearchReadingHistory -> "rss-search:$subscriptionId"
    is ReadHistoryRepository.RssCatalogReadingHistory -> "rss-catalog:$subscriptionId"
}

private const val MEASURE_RUNS = 5

private inline fun measureRepeated(block: () -> Unit): List<Long> =
    List(MEASURE_RUNS) { measureTimeMillis(block) }

private fun List<Long>.median(): Long = sorted()[size / 2]

private fun List<Long>.p95(): Long = sorted()[((size - 1) * 0.95).toInt()]

private class CountingSqlDriver(
    private val delegate: SqlDriver,
) : SqlDriver by delegate {
    var queryCount: Int = 0
        private set
    var mutationCount: Int = 0
        private set

    fun reset() {
        queryCount = 0
        mutationCount = 0
    }

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> {
        queryCount += 1
        return delegate.executeQuery(identifier, sql, mapper, parameters, binders)
    }

    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<Long> {
        mutationCount += 1
        return delegate.execute(identifier, sql, parameters, binders)
    }
}

private fun SqlDriver.queryPlan(sql: String): List<String> {
    return executeQuery(
        identifier = null,
        sql = "EXPLAIN QUERY PLAN $sql",
        mapper = { cursor ->
            QueryResult.Value(
                buildList {
                    while (cursor.next().value) {
                        add(cursor.getString(3).orEmpty())
                    }
                }
            )
        },
        parameters = 0,
    ).value
}

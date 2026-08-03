package me.thenano.yamibo.yamibo_app.repository.favorite

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking
import me.thenano.yamibo.yamibo_app.Database

class LocalFavoriteRepositoryImplRssTest {
    @Test
    fun createdContainersReceiveStableIdsIndependentOfLocalRows() = runBlocking {
        val db = inMemoryDatabase()
        val repository = FavoriteStoreRepositoryImpl(db)

        val first = repository.createCategory("A")
        val second = repository.createCategory("B")
        val collection = repository.createCollection(first.id, "C", "brown")
        val firstSyncId = db.localFavoriteCategoryQueries.getById(first.id)
            .executeAsOne()
            .syncId
        val secondSyncId = db.localFavoriteCategoryQueries.getById(second.id)
            .executeAsOne()
            .syncId
        val collectionSyncId = db.localFavoriteCollectionQueries.getById(collection.id)
            .executeAsOne()
            .syncId

        assertNotNull(firstSyncId)
        assertNotNull(secondSyncId)
        assertNotNull(collectionSyncId)
        assertNotEquals(first.id.toString(), firstSyncId)
        assertNotEquals(firstSyncId, secondSyncId)
    }

    @Test
    fun deletingRssFavoriteDeletesSubscriptionAndResults() = runBlocking {
        val db = inMemoryDatabase()
        val repository = FavoriteStoreRepositoryImpl(db)
        val now = 1_000L

        db.rssSearchSubscriptionQueries.insertSubscription(
            title = "App RSS",
            query = "app",
            forumId = null,
            forumName = null,
            enabled = 1,
            createdAt = now,
            updatedAt = now,
            lastRefreshStartedAt = null,
            lastRefreshFinishedAt = null,
            lastRefreshStatus = null,
            lastRefreshMessage = null,
            lastSearchId = null,
            lastTotalCount = 1,
        )
        val subscriptionId = db.rssSearchSubscriptionQueries.lastInsertedId().executeAsOne()
        db.rssSearchSubscriptionResultQueries.insertResultIgnore(
            subscriptionId = subscriptionId,
            threadId = 123,
            title = "Thread",
            forumId = null,
            attachmentType = null,
            hasPoll = 0,
            url = "https://bbs.yamibo.com/forum.php?mod=viewthread&tid=123",
            authorId = null,
            authorName = null,
            authorAvatarUrl = null,
            description = null,
            replyCount = null,
            viewCount = null,
            tag = null,
            lastUpdateText = null,
            lastUpdateSpecialText = null,
            lastUpdateEpoch = null,
            firstSeenAt = now,
            lastSeenAt = now,
            readAt = null,
            pageIndex = 1,
            positionInPage = 0,
        )
        repository.addRssSearchFavorite(
            subscriptionId = subscriptionId,
            title = "App RSS",
            coverUrl = null,
        )
        val item = repository.getAllFavoriteItems().single()

        repository.deleteFavoriteItems(setOf(item.id))

        assertNull(db.rssSearchSubscriptionQueries.getById(subscriptionId).executeAsOneOrNull())
        kotlin.test.assertEquals(0, db.rssSearchSubscriptionResultQueries.countBySubscription(subscriptionId).executeAsOne())
    }

    private fun inMemoryDatabase(): Database {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        return Database(driver)
    }
}

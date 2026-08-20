package me.thenano.yamibo.yamibo_app.repository.favorite

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.littlesurvival.dto.value.ThreadId
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import me.thenano.yamibo.yamibo_app.Database
import me.thenano.yamibo.yamibo_app.repository.RssSearchSubscriptionRepository
import me.thenano.yamibo.yamibo_app.repository.TagRepository
import me.thenano.yamibo.yamibo_app.repository.ThreadRepository

class FavoriteUpdateCategoryScopeTest {
    @Test
    fun collectionItemBelongsToItsParentCategoryForUpdateScope() = runBlocking {
        val db = inMemoryDatabase()
        val favorites = FavoriteStoreRepositoryImpl(db)
        val category = favorites.createCategory("Completed")
        val collection = favorites.createCollection(category.id, "Read", "brown")
        favorites.addNormalThreadFavorite(
            tid = ThreadId(29),
            title = "Nested favorite",
            coverUrl = null,
            lastUpdatedTime = null,
            forumId = null,
            forumName = null,
            collectionIds = listOf(collection.id),
        )

        val item = favorites.getAllFavoriteItems().single()
        assertTrue(favorites.getCategoryIdsForItem(item.id).isEmpty())
        assertEquals(setOf(category.id), favorites.getContainingCategoryIdsForItem(item.id))

        val updates = FavoriteUpdateRepositoryImpl(
            db = db,
            localFavoriteRepository = favorites,
            threadRepository = unused(),
            tagRepository = unused(),
            rssSearchSubscriptionRepository = unused(),
        )

        val filter = updates.getCategoryFilters().single { it.categoryId == category.id }
        assertEquals(1, filter.itemCount)
        assertEquals(setOf(category.id), updates.getScopeTargets().single().categoryIds)
    }

    private fun inMemoryDatabase(): Database {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        return Database(driver)
    }

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> unused(): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            throw UnsupportedOperationException(method.name)
        } as T
}

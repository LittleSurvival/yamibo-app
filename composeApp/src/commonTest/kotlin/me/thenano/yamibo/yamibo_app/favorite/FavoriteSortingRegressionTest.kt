package me.thenano.yamibo.yamibo_app.favorite

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import me.thenano.yamibo.yamibo_app.repository.FavoriteStoreRepository.FavoriteCollection
import me.thenano.yamibo.yamibo_app.repository.FavoriteStoreRepository.FavoriteCollectionWithItems
import me.thenano.yamibo.yamibo_app.repository.FavoriteStoreRepository.FavoriteItem
import me.thenano.yamibo.yamibo_app.repository.FavoriteStoreRepository.FavoriteTargetType
import me.thenano.yamibo.yamibo_app.repository.settings.FavoriteSortMode

class FavoriteSortingRegressionTest {
    private val items = FavoriteTargetType.entries.mapIndexed { index, type ->
        FavoriteItem(
            id = index + 1L,
            targetType = type,
            targetId = 100L + index,
            title = listOf("Delta", "alpha", "Charlie", "bravo")[index],
            coverUrl = null,
            lastUpdatedTime = listOf(40L, null, 20L, 10L)[index],
            forumId = null,
            forumName = listOf("Zeta", "Alpha", "Beta", null)[index],
            authorId = null,
            createdAt = 10L + index,
            lastFavoriteStatusUpdateAt = 30L + index,
        )
    }
    private val lastRead = mapOf(1L to 1L, 2L to 4L, 3L to 3L, 4L to 2L)
    private val remoteOrder = mapOf(1L to 30L, 3L to 10L, 4L to 20L)

    @Test
    fun everySortModePreservesStableIdentityForEveryTargetType() {
        FavoriteSortMode.entries.forEach { mode ->
            listOf(false, true).forEach { descending ->
                val first = sortItems(items, mode, descending, lastRead, remoteOrder)
                val second = sortItems(items, mode, descending, lastRead, remoteOrder)
                assertEquals(items.map { it.id }.toSet(), first.map { it.id }.toSet(), "$mode/$descending")
                assertEquals(first.map { it.id }, second.map { it.id }, "$mode/$descending")
            }
        }
        assertEquals(setOf(1L, 2L, 3L, 4L), sortItems(items, FavoriteSortMode.LAST_READ, true, lastRead, remoteOrder).map { it.id }.toSet())
        assertNotEquals(
            sortItems(items, FavoriteSortMode.DEFAULT, true, lastRead, remoteOrder).map { it.id },
            sortItems(items, FavoriteSortMode.LAST_READ, true, lastRead, remoteOrder).map { it.id },
        )
        assertNotEquals(
            sortItems(items, FavoriteSortMode.DEFAULT, true, lastRead, remoteOrder).map { it.id },
            sortItems(items, FavoriteSortMode.FAVORITED_ORDER, true, lastRead, remoteOrder).map { it.id },
        )
    }

    @Test
    fun nestedCollectionsRemainStableAcrossEverySortMode() {
        val collections = listOf(
            FavoriteCollectionWithItems(collection(1L, "Beta", 1L), items.take(2)),
            FavoriteCollectionWithItems(collection(2L, "Alpha", 0L), items.drop(2)),
        )
        FavoriteSortMode.entries.forEach { mode ->
            listOf(false, true).forEach { descending ->
                val sorted = sortCollections(collections, mode, descending, lastRead, remoteOrder)
                assertEquals(setOf(1L, 2L), sorted.map { it.collection.id }.toSet(), "$mode/$descending")
                assertEquals(
                    sorted.map { it.collection.id },
                    sortCollections(collections, mode, descending, lastRead, remoteOrder).map { it.collection.id },
                    "$mode/$descending",
                )
            }
        }
    }

    private fun collection(id: Long, name: String, order: Long) = FavoriteCollection(
        id = id,
        categoryId = 1L,
        name = name,
        colorKey = "brown",
        sortOrder = order,
        createdAt = id,
        updatedAt = id,
    )
}

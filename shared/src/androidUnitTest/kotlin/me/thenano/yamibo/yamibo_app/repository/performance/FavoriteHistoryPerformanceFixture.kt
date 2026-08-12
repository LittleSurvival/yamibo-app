package me.thenano.yamibo.yamibo_app.repository.performance

import me.thenano.yamibo.yamibo_app.Database

internal object FavoriteHistoryPerformanceFixture {
    const val FAVORITE_ITEM_COUNT = 5_000
    const val FAVORITE_CATEGORY_COUNT = 50
    const val COLLECTIONS_PER_CATEGORY = 2
    const val HISTORY_ENTRY_COUNT = 10_000

    fun populate(database: Database) {
        populateFavorites(database)
        populateHistory(database)
    }

    private fun populateFavorites(database: Database) = database.transaction {
        val categoryQueries = database.localFavoriteCategoryQueries
        val collectionQueries = database.localFavoriteCollectionQueries
        val itemQueries = database.localFavoriteItemQueries
        val categoryCrossRefs = database.localFavoriteItemCategoryCrossRefQueries
        val collectionCrossRefs = database.localFavoriteItemCollectionCrossRefQueries
        val baseTime = 1_800_000_000_000L

        repeat(FAVORITE_CATEGORY_COUNT) { categoryIndex ->
            categoryQueries.insertCategory(
                name = "Synthetic category ${categoryIndex.toString().padStart(2, '0')}",
                sortOrder = categoryIndex.toLong(),
                createdAt = baseTime + categoryIndex,
                updatedAt = baseTime + categoryIndex,
            )
            repeat(COLLECTIONS_PER_CATEGORY) { collectionIndex ->
                collectionQueries.insertCollection(
                    categoryId = categoryIndex + 1L,
                    name = "Collection $categoryIndex-$collectionIndex",
                    colorKey = if (collectionIndex == 0) "brown" else "pink",
                    sortOrder = collectionIndex.toLong(),
                    createdAt = baseTime + categoryIndex * 10L + collectionIndex,
                    updatedAt = baseTime + categoryIndex * 10L + collectionIndex,
                )
            }
        }

        repeat(FAVORITE_ITEM_COUNT) { itemIndex ->
            val itemId = itemIndex + 1L
            val categoryId = itemIndex % FAVORITE_CATEGORY_COUNT + 1L
            val type = when (itemIndex % 4) {
                0 -> "ThreadNormal"
                1 -> "ThreadNovel"
                2 -> "TagManga"
                else -> "RssSearch"
            }
            itemQueries.insertFavoriteItem(
                targetType = type,
                targetId = 100_000L + itemIndex,
                title = "Synthetic favorite ${itemIndex.toString().padStart(5, '0')}",
                coverUrl = if (itemIndex % 3 == 0) "https://example.test/$itemIndex.jpg" else null,
                lastUpdatedTime = baseTime - itemIndex,
                forumId = (itemIndex % 25 + 1).toLong(),
                forumName = "Forum ${itemIndex % 25}",
                authorId = if (type == "ThreadNovel") itemIndex + 10L else 0L,
                createdAt = baseTime - itemIndex * 2L,
                lastFavoriteStatusUpdateAt = baseTime - itemIndex,
            )
            val categoryItemIndex = itemIndex / FAVORITE_CATEGORY_COUNT
            if (categoryItemIndex % 2 == 0) {
                categoryCrossRefs.insertCrossRef(itemId, categoryId, baseTime)
            } else {
                val collectionId = (categoryId - 1L) * COLLECTIONS_PER_CATEGORY +
                    (categoryItemIndex % COLLECTIONS_PER_CATEGORY) + 1L
                collectionCrossRefs.insertCrossRef(itemId, collectionId, baseTime)
            }
        }
    }

    private fun populateHistory(database: Database) = database.transaction {
        val baseTime = 1_800_000_000_000L
        repeat(4_000) { index ->
            val id = 200_000L + index
            database.readingHistoryQueries.upsert(
                threadId = id,
                threadType = if (index % 3 == 0) "Novel" else "Normal",
                threadName = "Synthetic thread ${index.toString().padStart(5, '0')}",
                threadCover = null,
                forumName = "Forum ${index % 25}",
                forumId = (index % 25 + 1).toLong(),
                authorId = if (index % 3 == 0) index + 1L else 0L,
                page = index % 20 + 1L,
                postId = id + 1L,
                postTitle = "Post $index",
                anchorPostId = id + 1L,
                anchorPostRatio = (index % 100) / 100.0,
                anchorBlockId = "block-$index",
                anchorBlockType = "Text",
                anchorBlockRatio = null,
                globalScrollY = null,
                viewportHeight = 1_200L,
                firstVisibleItemIndex = (index % 20).toLong(),
                firstVisibleItemOffset = (index % 200).toLong(),
                historyOrigin = "Direct",
                lastVisitTime = baseTime - index * 4L,
                lastUpdatedTime = baseTime - index,
            )
        }
        repeat(2_000) { index ->
            val tagId = 300_000L + index
            database.mangaTagReadingHistoryQueries.upsert(
                tagId = tagId,
                tagName = "Synthetic tag $index",
                tagPage = index % 20 + 1L,
                threadId = 400_000L + index,
                threadTitle = "Manga thread $index",
                threadImagePageIndex = (index % 10).toLong(),
                threadImageTotalPages = 10L,
                firstVisibleItemIndex = null,
                firstVisibleItemOffset = null,
                lastVisitTime = baseTime - index * 4L - 1L,
                coverUrl = null,
            )
        }
        repeat(2_000) { index ->
            val tagId = if (index < 1_000) 300_000L + index else 302_000L + index
            database.tagCatalogReadingHistoryQueries.upsert(
                tagId = tagId,
                tagName = "Synthetic tag ${index / 2}",
                tagPage = index % 20 + 1L,
                threadId = 500_000L + index,
                threadTitle = "Catalog thread $index",
                threadPage = index % 20 + 1L,
                postId = 600_000L + index,
                postTitle = "Catalog post $index",
                authorId = null,
                anchorPostId = 600_000L + index,
                anchorPostRatio = null,
                anchorBlockId = null,
                anchorBlockType = null,
                anchorBlockRatio = null,
                viewportHeight = 1_200L,
                firstVisibleItemIndex = null,
                firstVisibleItemOffset = null,
                lastVisitTime = baseTime - index * 4L - 2L,
                coverUrl = null,
            )
        }
        repeat(1_000) { index ->
            val subscriptionId = 700_000L + index
            database.rssSearchReadingHistoryQueries.upsert(
                subscriptionId = subscriptionId,
                subscriptionTitle = "Synthetic RSS $index",
                subscriptionQuery = "query-$index",
                subscriptionPage = index % 20 + 1L,
                threadId = 800_000L + index,
                threadTitle = "RSS image thread $index",
                threadImagePageIndex = (index % 10).toLong(),
                threadImageTotalPages = 10L,
                firstVisibleItemIndex = null,
                firstVisibleItemOffset = null,
                lastVisitTime = baseTime - index * 4L - 3L,
                coverUrl = null,
            )
        }
        repeat(1_000) { index ->
            val subscriptionId = if (index < 500) 700_000L + index else 702_000L + index
            database.rssCatalogReadingHistoryQueries.upsert(
                subscriptionId = subscriptionId,
                subscriptionTitle = "Synthetic RSS $index",
                subscriptionQuery = "query-$index",
                subscriptionPage = index % 20 + 1L,
                threadId = 900_000L + index,
                threadTitle = "RSS catalog thread $index",
                threadPage = index % 20 + 1L,
                postId = 1_000_000L + index,
                postTitle = "RSS post $index",
                authorId = null,
                anchorPostId = 1_000_000L + index,
                anchorPostRatio = null,
                anchorBlockId = null,
                anchorBlockType = null,
                anchorBlockRatio = null,
                viewportHeight = 1_200L,
                firstVisibleItemIndex = null,
                firstVisibleItemOffset = null,
                lastVisitTime = baseTime - index * 4L - 4L,
                coverUrl = null,
            )
        }
    }
}

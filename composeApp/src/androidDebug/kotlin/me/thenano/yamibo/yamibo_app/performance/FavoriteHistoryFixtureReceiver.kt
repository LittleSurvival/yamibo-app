package me.thenano.yamibo.yamibo_app.performance

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.thenano.yamibo.yamibo_app.Database
import me.thenano.yamibo.yamibo_app.db.DatabaseFactory

class FavoriteHistoryFixtureReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        if (!isEmulator()) {
            Log.e(TAG, "refused|reason=fixture_receiver_is_emulator_only")
            return
        }
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { populate(context.applicationContext) }
                .onSuccess { Log.i(TAG, "complete|favorites=$FAVORITES|history=$HISTORY") }
                .onFailure { Log.e(TAG, "failed", it) }
            pending.finish()
        }
    }

    private fun populate(context: Context) {
        val db = Database(DatabaseFactory(context).createDriver())
        val base = 1_800_000_000_000L
        db.transaction {
            db.localFavoriteItemCollectionCrossRefQueries.deleteAll()
            db.localFavoriteItemCategoryCrossRefQueries.deleteAll()
            db.localFavoriteItemQueries.deleteAll()
            db.localFavoriteCollectionQueries.deleteAll()
            db.localFavoriteCategoryQueries.deleteAll()
            db.readingHistoryQueries.deleteAll()
            db.mangaTagReadingHistoryQueries.deleteAll()
            db.tagCatalogReadingHistoryQueries.deleteAll()
            db.rssSearchReadingHistoryQueries.deleteAll()
            db.rssCatalogReadingHistoryQueries.deleteAll()

            val categories = List(CATEGORIES) { index ->
                val name = "Perf category ${index.toString().padStart(2, '0')}"
                db.localFavoriteCategoryQueries.insertCategory(name, index.toLong(), base + index, base + index)
                db.localFavoriteCategoryQueries.getFirstByName(name).executeAsOne()
            }
            val collections = categories.mapIndexed { index, category ->
                db.localFavoriteCollectionQueries.insertCollection(
                    category.id, "Perf collection $index", "brown", 0L, base + index, base + index,
                )
                db.localFavoriteCollectionQueries.getLatestByCategoryId(category.id).executeAsOne()
            }
            repeat(FAVORITES) { index ->
                val type = TARGET_TYPES[index % TARGET_TYPES.size]
                val targetId = 100_000L + index
                val authorId = if (type == "ThreadNovel") index + 1L else 0L
                db.localFavoriteItemQueries.insertFavoriteItem(
                    type, targetId, "Perf favorite $index", null, base - index,
                    (index % 25 + 1).toLong(), "Forum ${index % 25}", authorId,
                    base - index, base - index,
                )
                val item = db.localFavoriteItemQueries.findByTarget(type, targetId, authorId).executeAsOne()
                val categoryIndex = index % CATEGORIES
                if ((index / CATEGORIES) % 2 == 0) {
                    db.localFavoriteItemCategoryCrossRefQueries.insertCrossRef(
                        item.id, categories[categoryIndex].id, base,
                    )
                } else {
                    db.localFavoriteItemCollectionCrossRefQueries.insertCrossRef(
                        item.id, collections[categoryIndex].id, base,
                    )
                }
            }
            repeat(4_000) { index ->
                val id = 200_000L + index
                db.readingHistoryQueries.upsert(
                    id, if (index % 3 == 0) "Novel" else "Normal", "Perf thread $index", null,
                    "Forum ${index % 25}", (index % 25 + 1).toLong(), if (index % 3 == 0) index + 1L else 0L,
                    1L, id + 1L, "Post $index", id + 1L, null, null, null, null, null,
                    1_200L, null, null, "Direct", base - index * 4L, base - index,
                )
            }
            repeat(2_000) { index ->
                val tagId = 300_000L + index
                db.mangaTagReadingHistoryQueries.upsert(
                    tagId, "Perf tag $index", 1L, 400_000L + index, "Manga $index", 0L, 10L,
                    null, null, base - index * 4L - 1L, null,
                )
                val catalogTagId = if (index < 1_000) tagId else 302_000L + index
                db.tagCatalogReadingHistoryQueries.upsert(
                    catalogTagId, "Perf tag $index", 1L, 500_000L + index, "Catalog $index", 1L,
                    600_000L + index, "Post $index", null, 600_000L + index, null, null, null,
                    null, 1_200L, null, null, base - index * 4L - 2L, null,
                )
            }
            repeat(1_000) { index ->
                val rssId = 700_000L + index
                db.rssSearchReadingHistoryQueries.upsert(
                    rssId, "Perf RSS $index", "query-$index", 1L, 800_000L + index,
                    "RSS image $index", 0L, 10L, null, null, base - index * 4L - 3L, null,
                )
                val catalogRssId = if (index < 500) rssId else 702_000L + index
                db.rssCatalogReadingHistoryQueries.upsert(
                    catalogRssId, "Perf RSS $index", "query-$index", 1L, 900_000L + index,
                    "RSS catalog $index", 1L, 1_000_000L + index, "Post $index", null,
                    1_000_000L + index, null, null, null, null, 1_200L, null, null,
                    base - index * 4L - 4L, null,
                )
            }
        }
    }

    private companion object {
        fun isEmulator(): Boolean = Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.contains("emulator") ||
            Build.MODEL.contains("Emulator") ||
            Build.MODEL.contains("Android SDK built for")

        const val ACTION = "me.thenano.yamibo.yamibo_app.debug.POPULATE_FAVORITE_HISTORY_PERF"
        const val TAG = "FH_FIXTURE"
        const val FAVORITES = 5_000
        const val HISTORY = 10_000
        const val CATEGORIES = 50
        val TARGET_TYPES = listOf("ThreadNormal", "ThreadNovel", "TagManga", "RssSearch")
    }
}

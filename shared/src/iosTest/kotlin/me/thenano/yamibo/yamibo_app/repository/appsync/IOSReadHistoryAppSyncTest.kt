package me.thenano.yamibo.yamibo_app.repository.appsync

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import io.github.littlesurvival.dto.value.PostId
import io.github.littlesurvival.dto.value.TagId
import io.github.littlesurvival.dto.value.ThreadId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import me.thenano.yamibo.yamibo_app.Database
import me.thenano.yamibo.yamibo_app.repository.IOSReadHistoryRepository
import me.thenano.yamibo.yamibo_app.repository.ReadHistoryRepository
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.DatabaseSyncDomainMaterializer
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.SqlDelightSyncDomainStateAdapter
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncInstallationState
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncAccountBinding
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperationKind
import me.thenano.yamibo.yamibo_app.store.appsync.SqlDelightAppSyncOperationStore
import me.thenano.yamibo.yamibo_app.store.settings.SettingsStore

class IOSReadHistoryAppSyncTest {
    @Test
    fun clearAllRecordsEveryHistoryTombstoneAndPreservesReadingTime() = runBlocking {
        val driver = NativeSqliteDriver(Database.Schema, ":memory:")
        try {
            val db = Database(driver)
            val store = SqlDelightAppSyncOperationStore(db).also {
                it.initialize("ios-test")
                it.bindAccount(SyncAccountBinding("account"), AppSyncInstallationState.Active)
            }
            val delegate = IOSReadHistoryRepository(db)
            val repository = OperationRecordingReadHistoryRepository(
                delegate,
                AppSyncMutationRecorder(
                    enabled = true,
                    store = store,
                    domainState = SqlDelightSyncDomainStateAdapter(
                        db,
                        DatabaseSyncDomainMaterializer(db, MemorySettingsStore()),
                        nowMillis = { 100 },
                    ),
                    nowMillis = { 100 },
                ),
            )
            val rssId = insertRssSubscription(db)
            val histories = histories(rssId)

            repository.savePosition(histories.thread)
            repository.saveImagePosition(histories.image)
            repository.saveTagMangaReaderModeHistory(histories.manga)
            repository.saveTagCatalogThreadHistory(histories.catalog)
            repository.saveRssSearchReaderModeHistory(histories.rssSearch)
            repository.saveRssCatalogThreadHistory(histories.rssCatalog)
            repository.recordReadingDuration("2026-08-01", 123)
            store.markAcknowledged(
                store.pendingOperations().mapTo(linkedSetOf()) { it.operationId },
                100,
            )

            repository.deleteAllCombinedHistory()

            val deletes = store.pendingOperations()
            assertEquals(6, deletes.size)
            assertTrue(deletes.all { it.kind == SyncOperationKind.Delete })
            assertEquals(
                setOf(
                    "reading.thread",
                    "reading.image",
                    "reading.tag-manga",
                    "reading.tag-catalog",
                    "reading.rss-search",
                    "reading.rss-catalog",
                ),
                deletes.mapTo(linkedSetOf()) { it.domainId.value },
            )
            assertEquals(0, delegate.getHistoryCount())
            assertTrue(delegate.getAllImageHistoryForSync().isEmpty())
            assertTrue(delegate.getAllTagMangaHistoryForSync().isEmpty())
            assertTrue(delegate.getAllTagCatalogHistoryForSync().isEmpty())
            assertTrue(delegate.getAllRssSearchHistoryForSync().isEmpty())
            assertTrue(delegate.getAllRssCatalogHistoryForSync().isEmpty())
            assertEquals(123, delegate.getReadingDurationTotal("2026-08-01", "2026-08-01"))
        } finally {
            driver.close()
        }
    }

    private fun insertRssSubscription(db: Database): Long {
        db.rssSearchSubscriptionQueries.insertSubscription(
            title = "RSS",
            query = "query",
            forumId = null,
            forumName = null,
            enabled = 1,
            createdAt = 1,
            updatedAt = 1,
            lastRefreshStartedAt = null,
            lastRefreshFinishedAt = null,
            lastRefreshStatus = null,
            lastRefreshMessage = null,
            lastSearchId = null,
            lastTotalCount = 0,
        )
        return db.rssSearchSubscriptionQueries.lastInsertedId().executeAsOne()
    }

    private fun histories(rssId: Long) = Histories(
        thread = ReadHistoryRepository.ThreadReadingHistory(
            threadType = ReadHistoryRepository.ThreadEntryType.Normal,
            threadName = "thread",
            threadId = ThreadId(1),
            threadCover = null,
            lastUpdatedTime = null,
            forumName = null,
            forumId = null,
            authorId = null,
            page = 1,
            postId = PostId(1),
            postTitle = "post",
            anchorPostId = 1,
            lastVisitTime = 1,
        ),
        image = ReadHistoryRepository.ImageReadingHistory(
            PostId(2), ThreadId(1), 1, 10, lastVisitTime = 2,
        ),
        manga = ReadHistoryRepository.TagMangaReadingHistory(
            TagId(3), "manga", 1, ThreadId(3), "thread", 1, 10, lastVisitTime = 3,
        ),
        catalog = ReadHistoryRepository.TagCatalogReadingHistory(
            TagId(4), "catalog", 1, ThreadId(4), "thread", 1,
            PostId(4), "post", lastVisitTime = 4,
        ),
        rssSearch = ReadHistoryRepository.RssSearchReadingHistory(
            rssId, "RSS", "query", 1, ThreadId(5), "thread", 1, 10, lastVisitTime = 5,
        ),
        rssCatalog = ReadHistoryRepository.RssCatalogReadingHistory(
            rssId, "RSS", "query", 1, ThreadId(6), "thread", 1,
            PostId(6), "post", lastVisitTime = 6,
        ),
    )

    private data class Histories(
        val thread: ReadHistoryRepository.ThreadReadingHistory,
        val image: ReadHistoryRepository.ImageReadingHistory,
        val manga: ReadHistoryRepository.TagMangaReadingHistory,
        val catalog: ReadHistoryRepository.TagCatalogReadingHistory,
        val rssSearch: ReadHistoryRepository.RssSearchReadingHistory,
        val rssCatalog: ReadHistoryRepository.RssCatalogReadingHistory,
    )

    private class MemorySettingsStore : SettingsStore {
        override fun getInt(key: String, defaultValue: Int) = defaultValue
        override fun putInt(key: String, value: Int) = Unit
        override fun getFloat(key: String, defaultValue: Float) = defaultValue
        override fun putFloat(key: String, value: Float) = Unit
        override fun getString(key: String, defaultValue: String) = defaultValue
        override fun putString(key: String, value: String) = Unit
        override fun getBoolean(key: String, defaultValue: Boolean) = defaultValue
        override fun putBoolean(key: String, value: Boolean) = Unit
        override fun remove(key: String) = Unit
        override fun hasKey(key: String) = false
    }
}

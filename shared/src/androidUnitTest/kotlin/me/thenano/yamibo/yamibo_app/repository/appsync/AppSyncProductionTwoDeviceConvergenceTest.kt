package me.thenano.yamibo.yamibo_app.repository.appsync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.littlesurvival.core.YamiboResult
import io.github.littlesurvival.dto.model.ForumSummary
import io.github.littlesurvival.dto.model.Tags
import io.github.littlesurvival.dto.model.TimeInfo
import io.github.littlesurvival.dto.model.User
import io.github.littlesurvival.dto.page.Post
import io.github.littlesurvival.dto.page.ThreadInfo
import io.github.littlesurvival.dto.page.ThreadPage
import io.github.littlesurvival.dto.value.FormHash
import io.github.littlesurvival.dto.value.ForumId
import io.github.littlesurvival.dto.value.PostId
import io.github.littlesurvival.dto.value.TagId
import io.github.littlesurvival.dto.value.ThreadId
import io.github.littlesurvival.dto.value.UserId
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import me.thenano.yamibo.yamibo_app.Database
import me.thenano.yamibo.yamibo_app.repository.AndroidReadHistoryRepository
import me.thenano.yamibo.yamibo_app.repository.FavoriteStoreRepository
import me.thenano.yamibo.yamibo_app.repository.FavoriteUpdateRepository
import me.thenano.yamibo.yamibo_app.repository.ReadHistoryRepository
import me.thenano.yamibo.yamibo_app.repository.RssSearchSubscriptionRepository
import me.thenano.yamibo.yamibo_app.repository.TagRepository
import me.thenano.yamibo.yamibo_app.repository.ThreadRepository
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncJournalLoadResult
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncJournalPublishResult
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncJournalRemote
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.DatabaseSyncDomainMaterializer
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.LoadedAppSyncCheckpoint
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.LoadedAppSyncJournal
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.OperationSyncEngine
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.OperationSyncResult
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.SqlDelightSyncDomainStateAdapter
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncInstallationState
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncOperationLifecycle
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncAccountBinding
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncJournalPayload
import me.thenano.yamibo.yamibo_app.repository.favorite.FavoriteStoreRepositoryImpl
import me.thenano.yamibo.yamibo_app.repository.favorite.FavoriteUpdateRepositoryImpl
import me.thenano.yamibo.yamibo_app.store.appsync.SqlDelightAppSyncOperationStore
import me.thenano.yamibo.yamibo_app.store.settings.SettingsStore

class AppSyncProductionTwoDeviceConvergenceTest {
    private val account = SyncAccountBinding("account")
    private val formHash = FormHash("form")

    @Test
    fun productionRepositoriesConvergeAcrossCreateUpdateSelectedDeleteAndClearAll() = runBlocking {
        val remote = InMemoryJournalRemote()
        val pages = MutableThreadPages(threadPage(100))
        val deviceA = fixture("a", remote, pages)
        val deviceB = fixture("b", remote, pages)
        insertRssSubscription(deviceB.db, "dummy")
        val rssIdA = insertRssSubscription(deviceA.db, "query")
        val rssIdB = insertRssSubscription(deviceB.db, "query")

        deviceA.settings.putString("theme", "dark")
        deviceA.favorites.ensureDefaults()
        val defaultCategory = deviceA.favorites.getDefaultCategory()
        deviceA.favorites.addNormalThreadFavorite(
            ThreadId(42), "kept", null, null, ForumId(1), "forum",
            categoryIds = listOf(defaultCategory.id),
        )
        deviceA.favorites.addNormalThreadFavorite(
            ThreadId(43), "removed later", null, null, ForumId(1), "forum",
            categoryIds = listOf(defaultCategory.id),
        )

        val baselineRun = deviceA.updates.startRun()
        deviceA.updates.runUpdate(baselineRun)
        pages.current = threadPage(101)
        val updateRun = deviceA.updates.startRun()
        deviceA.updates.runUpdate(updateRun)
        val events = deviceA.updates.getActiveEvents()
        assertEquals(2, events.size)

        val histories = histories(rssIdA)
        saveAndUpdateEveryHistory(deviceA.history, histories)
        deviceA.history.recordReadingDuration("2026-08-01", 100)
        deviceA.history.recordReadingDuration("2026-08-01", 50)

        val firstA = sync(deviceA)
        val firstB = sync(deviceB)
        assertEquals(0, firstA.quarantineCount + firstB.quarantineCount)
        assertEquals(2, deviceB.favorites.getAllFavoriteItems().size)
        assertEquals(2, deviceB.updates.getActiveEvents().size)
        assertHistoryPresent(deviceB.history, histories, rssIdB, expectedOffset = 10L)
        assertCanonicalProjectionEquals(deviceA, deviceB)

        deviceA.settings.putString("theme", "light")
        val removed = assertNotNull(
            deviceA.favorites.getFavoriteItem(
                FavoriteStoreRepository.FavoriteTargetType.ThreadNormal,
                43,
            ),
        )
        deviceA.favorites.deleteFavoriteItems(setOf(removed.id))
        deviceA.updates.dismissEvents(events.map { it.id })
        deviceA.history.deleteCombinedHistoryBatch(listOf(histories.manga, histories.rssSearch))

        val secondA = sync(deviceA)
        val secondB = sync(deviceB)
        assertEquals(0, secondA.quarantineCount + secondB.quarantineCount)
        assertEquals("light", deviceB.settings.getString("theme", "missing"))
        assertEquals(listOf("kept"), deviceB.favorites.getAllFavoriteItems().map { it.title })
        assertTrue(deviceB.updates.getActiveEvents().isEmpty())
        assertEquals(null, deviceB.history.getTagMangaReaderModeHistoryPosition(histories.manga.tagId))
        assertEquals(null, deviceB.history.getRssSearchReaderModeHistoryPosition(rssIdB))
        assertNotNull(deviceB.history.getTagCatalogThreadHistoryPosition(histories.catalog.tagId))
        assertNotNull(deviceB.history.getRssCatalogThreadHistoryPosition(rssIdB))
        assertCanonicalProjectionEquals(deviceA, deviceB)

        deviceA.history.deleteAllCombinedHistory()
        val thirdA = sync(deviceA)
        val thirdB = sync(deviceB)
        assertEquals(0, thirdA.quarantineCount + thirdB.quarantineCount)
        assertAllCombinedHistoryEmpty(deviceA.history)
        assertAllCombinedHistoryEmpty(deviceB.history)
        assertEquals(150L, deviceB.history.getReadingDurationTotal("2026-08-01", "2026-08-01"))
        assertCanonicalProjectionEquals(deviceA, deviceB)
        assertTrue(deviceA.store.pendingOperations().isEmpty())
        assertTrue(deviceB.store.pendingOperations().isEmpty())

        val loadsBeforeFixedPoint = remote.loadCount
        val publishesBeforeFixedPoint = remote.publishCount
        sync(deviceA)
        sync(deviceB)
        assertEquals(loadsBeforeFixedPoint + 2, remote.loadCount)
        assertEquals(publishesBeforeFixedPoint, remote.publishCount)
        assertEquals(8, remote.loadCount)
        assertEquals(4, remote.publishCount)
        assertEquals(0, remote.retryCount)
        val deviceAOperations = deviceA.store.allOutboxOperations()
        assertTrue(deviceAOperations.all { it.second == AppSyncOperationLifecycle.Acknowledged })
        assertEquals(
            deviceAOperations.mapTo(linkedSetOf()) { it.first.operationId },
            remote.operationIds(),
        )
        assertCanonicalProjectionEquals(deviceA, deviceB)
    }

    private suspend fun saveAndUpdateEveryHistory(
        repository: OperationRecordingReadHistoryRepository,
        histories: Histories,
    ) {
        repository.savePosition(histories.thread)
        repository.saveImagePosition(histories.image)
        repository.saveTagMangaReaderModeHistory(histories.manga)
        repository.saveTagCatalogThreadHistory(histories.catalog)
        repository.saveRssSearchReaderModeHistory(histories.rssSearch)
        repository.saveRssCatalogThreadHistory(histories.rssCatalog)
        repository.savePosition(histories.thread.copy(lastVisitTime = 11))
        repository.saveImagePosition(histories.image.copy(lastVisitTime = 12))
        repository.saveTagMangaReaderModeHistory(histories.manga.copy(lastVisitTime = 13))
        repository.saveTagCatalogThreadHistory(histories.catalog.copy(lastVisitTime = 14))
        repository.saveRssSearchReaderModeHistory(histories.rssSearch.copy(lastVisitTime = 15))
        repository.saveRssCatalogThreadHistory(histories.rssCatalog.copy(lastVisitTime = 16))
    }

    private suspend fun assertHistoryPresent(
        repository: ReadHistoryRepository,
        histories: Histories,
        rssId: Long,
        expectedOffset: Long,
    ) {
        assertEquals(
            expectedOffset + 1,
            repository.getPosition(
                histories.thread.threadId,
                histories.thread.threadType,
                histories.thread.authorId,
            )?.lastVisitTime,
        )
        assertEquals(expectedOffset + 2, repository.getImagePosition(histories.image.postId)?.lastVisitTime)
        assertEquals(expectedOffset + 3, repository.getTagMangaReaderModeHistoryPosition(histories.manga.tagId)?.lastVisitTime)
        assertEquals(expectedOffset + 4, repository.getTagCatalogThreadHistoryPosition(histories.catalog.tagId)?.lastVisitTime)
        assertEquals(expectedOffset + 5, repository.getRssSearchReaderModeHistoryPosition(rssId)?.lastVisitTime)
        assertEquals(expectedOffset + 6, repository.getRssCatalogThreadHistoryPosition(rssId)?.lastVisitTime)
    }

    private suspend fun assertAllCombinedHistoryEmpty(repository: ReadHistoryRepository) {
        assertEquals(0L, repository.getHistoryCount())
        assertTrue(repository.getAllImageHistoryForSync().isEmpty())
        assertTrue(repository.getAllTagMangaHistoryForSync().isEmpty())
        assertTrue(repository.getAllTagCatalogHistoryForSync().isEmpty())
        assertTrue(repository.getAllRssSearchHistoryForSync().isEmpty())
        assertTrue(repository.getAllRssCatalogHistoryForSync().isEmpty())
    }

    private fun assertCanonicalProjectionEquals(a: Fixture, b: Fixture) {
        assertEquals(a.domain.currentState(), b.domain.currentState())
    }

    private suspend fun sync(fixture: Fixture): OperationSyncResult.Converged {
        val result = assertIs<OperationSyncResult.Converged>(
            fixture.engine.synchronize(account, formHash),
        )
        assertEquals(0, result.quarantineCount)
        return result
    }

    private fun fixture(
        name: String,
        remote: InMemoryJournalRemote,
        pages: MutableThreadPages,
    ): Fixture {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        val db = Database(driver)
        val settings = MemorySettingsStore()
        val store = SqlDelightAppSyncOperationStore(db).also {
            it.initialize("generation-$name")
            it.bindAccount(account, AppSyncInstallationState.Active)
        }
        val domain = SqlDelightSyncDomainStateAdapter(
            db,
            DatabaseSyncDomainMaterializer(db, settings),
            nowMillis = { 1_000 },
        )
        val recorder = AppSyncMutationRecorder(true, store, domain, nowMillis = { 1_000 })
        val recordingSettings = OperationRecordingSettingsStore(db, settings, recorder)
        val favorites = FavoriteStoreRepositoryImpl(db, recorder)
        val updates = FavoriteUpdateRepositoryImpl(
            db,
            favorites,
            threadRepository(pages),
            unused(),
            unused(),
            recorder,
        )
        val history = OperationRecordingReadHistoryRepository(
            AndroidReadHistoryRepository(db),
            recorder,
        )
        return Fixture(
            db,
            store,
            domain,
            recordingSettings,
            favorites,
            updates,
            history,
            OperationSyncEngine(
                store,
                remote,
                domain,
                nowMillis = { 1_000 },
                ownerId = { "owner-$name" },
            ),
        )
    }

    private fun insertRssSubscription(db: Database, query: String): Long {
        db.rssSearchSubscriptionQueries.insertSubscription(
            title = query,
            query = query,
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
        ReadHistoryRepository.ThreadReadingHistory(
            ReadHistoryRepository.ThreadEntryType.Normal,
            "thread",
            ThreadId(1),
            null,
            null,
            null,
            null,
            null,
            1,
            PostId(1),
            "post",
            1,
            lastVisitTime = 1,
        ),
        ReadHistoryRepository.ImageReadingHistory(PostId(2), ThreadId(1), 1, 10, lastVisitTime = 2),
        ReadHistoryRepository.TagMangaReadingHistory(
            TagId(3), "manga", 1, ThreadId(3), "manga thread", 1, 10, lastVisitTime = 3,
        ),
        ReadHistoryRepository.TagCatalogReadingHistory(
            TagId(4), "catalog", 1, ThreadId(4), "catalog thread", 1,
            PostId(4), "catalog post", lastVisitTime = 4,
        ),
        ReadHistoryRepository.RssSearchReadingHistory(
            rssId, "RSS", "query", 1, ThreadId(5), "rss search", 1, 10, lastVisitTime = 5,
        ),
        ReadHistoryRepository.RssCatalogReadingHistory(
            rssId, "RSS", "query", 1, ThreadId(6), "rss catalog", 1,
            PostId(6), "rss post", lastVisitTime = 6,
        ),
    )

    private fun threadPage(latestPostId: Int): ThreadPage = ThreadPage(
        thread = ThreadInfo(
            ThreadId(42),
            "thread",
            ForumSummary(ForumId(1), "forum", "forum.php?fid=1"),
            totalReplies = latestPostId - 100,
        ),
        posts = listOf(
            Post(
                pid = PostId(latestPostId),
                floor = 1,
                title = "post-$latestPostId",
                author = User(UserId(7), "author"),
                timeCreate = TimeInfo("2026-08-01 00:00", epoch = latestPostId.toLong()),
                contentHtml = "content",
                tags = Tags(),
                poll = null,
            ),
        ),
    )

    @Suppress("UNCHECKED_CAST")
    private fun threadRepository(pages: MutableThreadPages): ThreadRepository =
        Proxy.newProxyInstance(
            ThreadRepository::class.java.classLoader,
            arrayOf(ThreadRepository::class.java),
        ) { _, method, _ ->
            when {
                method.name.startsWith("fetchThread") -> YamiboResult.Success(pages.current)
                method.name.startsWith("getCachedThread") -> null
                method.name.startsWith("setCachedThread") || method.name.startsWith("clearCachedThread") -> Unit
                else -> throw UnsupportedOperationException(method.name)
            }
        } as ThreadRepository

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> unused(): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            throw UnsupportedOperationException(method.name)
        } as T

    private data class MutableThreadPages(var current: ThreadPage)

    private data class Histories(
        val thread: ReadHistoryRepository.ThreadReadingHistory,
        val image: ReadHistoryRepository.ImageReadingHistory,
        val manga: ReadHistoryRepository.TagMangaReadingHistory,
        val catalog: ReadHistoryRepository.TagCatalogReadingHistory,
        val rssSearch: ReadHistoryRepository.RssSearchReadingHistory,
        val rssCatalog: ReadHistoryRepository.RssCatalogReadingHistory,
    )

    private data class Fixture(
        val db: Database,
        val store: SqlDelightAppSyncOperationStore,
        val domain: SqlDelightSyncDomainStateAdapter,
        val settings: OperationRecordingSettingsStore,
        val favorites: FavoriteStoreRepositoryImpl,
        val updates: FavoriteUpdateRepositoryImpl,
        val history: OperationRecordingReadHistoryRepository,
        val engine: OperationSyncEngine,
    )

    private class InMemoryJournalRemote : AppSyncJournalRemote {
        private val journals = linkedMapOf<String, LoadedAppSyncJournal>()
        var loadCount = 0
        var publishCount = 0
        var retryCount = 0

        override suspend fun loadJournals(
            accountBinding: SyncAccountBinding,
            forceDiscovery: Boolean,
        ): AppSyncJournalLoadResult {
            loadCount++
            return AppSyncJournalLoadResult.Success(
                journals.values.filter { it.payload.accountBinding == accountBinding },
                emptyList<LoadedAppSyncCheckpoint>(),
            )
        }

        override suspend fun publishOwnJournal(
            payload: AppSyncJournalPayload,
            expectedFingerprint: String?,
            formHash: FormHash,
        ): AppSyncJournalPublishResult {
            val key = "${payload.deviceId.value}:${payload.deviceEpoch.value}"
            val current = journals[key]
            if (expectedFingerprint != current?.fingerprint) {
                retryCount++
                return AppSyncJournalPublishResult.Conflict("fingerprint changed")
            }
            publishCount++
            val fingerprint = payload.operations.joinToString("|") { it.operationId.value } +
                ":${payload.heartbeatAtEpochMillis}:${payload.checkpointAcknowledgements.size}"
            val loaded = LoadedAppSyncJournal(key, fingerprint, payload)
            journals[key] = loaded
            return AppSyncJournalPublishResult.Verified(loaded)
        }

        fun operationIds() = journals.values
            .flatMapTo(linkedSetOf()) { journal ->
                journal.payload.operations.map { it.operationId }
            }
    }

    private class MemorySettingsStore : SettingsStore {
        private val values = mutableMapOf<String, Any>()
        override fun getInt(key: String, defaultValue: Int) = values[key] as? Int ?: defaultValue
        override fun putInt(key: String, value: Int) = set(key, value)
        override fun getFloat(key: String, defaultValue: Float) = values[key] as? Float ?: defaultValue
        override fun putFloat(key: String, value: Float) = set(key, value)
        override fun getString(key: String, defaultValue: String) = values[key] as? String ?: defaultValue
        override fun putString(key: String, value: String) = set(key, value)
        override fun getBoolean(key: String, defaultValue: Boolean) = values[key] as? Boolean ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) = set(key, value)
        override fun remove(key: String) {
            values.remove(key)
        }
        override fun hasKey(key: String) = key in values
        private fun set(key: String, value: Any) {
            values[key] = value
        }
    }
}

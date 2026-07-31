package me.thenano.yamibo.yamibo_app.repository.backup

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import me.thenano.yamibo.yamibo_app.Database
import me.thenano.yamibo.yamibo_app.repository.BackupRepository
import me.thenano.yamibo.yamibo_app.repository.settings.core.SettingsRegistry
import me.thenano.yamibo.yamibo_app.store.settings.SettingsStore

class BackupRepositoryImplTest {
    @Test
    fun localSnapshotCoversAllHistoryAndDurableUpdateDomains() {
        val fixture = fixture()
        seedPortableData(fixture.db, fixture.settings, settingValue = 7)

        val local = fixture.repository.createSnapshot(1_000, PortableSnapshotScope.LocalBackup)
        val appSync = fixture.repository.createSnapshot(1_000, PortableSnapshotScope.AppSync)

        assertEquals(1, local.readingState.threadHistory.size)
        assertEquals("Favorite", local.readingState.threadHistory.single().historyOrigin)
        assertEquals(1, local.readingState.tagCatalogHistory.size)
        assertEquals(1, local.readingState.rssSearchHistory.size)
        assertEquals(1, local.readingState.rssCatalogHistory.size)
        assertEquals(2, local.favorites.rssSubscriptions.size)
        assertEquals(1, local.readingState.chapterState.size)
        assertEquals(1, local.favoriteUpdates.events.size)
        assertEquals(1, local.favoriteUpdates.fidFilters.size)
        assertEquals(1, local.favoriteUpdates.categoryFilters.size)
        assertEquals(local.favoriteUpdates, appSync.favoriteUpdates)
        assertEquals(local.readingState.tagCatalogHistory, appSync.readingState.tagCatalogHistory)
        assertEquals(local.readingState.rssSearchHistory, appSync.readingState.rssSearchHistory)
        assertEquals(local.readingState.rssCatalogHistory, appSync.readingState.rssCatalogHistory)
        assertTrue(appSync.readingState.chapterState.isEmpty())
        assertTrue("FavoriteUpdateRun" !in PortableDomainManifest.included(PortableSnapshotScope.LocalBackup))
        assertTrue("FavoriteUpdateTrackedTarget" !in PortableDomainManifest.included(PortableSnapshotScope.LocalBackup))
    }

    @Test
    fun overwriteRestoresAllExpandedDomains() = runBlocking {
        val fixture = fixture()
        seedPortableData(fixture.db, fixture.settings, settingValue = 7)
        val source = fixture.repository.createBackup(automatic = false).getOrThrow().uri

        clearPortableData(fixture.db)
        fixture.settings.putInt("test.count", 99)

        val summary = fixture.repository.restoreBackup(source, BackupRepository.RestoreMode.Overwrite)
            .getOrThrow()

        assertEquals(7, fixture.settings.getInt("test.count", -1))
        assertEquals(1, fixture.db.tagCatalogReadingHistoryQueries.getAll().executeAsList().size)
        assertEquals(1, fixture.db.rssSearchReadingHistoryQueries.getAll().executeAsList().size)
        assertEquals(1, fixture.db.rssCatalogReadingHistoryQueries.getAll().executeAsList().size)
        assertEquals(2, fixture.db.rssSearchSubscriptionQueries.getAll().executeAsList().size)
        assertEquals(1, fixture.db.localChapterStateQueries.getAll().executeAsList().size)
        assertEquals(1, fixture.db.favoriteUpdateEventQueries.getAll().executeAsList().size)
        assertEquals(1, fixture.db.favoriteUpdateFidFilterQueries.getAll().executeAsList().size)
        assertEquals(1, fixture.db.favoriteUpdateCategoryFilterQueries.getAll().executeAsList().size)
        assertEquals(6, summary.readingHistory)
        assertEquals(1, summary.updateRecords)
        assertEquals(0, summary.skippedRecords)
    }

    @Test
    fun mergeKeepsNewerProgressAndLifecycleMarkers() = runBlocking {
        val fixture = fixture()
        seedPortableData(fixture.db, fixture.settings, settingValue = 7)
        val source = fixture.repository.createBackup(automatic = false).getOrThrow().uri

        fixture.db.tagCatalogReadingHistoryQueries.upsert(
            tagId = 21661,
            tagName = "newer",
            tagPage = 2,
            threadId = 99,
            threadTitle = "newer",
            threadPage = 2,
            postId = 99,
            postTitle = "newer",
            authorId = 5,
            anchorPostId = 0,
            anchorPostRatio = null,
            anchorBlockId = null,
            anchorBlockType = null,
            anchorBlockRatio = null,
            viewportHeight = null,
            firstVisibleItemIndex = null,
            firstVisibleItemOffset = null,
            lastVisitTime = 500,
            coverUrl = null,
        )
        fixture.db.localChapterStateQueries.upsert(
            targetType = "ThreadNormal",
            parentId = 10,
            targetId = 11,
            title = "newer chapter",
            read = 1,
            progressPercent = 90,
            lastPageIndex = 9,
            totalPages = 10,
            updatedAt = 500,
        )
        val eventId = fixture.db.favoriteUpdateEventQueries.getAll().executeAsOne().id
        fixture.db.favoriteUpdateEventQueries.markRead(500, eventId)

        fixture.repository.restoreBackup(source, BackupRepository.RestoreMode.Merge).getOrThrow()

        assertEquals(
            500,
            fixture.db.tagCatalogReadingHistoryQueries.getByTagId(21661).executeAsOne().lastVisitTime,
        )
        assertEquals(
            500,
            fixture.db.localChapterStateQueries.getByTarget("ThreadNormal", 10, 11).executeAsOne().updatedAt,
        )
        assertEquals(500, fixture.db.favoriteUpdateEventQueries.getAll().executeAsOne().readAt)
    }

    @Test
    fun overwriteFailureAfterClearRollsBackSqlAndSettings() = runBlocking {
        val fixture = fixture()
        seedPortableData(fixture.db, fixture.settings, settingValue = 7)
        val source = fixture.repository.createBackup(automatic = false).getOrThrow().uri

        fixture.db.localChapterStateQueries.upsert(
            targetType = "ThreadNormal",
            parentId = 10,
            targetId = 11,
            title = "local sentinel",
            read = 1,
            progressPercent = 99,
            lastPageIndex = 9,
            totalPages = 10,
            updatedAt = 900,
        )
        fixture.settings.putInt("test.count", 99)
        val failing = fixture.repository(restoreFailureInjector = { phase ->
            if (phase == "after-clear") error("injected restore failure")
        })

        val result = failing.restoreBackup(source, BackupRepository.RestoreMode.Overwrite)

        assertTrue(result.isFailure)
        assertEquals(
            "local sentinel",
            fixture.db.localChapterStateQueries.getByTarget("ThreadNormal", 10, 11).executeAsOne().title,
        )
        assertEquals(99, fixture.settings.getInt("test.count", -1))
        assertEquals(1, fixture.db.favoriteUpdateEventQueries.getAll().executeAsList().size)
    }

    private fun fixture(): Fixture {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        val db = Database(driver)
        val settings = MemorySettingsStore()
        val storage = MemoryBackupStorage()
        val registry = TestSettingsRegistry(settings)
        lateinit var factory: (((String) -> Unit)?) -> BackupRepositoryImpl
        factory = { injector ->
            BackupRepositoryImpl(
                db = db,
                settingsStore = settings,
                settingsRegistries = listOf(registry),
                storageProvider = storage,
                appVersionCode = 5,
                restoreFailureInjector = injector,
            )
        }
        return Fixture(db, settings, storage, factory(null), factory)
    }

    private fun seedPortableData(db: Database, settings: MemorySettingsStore, settingValue: Int) {
        settings.putInt("test.count", settingValue)
        db.localFavoriteCategoryQueries.insertCategory("category", 0, 10, 10)
        val category = db.localFavoriteCategoryQueries.getFirstByName("category").executeAsOne()
        db.localFavoriteCategoryQueries.setSyncId("category-sync", category.id)

        db.readingHistoryQueries.upsert(
            threadId = 1,
            threadType = "Normal",
            threadName = "favorite history",
            threadCover = null,
            forumName = "管理版",
            forumId = 1,
            authorId = 2,
            page = 1,
            postId = 3,
            postTitle = "post",
            anchorPostId = 0,
            anchorPostRatio = null,
            anchorBlockId = null,
            anchorBlockType = null,
            anchorBlockRatio = null,
            globalScrollY = null,
            viewportHeight = null,
            firstVisibleItemIndex = null,
            firstVisibleItemOffset = null,
            historyOrigin = "Favorite",
            lastVisitTime = 100,
            lastUpdatedTime = null,
        )
        db.tagCatalogReadingHistoryQueries.upsert(
            tagId = 21661,
            tagName = "tag",
            tagPage = 1,
            threadId = 2,
            threadTitle = "tag thread",
            threadPage = 1,
            postId = 3,
            postTitle = "post",
            authorId = 4,
            anchorPostId = 0,
            anchorPostRatio = null,
            anchorBlockId = null,
            anchorBlockType = null,
            anchorBlockRatio = null,
            viewportHeight = null,
            firstVisibleItemIndex = null,
            firstVisibleItemOffset = null,
            lastVisitTime = 100,
            coverUrl = null,
        )
        db.rssSearchSubscriptionQueries.insertSubscription(
            title = "app",
            query = "app",
            forumId = null,
            forumName = null,
            enabled = 1,
            createdAt = 10,
            updatedAt = 100,
            lastRefreshStartedAt = null,
            lastRefreshFinishedAt = null,
            lastRefreshStatus = null,
            lastRefreshMessage = null,
            lastSearchId = null,
            lastTotalCount = 0,
        )
        val rssSearchSubscriptionId =
            db.rssSearchSubscriptionQueries.lastInsertedId().executeAsOne()
        db.rssSearchSubscriptionQueries.insertSubscription(
            title = "catalog",
            query = "app",
            forumId = 1,
            forumName = "管理版",
            enabled = 1,
            createdAt = 10,
            updatedAt = 100,
            lastRefreshStartedAt = null,
            lastRefreshFinishedAt = null,
            lastRefreshStatus = null,
            lastRefreshMessage = null,
            lastSearchId = null,
            lastTotalCount = 0,
        )
        val rssCatalogSubscriptionId =
            db.rssSearchSubscriptionQueries.lastInsertedId().executeAsOne()
        db.rssSearchReadingHistoryQueries.upsert(
            subscriptionId = rssSearchSubscriptionId,
            subscriptionTitle = "app",
            subscriptionQuery = "app",
            subscriptionPage = 1,
            threadId = 31,
            threadTitle = "rss search",
            threadImagePageIndex = 0,
            threadImageTotalPages = 3,
            firstVisibleItemIndex = null,
            firstVisibleItemOffset = null,
            lastVisitTime = 100,
            coverUrl = null,
        )
        db.rssCatalogReadingHistoryQueries.upsert(
            subscriptionId = rssCatalogSubscriptionId,
            subscriptionTitle = "catalog",
            subscriptionQuery = "app",
            subscriptionPage = 1,
            threadId = 41,
            threadTitle = "rss catalog",
            threadPage = 1,
            postId = 42,
            postTitle = "post",
            authorId = 5,
            anchorPostId = 0,
            anchorPostRatio = null,
            anchorBlockId = null,
            anchorBlockType = null,
            anchorBlockRatio = null,
            viewportHeight = null,
            firstVisibleItemIndex = null,
            firstVisibleItemOffset = null,
            lastVisitTime = 100,
            coverUrl = null,
        )
        db.localChapterStateQueries.upsert(
            targetType = "ThreadNormal",
            parentId = 10,
            targetId = 11,
            title = "chapter",
            read = 0,
            progressPercent = 25,
            lastPageIndex = 1,
            totalPages = 4,
            updatedAt = 100,
        )
        db.readingTimeStatQueries.upsert("2026-07-30", 1_000, 100)
        db.favoriteUpdateEventQueries.insertEvent(
            targetType = "ThreadNormal",
            targetId = 50,
            authorId = 6,
            fid = 1,
            forumName = "管理版",
            title = "event",
            latestPostTitle = "latest",
            mode = "NormalThread",
            summary = "updated",
            detailIds = "101,102",
            coverUrl = null,
            detectedAt = 100,
            readAt = 100,
            dismissedAt = null,
            ambiguous = 0,
        )
        db.favoriteUpdateFidFilterQueries.upsertFilter(1, "管理版", 0, 1, 100)
        db.favoriteUpdateCategoryFilterQueries.upsertFilter(category.id, "category", 0, 1, 100)
    }

    private fun clearPortableData(db: Database) {
        db.readingHistoryQueries.deleteAll()
        db.tagCatalogReadingHistoryQueries.deleteAll()
        db.rssSearchReadingHistoryQueries.deleteAll()
        db.rssCatalogReadingHistoryQueries.deleteAll()
        db.rssSearchSubscriptionQueries.getAll().executeAsList().forEach {
            db.rssSearchPageCacheQueries.deleteBySubscription(it.id)
            db.rssSearchSubscriptionResultQueries.deleteBySubscription(it.id)
            db.rssSearchSubscriptionQueries.deleteById(it.id)
        }
        db.localChapterStateQueries.deleteAll()
        db.readingTimeStatQueries.deleteAll()
        db.favoriteUpdateEventQueries.deleteAll()
        db.favoriteUpdateFidFilterQueries.deleteAll()
        db.favoriteUpdateCategoryFilterQueries.deleteAll()
    }

    private data class Fixture(
        val db: Database,
        val settings: MemorySettingsStore,
        val storage: MemoryBackupStorage,
        val repository: BackupRepositoryImpl,
        val repositoryFactory: (((String) -> Unit)?) -> BackupRepositoryImpl,
    ) {
        fun repository(restoreFailureInjector: ((String) -> Unit)? = null): BackupRepositoryImpl =
            repositoryFactory(restoreFailureInjector)
    }

    private class TestSettingsRegistry(store: SettingsStore) : SettingsRegistry(store, "test") {
        val count by intSetting("count", 0)
    }

    private class MemorySettingsStore : SettingsStore {
        private val values = mutableMapOf<String, Any>()

        override fun getInt(key: String, defaultValue: Int) = values[key] as? Int ?: defaultValue
        override fun putInt(key: String, value: Int) { values[key] = value }
        override fun getFloat(key: String, defaultValue: Float) = values[key] as? Float ?: defaultValue
        override fun putFloat(key: String, value: Float) { values[key] = value }
        override fun getString(key: String, defaultValue: String) = values[key] as? String ?: defaultValue
        override fun putString(key: String, value: String) { values[key] = value }
        override fun getBoolean(key: String, defaultValue: Boolean) = values[key] as? Boolean ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { values[key] = value }
        override fun remove(key: String) { values.remove(key) }
        override fun hasKey(key: String) = key in values
    }

    private class MemoryBackupStorage : BackupStorageProvider {
        private val files = linkedMapOf<String, ByteArray>()

        override suspend fun getSelectedFolderLabel(): String? = "memory"
        override suspend fun setSelectedFolder(uri: String) = Result.success(Unit)
        override suspend fun writeBackupFile(
            fileName: String,
            bytes: ByteArray,
        ): Result<BackupRepository.BackupFileInfo> {
            files[fileName] = bytes
            return Result.success(
                BackupRepository.BackupFileInfo(fileName, bytes.size.toLong(), fileName, false, 1),
            )
        }

        override suspend fun readBackupFile(sourceUri: String): Result<ByteArray> =
            files[sourceUri]?.let(Result.Companion::success)
                ?: Result.failure(IllegalArgumentException("missing backup"))

        override suspend fun listBackupFiles(): List<BackupRepository.BackupFileInfo> =
            files.map { (name, bytes) ->
                BackupRepository.BackupFileInfo(name, bytes.size.toLong(), name, false, 1)
            }

        override suspend fun getBackupStorageBytes(): Long = files.values.sumOf { it.size.toLong() }
        override suspend fun deleteBackupFile(fileInfo: BackupRepository.BackupFileInfo): Result<Unit> {
            files.remove(fileInfo.uri)
            return Result.success(Unit)
        }
    }
}

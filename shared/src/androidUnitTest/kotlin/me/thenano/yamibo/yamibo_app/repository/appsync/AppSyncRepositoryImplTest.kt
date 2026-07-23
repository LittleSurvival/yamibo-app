package me.thenano.yamibo.yamibo_app.repository.appsync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import me.thenano.yamibo.yamibo_app.Database
import me.thenano.yamibo.yamibo_app.repository.BackupRepository
import me.thenano.yamibo.yamibo_app.repository.backup.BackupRepositoryImpl
import me.thenano.yamibo.yamibo_app.repository.backup.BackupStorageProvider
import me.thenano.yamibo.yamibo_app.repository.appsync.model.*
import me.thenano.yamibo.yamibo_app.repository.settings.AppSettingsRepository
import me.thenano.yamibo.yamibo_app.repository.settings.AppThemeMode
import me.thenano.yamibo.yamibo_app.store.settings.SettingsStore

class AppSyncRepositoryImplTest {
    @Test
    fun exportAndOverwriteCoverEveryHistoryFamilyAndFavoriteGraph() = runBlocking {
        val sourceDb = inMemoryDatabase()
        val sourceSettings = AppSettingsRepository(TestSettingsStore())
        sourceSettings.themeMode.setValue(AppThemeMode.DARK)
        seedAllDomains(sourceDb)
        val source = AppSyncRepositoryImpl(sourceDb, listOf(sourceSettings), appVersionCode = 4)

        val text = assertIs<AppSyncResult.Success<String>>(source.exportText()).value
        val snapshot = assertIs<AppSyncResult.Success<AppSyncEnvelope>>(source.decode(text)).value
        assertEquals(1, snapshot.readingHistory.thread.size)
        assertEquals(1, snapshot.readingHistory.image.size)
        assertEquals(1, snapshot.readingHistory.tagManga.size)
        assertEquals(1, snapshot.readingHistory.tagCatalog.size)
        assertEquals(1, snapshot.readingHistory.rssSearch.size)
        assertEquals(1, snapshot.readingHistory.rssCatalog.size)
        assertEquals(1, snapshot.readingHistory.readingTimeStats.size)
        assertEquals(1, snapshot.favorites.itemCategoryRefs.size)
        assertEquals(1, snapshot.favorites.itemCollectionRefs.size)

        val targetDb = inMemoryDatabase()
        val targetSettings = AppSettingsRepository(TestSettingsStore())
        val target = AppSyncRepositoryImpl(targetDb, listOf(targetSettings), appVersionCode = 4)
        val summary = assertIs<AppSyncResult.Success<AppSyncImportSummary>>(
            target.importText(text, AppSyncApplyMode.Overwrite),
        ).value

        assertEquals(7, summary.readingHistoryApplied)
        assertEquals(1, summary.favoritesApplied)
        assertEquals(AppThemeMode.DARK, targetSettings.themeMode.getValue())
        assertEquals(1, targetDb.readingHistoryQueries.getAll().executeAsList().size)
        assertEquals(1, targetDb.imageReadingHistoryQueries.getAll().executeAsList().size)
        assertEquals(1, targetDb.mangaTagReadingHistoryQueries.getAll().executeAsList().size)
        assertEquals(1, targetDb.tagCatalogReadingHistoryQueries.getAll().executeAsList().size)
        assertEquals(1, targetDb.rssSearchReadingHistoryQueries.getAll().executeAsList().size)
        assertEquals(1, targetDb.rssCatalogReadingHistoryQueries.getAll().executeAsList().size)
        assertEquals(1, targetDb.readingTimeStatQueries.getAll().executeAsList().size)

        val importedItem = targetDb.localFavoriteItemQueries.getAll().executeAsList().single()
        val importedCategory = targetDb.localFavoriteCategoryQueries.getAll().executeAsList().single()
        val importedCollection = targetDb.localFavoriteCollectionQueries.getAll().executeAsList().single()
        assertEquals(
            importedCategory.id,
            targetDb.localFavoriteItemCategoryCrossRefQueries.getAll().executeAsList().single().categoryId,
        )
        assertEquals(
            importedCollection.id,
            targetDb.localFavoriteItemCollectionCrossRefQueries.getAll().executeAsList().single().collectionId,
        )
        assertEquals(
            importedItem.id,
            targetDb.localFavoriteItemCollectionCrossRefQueries.getAll().executeAsList().single().itemId,
        )
    }

    @Test
    fun mergeKeepsNewerLocalHistory() = runBlocking {
        val db = inMemoryDatabase()
        db.imageReadingHistoryQueries.upsert(10, 20, 9, 10, null, null, 9_000)
        val repository = AppSyncRepositoryImpl(db, listOf(AppSettingsRepository(TestSettingsStore())), 4)
        val snapshot = AppSyncEnvelope(
            exportedAtEpochMillis = 1,
            sourceAppVersionCode = 4,
            readingHistory = me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncReadingHistory(
                image = listOf(
                    me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncImageHistory(
                        postId = 10,
                        threadId = 20,
                        pageIndex = 1,
                        totalPages = 10,
                        lastVisitTime = 1_000,
                    ),
                ),
            ),
        )

        assertIs<AppSyncResult.Success<AppSyncImportSummary>>(
            repository.applySnapshot(snapshot, AppSyncApplyMode.Merge),
        )
        assertEquals(9L, db.imageReadingHistoryQueries.getByPostId(10).executeAsOne().pageIndex)
    }

    @Test
    fun settingFailureRollsBackEarlierSettingWrites() = runBlocking {
        val db = inMemoryDatabase()
        val store = ThrowingSettingsStore()
        val settings = AppSettingsRepository(store)
        settings.themeMode.setValue(AppThemeMode.SYSTEM)
        store.throwOnThemeScheme = true
        val repository = AppSyncRepositoryImpl(db, listOf(settings), 4)
        val snapshot = AppSyncEnvelope(
            exportedAtEpochMillis = 1,
            sourceAppVersionCode = 4,
            settings = listOf(
                AppSyncSetting("appsettings.thememode", AppSyncSettingType.Enum, "DARK"),
                AppSyncSetting("appsettings.themescheme", AppSyncSettingType.Enum, "CLASSIC_BLACK"),
            ),
        )

        val failure = assertIs<AppSyncResult.Failure>(
            repository.applySnapshot(snapshot, AppSyncApplyMode.Merge),
        )
        assertIs<AppSyncError.Apply>(failure.error)
        assertEquals(AppThemeMode.SYSTEM, settings.themeMode.getValue())
    }

    @Test
    fun remoteOperationsAreTypedSideEffectFreeStubs() = runBlocking {
        val repository = AppSyncRepositoryImpl(
            inMemoryDatabase(),
            listOf(AppSettingsRepository(TestSettingsStore())),
            4,
        )

        val upload = assertIs<AppSyncResult.Failure>(repository.uploadCloud("text"))
        val load = assertIs<AppSyncResult.Failure>(repository.loadCloud())
        val update = assertIs<AppSyncResult.Failure>(repository.updateCloud("text"))
        assertEquals(AppSyncRemoteOperation.Upload, assertIs<AppSyncError.RemoteUnavailable>(upload.error).operation)
        assertEquals(AppSyncRemoteOperation.Load, assertIs<AppSyncError.RemoteUnavailable>(load.error).operation)
        assertEquals(AppSyncRemoteOperation.Update, assertIs<AppSyncError.RemoteUnavailable>(update.error).operation)
        assertTrue(repository.createSnapshot() is AppSyncResult.Success)
    }

    @Test
    fun existingYamiboBackupCreationAndRestoreRemainCompatible() = runBlocking {
        val db = inMemoryDatabase()
        seedAllDomains(db)
        val settings = AppSettingsRepository(TestSettingsStore())
        val storage = InMemoryBackupStorageProvider()
        val backup = BackupRepositoryImpl(db, TestSettingsStore(), listOf(settings), storage, 4)

        val created = backup.createBackup(automatic = false, customName = "compat").getOrThrow()
        assertTrue(created.name.endsWith(".yamibobak"))
        assertTrue(storage.bytes.decodeToString().contains("\"schemaVersion\": 1"))

        val restored = backup.restoreBackup(created.uri, BackupRepository.RestoreMode.Overwrite).getOrThrow()
        assertEquals(1, restored.favorites)
        assertEquals(3, restored.readingHistory)
    }

    private fun seedAllDomains(db: Database) {
        db.readingHistoryQueries.upsert(
            100,
            "Normal",
            "Thread",
            null,
            "Forum",
            5,
            0,
            2,
            101,
            "Post",
            101,
            0.5,
            null,
            null,
            null,
            null,
            900,
            4,
            20,
            "Direct",
            1_000,
            900,
        )
        db.imageReadingHistoryQueries.upsert(101, 100, 2, 5, 4, 20, 1_100)
        db.mangaTagReadingHistoryQueries.upsert(
            7,
            "Tag",
            1,
            100,
            "Thread",
            2,
            5,
            4,
            20,
            1_200,
            null,
        )
        db.tagCatalogReadingHistoryQueries.upsert(
            7,
            "Tag",
            1,
            100,
            "Thread",
            2,
            101,
            "Post",
            null,
            101,
            0.5,
            null,
            null,
            null,
            900,
            4,
            20,
            1_300,
            null,
        )
        db.rssSearchReadingHistoryQueries.upsert(
            8,
            "RSS",
            "query",
            1,
            100,
            "Thread",
            2,
            5,
            4,
            20,
            1_400,
            null,
        )
        db.rssCatalogReadingHistoryQueries.upsert(
            8,
            "RSS",
            "query",
            1,
            100,
            "Thread",
            2,
            101,
            "Post",
            null,
            101,
            0.5,
            null,
            null,
            null,
            900,
            4,
            20,
            1_500,
            null,
        )
        db.readingTimeStatQueries.upsert("2026-07-23", 60_000, 1_600)

        db.localFavoriteCategoryQueries.insertCategory("Default", 0, 1, 1)
        val category = db.localFavoriteCategoryQueries.getFirstByName("Default").executeAsOne()
        db.localFavoriteCollectionQueries.insertCollection(category.id, "Shelf", "brown", 0, 1, 1)
        val collection = db.localFavoriteCollectionQueries.getLatestByCategoryId(category.id).executeAsOne()
        db.localFavoriteItemQueries.insertFavoriteItem(
            "ThreadNormal",
            100,
            "Thread",
            null,
            900,
            5,
            "Forum",
            0,
            1,
            1_000,
        )
        val item = db.localFavoriteItemQueries.findByTarget("ThreadNormal", 100, 0).executeAsOne()
        db.localFavoriteItemCategoryCrossRefQueries.insertCrossRef(item.id, category.id, 1)
        db.localFavoriteItemCollectionCrossRefQueries.insertCrossRef(item.id, collection.id, 1)
    }

    private fun inMemoryDatabase(): Database {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        return Database(driver)
    }
}

private open class TestSettingsStore : SettingsStore {
    protected val values = mutableMapOf<String, String>()
    override fun getInt(key: String, defaultValue: Int): Int = values[key]?.toIntOrNull() ?: defaultValue
    override fun putInt(key: String, value: Int) { values[key] = value.toString() }
    override fun getFloat(key: String, defaultValue: Float): Float = values[key]?.toFloatOrNull() ?: defaultValue
    override fun putFloat(key: String, value: Float) { values[key] = value.toString() }
    override fun getString(key: String, defaultValue: String): String = values[key] ?: defaultValue
    override fun putString(key: String, value: String) { values[key] = value }
    override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        values[key]?.toBooleanStrictOrNull() ?: defaultValue
    override fun putBoolean(key: String, value: Boolean) { values[key] = value.toString() }
    override fun remove(key: String) { values.remove(key) }
    override fun hasKey(key: String): Boolean = key in values
}

private class ThrowingSettingsStore : TestSettingsStore() {
    var throwOnThemeScheme = false

    override fun putString(key: String, value: String) {
        if (throwOnThemeScheme && key == "appsettings.themescheme") {
            error("simulated settings write failure")
        }
        super.putString(key, value)
    }
}

private class InMemoryBackupStorageProvider : BackupStorageProvider {
    var bytes: ByteArray = byteArrayOf()
    private var fileInfo: BackupRepository.BackupFileInfo? = null

    override suspend fun getSelectedFolderLabel(): String = "memory"
    override suspend fun setSelectedFolder(uri: String): Result<Unit> = Result.success(Unit)

    override suspend fun writeBackupFile(
        fileName: String,
        bytes: ByteArray,
    ): Result<BackupRepository.BackupFileInfo> {
        this.bytes = bytes
        return Result.success(
            BackupRepository.BackupFileInfo(fileName, bytes.size.toLong(), "memory://backup", false, 1),
        ).also { fileInfo = it.getOrThrow() }
    }

    override suspend fun readBackupFile(sourceUri: String): Result<ByteArray> = Result.success(bytes)
    override suspend fun listBackupFiles(): List<BackupRepository.BackupFileInfo> = listOfNotNull(fileInfo)
    override suspend fun getBackupStorageBytes(): Long = bytes.size.toLong()
    override suspend fun deleteBackupFile(fileInfo: BackupRepository.BackupFileInfo): Result<Unit> {
        this.fileInfo = null
        bytes = byteArrayOf()
        return Result.success(Unit)
    }
}

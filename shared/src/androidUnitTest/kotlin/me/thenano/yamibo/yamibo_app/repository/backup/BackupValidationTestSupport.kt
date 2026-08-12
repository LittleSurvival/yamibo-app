package me.thenano.yamibo.yamibo_app.repository.backup

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.serialization.json.Json
import me.thenano.yamibo.yamibo_app.Database
import me.thenano.yamibo.yamibo_app.repository.BackupRepository
import me.thenano.yamibo.yamibo_app.repository.settings.core.SettingsRegistry
import me.thenano.yamibo.yamibo_app.store.settings.SettingsStore

internal class BackupValidationHarness(
    val settings: MutableTestSettingsStore = MutableTestSettingsStore(),
    registries: List<SettingsRegistry>? = null,
    restoreFailureInjector: ((String) -> Unit)? = null,
) {
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also(Database.Schema::create)
    val db = Database(driver)
    val storage = MutableTestBackupStorage()
    val registry = ValidationSettingsRegistry(settings)
    val repository = BackupRepositoryImpl(
        db = db,
        settingsStore = settings,
        settingsRegistries = registries ?: listOf(registry),
        storageProvider = storage,
        appVersionCode = 5,
        restoreFailureInjector = restoreFailureInjector,
    )

    fun repositoryWithInjector(injector: (String) -> Unit) = BackupRepositoryImpl(
        db = db,
        settingsStore = settings,
        settingsRegistries = listOf(registry),
        storageProvider = storage,
        appVersionCode = 5,
        restoreFailureInjector = injector,
    )

    fun put(name: String, backup: YamiboBackupFile) {
        storage.put(name, BACKUP_TEST_JSON.encodeToString(YamiboBackupFile.serializer(), backup).encodeToByteArray())
    }
}

internal class ValidationSettingsRegistry(store: SettingsStore) : SettingsRegistry(store, "validation") {
    val count by intSetting("count", 0)
    val ratio by floatSetting("ratio", 0f)
    val enabled by boolSetting("enabled", false)
    val label by stringSetting("label", "")
}

internal class MutableTestSettingsStore : SettingsStore {
    private val values = linkedMapOf<String, Any>()
    var failNextPutKey: String? = null

    override fun getInt(key: String, defaultValue: Int) = values[key] as? Int ?: defaultValue
    override fun putInt(key: String, value: Int) = put(key, value)
    override fun getFloat(key: String, defaultValue: Float) = values[key] as? Float ?: defaultValue
    override fun putFloat(key: String, value: Float) = put(key, value)
    override fun getString(key: String, defaultValue: String) = values[key] as? String ?: defaultValue
    override fun putString(key: String, value: String) = put(key, value)
    override fun getBoolean(key: String, defaultValue: Boolean) = values[key] as? Boolean ?: defaultValue
    override fun putBoolean(key: String, value: Boolean) = put(key, value)
    override fun remove(key: String) {
        values.remove(key)
    }

    override fun hasKey(key: String) = key in values

    private fun put(key: String, value: Any) {
        if (failNextPutKey == key) {
            failNextPutKey = null
            error("injected settings failure for $key")
        }
        values[key] = value
    }
}

internal class MutableTestBackupStorage : BackupStorageProvider {
    private val files = linkedMapOf<String, ByteArray>()

    fun put(name: String, bytes: ByteArray) {
        files[name] = bytes
    }

    override suspend fun getSelectedFolderLabel(): String? = "memory"
    override suspend fun setSelectedFolder(uri: String) = Result.success(Unit)

    override suspend fun writeBackupFile(
        fileName: String,
        bytes: ByteArray,
    ): Result<BackupRepository.BackupFileInfo> {
        files[fileName] = bytes
        return Result.success(BackupRepository.BackupFileInfo(fileName, bytes.size.toLong(), fileName, false, 1))
    }

    override suspend fun readBackupFile(sourceUri: String): Result<ByteArray> =
        files[sourceUri]?.let { Result.success(it) }
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

internal fun expandedBackup(
    categoryLocalId: Long = 100,
    categorySyncId: String = "category-sync",
    progressTime: Long = 100,
    eventDetails: List<Long> = listOf(101, 102),
    eventDetectedAt: Long = 100,
    eventSummary: String = "updated",
    eventTitle: String = "event",
    eventReadAt: Long? = 100,
    eventDismissedAt: Long? = null,
    eventAmbiguous: Boolean = false,
    includeOrphanCategoryFilter: Boolean = false,
): YamiboBackupFile {
    val eventIdentity = favoriteUpdateEventIdentity(
        targetType = "ThreadNormal",
        targetId = 50,
        authorId = 6,
        mode = "NormalThread",
        detailIds = eventDetails,
        ambiguous = eventAmbiguous,
        detectedAt = eventDetectedAt,
        summary = eventSummary,
        title = eventTitle,
    )
    return YamiboBackupFile(
        appVersionCode = 5,
        createdAt = 123,
        favorites = BackupFavorites(
            categories = listOf(
                BackupFavoriteCategory(categoryLocalId, categorySyncId, "category", 0, 10, 20),
            ),
            collections = listOf(
                BackupFavoriteCollection(200, "collection-sync", categoryLocalId, "collection", "brown", 0, 10, 20),
            ),
            items = listOf(
                BackupFavoriteItem(300, "ThreadNormal", 50, "favorite", null, 80, 1, "管理版", 6, 10, 80),
            ),
            rssSubscriptions = listOf(
                BackupRssSearchSubscription(
                    30,
                    me.thenano.yamibo.yamibo_app.repository.rss
                        .rssSearchSubscriptionSyncId("app", null),
                    "rss search",
                    "app",
                    null,
                    null,
                    true,
                    10,
                    progressTime,
                ),
                BackupRssSearchSubscription(
                    40,
                    me.thenano.yamibo.yamibo_app.repository.rss
                        .rssSearchSubscriptionSyncId("app", 1),
                    "rss catalog",
                    "app",
                    1,
                    "管理版",
                    true,
                    10,
                    progressTime,
                ),
            ),
            itemCategories = listOf(BackupFavoriteItemCategory(300, categoryLocalId, 10)),
            itemCollections = listOf(BackupFavoriteItemCollection(300, 200, 10)),
        ),
        settings = listOf(
            BackupSetting("validation.count", BackupSettingType.Int, "7"),
            BackupSetting("validation.ratio", BackupSettingType.Float, "1.5"),
            BackupSetting("validation.enabled", BackupSettingType.Bool, "true"),
            BackupSetting("validation.label", BackupSettingType.String, "portable"),
        ),
        notes = listOf(BackupDetailNote("ThreadNormal", 50, 6, "note", 10, progressTime)),
        bookmarks = listOf(BackupBookMark("ThreadNormal", 50, 51, "bookmark", true, true, 10, progressTime)),
        readingState = BackupReadingState(
            threadHistory = listOf(
                BackupThreadReadingHistory(
                    threadId = 50,
                    threadType = "Normal",
                    threadName = "thread",
                    threadCover = null,
                    forumName = "管理版",
                    forumId = 1,
                    authorId = 6,
                    page = 2,
                    postId = 51,
                    postTitle = "post",
                    anchorPostId = 51,
                    anchorPostRatio = 0.5,
                    anchorBlockId = "block",
                    anchorBlockType = "text",
                    anchorBlockRatio = 0.25,
                    globalScrollY = 100,
                    viewportHeight = 800,
                    firstVisibleItemIndex = 2,
                    firstVisibleItemOffset = 10,
                    historyOrigin = "Favorite",
                    lastVisitTime = progressTime,
                    lastUpdatedTime = progressTime,
                ),
            ),
            imageHistory = listOf(
                BackupImageReadingHistory(51, 50, 2, 10, 2, 10, progressTime),
            ),
            tagMangaHistory = listOf(
                BackupTagMangaReadingHistory(21661, "tag manga", 1, 50, "thread", 2, 10, 2, 10, progressTime, null),
            ),
            tagCatalogHistory = listOf(
                BackupTagCatalogReadingHistory(
                    21662, "tag catalog", 1, 50, "thread", 2, 51, "post", 6,
                    51, 0.5, "block", "text", 0.25, 800, 2, 10, progressTime, null,
                ),
            ),
            rssSearchHistory = listOf(
                BackupRssSearchReadingHistory(30, "rss search", "app", 1, 50, "thread", 2, 10, 2, 10, progressTime, null),
            ),
            rssCatalogHistory = listOf(
                BackupRssCatalogReadingHistory(
                    40, "rss catalog", "app", 1, 50, "thread", 2, 51, "post", 6,
                    51, 0.5, "block", "text", 0.25, 800, 2, 10, progressTime, null,
                ),
            ),
            chapterState = listOf(
                BackupChapterState("ThreadNormal", 50, 51, "chapter", true, 75, 3, 4, progressTime),
            ),
            readingTimeStats = listOf(BackupReadingTimeStat("2026-07-30", 1_000, progressTime)),
        ),
        favoriteUpdates = BackupFavoriteUpdates(
            events = listOf(
                BackupFavoriteUpdateEvent(
                    syncId = eventIdentity.syncId,
                    sourceFingerprint = eventIdentity.sourceFingerprint,
                    targetType = "ThreadNormal",
                    targetId = 50,
                    authorId = 6,
                    fid = 1,
                    forumName = "管理版",
                    title = eventTitle,
                    latestPostTitle = "latest",
                    mode = "NormalThread",
                    summary = eventSummary,
                    detailIds = eventDetails,
                    coverUrl = null,
                    detectedAt = eventDetectedAt,
                    readAt = eventReadAt,
                    dismissedAt = eventDismissedAt,
                    ambiguous = eventAmbiguous,
                ),
            ),
            fidFilters = listOf(BackupFavoriteUpdateFidFilter(1, false)),
            categoryFilters = buildList {
                add(BackupFavoriteUpdateCategoryFilter(categorySyncId, false))
                if (includeOrphanCategoryFilter) {
                    add(BackupFavoriteUpdateCategoryFilter("missing-category", true))
                }
            },
        ),
    )
}

internal val BACKUP_TEST_JSON = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
    explicitNulls = true
}

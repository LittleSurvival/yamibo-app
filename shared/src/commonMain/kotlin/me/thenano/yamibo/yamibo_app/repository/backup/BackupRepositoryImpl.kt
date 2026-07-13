package me.thenano.yamibo.yamibo_app.repository.backup

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import me.thenano.yamibo.yamibo_app.Database
import me.thenano.yamibo.yamibo_app.repository.BackupRepository
import me.thenano.yamibo.yamibo_app.repository.settings.core.*
import me.thenano.yamibo.yamibo_app.store.settings.SettingsStore
import me.thenano.yamibo.yamibo_app.util.time.currentLocalDateKeyAt
import me.thenano.yamibo.yamibo_app.util.time.currentTimeMillis

class BackupRepositoryImpl(
    private val db: Database,
    private val settingsStore: SettingsStore,
    private val settingsRegistries: List<SettingsRegistry>,
    private val storageProvider: BackupStorageProvider,
    private val appVersionCode: Int,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    },
) : BackupRepository {
    private val categoryQueries = db.localFavoriteCategoryQueries
    private val collectionQueries = db.localFavoriteCollectionQueries
    private val itemQueries = db.localFavoriteItemQueries
    private val itemCategoryQueries = db.localFavoriteItemCategoryCrossRefQueries
    private val itemCollectionQueries = db.localFavoriteItemCollectionCrossRefQueries
    private val noteQueries = db.detailNoteQueries
    private val bookmarkQueries = db.localBookMarkQueries
    private val readingQueries = db.readingHistoryQueries
    private val imageHistoryQueries = db.imageReadingHistoryQueries
    private val tagHistoryQueries = db.mangaTagReadingHistoryQueries
    private val readingTimeQueries = db.readingTimeStatQueries

    suspend fun createBackup(automatic: Boolean): Result<BackupRepository.BackupFileInfo> =
        createBackup(automatic = automatic, customName = null)

    override suspend fun createBackup(
        automatic: Boolean,
        customName: String?,
    ): Result<BackupRepository.BackupFileInfo> = runCatching {
        val now = currentTimeMillis()
        val backup = createSnapshot(now)
        val bytes = json.encodeToString(YamiboBackupFile.serializer(), backup).encodeToByteArray()
        val written = storageProvider.writeBackupFile(
            fileName = backupFileName(nowMillis = now, automatic = automatic, customName = customName),
            bytes = bytes,
        ).getOrThrow()
        if (automatic) cleanupAutoBackups(maxFiles = Int.MAX_VALUE)
        written
    }

    override suspend fun restoreBackup(sourceUri: String, mode: BackupRepository.RestoreMode): Result<BackupRepository.RestoreSummary> = runCatching {
        val backupBytes = storageProvider.readBackupFile(sourceUri).getOrThrow()
        val backup = try {
            json.decodeFromString(YamiboBackupFile.serializer(), backupBytes.decodeToString())
        } catch (e: SerializationException) {
            throw IllegalArgumentException("備份檔案格式無法解析", e)
        }
        val migrated = migrate(backup)
        restoreSnapshot(migrated, mode)
    }

    override suspend fun listBackupFiles(): List<BackupRepository.BackupFileInfo> =
        storageProvider.listBackupFiles()

    override suspend fun getBackupStorageBytes(): Long =
        storageProvider.getBackupStorageBytes()

    override suspend fun cleanupAutoBackups(maxFiles: Int): Result<Int> = runCatching {
        val keepCount = maxFiles.coerceIn(1, 10)
        val autoFiles = storageProvider.listBackupFiles()
            .filter { it.automatic }
            .sortedWith(compareByDescending<BackupRepository.BackupFileInfo> { it.modifiedAt ?: 0L }.thenByDescending { it.name })
        val toDelete = autoFiles.drop(keepCount)
        toDelete.forEach { storageProvider.deleteBackupFile(it).getOrThrow() }
        toDelete.size
    }

    override suspend fun getSelectedFolderLabel(): String? =
        storageProvider.getSelectedFolderLabel()

    override suspend fun setSelectedFolder(uri: String): Result<Unit> =
        storageProvider.setSelectedFolder(uri)

    private fun createSnapshot(now: Long): YamiboBackupFile {
        val categories = categoryQueries.getAll().executeAsList()
        val collections = collectionQueries.getAll().executeAsList()
        val items = itemQueries.getAll().executeAsList()
        val settings = settingsRegistries
            .flatMap { it.exportableSettingItems }
            .filterNot { shouldSkipSetting(it.storageKey) }
            .mapNotNull(::settingToBackup)

        return YamiboBackupFile(
            appVersionCode = appVersionCode,
            createdAt = now,
            favorites = BackupFavorites(
                categories = categories.map {
                    BackupFavoriteCategory(it.id, it.name, it.sortOrder, it.createdAt, it.updatedAt)
                },
                collections = collections.map {
                    BackupFavoriteCollection(it.id, it.categoryId, it.name, it.colorKey, it.sortOrder, it.createdAt, it.updatedAt)
                },
                items = items.map {
                    BackupFavoriteItem(
                        localId = it.id,
                        targetType = it.targetType,
                        targetId = it.targetId,
                        title = it.title,
                        coverUrl = it.coverUrl,
                        lastUpdatedTime = it.lastUpdatedTime,
                        forumId = it.forumId,
                        forumName = it.forumName,
                        authorId = it.authorId,
                        createdAt = it.createdAt,
                        lastFavoriteStatusUpdateAt = it.lastFavoriteStatusUpdateAt,
                    )
                },
                itemCategories = itemCategoryQueries.getAll().executeAsList().map {
                    BackupFavoriteItemCategory(it.itemId, it.categoryId, it.createdAt)
                },
                itemCollections = itemCollectionQueries.getAll().executeAsList().map {
                    BackupFavoriteItemCollection(it.itemId, it.collectionId, it.createdAt)
                },
            ),
            settings = settings,
            notes = noteQueries.getAll().executeAsList().map {
                BackupDetailNote(it.targetType, it.targetId, it.authorId, it.content, it.createdAt, it.updatedAt)
            },
            bookmarks = bookmarkQueries.getAll().executeAsList().map {
                BackupBookMark(
                    targetType = it.targetType,
                    parentId = it.parentId,
                    targetId = it.targetId,
                    title = it.title,
                    bookmarked = it.bookmarked != 0L,
                    read = it.read != 0L,
                    createdAt = it.createdAt,
                    updatedAt = it.updatedAt,
                )
            },
            readingState = BackupReadingState(
                threadHistory = readingQueries.getAll().executeAsList().map {
                    BackupThreadReadingHistory(
                        threadId = it.threadId,
                        threadType = it.threadType,
                        threadName = it.threadName,
                        threadCover = it.threadCover,
                        forumName = it.forumName,
                        forumId = it.forumId,
                        authorId = it.authorId,
                        page = it.page,
                        postId = it.postId,
                        postTitle = it.postTitle,
                        anchorPostId = it.anchorPostId,
                        anchorPostRatio = it.anchorPostRatio,
                        anchorBlockId = it.anchorBlockId,
                        anchorBlockType = it.anchorBlockType,
                        anchorBlockRatio = it.anchorBlockRatio,
                        globalScrollY = it.globalScrollY,
                        viewportHeight = it.viewportHeight,
                        firstVisibleItemIndex = it.firstVisibleItemIndex,
                        firstVisibleItemOffset = it.firstVisibleItemOffset,
                        historyOrigin = it.historyOrigin,
                        lastVisitTime = it.lastVisitTime,
                        lastUpdatedTime = it.lastUpdatedTime,
                    )
                },
                imageHistory = imageHistoryQueries.getAll().executeAsList().map {
                    BackupImageReadingHistory(
                        postId = it.postId,
                        threadId = it.threadId,
                        pageIndex = it.pageIndex,
                        totalPages = it.totalPages,
                        firstVisibleItemIndex = it.firstVisibleItemIndex,
                        firstVisibleItemOffset = it.firstVisibleItemOffset,
                        lastVisitTime = it.lastVisitTime,
                    )
                },
                tagMangaHistory = tagHistoryQueries.getAll().executeAsList().map {
                    BackupTagMangaReadingHistory(
                        tagId = it.tagId,
                        tagName = it.tagName,
                        tagPage = it.tagPage,
                        threadId = it.threadId,
                        threadTitle = it.threadTitle,
                        threadImagePageIndex = it.threadImagePageIndex,
                        threadImageTotalPages = it.threadImageTotalPages,
                        firstVisibleItemIndex = it.firstVisibleItemIndex,
                        firstVisibleItemOffset = it.firstVisibleItemOffset,
                        lastVisitTime = it.lastVisitTime,
                        coverUrl = it.coverUrl,
                    )
                },
                readingTimeStats = readingTimeQueries.getAll().executeAsList().map {
                    BackupReadingTimeStat(it.dateKey, it.durationMillis, it.updatedAt)
                },
            ),
        )
    }

    private fun restoreSnapshot(
        backup: YamiboBackupFile,
        mode: BackupRepository.RestoreMode,
    ): BackupRepository.RestoreSummary {
        if (mode == BackupRepository.RestoreMode.Overwrite) {
            clearRestorableData()
        }

        restoreSettings(backup.settings)

        val categoryIdMap = mutableMapOf<Long, Long>()
        val collectionIdMap = mutableMapOf<Long, Long>()
        val itemIdMap = mutableMapOf<Long, Long>()

        db.transaction {
            backup.favorites.categories.forEach { category ->
                val existing = if (mode == BackupRepository.RestoreMode.Merge) {
                    categoryQueries.getAll().executeAsList()
                        .firstOrNull { it.name.trim().equals(category.name.trim(), ignoreCase = true) }
                } else null
                val targetId = existing?.id ?: run {
                    categoryQueries.insertCategory(category.name, category.sortOrder, category.createdAt, category.updatedAt)
                    categoryQueries.getFirstByName(category.name).executeAsOne().id
                }
                categoryIdMap[category.localId] = targetId
            }

            backup.favorites.collections.forEach { collection ->
                val mappedCategoryId = categoryIdMap[collection.categoryLocalId] ?: return@forEach
                val existing = if (mode == BackupRepository.RestoreMode.Merge) {
                    collectionQueries.getByCategoryId(mappedCategoryId).executeAsList()
                        .firstOrNull { it.name.trim().equals(collection.name.trim(), ignoreCase = true) }
                } else null
                val targetId = existing?.id ?: run {
                    collectionQueries.insertCollection(
                        categoryId = mappedCategoryId,
                        name = collection.name,
                        colorKey = collection.colorKey,
                        sortOrder = collection.sortOrder,
                        createdAt = collection.createdAt,
                        updatedAt = collection.updatedAt,
                    )
                    collectionQueries.getLatestByCategoryId(mappedCategoryId).executeAsOne().id
                }
                collectionIdMap[collection.localId] = targetId
            }

            backup.favorites.items.forEach { item ->
                val existing = if (mode == BackupRepository.RestoreMode.Merge) {
                    itemQueries.findByTarget(item.targetType, item.targetId, item.authorId).executeAsOneOrNull()
                } else null
                val targetId = existing?.id ?: run {
                    itemQueries.insertFavoriteItem(
                        targetType = item.targetType,
                        targetId = item.targetId,
                        title = item.title,
                        coverUrl = item.coverUrl,
                        lastUpdatedTime = item.lastUpdatedTime,
                        forumId = item.forumId,
                        forumName = item.forNameSafe(),
                        authorId = item.authorId,
                        createdAt = item.createdAt,
                        lastFavoriteStatusUpdateAt = item.lastFavoriteStatusUpdateAt,
                    )
                    itemQueries.findByTarget(item.targetType, item.targetId, item.authorId).executeAsOne().id
                }
                if (existing != null && item.lastFavoriteStatusUpdateAt > existing.lastFavoriteStatusUpdateAt) {
                    itemQueries.updateFavoriteItem(
                        title = item.title,
                        coverUrl = item.coverUrl,
                        lastUpdatedTime = item.lastUpdatedTime,
                        forumId = item.forumId,
                        forumName = item.forNameSafe(),
                        authorId = item.authorId,
                        lastFavoriteStatusUpdateAt = item.lastFavoriteStatusUpdateAt,
                        id = existing.id,
                    )
                }
                itemIdMap[item.localId] = targetId
            }

            backup.favorites.itemCategories.forEach { ref ->
                val itemId = itemIdMap[ref.itemLocalId] ?: return@forEach
                val categoryId = categoryIdMap[ref.categoryLocalId] ?: return@forEach
                itemCategoryQueries.insertCrossRef(itemId, categoryId, ref.createdAt)
            }

            backup.favorites.itemCollections.forEach { ref ->
                val itemId = itemIdMap[ref.itemLocalId] ?: return@forEach
                val collectionId = collectionIdMap[ref.collectionLocalId] ?: return@forEach
                itemCollectionQueries.insertCrossRef(itemId, collectionId, ref.createdAt)
            }

            backup.notes.forEach {
                noteQueries.upsert(it.targetType, it.targetId, it.authorId, it.content, it.createdAt, it.updatedAt)
            }

            backup.bookmarks.forEach {
                bookmarkQueries.upsert(
                    targetType = it.targetType,
                    parentId = it.parentId,
                    targetId = it.targetId,
                    title = it.title,
                    bookmarked = if (it.bookmarked) 1L else 0L,
                    read = if (it.read) 1L else 0L,
                    createdAt = it.createdAt,
                    updatedAt = it.updatedAt,
                )
            }

            backup.readingState.threadHistory.forEach {
                readingQueries.upsert(
                    threadId = it.threadId,
                    threadType = it.threadType,
                    threadName = it.threadName,
                    threadCover = it.threadCover,
                    forumName = it.forumName,
                    forumId = it.forumId,
                    authorId = it.authorId,
                    page = it.page,
                    postId = it.postId,
                    postTitle = it.postTitle,
                    anchorPostId = it.anchorPostId,
                    anchorPostRatio = it.anchorPostRatio,
                    anchorBlockId = it.anchorBlockId,
                    anchorBlockType = it.anchorBlockType,
                    anchorBlockRatio = it.anchorBlockRatio,
                    globalScrollY = it.globalScrollY,
                    viewportHeight = it.viewportHeight,
                    firstVisibleItemIndex = it.firstVisibleItemIndex,
                    firstVisibleItemOffset = it.firstVisibleItemOffset,
                    historyOrigin = it.historyOrigin,
                    lastVisitTime = it.lastVisitTime,
                    lastUpdatedTime = it.lastUpdatedTime,
                )
            }

            backup.readingState.imageHistory.forEach {
                imageHistoryQueries.upsert(
                    postId = it.postId,
                    threadId = it.threadId,
                    pageIndex = it.pageIndex,
                    totalPages = it.totalPages,
                    firstVisibleItemIndex = it.firstVisibleItemIndex,
                    firstVisibleItemOffset = it.firstVisibleItemOffset,
                    lastVisitTime = it.lastVisitTime,
                )
            }

            backup.readingState.tagMangaHistory.forEach {
                tagHistoryQueries.upsert(
                    tagId = it.tagId,
                    tagName = it.tagName,
                    tagPage = it.tagPage,
                    threadId = it.threadId,
                    threadTitle = it.threadTitle,
                    threadImagePageIndex = it.threadImagePageIndex,
                    threadImageTotalPages = it.threadImageTotalPages,
                    firstVisibleItemIndex = it.firstVisibleItemIndex,
                    firstVisibleItemOffset = it.firstVisibleItemOffset,
                    lastVisitTime = it.lastVisitTime,
                    coverUrl = it.coverUrl,
                )
            }

            backup.readingState.readingTimeStats.forEach {
                readingTimeQueries.upsert(it.dateKey, it.durationMillis, it.updatedAt)
            }
        }

        return BackupRepository.RestoreSummary(
            favorites = backup.favorites.items.size,
            settings = backup.settings.size,
            notes = backup.notes.size,
            bookmarks = backup.bookmarks.size,
            readingHistory = backup.readingState.threadHistory.size +
                backup.readingState.imageHistory.size +
                backup.readingState.tagMangaHistory.size,
        )
    }

    private fun clearRestorableData() {
        db.transaction {
            itemCategoryQueries.deleteAll()
            itemCollectionQueries.deleteAll()
            collectionQueries.deleteAll()
            categoryQueries.deleteAll()
            itemQueries.deleteAll()
            noteQueries.deleteAll()
            bookmarkQueries.deleteAll()
            readingQueries.deleteAll()
            imageHistoryQueries.deleteAll()
            tagHistoryQueries.deleteAll()
            readingTimeQueries.deleteAll()
        }
    }

    private fun migrate(backup: YamiboBackupFile): YamiboBackupFile {
        if (backup.schemaVersion > CURRENT_BACKUP_SCHEMA_VERSION) {
            throw IllegalArgumentException("備份版本高於目前 App 支援版本，無法還原")
        }
        return backup
    }

    private fun restoreSettings(settings: List<BackupSetting>) {
        settings.filterNot { shouldSkipSetting(it.key) }.forEach { setting ->
            when (setting.type) {
                BackupSettingType.Int -> settingsStore.putInt(setting.key, setting.value.toIntOrNull() ?: return@forEach)
                BackupSettingType.Float -> settingsStore.putFloat(setting.key, setting.value.toFloatOrNull() ?: return@forEach)
                BackupSettingType.Bool -> settingsStore.putBoolean(setting.key, setting.value.toBooleanStrictOrNull() ?: return@forEach)
                BackupSettingType.String,
                BackupSettingType.Enum -> settingsStore.putString(setting.key, setting.value)
            }
        }
    }

    private fun settingToBackup(setting: SettingItem<*>): BackupSetting? {
        val value = setting.getValue()
        return when (setting) {
            is IntSetting -> BackupSetting(setting.storageKey, BackupSettingType.Int, value.toString())
            is FloatSetting -> BackupSetting(setting.storageKey, BackupSettingType.Float, value.toString())
            is BoolSetting -> BackupSetting(setting.storageKey, BackupSettingType.Bool, value.toString())
            is StringSetting -> BackupSetting(setting.storageKey, BackupSettingType.String, value.toString())
            is EnumSetting<*> -> BackupSetting(setting.storageKey, BackupSettingType.Enum, value.toString())
            else -> null
        }
    }

    private fun shouldSkipSetting(key: String): Boolean {
        val blockedSuffixes = listOf(
            "signpagehtmlcache",
            "signpagehtmlcacheupdatedat",
            "favoriteupdatehiddenrunid",
            "appupdatelastcheckat",
            "appupdateignoredversioncode",
            "backupfolderuri",
            "backuplastautobackupat",
        )
        val normalized = key.replace(".", "").lowercase()
        return blockedSuffixes.any(normalized::endsWith)
    }

    private fun backupFileName(nowMillis: Long, automatic: Boolean, customName: String? = null): String {
        if (!automatic) {
            normalizedManualBackupFileName(customName)?.let { return it }
        }
        val date = currentLocalDateKeyAt(nowMillis).replace("-", "")
        val seconds = ((nowMillis + UTC_PLUS_8_OFFSET_MILLIS) % DAY_MILLIS).floorDiv(1000L)
        val hour = (seconds / 3600L).toString().padStart(2, '0')
        val minute = ((seconds % 3600L) / 60L).toString().padStart(2, '0')
        val second = (seconds % 60L).toString().padStart(2, '0')
        val suffix = if (automatic) "-autobackup" else ""
        return "YamiboApp-$date-$hour$minute$second$suffix.yamibobak"
    }

    private fun normalizedManualBackupFileName(customName: String?): String? {
        val raw = customName?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val sanitized = raw
            .replace(Regex("""[\\/:*?"<>|\r\n\t]+"""), "_")
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '.', '_')
            .take(96)
            .trim(' ', '.', '_')
            .takeIf { it.isNotBlank() }
            ?: return null
        val withExtension = if (sanitized.endsWith(".yamibobak", ignoreCase = true)) {
            sanitized
        } else {
            "$sanitized.yamibobak"
        }
        return if (withExtension.endsWith("-autobackup.yamibobak", ignoreCase = true)) {
            withExtension.replace(Regex("""-autobackup\.yamibobak$""", RegexOption.IGNORE_CASE), ".yamibobak")
        } else {
            withExtension
        }
    }

    private fun BackupFavoriteItem.forNameSafe(): String? = forumName

    private companion object {
        const val DAY_MILLIS = 86_400_000L
        const val UTC_PLUS_8_OFFSET_MILLIS = 8L * 60L * 60L * 1000L
    }
}

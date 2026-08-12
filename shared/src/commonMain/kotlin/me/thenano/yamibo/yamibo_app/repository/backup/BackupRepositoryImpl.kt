package me.thenano.yamibo.yamibo_app.repository.backup

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import me.thenano.yamibo.yamibo_app.Database
import me.thenano.yamibo.yamibo_app.repository.BackupRepository
import me.thenano.yamibo.yamibo_app.repository.settings.core.*
import me.thenano.yamibo.yamibo_app.store.settings.SettingsStore
import me.thenano.yamibo.yamibo_app.util.time.currentLocalDateKeyAt
import me.thenano.yamibo.yamibo_app.util.time.currentTimeMillis
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncIdentityGenerator
import me.thenano.yamibo.yamibo_app.repository.rss.rssSearchSubscriptionSyncId

class BackupRepositoryImpl(
    private val db: Database,
    private val settingsStore: SettingsStore,
    private val settingsRegistries: List<SettingsRegistry>,
    private val storageProvider: BackupStorageProvider,
    private val appVersionCode: Int,
    private val restoreFailureInjector: ((String) -> Unit)? = null,
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
    private val tagCatalogHistoryQueries = db.tagCatalogReadingHistoryQueries
    private val rssSearchHistoryQueries = db.rssSearchReadingHistoryQueries
    private val rssCatalogHistoryQueries = db.rssCatalogReadingHistoryQueries
    private val rssSubscriptionQueries = db.rssSearchSubscriptionQueries
    private val rssPageCacheQueries = db.rssSearchPageCacheQueries
    private val rssSubscriptionResultQueries = db.rssSearchSubscriptionResultQueries
    private val chapterStateQueries = db.localChapterStateQueries
    private val readingTimeQueries = db.readingTimeStatQueries
    private val updateEventQueries = db.favoriteUpdateEventQueries
    private val updateFidFilterQueries = db.favoriteUpdateFidFilterQueries
    private val updateCategoryFilterQueries = db.favoriteUpdateCategoryFilterQueries
    private val updateFidChoiceQueries = db.favoriteUpdateFidChoiceQueries
    private val updateCategoryChoiceQueries = db.favoriteUpdateCategoryChoiceQueries

    suspend fun createBackup(automatic: Boolean): Result<BackupRepository.BackupFileInfo> =
        createBackup(automatic = automatic, customName = null)

    override suspend fun createBackup(
        automatic: Boolean,
        customName: String?,
    ): Result<BackupRepository.BackupFileInfo> = runCatching {
        val now = currentTimeMillis()
        val backup = createSnapshot(now, PortableSnapshotScope.LocalBackup)
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
        val plan = buildRestorePlan(migrated)
        restoreSnapshot(plan, mode)
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

    internal fun createAppSyncSnapshot(): YamiboBackupFile =
        createSnapshot(currentTimeMillis(), PortableSnapshotScope.AppSync)

    internal fun createSnapshot(
        now: Long,
        scope: PortableSnapshotScope,
    ): YamiboBackupFile {
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
                    BackupFavoriteCategory(
                        localId = it.id,
                        syncId = it.syncId,
                        name = it.name,
                        sortOrder = it.sortOrder,
                        createdAt = it.createdAt,
                        updatedAt = it.updatedAt,
                    )
                },
                collections = collections.map {
                    BackupFavoriteCollection(
                        localId = it.id,
                        syncId = it.syncId,
                        categoryLocalId = it.categoryId,
                        name = it.name,
                        colorKey = it.colorKey,
                        sortOrder = it.sortOrder,
                        createdAt = it.createdAt,
                        updatedAt = it.updatedAt,
                    )
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
                rssSubscriptions = rssSubscriptionQueries.getAll().executeAsList().map {
                    BackupRssSearchSubscription(
                        localId = it.id,
                        syncId = rssSearchSubscriptionSyncId(it.query, it.forumId),
                        title = it.title,
                        query = it.query,
                        forumId = it.forumId,
                        forumName = it.forumName,
                        enabled = it.enabled != 0L,
                        createdAt = it.createdAt,
                        updatedAt = it.updatedAt,
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
                threadHistory = readingQueries.getAllForBackup().executeAsList().map {
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
                tagCatalogHistory = tagCatalogHistoryQueries.getAll().executeAsList().map {
                    BackupTagCatalogReadingHistory(
                        tagId = it.tagId,
                        tagName = it.tagName,
                        tagPage = it.tagPage,
                        threadId = it.threadId,
                        threadTitle = it.threadTitle,
                        threadPage = it.threadPage,
                        postId = it.postId,
                        postTitle = it.postTitle,
                        authorId = it.authorId,
                        anchorPostId = it.anchorPostId,
                        anchorPostRatio = it.anchorPostRatio,
                        anchorBlockId = it.anchorBlockId,
                        anchorBlockType = it.anchorBlockType,
                        anchorBlockRatio = it.anchorBlockRatio,
                        viewportHeight = it.viewportHeight,
                        firstVisibleItemIndex = it.firstVisibleItemIndex,
                        firstVisibleItemOffset = it.firstVisibleItemOffset,
                        lastVisitTime = it.lastVisitTime,
                        coverUrl = it.coverUrl,
                    )
                },
                rssSearchHistory = rssSearchHistoryQueries.getAll().executeAsList().map {
                    BackupRssSearchReadingHistory(
                        subscriptionId = it.subscriptionId,
                        subscriptionTitle = it.subscriptionTitle,
                        subscriptionQuery = it.subscriptionQuery,
                        subscriptionPage = it.subscriptionPage,
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
                rssCatalogHistory = rssCatalogHistoryQueries.getAll().executeAsList().map {
                    BackupRssCatalogReadingHistory(
                        subscriptionId = it.subscriptionId,
                        subscriptionTitle = it.subscriptionTitle,
                        subscriptionQuery = it.subscriptionQuery,
                        subscriptionPage = it.subscriptionPage,
                        threadId = it.threadId,
                        threadTitle = it.threadTitle,
                        threadPage = it.threadPage,
                        postId = it.postId,
                        postTitle = it.postTitle,
                        authorId = it.authorId,
                        anchorPostId = it.anchorPostId,
                        anchorPostRatio = it.anchorPostRatio,
                        anchorBlockId = it.anchorBlockId,
                        anchorBlockType = it.anchorBlockType,
                        anchorBlockRatio = it.anchorBlockRatio,
                        viewportHeight = it.viewportHeight,
                        firstVisibleItemIndex = it.firstVisibleItemIndex,
                        firstVisibleItemOffset = it.firstVisibleItemOffset,
                        lastVisitTime = it.lastVisitTime,
                        coverUrl = it.coverUrl,
                    )
                },
                chapterState = if (scope == PortableSnapshotScope.LocalBackup) {
                    chapterStateQueries.getAll().executeAsList().map {
                    BackupChapterState(
                        targetType = it.targetType,
                        parentId = it.parentId,
                        targetId = it.targetId,
                        title = it.title,
                        read = it.read != 0L,
                        progressPercent = it.progressPercent,
                        lastPageIndex = it.lastPageIndex,
                        totalPages = it.totalPages,
                        updatedAt = it.updatedAt,
                    )
                    }
                } else {
                    emptyList()
                },
                readingTimeStats = readingTimeQueries.getAll().executeAsList().map {
                    BackupReadingTimeStat(it.dateKey, it.durationMillis, it.updatedAt)
                },
            ),
            favoriteUpdates = createFavoriteUpdateSnapshot(),
        )
    }

    private fun createFavoriteUpdateSnapshot(): BackupFavoriteUpdates {
        val categorySyncIds = categoryQueries.getAll().executeAsList()
            .associate { it.id to it.syncId }
        return BackupFavoriteUpdates(
            events = updateEventQueries.getAll().executeAsList().map { event ->
                val detailIds = event.detailIds.csvLongs()
                val identity = favoriteUpdateEventIdentity(
                    targetType = event.targetType,
                    targetId = event.targetId,
                    authorId = event.authorId,
                    mode = event.mode,
                    detailIds = detailIds,
                    ambiguous = event.ambiguous != 0L,
                    detectedAt = event.detectedAt,
                    summary = event.summary,
                    title = event.title,
                    sourceDiscriminator = event.sourceDiscriminator,
                )
                BackupFavoriteUpdateEvent(
                    syncId = event.syncId.ifBlank { identity.syncId },
                    sourceFingerprint = event.sourceFingerprint.ifBlank { identity.sourceFingerprint },
                    sourceDiscriminator = event.sourceDiscriminator.ifBlank { identity.sourceDiscriminator },
                    targetType = event.targetType,
                    targetId = event.targetId,
                    authorId = event.authorId,
                    fid = event.fid,
                    forumName = event.forumName,
                    title = event.title,
                    latestPostTitle = event.latestPostTitle,
                    mode = event.mode,
                    summary = event.summary,
                    detailIds = detailIds.distinct().sorted(),
                    coverUrl = event.coverUrl,
                    detectedAt = event.detectedAt,
                    readAt = event.readAt,
                    dismissedAt = event.dismissedAt,
                    ambiguous = event.ambiguous != 0L,
                )
            },
            fidFilters = updateFidChoiceQueries.getAll().executeAsList()
                .map { BackupFavoriteUpdateFidFilter(it.fid, it.enabled != 0L) }
                .ifEmpty {
                    updateFidFilterQueries.getAll().executeAsList().map {
                        BackupFavoriteUpdateFidFilter(it.fid, it.enabled != 0L)
                    }
                },
            categoryFilters = updateCategoryChoiceQueries.getAll().executeAsList()
                .map { BackupFavoriteUpdateCategoryFilter(it.categorySyncId, it.enabled != 0L) }
                .ifEmpty {
                    updateCategoryFilterQueries.getAll().executeAsList().mapNotNull {
                        val syncId = categorySyncIds[it.categoryId] ?: return@mapNotNull null
                        BackupFavoriteUpdateCategoryFilter(syncId, it.enabled != 0L)
                    }
                },
        )
    }

    private data class RestorePlan(
        val backup: YamiboBackupFile,
        val skippedCategoryFilters: Int,
    )

    private fun buildRestorePlan(backup: YamiboBackupFile): RestorePlan {
        fun requireBounded(name: String, size: Int) {
            require(size <= MAX_RECORDS_PER_DOMAIN) {
                "$name 筆數超過安全限制 $MAX_RECORDS_PER_DOMAIN"
            }
        }

        require(backup.schemaVersion in 1..CURRENT_BACKUP_SCHEMA_VERSION) {
            "不支援的備份版本：${backup.schemaVersion}"
        }
        val favorites = backup.favorites
        val reading = backup.readingState
        val updates = backup.favoriteUpdates
        listOf(
            "收藏分類" to favorites.categories.size,
            "收藏集合" to favorites.collections.size,
            "收藏項目" to favorites.items.size,
            "RSS 訂閱" to favorites.rssSubscriptions.size,
            "設定" to backup.settings.size,
            "筆記" to backup.notes.size,
            "書籤" to backup.bookmarks.size,
            "主題閱讀紀錄" to reading.threadHistory.size,
            "圖片閱讀紀錄" to reading.imageHistory.size,
            "標籤漫畫紀錄" to reading.tagMangaHistory.size,
            "標籤目錄紀錄" to reading.tagCatalogHistory.size,
            "RSS 搜尋紀錄" to reading.rssSearchHistory.size,
            "RSS 目錄紀錄" to reading.rssCatalogHistory.size,
            "章節進度" to reading.chapterState.size,
            "閱讀時間" to reading.readingTimeStats.size,
            "更新紀錄" to updates.events.size,
            "更新篩選" to updates.fidFilters.size + updates.categoryFilters.size,
        ).forEach { (name, size) -> requireBounded(name, size) }

        requireUnique("收藏分類 localId", favorites.categories.map { it.localId })
        requireUnique("收藏集合 localId", favorites.collections.map { it.localId })
        requireUnique("收藏項目 localId", favorites.items.map { it.localId })
        requireUnique("RSS 訂閱 localId", favorites.rssSubscriptions.map { it.localId })
        requireUnique("RSS 訂閱 syncId", favorites.rssSubscriptions.map { it.syncId })
        requireUnique(
            "收藏項目 identity",
            favorites.items.map { "${it.targetType}:${it.targetId}:${it.authorId}" },
        )
        favorites.rssSubscriptions.forEach {
            require(it.syncId == rssSearchSubscriptionSyncId(it.query, it.forumId)) {
                "RSS 訂閱 identity 驗證失敗"
            }
            require(it.query.isNotBlank()) { "RSS 訂閱搜尋字不可為空" }
            require(it.title.isNotBlank()) { "RSS 訂閱標題不可為空" }
            require(it.createdAt >= 0L && it.updatedAt >= 0L) { "RSS 訂閱時間無效" }
        }
        requireUnique(
            "主題閱讀 identity",
            reading.threadHistory.map { "${it.threadId}:${it.threadType}:${it.authorId}:${it.historyOrigin}" },
        )
        requireUnique("圖片閱讀 identity", reading.imageHistory.map { it.postId })
        requireUnique("標籤漫畫 identity", reading.tagMangaHistory.map { it.tagId })
        requireUnique("標籤目錄 identity", reading.tagCatalogHistory.map { it.tagId })
        requireUnique("RSS 搜尋 identity", reading.rssSearchHistory.map { it.subscriptionId })
        requireUnique("RSS 目錄 identity", reading.rssCatalogHistory.map { it.subscriptionId })
        requireUnique(
            "章節 identity",
            reading.chapterState.map { "${it.targetType}:${it.parentId}:${it.targetId}" },
        )
        requireUnique("閱讀時間 identity", reading.readingTimeStats.map { it.dateKey })
        requireUnique("更新 identity", updates.events.map { it.syncId })
        requireUnique("FID 篩選 identity", updates.fidFilters.map { it.fid })
        requireUnique("分類篩選 identity", updates.categoryFilters.map { it.categorySyncId })

        val categoryIds = favorites.categories.mapTo(hashSetOf()) { it.localId }
        val collectionIds = favorites.collections.mapTo(hashSetOf()) { it.localId }
        val itemIds = favorites.items.mapTo(hashSetOf()) { it.localId }
        favorites.collections.forEach {
            require(it.categoryLocalId in categoryIds) { "收藏集合引用不存在的分類" }
        }
        favorites.itemCategories.forEach {
            require(it.itemLocalId in itemIds && it.categoryLocalId in categoryIds) {
                "收藏項目與分類關聯無法解析"
            }
        }
        favorites.itemCollections.forEach {
            require(it.itemLocalId in itemIds && it.collectionLocalId in collectionIds) {
                "收藏項目與集合關聯無法解析"
            }
        }
        backup.settings.forEach(::validateSetting)
        reading.chapterState.forEach {
            require(it.progressPercent in 0L..100L) { "章節進度必須介於 0 到 100" }
            require(it.updatedAt >= 0L) { "章節進度時間無效" }
        }
        val normalizedEvents = updates.events.map { event ->
            require(event.detectedAt >= 0L) { "更新紀錄時間無效" }
            val expected = favoriteUpdateEventIdentity(
                targetType = event.targetType,
                targetId = event.targetId,
                authorId = event.authorId,
                mode = event.mode,
                detailIds = event.detailIds,
                ambiguous = event.ambiguous,
                detectedAt = event.detectedAt,
                summary = event.summary,
                title = event.title,
                sourceDiscriminator = event.sourceDiscriminator,
            )
            require(event.syncId == expected.syncId && event.sourceFingerprint == expected.sourceFingerprint) {
                "更新紀錄 identity 驗證失敗"
            }
            event.copy(sourceDiscriminator = expected.sourceDiscriminator)
        }

        val categorySyncIds = favorites.categories.mapNotNullTo(hashSetOf()) { it.syncId }
        val validCategoryFilters = updates.categoryFilters.filter { it.categorySyncId in categorySyncIds }
        return RestorePlan(
            backup = backup.copy(
                favoriteUpdates = updates.copy(
                    events = normalizedEvents,
                    categoryFilters = validCategoryFilters,
                ),
            ),
            skippedCategoryFilters = updates.categoryFilters.size - validCategoryFilters.size,
        )
    }

    private fun <T> requireUnique(name: String, values: List<T>) {
        require(values.size == values.toSet().size) { "$name 包含重複值" }
    }

    private fun validateSetting(setting: BackupSetting) {
        require(setting.key.isNotBlank()) { "設定 key 不可為空" }
        when (setting.type) {
            BackupSettingType.Int -> requireNotNull(setting.value.toIntOrNull()) { "設定整數格式錯誤" }
            BackupSettingType.Float -> requireNotNull(setting.value.toFloatOrNull()) { "設定浮點格式錯誤" }
            BackupSettingType.Bool -> requireNotNull(setting.value.toBooleanStrictOrNull()) { "設定布林格式錯誤" }
            BackupSettingType.String,
            BackupSettingType.Enum -> Unit
        }
    }

    private fun restoreSnapshot(
        plan: RestorePlan,
        mode: BackupRepository.RestoreMode,
    ): BackupRepository.RestoreSummary {
        val backup = plan.backup
        val settingsToTouch = if (mode == BackupRepository.RestoreMode.Overwrite) {
            val registered = settingsRegistries.flatMap { it.exportableSettingItems }
                .filterNot { shouldSkipSetting(it.storageKey) }
                .mapNotNull(::settingToBackup)
            (registered + backup.settings.filterNot { shouldSkipSetting(it.key) })
                .distinctBy { it.key }
        } else {
            backup.settings.filterNot { shouldSkipSetting(it.key) }
        }
        val settingsSnapshot = captureSettings(settingsToTouch)

        val categoryIdMap = mutableMapOf<Long, Long>()
        val collectionIdMap = mutableMapOf<Long, Long>()
        val itemIdMap = mutableMapOf<Long, Long>()
        val rssSubscriptionIdMap = mutableMapOf<Long, Long>()

        try {
            db.transaction {
                if (mode == BackupRepository.RestoreMode.Overwrite) {
                    clearRestorableDataWithinTransaction()
                    settingsToTouch.forEach { settingsStore.remove(it.key) }
                }
                restoreFailureInjector?.invoke("after-clear")
                restoreSettings(backup.settings)
                restoreFailureInjector?.invoke("after-settings")

            backup.favorites.categories.forEach { category ->
                val existing = if (mode == BackupRepository.RestoreMode.Merge) {
                    category.syncId?.let { categoryQueries.getBySyncId(it).executeAsOneOrNull() }
                        ?: categoryQueries.getAll().executeAsList()
                            .firstOrNull { it.name.trim().equals(category.name.trim(), ignoreCase = true) }
                } else null
                val targetId = existing?.id ?: run {
                    categoryQueries.insertCategory(category.name, category.sortOrder, category.createdAt, category.updatedAt)
                    categoryQueries.getFirstByName(category.name).executeAsOne().id.also { id ->
                        categoryQueries.setSyncId(
                            category.syncId ?: SyncIdentityGenerator.stableEntityId().value,
                            id,
                        )
                    }
                }
                if (existing != null) {
                    categoryQueries.setSyncId(
                        category.syncId ?: SyncIdentityGenerator.stableEntityId().value,
                        existing.id,
                    )
                }
                categoryIdMap[category.localId] = targetId
            }

            backup.favorites.collections.forEach { collection ->
                val mappedCategoryId = categoryIdMap[collection.categoryLocalId] ?: return@forEach
                val existing = if (mode == BackupRepository.RestoreMode.Merge) {
                    collection.syncId?.let { collectionQueries.getBySyncId(it).executeAsOneOrNull() }
                        ?: collectionQueries.getByCategoryId(mappedCategoryId).executeAsList()
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
                    collectionQueries.getLatestByCategoryId(mappedCategoryId).executeAsOne().id.also {
                        collectionQueries.setSyncId(
                            collection.syncId ?: SyncIdentityGenerator.stableEntityId().value,
                            it,
                        )
                    }
                }
                if (existing != null) {
                    collectionQueries.setSyncId(
                        collection.syncId ?: SyncIdentityGenerator.stableEntityId().value,
                        targetId,
                    )
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

            backup.favorites.rssSubscriptions.forEach { subscription ->
                val existing = if (mode == BackupRepository.RestoreMode.Merge) {
                    rssSubscriptionQueries.getAll().executeAsList().firstOrNull {
                        rssSearchSubscriptionSyncId(it.query, it.forumId) == subscription.syncId
                    }
                } else {
                    null
                }
                val targetId = existing?.id ?: run {
                    rssSubscriptionQueries.insertSubscription(
                        title = subscription.title,
                        query = subscription.query,
                        forumId = subscription.forumId,
                        forumName = subscription.forumName,
                        enabled = if (subscription.enabled) 1 else 0,
                        createdAt = subscription.createdAt,
                        updatedAt = subscription.updatedAt,
                        lastRefreshStartedAt = null,
                        lastRefreshFinishedAt = null,
                        lastRefreshStatus = null,
                        lastRefreshMessage = null,
                        lastSearchId = null,
                        lastTotalCount = 0,
                    )
                    rssSubscriptionQueries.lastInsertedId().executeAsOne()
                }
                if (existing != null && subscription.updatedAt > existing.updatedAt) {
                    rssSubscriptionQueries.rename(
                        subscription.title,
                        subscription.updatedAt,
                        targetId,
                    )
                    rssSubscriptionQueries.setEnabled(
                        if (subscription.enabled) 1 else 0,
                        subscription.updatedAt,
                        targetId,
                    )
                }
                rssSubscriptionIdMap[subscription.localId] = targetId
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

            backup.notes.forEach note@{
                val existing = noteQueries.getByTarget(it.targetType, it.targetId, it.authorId)
                    .executeAsOneOrNull()
                if (mode == BackupRepository.RestoreMode.Merge &&
                    existing != null &&
                    existing.updatedAt >= it.updatedAt
                ) {
                    return@note
                }
                noteQueries.upsert(it.targetType, it.targetId, it.authorId, it.content, it.createdAt, it.updatedAt)
            }

            backup.bookmarks.forEach bookmark@{
                val existing = bookmarkQueries.getByTarget(it.targetType, it.parentId, it.targetId)
                    .executeAsOneOrNull()
                if (mode == BackupRepository.RestoreMode.Merge &&
                    existing != null &&
                    existing.updatedAt >= it.updatedAt
                ) {
                    return@bookmark
                }
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

            backup.readingState.threadHistory.forEach history@{
                val existing = readingQueries.getAllForBackup().executeAsList().firstOrNull { row ->
                    row.threadId == it.threadId &&
                        row.threadType == it.threadType &&
                        row.authorId == it.authorId &&
                        row.historyOrigin == it.historyOrigin
                }
                if (mode == BackupRepository.RestoreMode.Merge &&
                    existing != null &&
                    existing.lastVisitTime >= it.lastVisitTime
                ) {
                    return@history
                }
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

            backup.readingState.imageHistory.forEach history@{
                val existing = imageHistoryQueries.getByPostId(it.postId).executeAsOneOrNull()
                if (mode == BackupRepository.RestoreMode.Merge &&
                    existing != null &&
                    existing.lastVisitTime >= it.lastVisitTime
                ) {
                    return@history
                }
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

            backup.readingState.tagMangaHistory.forEach history@{
                val existing = tagHistoryQueries.getByTagId(it.tagId).executeAsOneOrNull()
                if (mode == BackupRepository.RestoreMode.Merge &&
                    existing != null &&
                    existing.lastVisitTime >= it.lastVisitTime
                ) {
                    return@history
                }
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

            backup.readingState.tagCatalogHistory.forEach history@{
                val existing = tagCatalogHistoryQueries.getByTagId(it.tagId).executeAsOneOrNull()
                if (mode == BackupRepository.RestoreMode.Merge &&
                    existing != null &&
                    existing.lastVisitTime >= it.lastVisitTime
                ) {
                    return@history
                }
                tagCatalogHistoryQueries.upsert(
                    tagId = it.tagId,
                    tagName = it.tagName,
                    tagPage = it.tagPage,
                    threadId = it.threadId,
                    threadTitle = it.threadTitle,
                    threadPage = it.threadPage,
                    postId = it.postId,
                    postTitle = it.postTitle,
                    authorId = it.authorId,
                    anchorPostId = it.anchorPostId,
                    anchorPostRatio = it.anchorPostRatio,
                    anchorBlockId = it.anchorBlockId,
                    anchorBlockType = it.anchorBlockType,
                    anchorBlockRatio = it.anchorBlockRatio,
                    viewportHeight = it.viewportHeight,
                    firstVisibleItemIndex = it.firstVisibleItemIndex,
                    firstVisibleItemOffset = it.firstVisibleItemOffset,
                    lastVisitTime = it.lastVisitTime,
                    coverUrl = it.coverUrl,
                )
            }

            backup.readingState.rssSearchHistory.forEach history@{
                val subscriptionId = rssSubscriptionIdMap[it.subscriptionId] ?: it.subscriptionId
                val existing = rssSearchHistoryQueries.getBySubscriptionId(subscriptionId)
                    .executeAsOneOrNull()
                if (mode == BackupRepository.RestoreMode.Merge &&
                    existing != null &&
                    existing.lastVisitTime >= it.lastVisitTime
                ) {
                    return@history
                }
                rssSearchHistoryQueries.upsert(
                    subscriptionId = subscriptionId,
                    subscriptionTitle = it.subscriptionTitle,
                    subscriptionQuery = it.subscriptionQuery,
                    subscriptionPage = it.subscriptionPage,
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

            backup.readingState.rssCatalogHistory.forEach history@{
                val subscriptionId = rssSubscriptionIdMap[it.subscriptionId] ?: it.subscriptionId
                val existing = rssCatalogHistoryQueries.getBySubscriptionId(subscriptionId)
                    .executeAsOneOrNull()
                if (mode == BackupRepository.RestoreMode.Merge &&
                    existing != null &&
                    existing.lastVisitTime >= it.lastVisitTime
                ) {
                    return@history
                }
                rssCatalogHistoryQueries.upsert(
                    subscriptionId = subscriptionId,
                    subscriptionTitle = it.subscriptionTitle,
                    subscriptionQuery = it.subscriptionQuery,
                    subscriptionPage = it.subscriptionPage,
                    threadId = it.threadId,
                    threadTitle = it.threadTitle,
                    threadPage = it.threadPage,
                    postId = it.postId,
                    postTitle = it.postTitle,
                    authorId = it.authorId,
                    anchorPostId = it.anchorPostId,
                    anchorPostRatio = it.anchorPostRatio,
                    anchorBlockId = it.anchorBlockId,
                    anchorBlockType = it.anchorBlockType,
                    anchorBlockRatio = it.anchorBlockRatio,
                    viewportHeight = it.viewportHeight,
                    firstVisibleItemIndex = it.firstVisibleItemIndex,
                    firstVisibleItemOffset = it.firstVisibleItemOffset,
                    lastVisitTime = it.lastVisitTime,
                    coverUrl = it.coverUrl,
                )
            }

            backup.readingState.chapterState.forEach chapter@{
                val existing = chapterStateQueries.getByTarget(it.targetType, it.parentId, it.targetId)
                    .executeAsOneOrNull()
                if (mode == BackupRepository.RestoreMode.Merge &&
                    existing != null &&
                    existing.updatedAt >= it.updatedAt
                ) {
                    return@chapter
                }
                chapterStateQueries.upsert(
                    targetType = it.targetType,
                    parentId = it.parentId,
                    targetId = it.targetId,
                    title = it.title,
                    read = if (it.read) 1L else 0L,
                    progressPercent = it.progressPercent,
                    lastPageIndex = it.lastPageIndex,
                    totalPages = it.totalPages,
                    updatedAt = it.updatedAt,
                )
            }

            val localReadingTimes = readingTimeQueries.getAll().executeAsList().associateBy { it.dateKey }
            backup.readingState.readingTimeStats.forEach stat@{
                val existing = localReadingTimes[it.dateKey]
                if (mode == BackupRepository.RestoreMode.Merge &&
                    existing != null &&
                    existing.updatedAt >= it.updatedAt
                ) {
                    return@stat
                }
                readingTimeQueries.upsert(it.dateKey, it.durationMillis, it.updatedAt)
            }

            restoreFavoriteUpdates(
                backup = backup,
                categoryIdMap = categoryIdMap,
                mode = mode,
            )
            restoreFailureInjector?.invoke("before-commit")
            }
        } catch (error: Throwable) {
            runCatching { restoreCapturedSettings(settingsSnapshot) }
                .exceptionOrNull()
                ?.let(error::addSuppressed)
            throw error
        }

        return BackupRepository.RestoreSummary(
            favorites = backup.favorites.items.size + backup.favorites.rssSubscriptions.size,
            settings = backup.settings.size,
            notes = backup.notes.size,
            bookmarks = backup.bookmarks.size,
            readingHistory = backup.readingState.threadHistory.size +
                backup.readingState.imageHistory.size +
                backup.readingState.tagMangaHistory.size +
                backup.readingState.tagCatalogHistory.size +
                backup.readingState.rssSearchHistory.size +
                backup.readingState.rssCatalogHistory.size +
                backup.readingState.chapterState.size +
                backup.readingState.readingTimeStats.size,
            updateRecords = backup.favoriteUpdates.events.size,
            skippedRecords = plan.skippedCategoryFilters,
        )
    }

    private fun clearRestorableDataWithinTransaction() {
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
            tagCatalogHistoryQueries.deleteAll()
            rssSearchHistoryQueries.deleteAll()
            rssCatalogHistoryQueries.deleteAll()
            rssSubscriptionQueries.getAll().executeAsList().forEach {
                rssPageCacheQueries.deleteBySubscription(it.id)
                rssSubscriptionResultQueries.deleteBySubscription(it.id)
                rssSubscriptionQueries.deleteById(it.id)
            }
            chapterStateQueries.deleteAll()
            readingTimeQueries.deleteAll()
            updateEventQueries.deleteAll()
            updateFidChoiceQueries.deleteAll()
            updateCategoryChoiceQueries.deleteAll()
            updateFidFilterQueries.deleteAll()
            updateCategoryFilterQueries.deleteAll()
    }

    private fun restoreFavoriteUpdates(
        backup: YamiboBackupFile,
        categoryIdMap: Map<Long, Long>,
        mode: BackupRepository.RestoreMode,
    ) {
        val existingEvents = updateEventQueries.getAll().executeAsList().associateBy { event ->
            favoriteUpdateEventIdentity(
                targetType = event.targetType,
                targetId = event.targetId,
                authorId = event.authorId,
                mode = event.mode,
                detailIds = event.detailIds.csvLongs(),
                ambiguous = event.ambiguous != 0L,
                detectedAt = event.detectedAt,
                summary = event.summary,
                title = event.title,
                sourceDiscriminator = event.sourceDiscriminator,
            ).syncId
        }
        backup.favoriteUpdates.events.forEach { event ->
            val existing = existingEvents[event.syncId]
            if (existing == null) {
                updateEventQueries.upsertBySyncId(
                    targetType = event.targetType,
                    targetId = event.targetId,
                    authorId = event.authorId,
                    fid = event.fid,
                    forumName = event.forumName,
                    title = event.title,
                    latestPostTitle = event.latestPostTitle,
                    mode = event.mode,
                    summary = event.summary,
                    detailIds = event.detailIds.distinct().sorted().joinToString(","),
                    coverUrl = event.coverUrl,
                    detectedAt = event.detectedAt,
                    readAt = event.readAt,
                    dismissedAt = event.dismissedAt,
                    ambiguous = if (event.ambiguous) 1L else 0L,
                    syncId = event.syncId,
                    sourceFingerprint = event.sourceFingerprint,
                    sourceDiscriminator = event.sourceDiscriminator,
                )
            } else if (mode == BackupRepository.RestoreMode.Merge) {
                laterNonNull(existing.readAt, event.readAt)?.let {
                    if (it != existing.readAt) updateEventQueries.markRead(it, existing.id)
                }
                laterNonNull(existing.dismissedAt, event.dismissedAt)?.let {
                    if (it != existing.dismissedAt) updateEventQueries.dismiss(it, existing.id)
                }
            }
        }

        val now = currentTimeMillis()
        val existingFidFilters = updateFidFilterQueries.getAll().executeAsList().associateBy { it.fid }
        val fidCounts = backup.favorites.items.groupingBy { it.forumId }.eachCount()
        backup.favoriteUpdates.fidFilters.forEach { filter ->
            updateFidChoiceQueries.upsertChoice(
                fid = filter.fid,
                enabled = if (filter.enabled) 1L else 0L,
                winnerOperationId = null,
                updatedAt = now,
            )
            val current = existingFidFilters[filter.fid]
            val matchingItem = backup.favorites.items.firstOrNull { it.forumId == filter.fid }
            updateFidFilterQueries.upsertFilter(
                fid = filter.fid,
                forumName = matchingItem?.forumName ?: current?.forumName ?: "FID ${filter.fid}",
                enabled = if (filter.enabled) 1L else 0L,
                itemCount = (fidCounts[filter.fid] ?: current?.itemCount?.toInt() ?: 0).toLong(),
                updatedAt = now,
            )
        }

        val categoryBySyncId = backup.favorites.categories
            .mapNotNull { category ->
                val syncId = category.syncId ?: return@mapNotNull null
                val localId = categoryIdMap[category.localId] ?: return@mapNotNull null
                syncId to (localId to category.name)
            }.toMap()
        val categoryCounts = backup.favorites.itemCategories
            .groupingBy { it.categoryLocalId }
            .eachCount()
        backup.favoriteUpdates.categoryFilters.forEach { filter ->
            val (localId, name) = categoryBySyncId[filter.categorySyncId] ?: return@forEach
            updateCategoryChoiceQueries.upsertChoice(
                categorySyncId = filter.categorySyncId,
                enabled = if (filter.enabled) 1L else 0L,
                winnerOperationId = null,
                updatedAt = now,
            )
            updateCategoryFilterQueries.upsertFilter(
                categoryId = localId,
                categoryName = name,
                enabled = if (filter.enabled) 1L else 0L,
                itemCount = (categoryCounts.entries.firstOrNull { (backupId, _) ->
                    categoryIdMap[backupId] == localId
                }?.value ?: 0).toLong(),
                updatedAt = now,
            )
        }
    }

    private fun laterNonNull(left: Long?, right: Long?): Long? = when {
        left == null -> right
        right == null -> left
        else -> maxOf(left, right)
    }

    private data class CapturedSetting(
        val setting: BackupSetting,
        val existed: Boolean,
    )

    private fun captureSettings(settings: List<BackupSetting>): List<CapturedSetting> =
        settings.distinctBy { it.key }.map { setting ->
            val existed = settingsStore.hasKey(setting.key)
            val current = if (existed) {
                setting.copy(
                    value = when (setting.type) {
                        BackupSettingType.Int -> settingsStore.getInt(setting.key, 0).toString()
                        BackupSettingType.Float -> settingsStore.getFloat(setting.key, 0f).toString()
                        BackupSettingType.Bool -> settingsStore.getBoolean(setting.key, false).toString()
                        BackupSettingType.String,
                        BackupSettingType.Enum -> settingsStore.getString(setting.key, "")
                    },
                )
            } else {
                setting
            }
            CapturedSetting(current, existed)
        }

    private fun restoreCapturedSettings(settings: List<CapturedSetting>) {
        settings.forEach { captured ->
            if (captured.existed) {
                restoreSettings(listOf(captured.setting))
            } else {
                settingsStore.remove(captured.setting.key)
            }
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

    private fun String?.csvLongs(): List<Long> =
        this?.split(",")?.mapNotNull { it.trim().toLongOrNull() }.orEmpty()

    private companion object {
        const val MAX_RECORDS_PER_DOMAIN = 100_000
        const val DAY_MILLIS = 86_400_000L
        const val UTC_PLUS_8_OFFSET_MILLIS = 8L * 60L * 60L * 1000L
    }
}

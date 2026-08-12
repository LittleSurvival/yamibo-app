package me.thenano.yamibo.yamibo_app.repository.appsync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import me.thenano.yamibo.yamibo_app.Database
import me.thenano.yamibo.yamibo_app.repository.BookMarkRepository
import me.thenano.yamibo.yamibo_app.repository.AndroidReadHistoryRepository
import me.thenano.yamibo.yamibo_app.repository.DetailNoteRepository
import me.thenano.yamibo.yamibo_app.repository.ReadHistoryRepository
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.DatabaseSyncDomainMaterializer
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncBulkDeleteProofFields
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.OperationReducer
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.SqlDelightSyncDomainStateAdapter
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncInstallationState
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncAccountBinding
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperationKind
import me.thenano.yamibo.yamibo_app.repository.bookmark.BookMarkRepositoryImpl
import me.thenano.yamibo.yamibo_app.repository.detailnote.DetailNoteRepositoryImpl
import me.thenano.yamibo.yamibo_app.repository.favorite.FavoriteStoreRepositoryImpl
import me.thenano.yamibo.yamibo_app.repository.settings.MangaReaderSettingsRepository
import me.thenano.yamibo.yamibo_app.repository.settings.NovelReaderSettingsRepository
import me.thenano.yamibo.yamibo_app.repository.settings.TouchZoneLayout
import io.github.littlesurvival.dto.value.TagId
import io.github.littlesurvival.dto.value.PostId
import io.github.littlesurvival.dto.value.ThreadId
import me.thenano.yamibo.yamibo_app.store.appsync.SqlDelightAppSyncOperationStore
import me.thenano.yamibo.yamibo_app.store.appsync.LocalSyncOperationDraft
import me.thenano.yamibo.yamibo_app.store.settings.SettingsStore

class AppSyncLocalMutationRoutingTest {
    @Test
    fun localMutationNeverLoadsTheFullResolvedState() = runBlocking {
        val db = inMemoryDatabase()
        val store = SqlDelightAppSyncOperationStore(db).also {
            it.initialize("generation")
            it.bindAccount(SyncAccountBinding("account"), AppSyncInstallationState.Active)
        }
        var fullStateReads = 0
        val domainState = SqlDelightSyncDomainStateAdapter(
            db = db,
            materializer = DatabaseSyncDomainMaterializer(db, MapSettingsStore()),
            nowMillis = { 100 },
            onFullStateRead = { fullStateReads++ },
        )
        val recorder = AppSyncMutationRecorder(true, store, domainState, nowMillis = { 100 })
        val repository = OperationRecordingReadHistoryRepository(
            AndroidReadHistoryRepository(db),
            recorder,
        )

        repository.savePosition(sampleThread())
        repository.savePosition(sampleThread().copy(page = 2, lastVisitTime = 2))

        assertEquals(0, fullStateReads)
        assertEquals(2, store.pendingOperations().size)
    }

    @Test
    fun entityScopedGenerationAdvancesAfterTombstoneWithoutReadingUnrelatedState() = runBlocking {
        val db = inMemoryDatabase()
        val store = SqlDelightAppSyncOperationStore(db).also {
            it.initialize("generation")
            it.bindAccount(SyncAccountBinding("account"), AppSyncInstallationState.Active)
        }
        var fullStateReads = 0
        val domainState = SqlDelightSyncDomainStateAdapter(
            db = db,
            materializer = DatabaseSyncDomainMaterializer(db, MapSettingsStore()),
            nowMillis = { 100 },
            onFullStateRead = { fullStateReads++ },
        )
        val recorder = AppSyncMutationRecorder(true, store, domainState, nowMillis = { 100 })
        val delegate = AndroidReadHistoryRepository(db)
        val repository = OperationRecordingReadHistoryRepository(delegate, recorder)
        val history = sampleThread()

        repository.savePosition(history)
        repository.deleteHistory(history.threadId, history.threadType, history.authorId)
        repository.savePosition(history.copy(page = 3, lastVisitTime = 3))

        assertEquals(0, fullStateReads)
        assertEquals(
            listOf(1L, 1L, 2L),
            store.pendingOperations().map { it.entityGeneration },
        )
        assertEquals(
            listOf(SyncOperationKind.Put, SyncOperationKind.Delete, SyncOperationKind.Put),
            store.pendingOperations().map { it.kind },
        )
    }

    @Test
    fun activeDetailNoteMutationCommitsDataAndOperationTogether() = runBlocking {
        val fixture = activeFixture()
        val repository = DetailNoteRepositoryImpl(fixture.db, fixture.recorder)

        repository.saveNote(DetailNoteRepository.TargetType.NovelThread, 12, 34, "note")

        assertEquals("note", repository.getNote(DetailNoteRepository.TargetType.NovelThread, 12, 34)?.content)
        val operation = fixture.store.pendingOperations().single()
        assertEquals("detail-note", operation.domainId.value)
        assertEquals("NovelThread|12|34", operation.entityId.value)
        assertEquals(SyncOperationKind.Put, operation.kind)
    }

    @Test
    fun bookmarkBecomingEmptyCreatesDeleteTombstone() = runBlocking {
        val fixture = activeFixture()
        val repository = BookMarkRepositoryImpl(fixture.db, fixture.recorder)

        repository.setBookmarked(BookMarkRepository.TargetType.ThreadPost, 12, 34, "post", true)
        repository.setBookmarked(BookMarkRepository.TargetType.ThreadPost, 12, 34, "post", false)

        assertEquals(null, repository.getEntry(BookMarkRepository.TargetType.ThreadPost, 12, 34))
        assertEquals(
            listOf(SyncOperationKind.Put, SyncOperationKind.Delete),
            fixture.store.pendingOperations().map { it.kind },
        )
    }

    @Test
    fun unboundCanonicalSettingRemainsWritableWithoutPublishing() {
        val db = inMemoryDatabase()
        val store = SqlDelightAppSyncOperationStore(db).also { it.initialize("generation") }
        val settings = MapSettingsStore()
        val recorder = recorder(db, store)
        val recordingStore = OperationRecordingSettingsStore(db, settings, recorder)

        recordingStore.putString("theme", "dark")

        assertEquals("dark", recordingStore.getString("theme", "light"))
        assertEquals("dark", settings.getString("theme", "light"))
        assertTrue(store.pendingOperations().isEmpty())
        assertEquals(
            "local-pending-bootstrap-migration",
            db.appSyncOperationQueries.getSyncSettingValue("theme").executeAsOne().winnerOperationId,
        )
    }

    @Test
    fun deviceLocalCategorySelectionIgnoresCloudCanonicalValueAndDoesNotPublish() {
        val fixture = activeFixture()
        val settings = MapSettingsStore().also {
            it.putInt("appsettings.favoritelastcategoryid", 7)
        }
        fixture.db.appSyncOperationQueries.upsertSyncSettingValue(
            settingKey = "appsettings.favoritelastcategoryid",
            type = "int",
            value_ = "99",
            winnerOperationId = "remote-device",
            updatedAtEpochMillis = 1,
        )
        val recordingStore = OperationRecordingSettingsStore(fixture.db, settings, fixture.recorder)

        assertEquals(7, recordingStore.getInt("appsettings.favoritelastcategoryid", 0))
        recordingStore.putInt("appsettings.favoritelastcategoryid", 8)

        assertEquals(8, recordingStore.getInt("appsettings.favoritelastcategoryid", 0))
        assertTrue(fixture.store.pendingOperations().isEmpty())
        assertEquals(
            "99",
            fixture.db.appSyncOperationQueries
                .getSyncSettingValue("appsettings.favoritelastcategoryid")
                .executeAsOne()
                .settingValue,
        )
    }

    @Test
    fun mangaAndThreadTouchSettingsSyncAsIndependentEntities() {
        val source = activeFixture()
        val sourceSettings = MapSettingsStore()
        val recordingStore = OperationRecordingSettingsStore(source.db, sourceSettings, source.recorder)
        val sourceManga = MangaReaderSettingsRepository(recordingStore)
        val sourceThread = NovelReaderSettingsRepository(recordingStore)

        sourceManga.touchZone.setValue(TouchZoneLayout.EDGE)
        sourceManga.reverseTouchZones.setValue(true)
        sourceThread.threadTouchZone.setValue(TouchZoneLayout.KINDLE)
        sourceThread.threadReverseTouchZones.setValue(false)

        val expectedKeys = setOf(
            sourceManga.touchZone.storageKey,
            sourceManga.reverseTouchZones.storageKey,
            sourceThread.threadTouchZone.storageKey,
            sourceThread.threadReverseTouchZones.storageKey,
        )
        val operations = source.store.pendingOperations()
        assertEquals(expectedKeys, operations.mapTo(linkedSetOf()) { it.entityId.value })
        assertTrue(operations.all { it.domainId.value == "settings" })

        val targetDb = inMemoryDatabase()
        val targetSettings = MapSettingsStore()
        val targetDomain = SqlDelightSyncDomainStateAdapter(
            db = targetDb,
            materializer = DatabaseSyncDomainMaterializer(targetDb, targetSettings),
            nowMillis = { 200 },
        )
        targetDomain.apply(OperationReducer().reduce(operations = operations))

        val targetManga = MangaReaderSettingsRepository(targetSettings)
        val targetThread = NovelReaderSettingsRepository(targetSettings)
        assertEquals(TouchZoneLayout.EDGE, targetManga.touchZone.getValue())
        assertTrue(targetManga.reverseTouchZones.getValue())
        assertEquals(TouchZoneLayout.KINDLE, targetThread.threadTouchZone.getValue())
        assertFalse(targetThread.threadReverseTouchZones.getValue())
    }

    @Test
    fun remoteSettingTombstoneDeletesCanonicalAndPlatformProjection() {
        val db = inMemoryDatabase()
        val store = SqlDelightAppSyncOperationStore(db).also {
            it.initialize("generation")
            it.bindAccount(SyncAccountBinding("account"), AppSyncInstallationState.Active)
        }
        val settings = MapSettingsStore()
        val domainState = SqlDelightSyncDomainStateAdapter(
            db = db,
            materializer = DatabaseSyncDomainMaterializer(db, settings),
            nowMillis = { 100 },
        )
        val recordingStore = OperationRecordingSettingsStore(
            db,
            settings,
            AppSyncMutationRecorder(true, store, domainState, nowMillis = { 100 }),
        )
        recordingStore.putString("theme", "dark")
        recordingStore.remove("theme")

        settings.putString("theme", "stale")
        db.appSyncOperationQueries.upsertSyncSettingValue(
            settingKey = "theme",
            type = "string",
            value_ = "stale",
            winnerOperationId = "stale",
            updatedAtEpochMillis = 1,
        )
        domainState.apply(OperationReducer().reduce(operations = store.pendingOperations()))

        assertFalse(settings.hasKey("theme"))
        assertEquals(
            null,
            db.appSyncOperationQueries.getSyncSettingValue("theme").executeAsOneOrNull(),
        )
    }

    @Test
    fun checkpointReplacementClearsStalePlatformSettingProjection() {
        val db = inMemoryDatabase()
        val settings = MapSettingsStore().also { it.putString("theme", "stale") }
        db.appSyncOperationQueries.upsertSyncSettingValue(
            settingKey = "theme",
            type = "string",
            value_ = "stale",
            winnerOperationId = "stale",
            updatedAtEpochMillis = 1,
        )
        val domainState = SqlDelightSyncDomainStateAdapter(
            db = db,
            materializer = DatabaseSyncDomainMaterializer(db, settings),
            nowMillis = { 100 },
        )

        domainState.adoptCheckpoint(emptyList())

        assertFalse(settings.hasKey("theme"))
        assertEquals(
            null,
            db.appSyncOperationQueries.getSyncSettingValue("theme").executeAsOneOrNull(),
        )
    }

    @Test
    fun favoriteCommandCreatesItemAndMembershipOperationsAtomically() = runBlocking {
        val fixture = activeFixture()
        val repository = FavoriteStoreRepositoryImpl(fixture.db, fixture.recorder)
        val category = repository.getDefaultCategory()
        fixture.store.markAcknowledged(
            fixture.store.pendingOperations().mapTo(linkedSetOf()) { it.operationId },
            atEpochMillis = 100,
        )

        repository.addTagMangaFavorite(
            tagId = TagId(77),
            tagName = "title",
            coverUrl = null,
            categoryIds = listOf(category.id),
            collectionIds = emptyList(),
        )
        repository.addTagMangaFavorite(
            tagId = TagId(77),
            tagName = "title",
            coverUrl = null,
            categoryIds = listOf(category.id),
            collectionIds = emptyList(),
        )

        val pending = fixture.store.pendingOperations()
        assertEquals(
            listOf("favorite.item", "favorite.item-category"),
            pending.map { it.domainId.value },
        )
        assertEquals(listOf(2L, 3L), pending.map { it.sequence.value })
        assertEquals(1, repository.getAllFavoriteItems().size)
        assertEquals(setOf(category.id), repository.getCategoryIdsForItem(repository.getAllFavoriteItems().single().id))
    }

    @Test
    fun confirmedBulkDeleteStoresPortableAuthorizationProof() {
        val fixture = activeFixture()
        var localMutationRan = false
        val operations = fixture.recorder.recordAuthorizedDeleteBatch(
            drafts = listOf("one", "two").map { entityId ->
                LocalSyncOperationDraft(
                    domainId = me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncDomainId(
                        "reading.thread",
                    ),
                    entityId = me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncEntityId(
                        entityId,
                    ),
                    kind = SyncOperationKind.Delete,
                    fields = emptyMap(),
                )
            },
            scopeKey = "reading-history:selected",
        ) {
            localMutationRan = true
        }

        assertTrue(localMutationRan)
        assertEquals(2, operations.size)
        val authorizationId = operations.map { it.bulkDeleteAuthorizationId }.distinct().single()
        assertEquals(
            setOf<String?>("2"),
            operations.mapTo(linkedSetOf()) {
                it.fields[AppSyncBulkDeleteProofFields.COUNT]
            },
        )
        assertEquals(
            "reading-history:selected",
            operations.first().fields[AppSyncBulkDeleteProofFields.SCOPE],
        )
        assertEquals(2L, fixture.store.loadBulkDeleteAuthorization(requireNotNull(authorizationId))?.operationCount)
    }

    @Test
    fun allReadingHistoryModesRecordAndClearWithExactTombstones() = runBlocking {
        val fixture = activeFixture()
        val delegate = AndroidReadHistoryRepository(fixture.db)
        val repository = OperationRecordingReadHistoryRepository(delegate, fixture.recorder)
        delegate.recordReadingDuration("2026-08-01", 123)
        fixture.db.rssSearchSubscriptionQueries.insertSubscription(
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
        val rssId = fixture.db.rssSearchSubscriptionQueries.lastInsertedId().executeAsOne()
        val thread = sampleThread()
        val image = ReadHistoryRepository.ImageReadingHistory(
            PostId(2), ThreadId(1), 1, 10, lastVisitTime = 2,
        )
        val manga = ReadHistoryRepository.TagMangaReadingHistory(
            TagId(3), "manga", 1, ThreadId(3), "thread", 1, 10, lastVisitTime = 3,
        )
        val catalog = ReadHistoryRepository.TagCatalogReadingHistory(
            TagId(3), "catalog", 1, ThreadId(4), "thread", 1,
            PostId(4), "post", lastVisitTime = 4,
        )
        val rssSearch = ReadHistoryRepository.RssSearchReadingHistory(
            rssId, "RSS", "query", 1, ThreadId(5), "thread", 1, 10, lastVisitTime = 5,
        )
        val rssCatalog = ReadHistoryRepository.RssCatalogReadingHistory(
            rssId, "RSS", "query", 1, ThreadId(6), "thread", 1,
            PostId(6), "post", lastVisitTime = 6,
        )

        repository.savePosition(thread)
        repository.saveImagePosition(image)
        repository.saveTagMangaReaderModeHistory(manga)
        repository.saveTagCatalogThreadHistory(catalog)
        repository.saveRssSearchReaderModeHistory(rssSearch)
        repository.saveRssCatalogThreadHistory(rssCatalog)

        assertEquals(
            setOf(
                "reading.thread", "reading.image", "reading.tag-manga",
                "reading.tag-catalog", "reading.rss-search", "reading.rss-catalog",
            ),
            fixture.store.pendingOperations().mapTo(linkedSetOf()) { it.domainId.value },
        )
        fixture.store.markAcknowledged(
            fixture.store.pendingOperations().mapTo(linkedSetOf()) { it.operationId },
            atEpochMillis = 100,
        )

        repository.deleteAllCombinedHistory()

        val deletes = fixture.store.pendingOperations()
        assertEquals(6, deletes.size)
        assertTrue(deletes.all { it.kind == SyncOperationKind.Delete })
        assertEquals(
            setOf(
                "reading.thread", "reading.image", "reading.tag-manga",
                "reading.tag-catalog", "reading.rss-search", "reading.rss-catalog",
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
    }

    @Test
    fun clearAllEnumerationFailureLeavesHistoryAndOutboxUntouched() = runBlocking {
        val fixture = activeFixture()
        val base = AndroidReadHistoryRepository(fixture.db)
        val catalog = ReadHistoryRepository.TagCatalogReadingHistory(
            TagId(9), "catalog", 1, ThreadId(9), "thread", 1,
            PostId(9), "post", lastVisitTime = 9,
        )
        base.saveTagCatalogThreadHistory(catalog)
        val failing = object : ReadHistoryRepository by base {
            override suspend fun getAllTagCatalogHistoryForSync():
                List<ReadHistoryRepository.TagCatalogReadingHistory> = error("injected enumeration failure")
        }
        val repository = OperationRecordingReadHistoryRepository(failing, fixture.recorder)

        assertFailsWith<IllegalStateException> {
            repository.deleteAllCombinedHistory()
        }

        assertEquals(catalog, base.getTagCatalogThreadHistoryPosition(TagId(9)))
        assertTrue(fixture.store.pendingOperations().isEmpty())
    }

    @Test
    fun selectedDeleteDoesNotCrossTagHistoryModesAndLaterReadRecreatesGeneration() = runBlocking {
        val fixture = activeFixture()
        val delegate = AndroidReadHistoryRepository(fixture.db)
        val repository = OperationRecordingReadHistoryRepository(delegate, fixture.recorder)
        val manga = ReadHistoryRepository.TagMangaReadingHistory(
            TagId(7), "manga", 1, ThreadId(7), "thread", 1, 10, lastVisitTime = 7,
        )
        val catalog = ReadHistoryRepository.TagCatalogReadingHistory(
            TagId(7), "catalog", 1, ThreadId(8), "thread", 1,
            PostId(8), "post", lastVisitTime = 8,
        )
        repository.saveTagMangaReaderModeHistory(manga)
        repository.saveTagCatalogThreadHistory(catalog)
        fixture.store.markAcknowledged(
            fixture.store.pendingOperations().mapTo(linkedSetOf()) { it.operationId },
            atEpochMillis = 100,
        )

        repository.deleteCombinedHistoryBatch(listOf(manga))

        assertEquals(null, delegate.getTagMangaReaderModeHistoryPosition(TagId(7)))
        assertEquals(catalog, delegate.getTagCatalogThreadHistoryPosition(TagId(7)))
        val delete = fixture.store.pendingOperations().single()
        assertEquals("reading.tag-manga", delete.domainId.value)
        assertEquals(1L, delete.entityGeneration)
        fixture.store.markAcknowledged(setOf(delete.operationId), 101)

        repository.saveTagMangaReaderModeHistory(manga.copy(lastVisitTime = 9))

        val recreated = fixture.store.pendingOperations().single()
        assertEquals(SyncOperationKind.Put, recreated.kind)
        assertEquals(2L, recreated.entityGeneration)
    }

    @Test
    fun twoDeviceHistoryProjectionConvergesAcrossDifferentLocalRssIds() = runBlocking {
        val deviceA = activeFixture()
        val deviceB = activeFixture()
        val delegateA = AndroidReadHistoryRepository(deviceA.db)
        val repositoryA = OperationRecordingReadHistoryRepository(delegateA, deviceA.recorder)
        insertRssSubscription(deviceB.db, "dummy")
        val rssIdA = insertRssSubscription(deviceA.db, "query")
        val rssIdB = insertRssSubscription(deviceB.db, "query")
        assertFalse(rssIdA == rssIdB)
        val delegateB = AndroidReadHistoryRepository(deviceB.db)
        val domainB = SqlDelightSyncDomainStateAdapter(
            db = deviceB.db,
            materializer = DatabaseSyncDomainMaterializer(deviceB.db, MapSettingsStore()),
            nowMillis = { 100 },
        )
        val thread = sampleThread()
        val image = ReadHistoryRepository.ImageReadingHistory(
            PostId(2), ThreadId(1), 1, 10, lastVisitTime = 2,
        )
        val manga = ReadHistoryRepository.TagMangaReadingHistory(
            TagId(3), "manga", 1, ThreadId(3), "manga thread", 1, 10, lastVisitTime = 3,
        )
        val catalog = ReadHistoryRepository.TagCatalogReadingHistory(
            TagId(4), "catalog", 1, ThreadId(4), "catalog thread", 1,
            PostId(4), "catalog post", lastVisitTime = 4,
        )
        val rssSearch = ReadHistoryRepository.RssSearchReadingHistory(
            rssIdA, "RSS", "query", 1, ThreadId(5), "rss search", 1, 10, lastVisitTime = 5,
        )
        val rssCatalog = ReadHistoryRepository.RssCatalogReadingHistory(
            rssIdA, "RSS", "query", 1, ThreadId(6), "rss catalog", 1,
            PostId(6), "rss post", lastVisitTime = 6,
        )

        repositoryA.savePosition(thread)
        repositoryA.saveImagePosition(image)
        repositoryA.saveTagMangaReaderModeHistory(manga)
        repositoryA.saveTagCatalogThreadHistory(catalog)
        repositoryA.saveRssSearchReaderModeHistory(rssSearch)
        repositoryA.saveRssCatalogThreadHistory(rssCatalog)
        applyAllOperations(deviceA, domainB)

        assertEquals(thread, delegateB.getPosition(thread.threadId, thread.threadType, thread.authorId))
        assertEquals(image, delegateB.getImagePosition(image.postId))
        assertEquals(manga, delegateB.getTagMangaReaderModeHistoryPosition(manga.tagId))
        assertEquals(catalog, delegateB.getTagCatalogThreadHistoryPosition(catalog.tagId))
        assertEquals("rss search", delegateB.getRssSearchReaderModeHistoryPosition(rssIdB)?.threadTitle)
        assertEquals("rss catalog", delegateB.getRssCatalogThreadHistoryPosition(rssIdB)?.threadTitle)

        repositoryA.deleteCombinedHistoryBatch(listOf(manga, rssSearch))
        applyAllOperations(deviceA, domainB)

        assertEquals(null, delegateB.getTagMangaReaderModeHistoryPosition(manga.tagId))
        assertEquals(null, delegateB.getRssSearchReaderModeHistoryPosition(rssIdB))
        assertEquals(catalog, delegateB.getTagCatalogThreadHistoryPosition(catalog.tagId))
        assertEquals("rss catalog", delegateB.getRssCatalogThreadHistoryPosition(rssIdB)?.threadTitle)

        repositoryA.deleteAllCombinedHistory()
        applyAllOperations(deviceA, domainB)

        assertEquals(0, delegateB.getHistoryCount())
        assertTrue(delegateB.getAllImageHistoryForSync().isEmpty())
        assertTrue(delegateB.getAllTagMangaHistoryForSync().isEmpty())
        assertTrue(delegateB.getAllTagCatalogHistoryForSync().isEmpty())
        assertTrue(delegateB.getAllRssSearchHistoryForSync().isEmpty())
        assertTrue(delegateB.getAllRssCatalogHistoryForSync().isEmpty())

        repositoryA.savePosition(thread.copy(lastVisitTime = 101))
        repositoryA.saveImagePosition(image.copy(lastVisitTime = 102))
        repositoryA.saveTagMangaReaderModeHistory(manga.copy(lastVisitTime = 103))
        repositoryA.saveTagCatalogThreadHistory(catalog.copy(lastVisitTime = 104))
        repositoryA.saveRssSearchReaderModeHistory(rssSearch.copy(lastVisitTime = 105))
        repositoryA.saveRssCatalogThreadHistory(rssCatalog.copy(lastVisitTime = 106))
        val recreated = deviceA.store.allOutboxOperations().map { it.first }.filter {
            it.entityGeneration == 2L && it.kind == SyncOperationKind.Put
        }
        assertEquals(
            setOf(
                "reading.thread", "reading.image", "reading.tag-manga",
                "reading.tag-catalog", "reading.rss-search", "reading.rss-catalog",
            ),
            recreated.mapTo(linkedSetOf()) { it.domainId.value },
        )
        applyAllOperations(deviceA, domainB)

        assertEquals(101, delegateB.getPosition(thread.threadId, thread.threadType, thread.authorId)?.lastVisitTime)
        assertEquals(102, delegateB.getImagePosition(image.postId)?.lastVisitTime)
        assertEquals(103, delegateB.getTagMangaReaderModeHistoryPosition(manga.tagId)?.lastVisitTime)
        assertEquals(104, delegateB.getTagCatalogThreadHistoryPosition(catalog.tagId)?.lastVisitTime)
        assertEquals(105, delegateB.getRssSearchReaderModeHistoryPosition(rssIdB)?.lastVisitTime)
        assertEquals(106, delegateB.getRssCatalogThreadHistoryPosition(rssIdB)?.lastVisitTime)
    }

    private fun applyAllOperations(source: Fixture, target: SqlDelightSyncDomainStateAdapter) {
        val operations = source.store.allOutboxOperations().map { it.first }
        val reduction = OperationReducer().reduce(operations = operations)
        assertTrue(reduction.quarantined.isEmpty())
        target.apply(reduction)
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

    private fun sampleThread() = ReadHistoryRepository.ThreadReadingHistory(
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
    )

    private fun activeFixture(): Fixture {
        val db = inMemoryDatabase()
        val store = SqlDelightAppSyncOperationStore(db).also {
            it.initialize("generation")
            it.bindAccount(SyncAccountBinding("account"), AppSyncInstallationState.Active)
        }
        return Fixture(db, store, recorder(db, store))
    }

    private fun recorder(
        db: Database,
        store: SqlDelightAppSyncOperationStore,
    ) = AppSyncMutationRecorder(
        enabled = true,
        store = store,
        domainState = SqlDelightSyncDomainStateAdapter(
            db = db,
            materializer = DatabaseSyncDomainMaterializer(db, MapSettingsStore()),
            nowMillis = { 100 },
        ),
        nowMillis = { 100 },
    )

    private fun inMemoryDatabase(): Database {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        return Database(driver)
    }

    private data class Fixture(
        val db: Database,
        val store: SqlDelightAppSyncOperationStore,
        val recorder: AppSyncMutationRecorder,
    )

    private class MapSettingsStore : SettingsStore {
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

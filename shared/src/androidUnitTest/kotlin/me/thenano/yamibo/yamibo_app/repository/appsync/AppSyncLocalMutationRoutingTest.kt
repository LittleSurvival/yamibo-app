package me.thenano.yamibo.yamibo_app.repository.appsync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import me.thenano.yamibo.yamibo_app.Database
import me.thenano.yamibo.yamibo_app.repository.BookMarkRepository
import me.thenano.yamibo.yamibo_app.repository.DetailNoteRepository
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
import io.github.littlesurvival.dto.value.TagId
import me.thenano.yamibo.yamibo_app.store.appsync.SqlDelightAppSyncOperationStore
import me.thenano.yamibo.yamibo_app.store.appsync.LocalSyncOperationDraft
import me.thenano.yamibo.yamibo_app.store.settings.SettingsStore

class AppSyncLocalMutationRoutingTest {
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

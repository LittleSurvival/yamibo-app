package me.thenano.yamibo.yamibo_app.repository.appsync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import me.thenano.yamibo.yamibo_app.Database
import me.thenano.yamibo.yamibo_app.repository.RssSearchSubscriptionRepository
import me.thenano.yamibo.yamibo_app.repository.TagRepository
import me.thenano.yamibo.yamibo_app.repository.ThreadRepository
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.DatabaseSyncDomainMaterializer
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.SqlDelightSyncDomainStateAdapter
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncInstallationState
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncAccountBinding
import me.thenano.yamibo.yamibo_app.repository.backup.favoriteUpdateEventIdentity
import me.thenano.yamibo.yamibo_app.repository.favorite.FavoriteStoreRepositoryImpl
import me.thenano.yamibo.yamibo_app.repository.favorite.FavoriteUpdateRepositoryImpl
import me.thenano.yamibo.yamibo_app.store.appsync.SqlDelightAppSyncOperationStore
import me.thenano.yamibo.yamibo_app.store.settings.SettingsStore

class FavoriteUpdateMutationRoutingTest {
    @Test
    fun lifecycleBatchAndFilterTogglesRecordOperationsAtomically() = runBlocking {
        val fixture = fixture(bound = true)
        val eventIds = listOf(
            seedEvent(fixture.db, 100, "details:100"),
            seedEvent(fixture.db, 101, "details:101"),
        )

        fixture.repository.markEventRead(eventIds.first())
        fixture.repository.dismissEvents(eventIds)
        fixture.repository.setFidEnabled(1, false)
        fixture.repository.setCategoryEnabled(7, false)

        val operations = fixture.store.pendingOperations()
        assertEquals(5, operations.size)
        assertEquals(
            setOf(
                "favorite.update-event",
                "favorite.update-fid-filter",
                "favorite.update-category-filter",
            ),
            operations.mapTo(linkedSetOf()) { it.domainId.value },
        )
        assertEquals(2, operations.count { "dismissedAt" in it.fields })
        assertEquals(1, operations.count { "readAt" in it.fields })
        assertEquals(
            0,
            fixture.db.favoriteUpdateFidChoiceQueries.getByFid(1).executeAsOne().enabled,
        )
        assertEquals(
            0,
            fixture.db.favoriteUpdateCategoryChoiceQueries.getBySyncId("category-sync")
                .executeAsOne()
                .enabled,
        )
    }

    @Test
    fun unboundRecorderKeepsLocalBehaviorWithoutOutbox() = runBlocking {
        val fixture = fixture(bound = false)
        val eventId = seedEvent(fixture.db, 100, "details:100")

        fixture.repository.markEventRead(eventId)
        fixture.repository.setFidEnabled(1, false)

        assertEquals(0, fixture.store.pendingOperations().size)
        assertEquals(
            false,
            fixture.db.favoriteUpdateEventQueries.getById(eventId).executeAsOne().readAt == null,
        )
        assertEquals(0, fixture.db.favoriteUpdateFidChoiceQueries.getByFid(1).executeAsOne().enabled)
    }

    private fun fixture(bound: Boolean): Fixture {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        val db = Database(driver)
        db.localFavoriteCategoryQueries.insertCategory("category", 0, 1, 1)
        val categoryId = db.localFavoriteCategoryQueries.getFirstByName("category").executeAsOne().id
        db.localFavoriteCategoryQueries.setSyncId("category-sync", categoryId)
        require(categoryId == 7L || categoryId == 1L)
        if (categoryId != 7L) {
            driver.execute(
                null,
                "UPDATE LocalFavoriteCategory SET id = 7 WHERE id = $categoryId",
                0,
            )
        }
        db.favoriteUpdateFidFilterQueries.upsertFilter(1, "forum", 1, 1, 1)
        db.favoriteUpdateCategoryFilterQueries.upsertFilter(7, "category", 1, 1, 1)

        val store = SqlDelightAppSyncOperationStore(db).also {
            it.initialize("generation")
            if (bound) it.bindAccount(SyncAccountBinding("account"), AppSyncInstallationState.Active)
        }
        val recorder = AppSyncMutationRecorder(
            enabled = true,
            store = store,
            domainState = SqlDelightSyncDomainStateAdapter(
                db,
                DatabaseSyncDomainMaterializer(db, MemorySettingsStore()),
                nowMillis = { 100 },
            ),
            nowMillis = { 100 },
        )
        val repository = FavoriteUpdateRepositoryImpl(
            db = db,
            localFavoriteRepository = FavoriteStoreRepositoryImpl(db),
            threadRepository = unused(),
            tagRepository = unused(),
            rssSearchSubscriptionRepository = unused(),
            mutationRecorder = recorder,
        )
        return Fixture(db, store, repository)
    }

    private fun seedEvent(db: Database, detailId: Long, discriminator: String): Long {
        val identity = favoriteUpdateEventIdentity(
            "ThreadNormal", 42, null, "NormalThread", listOf(detailId), false,
            100, "new", "title", discriminator,
        )
        db.favoriteUpdateEventQueries.upsertBySyncId(
            "ThreadNormal", 42, 0, 1, "forum", "title", "post", "NormalThread",
            "new", detailId.toString(), null, 100, null, null, 0,
            identity.syncId, identity.sourceFingerprint, identity.sourceDiscriminator,
        )
        return db.favoriteUpdateEventQueries.getBySyncId(identity.syncId).executeAsOne().id
    }

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> unused(): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            throw UnsupportedOperationException(method.name)
        } as T

    private data class Fixture(
        val db: Database,
        val store: SqlDelightAppSyncOperationStore,
        val repository: FavoriteUpdateRepositoryImpl,
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

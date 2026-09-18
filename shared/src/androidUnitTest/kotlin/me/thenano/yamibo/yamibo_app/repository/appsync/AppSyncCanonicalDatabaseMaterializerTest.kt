package me.thenano.yamibo.yamibo_app.repository.appsync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.*
import me.thenano.yamibo.yamibo_app.Database
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.DatabaseSyncDomainMaterializer
import me.thenano.yamibo.yamibo_app.repository.appsync.schema.*
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.*
import me.thenano.yamibo.yamibo_app.store.settings.SettingsStore

class AppSyncCanonicalDatabaseMaterializerTest {
    private fun checkpoint(): AppSyncCanonicalCheckpoint {
        val corpus = AppSyncSyntheticCorpus.create()
        val cache = mutableMapOf<SyncOperationId, AppSyncCanonicalOperation>()
        fun canonical(op: SyncOperation): AppSyncCanonicalOperation = cache.getOrPut(op.operationId) {
            assertIs<AppSyncCanonicalOperationImport.Accepted>(AppSyncCanonicalOperationImporter()
                .import(corpus.journal.accountBinding.value, op)).operation
        }
        val entities = corpus.resolved.map { entity ->
            val domain = AppSyncCanonicalSchema.domains.getValue(entity.key.domainId.value)
            AppSyncCanonicalProjection(domain.id, entity.key.entityId.value, entity.key.generation,
                entity.fields.filter { (name, value) -> domain.fields[name]?.let {
                    it.portable && it.id in canonical(value.operation).fields } == true }.entries.associate { (name, value) ->
                    domain.fields.getValue(name).id to canonical(value.operation)
                }, entity.relationOperation?.let(::canonical), entity.tombstone?.let(::canonical))
        }
        return AppSyncCanonicalCheckpoint("corpus", corpus.journal.accountBinding.value, 1,
            corpus.journal.observed.asStableMap(), entities.reversed())
    }
    private fun database(test: (Database, DatabaseSyncDomainMaterializer) -> Unit) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            Database.Schema.create(driver)
            val db = Database(driver)
            test(db, DatabaseSyncDomainMaterializer(db, MemorySettingsStore()))
        } finally { driver.close() }
    }

    @Test fun allDomainsApplyInDependencyOrderAndReplayWithoutDuplicates() = database { db, materializer ->
        val checkpoint = checkpoint()
        materializer.applyCanonicalProjections(checkpoint.accountBinding, checkpoint)
        val favorites = db.localFavoriteItemQueries.getAll().executeAsList()
        assertEquals(checkpoint.entities.count { it.domainId == 2 && it.tombstone == null }, favorites.size)
        assertTrue(favorites.all { it.coverUrl == null })
        val histories = db.rssSearchReadingHistoryQueries.getAll().executeAsList()
        assertTrue(histories.isNotEmpty())
        val parents = db.rssSearchSubscriptionQueries.getAll().executeAsList().associateBy { it.id }
        histories.forEach { history ->
            assertEquals(parents.getValue(history.subscriptionId).title, history.subscriptionTitle)
            assertEquals(parents.getValue(history.subscriptionId).query, history.subscriptionQuery)
        }
        val settings = db.appSyncOperationQueries.getSyncSettingValues().executeAsList()
        assertTrue(settings.isNotEmpty())
        assertEquals(checkpoint.entities.count { it.domainId == 17 }, db.favoriteUpdateEventQueries.getAll().executeAsList().size)
        materializer.applyCanonicalProjections(checkpoint.accountBinding, checkpoint)
        assertEquals(favorites, db.localFavoriteItemQueries.getAll().executeAsList())
        assertEquals(histories, db.rssSearchReadingHistoryQueries.getAll().executeAsList())
        assertEquals(settings, db.appSyncOperationQueries.getSyncSettingValues().executeAsList())
    }

    @Test fun lateMissingEssentialValueRollsBackEarlierDomainWrites() = database { db, materializer ->
        val checkpoint = checkpoint()
        val incomplete = checkpoint.copy(entities = checkpoint.entities.map {
            if (it.domainId == 14) it.copy(fields = it.fields - 54) else it
        })
        assertFailsWith<IllegalArgumentException> { materializer.applyCanonicalProjections(checkpoint.accountBinding, incomplete) }
        assertTrue(db.localFavoriteItemQueries.getAll().executeAsList().isEmpty())
        assertTrue(db.localFavoriteCategoryQueries.getAll().executeAsList().isEmpty())
        assertTrue(db.rssSearchSubscriptionQueries.getAll().executeAsList().isEmpty())
        assertTrue(db.appSyncOperationQueries.getSyncSettingValues().executeAsList().isEmpty())
    }

    @Test fun authoritativeDeleteUsesOnlyKeyAndWrongAccountMakesNoWrites() = database { db, materializer ->
        val checkpoint = checkpoint()
        assertFailsWith<IllegalArgumentException> { materializer.applyCanonicalProjections("wrong", checkpoint) }
        assertTrue(db.localFavoriteItemQueries.getAll().executeAsList().isEmpty())
        materializer.applyCanonicalProjections(checkpoint.accountBinding, checkpoint)
        val favorite = checkpoint.entities.first { it.domainId == 2 }
        val winner = favorite.fields.values.first()
        val deletion = winner.copy(sequence = 10000, kind = SyncOperationKind.Delete,
            origin = SyncOperationOrigin.UserAction, fields = emptyMap())
        val deleted = checkpoint.copy(coverage = checkpoint.coverage + ("${winner.deviceId}:${winner.deviceEpoch}" to 10000L),
            entities = listOf(favorite.copy(fields = emptyMap(), tombstone = deletion)))
        val before = db.localFavoriteItemQueries.getAll().executeAsList().size
        materializer.applyCanonicalProjections(checkpoint.accountBinding, deleted)
        materializer.applyCanonicalProjections(checkpoint.accountBinding, deleted)
        assertEquals(before - 1, db.localFavoriteItemQueries.getAll().executeAsList().size)
    }

    @Test fun missingRelationParentCannotSilentlyDropAnAddition() = database { db, materializer ->
        val checkpoint = checkpoint()
        val incomplete = checkpoint.copy(entities = checkpoint.entities.filter { it.domainId != 2 })
        assertFailsWith<IllegalArgumentException> { materializer.applyCanonicalProjections(checkpoint.accountBinding, incomplete) }
        assertTrue(db.localFavoriteCategoryQueries.getAll().executeAsList().isEmpty())
    }

    @Test fun replayPreservesLocalCoverAndRelationRemovalNeedsNoStaleBody() = database { db, materializer ->
        val checkpoint = checkpoint()
        materializer.applyCanonicalProjections(checkpoint.accountBinding, checkpoint)
        val item = db.localFavoriteItemQueries.getAll().executeAsList().first()
        db.localFavoriteItemQueries.updateFavoriteItem(item.title, "local-cover", item.lastUpdatedTime,
            item.forumId, item.forumName, item.authorId, item.lastFavoriteStatusUpdateAt, item.id)
        materializer.applyCanonicalProjections(checkpoint.accountBinding, checkpoint)
        assertEquals("local-cover", db.localFavoriteItemQueries.getAll().executeAsList().first { it.id == item.id }.coverUrl)
        val relation = checkpoint.entities.first { it.domainId == 15 }
        val winner = requireNotNull(relation.relation)
        val removed = winner.copy(sequence = 10000, kind = SyncOperationKind.RelationRemove,
            origin = SyncOperationOrigin.UserAction, fields = emptyMap())
        val removal = checkpoint.copy(coverage = checkpoint.coverage + ("${winner.deviceId}:${winner.deviceEpoch}" to 10000L),
            entities = listOf(relation.copy(fields = emptyMap(), relation = removed)))
        val count = db.localFavoriteItemCategoryCrossRefQueries.getAll().executeAsList().size
        materializer.applyCanonicalProjections(checkpoint.accountBinding, removal)
        materializer.applyCanonicalProjections(checkpoint.accountBinding, removal)
        assertEquals(count - 1, db.localFavoriteItemCategoryCrossRefQueries.getAll().executeAsList().size)
    }

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

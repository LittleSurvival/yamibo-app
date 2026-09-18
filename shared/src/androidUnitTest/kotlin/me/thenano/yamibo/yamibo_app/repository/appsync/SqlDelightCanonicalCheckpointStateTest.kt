package me.thenano.yamibo.yamibo_app.repository.appsync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.*
import me.thenano.yamibo.yamibo_app.Database
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.*
import me.thenano.yamibo.yamibo_app.repository.appsync.schema.*
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.*
import me.thenano.yamibo.yamibo_app.store.settings.SettingsStore

class SqlDelightCanonicalCheckpointStateTest {
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

    private fun state(db: Database) = SqlDelightCanonicalCheckpointState(db, DatabaseSyncDomainMaterializer(db, MemorySettingsStore()))
    private fun database(test: (Database, SqlDelightCanonicalCheckpointState) -> Unit) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            Database.Schema.create(driver)
            val db = Database(driver)
            test(db, state(db))
        } finally { driver.close() }
    }

    @Test fun migration45CreatesEmptyStateWithoutTouchingOtherData() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            driver.execute(null, "CREATE TABLE retained (value TEXT NOT NULL)", 0)
            driver.execute(null, "INSERT INTO retained VALUES ('sentinel')", 0)
            Database.Schema.migrate(driver, 45, 46)
            val count = driver.executeQuery(null, "SELECT COUNT(*) FROM AppSyncCanonicalState", { cursor ->
                cursor.next(); app.cash.sqldelight.db.QueryResult.Value(cursor.getLong(0))
            }, 0).value
            assertEquals(0L, count)
            val result = driver.executeQuery(null, "SELECT value FROM retained", { cursor ->
                cursor.next(); app.cash.sqldelight.db.QueryResult.Value(cursor.getString(0))
            }, 0).value
            assertEquals("sentinel", result)
        } finally { driver.close() }
    }

    @Test fun restartRetainsExactCanonicalEvidenceAndIdempotentReplay() {
        val file = java.nio.file.Files.createTempFile("appsync-canonical-", ".db")
        val url = "jdbc:sqlite:${file.toAbsolutePath()}"
        val checkpoint = checkpoint()
        try {
            JdbcSqliteDriver(url).use { driver ->
                Database.Schema.create(driver)
                val db = Database(driver)
                assertTrue(state(db).replace(checkpoint.accountBinding, checkpoint))
            }
            JdbcSqliteDriver(url).use { driver ->
                val db = Database(driver)
                val store = state(db)
                val restored = assertNotNull(store.read(checkpoint.accountBinding))
                assertEquals(AppSyncCanonicalCheckpointCodec().encode(checkpoint), AppSyncCanonicalCheckpointCodec().encode(restored))
                assertFalse(store.replace(checkpoint.accountBinding, checkpoint))
                assertTrue(db.localFavoriteItemQueries.getAll().executeAsList().isNotEmpty())
                assertContentEquals(AppSyncCanonicalCheckpointCodec().encode(checkpoint).toByteArray(),
                    db.appSyncCanonicalStateQueries.getState().executeAsOne().canonicalPayload)
            }
        } finally { java.nio.file.Files.deleteIfExists(file) }
    }

    @Test fun failedReplacementPreservesPriorStateAndMaterializedRows() = database { db, store ->
        val checkpoint = checkpoint()
        store.replace(checkpoint.accountBinding, checkpoint)
        val before = db.localFavoriteItemQueries.getAll().executeAsList()
        val broken = checkpoint.copy(checkpointId = "next", entities = checkpoint.entities.map {
            if (it.domainId == 14) it.copy(fields = it.fields - 54) else it
        })
        val failure = assertFailsWith<IllegalArgumentException> { store.replace(checkpoint.accountBinding, broken) }
        assertEquals("Missing materialized field: durationMillis", failure.message)
        assertEquals(before, db.localFavoriteItemQueries.getAll().executeAsList())
        assertEquals(checkpoint.checkpointId, store.read(checkpoint.accountBinding)?.checkpointId)
        assertTrue(store.replace(checkpoint.accountBinding, checkpoint.copy(checkpointId = "next")))
    }

    @Test fun collisionRegressionAndWrongAccountAreRejected() = database { db, store ->
        val checkpoint = checkpoint()
        store.replace(checkpoint.accountBinding, checkpoint)
        assertFailsWith<IllegalArgumentException> { store.replace(checkpoint.accountBinding, checkpoint.copy(createdAtEpochMillis = 2)) }
        // An empty valid checkpoint cannot regress previously covered operations.
        assertFailsWith<IllegalArgumentException> { store.replace(checkpoint.accountBinding,
            checkpoint.copy(checkpointId = "older", coverage = emptyMap(), entities = emptyList())) }
        assertFailsWith<IllegalArgumentException> { store.read("another-account") }
        assertFailsWith<IllegalArgumentException> { store.replace("another-account", checkpoint.copy(accountBinding = "another-account")) }
        assertEquals(checkpoint.checkpointId, db.appSyncCanonicalStateQueries.getState().executeAsOne().checkpointId)
    }

    @Test fun corruptedPersistenceCannotBecomeAnEmptyFallback() = database { db, store ->
        val checkpoint = checkpoint()
        store.replace(checkpoint.accountBinding, checkpoint)
        val row = db.appSyncCanonicalStateQueries.getState().executeAsOne()
        db.appSyncCanonicalStateQueries.putState(row.accountBinding, row.checkpointId, byteArrayOf(0), row.canonicalSha256)
        assertFailsWith<IllegalArgumentException> { store.read(checkpoint.accountBinding) }
        assertFailsWith<IllegalArgumentException> { store.replace(checkpoint.accountBinding, checkpoint.copy(checkpointId = "next")) }
        assertTrue(db.localFavoriteItemQueries.getAll().executeAsList().isNotEmpty())
    }

    @Test fun migration46PreservesCanonicalEvidenceAndRequiresPreferenceReconciliation() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            Database.Schema.migrate(driver, 45, 46)
            driver.execute(null, "INSERT INTO AppSyncCanonicalState VALUES (1, 'account', 'checkpoint', X'010203', 'digest')", 0)
            Database.Schema.migrate(driver, 46, 47)
            val queries = Database(driver).appSyncCanonicalStateQueries
            val row = queries.getState().executeAsOne()
            assertEquals("account", row.accountBinding)
            assertEquals("checkpoint", row.checkpointId)
            assertContentEquals(byteArrayOf(1, 2, 3), row.canonicalPayload)
            assertEquals("digest", row.canonicalSha256)
            assertEquals(1L, row.settingsReconciliationPending)
            queries.markSettingsReconciled()
            queries.putState(row.accountBinding, "next", row.canonicalPayload, row.canonicalSha256)
            assertEquals(0L, queries.getState().executeAsOne().settingsReconciliationPending)
            queries.markSettingsPending()
            queries.putState(row.accountBinding, "another", row.canonicalPayload, row.canonicalSha256)
            assertEquals(1L, queries.getState().executeAsOne().settingsReconciliationPending)
        }
    }

    @Test fun enclosingEngineTransactionRollbackAlsoRestoresCanonicalState() = database { db, store ->
        val checkpoint = checkpoint()
        store.replace(checkpoint.accountBinding, checkpoint)
        val before = db.localFavoriteItemQueries.getAll().executeAsList()
        assertFailsWith<IllegalStateException> {
            db.transaction {
                store.replace(checkpoint.accountBinding, checkpoint.copy(checkpointId = "next"))
                error("injected activation failure")
            }
        }
        assertEquals(checkpoint.checkpointId, store.read(checkpoint.accountBinding)?.checkpointId)
        assertEquals(before, db.localFavoriteItemQueries.getAll().executeAsList())
        val item = before.first()
        db.localFavoriteItemQueries.updateFavoriteItem("unsynced local title", "local-cover", item.lastUpdatedTime,
            item.forumId, item.forumName, item.authorId, item.lastFavoriteStatusUpdateAt, item.id)
        assertFalse(store.replace(checkpoint.accountBinding, checkpoint))
        assertEquals("unsynced local title", db.localFavoriteItemQueries.getAll().executeAsList().first { it.id == item.id }.title)
        assertTrue(store.replace(checkpoint.accountBinding, checkpoint.copy(checkpointId = "next")))
        assertEquals("local-cover", db.localFavoriteItemQueries.getAll().executeAsList()
            .first { it.targetType == item.targetType && it.targetId == item.targetId && it.authorId == item.authorId }.coverUrl)
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

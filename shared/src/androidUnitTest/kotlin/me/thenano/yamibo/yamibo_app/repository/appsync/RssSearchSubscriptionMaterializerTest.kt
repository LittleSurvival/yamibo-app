package me.thenano.yamibo.yamibo_app.repository.appsync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import me.thenano.yamibo.yamibo_app.Database
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.DatabaseSyncDomainMaterializer
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.OperationReducer
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.SqlDelightSyncDomainStateAdapter
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncAccountBinding
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncCausalContext
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncDeviceEpoch
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncDeviceId
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncDomainId
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncEntityId
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperation
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperationKind
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperationOrigin
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncSequence
import me.thenano.yamibo.yamibo_app.repository.rss.rssSearchSubscriptionSyncId
import me.thenano.yamibo.yamibo_app.store.settings.SettingsStore

class RssSearchSubscriptionMaterializerTest {
    @Test
    fun materializesPutPatchDeleteAndClearsOnlyRebuildableResults() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        val db = Database(driver)
        val adapter = SqlDelightSyncDomainStateAdapter(
            db,
            DatabaseSyncDomainMaterializer(db, MemorySettingsStore()),
            nowMillis = { 1_000 },
        )
        val entityId = rssSearchSubscriptionSyncId("app", null)
        val put = operation(
            sequence = 1,
            entityId = entityId,
            kind = SyncOperationKind.Put,
            fields = mapOf(
                "title" to "app",
                "query" to "app",
                "forumId" to null,
                "forumName" to null,
                "enabled" to "true",
                "createdAt" to "100",
                "updatedAt" to "100",
            ),
        )
        adapter.apply(OperationReducer().reduce(operations = listOf(put)))
        val subscription = db.rssSearchSubscriptionQueries.getAll().executeAsOne()
        assertEquals("app", subscription.title)
        assertEquals(1, subscription.enabled)

        db.rssSearchPageCacheQueries.upsert(subscription.id, 1, "{}", 100)
        db.rssSearchSubscriptionResultQueries.insertResultIgnore(
            subscription.id, 42, "thread", null, null, 0,
            "https://bbs.yamibo.com/thread-42-1-1.html",
            null, null, null, null, null, null, null, null, null, null,
            100, 100, null, 1, 0,
        )
        val patch = operation(
            sequence = 2,
            entityId = entityId,
            kind = SyncOperationKind.Patch,
            fields = mapOf("title" to "App feed", "enabled" to "false", "updatedAt" to "200"),
            context = SyncCausalContext().advance(put.replicaKey, put.sequence),
        )
        adapter.apply(OperationReducer().reduce(adapter.currentState(), listOf(patch)))
        val patched = db.rssSearchSubscriptionQueries.getAll().executeAsOne()
        assertEquals("App feed", patched.title)
        assertEquals(0, patched.enabled)
        assertEquals(1, db.rssSearchSubscriptionResultQueries.countBySubscription(patched.id).executeAsOne())

        val delete = operation(
            sequence = 3,
            entityId = entityId,
            kind = SyncOperationKind.Delete,
            fields = emptyMap(),
            context = SyncCausalContext()
                .advance(put.replicaKey, put.sequence)
                .advance(patch.replicaKey, patch.sequence),
        )
        adapter.apply(OperationReducer().reduce(adapter.currentState(), listOf(delete)))

        assertEquals(0, db.rssSearchSubscriptionQueries.getAll().executeAsList().size)
        assertEquals(0, db.rssSearchSubscriptionResultQueries.countBySubscription(subscription.id).executeAsOne())
        assertNull(db.rssSearchPageCacheQueries.getByPage(subscription.id, 1).executeAsOneOrNull())
    }

    private fun operation(
        sequence: Long,
        entityId: String,
        kind: SyncOperationKind,
        fields: Map<String, String?>,
        context: SyncCausalContext = SyncCausalContext(),
    ): SyncOperation {
        val deviceId = SyncDeviceId("device")
        val epoch = SyncDeviceEpoch("epoch")
        val syncSequence = SyncSequence(sequence)
        return SyncOperation(
            operationId = SyncOperation.idFor(deviceId, epoch, syncSequence),
            deviceId = deviceId,
            deviceEpoch = epoch,
            sequence = syncSequence,
            accountBinding = SyncAccountBinding("account"),
            domainId = SyncDomainId("rss.search-subscription"),
            entityId = SyncEntityId(entityId),
            kind = kind,
            fields = fields,
            causalContext = context,
            createdAtEpochMillis = 100,
            origin = SyncOperationOrigin.UserAction,
        )
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

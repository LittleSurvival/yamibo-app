package me.thenano.yamibo.yamibo_app.repository.appsync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
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
import me.thenano.yamibo.yamibo_app.repository.backup.favoriteUpdateEventIdentity
import me.thenano.yamibo.yamibo_app.store.settings.SettingsStore

class FavoriteUpdateMaterializerTest {
    @Test
    fun fieldlessRemoteTombstonesDeleteStableIdentityProjections() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        val db = Database(driver)
        val adapter = SqlDelightSyncDomainStateAdapter(
            db,
            DatabaseSyncDomainMaterializer(db, MemorySettingsStore()),
            nowMillis = { 1_000 },
        )
        val puts = listOf(
            operation(
                "a", 1, "favorite.item", "ThreadNormal|42|9", SyncOperationKind.Put,
                mapOf(
                    "targetType" to "ThreadNormal", "targetId" to "42", "authorId" to "9",
                    "title" to "favorite", "coverUrl" to null, "lastUpdatedTime" to null,
                    "forumId" to null, "forumName" to null, "createdAt" to "1",
                    "lastFavoriteStatusUpdateAt" to "1",
                ),
            ),
            operation(
                "a", 2, "detail-note", "Thread|42|9", SyncOperationKind.Put,
                mapOf(
                    "targetType" to "Thread", "targetId" to "42", "authorId" to "9",
                    "content" to "note", "createdAt" to "1", "updatedAt" to "1",
                ),
            ),
            operation(
                "a", 3, "bookmark", "Thread|42|7", SyncOperationKind.Put,
                mapOf(
                    "targetType" to "Thread", "parentId" to "42", "targetId" to "7",
                    "title" to "bookmark", "bookmarked" to "true", "read" to "false",
                    "createdAt" to "1", "updatedAt" to "1",
                ),
            ),
            operation(
                "a", 4, "reading.thread", "42|Normal|9|Direct", SyncOperationKind.Put,
                mapOf(
                    "threadId" to "42", "threadType" to "Normal", "authorId" to "9",
                    "historyOrigin" to "Direct", "threadName" to "thread", "threadCover" to null,
                    "forumName" to null, "forumId" to null, "page" to "1", "postId" to "7",
                    "postTitle" to "post", "anchorPostId" to "7", "anchorPostRatio" to null,
                    "anchorBlockId" to null, "anchorBlockType" to null, "anchorBlockRatio" to null,
                    "globalScrollY" to null, "viewportHeight" to null,
                    "firstVisibleItemIndex" to null, "firstVisibleItemOffset" to null,
                    "lastVisitTime" to "1", "lastUpdatedTime" to null,
                ),
            ),
            operation(
                "a", 5, "reading.image", "7", SyncOperationKind.Put,
                mapOf(
                    "postId" to "7", "threadId" to "42", "pageIndex" to "1",
                    "totalPages" to "2", "firstVisibleItemIndex" to null,
                    "firstVisibleItemOffset" to null, "lastVisitTime" to "1",
                ),
            ),
            operation(
                "a", 6, "reading.tag-manga", "21661", SyncOperationKind.Put,
                mapOf(
                    "tagId" to "21661", "tagName" to "tag", "tagPage" to "1",
                    "threadId" to "42", "threadTitle" to "thread",
                    "threadImagePageIndex" to "1", "threadImageTotalPages" to "2",
                    "firstVisibleItemIndex" to null, "firstVisibleItemOffset" to null,
                    "lastVisitTime" to "1", "coverUrl" to null,
                ),
            ),
        )
        adapter.apply(OperationReducer().reduce(adapter.currentState(), puts))

        assertEquals(1, db.localFavoriteItemQueries.getAll().executeAsList().size)
        assertEquals(1, db.detailNoteQueries.getAll().executeAsList().size)
        assertEquals(1, db.localBookMarkQueries.getAll().executeAsList().size)
        assertEquals(1, db.readingHistoryQueries.getAll().executeAsList().size)
        assertEquals(1, db.imageReadingHistoryQueries.getAll().executeAsList().size)
        assertEquals(1, db.mangaTagReadingHistoryQueries.getAll().executeAsList().size)

        val deletes = puts.mapIndexed { index, put ->
            operation(
                "b",
                (index + 1).toLong(),
                put.domainId.value,
                put.entityId.value,
                SyncOperationKind.Delete,
                emptyMap(),
            )
        }
        adapter.apply(OperationReducer().reduce(adapter.currentState(), deletes))

        assertEquals(0, db.localFavoriteItemQueries.getAll().executeAsList().size)
        assertEquals(0, db.detailNoteQueries.getAll().executeAsList().size)
        assertEquals(0, db.localBookMarkQueries.getAll().executeAsList().size)
        assertEquals(0, db.readingHistoryQueries.getAll().executeAsList().size)
        assertEquals(0, db.imageReadingHistoryQueries.getAll().executeAsList().size)
        assertEquals(0, db.mangaTagReadingHistoryQueries.getAll().executeAsList().size)
    }

    @Test
    fun materializesLifecycleAndChoicesAndPreservesTransientScannerState() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        val db = Database(driver)
        val adapter = SqlDelightSyncDomainStateAdapter(
            db,
            DatabaseSyncDomainMaterializer(db, MemorySettingsStore()),
            nowMillis = { 1_000 },
        )
        driver.execute(
            null,
            """
            INSERT INTO FavoriteUpdateRun(
                runId, status, phase, startedAt, updatedAt
            ) VALUES ('run', 'COMPLETED', 'COMPLETED', 1, 1)
            """.trimIndent(),
            0,
        )
        driver.execute(
            null,
            """
            INSERT INTO FavoriteUpdateTrackedTarget(
                targetType, targetId, authorId, title, mode, baselineReady
            ) VALUES ('ThreadNormal', 42, 0, 'title', 'NormalThread', 1)
            """.trimIndent(),
            0,
        )

        val put = eventPut()
        val read = operation(
            "a", 2, "favorite.update-event", put.entityId.value,
            SyncOperationKind.Patch, mapOf("readAt" to "200"),
            SyncCausalContext().advance(put.replicaKey, put.sequence),
        )
        val dismiss = operation(
            "a", 3, "favorite.update-event", put.entityId.value,
            SyncOperationKind.Patch, mapOf("dismissedAt" to "300"),
            SyncCausalContext()
                .advance(put.replicaKey, put.sequence)
                .advance(read.replicaKey, read.sequence),
        )
        val fid = operation(
            "a", 4, "favorite.update-fid-filter", "fid:1",
            SyncOperationKind.Put, mapOf("fid" to "1", "enabled" to "false"),
        )
        val category = operation(
            "a", 5, "favorite.update-category-filter", "category:missing",
            SyncOperationKind.Put,
            mapOf("categorySyncId" to "missing", "enabled" to "false"),
        )
        adapter.apply(OperationReducer().reduce(operations = listOf(put, read, dismiss, fid, category)))

        val event = db.favoriteUpdateEventQueries.getBySyncId(put.entityId.value).executeAsOne()
        assertEquals(200, event.readAt)
        assertEquals(300, event.dismissedAt)
        assertEquals(0, db.favoriteUpdateFidChoiceQueries.getByFid(1).executeAsOne().enabled)
        assertEquals(
            0,
            db.favoriteUpdateCategoryChoiceQueries.getBySyncId("missing").executeAsOne().enabled,
        )

        adapter.adoptCheckpoint(emptyList())

        assertEquals(0, db.favoriteUpdateEventQueries.getAll().executeAsList().size)
        assertEquals(0, db.favoriteUpdateFidChoiceQueries.getAll().executeAsList().size)
        assertEquals(0, db.favoriteUpdateCategoryChoiceQueries.getAll().executeAsList().size)
        assertEquals("run", db.favoriteUpdateRunQueries.getLatest().executeAsOne().runId)
        assertEquals(1, db.favoriteUpdateTrackedTargetQueries.getAll().executeAsList().size)
    }

    private fun eventPut(): SyncOperation {
        val identity = favoriteUpdateEventIdentity(
            "ThreadNormal", 42, null, "NormalThread", listOf(100), false,
            100, "new", "title", "post:100:revision:500",
        )
        return operation(
            "a", 1, "favorite.update-event", identity.syncId, SyncOperationKind.Put,
            mapOf(
                "targetType" to "ThreadNormal", "targetId" to "42", "authorId" to "0",
                "fid" to "1", "forumName" to "forum", "title" to "title",
                "latestPostTitle" to "post", "mode" to "NormalThread", "summary" to "new",
                "detailIds" to "100", "coverUrl" to null, "detectedAt" to "100",
                "ambiguous" to "false", "sourceFingerprint" to identity.sourceFingerprint,
                "sourceDiscriminator" to identity.sourceDiscriminator,
            ),
        )
    }

    private fun operation(
        device: String,
        sequence: Long,
        domain: String,
        entity: String,
        kind: SyncOperationKind,
        fields: Map<String, String?>,
        context: SyncCausalContext = SyncCausalContext(),
    ): SyncOperation {
        val deviceId = SyncDeviceId(device)
        val epoch = SyncDeviceEpoch("epoch")
        val syncSequence = SyncSequence(sequence)
        return SyncOperation(
            SyncOperation.idFor(deviceId, epoch, syncSequence),
            deviceId,
            epoch,
            syncSequence,
            SyncAccountBinding("account"),
            SyncDomainId(domain),
            SyncEntityId(entity),
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

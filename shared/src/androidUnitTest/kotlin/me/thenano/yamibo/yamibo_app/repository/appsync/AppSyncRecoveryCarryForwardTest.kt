package me.thenano.yamibo.yamibo_app.repository.appsync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.*
import kotlinx.serialization.json.Json
import me.thenano.yamibo.yamibo_app.Database
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.*
import me.thenano.yamibo.yamibo_app.repository.appsync.model.*
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.*
import me.thenano.yamibo.yamibo_app.store.appsync.*

class AppSyncRecoveryCarryForwardTest {
    @Test
    fun committedCarryForwardSurvivesClosingDatabaseAndReopeningOnDisk() {
        val databaseFile = java.nio.file.Files.createTempFile("appsync-carry-", ".db")
        val url = "jdbc:sqlite:${databaseFile.toAbsolutePath()}"
        try {
            val fixture = Fixture(url)
            fixture.appendLateOperations()
            fixture.recovery.activateCommittedRecovery(fixture.session.sessionId, 40)
            val pending = fixture.operations.pendingOperations()
            val installation = fixture.operations.installation()
            val mappings = fixture.database.appSyncOperationQueries.getRecoveryCarryForward(fixture.session.sessionId).executeAsList()
            fixture.driver.close()
            val reopenedDriver = JdbcSqliteDriver(url)
            try {
                val reopened = Database(reopenedDriver)
                SqlDelightAppSyncRecoveryStore(reopened).activateCommittedRecovery(fixture.session.sessionId, 50)
                val store = SqlDelightAppSyncOperationStore(reopened)
                assertEquals(pending, store.pendingOperations())
                assertEquals(installation, store.installation())
                assertEquals(mappings, reopened.appSyncOperationQueries.getRecoveryCarryForward(fixture.session.sessionId).executeAsList())
            } finally {
                reopenedDriver.close()
            }
        } finally {
            java.nio.file.Files.deleteIfExists(databaseFile)
        }
    }

    @Test
    fun migration44CreatesEmptyMappingWithoutRewritingOldPayloads() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.migrate(driver, oldVersion = 44, newVersion = 45)
        assertTrue(Database(driver).appSyncOperationQueries.getRecoveryCarryForward("old").executeAsList().isEmpty())
        driver.close()
    }

    @Test
    fun copiesPreserveOrderDeletionProofAndWinningProvenanceWithoutMaterializingData() {
        val fixture = Fixture()
        val late = fixture.appendLateOperations()
        fixture.operations.markPublishedUnverified(setOf(late[1].operationId))
        fixture.recovery.activateCommittedRecovery(fixture.session.sessionId, 40)

        val copies = fixture.operations.pendingOperations()
        assertEquals(late.size, copies.size)
        copies.zip(late).forEachIndexed { index, (copy, source) ->
            assertEquals(source.fields, copy.fields)
            assertEquals(source.entityGeneration, copy.entityGeneration)
            assertEquals(source.kind, copy.kind)
            assertEquals(source.createdAtEpochMillis, copy.createdAtEpochMillis)
            assertEquals(source.origin, copy.origin)
            assertEquals(source.bulkDeleteAuthorizationId, copy.bulkDeleteAuthorizationId)
            assertEquals(source.schemaVersion, copy.schemaVersion)
            assertEquals(fixture.session.targetDeviceId, copy.deviceId)
            assertEquals((index + 2).toLong(), copy.sequence.value)
            assertTrue(copy.causalContext.includes(source))
            assertTrue(copy.causalContext.includes(if (index == 0) fixture.shadow else copies[index - 1]))
            assertEquals(7L, copy.causalContext.asStableMap()["peer:epoch"])
        }
        val mapped = fixture.database.appSyncOperationQueries.getRecoveryCarryForward(fixture.session.sessionId).executeAsList()
        assertEquals(late.map { it.operationId.value }, mapped.map { it.sourceOperationId })
        assertEquals(copies.map { it.operationId.value }, mapped.map { it.replacementOperationId })
        // Original bodies remain intact and unacknowledged until replacement coverage exists.
        val originalRows = fixture.operations.allOutboxOperations().associateBy { it.first.operationId }
        late.forEach { assertEquals(it, originalRows.getValue(it.operationId).first) }
        assertEquals(AppSyncOperationLifecycle.PublishedUnverified, originalRows.getValue(late[1].operationId).second)
        val states = fixture.database.appSyncOperationQueries.getResolvedEntities().executeAsList().map {
            Json.decodeFromString(ResolvedSyncEntity.serializer(), it.encodedState)
        }
        assertEquals(copies[1], states.single { it.key.entityId.value == "note" }.fields.getValue("content").operation)
        assertEquals("second", states.single { it.key.entityId.value == "note" }.fields.getValue("content").value)
        assertEquals(copies[2], states.single { it.key.entityId.value == "deleted" }.tombstone)
        assertEquals(copies[3], states.single { it.key.entityId.value == "relation" }.relationOperation)
        assertFalse(states.single { it.key.entityId.value == "relation" }.relationPresent!!)
        assertEquals("local-cover", fixture.database.localFavoriteItemQueries.getAll().executeAsOne().coverUrl)

        val forward = OperationReducer().reduce(operations = copies)
        val backward = OperationReducer().reduce(operations = copies.reversed())
        assertEquals(forward.entities, backward.entities)
        assertTrue(forward.quarantined.isEmpty())
        assertEquals("second", forward.entities.values.single { it.key.entityId.value == "note" }.fields["content"]?.value)
        val after = fixture.operations.appendLocalOperation(
            fixture.account, SyncDomainId("settings"), SyncEntityId("appsettings.thememode"), 1,
            SyncOperationKind.Patch, mapOf("type" to "string", "value" to "dark"), fixture.operations.causalContext(),
            50, SyncOperationOrigin.UserAction,
        )
        assertEquals(copies.last().sequence.value + 1, after.sequence.value)
        assertTrue(after.causalContext.includes(copies.last()))
        fixture.driver.close()
    }

    @Test
    fun failureAtTransactionCommitRollsBackQueueMappingsProvenanceAndWriterThenResumesOnce() {
        val fixture = Fixture()
        fixture.appendLateOperations()
        val rowsBefore = fixture.operations.allOutboxOperations()
        val writerBefore = fixture.operations.installation()
        val stateBefore = fixture.database.appSyncOperationQueries.getResolvedEntities().executeAsList()
        val causalBefore = fixture.operations.causalContext()
        fixture.driver.execute(null, """
            CREATE TRIGGER fail_recovery_activation BEFORE UPDATE ON AppSyncRecoverySession
            WHEN NEW.phase = 'COMPLETED'
            BEGIN SELECT RAISE(ABORT, 'simulated activation failure'); END
        """.trimIndent(), 0)
        assertFails { fixture.recovery.activateCommittedRecovery(fixture.session.sessionId, 40) }
        assertEquals(rowsBefore, fixture.operations.allOutboxOperations())
        assertEquals(writerBefore, fixture.operations.installation())
        assertEquals(stateBefore, fixture.database.appSyncOperationQueries.getResolvedEntities().executeAsList())
        assertEquals(causalBefore, fixture.operations.causalContext())
        assertTrue(fixture.database.appSyncOperationQueries.getRecoveryCarryForward(fixture.session.sessionId).executeAsList().isEmpty())
        assertEquals(AppSyncRecoveryPhase.ActivatingLocal, fixture.recovery.session(fixture.session.sessionId)?.phase)
        fixture.driver.execute(null, "DROP TRIGGER fail_recovery_activation", 0)
        SqlDelightAppSyncRecoveryStore(fixture.database).activateCommittedRecovery(fixture.session.sessionId, 41)
        val after = fixture.operations.allOutboxOperations()
        SqlDelightAppSyncRecoveryStore(fixture.database).activateCommittedRecovery(fixture.session.sessionId, 42)
        assertEquals(after, fixture.operations.allOutboxOperations())
        assertEquals(4, fixture.operations.pendingOperations().size)
        fixture.driver.close()
    }

    private class Fixture(url: String = JdbcSqliteDriver.IN_MEMORY) {
        val driver = JdbcSqliteDriver(url)
        val database: Database
        val operations: SqlDelightAppSyncOperationStore
        val recovery: SqlDelightAppSyncRecoveryStore
        val account = SyncAccountBinding("synthetic-account")
        val session: AppSyncRecoverySession
        val shadow: SyncOperation
        init {
            Database.Schema.create(driver)
            database = Database(driver)
            operations = SqlDelightAppSyncOperationStore(database)
            operations.initialize("generation")
            operations.bindAccount(account, AppSyncInstallationState.Active)
            recovery = SqlDelightAppSyncRecoveryStore(database)
            val source = operations.appendLocalOperation(
                account, SyncDomainId("settings"), SyncEntityId("appsettings.thememode"), 1,
                SyncOperationKind.Patch, mapOf("type" to "string", "value" to "light"), SyncCausalContext(),
                1, SyncOperationOrigin.UserAction,
            )
            session = recovery.createOrResume(account, setOf(source.operationId.value), "replacement", 10)
            shadow = source.copy(
                operationId = SyncOperation.idFor(session.targetDeviceId, session.targetDeviceEpoch, SyncSequence(1)),
                deviceId = session.targetDeviceId, deviceEpoch = session.targetDeviceEpoch, sequence = SyncSequence(1),
            )
            recovery.stageOperations(session.sessionId, listOf(shadow))
            recovery.saveSegmentIntent(session.sessionId, 0, 1, "segment", null)
            recovery.markSegmentVerified(session.sessionId, 0, "segment", 301, 20)
            recovery.transition(session.sessionId, AppSyncRecoveryPhase.PublishingSegments, AppSyncRecoveryPhase.PublishingRoot, 21)
            recovery.markRootVerified(session.sessionId, 401, "root", 22)
            recovery.markIndexCommitted(session.sessionId, 23)
            database.localFavoriteItemQueries.insertFavoriteItem(
                targetType = "ThreadNormal", targetId = 1, title = "local-only", coverUrl = "local-cover",
                lastUpdatedTime = 1, forumId = 1, forumName = "forum", authorId = 0, createdAt = 1,
                lastFavoriteStatusUpdateAt = 1,
            )
        }

        fun appendLateOperations(): List<SyncOperation> {
            val materializer = object : SyncDomainMaterializer {
                override fun apply(entity: ResolvedSyncEntity) = error("Local content must not be rematerialized")
                override fun reconcileProjections() = error("Local content must not be rematerialized")
            }
            val state = SqlDelightSyncDomainStateAdapter(database, materializer, nowMillis = { 30 })
            val types = listOf(SyncOperationKind.Patch, SyncOperationKind.Patch, SyncOperationKind.Delete, SyncOperationKind.RelationRemove)
            val ids = listOf("note", "note", "deleted", "relation")
            return types.mapIndexed { index, kind ->
                val fields = if (index < 2) mapOf("content" to if (index == 0) "first" else "second")
                    else if (kind == SyncOperationKind.Delete) mapOf(
                        AppSyncBulkDeleteProofFields.SCOPE to "synthetic-scope",
                        AppSyncBulkDeleteProofFields.COUNT to "1",
                        AppSyncBulkDeleteProofFields.EXPIRES_AT to "1000",
                    ) else mapOf("targetType" to "ThreadNormal", "targetId" to "1", "authorId" to "0", "categorySyncId" to "category")
                operations.appendLocalOperation(
                    account, SyncDomainId(if (index == 3) "favorite.item-category" else "detail-note"),
                    SyncEntityId(ids[index]), 3, kind, fields, SyncCausalContext(mapOf("peer:epoch" to 7)),
                    30L + index, SyncOperationOrigin.UserAction,
                    bulkDeleteAuthorizationId = "proof".takeIf { index == 2 },
                    localMutation = state::recordLocal,
                )
            }
        }
    }
}

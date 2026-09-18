package me.thenano.yamibo.yamibo_app.repository.appsync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncRecoveryPhase
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncRecoveryMode
import me.thenano.yamibo.yamibo_app.Database
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncInstallationState
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncOperationLifecycle
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncLegacyOperationClassifier
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncLegacyRecoveryPlanner
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncRecoveryOperationStager
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncAccountBinding
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncCausalContext
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncDomainId
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncEntityId
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperation
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperationKind
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperationOrigin
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncSequence
import me.thenano.yamibo.yamibo_app.store.appsync.SqlDelightAppSyncOperationStore
import me.thenano.yamibo.yamibo_app.store.appsync.SqlDelightAppSyncRecoveryStore

class SqlDelightAppSyncRecoveryStoreTest {
    @Test fun nativeJournalPinRequiresSessionWriterIdentity() {
        val fixture = fixture()
        val recovery = SqlDelightAppSyncRecoveryStore(fixture.database)
        val session = recovery.createOrResumeSegmentedJournal(fixture.account, setOf(fixture.source.operationId.value), "source", 10)
        val installation = requireNotNull(fixture.operations.installation())
        val identity = "${installation.deviceId.value}:${installation.deviceEpoch.value}"
        val journal = me.thenano.yamibo.yamibo_app.repository.appsync.schema.AppSyncCanonicalJournal(
            me.thenano.yamibo.yamibo_app.repository.appsync.schema.AppSyncCanonicalOperationBlock(fixture.account.value, emptyList()),
            installation.deviceId.value, installation.deviceEpoch.value, installation.writerNonce.value,
            0, 0, emptyMap(), emptyList(), 1, 3, 3, "test", 0)
        val codec = me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncV3DocumentCodec()
        assertFailsWith<IllegalArgumentException> {
            recovery.pinPayload(session.sessionId, "Journal", identity, 3) {
                codec.encodeJournal(identity, journal.copy(writerNonce = "restored"))
            }
        }
        assertNull(fixture.database.appSyncOperationQueries.getRecoveryPayload(session.sessionId).executeAsOneOrNull())
        val encoded = codec.encodeJournal(identity, journal)
        assertEquals(encoded, recovery.pinPayload(session.sessionId, "Journal", identity, 3) { encoded })
        recovery.rollbackPreCommit(session.sessionId)
        assertNull(fixture.database.appSyncOperationQueries.getRecoveryPayload(session.sessionId).executeAsOneOrNull())
        assertEquals(listOf(fixture.source), fixture.operations.pendingOperations())
    }

    @Test fun nativePayloadAndRootIntentSurviveRestartWithoutChangingProtocolOrBody() {
        val fixture = fixture()
        val recovery = SqlDelightAppSyncRecoveryStore(fixture.database)
        val session = recovery.createOrResumeSegmentedCheckpoint(fixture.account, "native", "source", 10)
        val document = me.thenano.yamibo.yamibo_app.repository.appsync.schema.AppSyncCanonicalCheckpoint(
            "native", fixture.account.value, 1, emptyMap(), emptyList())
        val envelope = me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncV3DocumentCodec().encodeCheckpoint(document)
        assertEquals(envelope, recovery.pinPayload(session.sessionId, "Checkpoint", "native", 3) { envelope })
        val restarted = SqlDelightAppSyncRecoveryStore(fixture.database)
        assertEquals(envelope, restarted.pinPayload(session.sessionId, "Checkpoint", "native", 3) { error("Must use frozen bytes") })
        assertFailsWith<IllegalArgumentException> { restarted.pinPayload(session.sessionId, "Checkpoint", "native") { "legacy" } }
        restarted.startSegmentedJournal(session.sessionId, 11)
        assertFailsWith<IllegalArgumentException> { restarted.pinNativeRootIntent(session.sessionId, "a".repeat(64)) }
        restarted.saveSegmentIntent(session.sessionId, 0, 1, "segment", null)
        restarted.transition(session.sessionId, AppSyncRecoveryPhase.PublishingSegments, AppSyncRecoveryPhase.PublishingRoot, 12)
        assertFailsWith<IllegalArgumentException> { restarted.pinNativeRootIntent(session.sessionId, "a".repeat(64)) }
        restarted.markSegmentVerified(session.sessionId, 0, "segment", 42, 13)
        assertTrue(restarted.pinNativeRootIntent(session.sessionId, "a".repeat(64)))
        val again = SqlDelightAppSyncRecoveryStore(fixture.database)
        assertEquals(false, again.pinNativeRootIntent(session.sessionId, "a".repeat(64)))
        assertFailsWith<IllegalArgumentException> { again.pinNativeRootIntent(session.sessionId, "b".repeat(64)) }
        assertFailsWith<IllegalArgumentException> { again.markRootVerified(session.sessionId, 43, "b".repeat(64), 14) }
        assertEquals(AppSyncRecoveryPhase.PublishingRoot, again.session(session.sessionId)?.phase)
        again.markRootVerified(session.sessionId, 43, "a".repeat(64), 14)
        assertEquals(AppSyncRecoveryPhase.CommittingIndex, again.session(session.sessionId)?.phase)
        assertEquals(false, again.pinNativeRootIntent(session.sessionId, "a".repeat(64)))
        assertEquals(false, again.session(session.sessionId)?.indexCommitted)
        assertTrue(fixture.operations.pendingOperations().isNotEmpty())
    }

    @Test fun nativeRecoveryRejectsWrongAccountIdentityAndLegacySessionBeforePinning() {
        val fixture = fixture()
        val recovery = SqlDelightAppSyncRecoveryStore(fixture.database)
        val session = recovery.createOrResumeSegmentedCheckpoint(fixture.account, "native", "source", 10)
        val codec = me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncV3DocumentCodec()
        val cp = me.thenano.yamibo.yamibo_app.repository.appsync.schema.AppSyncCanonicalCheckpoint(
            "native", fixture.account.value, 1, emptyMap(), emptyList())
        for (body in listOf("legacy body", codec.encodeCheckpoint(cp.copy(accountBinding = "other")),
            codec.encodeCheckpoint(cp.copy(checkpointId = "other")))) {
            assertFailsWith<Exception> { recovery.pinPayload(session.sessionId, "Checkpoint", "native", 3) { body } }
            assertNull(fixture.database.appSyncOperationQueries.getRecoveryPayload(session.sessionId).executeAsOneOrNull())
        }
        recovery.rollbackPreCommit(session.sessionId)
        val legacy = recovery.createOrResume(fixture.account, setOf(fixture.source.operationId.value), "legacy", 20)
        assertFailsWith<IllegalArgumentException> { recovery.pinPayload(legacy.sessionId, "Journal", "native", 3) { "invalid" } }
    }

    @Test fun migration48KeepsExistingPayloadInLegacyTransportWithoutInventingRootIntent() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            driver.execute(null, "CREATE TABLE AppSyncRecoverySession (sessionId TEXT NOT NULL PRIMARY KEY)", 0)
            driver.execute(null, "INSERT INTO AppSyncRecoverySession VALUES ('existing')", 0)
            Database.Schema.migrate(driver, 43, 44)
            driver.execute(null, "INSERT INTO AppSyncRecoveryPayload VALUES ('existing', 'Journal', 'replica', 'body', 'digest')", 0)
            Database.Schema.migrate(driver, 48, 49)
            Database.Schema.migrate(driver, 49, 50)
            val row = Database(driver).appSyncOperationQueries.getRecoveryPayload("existing").executeAsOne()
            assertEquals("body", row.canonicalEnvelope)
            assertEquals("digest", row.envelopeFingerprint)
            assertEquals(2L, row.transportVersion)
            assertNull(row.rootIntentFingerprint)
            assertNull(row.verifiedIndexBlogId)
            assertNull(row.verifiedIndexFingerprint)
            assertNull(row.indexVerifiedAtEpochMillis)
        }
    }

    @Test
    fun pinnedPayloadSurvivesRestartAndRollbackRemovesIt() {
        val fixture = fixture()
        val recovery = SqlDelightAppSyncRecoveryStore(fixture.database)
        val session = recovery.createOrResume(fixture.account, setOf(fixture.source.operationId.value), "pin", 10)
        assertEquals("first-envelope", recovery.pinPayload(session.sessionId, "Journal", "replica") { "first-envelope" })
        val restarted = SqlDelightAppSyncRecoveryStore(fixture.database)
        assertEquals("first-envelope", restarted.pinPayload(session.sessionId, "Journal", "replica") {
            error("A persisted envelope must not be regenerated")
        })
        assertFailsWith<IllegalArgumentException> {
            restarted.pinPayload(session.sessionId, "Journal", "another-replica") { "changed" }
        }
        assertFailsWith<IllegalArgumentException> {
            restarted.pinPayload(session.sessionId, "Checkpoint", "replica") { "changed" }
        }
        restarted.rollbackPreCommit(session.sessionId)
        assertNull(fixture.database.appSyncOperationQueries.getRecoveryPayload(session.sessionId).executeAsOneOrNull())
    }

    @Test
    fun migration43PreservesExistingSessionWithoutInventingPayload() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(null, "CREATE TABLE AppSyncRecoverySession (sessionId TEXT NOT NULL PRIMARY KEY)", 0)
        driver.execute(null, "INSERT INTO AppSyncRecoverySession VALUES ('existing')", 0)
        Database.Schema.migrate(driver, oldVersion = 43, newVersion = 44)
        val count = driver.executeQuery(null, "SELECT count(*) FROM AppSyncRecoverySession", { cursor ->
            cursor.next()
            app.cash.sqldelight.db.QueryResult.Value(cursor.getLong(0))
        }, 0).value
        assertEquals(1L, count)
        val payloadCount = driver.executeQuery(null, "SELECT count(*) FROM AppSyncRecoveryPayload", { cursor ->
            cursor.next(); app.cash.sqldelight.db.QueryResult.Value(cursor.getLong(0))
        }, 0).value
        assertEquals(0L, payloadCount)
    }

    @Test
    fun migration39CreatesDurableRecoverySchema() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)

        Database.Schema.migrate(driver, oldVersion = 39, newVersion = 40)

        val tables = driver.executeQuery(
            identifier = null,
            sql = "SELECT name FROM sqlite_master WHERE type = 'table' AND name LIKE 'AppSyncRecovery%'",
            mapper = { cursor ->
                app.cash.sqldelight.db.QueryResult.Value(
                    buildSet {
                        while (cursor.next().value) add(requireNotNull(cursor.getString(0)))
                    },
                )
            },
            parameters = 0,
        ).value
        assertEquals(
            setOf(
                "AppSyncRecoverySession",
                "AppSyncRecoveryShadowOperation",
                "AppSyncRecoverySegmentWrite",
            ),
            tables,
        )
    }

    @Test
    fun migration40CreatesDurableCleanupObservationSchema() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)

        Database.Schema.migrate(driver, oldVersion = 40, newVersion = 41)

        val columns = driver.executeQuery(
            identifier = null,
            sql = "PRAGMA table_info('AppSyncCleanupObservation')",
            mapper = { cursor ->
                app.cash.sqldelight.db.QueryResult.Value(
                    buildSet {
                        while (cursor.next().value) add(requireNotNull(cursor.getString(1)))
                    },
                )
            },
            parameters = 0,
        ).value
        assertTrue(
            columns.containsAll(
                setOf(
                    "generationId",
                    "observationCount",
                    "firstObservedAtEpochMillis",
                    "lastObservedAtEpochMillis",
                    "deletedBlogIdsJson",
                ),
            ),
        )
    }

    @Test
    fun migration41AddsRecoveryPayloadProgressWithoutRewritingSessions() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(
            null,
            "CREATE TABLE AppSyncRecoverySession (sessionId TEXT NOT NULL PRIMARY KEY)",
            0,
        )

        Database.Schema.migrate(driver, oldVersion = 41, newVersion = 42)

        val columns = driver.executeQuery(
            null,
            "PRAGMA table_info('AppSyncRecoverySession')",
            { cursor ->
                app.cash.sqldelight.db.QueryResult.Value(
                    buildSet {
                        while (cursor.next().value) add(requireNotNull(cursor.getString(1)))
                    },
                )
            },
            0,
        ).value
        assertTrue(columns.containsAll(setOf("encodedChars", "targetBudgetChars")))
    }

    @Test
    fun migration42AddsStableRetryIdentityWithoutRewritingSessions() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(
            null,
            "CREATE TABLE AppSyncRecoverySession (sessionId TEXT NOT NULL PRIMARY KEY)",
            0,
        )

        Database.Schema.migrate(driver, oldVersion = 42, newVersion = 43)

        val columns = driver.executeQuery(
            null,
            "PRAGMA table_info('AppSyncRecoverySession')",
            { cursor ->
                app.cash.sqldelight.db.QueryResult.Value(
                    buildSet {
                        while (cursor.next().value) add(requireNotNull(cursor.getString(1)))
                    },
                )
            },
            0,
        ).value
        assertTrue(columns.contains("retryIdentity"))
    }
    @Test
    fun everyRecoveryPhaseSurvivesStoreRestart() {
        AppSyncRecoveryPhase.entries.forEach { phase ->
            val fixture = fixture()
            val recovery = SqlDelightAppSyncRecoveryStore(fixture.database)
            val session = recovery.createOrResume(
                fixture.account, setOf(fixture.source.operationId.value), "phase-${phase.name}", 10,
            )
            if (phase != AppSyncRecoveryPhase.Classifying) {
                recovery.transition(session.sessionId, AppSyncRecoveryPhase.Classifying, phase, 20)
            }

            assertEquals(
                phase,
                SqlDelightAppSyncRecoveryStore(fixture.database).session(session.sessionId)?.phase,
            )
        }
    }

    @Test
    fun segmentedJournalCommitAcknowledgesSourcesWithoutRotatingReplicaIdentity() {
        val fixture = fixture()
        val recovery = SqlDelightAppSyncRecoveryStore(fixture.database)
        val session = recovery.createOrResumeSegmentedJournal(
            fixture.account,
            setOf(fixture.source.operationId.value),
            "segmented-payload",
            10,
        )
        assertEquals(AppSyncRecoveryMode.SegmentedJournal, session.mode)
        assertEquals(fixture.installation.deviceId, session.targetDeviceId)
        assertEquals(fixture.installation.deviceEpoch, session.targetDeviceEpoch)
        recovery.startSegmentedJournal(session.sessionId, 11)
        recovery.saveSegmentIntent(session.sessionId, 0, 1, "chunk", null)
        recovery.markSegmentVerified(session.sessionId, 0, "chunk", 301, 20)
        recovery.transition(
            session.sessionId, AppSyncRecoveryPhase.PublishingSegments,
            AppSyncRecoveryPhase.PublishingRoot, 21,
        )
        recovery.markRootVerified(session.sessionId, 401, "root", 22)
        recovery.markIndexCommitted(session.sessionId, 23)

        recovery.activateCommittedSession(session.sessionId, 24)

        val after = requireNotNull(fixture.operations.installation())
        assertEquals(fixture.installation.deviceId, after.deviceId)
        assertEquals(fixture.installation.deviceEpoch, after.deviceEpoch)
        assertEquals(fixture.installation.writerNonce, after.writerNonce)
        assertEquals(401, after.journalBlogId)
        assertEquals(
            AppSyncOperationLifecycle.Acknowledged,
            fixture.operations.allOutboxOperations().single().second,
        )
        assertEquals(AppSyncRecoveryPhase.Completed, recovery.session(session.sessionId)?.phase)
    }

    @Test
    fun completedSessionDoesNotBlockTheNextSegmentedGeneration() {
        val fixture = fixture()
        val recovery = SqlDelightAppSyncRecoveryStore(fixture.database)
        val first = recovery.createOrResumeSegmentedJournal(
            fixture.account, setOf(fixture.source.operationId.value), "generation-one", 10,
        )
        recovery.startSegmentedJournal(first.sessionId, 11)
        recovery.saveSegmentIntent(first.sessionId, 0, 1, "chunk-one", null)
        recovery.markSegmentVerified(first.sessionId, 0, "chunk-one", 301, 12)
        recovery.transition(
            first.sessionId, AppSyncRecoveryPhase.PublishingSegments,
            AppSyncRecoveryPhase.PublishingRoot, 13,
        )
        recovery.markRootVerified(first.sessionId, 401, "root-one", 14)
        recovery.markIndexCommitted(first.sessionId, 15)
        recovery.activateCommittedSession(first.sessionId, 16)
        val nextSource = fixture.operations.appendLocalOperation(
            fixture.account, SyncDomainId("settings"), SyncEntityId("appsettings.thememode"), 1,
            SyncOperationKind.Patch, mapOf("type" to "string", "value" to "light"),
            fixture.operations.causalContext(), 17, SyncOperationOrigin.UserAction,
        )

        val second = recovery.createOrResumeSegmentedJournal(
            fixture.account, setOf(nextSource.operationId.value), "generation-two", 18,
        )

        assertNotEquals(first.sessionId, second.sessionId)
        assertNull(recovery.session(first.sessionId))
        assertEquals(AppSyncRecoveryPhase.Classifying, second.phase)
    }

    @Test
    fun contentFreeRecoveryCompletionAcknowledgesPresentAndSupersedesAbsentSources() {
        val fixture = fixture()
        val absent = fixture.operations.appendLocalOperation(
            fixture.account, SyncDomainId("settings"), SyncEntityId("appsettings.backupfolderuri"), 1,
            SyncOperationKind.Patch, mapOf("value" to "content://local"),
            fixture.operations.causalContext(), 5, SyncOperationOrigin.UserAction,
        )
        val recovery = SqlDelightAppSyncRecoveryStore(fixture.database)
        val before = requireNotNull(fixture.operations.installation())
        val session = recovery.createOrResume(
            fixture.account,
            setOf(fixture.source.operationId.value, absent.operationId.value),
            "content-free",
            10,
            acknowledgedSourceOperationIds = setOf(fixture.source.operationId.value),
        )

        recovery.completeVerifiedRecoveryWithoutPublication(session.sessionId, 11)

        val rows = fixture.operations.allOutboxOperations().associate { it.first.operationId to it.second }
        assertEquals(AppSyncOperationLifecycle.Acknowledged, rows[fixture.source.operationId])
        assertEquals(AppSyncOperationLifecycle.SupersededByRecovery, rows[absent.operationId])
        assertEquals(before, fixture.operations.installation())
        assertEquals(AppSyncRecoveryPhase.Completed, recovery.session(session.sessionId)?.phase)
    }

    @Test
    fun partialSegmentRowSetCannotVerifyRoot() {
        val fixture = fixture()
        val recovery = SqlDelightAppSyncRecoveryStore(fixture.database)
        val session = recovery.createOrResume(
            fixture.account, setOf(fixture.source.operationId.value), "partial", 10,
        )
        recovery.stageOperations(session.sessionId, listOf(shadowOperation(session)))
        recovery.saveSegmentIntent(session.sessionId, 0, 2, "chunk-0", 222)
        recovery.markSegmentVerified(session.sessionId, 0, "chunk-0", 111, 20)
        recovery.transition(
            session.sessionId, AppSyncRecoveryPhase.PublishingSegments,
            AppSyncRecoveryPhase.PublishingRoot, 21,
        )

        assertFailsWith<IllegalArgumentException> {
            recovery.markRootVerified(session.sessionId, 333, "root", 22)
        }
        assertEquals(AppSyncRecoveryPhase.PublishingRoot, recovery.session(session.sessionId)?.phase)
        assertEquals(AppSyncOperationLifecycle.PendingLocal, fixture.operations.allOutboxOperations().single().second)
        assertEquals(fixture.installation, fixture.operations.installation())
    }

    @Test
    fun activationTransactionFailureRollsBackAllLifecycleIdentityAndDomainChanges() {
        val fixture = fixture()
        fixture.database.localFavoriteItemQueries.insertFavoriteItem(
            targetType = "ThreadNormal", targetId = 1, title = "local-domain-data",
            coverUrl = null, lastUpdatedTime = 1, forumId = 1, forumName = "forum",
            authorId = 0, createdAt = 1, lastFavoriteStatusUpdateAt = 1,
        )
        fixture.database.appSyncOperationQueries.upsertCausalWatermark("remote:epoch", 9)
        val causalBefore = fixture.operations.causalContext()
        val recovery = SqlDelightAppSyncRecoveryStore(fixture.database)
        val session = recovery.createOrResume(
            fixture.account, setOf(fixture.source.operationId.value), "transaction-failure", 10,
        )
        val first = shadowOperation(session)
        val secondSequence = SyncSequence(session.targetFirstSequence + 1)
        val second = first.copy(
            operationId = SyncOperation.idFor(
                session.targetDeviceId, session.targetDeviceEpoch, secondSequence,
            ),
            sequence = secondSequence,
            entityId = SyncEntityId("second"),
        )
        recovery.stageOperations(session.sessionId, listOf(first, second))
        recovery.saveSegmentIntent(session.sessionId, 0, 1, "chunk", null)
        recovery.markSegmentVerified(session.sessionId, 0, "chunk", 301, 20)
        recovery.transition(
            session.sessionId, AppSyncRecoveryPhase.PublishingSegments,
            AppSyncRecoveryPhase.PublishingRoot, 21,
        )
        recovery.markRootVerified(session.sessionId, 401, "root", 22)
        recovery.markIndexCommitted(session.sessionId, 23)
        fixture.database.appSyncOperationQueries.insertOutboxOperation(
            operationId = second.operationId.value,
            deviceId = second.deviceId.value,
            deviceEpoch = second.deviceEpoch.value,
            sequence = second.sequence.value,
            accountBinding = second.accountBinding.value,
            domainId = second.domainId.value,
            entityId = "conflicting-existing-row",
            entityGeneration = second.entityGeneration,
            kind = second.kind.name,
            fieldsJson = Json.encodeToString(second.fields),
            causalContextJson = Json.encodeToString(SyncCausalContext.serializer(), second.causalContext),
            createdAtEpochMillis = second.createdAtEpochMillis,
            origin = second.origin.name,
            bulkDeleteAuthorizationId = second.bulkDeleteAuthorizationId,
            schemaVersion = second.schemaVersion.toLong(),
            lifecycle = "PENDING_LOCAL",
            acknowledgedAtEpochMillis = null,
        )

        assertFailsWith<IllegalArgumentException> {
            recovery.activateCommittedRecovery(session.sessionId, 24)
        }

        val rows = fixture.operations.allOutboxOperations().associate { it.first.operationId to it.second }
        assertEquals(AppSyncOperationLifecycle.PendingLocal, rows[fixture.source.operationId])
        assertNull(rows[first.operationId])
        assertEquals(AppSyncOperationLifecycle.PendingLocal, rows[second.operationId])
        assertEquals(fixture.installation, fixture.operations.installation())
        assertEquals(causalBefore, fixture.operations.causalContext())
        assertEquals("local-domain-data", fixture.database.localFavoriteItemQueries.getAll().executeAsOne().title)
        assertEquals(AppSyncRecoveryPhase.ActivatingLocal, recovery.session(session.sessionId)?.phase)
    }

    @Test
    fun shadowStagingPreservesDestructiveRelationAndCausalSemantics() {
        val fixture = fixture()
        val delete = fixture.operations.appendLocalOperation(
            fixture.account, SyncDomainId("detail-note"), SyncEntityId("note"), 7,
            SyncOperationKind.Delete, emptyMap(), SyncCausalContext(), 2,
            SyncOperationOrigin.UserAction, bulkDeleteAuthorizationId = "bulk-proof",
        )
        val relationRemove = fixture.operations.appendLocalOperation(
            fixture.account, SyncDomainId("favorite.item-category"), SyncEntityId("relation"), 3,
            SyncOperationKind.RelationRemove,
            mapOf("targetId" to "1", "categorySyncId" to "category"),
            SyncCausalContext(), 3, SyncOperationOrigin.UserAction,
        )
        val sources = listOf(fixture.source, delete, relationRemove)
        val recovery = SqlDelightAppSyncRecoveryStore(fixture.database)
        val session = recovery.createOrResume(
            fixture.account, sources.mapTo(linkedSetOf()) { it.operationId.value },
            "mixed-replacement", 10,
        )
        val classified = AppSyncLegacyOperationClassifier()
            .classify(sources, emptySet(), authoritativeAbsence = true)
            .map { it.copy(requiresRecovery = true) }
        val plan = AppSyncLegacyRecoveryPlanner().plan(classified)

        val staged = AppSyncRecoveryOperationStager(recovery).stage(session.sessionId, plan)

        val byEntity = staged.associateBy { it.entityId }
        listOf(delete, relationRemove).forEach { source ->
            val replacement = requireNotNull(byEntity[source.entityId])
            assertEquals(source.kind, replacement.kind)
            assertEquals(source.fields, replacement.fields)
            assertEquals(source.entityGeneration, replacement.entityGeneration)
            assertEquals(source.causalContext, replacement.causalContext)
            assertEquals(source.bulkDeleteAuthorizationId, replacement.bulkDeleteAuthorizationId)
        }
        assertTrue(staged.all {
            it.deviceId == session.targetDeviceId && it.deviceEpoch == session.targetDeviceEpoch
        })
        assertEquals(
            (session.targetFirstSequence until session.targetFirstSequence + staged.size).toList(),
            staged.map { it.sequence.value },
        )
    }

    @Test
    fun restartReusesShadowIdentityAndVerifiedSegmentProgress() {
        val fixture = fixture()
        val recovery = SqlDelightAppSyncRecoveryStore(fixture.database)
        val first = recovery.createOrResume(
            fixture.account, setOf(fixture.source.operationId.value), "replacement-fingerprint", 10,
        )
        val resumed = SqlDelightAppSyncRecoveryStore(fixture.database).createOrResume(
            fixture.account, setOf(fixture.source.operationId.value), "replacement-fingerprint", 20,
        )
        assertEquals(first, resumed)
        assertNotEquals(fixture.installation.deviceEpoch, resumed.targetDeviceEpoch)
        assertEquals(1L, resumed.targetFirstSequence)

        val shadow = shadowOperation(resumed)
        recovery.stageOperations(first.sessionId, listOf(shadow))
        recovery.saveSegmentIntent(first.sessionId, 0, 2, "chunk-0", nextBlogId = 222)
        recovery.saveSegmentIntent(first.sessionId, 1, 2, "chunk-1", nextBlogId = null)
        recovery.markSegmentVerified(first.sessionId, 1, "chunk-1", blogId = 222, verifiedAtEpochMillis = 30)

        val restarted = SqlDelightAppSyncRecoveryStore(fixture.database)
        assertEquals(listOf(shadow), restarted.shadowOperations(first.sessionId))
        val segments = restarted.segmentWrites(first.sessionId)
        assertEquals(2, segments.size)
        assertEquals(222, segments.single { it.segmentIndex == 1 }.blogId)
        assertNull(segments.single { it.segmentIndex == 0 }.blogId)
        assertEquals(fixture.installation, fixture.operations.installation())
        assertEquals(
            AppSyncOperationLifecycle.PendingLocal,
            fixture.operations.allOutboxOperations().single().second,
        )
    }

    @Test
    fun preCommitRollbackDeletesOnlyStagingState() {
        val fixture = fixture()
        val recovery = SqlDelightAppSyncRecoveryStore(fixture.database)
        val session = recovery.createOrResume(
            fixture.account, setOf(fixture.source.operationId.value), "replacement-fingerprint", 10,
        )
        recovery.stageOperations(session.sessionId, listOf(shadowOperation(session)))

        recovery.rollbackPreCommit(session.sessionId)

        assertNull(recovery.recoverySession(fixture.account))
        assertTrue(recovery.shadowOperations(session.sessionId).isEmpty())
        assertEquals(fixture.installation, fixture.operations.installation())
        assertEquals(
            fixture.source,
            fixture.operations.allOutboxOperations().single().first,
        )
    }

    @Test
    fun verifiedIndexActivationCarriesUncoveredPendingRowsAndIsIdempotent() {
        val fixture = fixture()
        val unrelated = fixture.operations.appendLocalOperation(
            accountBinding = fixture.account,
            domainId = SyncDomainId("settings"),
            entityId = SyncEntityId("language"),
            entityGeneration = 1,
            kind = SyncOperationKind.Patch,
            fields = mapOf("type" to "string", "value" to "zh-TW"),
            causalContext = SyncCausalContext(),
            createdAtEpochMillis = 2,
            origin = SyncOperationOrigin.UserAction,
        )
        val recovery = SqlDelightAppSyncRecoveryStore(fixture.database)
        val session = recovery.createOrResume(
            fixture.account, setOf(fixture.source.operationId.value), "replacement-fingerprint", 10,
        )
        val shadow = shadowOperation(session)
        recovery.stageOperations(session.sessionId, listOf(shadow))
        recovery.saveSegmentIntent(session.sessionId, 0, 1, "chunk", null)
        recovery.markSegmentVerified(session.sessionId, 0, "chunk", 301, 20)
        recovery.transition(
            session.sessionId,
            me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncRecoveryPhase.PublishingSegments,
            me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncRecoveryPhase.PublishingRoot,
            21,
        )
        recovery.markRootVerified(session.sessionId, 401, "root-fingerprint", 22)

        // Root verification alone is staging; source identity and lifecycle are still authoritative.
        assertEquals(
            AppSyncOperationLifecycle.PendingLocal,
            fixture.operations.allOutboxOperations().single { it.first == fixture.source }.second,
        )
        assertNull(fixture.operations.installation()?.journalBlogId)

        recovery.markIndexCommitted(session.sessionId, 23)
        recovery.activateCommittedRecovery(session.sessionId, 24)
        val carried = fixture.operations.pendingOperations().single()
        assertEquals(unrelated.fields, carried.fields)
        assertEquals(unrelated.createdAtEpochMillis, carried.createdAtEpochMillis)
        assertEquals(unrelated.origin, carried.origin)
        assertEquals(shadow.sequence.value + 1, carried.sequence.value)
        assertTrue(carried.causalContext.includes(shadow))
        assertTrue(carried.causalContext.includes(unrelated))
        val mappings = fixture.database.appSyncOperationQueries.getRecoveryCarryForward(session.sessionId).executeAsList()
        assertEquals(unrelated.operationId.value, mappings.single().sourceOperationId)
        assertEquals(carried.operationId.value, mappings.single().replacementOperationId)
        val outboxBeforeRetry = fixture.operations.allOutboxOperations()
        SqlDelightAppSyncRecoveryStore(fixture.database).activateCommittedRecovery(session.sessionId, 25)
        assertEquals(outboxBeforeRetry, fixture.operations.allOutboxOperations())
        assertEquals(mappings, fixture.database.appSyncOperationQueries.getRecoveryCarryForward(session.sessionId).executeAsList())

        val rows = fixture.operations.allOutboxOperations().associate { it.first.operationId to it.second }
        assertEquals(AppSyncOperationLifecycle.SupersededByRecovery, rows[fixture.source.operationId])
        assertEquals(AppSyncOperationLifecycle.PendingLocal, rows[unrelated.operationId])
        assertEquals(AppSyncOperationLifecycle.Acknowledged, rows[shadow.operationId])
        assertEquals(401, fixture.operations.installation()?.journalBlogId)
        assertEquals(session.targetDeviceId, fixture.operations.installation()?.deviceId)
        assertEquals(session.targetDeviceEpoch, fixture.operations.installation()?.deviceEpoch)
        assertEquals(session.targetWriterNonce, fixture.operations.installation()?.writerNonce)
        assertEquals(
            me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncRecoveryPhase.Completed,
            recovery.recoverySession(fixture.account)?.phase,
        )
    }

    @Test
    fun everyCrashPointPreservesLocalDataSourceIdentityCoverageAndDestructiveSemantics() {
        AppSyncRecoveryCrashPoint.entries.forEach { crashPoint ->
            val fixture = fixture()
            fixture.database.localFavoriteItemQueries.insertFavoriteItem(
                targetType = "ThreadNormal", targetId = 1, title = "local-domain-data",
                coverUrl = null, lastUpdatedTime = 1, forumId = 1, forumName = "forum",
                authorId = 0, createdAt = 1, lastFavoriteStatusUpdateAt = 1,
            )
            fixture.database.appSyncOperationQueries.upsertCausalWatermark("remote:epoch", 9)
            val delete = fixture.operations.appendLocalOperation(
                fixture.account, SyncDomainId("detail-note"), SyncEntityId("note"), 7,
                SyncOperationKind.Delete, emptyMap(), fixture.operations.causalContext(), 2,
                SyncOperationOrigin.UserAction, bulkDeleteAuthorizationId = "bulk-proof",
            )
            val relation = fixture.operations.appendLocalOperation(
                fixture.account, SyncDomainId("favorite.item-category"), SyncEntityId("relation"), 3,
                SyncOperationKind.RelationRemove,
                mapOf("targetId" to "1", "categorySyncId" to "category"),
                fixture.operations.causalContext(), 3, SyncOperationOrigin.UserAction,
            )
            val sources = listOf(fixture.source, delete, relation)
            val sourceRows = fixture.operations.allOutboxOperations().associate { it.first.operationId to it }
            val installationBefore = requireNotNull(fixture.operations.installation())
            val causalBefore = fixture.operations.causalContext()
            val recovery = SqlDelightAppSyncRecoveryStore(fixture.database)
            val session = recovery.createOrResume(
                fixture.account, sources.mapTo(linkedSetOf()) { it.operationId.value },
                "crash-${crashPoint.name}", 10,
            )
            val classifications = AppSyncLegacyOperationClassifier()
                .classify(sources, emptySet(), authoritativeAbsence = true)
                .map { it.copy(requiresRecovery = true) }
            AppSyncRecoveryOperationStager(recovery).stage(
                session.sessionId,
                AppSyncLegacyRecoveryPlanner().plan(classifications),
            )
            if (crashPoint >= AppSyncRecoveryCrashPoint.AfterAmbiguousSegmentWrite) {
                recovery.saveSegmentIntent(session.sessionId, 0, 1, "segment", null)
            }
            if (crashPoint >= AppSyncRecoveryCrashPoint.BeforeRootCreation) {
                recovery.markSegmentVerified(session.sessionId, 0, "segment", 301, 20)
            }
            if (crashPoint >= AppSyncRecoveryCrashPoint.BeforeIndexCommit) {
                recovery.transition(
                    session.sessionId, AppSyncRecoveryPhase.PublishingSegments,
                    AppSyncRecoveryPhase.PublishingRoot, 21,
                )
                recovery.markRootVerified(session.sessionId, 401, "root", 22)
            }
            if (crashPoint >= AppSyncRecoveryCrashPoint.BeforeLocalActivation) {
                recovery.markIndexCommitted(session.sessionId, 23)
            }

            assertFailsWith<SimulatedRecoveryCrash> {
                AppSyncRecoveryCrashInjector(crashPoint).reach(crashPoint)
            }

            val after = fixture.operations.allOutboxOperations().associate { it.first.operationId to it }
            sourceRows.forEach { (id, row) -> assertEquals(row, after[id]) }
            assertEquals(installationBefore, fixture.operations.installation())
            assertEquals(causalBefore, fixture.operations.causalContext())
            assertEquals(
                "local-domain-data",
                fixture.database.localFavoriteItemQueries.getAll().executeAsOne().title,
            )
            assertEquals(SyncOperationKind.Delete, after[delete.operationId]?.first?.kind)
            assertEquals("bulk-proof", after[delete.operationId]?.first?.bulkDeleteAuthorizationId)
            assertEquals(SyncOperationKind.RelationRemove, after[relation.operationId]?.first?.kind)
            assertEquals(relation.fields, after[relation.operationId]?.first?.fields)
        }
    }

    @Test
    fun everyDocumentedCrashPointCanBeInjectedDeterministically() {
        AppSyncRecoveryCrashPoint.entries.forEach { point ->
            val error = assertFailsWith<SimulatedRecoveryCrash> {
                AppSyncRecoveryCrashInjector(point).reach(point)
            }
            assertEquals(point, error.point)
        }
    }

    private fun shadowOperation(session: me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncRecoverySession): SyncOperation {
        val sequence = SyncSequence(session.targetFirstSequence)
        return SyncOperation(
            operationId = SyncOperation.idFor(session.targetDeviceId, session.targetDeviceEpoch, sequence),
            deviceId = session.targetDeviceId,
            deviceEpoch = session.targetDeviceEpoch,
            sequence = sequence,
            accountBinding = session.accountBinding,
            domainId = SyncDomainId("settings"),
            entityId = SyncEntityId("appsettings.thememode"),
            kind = SyncOperationKind.Patch,
            fields = mapOf("type" to "string", "value" to "dark"),
            causalContext = SyncCausalContext(),
            createdAtEpochMillis = 10,
            origin = SyncOperationOrigin.Migration,
        )
    }

    private fun fixture(): Fixture {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        val database = Database(driver)
        val operations = SqlDelightAppSyncOperationStore(database)
        operations.initialize("generation")
        val account = SyncAccountBinding("account")
        operations.bindAccount(account, AppSyncInstallationState.Active)
        val source = operations.appendLocalOperation(
            accountBinding = account,
            domainId = SyncDomainId("settings"),
            entityId = SyncEntityId("appsettings.signpagehtmlcache"),
            entityGeneration = 1,
            kind = SyncOperationKind.Patch,
            fields = mapOf("type" to "string", "value" to "legacy-cache"),
            causalContext = SyncCausalContext(),
            createdAtEpochMillis = 1,
            origin = SyncOperationOrigin.UserAction,
        )
        val installation = requireNotNull(operations.installation())
        return Fixture(database, operations, account, installation, source)
    }

    private data class Fixture(
        val database: Database,
        val operations: SqlDelightAppSyncOperationStore,
        val account: SyncAccountBinding,
        val installation: me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncInstallation,
        val source: SyncOperation,
    )
}

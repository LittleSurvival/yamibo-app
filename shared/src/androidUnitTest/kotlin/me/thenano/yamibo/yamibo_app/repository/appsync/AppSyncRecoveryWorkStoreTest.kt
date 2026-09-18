package me.thenano.yamibo.yamibo_app.repository.appsync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.*
import me.thenano.yamibo.yamibo_app.Database
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncRecoveryAttempts
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncRecoveryFailureCategory
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncInstallationState
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncRecoveryPhase
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.*
import me.thenano.yamibo.yamibo_app.store.appsync.*

class AppSyncRecoveryWorkStoreTest {
    private val account = SyncAccountBinding("account")
    private val first = "00000000-0000-0000-0000-000000000001"
    private val second = "00000000-0000-0000-0000-000000000002"
    private class Fixture(val db: Database, val account: SyncAccountBinding) {
        val operations = SqlDelightAppSyncOperationStore(db).also {
            it.initialize("database"); it.bindAccount(account, AppSyncInstallationState.Active)
        }
        val recovery = SqlDelightAppSyncRecoveryStore(db)
        val session = recovery.createOrResumeSegmentedJournal(account, emptySet(), "frozen", 1)
        val work = SqlDelightAppSyncRecoveryWorkStore(db)
    }
    private fun fixture(block: Fixture.() -> Unit) = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use {
        Database.Schema.create(it); Fixture(Database(it), account).block()
    }

    @Test fun restartAndDuplicateEnqueueCallbacksReuseIdentityUntilExecutionStarts() = fixture {
        val prepared = assertNotNull(work.prepare(account, first, 10))
        assertEquals(30_010L, prepared.notBeforeEpochMillis)
        assertNull(prepared.enqueuedAtEpochMillis)
        val restarted = SqlDelightAppSyncRecoveryWorkStore(db)
        assertEquals(prepared, restarted.prepare(account, second, 20))
        assertTrue(restarted.markEnqueued(first, 21))
        assertTrue(restarted.markEnqueued(first, 22))
        assertEquals(21L, restarted.current(account)?.enqueuedAtEpochMillis)
        assertTrue(restarted.markStarted(first, 30_010))
        assertTrue(restarted.markStarted(first, 30_011))
        assertEquals(30_010L, restarted.current(account)?.startedAtEpochMillis)
        val successor = assertNotNull(restarted.prepare(account, second, 30_012))
        assertEquals(second, successor.requestId)
        assertNull(successor.enqueuedAtEpochMillis)
        assertFalse(restarted.markStarted(first, 30_013))
        assertFalse(restarted.markEnqueued(first, 30_013))
        assertNull(restarted.current(SyncAccountBinding("other")))
    }

    @Test fun changedRetryOrTerminalPhaseInvalidatesOldSchedulingEvidence() = fixture {
        var now = 10L
        val attempts = AppSyncRecoveryAttempts(recovery, { now })
        attempts.recordFailure(session.sessionId, AppSyncRecoveryFailureCategory.Network)
        val prepared = assertNotNull(work.prepare(account, first, now))
        assertEquals(recovery.session(session.sessionId)?.nextRetryAtEpochMillis, prepared.notBeforeEpochMillis)
        work.markEnqueued(first, now)
        now = prepared.notBeforeEpochMillis
        attempts.recordFailure(session.sessionId, AppSyncRecoveryFailureCategory.Network)
        assertNull(work.current(account))
        assertFalse(work.markEnqueued(first, now))
        assertFalse(work.markStarted(first, now))
        val replacement = assertNotNull(work.prepare(account, second, now))
        assertTrue(replacement.notBeforeEpochMillis > prepared.notBeforeEpochMillis)
        now = replacement.notBeforeEpochMillis
        attempts.recordFailure(session.sessionId, AppSyncRecoveryFailureCategory.Network)
        assertEquals(AppSyncRecoveryPhase.NeedsAttention, recovery.session(session.sessionId)?.phase)
        assertNull(work.current(account))
        assertNull(work.prepare(account, first, now))
        assertFalse(work.markStarted(second, now))
        recovery.resumeRetryExhaustedRecovery(account, now)
        assertNotNull(work.prepare(account, first, now))
    }

    @Test fun retirementAndRollbackCannotLeaveFalseEnqueueEvidence() = fixture {
        db.transaction {
            work.prepare(account, first, 10)
            work.markEnqueued(first, 11)
            rollback()
        }
        assertNull(work.current(account))
        work.prepare(account, first, 12)
        work.markEnqueued(first, 13)
        work.retire(first, 14)
        assertNull(work.current(account))
        assertFalse(work.markEnqueued(first, 15))
        assertFalse(work.markStarted(first, 15))
        assertEquals(second, work.prepare(account, second, 16)?.requestId)
        recovery.transition(session.sessionId, session.phase, AppSyncRecoveryPhase.Completed, 17)
        assertNull(work.current(account))
        assertNull(work.prepare(account, first, 18))
    }

    @Test fun startedPredecessorSurvivesCrashBetweenRetryPersistenceAndSuccessorEnqueue() = fixture {
        work.prepare(account, first, 10)
        work.markEnqueued(first, 11)
        assertTrue(work.begin(account, first, 30_010))
        AppSyncRecoveryAttempts(recovery, { 30_011 }).recordFailure(session.sessionId, AppSyncRecoveryFailureCategory.Network)
        val restarted = SqlDelightAppSyncRecoveryWorkStore(db)
        assertNull(restarted.current(account))
        assertFalse(restarted.begin(SyncAccountBinding("other"), first, 30_012))
        assertTrue(restarted.begin(account, first, 30_012))
        val successor = assertNotNull(restarted.prepare(account, second, 30_013))
        assertEquals(recovery.session(session.sessionId)?.nextRetryAtEpochMillis, successor.notBeforeEpochMillis)
        // Crash again after SQL preparation but before the WorkManager enqueue/acknowledgement.
        val afterPreparationCrash = SqlDelightAppSyncRecoveryWorkStore(db)
        assertTrue(afterPreparationCrash.begin(account, first, 30_014))
        assertEquals(successor, afterPreparationCrash.prepare(account, first, 30_015))
        assertNull(afterPreparationCrash.current(account)?.startedAtEpochMillis)
        assertTrue(afterPreparationCrash.markEnqueued(second, 30_016))
        assertFalse(restarted.begin(account, first, 30_014))
        assertTrue(restarted.begin(account, second, successor.notBeforeEpochMillis))
    }

    @Test fun migrationAddsEmptyDeviceLocalLedgerWithoutChangingRecoveryEvidence() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            driver.execute(null, "CREATE TABLE AppSyncRecoverySession(sessionId TEXT NOT NULL PRIMARY KEY, evidence TEXT NOT NULL)", 0)
            driver.execute(null, "INSERT INTO AppSyncRecoverySession VALUES ('old', 'unchanged')", 0)
            Database.Schema.migrate(driver, oldVersion = 51, newVersion = 52)
            assertNull(Database(driver).appSyncRecoveryWorkQueries.getForSession("old").executeAsOneOrNull())
            driver.executeQuery(null, "SELECT evidence FROM AppSyncRecoverySession WHERE sessionId = 'old'", { cursor ->
                assertTrue(cursor.next().value)
                assertEquals("unchanged", cursor.getString(0))
                app.cash.sqldelight.db.QueryResult.Value(Unit)
            }, 0)
        }
    }
}

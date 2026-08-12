package me.thenano.yamibo.yamibo_app.repository.appsync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import me.thenano.yamibo.yamibo_app.Database

class AppSyncMigration29Test {
    @Test
    fun migrationCreatesOperationSyncTables() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)

        Database.Schema.migrate(driver, oldVersion = 29, newVersion = 30)

        val expected = setOf(
            "AppSyncInstallation",
            "AppSyncOutbox",
            "AppSyncAppliedOperation",
            "AppSyncCausalWatermark",
            "AppSyncConflictHistory",
            "AppSyncQuarantine",
            "AppSyncRunLease",
            "AppSyncCheckpoint",
            "AppSyncCheckpointAcknowledgement",
            "AppSyncBulkDeleteAuthorization",
            "AppSyncReliabilityRun",
        )
        val actual = driver.executeQuery(
            identifier = null,
            sql = "SELECT name FROM sqlite_master WHERE type = 'table' AND name LIKE 'AppSync%'",
            mapper = { cursor ->
                app.cash.sqldelight.db.QueryResult.Value(
                    buildSet {
                        while (cursor.next().value) {
                            add(requireNotNull(cursor.getString(0)))
                        }
                    },
                )
            },
            parameters = 0,
        ).value

        assertEquals(expected, actual)
    }

    @Test
    fun migration32AddsPrivacySafeReliabilityAuditColumns() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(
            identifier = null,
            sql = """
                CREATE TABLE AppSyncReliabilityRun (
                    runId TEXT NOT NULL PRIMARY KEY,
                    trigger TEXT NOT NULL,
                    phase TEXT NOT NULL,
                    outcome TEXT,
                    eligible INTEGER NOT NULL,
                    exclusionReason TEXT,
                    retryCount INTEGER NOT NULL DEFAULT 0,
                    pendingCount INTEGER NOT NULL DEFAULT 0,
                    quarantineCount INTEGER NOT NULL DEFAULT 0,
                    startedAtEpochMillis INTEGER NOT NULL,
                    completedAtEpochMillis INTEGER,
                    nextRetryAtEpochMillis INTEGER
                )
            """.trimIndent(),
            parameters = 0,
        )

        Database.Schema.migrate(driver, oldVersion = 32, newVersion = 33)

        val columns = driver.executeQuery(
            identifier = null,
            sql = "PRAGMA table_info(AppSyncReliabilityRun)",
            mapper = { cursor ->
                app.cash.sqldelight.db.QueryResult.Value(
                    buildSet {
                        while (cursor.next().value) {
                            add(requireNotNull(cursor.getString(1)))
                        }
                    },
                )
            },
            parameters = 0,
        ).value
        assertTrue("durationMillis" in columns)
        assertTrue("causalReplicaCount" in columns)
        assertTrue("causalCoverageHash" in columns)
    }

    @Test
    fun migration33AddsDurableKnownSettingKeys() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(
            identifier = null,
            sql = """
                CREATE TABLE AppSyncSettingValue (
                    settingKey TEXT NOT NULL PRIMARY KEY,
                    type TEXT NOT NULL,
                    value TEXT,
                    winnerOperationId TEXT NOT NULL,
                    updatedAtEpochMillis INTEGER NOT NULL
                )
            """.trimIndent(),
            parameters = 0,
        )
        driver.execute(
            identifier = null,
            sql = """
                INSERT INTO AppSyncSettingValue(
                    settingKey, type, value, winnerOperationId, updatedAtEpochMillis
                ) VALUES ('theme', 'string', 'dark', 'existing', 1)
            """.trimIndent(),
            parameters = 0,
        )

        Database.Schema.migrate(driver, oldVersion = 33, newVersion = 34)

        val tableCount = driver.executeQuery(
            identifier = null,
            sql = """
                SELECT COUNT(*) FROM sqlite_master
                WHERE type = 'table' AND name = 'AppSyncKnownSettingKey'
            """.trimIndent(),
            mapper = { cursor ->
                cursor.next()
                app.cash.sqldelight.db.QueryResult.Value(cursor.getLong(0))
            },
            parameters = 0,
        ).value
        assertEquals(1L, tableCount)
        val backfilled = driver.executeQuery(
            identifier = null,
            sql = "SELECT settingKey FROM AppSyncKnownSettingKey",
            mapper = { cursor ->
                cursor.next()
                app.cash.sqldelight.db.QueryResult.Value(cursor.getString(0))
            },
            parameters = 0,
        ).value
        assertEquals("theme", backfilled)
    }
}

package me.thenano.yamibo.yamibo_app.repository.appsync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import me.thenano.yamibo.yamibo_app.Database
import me.thenano.yamibo.yamibo_app.store.appsync.SqlDelightAppSyncOperationStore
import me.thenano.yamibo.yamibo_app.util.time.FixedScheduleInterval

class AppSyncScheduleMigrationTest {
    @Test
    fun migrationAddsConservativeSchedulingDefaultsWithoutChangingAutomaticEnablement() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(
            null,
            """
            CREATE TABLE AppSyncInstallation (
                singletonId INTEGER NOT NULL PRIMARY KEY CHECK (singletonId = 1),
                databaseGeneration TEXT NOT NULL,
                accountBinding TEXT,
                deviceId TEXT NOT NULL,
                deviceEpoch TEXT NOT NULL,
                writerNonce TEXT NOT NULL,
                nextSequence INTEGER NOT NULL DEFAULT 1,
                state TEXT NOT NULL,
                lastVerifiedHeartbeatAt INTEGER,
                journalBlogId INTEGER,
                lastFullDiscoveryAt INTEGER,
                automaticEnabled INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
            0,
        )
        driver.execute(
            null,
            """
            INSERT INTO AppSyncInstallation VALUES (
                1, 'generation', NULL, 'device', 'epoch', 'nonce', 1,
                'UNBOUND', NULL, NULL, NULL, 1
            )
            """.trimIndent(),
            0,
        )

        Database.Schema.migrate(driver, oldVersion = 35, newVersion = 36)
        val installation = requireNotNull(
            SqlDelightAppSyncOperationStore(Database(driver)).installation(),
        )

        assertEquals(true, installation.automaticEnabled)
        assertEquals(false, installation.scheduleSettings.syncOnAppStart)
        assertEquals(false, installation.scheduleSettings.syncOnForegroundExit)
        assertEquals(
            FixedScheduleInterval.Hours6,
            installation.scheduleSettings.periodicInterval,
        )
        assertEquals(0L, installation.requestedTriggerGeneration)
        assertEquals(0L, installation.accountedTriggerGeneration)
    }
}

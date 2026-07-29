package me.thenano.yamibo.yamibo_app.repository.appsync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import me.thenano.yamibo.yamibo_app.Database

class FavoriteUpdateAppSyncMigrationTest {
    @Test
    fun migrationBackfillsStableEventsAndDurableChoicesIdempotently() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.executeSql(
            """
            CREATE TABLE FavoriteUpdateEvent(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                targetType TEXT NOT NULL, targetId INTEGER NOT NULL, authorId INTEGER,
                fid INTEGER, forumName TEXT, title TEXT NOT NULL, mode TEXT NOT NULL,
                summary TEXT NOT NULL, detailIds TEXT, coverUrl TEXT, detectedAt INTEGER NOT NULL,
                readAt INTEGER, dismissedAt INTEGER, ambiguous INTEGER NOT NULL DEFAULT 0,
                latestPostTitle TEXT
            )
            """,
        )
        driver.executeSql(
            """
            CREATE TABLE FavoriteUpdateFidFilter(
                fid INTEGER NOT NULL PRIMARY KEY, forumName TEXT NOT NULL,
                enabled INTEGER NOT NULL DEFAULT 1, itemCount INTEGER NOT NULL DEFAULT 0,
                updatedAt INTEGER NOT NULL
            )
            """,
        )
        driver.executeSql(
            """
            CREATE TABLE FavoriteUpdateCategoryFilter(
                categoryId INTEGER NOT NULL PRIMARY KEY, categoryName TEXT NOT NULL,
                enabled INTEGER NOT NULL DEFAULT 1, itemCount INTEGER NOT NULL DEFAULT 0,
                updatedAt INTEGER NOT NULL
            )
            """,
        )
        driver.executeSql(
            """
            CREATE TABLE LocalFavoriteCategory(
                id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL,
                sortOrder INTEGER NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL,
                syncId TEXT
            )
            """,
        )
        driver.executeSql(
            """
            INSERT INTO FavoriteUpdateEvent(
                targetType, targetId, authorId, fid, forumName, title, mode, summary,
                detailIds, coverUrl, detectedAt, readAt, dismissedAt, ambiguous, latestPostTitle
            ) VALUES
                ('ThreadNormal', 42, 0, 1, 'forum', 'title', 'NormalThread', 'new', '100,101',
                 NULL, 10, 20, 30, 0, 'post'),
                ('ThreadNormal', 42, 0, 1, 'forum', 'title', 'NormalThread', 'duplicate', '101,100',
                 NULL, 11, 25, 35, 0, 'post'),
                ('TagManga', 9, 0, NULL, NULL, 'tag', 'TagManga', 'legacy', '',
                 NULL, 50, NULL, NULL, 1, NULL)
            """,
        )
        driver.executeSql(
            "INSERT INTO FavoriteUpdateFidFilter VALUES (1, 'forum', 0, 2, 100)",
        )
        driver.executeSql(
            "INSERT INTO LocalFavoriteCategory VALUES (7, 'category', 0, 1, 1, 'category-sync')",
        )
        driver.executeSql(
            "INSERT INTO FavoriteUpdateCategoryFilter VALUES (7, 'category', 0, 1, 100)",
        )
        driver.executeSql(
            "INSERT INTO FavoriteUpdateCategoryFilter VALUES (99, 'orphan', 1, 1, 100)",
        )

        Database.Schema.migrate(driver, oldVersion = 34, newVersion = 35)
        val db = Database(driver)
        backfillFavoriteUpdateSyncState(db)
        backfillFavoriteUpdateSyncState(db)

        val events = db.favoriteUpdateEventQueries.getAll().executeAsList()
        assertEquals(2, events.size)
        assertEquals(25, events.first { it.targetType == "ThreadNormal" }.readAt)
        assertEquals(35, events.first { it.targetType == "ThreadNormal" }.dismissedAt)
        events.forEach {
            assertNotNull(it.syncId.takeIf(String::isNotBlank))
            assertNotNull(it.sourceFingerprint.takeIf(String::isNotBlank))
            assertNotNull(it.sourceDiscriminator.takeIf(String::isNotBlank))
        }
        assertEquals(0, db.favoriteUpdateFidChoiceQueries.getByFid(1).executeAsOne().enabled)
        assertEquals(
            0,
            db.favoriteUpdateCategoryChoiceQueries.getBySyncId("category-sync")
                .executeAsOne()
                .enabled,
        )
        assertEquals(1, db.favoriteUpdateCategoryChoiceQueries.getAll().executeAsList().size)
    }

    private fun JdbcSqliteDriver.executeSql(sql: String) {
        execute(null, sql.trimIndent(), 0)
    }
}

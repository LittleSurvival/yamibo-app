package me.thenano.yamibo.yamibo_app.repository.appsync

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncError
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncMigrationErrorKind
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncResult
import me.thenano.yamibo.yamibo_app.repository.appsync.migration.AppSyncMigration
import me.thenano.yamibo.yamibo_app.repository.appsync.migration.AppSyncMigrator

class AppSyncMigratorTest {
    @Test
    fun migrationsRunOneVersionAtATimeWithoutMutatingInput() {
        val input = JsonObject(mapOf("schemaVersion" to JsonPrimitive(1), "value" to JsonPrimitive("kept")))
        val migrator = AppSyncMigrator(
            migrations = listOf(versionMigration(1), versionMigration(2)),
            currentVersion = 3,
            minimumVersion = 1,
        )

        val output = assertIs<AppSyncResult.Success<JsonObject>>(migrator.migrate(input)).value
        assertEquals(3, output.getValue("schemaVersion").jsonPrimitive.int)
        assertEquals(1, input.getValue("schemaVersion").jsonPrimitive.int)
        assertEquals("kept", output.getValue("value").jsonPrimitive.content)
    }

    @Test
    fun missingFutureAndLegacyVersionsAreTyped() {
        val missing = AppSyncMigrator(
            migrations = emptyList(),
            currentVersion = 2,
            minimumVersion = 1,
        ).migrate(JsonObject(mapOf("schemaVersion" to JsonPrimitive(1))))
        assertEquals(
            AppSyncMigrationErrorKind.MissingMigration,
            assertIs<AppSyncError.Migration>(assertIs<AppSyncResult.Failure>(missing).error).kind,
        )

        val future = AppSyncMigrator(currentVersion = 1, minimumVersion = 1)
            .migrate(JsonObject(mapOf("schemaVersion" to JsonPrimitive(2))))
        assertEquals(
            AppSyncMigrationErrorKind.UnsupportedFutureSchema,
            assertIs<AppSyncError.Migration>(assertIs<AppSyncResult.Failure>(future).error).kind,
        )

        val legacy = AppSyncMigrator(currentVersion = 3, minimumVersion = 2)
            .migrate(JsonObject(mapOf("schemaVersion" to JsonPrimitive(1))))
        assertEquals(
            AppSyncMigrationErrorKind.UnsupportedLegacySchema,
            assertIs<AppSyncError.Migration>(assertIs<AppSyncResult.Failure>(legacy).error).kind,
        )
    }

    private fun versionMigration(from: Int) = object : AppSyncMigration {
        override val fromVersion: Int = from
        override fun migrate(input: JsonObject): JsonObject =
            JsonObject(input + ("schemaVersion" to JsonPrimitive(toVersion)))
    }
}

package me.thenano.yamibo.yamibo_app.repository.appsync.migration

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncFormat
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncError
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncMigrationErrorKind
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncResult

class AppSyncMigrator(
    migrations: List<AppSyncMigration> = emptyList(),
    private val currentVersion: Int = AppSyncFormat.CURRENT_SCHEMA_VERSION,
    private val minimumVersion: Int = AppSyncFormat.MIN_SUPPORTED_SCHEMA_VERSION,
) {
    private val migrationsByVersion = migrations.associateBy(AppSyncMigration::fromVersion)

    fun migrate(input: JsonObject): AppSyncResult<JsonObject> {
        val initialVersion = input["schemaVersion"]?.jsonPrimitive?.intOrNull
            ?: return failure(
                AppSyncMigrationErrorKind.MissingSchemaVersion,
                null,
                "schemaVersion is required and must be an integer",
            )
        if (initialVersion < minimumVersion) {
            return failure(
                AppSyncMigrationErrorKind.UnsupportedLegacySchema,
                initialVersion,
                "Schema $initialVersion is older than supported minimum $minimumVersion",
            )
        }
        if (initialVersion > currentVersion) {
            return failure(
                AppSyncMigrationErrorKind.UnsupportedFutureSchema,
                initialVersion,
                "Schema $initialVersion is newer than supported current $currentVersion",
            )
        }

        var version = initialVersion
        var current = input
        while (version < currentVersion) {
            val migration = migrationsByVersion[version]
                ?: return failure(
                    AppSyncMigrationErrorKind.MissingMigration,
                    version,
                    "Missing migration from schema $version to ${version + 1}",
                )
            if (migration.toVersion != version + 1) {
                return failure(
                    AppSyncMigrationErrorKind.InvalidMigration,
                    version,
                    "Migration from $version must advance exactly one version",
                )
            }
            current = try {
                migration.migrate(current)
            } catch (error: Throwable) {
                return failure(
                    AppSyncMigrationErrorKind.InvalidMigration,
                    version,
                    "Migration from $version failed: ${error.message ?: error::class.simpleName}",
                )
            }
            val migratedVersion = current["schemaVersion"]?.jsonPrimitive?.intOrNull
            if (migratedVersion != migration.toVersion) {
                return failure(
                    AppSyncMigrationErrorKind.InvalidMigration,
                    version,
                    "Migration from $version did not write schemaVersion ${migration.toVersion}",
                )
            }
            version = migration.toVersion
        }
        return AppSyncResult.Success(current)
    }

    private fun failure(
        kind: AppSyncMigrationErrorKind,
        version: Int?,
        message: String,
    ): AppSyncResult.Failure = AppSyncResult.Failure(
        AppSyncError.Migration(kind, version, message),
    )
}

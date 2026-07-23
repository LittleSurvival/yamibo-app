package me.thenano.yamibo.yamibo_app.repository.appsync.model

enum class AppSyncApplyMode {
    Merge,
    Overwrite,
}

data class AppSyncImportSummary(
    val readingHistoryApplied: Int,
    val favoritesApplied: Int,
    val settingsApplied: Int,
    val settingsSkipped: Int,
    val warnings: List<String> = emptyList(),
)

sealed interface AppSyncResult<out T> {
    data class Success<T>(val value: T) : AppSyncResult<T>
    data class Failure(val error: AppSyncError) : AppSyncResult<Nothing>
}

sealed interface AppSyncError {
    val message: String

    data class Codec(
        val kind: AppSyncCodecErrorKind,
        override val message: String,
    ) : AppSyncError

    data class Migration(
        val kind: AppSyncMigrationErrorKind,
        val schemaVersion: Int?,
        override val message: String,
    ) : AppSyncError

    data class Validation(
        val violations: List<String>,
        override val message: String = violations.joinToString("; "),
    ) : AppSyncError

    data class Apply(
        override val message: String,
        val settingsRollbackIncomplete: Boolean = false,
    ) : AppSyncError

    data class RemoteUnavailable(
        val operation: AppSyncRemoteOperation,
        override val message: String = "Cloud app sync is not implemented",
    ) : AppSyncError
}

enum class AppSyncCodecErrorKind {
    PayloadTooLarge,
    MalformedFrame,
    UnsupportedCodecVersion,
    InvalidBase64,
    InvalidGzip,
    InvalidUtf8,
    InvalidJson,
}

enum class AppSyncMigrationErrorKind {
    MissingSchemaVersion,
    UnsupportedLegacySchema,
    UnsupportedFutureSchema,
    MissingMigration,
    InvalidMigration,
}

enum class AppSyncRemoteOperation {
    Upload,
    Load,
    Update,
}

inline fun <T, R> AppSyncResult<T>.map(transform: (T) -> R): AppSyncResult<R> = when (this) {
    is AppSyncResult.Success -> AppSyncResult.Success(transform(value))
    is AppSyncResult.Failure -> this
}

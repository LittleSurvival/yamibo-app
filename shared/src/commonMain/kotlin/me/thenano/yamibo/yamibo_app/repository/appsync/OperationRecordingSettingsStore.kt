package me.thenano.yamibo.yamibo_app.repository.appsync

import me.thenano.yamibo.yamibo_app.Database
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperationKind
import me.thenano.yamibo.yamibo_app.store.settings.SettingsStore

internal class OperationRecordingSettingsStore(
    private val db: Database,
    private val delegate: SettingsStore,
    private val recorder: AppSyncMutationRecorder,
) : SettingsStore {
    override fun getInt(key: String, defaultValue: Int): Int =
        if (isAppSyncLocalOnlySetting(key)) delegate.getInt(key, defaultValue)
        else canonical(key)?.toIntOrNull() ?: delegate.getInt(key, defaultValue)

    override fun putInt(key: String, value: Int) = put(key, "int", value.toString()) {
        delegate.putInt(key, value)
    }

    override fun getFloat(key: String, defaultValue: Float): Float =
        if (isAppSyncLocalOnlySetting(key)) delegate.getFloat(key, defaultValue)
        else canonical(key)?.toFloatOrNull() ?: delegate.getFloat(key, defaultValue)

    override fun putFloat(key: String, value: Float) = put(key, "float", value.toString()) {
        delegate.putFloat(key, value)
    }

    override fun getString(key: String, defaultValue: String): String =
        if (isAppSyncLocalOnlySetting(key)) delegate.getString(key, defaultValue)
        else canonical(key) ?: delegate.getString(key, defaultValue)

    override fun putString(key: String, value: String) = put(key, "string", value) {
        delegate.putString(key, value)
    }

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        if (isAppSyncLocalOnlySetting(key)) delegate.getBoolean(key, defaultValue)
        else canonical(key)?.toBooleanStrictOrNull() ?: delegate.getBoolean(key, defaultValue)

    override fun putBoolean(key: String, value: Boolean) = put(key, "bool", value.toString()) {
        delegate.putBoolean(key, value)
    }

    override fun remove(key: String) {
        if (isAppSyncLocalOnlySetting(key)) {
            delegate.remove(key)
            return
        }
        db.appSyncOperationQueries.recordKnownSyncSettingKey(key)
        val existing = db.appSyncOperationQueries.getSyncSettingValue(key).executeAsOneOrNull()
        recorder.record(
            domain = "settings",
            entityId = key,
            kind = SyncOperationKind.Delete,
            fields = mapOf("type" to (existing?.type ?: "string"), "value" to null),
        ) {
            db.appSyncOperationQueries.deleteSyncSettingValue(key)
        }
        delegate.remove(key)
    }

    override fun hasKey(key: String): Boolean =
        if (isAppSyncLocalOnlySetting(key)) delegate.hasKey(key) else
            db.appSyncOperationQueries.getSyncSettingValue(key).executeAsOneOrNull() != null ||
            delegate.hasKey(key)

    private fun canonical(key: String): String? =
        db.appSyncOperationQueries.getSyncSettingValue(key).executeAsOneOrNull()?.settingValue

    private fun put(
        key: String,
        type: String,
        value: String,
        project: () -> Unit,
    ) {
        if (isAppSyncLocalOnlySetting(key)) {
            project()
            return
        }
        db.appSyncOperationQueries.recordKnownSyncSettingKey(key)
        recorder.record(
            domain = "settings",
            entityId = key,
            kind = if (canonical(key) == null) SyncOperationKind.Put else SyncOperationKind.Patch,
            fields = mapOf("type" to type, "value" to value),
        ) { nullableOperation ->
            db.appSyncOperationQueries.upsertSyncSettingValue(
                settingKey = key,
                type = type,
                value_ = value,
                winnerOperationId = nullableOperation?.operationId?.value ?: PENDING_SETTINGS_MIGRATION_WINNER,
                updatedAtEpochMillis = nullableOperation?.createdAtEpochMillis ?: 0L,
            )
        }
        project()
    }

}

internal const val PENDING_SETTINGS_MIGRATION_WINNER = "local-pending-bootstrap-migration"

/**
 * Settings in this list contain installation-local identifiers or transient UI state. They may
 * still belong in a portable backup, but publishing them through AppSync would make one device
 * interpret another device's local database identifiers.
 */
internal fun isAppSyncLocalOnlySetting(key: String): Boolean = key in APP_SYNC_LOCAL_ONLY_SETTINGS

private val APP_SYNC_LOCAL_ONLY_SETTINGS = setOf(
    "appsettings.favoritelastcategoryid",
)

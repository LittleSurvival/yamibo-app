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
        canonical(key)?.toIntOrNull() ?: delegate.getInt(key, defaultValue)

    override fun putInt(key: String, value: Int) = put(key, "int", value.toString()) {
        delegate.putInt(key, value)
    }

    override fun getFloat(key: String, defaultValue: Float): Float =
        canonical(key)?.toFloatOrNull() ?: delegate.getFloat(key, defaultValue)

    override fun putFloat(key: String, value: Float) = put(key, "float", value.toString()) {
        delegate.putFloat(key, value)
    }

    override fun getString(key: String, defaultValue: String): String =
        canonical(key) ?: delegate.getString(key, defaultValue)

    override fun putString(key: String, value: String) = put(key, "string", value) {
        delegate.putString(key, value)
    }

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        canonical(key)?.toBooleanStrictOrNull() ?: delegate.getBoolean(key, defaultValue)

    override fun putBoolean(key: String, value: Boolean) = put(key, "bool", value.toString()) {
        delegate.putBoolean(key, value)
    }

    override fun remove(key: String) {
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

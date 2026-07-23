package me.thenano.yamibo.yamibo_app.repository.appsync

import me.thenano.yamibo.yamibo_app.Database
import me.thenano.yamibo.yamibo_app.repository.AppSyncRepository
import me.thenano.yamibo.yamibo_app.repository.appsync.codec.AppSyncTextCodec
import me.thenano.yamibo.yamibo_app.repository.appsync.model.*
import me.thenano.yamibo.yamibo_app.repository.settings.core.SettingsRegistry

class AppSyncRepositoryImpl(
    db: Database,
    settingsRegistries: List<SettingsRegistry>,
    appVersionCode: Int,
    private val codec: AppSyncTextCodec = AppSyncTextCodec(),
    private val validator: AppSyncValidator = AppSyncValidator(),
) : AppSyncRepository {
    private val settingsPolicy = AppSyncSettingsPolicy(settingsRegistries)
    private val reader = AppSyncSnapshotReader(db, settingsPolicy, appVersionCode)
    private val applier = AppSyncSnapshotApplier(db, settingsPolicy)

    override suspend fun createSnapshot(): AppSyncResult<AppSyncEnvelope> = try {
        AppSyncResult.Success(reader.read())
    } catch (error: Throwable) {
        AppSyncResult.Failure(
            AppSyncError.Apply("Unable to create app-sync snapshot: ${error.message ?: error::class.simpleName}"),
        )
    }

    override fun encode(snapshot: AppSyncEnvelope): AppSyncResult<String> = codec.encode(snapshot)

    override fun decode(encodedText: String): AppSyncResult<AppSyncEnvelope> = codec.decode(encodedText)

    override suspend fun applySnapshot(
        snapshot: AppSyncEnvelope,
        mode: AppSyncApplyMode,
    ): AppSyncResult<AppSyncImportSummary> {
        val violations = validator.validate(snapshot)
        if (violations.isNotEmpty()) return AppSyncResult.Failure(AppSyncError.Validation(violations))
        return applier.apply(snapshot, mode)
    }

    override suspend fun exportText(): AppSyncResult<String> = when (val snapshot = createSnapshot()) {
        is AppSyncResult.Success -> encode(snapshot.value)
        is AppSyncResult.Failure -> snapshot
    }

    override suspend fun importText(
        encodedText: String,
        mode: AppSyncApplyMode,
    ): AppSyncResult<AppSyncImportSummary> = when (val snapshot = decode(encodedText)) {
        is AppSyncResult.Success -> applySnapshot(snapshot.value, mode)
        is AppSyncResult.Failure -> snapshot
    }

    override suspend fun uploadCloud(encodedText: String): AppSyncResult<Unit> =
        remoteStub(AppSyncRemoteOperation.Upload)

    override suspend fun loadCloud(): AppSyncResult<String> =
        remoteStub(AppSyncRemoteOperation.Load)

    override suspend fun updateCloud(encodedText: String): AppSyncResult<Unit> =
        remoteStub(AppSyncRemoteOperation.Update)

    private fun <T> remoteStub(operation: AppSyncRemoteOperation): AppSyncResult<T> =
        AppSyncResult.Failure(AppSyncError.RemoteUnavailable(operation))
}

package me.thenano.yamibo.yamibo_app.repository

import me.thenano.yamibo.yamibo_app.repository.appsync.model.*

interface AppSyncRepository {
    suspend fun createSnapshot(): AppSyncResult<AppSyncEnvelope>
    fun encode(snapshot: AppSyncEnvelope): AppSyncResult<String>
    fun decode(encodedText: String): AppSyncResult<AppSyncEnvelope>
    suspend fun applySnapshot(snapshot: AppSyncEnvelope, mode: AppSyncApplyMode): AppSyncResult<AppSyncImportSummary>
    suspend fun exportText(): AppSyncResult<String>
    suspend fun importText(encodedText: String, mode: AppSyncApplyMode): AppSyncResult<AppSyncImportSummary>

    /**
     * Remote transport STUB.
     *
     * Provider selection, authentication, conflict handling, concurrency control,
     * encryption, and scheduling require a future OpenSpec change.
     */
    suspend fun uploadCloud(encodedText: String): AppSyncResult<Unit>

    /** Remote transport STUB. See [uploadCloud]. */
    suspend fun loadCloud(): AppSyncResult<String>

    /** Remote transport STUB. See [uploadCloud]. */
    suspend fun updateCloud(encodedText: String): AppSyncResult<Unit>
}

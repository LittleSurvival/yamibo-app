package me.thenano.yamibo.yamibo_app.store.appsync

import me.thenano.yamibo.yamibo_app.repository.appsync.engine.OperationReductionResult
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncInstallation
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncInstallationState
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncOperationLifecycle
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncRunLease
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncBulkDeleteAuthorization
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncVerifiedCheckpoint
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncAccountBinding
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncCausalContext
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncDomainId
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncEntityId
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperation
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperationId
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperationKind
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperationOrigin

internal data class LocalSyncOperationDraft(
    val domainId: SyncDomainId,
    val entityId: SyncEntityId,
    val entityGeneration: Long = 1,
    val kind: SyncOperationKind,
    val fields: Map<String, String?>,
    val bulkDeleteAuthorizationId: String? = null,
)

internal interface AppSyncOperationStore {
    fun initialize(databaseGeneration: String): AppSyncInstallation
    fun installation(): AppSyncInstallation?
    fun bindAccount(accountBinding: SyncAccountBinding, state: AppSyncInstallationState)
    fun rotateDeviceEpoch(accountBinding: SyncAccountBinding, state: AppSyncInstallationState)
    fun updateState(state: AppSyncInstallationState)
    fun updateVerifiedHeartbeat(atEpochMillis: Long, journalBlogId: Long?)
    fun updateDiscoveryTime(atEpochMillis: Long)
    fun setAutomaticEnabled(enabled: Boolean)
    fun prepareForCloudReset()

    fun appendLocalOperation(
        accountBinding: SyncAccountBinding,
        domainId: SyncDomainId,
        entityId: SyncEntityId,
        entityGeneration: Long,
        kind: SyncOperationKind,
        fields: Map<String, String?>,
        causalContext: SyncCausalContext,
        createdAtEpochMillis: Long,
        origin: SyncOperationOrigin,
        bulkDeleteAuthorizationId: String? = null,
        localMutation: (SyncOperation) -> Unit = {},
    ): SyncOperation

    fun appendLocalOperations(
        accountBinding: SyncAccountBinding,
        drafts: List<LocalSyncOperationDraft>,
        causalContext: SyncCausalContext,
        createdAtEpochMillis: Long,
        origin: SyncOperationOrigin,
        localMutation: (List<SyncOperation>) -> Unit = {},
    ): List<SyncOperation>

    fun appendLocalCommand(
        accountBinding: SyncAccountBinding,
        causalContext: SyncCausalContext,
        createdAtEpochMillis: Long,
        origin: SyncOperationOrigin,
        localMutation: () -> List<LocalSyncOperationDraft>,
        afterOperationsCreated: (List<SyncOperation>) -> Unit = {},
    ): List<SyncOperation>

    fun pendingOperations(): List<SyncOperation>
    fun allOutboxOperations(): List<Pair<SyncOperation, AppSyncOperationLifecycle>>
    fun markPublishedUnverified(operationIds: Set<SyncOperationId>)
    fun markAcknowledged(operationIds: Set<SyncOperationId>, atEpochMillis: Long)
    fun markCompacted(operationIds: Set<SyncOperationId>)
    fun replaceWithVerifiedCloudState(
        result: OperationReductionResult,
        coverage: SyncCausalContext,
        cloudOperationIds: Set<SyncOperationId>,
        appliedAtEpochMillis: Long,
        domainMutation: (OperationReductionResult) -> Unit,
    )
    fun isApplied(operationId: SyncOperationId): Boolean
    fun applyRemoteReduction(
        result: OperationReductionResult,
        appliedAtEpochMillis: Long,
        domainMutation: (OperationReductionResult) -> Unit,
    )
    fun adoptCheckpoint(
        checkpointId: String,
        blogId: Long?,
        coverage: SyncCausalContext,
        payloadFingerprint: String,
        createdAtEpochMillis: Long,
        verifiedAtEpochMillis: Long,
        laterReduction: OperationReductionResult,
        domainMutation: (OperationReductionResult) -> Unit,
    )
    fun saveVerifiedCheckpoint(checkpoint: AppSyncVerifiedCheckpoint)
    fun verifiedCheckpoints(): List<AppSyncVerifiedCheckpoint>
    fun causalContext(): SyncCausalContext

    fun acquireLease(
        ownerId: String,
        nowEpochMillis: Long,
        durationMillis: Long,
    ): Boolean

    fun currentLease(): AppSyncRunLease?
    fun releaseLease(ownerId: String)

    fun saveBulkDeleteAuthorization(authorization: AppSyncBulkDeleteAuthorization)
    fun loadBulkDeleteAuthorization(authorizationId: String): AppSyncBulkDeleteAuthorization?
    fun consumeBulkDeleteAuthorization(
        authorizationId: String,
        nowEpochMillis: Long,
    ): Boolean
}

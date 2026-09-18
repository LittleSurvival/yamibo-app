package me.thenano.yamibo.yamibo_app.repository.appsync

import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncBulkDeleteProofFields
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.SqlDelightSyncDomainStateAdapter
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncBulkDeleteAuthorization
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncInstallationState
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncDomainId
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncEntityId
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncIdentityGenerator
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperation
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperationKind
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperationOrigin
import me.thenano.yamibo.yamibo_app.store.appsync.AppSyncOperationStore
import me.thenano.yamibo.yamibo_app.store.appsync.LocalSyncOperationDraft

internal class AppSyncMutationRecorder(
    private val enabled: Boolean,
    private val store: AppSyncOperationStore,
    private val domainState: SqlDelightSyncDomainStateAdapter,
    private val nowMillis: () -> Long,
) {
    fun currentGeneration(domain: String, entityId: String): Long {
        return domainState.currentGeneration(SyncDomainId(domain), SyncEntityId(entityId))
    }

    fun record(
        domain: String,
        entityId: String,
        kind: SyncOperationKind,
        fields: Map<String, String?>,
        entityGeneration: Long = 1,
        bulkDeleteAuthorizationId: String? = null,
        mutation: (SyncOperation?) -> Unit,
    ): SyncOperation? {
        val installation = store.installation()
        val account = installation?.accountBinding
        val canRecord = enabled &&
            account != null &&
            installation.state in RECORDABLE_STATES
        if (!canRecord || !AppSyncPortabilityPolicy.isEntityPortable(domain, entityId)) {
            mutation(null)
            return null
        }
        val preparation = AppSyncMutationPreparation(domainState::entityState)
        return store.appendLocalCommand(
            accountBinding = account,
            causalContext = store.causalContext(),
            createdAtEpochMillis = nowMillis(),
            origin = SyncOperationOrigin.UserAction,
            localMutation = { portableDrafts(listOf(LocalSyncOperationDraft(
                SyncDomainId(domain), SyncEntityId(entityId), entityGeneration, kind, fields,
                bulkDeleteAuthorizationId,
            ))) },
            prepareOperationFields = preparation::prepareFields,
            afterOperationsCreated = { operations ->
                mutation(operations.singleOrNull())
                operations.forEach(domainState::recordLocal)
            },
        ).singleOrNull()
    }

    fun recordBatch(
        drafts: List<LocalSyncOperationDraft>,
        mutation: (List<SyncOperation>) -> Unit,
    ): List<SyncOperation> {
        if (drafts.isEmpty()) {
            mutation(emptyList())
            return emptyList()
        }
        val installation = store.installation()
        val account = installation?.accountBinding
        val canRecord = enabled &&
            account != null &&
            installation.state in RECORDABLE_STATES
        if (!canRecord) {
            mutation(emptyList())
            return emptyList()
        }
        val preparation = AppSyncMutationPreparation(domainState::entityState)
        return store.appendLocalCommand(
            accountBinding = account,
            causalContext = store.causalContext(),
            createdAtEpochMillis = nowMillis(),
            origin = SyncOperationOrigin.UserAction,
            localMutation = { portableDrafts(drafts) },
            prepareOperationFields = preparation::prepareFields,
            afterOperationsCreated = { operations ->
                mutation(operations)
                operations.forEach(domainState::recordLocal)
            },
        )
    }

    fun recordAuthorizedDeleteBatch(
        drafts: List<LocalSyncOperationDraft>,
        scopeKey: String,
        mutation: (List<SyncOperation>) -> Unit,
    ): List<SyncOperation> {
        if (drafts.isEmpty()) {
            mutation(emptyList())
            return emptyList()
        }
        val installation = store.installation()
        if (
            !enabled ||
            installation?.accountBinding == null ||
            installation.state !in RECORDABLE_STATES
        ) {
            mutation(emptyList())
            return emptyList()
        }
        val createdAt = nowMillis()
        val authorizedDrafts = drafts
            .filter { AppSyncPortabilityPolicy.isEntityPortable(it.domainId.value, it.entityId.value) }
            .groupBy { it.domainId }
            .values
            .flatMap { domainDrafts ->
                val authorizationId = SyncIdentityGenerator.writerNonce().value
                val expiresAt = createdAt + BULK_DELETE_CONFIRMATION_WINDOW_MILLIS
                store.saveBulkDeleteAuthorization(
                    AppSyncBulkDeleteAuthorization(
                        authorizationId = authorizationId,
                        domainId = domainDrafts.first().domainId.value,
                        scopeKey = scopeKey,
                        operationCount = domainDrafts.size.toLong(),
                        expiresAtEpochMillis = expiresAt,
                        consumedAtEpochMillis = null,
                    ),
                )
                domainDrafts.map { draft ->
                    check(draft.kind == SyncOperationKind.Delete) {
                        "Bulk-delete authorization can only be attached to delete operations"
                    }
                    draft.copy(
                        fields = draft.fields + mapOf(
                            AppSyncBulkDeleteProofFields.SCOPE to scopeKey,
                            AppSyncBulkDeleteProofFields.COUNT to domainDrafts.size.toString(),
                            AppSyncBulkDeleteProofFields.EXPIRES_AT to expiresAt.toString(),
                        ),
                        bulkDeleteAuthorizationId = authorizationId,
                    )
                }
            }
        return recordBatch(authorizedDrafts, mutation)
    }

    fun recordCommand(
        mutation: () -> List<LocalSyncOperationDraft>,
    ): List<SyncOperation> {
        val installation = store.installation()
        val account = installation?.accountBinding
        val canRecord = enabled &&
            account != null &&
            installation.state in RECORDABLE_STATES
        if (!canRecord) {
            mutation()
            return emptyList()
        }
        val preparation = AppSyncMutationPreparation(domainState::entityState)
        return store.appendLocalCommand(
            accountBinding = account,
            causalContext = store.causalContext(),
            createdAtEpochMillis = nowMillis(),
            origin = SyncOperationOrigin.UserAction,
            localMutation = { portableDrafts(mutation()) },
            prepareOperationFields = preparation::prepareFields,
            afterOperationsCreated = { operations ->
                operations.forEach(domainState::recordLocal)
            },
        )
    }

    // Filter before constructing SyncOperation, whose field-name validator also rejects
    // malformed unknown keys. Excluded data must not prevent the ordinary local mutation.
    private fun portableDrafts(drafts: List<LocalSyncOperationDraft>) = drafts
        .filter { AppSyncPortabilityPolicy.isEntityPortable(it.domainId.value, it.entityId.value) }
        .map { it.copy(fields = portableAppSyncFields(it.domainId.value, it.entityId.value, it.fields)) }

    private companion object {
        const val BULK_DELETE_CONFIRMATION_WINDOW_MILLIS = 5 * 60 * 1_000L
        val RECORDABLE_STATES = setOf(
            AppSyncInstallationState.Active,
            AppSyncInstallationState.PausedAuth,
            AppSyncInstallationState.PausedProvider,
            AppSyncInstallationState.Quarantined,
        )
    }
}

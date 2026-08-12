package me.thenano.yamibo.yamibo_app.store.appsync

import kotlinx.serialization.json.Json
import me.thenano.yamibo.yamibo_app.Database
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.OperationReductionResult
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.ResolvedSyncEntity
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncInstallation
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncBulkDeleteAuthorization
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncBootstrapRollbackSnapshot
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncInstallationState
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncOperationLifecycle
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncRunLease
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncVerifiedCheckpoint
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncReplicaObservation
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncJournalRetirementIntent
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncJournalRetirementStage
import me.thenano.yamibo.yamibo_app.repository.appsync.AppSyncAutomaticTrigger
import me.thenano.yamibo.yamibo_app.repository.appsync.AppSyncScheduleSettings
import me.thenano.yamibo.yamibo_app.repository.appsync.appSyncIntervalFromStorageKey
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.CURRENT_SYNC_OPERATION_SCHEMA_VERSION
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncAccountBinding
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncCausalContext
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncDeviceEpoch
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncDeviceId
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncDomainId
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncEntityId
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncIdentityGenerator
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperation
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperationCodec
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperationId
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperationKind
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperationOrigin
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncReplicaKey
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncSequence
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncWriterNonce
import me.thenano.yamibo.yamibo_app.AppSyncOutbox

internal class SqlDelightAppSyncOperationStore(
    private val db: Database,
    private val json: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
        explicitNulls = true
    },
) : AppSyncOperationStore {
    private val queries = db.appSyncOperationQueries
    private val operationCodec = SyncOperationCodec(json)

    override fun initialize(databaseGeneration: String): AppSyncInstallation {
        require(databaseGeneration.isNotBlank()) { "Database generation cannot be blank" }
        val current = installation()
        if (current != null) {
            if (current.databaseGeneration != databaseGeneration) {
                queries.updateInstallationState(AppSyncInstallationState.RebootstrapRequired.toDb())
            }
            return installation() ?: error("Installation disappeared during initialization")
        }
        queries.insertInstallation(
            databaseGeneration = databaseGeneration,
            accountBinding = null,
            deviceId = SyncIdentityGenerator.deviceId().value,
            deviceEpoch = SyncIdentityGenerator.deviceEpoch().value,
            writerNonce = SyncIdentityGenerator.writerNonce().value,
            nextSequence = 1L,
            state = AppSyncInstallationState.Unbound.toDb(),
            lastVerifiedHeartbeatAt = null,
            journalBlogId = null,
            lastFullDiscoveryAt = null,
            automaticEnabled = 0L,
            syncOnAppStart = 0L,
            syncOnForegroundExit = 0L,
            periodicIntervalKey = "6h",
            requestedTriggerGeneration = 0L,
            accountedTriggerGeneration = 0L,
        )
        return installation() ?: error("Failed to initialize AppSync installation")
    }

    override fun installation(): AppSyncInstallation? =
        queries.getInstallation().executeAsOneOrNull()?.let { row ->
            AppSyncInstallation(
                databaseGeneration = row.databaseGeneration,
                accountBinding = row.accountBinding?.let(::SyncAccountBinding),
                deviceId = SyncDeviceId(row.deviceId),
                deviceEpoch = SyncDeviceEpoch(row.deviceEpoch),
                writerNonce = SyncWriterNonce(row.writerNonce),
                nextSequence = row.nextSequence,
                state = AppSyncInstallationState.fromDb(row.state),
                lastVerifiedHeartbeatAt = row.lastVerifiedHeartbeatAt,
                journalBlogId = row.journalBlogId,
                lastFullDiscoveryAt = row.lastFullDiscoveryAt,
                automaticEnabled = row.automaticEnabled != 0L,
                scheduleSettings = AppSyncScheduleSettings(
                    syncOnAppStart = row.syncOnAppStart != 0L,
                    syncOnForegroundExit = row.syncOnForegroundExit != 0L,
                    periodicInterval = appSyncIntervalFromStorageKey(row.periodicIntervalKey),
                ),
                requestedTriggerGeneration = row.requestedTriggerGeneration,
                accountedTriggerGeneration = row.accountedTriggerGeneration,
            )
        }

    override fun bindAccount(
        accountBinding: SyncAccountBinding,
        state: AppSyncInstallationState,
    ) {
        val current = requireInstallation()
        queries.updateInstallationIdentity(
            accountBinding = accountBinding.value,
            deviceId = current.deviceId.value,
            deviceEpoch = current.deviceEpoch.value,
            writerNonce = current.writerNonce.value,
            nextSequence = current.nextSequence,
            state = state.toDb(),
        )
    }

    override fun rotateDeviceEpoch(
        accountBinding: SyncAccountBinding,
        state: AppSyncInstallationState,
    ) {
        val current = requireInstallation()
        db.transaction {
            queries.markReplicaOperationsDiscardedByRebootstrap(
                deviceId = current.deviceId.value,
                deviceEpoch = current.deviceEpoch.value,
            )
            queries.updateInstallationIdentity(
                accountBinding = accountBinding.value,
                deviceId = SyncIdentityGenerator.deviceId().value,
                deviceEpoch = SyncIdentityGenerator.deviceEpoch().value,
                writerNonce = SyncIdentityGenerator.writerNonce().value,
                nextSequence = 1L,
                state = state.toDb(),
            )
        }
        check(current.databaseGeneration == requireInstallation().databaseGeneration)
    }

    override fun updateState(state: AppSyncInstallationState) {
        requireInstallation()
        queries.updateInstallationState(state.toDb())
    }

    override fun updateVerifiedHeartbeat(atEpochMillis: Long, journalBlogId: Long?) {
        queries.updateInstallationHeartbeat(
            lastVerifiedHeartbeatAt = atEpochMillis,
            journalBlogId = journalBlogId,
            state = AppSyncInstallationState.Active.toDb(),
        )
    }

    override fun updateDiscoveryTime(atEpochMillis: Long) {
        queries.updateDiscoveryTime(atEpochMillis)
    }

    override fun setAutomaticEnabled(enabled: Boolean) {
        queries.setAutomaticEnabled(if (enabled) 1L else 0L)
    }

    override fun setScheduleSettings(settings: AppSyncScheduleSettings) {
        require(settings.periodicInterval in me.thenano.yamibo.yamibo_app.repository.appsync.AppSyncPeriodicIntervals) {
            "Unsupported AppSync periodic interval"
        }
        queries.setScheduleSettings(
            syncOnAppStart = if (settings.syncOnAppStart) 1L else 0L,
            syncOnForegroundExit = if (settings.syncOnForegroundExit) 1L else 0L,
            periodicIntervalKey = settings.periodicInterval.storageKey,
        )
    }

    override fun requestAutomaticTrigger(trigger: AppSyncAutomaticTrigger): Long? {
        var requestedGeneration: Long? = null
        db.transaction {
            val current = requireInstallation()
            val triggerEnabled = when (trigger) {
                AppSyncAutomaticTrigger.AppStartup -> current.scheduleSettings.syncOnAppStart
                AppSyncAutomaticTrigger.ForegroundExit ->
                    current.scheduleSettings.syncOnForegroundExit
            }
            if (current.automaticEnabled && triggerEnabled) {
                queries.advanceRequestedTriggerGeneration()
                requestedGeneration = requireInstallation().requestedTriggerGeneration
            }
        }
        return requestedGeneration
    }

    override fun accountAutomaticTrigger(upToGeneration: Long) {
        db.transaction {
            val current = requireInstallation()
            val accounted = minOf(upToGeneration, current.requestedTriggerGeneration)
            if (accounted > current.accountedTriggerGeneration) {
                queries.setAccountedTriggerGeneration(accounted)
            }
        }
    }

    override fun prepareForCloudReset() {
        queries.prepareInstallationForCloudReset()
    }

    override fun appendLocalOperation(
        accountBinding: SyncAccountBinding,
        domainId: SyncDomainId,
        entityId: SyncEntityId,
        entityGeneration: Long,
        kind: SyncOperationKind,
        fields: Map<String, String?>,
        causalContext: SyncCausalContext,
        createdAtEpochMillis: Long,
        origin: SyncOperationOrigin,
        bulkDeleteAuthorizationId: String?,
        localMutation: (SyncOperation) -> Unit,
    ): SyncOperation = appendLocalOperations(
        accountBinding = accountBinding,
        drafts = listOf(
            LocalSyncOperationDraft(
                domainId = domainId,
                entityId = entityId,
                entityGeneration = entityGeneration,
                kind = kind,
                fields = fields,
                bulkDeleteAuthorizationId = bulkDeleteAuthorizationId,
            ),
        ),
        causalContext = causalContext,
        createdAtEpochMillis = createdAtEpochMillis,
        origin = origin,
    ) { operations ->
        localMutation(operations.single())
    }.single()

    override fun appendLocalOperations(
        accountBinding: SyncAccountBinding,
        drafts: List<LocalSyncOperationDraft>,
        causalContext: SyncCausalContext,
        createdAtEpochMillis: Long,
        origin: SyncOperationOrigin,
        localMutation: (List<SyncOperation>) -> Unit,
    ): List<SyncOperation> = appendLocalCommand(
        accountBinding = accountBinding,
        causalContext = causalContext,
        createdAtEpochMillis = createdAtEpochMillis,
        origin = origin,
        localMutation = { drafts },
        afterOperationsCreated = localMutation,
    )

    override fun captureBootstrapMigration(
        accountBinding: SyncAccountBinding,
        drafts: List<LocalSyncOperationDraft>,
        causalContext: SyncCausalContext,
        createdAtEpochMillis: Long,
    ): List<SyncOperation> {
        var created = emptyList<SyncOperation>()
        db.transaction {
            val before = requireInstallation()
            require(before.accountBinding == null) {
                "Bootstrap migration can only be captured by an unbound installation"
            }
            queries.updateInstallationIdentity(
                accountBinding = accountBinding.value,
                deviceId = SyncIdentityGenerator.deviceId().value,
                deviceEpoch = SyncIdentityGenerator.deviceEpoch().value,
                writerNonce = SyncIdentityGenerator.writerNonce().value,
                nextSequence = 1L,
                state = AppSyncInstallationState.Bootstrapping.toDb(),
            )
            val installation = requireInstallation()
            created = drafts.mapIndexed { index, draft ->
                val sequence = SyncSequence(installation.nextSequence + index)
                SyncOperation(
                    operationId = SyncOperation.idFor(
                        installation.deviceId,
                        installation.deviceEpoch,
                        sequence,
                    ),
                    deviceId = installation.deviceId,
                    deviceEpoch = installation.deviceEpoch,
                    sequence = sequence,
                    accountBinding = accountBinding,
                    domainId = draft.domainId,
                    entityId = draft.entityId,
                    entityGeneration = draft.entityGeneration,
                    kind = draft.kind,
                    fields = draft.fields,
                    causalContext = causalContext,
                    createdAtEpochMillis = createdAtEpochMillis,
                    origin = SyncOperationOrigin.Migration,
                    bulkDeleteAuthorizationId = draft.bulkDeleteAuthorizationId,
                )
            }
            created.forEach {
                insertOutbox(it, AppSyncOperationLifecycle.PendingLocal)
                queries.advanceNextSequence()
            }
        }
        return created
    }

    override fun saveBootstrapRollbackSnapshot(snapshot: AppSyncBootstrapRollbackSnapshot) {
        require(snapshot.databaseGeneration.isNotBlank()) {
            "Rollback snapshot database generation cannot be blank"
        }
        require(snapshot.encodedSnapshot.isNotBlank()) {
            "Rollback snapshot payload cannot be blank"
        }
        queries.saveBootstrapRollbackSnapshot(
            accountBinding = snapshot.accountBinding.value,
            databaseGeneration = snapshot.databaseGeneration,
            encodedSnapshot = snapshot.encodedSnapshot,
            createdAtEpochMillis = snapshot.createdAtEpochMillis,
        )
    }

    override fun latestBootstrapRollbackSnapshot(): AppSyncBootstrapRollbackSnapshot? =
        queries.getBootstrapRollbackSnapshot().executeAsOneOrNull()?.let { row ->
            AppSyncBootstrapRollbackSnapshot(
                accountBinding = SyncAccountBinding(row.accountBinding),
                databaseGeneration = row.databaseGeneration,
                encodedSnapshot = row.encodedSnapshot,
                createdAtEpochMillis = row.createdAtEpochMillis,
            )
        }

    override fun appendLocalCommand(
        accountBinding: SyncAccountBinding,
        causalContext: SyncCausalContext,
        createdAtEpochMillis: Long,
        origin: SyncOperationOrigin,
        localMutation: () -> List<LocalSyncOperationDraft>,
        afterOperationsCreated: (List<SyncOperation>) -> Unit,
    ): List<SyncOperation> {
        var created: List<SyncOperation>? = null
        db.transaction {
            val installation = requireInstallation()
            require(installation.accountBinding == accountBinding) {
                "Operation account does not match installation binding"
            }
            require(
                installation.state in setOf(
                    AppSyncInstallationState.Active,
                    AppSyncInstallationState.PausedAuth,
                    AppSyncInstallationState.PausedProvider,
                    AppSyncInstallationState.Quarantined,
                ),
            ) {
                "Local publication is disabled while installation is ${installation.state}"
            }
            val drafts = localMutation()
            val operations = drafts.mapIndexed { index, draft ->
                val sequence = SyncSequence(installation.nextSequence + index)
                SyncOperation(
                    operationId = SyncOperation.idFor(
                        installation.deviceId,
                        installation.deviceEpoch,
                        sequence,
                    ),
                    deviceId = installation.deviceId,
                    deviceEpoch = installation.deviceEpoch,
                    sequence = sequence,
                    accountBinding = accountBinding,
                    domainId = draft.domainId,
                    entityId = draft.entityId,
                    entityGeneration = draft.entityGeneration,
                    kind = draft.kind,
                    fields = draft.fields,
                    causalContext = causalContext,
                    createdAtEpochMillis = createdAtEpochMillis,
                    origin = origin,
                    bulkDeleteAuthorizationId = draft.bulkDeleteAuthorizationId,
                )
            }
            afterOperationsCreated(operations)
            operations.forEach {
                insertOutbox(it, AppSyncOperationLifecycle.PendingLocal)
                queries.advanceNextSequence()
            }
            created = operations
        }
        return requireNotNull(created)
    }

    override fun pendingOperations(): List<SyncOperation> =
        queries.getPendingOperations().executeAsList().map(::decodeOperation)

    override fun allOutboxOperations(): List<Pair<SyncOperation, AppSyncOperationLifecycle>> =
        queries.getAllOutboxOperations().executeAsList().map { row ->
            decodeOperation(row) to AppSyncOperationLifecycle.fromDb(row.lifecycle)
        }

    override fun markPublishedUnverified(operationIds: Set<SyncOperationId>) {
        if (operationIds.isNotEmpty()) {
            queries.markOperationsPublishedUnverified(operationIds.map { it.value })
        }
    }

    override fun markAcknowledged(
        operationIds: Set<SyncOperationId>,
        atEpochMillis: Long,
    ) {
        if (operationIds.isNotEmpty()) {
            queries.markOperationsAcknowledged(
                acknowledgedAtEpochMillis = atEpochMillis,
                operationId = operationIds.map { it.value },
            )
        }
    }

    override fun markCompacted(operationIds: Set<SyncOperationId>) {
        if (operationIds.isNotEmpty()) {
            queries.markOperationsCompacted(operationIds.map { it.value })
        }
    }

    override fun replaceWithVerifiedCloudState(
        result: OperationReductionResult,
        coverage: SyncCausalContext,
        cloudOperationIds: Set<SyncOperationId>,
        appliedAtEpochMillis: Long,
        domainMutation: (OperationReductionResult) -> Unit,
    ) {
        db.transaction {
            val current = requireInstallation()
            val accountBinding = requireNotNull(current.accountBinding) {
                "Force pull requires an account-bound installation"
            }
            val localCloudIds = allOutboxOperations()
                .filter { (_, lifecycle) ->
                    lifecycle == AppSyncOperationLifecycle.PendingLocal ||
                        lifecycle == AppSyncOperationLifecycle.PublishedUnverified
                }
                .mapTo(linkedSetOf()) { it.first.operationId }
                .intersect(cloudOperationIds)
            if (localCloudIds.isNotEmpty()) {
                queries.markOperationsAcknowledged(
                    acknowledgedAtEpochMillis = appliedAtEpochMillis,
                    operationId = localCloudIds.map { it.value },
                )
            }
            val discarded = allOutboxOperations()
                .filter { (_, lifecycle) ->
                    lifecycle == AppSyncOperationLifecycle.PendingLocal ||
                        lifecycle == AppSyncOperationLifecycle.PublishedUnverified
                }
                .mapTo(linkedSetOf()) { it.first.operationId }
                .minus(cloudOperationIds)
            if (discarded.isNotEmpty()) {
                queries.markOperationsDiscardedByForcePull(discarded.map { it.value })
            }
            domainMutation(result)
            queries.clearCausalWatermarks()
            coverage.asStableMap().forEach { (replicaKey, sequence) ->
                queries.upsertCausalWatermark(replicaKey, sequence)
            }
            recordReductionMetadata(result, appliedAtEpochMillis)
            // A force pull may discard unpublished operations from the current replica. Reusing
            // that replica afterwards would create a permanent sequence gap when the next local
            // operation is appended. Start a fresh writer identity while retaining the old
            // outbox rows and their DiscardedByForcePull lifecycle as an audit trail.
            queries.updateInstallationIdentity(
                accountBinding = accountBinding.value,
                deviceId = SyncIdentityGenerator.deviceId().value,
                deviceEpoch = SyncIdentityGenerator.deviceEpoch().value,
                writerNonce = SyncIdentityGenerator.writerNonce().value,
                nextSequence = 1L,
                state = AppSyncInstallationState.Active.toDb(),
            )
        }
    }

    override fun completeBootstrap(
        accountBinding: SyncAccountBinding,
        result: OperationReductionResult,
        coverage: SyncCausalContext,
        cloudOperationIds: Set<SyncOperationId>,
        appliedAtEpochMillis: Long,
        rotateDeviceEpoch: Boolean,
        checkpoint: AppSyncVerifiedCheckpoint?,
        domainMutation: (OperationReductionResult) -> Unit,
    ) {
        db.transaction {
            val current = requireInstallation()
            require(
                rotateDeviceEpoch ||
                    current.accountBinding == null ||
                    current.accountBinding == accountBinding,
            ) {
                "Bootstrap account does not match installation binding"
            }
            val acknowledged = allOutboxOperations()
                .asSequence()
                .filter { (_, lifecycle) ->
                    lifecycle == AppSyncOperationLifecycle.PendingLocal ||
                        lifecycle == AppSyncOperationLifecycle.PublishedUnverified
                }
                .map { it.first.operationId }
                .filter(cloudOperationIds::contains)
                .toSet()
            if (acknowledged.isNotEmpty()) {
                queries.markOperationsAcknowledged(
                    acknowledgedAtEpochMillis = appliedAtEpochMillis,
                    operationId = acknowledged.map { it.value },
                )
            }
            domainMutation(result)
            queries.clearCausalWatermarks()
            coverage.asStableMap().forEach { (replicaKey, sequence) ->
                queries.upsertCausalWatermark(replicaKey, sequence)
            }
            recordReductionMetadata(result, appliedAtEpochMillis)
            checkpoint?.let {
                queries.upsertCheckpoint(
                    checkpointId = it.checkpointId,
                    blogId = it.blogId,
                    causalContextJson = json.encodeToString(
                        SyncCausalContext.serializer(),
                        it.coverage,
                    ),
                    payloadFingerprint = it.payloadFingerprint,
                    state = "VERIFIED",
                    createdAtEpochMillis = it.createdAtEpochMillis,
                    verifiedAtEpochMillis = it.verifiedAtEpochMillis,
                )
            }
            if (rotateDeviceEpoch) {
                queries.markReplicaOperationsDiscardedByRebootstrap(
                    deviceId = current.deviceId.value,
                    deviceEpoch = current.deviceEpoch.value,
                )
                queries.updateInstallationIdentity(
                    accountBinding = accountBinding.value,
                    deviceId = SyncIdentityGenerator.deviceId().value,
                    deviceEpoch = SyncIdentityGenerator.deviceEpoch().value,
                    writerNonce = SyncIdentityGenerator.writerNonce().value,
                    nextSequence = 1L,
                    state = AppSyncInstallationState.Active.toDb(),
                )
            } else {
                queries.updateInstallationIdentity(
                    accountBinding = accountBinding.value,
                    deviceId = current.deviceId.value,
                    deviceEpoch = current.deviceEpoch.value,
                    writerNonce = current.writerNonce.value,
                    nextSequence = current.nextSequence,
                    state = AppSyncInstallationState.Active.toDb(),
                )
            }
        }
    }

    override fun isApplied(operationId: SyncOperationId): Boolean =
        queries.isOperationApplied(operationId.value).executeAsOne() > 0L

    override fun applyRemoteReduction(
        result: OperationReductionResult,
        appliedAtEpochMillis: Long,
        domainMutation: (OperationReductionResult) -> Unit,
    ) {
        db.transaction {
            domainMutation(result)
            recordReductionMetadata(result, appliedAtEpochMillis)
        }
    }

    override fun adoptCheckpoint(
        checkpointId: String,
        blogId: Long?,
        coverage: SyncCausalContext,
        payloadFingerprint: String,
        createdAtEpochMillis: Long,
        verifiedAtEpochMillis: Long,
        laterReduction: OperationReductionResult,
        domainMutation: (OperationReductionResult) -> Unit,
    ) {
        db.transaction {
            domainMutation(laterReduction)
            queries.clearCausalWatermarks()
            coverage.asStableMap().forEach { (replicaKey, sequence) ->
                queries.upsertCausalWatermark(replicaKey, sequence)
            }
            recordReductionMetadata(laterReduction, verifiedAtEpochMillis)
            queries.upsertCheckpoint(
                checkpointId = checkpointId,
                blogId = blogId,
                causalContextJson = json.encodeToString(SyncCausalContext.serializer(), coverage),
                payloadFingerprint = payloadFingerprint,
                state = "VERIFIED",
                createdAtEpochMillis = createdAtEpochMillis,
                verifiedAtEpochMillis = verifiedAtEpochMillis,
            )
        }
    }

    override fun saveVerifiedCheckpoint(checkpoint: AppSyncVerifiedCheckpoint) {
        queries.upsertCheckpoint(
            checkpointId = checkpoint.checkpointId,
            blogId = checkpoint.blogId,
            causalContextJson = json.encodeToString(
                SyncCausalContext.serializer(),
                checkpoint.coverage,
            ),
            payloadFingerprint = checkpoint.payloadFingerprint,
            state = "VERIFIED",
            createdAtEpochMillis = checkpoint.createdAtEpochMillis,
            verifiedAtEpochMillis = checkpoint.verifiedAtEpochMillis,
        )
    }

    override fun verifiedCheckpoints(): List<AppSyncVerifiedCheckpoint> =
        queries.getVerifiedCheckpoints().executeAsList().map {
            AppSyncVerifiedCheckpoint(
                checkpointId = it.checkpointId,
                blogId = it.blogId,
                coverage = json.decodeFromString(
                    SyncCausalContext.serializer(),
                    it.causalContextJson,
                ),
                payloadFingerprint = it.payloadFingerprint,
                createdAtEpochMillis = it.createdAtEpochMillis,
                verifiedAtEpochMillis = requireNotNull(it.verifiedAtEpochMillis),
            )
        }

    override fun retainVerifiedCheckpoints(checkpointIds: Set<String>) {
        queries.transaction {
            queries.getVerifiedCheckpoints().executeAsList()
                .asSequence()
                .map { it.checkpointId }
                .filterNot(checkpointIds::contains)
                .forEach(queries::deleteCheckpoint)
        }
    }

    override fun recordReplicaObservation(
        accountBinding: SyncAccountBinding,
        replicaKey: String,
        sourceBlogId: Long,
        fingerprint: String,
        publishedThroughSequence: Long,
        observedAtEpochMillis: Long,
        maximumObservationGapMillis: Long,
    ): AppSyncReplicaObservation {
        require(
            replicaKey.isNotBlank() &&
                sourceBlogId > 0L &&
                fingerprint.isNotBlank() &&
                publishedThroughSequence >= 0L &&
                maximumObservationGapMillis > 0L,
        )
        var recorded: AppSyncReplicaObservation? = null
        db.transaction {
            val existing = queries.getReplicaObservation(
                accountBinding.value,
                replicaKey,
            ).executeAsOneOrNull()
            val unchanged = existing != null &&
                existing.sourceBlogId == sourceBlogId &&
                existing.fingerprint == fingerprint &&
                existing.publishedThroughSequence == publishedThroughSequence &&
                observedAtEpochMillis >= existing.lastObservedAtEpochMillis &&
                observedAtEpochMillis - existing.lastObservedAtEpochMillis <=
                maximumObservationGapMillis
            val firstObservedAt = if (unchanged) {
                requireNotNull(existing).firstObservedUnchangedAtEpochMillis
            } else {
                observedAtEpochMillis
            }
            queries.upsertReplicaObservation(
                accountBinding = accountBinding.value,
                replicaKey = replicaKey,
                sourceBlogId = sourceBlogId,
                fingerprint = fingerprint,
                publishedThroughSequence = publishedThroughSequence,
                firstObservedUnchangedAtEpochMillis = firstObservedAt,
                lastObservedAtEpochMillis = observedAtEpochMillis,
            )
            recorded = AppSyncReplicaObservation(
                accountBinding = accountBinding,
                replicaKey = replicaKey,
                sourceBlogId = sourceBlogId,
                fingerprint = fingerprint,
                publishedThroughSequence = publishedThroughSequence,
                firstObservedUnchangedAtEpochMillis = firstObservedAt,
                lastObservedAtEpochMillis = observedAtEpochMillis,
            )
        }
        return requireNotNull(recorded)
    }

    override fun replicaObservations(
        accountBinding: SyncAccountBinding,
    ): List<AppSyncReplicaObservation> =
        queries.getReplicaObservations(accountBinding.value).executeAsList().map {
            AppSyncReplicaObservation(
                accountBinding = SyncAccountBinding(it.accountBinding),
                replicaKey = it.replicaKey,
                sourceBlogId = it.sourceBlogId,
                fingerprint = it.fingerprint,
                publishedThroughSequence = it.publishedThroughSequence,
                firstObservedUnchangedAtEpochMillis = it.firstObservedUnchangedAtEpochMillis,
                lastObservedAtEpochMillis = it.lastObservedAtEpochMillis,
            )
        }

    override fun saveRetirementIntent(intent: AppSyncJournalRetirementIntent) {
        queries.upsertRetirementIntent(
            accountBinding = intent.accountBinding.value,
            replicaKey = intent.replicaKey,
            sourceBlogId = intent.sourceBlogId,
            fingerprint = intent.fingerprint,
            publishedThroughSequence = intent.publishedThroughSequence,
            checkpointId = intent.checkpointId,
            checkpointFingerprint = intent.checkpointFingerprint,
            checkpointVectorHash = intent.checkpointVectorHash,
            activeSetHash = intent.activeSetHash,
            stage = intent.stage.name.uppercase(),
            attempts = intent.attempts,
            lastResultCode = intent.lastResultCode,
            createdAtEpochMillis = intent.createdAtEpochMillis,
            updatedAtEpochMillis = intent.updatedAtEpochMillis,
            completedAtEpochMillis = intent.completedAtEpochMillis,
        )
    }

    override fun retirementIntents(
        accountBinding: SyncAccountBinding,
    ): List<AppSyncJournalRetirementIntent> =
        queries.getRetirementIntents(accountBinding.value).executeAsList().map {
            AppSyncJournalRetirementIntent(
                accountBinding = SyncAccountBinding(it.accountBinding),
                replicaKey = it.replicaKey,
                sourceBlogId = it.sourceBlogId,
                fingerprint = it.fingerprint,
                publishedThroughSequence = it.publishedThroughSequence,
                checkpointId = it.checkpointId,
                checkpointFingerprint = it.checkpointFingerprint,
                checkpointVectorHash = it.checkpointVectorHash,
                activeSetHash = it.activeSetHash,
                stage = AppSyncJournalRetirementStage.entries.first {
                    stage -> stage.name.equals(it.stage, ignoreCase = true)
                },
                attempts = it.attempts,
                lastResultCode = it.lastResultCode,
                createdAtEpochMillis = it.createdAtEpochMillis,
                updatedAtEpochMillis = it.updatedAtEpochMillis,
                completedAtEpochMillis = it.completedAtEpochMillis,
            )
        }

    override fun transitionRetirementIntent(
        accountBinding: SyncAccountBinding,
        replicaKey: String,
        expectedStage: AppSyncJournalRetirementStage,
        newStage: AppSyncJournalRetirementStage,
        resultCode: String?,
        atEpochMillis: Long,
        incrementAttempts: Boolean,
    ): Boolean {
        var transitioned = false
        db.transaction {
            val current = queries.getRetirementIntents(accountBinding.value)
                .executeAsList()
                .firstOrNull { it.replicaKey == replicaKey }
            if (current?.stage?.equals(expectedStage.name, ignoreCase = true) == true) {
                queries.transitionRetirementIntent(
                    stage = newStage.name.uppercase(),
                    attempts = if (incrementAttempts) 1L else 0L,
                    lastResultCode = resultCode,
                    updatedAtEpochMillis = atEpochMillis,
                    completedAtEpochMillis = atEpochMillis.takeIf {
                        newStage == AppSyncJournalRetirementStage.Completed
                    },
                    accountBinding = accountBinding.value,
                    replicaKey = replicaKey,
                    stage_ = expectedStage.name.uppercase(),
                )
                transitioned = true
            }
        }
        return transitioned
    }

    override fun pinnedRetirementCheckpointIds(): Set<String> =
        queries.getPinnedRetirementCheckpointIds().executeAsList().toSet()

    private fun recordReductionMetadata(
        result: OperationReductionResult,
        appliedAtEpochMillis: Long,
    ) {
        result.appliedOperations.forEach { operation ->
            queries.insertAppliedOperation(
                operationId = operation.operationId.value,
                deviceId = operation.deviceId.value,
                deviceEpoch = operation.deviceEpoch.value,
                sequence = operation.sequence.value,
                appliedAtEpochMillis = appliedAtEpochMillis,
            )
            val replica = operation.replicaKey
            val current = queries.getCausalWatermark(replica.stableKey)
                .executeAsOneOrNull()
                ?.sequence
                ?: 0L
            if (operation.sequence.value > current) {
                queries.upsertCausalWatermark(replica.stableKey, operation.sequence.value)
            }
        }
        result.conflicts.forEach { conflict ->
            queries.insertConflict(
                domainId = conflict.entityKey.domainId.value,
                entityId = conflict.entityKey.entityId.value,
                entityGeneration = conflict.entityKey.generation,
                fieldName = conflict.field,
                winnerOperationId = conflict.winnerOperationId.value,
                loserOperationId = conflict.loserOperationId.value,
                policy = conflict.policy.name,
                reason = conflict.reason,
                recordedAtEpochMillis = appliedAtEpochMillis,
            )
        }
        result.quarantined.forEach { quarantine ->
            queries.upsertQuarantine(
                operationId = quarantine.operation.operationId.value,
                encodedOperation = operationCodec.encode(quarantine.operation),
                reasonCode = "VALIDATION_FAILED",
                reasonDetail = quarantine.reason,
                quarantinedAtEpochMillis = appliedAtEpochMillis,
                retryAfterSchemaVersion = null,
            )
        }
        advanceCausalWatermarks(
            result.entities.values.flatMap { entity ->
                buildList {
                    addAll(entity.fields.values.map { it.operation })
                    entity.relationOperation?.let(::add)
                    entity.tombstone?.let(::add)
                }
            },
        )
    }

    override fun causalContext(): SyncCausalContext =
        queries.getCausalWatermarks().executeAsList().fold(SyncCausalContext()) { context, row ->
            val separator = row.replicaKey.lastIndexOf(':')
            if (separator <= 0 || separator == row.replicaKey.lastIndex) {
                context
            } else {
                context.advance(
                    SyncReplicaKey(
                        SyncDeviceId(row.replicaKey.substring(0, separator)),
                        SyncDeviceEpoch(row.replicaKey.substring(separator + 1)),
                    ),
                    SyncSequence(row.sequence),
                )
            }
        }

    override fun reconcileResolvedStateCoverage() {
        advanceCausalWatermarks(
            queries.getResolvedEntities().executeAsList().flatMap { row ->
                val entity = json.decodeFromString(
                    ResolvedSyncEntity.serializer(),
                    row.encodedState,
                )
                buildList {
                    addAll(entity.fields.values.map { it.operation })
                    entity.relationOperation?.let(::add)
                    entity.tombstone?.let(::add)
                }
            },
        )
    }

    private fun advanceCausalWatermarks(operations: Iterable<SyncOperation>) {
        operations.distinctBy { it.operationId }.forEach { operation ->
            val replica = operation.replicaKey
            val current = queries.getCausalWatermark(replica.stableKey)
                .executeAsOneOrNull()
                ?.sequence
                ?: 0L
            if (operation.sequence.value > current) {
                queries.upsertCausalWatermark(replica.stableKey, operation.sequence.value)
            }
        }
    }

    override fun acquireLease(
        ownerId: String,
        nowEpochMillis: Long,
        durationMillis: Long,
    ): Boolean {
        require(ownerId.isNotBlank()) { "Lease owner cannot be blank" }
        require(durationMillis > 0L) { "Lease duration must be positive" }
        var acquired = false
        db.transaction {
            val current = queries.getRunLease().executeAsOneOrNull()
            if (current == null || current.ownerId == ownerId || current.expiresAtEpochMillis <= nowEpochMillis) {
                queries.acquireRunLease(
                    ownerId = ownerId,
                    acquiredAtEpochMillis = nowEpochMillis,
                    expiresAtEpochMillis = nowEpochMillis + durationMillis,
                )
                acquired = true
            }
        }
        return acquired
    }

    override fun currentLease(): AppSyncRunLease? =
        queries.getRunLease().executeAsOneOrNull()?.let {
            AppSyncRunLease(it.ownerId, it.acquiredAtEpochMillis, it.expiresAtEpochMillis)
        }

    override fun releaseLease(ownerId: String) {
        queries.releaseRunLease(ownerId)
    }

    override fun saveBulkDeleteAuthorization(authorization: AppSyncBulkDeleteAuthorization) {
        queries.insertBulkDeleteAuthorization(
            authorizationId = authorization.authorizationId,
            domainId = authorization.domainId,
            scopeKey = authorization.scopeKey,
            operationCount = authorization.operationCount,
            expiresAtEpochMillis = authorization.expiresAtEpochMillis,
        )
    }

    override fun loadBulkDeleteAuthorization(
        authorizationId: String,
    ): AppSyncBulkDeleteAuthorization? =
        queries.getBulkDeleteAuthorization(authorizationId).executeAsOneOrNull()?.let {
            AppSyncBulkDeleteAuthorization(
                authorizationId = it.authorizationId,
                domainId = it.domainId,
                scopeKey = it.scopeKey,
                operationCount = it.operationCount,
                expiresAtEpochMillis = it.expiresAtEpochMillis,
                consumedAtEpochMillis = it.consumedAtEpochMillis,
            )
        }

    override fun consumeBulkDeleteAuthorization(
        authorizationId: String,
        nowEpochMillis: Long,
    ): Boolean {
        queries.consumeBulkDeleteAuthorization(
            consumedAtEpochMillis = nowEpochMillis,
            authorizationId = authorizationId,
            expiresAtEpochMillis = nowEpochMillis,
        )
        return loadBulkDeleteAuthorization(authorizationId)?.consumedAtEpochMillis == nowEpochMillis
    }

    private fun insertOutbox(
        operation: SyncOperation,
        lifecycle: AppSyncOperationLifecycle,
    ) {
        queries.insertOutboxOperation(
            operationId = operation.operationId.value,
            deviceId = operation.deviceId.value,
            deviceEpoch = operation.deviceEpoch.value,
            sequence = operation.sequence.value,
            accountBinding = operation.accountBinding.value,
            domainId = operation.domainId.value,
            entityId = operation.entityId.value,
            entityGeneration = operation.entityGeneration,
            kind = operation.kind.name,
            fieldsJson = json.encodeToString(operation.fields),
            causalContextJson = json.encodeToString(SyncCausalContext.serializer(), operation.causalContext),
            createdAtEpochMillis = operation.createdAtEpochMillis,
            origin = operation.origin.name,
            bulkDeleteAuthorizationId = operation.bulkDeleteAuthorizationId,
            schemaVersion = operation.schemaVersion.toLong(),
            lifecycle = lifecycle.toDb(),
            acknowledgedAtEpochMillis = null,
        )
    }

    private fun decodeOperation(row: AppSyncOutbox): SyncOperation {
        val deviceId = SyncDeviceId(row.deviceId)
        val epoch = SyncDeviceEpoch(row.deviceEpoch)
        val sequence = SyncSequence(row.sequence)
        return SyncOperation(
            operationId = SyncOperationId(row.operationId),
            deviceId = deviceId,
            deviceEpoch = epoch,
            sequence = sequence,
            accountBinding = SyncAccountBinding(row.accountBinding),
            domainId = SyncDomainId(row.domainId),
            entityId = SyncEntityId(row.entityId),
            entityGeneration = row.entityGeneration,
            kind = SyncOperationKind.valueOf(row.kind),
            fields = json.decodeFromString(row.fieldsJson),
            causalContext = json.decodeFromString(
                SyncCausalContext.serializer(),
                row.causalContextJson,
            ),
            createdAtEpochMillis = row.createdAtEpochMillis,
            origin = SyncOperationOrigin.valueOf(row.origin),
            bulkDeleteAuthorizationId = row.bulkDeleteAuthorizationId,
            schemaVersion = row.schemaVersion.toInt().also {
                require(it == CURRENT_SYNC_OPERATION_SCHEMA_VERSION)
            },
        )
    }

    private fun requireInstallation(): AppSyncInstallation =
        requireNotNull(installation()) { "AppSync installation is not initialized" }
}

private fun AppSyncInstallationState.toDb(): String =
    name.replace(UPPER_BOUNDARY, "_$1").uppercase()

private fun AppSyncOperationLifecycle.toDb(): String =
    name.replace(UPPER_BOUNDARY, "_$1").uppercase()

private fun AppSyncInstallationState.Companion.fromDb(value: String): AppSyncInstallationState =
    AppSyncInstallationState.entries.firstOrNull { it.toDb() == value }
        ?: throw IllegalArgumentException("Unknown installation state: $value")

private fun AppSyncOperationLifecycle.Companion.fromDb(value: String): AppSyncOperationLifecycle =
    AppSyncOperationLifecycle.entries.firstOrNull { it.toDb() == value }
        ?: throw IllegalArgumentException("Unknown operation lifecycle: $value")

private val UPPER_BOUNDARY = Regex("(?<=[a-z0-9])([A-Z])")

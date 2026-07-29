package me.thenano.yamibo.yamibo_app.repository.appsync.engine

import io.github.littlesurvival.dto.value.FormHash
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncInstallationState
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncVerifiedCheckpoint
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncAccountBinding
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperation
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperationId
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncJournalPayload
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncCheckpointAcknowledgement
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.ParsedAppSyncCheckpointEnvelope
import me.thenano.yamibo.yamibo_app.store.appsync.AppSyncOperationStore

internal data class LoadedAppSyncJournal(
    val remoteId: String,
    val fingerprint: String,
    val payload: AppSyncJournalPayload,
)

internal data class LoadedAppSyncCheckpoint(
    val remoteId: String,
    val envelope: ParsedAppSyncCheckpointEnvelope,
)

internal sealed interface AppSyncJournalLoadResult {
    data class Success(
        val journals: List<LoadedAppSyncJournal>,
        val checkpoints: List<LoadedAppSyncCheckpoint> = emptyList(),
    ) : AppSyncJournalLoadResult
    data object NotLoggedIn : AppSyncJournalLoadResult
    data class RetryableFailure(val reason: String) : AppSyncJournalLoadResult
    data class TerminalFailure(val reason: String) : AppSyncJournalLoadResult
}

internal sealed interface AppSyncJournalPublishResult {
    data class Verified(
        val journal: LoadedAppSyncJournal,
    ) : AppSyncJournalPublishResult

    data class Unknown(
        val reason: String,
    ) : AppSyncJournalPublishResult

    data class Conflict(
        val reason: String,
    ) : AppSyncJournalPublishResult

    data object FormExpired : AppSyncJournalPublishResult
    data class StoragePressure(val encodedChars: Int, val limitChars: Int) :
        AppSyncJournalPublishResult
    data class TerminalFailure(val reason: String) : AppSyncJournalPublishResult
}

internal sealed interface AppSyncCheckpointPublishResult {
    data class Verified(val checkpoint: LoadedAppSyncCheckpoint) : AppSyncCheckpointPublishResult
    data class Unknown(val reason: String) : AppSyncCheckpointPublishResult
    data object FormExpired : AppSyncCheckpointPublishResult
    data class StoragePressure(val encodedChars: Int, val limitChars: Int) :
        AppSyncCheckpointPublishResult
    data class TerminalFailure(val reason: String) : AppSyncCheckpointPublishResult
}

internal interface AppSyncJournalRemote {
    suspend fun loadJournals(
        accountBinding: SyncAccountBinding,
        forceDiscovery: Boolean,
    ): AppSyncJournalLoadResult

    suspend fun publishOwnJournal(
        payload: AppSyncJournalPayload,
        expectedFingerprint: String?,
        formHash: FormHash,
    ): AppSyncJournalPublishResult

    suspend fun publishCheckpoint(
        payload: me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncCheckpointPayload,
        formHash: FormHash,
    ): AppSyncCheckpointPublishResult =
        AppSyncCheckpointPublishResult.TerminalFailure("Checkpoint publication is unsupported")
}

internal interface SyncDomainStateAdapter {
    fun currentState(): Map<SyncEntityKey, ResolvedSyncEntity>
    fun apply(result: OperationReductionResult)
    fun applyWithinTransaction(result: OperationReductionResult) = apply(result)
    fun reconcileProjections() = Unit
    fun adoptCheckpoint(entities: Collection<ResolvedSyncEntity>) {
        apply(
            OperationReductionResult(
                entities = entities.associateBy { it.key },
                conflicts = emptyList(),
                quarantined = emptyList(),
                appliedOperations = emptyList(),
            ),
        )
    }
    fun adoptCheckpointWithinTransaction(entities: Collection<ResolvedSyncEntity>) =
        adoptCheckpoint(entities)
    fun entityCount(domainId: me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncDomainId): Int =
        currentState().keys.count { it.domainId == domainId }
}

internal sealed interface OperationSyncResult {
    data class Converged(
        val appliedRemoteCount: Int,
        val acknowledgedLocalCount: Int,
        val quarantineCount: Int,
        val attempts: Int,
        val changes: List<OperationChangeSummary>,
    ) : OperationSyncResult

    data class RetryScheduled(
        val reason: String,
    ) : OperationSyncResult

    data class PausedAuth(
        val reason: String,
    ) : OperationSyncResult

    data class RebootstrapRequired(
        val reason: String,
    ) : OperationSyncResult

    data class Quarantined(
        val reason: String,
    ) : OperationSyncResult

    data class StoragePressure(
        val reason: String,
    ) : OperationSyncResult

    data object AlreadyRunning : OperationSyncResult
}

internal class OperationSyncEngine(
    private val store: AppSyncOperationStore,
    private val remote: AppSyncJournalRemote,
    private val domainState: SyncDomainStateAdapter,
    private val reducer: OperationReducer = OperationReducer(),
    private val nowMillis: () -> Long,
    private val ownerId: () -> String,
    private val maxAttempts: Int = 3,
    private val leaseDurationMillis: Long = 15 * 60 * 1_000L,
    private val inactiveAfterMillis: Long = 90L * 24 * 60 * 60 * 1_000,
    private val bulkDeleteGuard: BulkDeleteGuard = BulkDeleteGuard(
        authorizationLookup = store::loadBulkDeleteAuthorization,
    ),
) {
    private val processMutex = Mutex()
    private val compaction = CompactionCoordinator(store, nowMillis, inactiveAfterMillis)

    init {
        require(maxAttempts > 0) { "Sync attempts must be positive" }
        require(leaseDurationMillis > 0L) { "Lease duration must be positive" }
    }

    suspend fun synchronize(
        accountBinding: SyncAccountBinding,
        formHash: FormHash?,
        forceDiscovery: Boolean = false,
    ): OperationSyncResult = processMutex.withLock {
        val installation = store.installation()
            ?: return@withLock OperationSyncResult.RebootstrapRequired("Installation is not initialized")
        if (installation.accountBinding != accountBinding ||
            installation.state != AppSyncInstallationState.Active
        ) {
            return@withLock OperationSyncResult.RebootstrapRequired(
                "Installation must complete pull-only bootstrap before publication",
            )
        }
        val heartbeat = installation.lastVerifiedHeartbeatAt
        if (heartbeat != null && nowMillis() - heartbeat > inactiveAfterMillis) {
            store.updateState(AppSyncInstallationState.RebootstrapRequired)
            return@withLock OperationSyncResult.RebootstrapRequired(
                "Device has been inactive for more than 90 days",
            )
        }
        if (formHash == null) {
            store.updateState(AppSyncInstallationState.PausedAuth)
            return@withLock OperationSyncResult.PausedAuth("Cached FormHash is unavailable")
        }

        val leaseOwner = ownerId()
        val startedAt = nowMillis()
        if (!store.acquireLease(leaseOwner, startedAt, leaseDurationMillis)) {
            return@withLock OperationSyncResult.AlreadyRunning
        }
        try {
            try {
                runAttempts(accountBinding, formHash, forceDiscovery)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                OperationSyncResult.RetryScheduled(
                    "Unexpected sync provider failure (${error::class.simpleName ?: "unknown"})",
                )
            }
        } finally {
            store.releaseLease(leaseOwner)
        }
    }

    private suspend fun runAttempts(
        accountBinding: SyncAccountBinding,
        formHash: FormHash,
        forceDiscovery: Boolean,
    ): OperationSyncResult {
        var totalApplied = 0
        var totalAcknowledged = 0
        var totalQuarantined = 0
        val receivedOperations = linkedMapOf<SyncOperationId, SyncOperation>()
        val uploadedOperations = linkedMapOf<SyncOperationId, SyncOperation>()

        repeat(maxAttempts) { attemptIndex ->
            val cloud = when (
                val result = remote.loadJournals(
                    accountBinding,
                    forceDiscovery = forceDiscovery && attemptIndex == 0,
                )
            ) {
                is AppSyncJournalLoadResult.Success -> result
                AppSyncJournalLoadResult.NotLoggedIn -> {
                    store.updateState(AppSyncInstallationState.PausedAuth)
                    return OperationSyncResult.PausedAuth("Yamibo login is unavailable")
                }
                is AppSyncJournalLoadResult.RetryableFailure ->
                    return OperationSyncResult.RetryScheduled(result.reason)
                is AppSyncJournalLoadResult.TerminalFailure -> {
                    store.updateState(AppSyncInstallationState.PausedProvider)
                    return OperationSyncResult.Quarantined(result.reason)
                }
            }
            cloud.checkpoints.forEach { checkpoint ->
                val envelope = checkpoint.envelope
                store.saveVerifiedCheckpoint(
                    AppSyncVerifiedCheckpoint(
                        checkpointId = envelope.payload.checkpointId,
                        blogId = checkpoint.remoteId.toLongOrNull(),
                        coverage = envelope.payload.coverage,
                        payloadFingerprint = envelope.fingerprint,
                        createdAtEpochMillis = envelope.payload.createdAtEpochMillis,
                        verifiedAtEpochMillis = nowMillis(),
                    ),
                )
            }
            val loaded = cloud.journals

            val installation = requireNotNull(store.installation())
            val compactedCoverage = compaction.compactIfSafe(loaded)
            val ownJournal = loaded.singleOrNull {
                it.payload.deviceId == installation.deviceId &&
                    it.payload.deviceEpoch == installation.deviceEpoch
            }
            if (ownJournal != null && ownJournal.payload.writerNonce != installation.writerNonce) {
                store.rotateDeviceEpoch(accountBinding, AppSyncInstallationState.RebootstrapRequired)
                return OperationSyncResult.RebootstrapRequired(
                    "The device journal is owned by another restored installation",
                )
            }

            val localOutboxIds = store.allOutboxOperations()
                .mapTo(hashSetOf()) { it.first.operationId }
            val incoming = loaded
                .asSequence()
                .flatMap { it.payload.operations.asSequence() }
                .distinctBy { it.operationId }
                .filterNot { it.operationId in localOutboxIds || store.isApplied(it.operationId) }
                .toList()
            if (incoming.isNotEmpty()) {
                val guarded = bulkDeleteGuard.evaluate(incoming, domainState::entityCount)
                val reduced = reducer.reduce(domainState.currentState(), guarded.accepted)
                val reduction = reduced.copy(
                    quarantined = reduced.quarantined + guarded.quarantined,
                )
                store.applyRemoteReduction(
                    reduction,
                    nowMillis(),
                    domainState::applyWithinTransaction,
                )
                domainState.reconcileProjections()
                totalApplied += reduction.appliedOperations.size
                totalQuarantined += reduction.quarantined.size
                reduction.appliedOperations.forEach {
                    receivedOperations[it.operationId] = it
                }
            }

            val pending = store.pendingOperations()
            val checkpointAcknowledgements = store.verifiedCheckpoints()
                .map {
                    AppSyncCheckpointAcknowledgement(
                        checkpointId = it.checkpointId,
                        coverage = it.coverage,
                    )
                }
                .sortedBy { it.checkpointId }
            val journalMetadataChanged =
                ownJournal == null ||
                    ownJournal.payload.checkpointAcknowledgements != checkpointAcknowledgements ||
                    compactedCoverage != null
            if (pending.isNotEmpty() || journalMetadataChanged) {
                val mergedOwnOperations = mergeOwnOperations(
                    existing = ownJournal?.payload?.operations.orEmpty().filterNot {
                        compactedCoverage?.includes(it) == true
                    },
                    pending = pending,
                )
                val payload = AppSyncJournalPayload(
                    accountBinding = accountBinding,
                    deviceId = installation.deviceId,
                    deviceEpoch = installation.deviceEpoch,
                    writerNonce = installation.writerNonce,
                    firstSequence = mergedOwnOperations.firstOrNull()?.sequence?.value ?: 0L,
                    lastSequence = mergedOwnOperations.lastOrNull()?.sequence?.value ?: 0L,
                    operations = mergedOwnOperations,
                    observed = store.causalContext(),
                    checkpointAcknowledgements = checkpointAcknowledgements,
                    heartbeatAtEpochMillis = nowMillis(),
                )
                val pendingIds = pending.mapTo(linkedSetOf()) { it.operationId }
                store.markPublishedUnverified(pendingIds)
                when (
                    val published = remote.publishOwnJournal(
                        payload,
                        expectedFingerprint = ownJournal?.fingerprint,
                        formHash = formHash,
                    )
                ) {
                    is AppSyncJournalPublishResult.Verified -> {
                        val verifiedIds = published.journal.payload.operations
                            .mapTo(hashSetOf()) { it.operationId }
                        val acknowledged = pendingIds.intersect(verifiedIds)
                        if (acknowledged != pendingIds) {
                            return OperationSyncResult.RetryScheduled(
                                "Authoritative reload omitted expected operation ids",
                            )
                        }
                        store.markAcknowledged(acknowledged, nowMillis())
                        store.updateVerifiedHeartbeat(
                            atEpochMillis = published.journal.payload.heartbeatAtEpochMillis,
                            journalBlogId = published.journal.remoteId.toLongOrNull(),
                        )
                        totalAcknowledged += acknowledged.size
                        pending.filter { it.operationId in acknowledged }.forEach {
                            uploadedOperations[it.operationId] = it
                        }
                    }
                    is AppSyncJournalPublishResult.Unknown ->
                        return OperationSyncResult.RetryScheduled(published.reason)
                    is AppSyncJournalPublishResult.Conflict -> {
                        if (attemptIndex == maxAttempts - 1) {
                            return OperationSyncResult.RetryScheduled(published.reason)
                        }
                        return@repeat
                    }
                    AppSyncJournalPublishResult.FormExpired -> {
                        store.updateState(AppSyncInstallationState.PausedAuth)
                        return OperationSyncResult.PausedAuth("Cached FormHash expired")
                    }
                    is AppSyncJournalPublishResult.StoragePressure -> {
                        store.updateState(AppSyncInstallationState.PausedProvider)
                        return OperationSyncResult.StoragePressure(
                            "Journal requires ${published.encodedChars} chars; " +
                                "safe provider limit is ${published.limitChars}",
                        )
                    }
                    is AppSyncJournalPublishResult.TerminalFailure -> {
                        store.updateState(AppSyncInstallationState.PausedProvider)
                        return OperationSyncResult.Quarantined(published.reason)
                    }
                }
            }

            val confirmPull = when (val result = remote.loadJournals(accountBinding, false)) {
                is AppSyncJournalLoadResult.Success -> result.journals
                AppSyncJournalLoadResult.NotLoggedIn ->
                    return OperationSyncResult.PausedAuth("Login expired during verification pull")
                is AppSyncJournalLoadResult.RetryableFailure ->
                    return OperationSyncResult.RetryScheduled(result.reason)
                is AppSyncJournalLoadResult.TerminalFailure ->
                    return OperationSyncResult.Quarantined(result.reason)
            }
            val unseenAfterPublish = confirmPull
                .asSequence()
                .flatMap { it.payload.operations.asSequence() }
                .any { it.operationId !in localOutboxIds && !store.isApplied(it.operationId) }
            if (!unseenAfterPublish && store.pendingOperations().isEmpty()) {
                return OperationSyncResult.Converged(
                    appliedRemoteCount = totalApplied,
                    acknowledgedLocalCount = totalAcknowledged,
                    quarantineCount = totalQuarantined,
                    attempts = attemptIndex + 1,
                    changes = summarizeWinningOperations(
                        received = receivedOperations.values,
                        uploaded = uploadedOperations.values,
                        state = domainState.currentState(),
                    ),
                )
            }
        }

        return OperationSyncResult.RetryScheduled("Sync did not reach a fixed point")
    }

    private fun mergeOwnOperations(
        existing: List<SyncOperation>,
        pending: List<SyncOperation>,
    ): List<SyncOperation> {
        val merged = (existing + pending)
            .distinctBy { it.operationId }
            .sortedBy { it.sequence.value }
        merged.zipWithNext().forEach { (left, right) ->
            require(right.sequence.value == left.sequence.value + 1L) {
                "Own journal sequence contains an unsafe gap"
            }
        }
        return merged
    }
}

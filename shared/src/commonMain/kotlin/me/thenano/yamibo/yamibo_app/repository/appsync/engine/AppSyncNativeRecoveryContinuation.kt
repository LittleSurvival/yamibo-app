package me.thenano.yamibo.yamibo_app.repository.appsync.engine

import io.github.littlesurvival.dto.value.FormHash
import kotlinx.coroutines.CancellationException
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncInstallationState
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncRecoveryPhase
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.*
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.*
import me.thenano.yamibo.yamibo_app.store.appsync.*

internal object AppSyncV3FeatureFlagKeys {
    const val SANITIZED_V2_FALLBACK = "appSyncSanitizedV2FallbackEnabled"
    const val WRITER = "appSyncV3WriterEnabled"
    const val READER_READY = "appSyncV3ReaderReady"
    const val BENCHMARKS_APPROVED = "appSyncV3BenchmarksApproved"
}

/** Service adapter for native journal creation and frozen recovery. The engine owns the run lease.
 * This does not enqueue work. Committed activation stays readable
 * when rollout is disabled; every remote write still independently checks the cohort gate.
 */
internal class AppSyncNativeRecoveryContinuation(
    private val provider: AppSyncBlogProvider,
    private val operations: AppSyncOperationStore,
    private val recovery: SqlDelightAppSyncRecoveryStore,
    private val remoteBlogs: AppSyncRemoteBlogStore,
    private val activator: AppSyncCanonicalCheckpointActivator,
    private val nowMillis: () -> Long,
    private val canWrite: () -> Boolean = { false },
    private val journalStarter: AppSyncNativeJournalStarter? = null,
    private val legacyStarter: AppSyncLegacyMigrationStarter? = null,
    private val canAttemptMigration: () -> Boolean = canWrite,
    private val preferSanitizedV2: () -> Boolean = { false },
    private val canWriteSanitizedV2: () -> Boolean = { false },
    private val sanitizedV2Starter: AppSyncNativeJournalStarter? = null,
) : AppSyncCanonicalRecoveryContinuation {
    override fun hasPending(account: SyncAccountBinding): Boolean = recovery.recoverySession(account)?.let {
        it.phase != AppSyncRecoveryPhase.Completed && recovery.usesNativeTransport(it.sessionId)
    } == true

    override fun requiresAuthoritativeDiscovery(): Boolean = preferSanitizedV2() || legacyStarter != null && canAttemptMigration()

    override suspend fun resumeLegacy(account: SyncAccountBinding, formHash: FormHash,
        cloud: AppSyncJournalLoadResult.Success): OperationSyncResult? {
        val starting = !hasPending(account)
        if (starting && (legacyStarter == null || !canWrite() || cloud.requiresCanonicalProcessing || cloud.verifiedLegacyCheckpoints.isEmpty())) return null
        if (!starting && cloud.verifiedCanonicalCheckpoints.isNotEmpty()) return null
        return try {
            if (!starting && recovery.legacyMigrationSource(requireNotNull(recovery.recoverySession(account)).sessionId) == null) return null
            val pending = operations.pendingOperations().filter { it.accountBinding == account }.map { it.operationId.value }.toSet()
            if (starting && requireNotNull(legacyStarter).start(account, cloud).isFailure)
                return OperationSyncResult.PausedProvider("Legacy migration could not be prepared from verified cloud history")
            val session = requireNotNull(recovery.recoverySession(account))
            val source = requireNotNull(recovery.legacyMigrationSource(session.sessionId))
            val frozen = recovery.nativePayload(session.sessionId)
            val checkpoint = (AppSyncV3DocumentCodec().discover(frozen.body, account.value, AppSyncV3PayloadKind.Checkpoint)
                as? AppSyncV3DocumentRead.Checkpoint)?.document
            requireNotNull(checkpoint)
            // The only native discovery allowed before the first index commit is our exact
            // frozen checkpoint. Never reinterpret unrelated native state as legacy input.
            require(cloud.canonicalDocuments.all { (it.document as? AppSyncV3DocumentRead.Checkpoint)?.document == checkpoint })
            require(cloud.verifiedLegacyCheckpoints.any {
                it.blogId == source.blogId && it.fingerprint == source.fingerprint && it.indexFingerprint == source.indexFingerprint &&
                    it.read().payload.checkpointId == source.checkpointId
            })
            val current = AppSyncLegacyCloudMigration().prepare(account, requireNotNull(operations.installation()),
                cloud.copy(canonicalDocuments = emptyList()), emptyList(), checkpoint.checkpointId, checkpoint.createdAtEpochMillis)
            require(current is AppSyncLegacyCloudMigrationResult.Ready)
            require(current.checkpoint.coverage.all { (replica, sequence) -> (checkpoint.coverage[replica] ?: 0) >= sequence })
            val merged = AppSyncCanonicalPendingMerge().prepare(checkpoint, cloud.journals.flatMap { it.payload.operations },
                checkpoint.checkpointId, checkpoint.createdAtEpochMillis)
            require(merged is AppSyncCanonicalPendingMergeResult.Ready && merged.checkpoint == checkpoint)
            if (!session.indexCommitted && !canWrite()) {
                block(account, "native-compatibility")
                return OperationSyncResult.PausedProvider("Native recovery requires compatible readers and approved rollout")
            }
            val selection = AppSyncBlogClassSelection.Existing(requireNotNull(remoteBlogs.loadClassId(account)))
            val publisher = AppSyncV3SegmentPublisher(provider, recovery, nowMillis, canWrite = { canWrite() },
                discover = AppSyncV3ArtifactReconciler(provider, selection.classId)::discover)
            val committer = AppSyncV3IndexCommitter(provider, recovery, publisher, nowMillis, { canWrite() })
            val coordinator = AppSyncV3CommitCoordinator(committer, recovery, nowMillis, activator,
                canRun = { recovery.session(session.sessionId)?.indexCommitted == true || canWrite() })
            when (val result = coordinator.commit(session.sessionId, frozen.body, frozen.identity, selection, formHash)) {
                is AppSyncSegmentedJournalCommitResult.Verified -> OperationSyncResult.Converged(0,
                    (pending - operations.pendingOperations().map { it.operationId.value }.toSet()).size,
                    0, 1, emptyList())
                AppSyncSegmentedJournalCommitResult.FormExpired -> {
                    operations.updateState(AppSyncInstallationState.PausedAuth)
                    OperationSyncResult.PausedAuth("Native recovery authentication expired")
                }
                is AppSyncSegmentedJournalCommitResult.Retryable -> OperationSyncResult.RetryScheduled(result.reason)
                is AppSyncSegmentedJournalCommitResult.Conflict -> OperationSyncResult.RetryScheduled(result.reason)
                is AppSyncSegmentedJournalCommitResult.Terminal -> {
                    block(account, if (!canWrite()) "native-compatibility" else "native-recovery-evidence")
                    OperationSyncResult.PausedProvider(result.reason)
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) {
            block(account, "native-migration-evidence")
            OperationSyncResult.PausedProvider("Legacy migration evidence could not be reconciled")
        }
    }

    override fun preflight(account: SyncAccountBinding): OperationSyncResult? {
        if (!hasPending(account)) return null
        val session = requireNotNull(recovery.recoverySession(account))
        if (session.phase == AppSyncRecoveryPhase.NeedsAttention)
            return OperationSyncResult.PausedProvider("Native recovery requires explicit resume")
        if ((session.nextRetryAtEpochMillis ?: 0L) > nowMillis())
            return OperationSyncResult.RetryScheduled("Persisted native retry deadline has not arrived")
        return null
    }

    override fun cloudFailure(account: SyncAccountBinding, retryable: Boolean) {
        if (!hasPending(account)) return
        if (retryable) AppSyncRecoveryAttempts(recovery, nowMillis).recordFailure(
            requireNotNull(recovery.recoverySession(account)).sessionId, AppSyncRecoveryFailureCategory.Network)
        else block(account)
    }

    fun block(account: SyncAccountBinding, category: String = "native-cloud-validation") {
        val session = recovery.recoverySession(account) ?: return
        if (!hasPending(account) || session.phase == AppSyncRecoveryPhase.NeedsAttention) return
        recovery.transition(session.sessionId, session.phase, AppSyncRecoveryPhase.NeedsAttention, nowMillis(),
            lastErrorCategory = category)
    }

    override suspend fun resume(account: SyncAccountBinding, formHash: FormHash,
        cloud: AppSyncCanonicalCloudPlan.Ready): OperationSyncResult? {
        val starting = !hasPending(account)
        val startFallback = starting && preferSanitizedV2()
        val starter = if (startFallback) sanitizedV2Starter else journalStarter
        if (starting && startFallback && (!canWriteSanitizedV2() || starter == null))
            return OperationSyncResult.PausedProvider("Sanitized v2 publication requires compatible readers and configured recovery")
        if (starting && !startFallback && (!canWrite() || starter == null)) return null
        return try {
            val installation = requireNotNull(operations.installation())
            require(installation.accountBinding == account)
            // A committed activation performs no remote requests. Its persisted class link is
            // still required so a later phase regression cannot accidentally invent a class.
            val selection = AppSyncBlogClassSelection.Existing(requireNotNull(remoteBlogs.loadClassId(account)))
            val pending = operations.pendingOperations().filter { it.accountBinding == account }.map { it.operationId.value }.toSet()
            val remoteIds = (cloud.legacyOperations.filter {
                it.deviceId != installation.deviceId || it.deviceEpoch != installation.deviceEpoch
            }.map { it.operationId } + cloud.canonicalOperations.operations.filter {
                it.deviceId != installation.deviceId.value || it.deviceEpoch != installation.deviceEpoch.value
            }.map {
                SyncOperation.idFor(SyncDeviceId(it.deviceId), SyncDeviceEpoch(it.deviceEpoch), SyncSequence(it.sequence))
            }).distinct().filterNot(operations::isApplied)
            if (starting && requireNotNull(starter).start(account, cloud).isFailure)
                return OperationSyncResult.PausedProvider("Native journal could not be prepared from verified cloud history")
            val session = requireNotNull(recovery.recoverySession(account))
            if (session.phase == AppSyncRecoveryPhase.NeedsAttention)
                return OperationSyncResult.PausedProvider("Native recovery requires explicit resume")
            val fallback = recovery.usesSanitizedV2Transport(session.sessionId)
            val canPublish = if (fallback) canWriteSanitizedV2 else canWrite
            if (!session.indexCommitted && !canPublish()) {
                block(account, "native-compatibility")
                return OperationSyncResult.PausedProvider("Native recovery requires compatible readers and approved rollout")
            }
            val payload = recovery.nativePayload(session.sessionId)
            val coveredAcknowledged = if (starting) pending - operations.pendingOperations().map { it.operationId.value }.toSet() else emptySet()
            val result = if (fallback) {
                val publisher = AppSyncSanitizedV2SegmentPublisher(provider, recovery, nowMillis, canWrite = { canPublish() },
                    discover = AppSyncV3ArtifactReconciler(provider, selection.classId)::discover)
                val committer = AppSyncSanitizedV2IndexCommitter(provider, recovery, publisher, nowMillis, { canPublish() })
                AppSyncSanitizedV2CommitCoordinator(committer, recovery, activator, nowMillis, { canPublish() })
                    .commit(session.sessionId, selection, formHash, cloud)
            } else {
                val publisher = AppSyncV3SegmentPublisher(provider, recovery, nowMillis, canWrite = { canPublish() },
                    discover = AppSyncV3ArtifactReconciler(provider, selection.classId)::discover)
                val committer = AppSyncV3IndexCommitter(provider, recovery, publisher, nowMillis, { canPublish() })
                AppSyncV3CommitCoordinator(committer, recovery, nowMillis, activator,
                    canRun = { recovery.session(session.sessionId)?.indexCommitted == true || canPublish() })
                    .commit(session.sessionId, payload.body, payload.identity, selection, formHash, cloud)
            }
            when (result) {
                is AppSyncSegmentedJournalCommitResult.Verified -> OperationSyncResult.Converged(
                    appliedRemoteCount = remoteIds.count(operations::isApplied),
                    acknowledgedLocalCount = (result.acknowledgedOperationIds + coveredAcknowledged +
                        (pending - operations.pendingOperations().map { it.operationId.value }.toSet())).count { it in pending },
                    quarantineCount = 0, attempts = 1, changes = emptyList())
                AppSyncSegmentedJournalCommitResult.FormExpired -> {
                    operations.updateState(AppSyncInstallationState.PausedAuth)
                    OperationSyncResult.PausedAuth("Native recovery authentication expired")
                }
                is AppSyncSegmentedJournalCommitResult.Retryable -> OperationSyncResult.RetryScheduled(result.reason)
                is AppSyncSegmentedJournalCommitResult.Conflict -> OperationSyncResult.RetryScheduled(result.reason)
                is AppSyncSegmentedJournalCommitResult.Terminal -> {
                    block(account, if (!canPublish()) "native-compatibility" else "native-recovery-evidence")
                    OperationSyncResult.PausedProvider(result.reason)
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) {
            block(account, "native-recovery-evidence")
            OperationSyncResult.PausedProvider("Native recovery evidence could not be loaded")
        }
    }
}

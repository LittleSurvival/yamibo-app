package me.thenano.yamibo.yamibo_app.repository.appsync.engine

import io.github.littlesurvival.dto.value.FormHash
import kotlinx.coroutines.CancellationException
import me.thenano.yamibo.yamibo_app.repository.appsync.domain.stableAppSyncFingerprint
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncJournalRetirementIntent
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncJournalRetirementStage
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncAccountBinding
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncReplicaKey
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.resolvedPublishedThroughSequence
import me.thenano.yamibo.yamibo_app.store.appsync.AppSyncOperationStore

internal sealed interface AppSyncJournalRetirementMaintenanceResult {
    data object NotDue : AppSyncJournalRetirementMaintenanceResult
    data class Observed(val journalCount: Int) : AppSyncJournalRetirementMaintenanceResult
    data class Candidate(val count: Int) : AppSyncJournalRetirementMaintenanceResult
    data class Blocked(val candidateCount: Int, val reason: String) :
        AppSyncJournalRetirementMaintenanceResult
    data class Pending(val stage: AppSyncJournalRetirementStage) :
        AppSyncJournalRetirementMaintenanceResult
    data object Completed : AppSyncJournalRetirementMaintenanceResult
    data object PausedAuth : AppSyncJournalRetirementMaintenanceResult
    data class RetryableFailure(val reason: String) :
        AppSyncJournalRetirementMaintenanceResult
    data class TerminalFailure(val reason: String) :
        AppSyncJournalRetirementMaintenanceResult
    data object AlreadyRunning : AppSyncJournalRetirementMaintenanceResult
}

internal class JournalRetirementCoordinator(
    private val store: AppSyncOperationStore,
    private val remote: AppSyncJournalRemote,
    private val nowMillis: () -> Long,
    private val ownerId: () -> String,
    private val evaluator: JournalRetirementProofEvaluator = JournalRetirementProofEvaluator(),
    private val maintenanceIntervalMillis: Long = 24L * 60 * 60 * 1_000,
    private val maximumObservationGapMillis: Long = 180L * 24 * 60 * 60 * 1_000,
    private val leaseDurationMillis: Long = 15L * 60 * 1_000,
) {
    suspend fun maintain(
        accountBinding: SyncAccountBinding,
        formHash: FormHash,
        force: Boolean,
        allowDelete: Boolean,
    ): AppSyncJournalRetirementMaintenanceResult {
        val installation = store.installation()
            ?: return AppSyncJournalRetirementMaintenanceResult.TerminalFailure(
                "Installation is not initialized",
            )
        val now = nowMillis()
        if (!force &&
            installation.lastFullDiscoveryAt != null &&
            now >= installation.lastFullDiscoveryAt &&
            now - installation.lastFullDiscoveryAt < maintenanceIntervalMillis
        ) {
            return AppSyncJournalRetirementMaintenanceResult.NotDue
        }
        val owner = "journal-retirement:${ownerId()}"
        if (!store.acquireLease(owner, now, leaseDurationMillis)) {
            return AppSyncJournalRetirementMaintenanceResult.AlreadyRunning
        }
        try {
            val cloud = when (val loaded = remote.loadJournals(accountBinding, true)) {
                is AppSyncJournalLoadResult.Success -> loaded
                AppSyncJournalLoadResult.NotLoggedIn ->
                    return AppSyncJournalRetirementMaintenanceResult.PausedAuth
                is AppSyncJournalLoadResult.RetryableFailure ->
                    return AppSyncJournalRetirementMaintenanceResult.RetryableFailure(loaded.reason)
                is AppSyncJournalLoadResult.TerminalFailure ->
                    return AppSyncJournalRetirementMaintenanceResult.TerminalFailure(loaded.reason)
            }
            store.updateDiscoveryTime(now)
            cloud.journals.forEach { journal ->
                val publishedThrough = journal.payload.resolvedPublishedThroughSequence()
                    ?: return@forEach
                val blogId = journal.remoteId.toLongOrNull() ?: return@forEach
                store.recordReplicaObservation(
                    accountBinding = accountBinding,
                    replicaKey = SyncReplicaKey(
                        journal.payload.deviceId,
                        journal.payload.deviceEpoch,
                    ).stableKey,
                    sourceBlogId = blogId,
                    fingerprint = journal.fingerprint,
                    publishedThroughSequence = publishedThrough,
                    observedAtEpochMillis = now,
                    maximumObservationGapMillis = maximumObservationGapMillis,
                )
            }
            val ownReplica = SyncReplicaKey(installation.deviceId, installation.deviceEpoch)
            val evaluation = evaluator.evaluate(
                nowEpochMillis = now,
                ownReplicaKey = ownReplica,
                journals = cloud.journals,
                checkpoints = cloud.checkpoints,
                observations = store.replicaObservations(accountBinding),
                indexedReplicaKeys = cloud.indexedReplicaKeys,
                discoveryIssues = cloud.retirementDiscoveryIssues,
            )
            when (evaluation) {
                AppSyncJournalRetirementEvaluation.NotNeeded ->
                    return AppSyncJournalRetirementMaintenanceResult.Observed(
                        cloud.journals.size,
                    )
                is AppSyncJournalRetirementEvaluation.Blocked ->
                    return AppSyncJournalRetirementMaintenanceResult.Blocked(
                        evaluation.candidateCount,
                        evaluation.reason,
                    )
                is AppSyncJournalRetirementEvaluation.Eligible -> {
                    if (!allowDelete) {
                        return AppSyncJournalRetirementMaintenanceResult.Candidate(1)
                    }
                    return advance(evaluation.proof, accountBinding, formHash, now)
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return AppSyncJournalRetirementMaintenanceResult.RetryableFailure(
                error.message ?: error::class.simpleName ?: "Unknown retirement failure",
            )
        } finally {
            store.releaseLease(owner)
        }
    }

    private suspend fun advance(
        proof: AppSyncJournalRetirementProof,
        accountBinding: SyncAccountBinding,
        formHash: FormHash,
        now: Long,
    ): AppSyncJournalRetirementMaintenanceResult {
        val replicaKey = proof.observation.replicaKey
        var intent = store.retirementIntents(accountBinding)
            .firstOrNull { it.replicaKey == replicaKey }
        if (intent == null) {
            intent = AppSyncJournalRetirementIntent(
                accountBinding = accountBinding,
                replicaKey = replicaKey,
                sourceBlogId = proof.observation.sourceBlogId,
                fingerprint = proof.observation.fingerprint,
                publishedThroughSequence = proof.publishedThroughSequence,
                checkpointId = proof.checkpoint.envelope.payload.checkpointId,
                checkpointFingerprint = proof.checkpoint.envelope.fingerprint,
                checkpointVectorHash = stableHash(
                    proof.checkpoint.envelope.payload.coverage.asStableMap().entries.map {
                        "${it.key}=${it.value}"
                    },
                ),
                activeSetHash = stableHash(proof.activeReplicaKeys.sorted()),
                stage = AppSyncJournalRetirementStage.IntentRecorded,
                attempts = 0,
                lastResultCode = null,
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
                completedAtEpochMillis = null,
            )
            store.saveRetirementIntent(intent)
        }
        if (!intent.matches(proof)) {
            return AppSyncJournalRetirementMaintenanceResult.Blocked(
                1,
                "退休證據已改變，必須重新累積並驗證",
            )
        }
        return when (intent.stage) {
            AppSyncJournalRetirementStage.IntentRecorded -> {
                when (val result = remote.publishRetirementIndex(intent, formHash)) {
                    AppSyncJournalRetirementRemoteResult.Verified -> {
                        store.transitionRetirementIntent(
                            accountBinding,
                            replicaKey,
                            AppSyncJournalRetirementStage.IntentRecorded,
                            AppSyncJournalRetirementStage.IndexRetirementPublished,
                            "INDEX_VERIFIED",
                            now,
                            incrementAttempts = true,
                        )
                        AppSyncJournalRetirementMaintenanceResult.Pending(
                            AppSyncJournalRetirementStage.IndexRetirementPublished,
                        )
                    }
                    else -> remoteFailure(
                        result,
                        accountBinding,
                        replicaKey,
                        AppSyncJournalRetirementStage.IntentRecorded,
                        now,
                    )
                }
            }
            AppSyncJournalRetirementStage.IndexRetirementPublished -> {
                store.transitionRetirementIntent(
                    accountBinding,
                    replicaKey,
                    AppSyncJournalRetirementStage.IndexRetirementPublished,
                    AppSyncJournalRetirementStage.DeleteRequested,
                    "DELETE_READY",
                    now,
                )
                AppSyncJournalRetirementMaintenanceResult.Pending(
                    AppSyncJournalRetirementStage.DeleteRequested,
                )
            }
            AppSyncJournalRetirementStage.DeleteRequested -> {
                when (val result = remote.deleteRetiredJournal(intent, formHash)) {
                    AppSyncJournalRetirementRemoteResult.Verified -> {
                        store.transitionRetirementIntent(
                            accountBinding,
                            replicaKey,
                            AppSyncJournalRetirementStage.DeleteRequested,
                            AppSyncJournalRetirementStage.Completed,
                            "DELETE_VERIFIED",
                            now,
                            incrementAttempts = true,
                        )
                        AppSyncJournalRetirementMaintenanceResult.Completed
                    }
                    else -> remoteFailure(
                        result,
                        accountBinding,
                        replicaKey,
                        AppSyncJournalRetirementStage.DeleteRequested,
                        now,
                    )
                }
            }
            AppSyncJournalRetirementStage.Completed,
            AppSyncJournalRetirementStage.Absorbed,
            -> AppSyncJournalRetirementMaintenanceResult.Completed
            AppSyncJournalRetirementStage.Blocked ->
                AppSyncJournalRetirementMaintenanceResult.Blocked(
                    1,
                    intent.lastResultCode ?: "退休 intent 已隔離",
                )
        }
    }

    private fun remoteFailure(
        result: AppSyncJournalRetirementRemoteResult,
        accountBinding: SyncAccountBinding,
        replicaKey: String,
        expectedStage: AppSyncJournalRetirementStage,
        now: Long,
    ): AppSyncJournalRetirementMaintenanceResult = when (result) {
        AppSyncJournalRetirementRemoteResult.Verified ->
            error("Verified retirement result must be handled by the caller")
        AppSyncJournalRetirementRemoteResult.FormExpired ->
            AppSyncJournalRetirementMaintenanceResult.PausedAuth
        is AppSyncJournalRetirementRemoteResult.RetryableFailure -> {
            store.transitionRetirementIntent(
                accountBinding,
                replicaKey,
                expectedStage,
                expectedStage,
                "RETRYABLE",
                now,
                incrementAttempts = true,
            )
            AppSyncJournalRetirementMaintenanceResult.RetryableFailure(result.reason)
        }
        is AppSyncJournalRetirementRemoteResult.TerminalFailure -> {
            store.transitionRetirementIntent(
                accountBinding,
                replicaKey,
                expectedStage,
                AppSyncJournalRetirementStage.Blocked,
                "TERMINAL",
                now,
                incrementAttempts = true,
            )
            AppSyncJournalRetirementMaintenanceResult.TerminalFailure(result.reason)
        }
    }

    private fun AppSyncJournalRetirementIntent.matches(
        proof: AppSyncJournalRetirementProof,
    ): Boolean =
        sourceBlogId == proof.observation.sourceBlogId &&
            fingerprint == proof.observation.fingerprint &&
            publishedThroughSequence == proof.publishedThroughSequence &&
            checkpointId == proof.checkpoint.envelope.payload.checkpointId &&
            checkpointFingerprint == proof.checkpoint.envelope.fingerprint &&
            checkpointVectorHash == stableHash(
                proof.checkpoint.envelope.payload.coverage.asStableMap().entries.map {
                    "${it.key}=${it.value}"
                },
            ) &&
            activeSetHash == stableHash(proof.activeReplicaKeys.sorted())

    private fun stableHash(values: List<String>): String =
        stableAppSyncFingerprint(values.sorted().joinToString(";"))
}

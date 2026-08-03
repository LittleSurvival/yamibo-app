package me.thenano.yamibo.yamibo_app.repository.appsync.engine

import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncReplicaObservation
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncCausalContext
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncReplicaKey
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.resolvedPublishedThroughSequence

internal data class AppSyncJournalRetirementProof(
    val candidate: LoadedAppSyncJournal,
    val observation: AppSyncReplicaObservation,
    val publishedThroughSequence: Long,
    val checkpoint: LoadedAppSyncCheckpoint,
    val activeReplicaKeys: Set<String>,
)

internal sealed interface AppSyncJournalRetirementEvaluation {
    data class Eligible(val proof: AppSyncJournalRetirementProof) :
        AppSyncJournalRetirementEvaluation

    data class Blocked(
        val candidateCount: Int,
        val reason: String,
    ) : AppSyncJournalRetirementEvaluation

    data object NotNeeded : AppSyncJournalRetirementEvaluation
}

internal class JournalRetirementProofEvaluator(
    private val inactiveAfterMillis: Long = 90L * 24 * 60 * 60 * 1_000,
) {
    fun evaluate(
        nowEpochMillis: Long,
        ownReplicaKey: SyncReplicaKey,
        journals: List<LoadedAppSyncJournal>,
        checkpoints: List<LoadedAppSyncCheckpoint>,
        observations: List<AppSyncReplicaObservation>,
        indexedReplicaKeys: Set<String> = emptySet(),
        discoveryIssues: List<String> = emptyList(),
    ): AppSyncJournalRetirementEvaluation {
        val journalByReplica = journals.associateBy { it.payload.replicaKey().stableKey }
        val observationByReplica = observations.associateBy { it.replicaKey }
        val candidates = observations
            .asSequence()
            .filter {
                nowEpochMillis >= it.lastObservedAtEpochMillis &&
                    nowEpochMillis - it.firstObservedUnchangedAtEpochMillis >=
                    inactiveAfterMillis
            }
            .mapNotNull { observation ->
                journalByReplica[observation.replicaKey]?.let { observation to it }
            }
            .filterNot { (_, journal) ->
                journal.payload.replicaKey() == ownReplicaKey
            }
            .sortedBy { (observation, _) -> observation.firstObservedUnchangedAtEpochMillis }
            .toList()
        if (candidates.isEmpty()) return AppSyncJournalRetirementEvaluation.NotNeeded
        if (discoveryIssues.isNotEmpty()) {
            return AppSyncJournalRetirementEvaluation.Blocked(
                candidates.size,
                "完整 discovery 尚有無法驗證的 AppSync 項目",
            )
        }

        val activeReplicaKeys = linkedSetOf<String>().apply {
            addAll(indexedReplicaKeys)
        }
        journals.forEach { journal ->
            val key = journal.payload.replicaKey().stableKey
            val observation = observationByReplica[key]
            val provenInactive = observation != null &&
                nowEpochMillis >= observation.lastObservedAtEpochMillis &&
                nowEpochMillis - observation.firstObservedUnchangedAtEpochMillis >=
                inactiveAfterMillis
            if (!provenInactive) activeReplicaKeys += key
        }
        observations.forEach { observation ->
            val provenInactive =
                nowEpochMillis >= observation.lastObservedAtEpochMillis &&
                    nowEpochMillis - observation.firstObservedUnchangedAtEpochMillis >=
                    inactiveAfterMillis
            if (!provenInactive) activeReplicaKeys += observation.replicaKey
        }
        if (ownReplicaKey.stableKey !in activeReplicaKeys) {
            return AppSyncJournalRetirementEvaluation.Blocked(
                candidates.size,
                "目前 replica 尚未形成已驗證的 active retirement actor",
            )
        }
        val activeJournals = activeReplicaKeys.mapNotNull(journalByReplica::get)
        if (activeJournals.size != activeReplicaKeys.size) {
            return AppSyncJournalRetirementEvaluation.Blocked(
                candidates.size,
                "活躍 replica discovery 不完整",
            )
        }

        for ((observation, candidate) in candidates) {
            val publishedThrough = candidate.payload.resolvedPublishedThroughSequence()
                ?: continue
            if (
                candidate.remoteId.toLongOrNull() != observation.sourceBlogId ||
                candidate.fingerprint != observation.fingerprint ||
                publishedThrough != observation.publishedThroughSequence
            ) {
                continue
            }
            val checkpoint = checkpoints
                .filter {
                    it.envelope.payload.coverage[
                        candidate.payload.replicaKey()
                    ] >= publishedThrough
                }
                .maxWithOrNull(
                    compareBy(
                        { it.envelope.payload.coverage.asStableMap().values.sum() },
                        { it.envelope.payload.createdAtEpochMillis },
                        { it.envelope.payload.checkpointId },
                    ),
                ) ?: continue
            val coverage = checkpoint.envelope.payload.coverage
            val allAcknowledged = activeJournals.all { active ->
                active.payload.observed.covers(coverage) &&
                    active.payload.checkpointAcknowledgements.any {
                        it.checkpointId == checkpoint.envelope.payload.checkpointId &&
                            it.coverage == coverage
                    }
            }
            if (!allAcknowledged) continue
            return AppSyncJournalRetirementEvaluation.Eligible(
                AppSyncJournalRetirementProof(
                    candidate = candidate,
                    observation = observation,
                    publishedThroughSequence = publishedThrough,
                    checkpoint = checkpoint,
                    activeReplicaKeys = activeReplicaKeys,
                ),
            )
        }
        return AppSyncJournalRetirementEvaluation.Blocked(
            candidates.size,
            "等待 checkpoint 完整覆蓋與所有活躍 replica 確認",
        )
    }

    private fun SyncCausalContext.covers(other: SyncCausalContext): Boolean {
        val mine = asStableMap()
        return other.asStableMap().all { (replica, sequence) ->
            (mine[replica] ?: 0L) >= sequence
        }
    }

    private fun me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncJournalPayload
        .replicaKey(): SyncReplicaKey = SyncReplicaKey(deviceId, deviceEpoch)
}

package me.thenano.yamibo.yamibo_app.repository.appsync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.serialization.json.Json
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncJournalRetirementEvaluation
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.JournalRetirementProofEvaluator
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.LoadedAppSyncCheckpoint
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.LoadedAppSyncJournal
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncReplicaObservation
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncAccountBinding
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncCausalContext
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncDeviceEpoch
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncDeviceId
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncReplicaKey
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncSequence
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncWriterNonce
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncCheckpointAcknowledgement
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncCheckpointEnvelopeCodec
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncCheckpointValidation
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncJournalPayload
import me.thenano.yamibo.yamibo_app.repository.backup.YamiboBackupFile

class JournalRetirementProofEvaluatorTest {
    private val evaluator = JournalRetirementProofEvaluator(inactiveAfterMillis = 100)
    private val candidateKey = SyncReplicaKey(
        SyncDeviceId("candidate"),
        SyncDeviceEpoch("old"),
    )
    private val activeKey = SyncReplicaKey(
        SyncDeviceId("active"),
        SyncDeviceEpoch("current"),
    )

    @Test
    fun coveredInactiveCandidateWithExactActiveAcknowledgementIsEligible() {
        val coverage = SyncCausalContext()
            .advance(candidateKey, SyncSequence(5))
            .advance(activeKey, SyncSequence(1))
        val checkpoint = checkpoint(coverage)
        val candidate = journal(candidateKey, 42, "candidate-fp", coverage, publishedThrough = 5)
        val active = journal(
            activeKey,
            43,
            "active-fp",
            coverage,
            publishedThrough = 1,
            acknowledgements = listOf(
                AppSyncCheckpointAcknowledgement("checkpoint", coverage),
            ),
        )

        val result = evaluator.evaluate(
            nowEpochMillis = 200,
            ownReplicaKey = activeKey,
            journals = listOf(candidate, active),
            checkpoints = listOf(checkpoint),
            observations = listOf(
                observation(candidateKey, 42, "candidate-fp", 5, first = 0, last = 200),
                observation(activeKey, 43, "active-fp", 1, first = 190, last = 200),
            ),
        )

        val eligible = assertIs<AppSyncJournalRetirementEvaluation.Eligible>(result)
        assertEquals(candidateKey.stableKey, eligible.proof.observation.replicaKey)
        assertEquals("checkpoint", eligible.proof.checkpoint.envelope.payload.checkpointId)
    }

    @Test
    fun missingActiveAcknowledgementFailsClosed() {
        val coverage = SyncCausalContext().advance(candidateKey, SyncSequence(5))
        val result = evaluator.evaluate(
            nowEpochMillis = 200,
            ownReplicaKey = activeKey,
            journals = listOf(
                journal(candidateKey, 42, "candidate-fp", coverage, 5),
                journal(activeKey, 43, "active-fp", coverage, 1),
            ),
            checkpoints = listOf(checkpoint(coverage)),
            observations = listOf(
                observation(candidateKey, 42, "candidate-fp", 5, 0, 200),
                observation(activeKey, 43, "active-fp", 1, 190, 200),
            ),
        )

        assertIs<AppSyncJournalRetirementEvaluation.Blocked>(result)
    }

    @Test
    fun durableActiveObservationMissingFromDiscoveryFailsClosed() {
        val coverage = SyncCausalContext().advance(candidateKey, SyncSequence(5))
        val result = evaluator.evaluate(
            nowEpochMillis = 200,
            ownReplicaKey = activeKey,
            journals = listOf(journal(candidateKey, 42, "candidate-fp", coverage, 5)),
            checkpoints = listOf(checkpoint(coverage)),
            observations = listOf(
                observation(candidateKey, 42, "candidate-fp", 5, 0, 200),
                observation(activeKey, 43, "active-fp", 1, 190, 200),
            ),
        )

        val blocked = assertIs<AppSyncJournalRetirementEvaluation.Blocked>(result)
        assertEquals("活躍 replica discovery 不完整", blocked.reason)
    }

    @Test
    fun indexOnlyActiveReplicaFailsClosed() {
        val coverage = SyncCausalContext().advance(candidateKey, SyncSequence(5))
        val result = evaluator.evaluate(
            nowEpochMillis = 200,
            ownReplicaKey = activeKey,
            journals = listOf(
                journal(candidateKey, 42, "candidate-fp", coverage, 5),
                journal(
                    activeKey,
                    43,
                    "active-fp",
                    coverage,
                    1,
                    listOf(AppSyncCheckpointAcknowledgement("checkpoint", coverage)),
                ),
            ),
            checkpoints = listOf(checkpoint(coverage)),
            observations = listOf(
                observation(candidateKey, 42, "candidate-fp", 5, 0, 200),
                observation(activeKey, 43, "active-fp", 1, 190, 200),
            ),
            indexedReplicaKeys = setOf(activeKey.stableKey, "missing:epoch"),
        )

        val blocked = assertIs<AppSyncJournalRetirementEvaluation.Blocked>(result)
        assertEquals("活躍 replica discovery 不完整", blocked.reason)
    }

    @Test
    fun unsupportedOrIncompleteDiscoveryFailsClosed() {
        val coverage = SyncCausalContext()
            .advance(candidateKey, SyncSequence(5))
            .advance(activeKey, SyncSequence(1))
        val result = evaluator.evaluate(
            nowEpochMillis = 200,
            ownReplicaKey = activeKey,
            journals = listOf(
                journal(candidateKey, 42, "candidate-fp", coverage, 5),
                journal(
                    activeKey,
                    43,
                    "active-fp",
                    coverage,
                    1,
                    listOf(AppSyncCheckpointAcknowledgement("checkpoint", coverage)),
                ),
            ),
            checkpoints = listOf(checkpoint(coverage)),
            observations = listOf(
                observation(candidateKey, 42, "candidate-fp", 5, 0, 200),
                observation(activeKey, 43, "active-fp", 1, 190, 200),
            ),
            discoveryIssues = listOf("unsupported schema"),
        )

        val blocked = assertIs<AppSyncJournalRetirementEvaluation.Blocked>(result)
        assertEquals("完整 discovery 尚有無法驗證的 AppSync 項目", blocked.reason)
    }

    @Test
    fun missingCheckpointOrChangedCandidateFailsClosed() {
        val coverage = SyncCausalContext().advance(candidateKey, SyncSequence(5))
        val observations = listOf(
            observation(candidateKey, 42, "old-fingerprint", 5, 0, 200),
            observation(activeKey, 43, "active-fp", 1, 190, 200),
        )
        val journals = listOf(
            journal(candidateKey, 42, "new-fingerprint", coverage, 6),
            journal(activeKey, 43, "active-fp", coverage, 1),
        )

        assertIs<AppSyncJournalRetirementEvaluation.Blocked>(
            evaluator.evaluate(200, activeKey, journals, emptyList(), observations),
        )
    }

    @Test
    fun ambiguousLegacyCandidateIsNotEligible() {
        val coverage = SyncCausalContext().advance(candidateKey, SyncSequence(5))
        val candidate = journal(
            candidateKey,
            42,
            "candidate-fp",
            coverage,
            publishedThrough = null,
        ).copy(
            payload = journal(
                candidateKey,
                42,
                "candidate-fp",
                coverage,
                publishedThrough = null,
            ).payload.copy(
                observed = coverage,
                lastSequence = 0,
            ),
        )
        val active = journal(
            activeKey,
            43,
            "active-fp",
            coverage,
            1,
            listOf(AppSyncCheckpointAcknowledgement("checkpoint", coverage)),
        )

        val result = evaluator.evaluate(
            200,
            activeKey,
            listOf(candidate, active),
            listOf(checkpoint(coverage)),
            listOf(
                observation(candidateKey, 42, "candidate-fp", 5, 0, 200),
                observation(activeKey, 43, "active-fp", 1, 190, 200),
            ),
        )

        assertIs<AppSyncJournalRetirementEvaluation.Blocked>(result)
    }

    private fun observation(
        key: SyncReplicaKey,
        blogId: Long,
        fingerprint: String,
        sequence: Long,
        first: Long,
        last: Long,
    ) = AppSyncReplicaObservation(
        accountBinding = ACCOUNT,
        replicaKey = key.stableKey,
        sourceBlogId = blogId,
        fingerprint = fingerprint,
        publishedThroughSequence = sequence,
        firstObservedUnchangedAtEpochMillis = first,
        lastObservedAtEpochMillis = last,
    )

    private fun journal(
        key: SyncReplicaKey,
        blogId: Long,
        fingerprint: String,
        observed: SyncCausalContext,
        publishedThrough: Long?,
        acknowledgements: List<AppSyncCheckpointAcknowledgement> = emptyList(),
    ) = LoadedAppSyncJournal(
        remoteId = blogId.toString(),
        fingerprint = fingerprint,
        payload = AppSyncJournalPayload(
            accountBinding = ACCOUNT,
            deviceId = key.deviceId,
            deviceEpoch = key.deviceEpoch,
            writerNonce = SyncWriterNonce("writer-${key.stableKey}"),
            firstSequence = 0,
            lastSequence = 0,
            operations = emptyList(),
            observed = observed,
            checkpointAcknowledgements = acknowledgements,
            heartbeatAtEpochMillis = 0,
            publishedThroughSequence = publishedThrough,
        ),
    )

    private fun checkpoint(coverage: SyncCausalContext): LoadedAppSyncCheckpoint {
        val codec = AppSyncCheckpointEnvelopeCodec(
            json = Json {
                encodeDefaults = true
                ignoreUnknownKeys = false
                explicitNulls = true
            },
        )
        val payload = codec.createPayload(
            checkpointId = "checkpoint",
            accountBinding = ACCOUNT,
            coverage = coverage,
            snapshot = YamiboBackupFile(appVersionCode = 1, createdAt = 1),
            tombstones = emptyList(),
            createdAtEpochMillis = 1,
        )
        val parsed = assertIs<AppSyncCheckpointValidation.Valid>(
            codec.validate(codec.encode(payload)),
        ).envelope
        return LoadedAppSyncCheckpoint("99", parsed)
    }

    private companion object {
        val ACCOUNT = SyncAccountBinding("account")
    }
}

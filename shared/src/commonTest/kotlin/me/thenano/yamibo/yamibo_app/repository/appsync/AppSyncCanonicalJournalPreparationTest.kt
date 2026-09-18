package me.thenano.yamibo.yamibo_app.repository.appsync

import kotlin.test.*
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.*
import me.thenano.yamibo.yamibo_app.repository.appsync.model.*
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.*
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.*
import me.thenano.yamibo.yamibo_app.repository.appsync.schema.*

class AppSyncCanonicalJournalPreparationTest {
    private val account = SyncAccountBinding("account")
    private val installation = AppSyncInstallation("db", account, SyncDeviceId("local"), SyncDeviceEpoch("epoch"),
        SyncWriterNonce("writer"), 10, AppSyncInstallationState.Active, null, null, null, false,
        AppSyncScheduleSettings(), 0, 0)
    private val base = AppSyncCanonicalCheckpoint("base", account.value, 1, emptyMap(), emptyList())
    private val codec = AppSyncV3DocumentCodec()
    private val preparation = AppSyncCanonicalJournalPreparation()
    private fun source(sequence: Long = 1): SyncOperation {
        val original = AppSyncSyntheticCorpus.create().journal.operations.first { it.domainId.value == "detail-note" }
        val seq = SyncSequence(sequence)
        return original.copy(accountBinding = account, deviceId = installation.deviceId, deviceEpoch = installation.deviceEpoch,
            sequence = seq, operationId = SyncOperation.idFor(installation.deviceId, installation.deviceEpoch, seq),
            causalContext = SyncCausalContext(if (sequence == 1L) emptyMap() else mapOf("local:epoch" to sequence - 1)),
            fields = original.fields + ("content" to "note $sequence"))
    }
    private fun local(vararg sources: SyncOperation) = assertIs<AppSyncCanonicalPendingMergeResult.Ready>(
        AppSyncCanonicalPendingMerge().prepare(base, sources.toList(), "local", 1)).checkpoint
    private fun prepare(state: AppSyncCanonicalCheckpoint, sources: List<SyncOperation>, existing: AppSyncV3DocumentRead.Journal? = null,
        acks: List<AppSyncVerifiedCanonicalCheckpoint> = emptyList()) = preparation.prepare(installation, state, existing, sources, acks, 10, "test")
    private fun read(ready: AppSyncCanonicalJournalPreparationResult.Ready) = assertIs<AppSyncV3DocumentRead.Journal>(
        codec.discover(ready.envelope, account.value, AppSyncV3PayloadKind.Journal))
    private fun reason(result: AppSyncCanonicalJournalPreparationResult) = assertIs<AppSyncCanonicalJournalPreparationResult.NeedsAttention>(result).reason
    private fun verified(checkpoint: AppSyncCanonicalCheckpoint): AppSyncVerifiedCanonicalCheckpoint {
        val index = AppSyncIndexEnvelopeCodec().encode(AppSyncIndexPayload(account, checkpoints = listOf(
            AppSyncIndexCheckpointReference(checkpoint.checkpointId, 42, AppSyncCanonicalCheckpointCodec().encode(checkpoint).sha256().hex())),
            updatedAtEpochMillis = 1))
        return assertNotNull(AppSyncVerifiedCanonicalCheckpoint.verify(account.value, 42, index, codec.encodeCheckpoint(checkpoint)))
    }

    @Test fun appendsAndReplaysWithoutDroppingExistingOperationsOrAcknowledgements() {
        val first = source(); val second = source(2)
        val start = assertIs<AppSyncCanonicalJournalPreparationResult.Ready>(prepare(local(first), listOf(first), acks = listOf(verified(base))))
        val next = assertIs<AppSyncCanonicalJournalPreparationResult.Ready>(prepare(local(first, second), listOf(second, first, second), read(start)))
        assertEquals(listOf(1L, 2L), next.journal.block.operations.map { it.sequence })
        assertEquals(setOf(first.operationId, second.operationId), next.sourceOperationIds)
        assertEquals(start.journal.acknowledgements, next.journal.acknowledgements)
        assertEquals(2L, next.journal.publishedThroughSequence)
        assertEquals(next.journal, read(next).document)
        assertEquals(next.fingerprint, read(next).metadata.canonicalFingerprint)
        val replay = assertIs<AppSyncCanonicalJournalPreparationResult.Ready>(prepare(local(first, second), listOf(second, first), read(next)))
        assertEquals(next.envelope, replay.envelope)
    }

    @Test fun invalidOwnersIdentityCollisionsAndMissingSequencesCannotProduceBodies() {
        val first = source(); val second = source(2)
        val state = local(first, second)
        assertEquals(AppSyncCanonicalJournalFailure.Account, reason(prepare(state, listOf(first.copy(accountBinding = SyncAccountBinding("other"))))))
        assertEquals(AppSyncCanonicalJournalFailure.SequenceGap, reason(prepare(state, listOf(second))))
        assertEquals(AppSyncCanonicalJournalFailure.Coverage, reason(prepare(state, listOf(first))))
        assertEquals(AppSyncCanonicalJournalFailure.OperationCollision, reason(prepare(state, listOf(first, first.copy(fields = first.fields + ("content" to "conflict"))))))
        val existing = read(assertIs<AppSyncCanonicalJournalPreparationResult.Ready>(prepare(local(first), listOf(first))))
        assertEquals(AppSyncCanonicalJournalFailure.Writer, reason(prepare(state, listOf(second), existing.copy(document = existing.document.copy(writerNonce = "clone")))))
        assertEquals(AppSyncCanonicalJournalFailure.InvalidDocument, reason(prepare(state, listOf(second), existing.copy(metadata = existing.metadata.copy(uncompressedLength = 1)))))
    }

    @Test fun excludedLegacySequencesAndUnappliedAcknowledgementsAreExplicitFailures() {
        val first = source()
        assertEquals(AppSyncCanonicalJournalFailure.SourceImport, reason(prepare(local(first), listOf(first.copy(domainId = SyncDomainId("unknown"))))))
        assertEquals(AppSyncCanonicalJournalFailure.SourceImport, reason(prepare(local(first), listOf(first.copy(kind = SyncOperationKind.Patch, fields = emptyMap())))))
        assertEquals(AppSyncCanonicalJournalFailure.Acknowledgement, reason(prepare(base, emptyList(), acks = listOf(verified(local(first))))))
        assertEquals(AppSyncCanonicalJournalFailure.Budget, reason(prepare(base, List(100001) { first })))
    }

    @Test fun sharedDeletionProofIsPublishedOnceAndConflictingProofsAreRejected() {
        val fields = mapOf(AppSyncBulkDeleteProofFields.SCOPE to "notes", AppSyncBulkDeleteProofFields.COUNT to "2",
            AppSyncBulkDeleteProofFields.EXPIRES_AT to (AppSyncSyntheticCorpus.TIMESTAMP + 1000).toString())
        val first = source().copy(kind = SyncOperationKind.Delete, origin = SyncOperationOrigin.UserAction, bulkDeleteAuthorizationId = "batch", fields = fields)
        val second = source(2).copy(kind = SyncOperationKind.Delete, origin = SyncOperationOrigin.UserAction, entityId = SyncEntityId("ThreadNormal|999|0"),
            bulkDeleteAuthorizationId = "batch", fields = fields)
        val state = base.copy(coverage = mapOf("local:epoch" to 2L))
        val ready = assertIs<AppSyncCanonicalJournalPreparationResult.Ready>(prepare(state, listOf(first, second)))
        assertEquals(1, ready.journal.block.authorizations.size)
        assertEquals(2L, ready.journal.block.authorizations.single().operationCount)
        assertTrue(ready.journal.block.operations.all { it.fields.isEmpty() && it.authorizationId == "batch" })
        assertEquals(AppSyncCanonicalJournalFailure.ProofCollision, reason(prepare(state,
            listOf(first, second.copy(fields = fields + (AppSyncBulkDeleteProofFields.SCOPE to "other"))))))
    }

    @Test fun nullablePublishedMetadataKeepsTheObservedPrefix() {
        val journal = AppSyncCanonicalJournal(AppSyncCanonicalOperationBlock(account.value, emptyList()), "local", "epoch", "writer",
            0, 0, mapOf("local:epoch" to 5L), emptyList(), 1, 3, 3, "test", null)
        val existing = assertIs<AppSyncV3DocumentRead.Journal>(codec.discover(codec.encodeJournal("local:epoch", journal), account.value, AppSyncV3PayloadKind.Journal))
        val ready = assertIs<AppSyncCanonicalJournalPreparationResult.Ready>(prepare(base.copy(coverage = mapOf("local:epoch" to 5L)), emptyList(), existing))
        assertEquals(5L, ready.journal.publishedThroughSequence)
    }

    @Test fun equalCoverageAcknowledgementsCannotHideCheckpointIdentityCollision() {
        assertEquals(AppSyncCanonicalJournalFailure.Acknowledgement, reason(prepare(base, emptyList(),
            acks = listOf(verified(base), verified(base.copy(createdAtEpochMillis = 2))))))
    }

    @Test fun metadataOnlyJournalPreservesPublishedWatermarkAndRequiresContiguousAppend() {
        val empty = AppSyncCanonicalJournal(AppSyncCanonicalOperationBlock(account.value, emptyList()), "local", "epoch", "writer",
            0, 0, mapOf("local:epoch" to 5L), emptyList(), 1, 3, 3, "test", 5)
        val existing = assertIs<AppSyncV3DocumentRead.Journal>(codec.discover(codec.encodeJournal("local:epoch", empty), account.value, AppSyncV3PayloadKind.Journal))
        val state = base.copy(coverage = mapOf("local:epoch" to 5L))
        val heartbeat = assertIs<AppSyncCanonicalJournalPreparationResult.Ready>(prepare(state, emptyList(), existing))
        assertEquals(0L, heartbeat.journal.lastSequence)
        assertEquals(5L, heartbeat.journal.publishedThroughSequence)
        assertEquals(AppSyncCanonicalJournalFailure.SequenceGap, reason(prepare(state, listOf(source()), existing)))
        assertEquals(AppSyncCanonicalJournalFailure.Writer, reason(preparation.prepare(installation.copy(nextSequence = 5),
            state, existing, emptyList(), emptyList(), 10, "test")))
        val appended = assertIs<AppSyncCanonicalJournalPreparationResult.Ready>(prepare(state.copy(coverage = mapOf("local:epoch" to 6L)), listOf(source(6)), existing))
        assertEquals(6L, appended.journal.firstSequence)
        assertEquals(AppSyncCanonicalJournalFailure.SequenceGap, reason(prepare(state.copy(coverage = mapOf("local:epoch" to 7L)), listOf(source(7)), existing)))
    }
}

package me.thenano.yamibo.yamibo_app.repository.appsync

import kotlin.test.*
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.*
import me.thenano.yamibo.yamibo_app.repository.backup.*
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.*
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.*
import me.thenano.yamibo.yamibo_app.repository.appsync.schema.*

class AppSyncSanitizedV2CheckpointPreparationTest {
    private val corpus by lazy { AppSyncSyntheticCorpus.create() }
    private fun checkpoint() = assertIs<AppSyncProjectionImportResult.Ready>(AppSyncCanonicalProjectionImporter().prepare(
        corpus.journal.accountBinding.value, "fallback-checkpoint", 100, corpus.journal.observed.asStableMap(), corpus.resolved)).checkpoint

    @Test fun allDomainCheckpointRoundTripsWithOriginalWinnersAndCoverage() {
        val checkpoint = checkpoint()
        val prepared = AppSyncSanitizedV2CheckpointPreparation { true }.prepare(checkpoint, corpus.snapshot)
        val ready = assertIs<AppSyncV2CheckpointPreparation.Ready>(prepared, prepared.toString())
        val read = assertIs<AppSyncCheckpointValidation.Valid>(AppSyncCheckpointEnvelopeCodec().validate(ready.envelope)).envelope
        assertEquals(ready.payload, read.payload)
        assertEquals(ready.fingerprint, read.fingerprint)
        assertEquals(19, read.payload.resolvedEntities.map { it.key.domainId }.distinct().size)
        assertEquals(checkpoint.coverage, read.payload.coverage.asStableMap())
        val reimported = assertIs<AppSyncProjectionImportResult.Ready>(AppSyncCanonicalProjectionImporter().prepare(
            checkpoint.accountBinding, checkpoint.checkpointId, checkpoint.createdAtEpochMillis,
            checkpoint.coverage, read.payload.resolvedEntities)).checkpoint
        assertEquals(checkpoint, reimported)
        assertTrue(AppSyncCanonicalSnapshotValidation.matches(checkpoint, read.snapshot))
        assertEquals(ready, AppSyncSanitizedV2CheckpointPreparation { true }.prepare(checkpoint, corpus.snapshot))
    }

    @Test fun ambiguousEventUsesPortableEvidenceAndOrdinaryEncoderRemainsGuarded() {
        val original = corpus.snapshot.favoriteUpdates.events.first()
        val identity = favoriteUpdateEventIdentity(original.targetType, original.targetId, original.authorId,
            original.mode, emptyList(), true, original.detectedAt, original.summary, original.title)
        val event = original.copy(syncId = identity.syncId, sourceFingerprint = identity.sourceFingerprint,
            sourceDiscriminator = identity.sourceDiscriminator, detailIds = emptyList(), ambiguous = true)
        val snapshot = YamiboBackupFile(appVersionCode = 1, createdAt = 100,
            favoriteUpdates = BackupFavoriteUpdates(events = listOf(event)))
        val draft = BackupSnapshotMigrationPlanner().plan(snapshot).single()
        val template = corpus.journal.operations.first { it.domainId == draft.domainId }
        val operation = template.copy(entityId = draft.entityId, fields = draft.fields)
        val entity = ResolvedSyncEntity(SyncEntityKey(operation.domainId, operation.entityId, operation.entityGeneration),
            fields = operation.fields.mapValues { ResolvedSyncField(it.value, operation) })
        val checkpoint = assertIs<AppSyncProjectionImportResult.Ready>(AppSyncCanonicalProjectionImporter().prepare(
            operation.accountBinding.value, "ambiguous", 100, corpus.journal.observed.asStableMap(), listOf(entity))).checkpoint
        val prepared = AppSyncSanitizedV2CheckpointPreparation { true }.prepare(checkpoint, snapshot)
        val ready = assertIs<AppSyncV2CheckpointPreparation.Ready>(prepared, prepared.toString())
        assertFailsWith<IllegalArgumentException> { AppSyncCheckpointEnvelopeCodec().encode(ready.payload) }
        val read = assertIs<AppSyncCheckpointValidation.Valid>(AppSyncCheckpointEnvelopeCodec().validate(ready.envelope)).envelope
        val restored = read.snapshot.favoriteUpdates.events.single()
        assertEquals(identity.syncId, restored.syncId)
        assertEquals(identity.sourceFingerprint, restored.sourceFingerprint)
        assertTrue(restored.sourceDiscriminator.startsWith(PORTABLE_LEGACY_EVENT_PREFIX))
        assertNull(restored.forumName)
        assertNull(restored.latestPostTitle)
        assertEquals(event.summary, restored.summary)
        assertTrue(AppSyncCanonicalSnapshotValidation.matches(checkpoint, read.snapshot))
    }

    @Test fun mismatchedSnapshotCannotBorrowCanonicalCoverageOrPublishDifferentValues() {
        val preparation = AppSyncSanitizedV2CheckpointPreparation { true }
        val checkpoint = checkpoint()
        assertEquals(AppSyncV2CheckpointFailure.Snapshot, assertIs<AppSyncV2CheckpointPreparation.NeedsAttention>(
            preparation.prepare(checkpoint, corpus.snapshot.copy(settings = emptyList()))).reason)
        val note = corpus.snapshot.notes.first()
        assertEquals(AppSyncV2CheckpointFailure.Snapshot, assertIs<AppSyncV2CheckpointPreparation.NeedsAttention>(
            preparation.prepare(checkpoint, corpus.snapshot.copy(notes = listOf(note.copy(content = "changed"))))).reason)
        assertEquals(AppSyncV2CheckpointFailure.Projection, assertIs<AppSyncV2CheckpointPreparation.NeedsAttention>(
            preparation.prepare(checkpoint.copy(coverage = emptyMap()), corpus.snapshot)).reason)
    }

    @Test fun preparationDefaultsOffAndRechecksCompatibilityAfterEncoding() {
        val checkpoint = checkpoint()
        assertEquals(AppSyncV2CheckpointFailure.Compatibility, assertIs<AppSyncV2CheckpointPreparation.NeedsAttention>(
            AppSyncSanitizedV2CheckpointPreparation().prepare(checkpoint, corpus.snapshot)).reason)
        var checks = 0
        assertEquals(AppSyncV2CheckpointFailure.Compatibility, assertIs<AppSyncV2CheckpointPreparation.NeedsAttention>(
            AppSyncSanitizedV2CheckpointPreparation { ++checks == 1 }.prepare(checkpoint, corpus.snapshot)).reason)
        assertEquals(2, checks)
    }
}

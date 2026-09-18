package me.thenano.yamibo.yamibo_app.repository.appsync

import kotlin.test.*
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.*
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.*
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.*
import me.thenano.yamibo.yamibo_app.repository.backup.CloudBackupPayloadCodec

class AppSyncLegacyCheckpointMigrationTest {
    private val corpus = AppSyncSyntheticCorpus.create()
    private val account = AppSyncSyntheticCorpus.account
    private val codec = AppSyncCheckpointEnvelopeCodec()
    private val migration = AppSyncLegacyCheckpointMigration()
    private fun proof(payload: AppSyncCheckpointPayload = corpus.checkpoint()): AppSyncVerifiedLegacyCheckpoint {
        val body = codec.encode(payload)
        val parsed = assertIs<AppSyncCheckpointValidation.Valid>(codec.validate(body)).envelope
        val index = AppSyncIndexEnvelopeCodec().encode(AppSyncIndexPayload(account,
            checkpoints = listOf(AppSyncIndexCheckpointReference(payload.checkpointId, 42, parsed.fingerprint)), updatedAtEpochMillis = 1))
        return assertNotNull(AppSyncVerifiedLegacyCheckpoint.verify(account.value, 42, index, body))
    }

    @Test fun verifiesLegacyIndexAndMigratesBothRepresentationsOfTheSyntheticCorpus() {
        val verified = proof()
        val migrated = assertIs<AppSyncLegacyCheckpointMigrationResult.Ready>(migration.prepare(verified))
        assertEquals(corpus.journal.observed.asStableMap(), migrated.checkpoint.coverage)
        assertEquals(19, migrated.checkpoint.entities.map { it.domainId }.distinct().size)
        assertEquals(verified.fingerprint, migrated.sourceFingerprint)
        assertEquals(verified.indexFingerprint, migrated.sourceIndexFingerprint)
        assertEquals(42L, verified.blogId)
        assertEquals(migrated, migration.prepare(verified))
    }

    @Test fun rejectsWrongIndexAccountPhysicalIdAndFingerprint() {
        val body = codec.encode(corpus.checkpoint())
        val parsed = assertIs<AppSyncCheckpointValidation.Valid>(codec.validate(body)).envelope
        fun index(binding: SyncAccountBinding = account, fingerprint: String = parsed.fingerprint) = AppSyncIndexEnvelopeCodec()
            .encode(AppSyncIndexPayload(binding, checkpoints = listOf(AppSyncIndexCheckpointReference(parsed.payload.checkpointId, 42, fingerprint)),
                updatedAtEpochMillis = 1))
        assertNull(AppSyncVerifiedLegacyCheckpoint.verify(account.value, 41, index(), body))
        assertNull(AppSyncVerifiedLegacyCheckpoint.verify(account.value, 42, index(SyncAccountBinding("other")), body))
        assertNull(AppSyncVerifiedLegacyCheckpoint.verify(account.value, 42, index(fingerprint = "0".repeat(64)), body))
        assertNull(AppSyncVerifiedLegacyCheckpoint.verify(account.value, 0, index(), body))
    }

    @Test fun refusesMissingResolvedDataAndSnapshotValueOrIdentityDisagreement() {
        val payload = corpus.checkpoint()
        val backup = CloudBackupPayloadCodec()
        val snapshot = backup.decode(payload.encodedSnapshot).getOrThrow()
        fun reason(candidate: AppSyncCheckpointPayload) = assertIs<AppSyncLegacyCheckpointMigrationResult.NeedsAttention>(
            migration.prepare(proof(candidate))).reason
        assertEquals(AppSyncLegacyCheckpointFailure.Snapshot, reason(payload.copy(
            resolvedEntities = payload.resolvedEntities.filterNot { it.key.domainId.value == "detail-note" })))
        assertEquals(AppSyncLegacyCheckpointFailure.Snapshot, reason(payload.copy(encodedSnapshot = backup.encode(
            snapshot.copy(notes = snapshot.notes.map { it.copy(content = "different snapshot note") })).getOrThrow())))
        assertEquals(AppSyncLegacyCheckpointFailure.Snapshot, reason(payload.copy(encodedSnapshot = backup.encode(
            snapshot.copy(notes = emptyList())).getOrThrow())))
        assertEquals(AppSyncLegacyCheckpointFailure.Snapshot, reason(payload.copy(encodedSnapshot = backup.encode(
            snapshot.copy(notes = snapshot.notes + snapshot.notes.first())).getOrThrow())))
    }

    @Test fun declaredTombstoneMustHaveMatchingResolvedProvenance() {
        val payload = corpus.checkpoint()
        val deleted = payload.resolvedEntities.first { it.tombstone != null }
        val invalid = payload.copy(tombstones = listOf(AppSyncCheckpointTombstone(deleted.key.domainId, deleted.key.entityId,
            deleted.key.generation, SyncOperationId("missing-delete"))))
        assertEquals(AppSyncLegacyCheckpointFailure.Projection,
            assertIs<AppSyncLegacyCheckpointMigrationResult.NeedsAttention>(migration.prepare(proof(invalid))).reason)
    }

    @Test fun proofReparsesTheOriginalBodyInsteadOfTrustingMutatedCallerCollections() {
        val verified = proof()
        val original = verified.read().payload.resolvedEntities.size
        (verified.read().payload.resolvedEntities as MutableList).clear()
        assertEquals(original, verified.read().payload.resolvedEntities.size)
        assertIs<AppSyncLegacyCheckpointMigrationResult.Ready>(migration.prepare(verified))
    }
}

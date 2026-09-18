package me.thenano.yamibo.yamibo_app.repository.appsync

import kotlin.test.*
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.*
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.*
import me.thenano.yamibo.yamibo_app.repository.appsync.schema.*

class AppSyncCanonicalProjectionImporterTest {
    private val corpus = AppSyncSyntheticCorpus.create()
    private val importer = AppSyncCanonicalProjectionImporter()
    private fun prepare(entities: List<ResolvedSyncEntity> = corpus.resolved,
        coverage: Map<String, Long> = corpus.journal.observed.asStableMap()) =
        importer.prepare(AppSyncSyntheticCorpus.account.value, "migrated", AppSyncSyntheticCorpus.TIMESTAMP, coverage, entities)

    @Test fun convertsTheSyntheticLegacyProjectionWithoutReplayingOrKeepingExcludedFields() {
        val result = assertIs<AppSyncProjectionImportResult.Ready>(prepare())
        assertEquals(corpus.journal.observed.asStableMap(), result.checkpoint.coverage)
        assertEquals(corpus.resolved.size, result.checkpoint.entities.size)
        assertEquals(19, result.checkpoint.entities.map { it.domainId }.distinct().size)
        assertTrue(result.excludedFields > 0)
        assertTrue(result.checkpoint.entities.all { entity ->
            entity.fields.keys.all { AppSyncCanonicalSchema.domainsById.getValue(entity.domainId).fieldsById.getValue(it).portable }
        })
        assertEquals(result, prepare(corpus.resolved.reversed()))
    }

    @Test fun fieldWinnersArePreservedWhenAnOlderPutContainsALosingValue() {
        val note = corpus.resolved.first { it.key.domainId.value == "detail-note" && it.tombstone == null }
        val old = note.fields.getValue("content").operation
        val seq = SyncSequence(corpus.journal.lastSequence + 1)
        val patch = old.copy(operationId = SyncOperation.idFor(old.deviceId, old.deviceEpoch, seq), sequence = seq,
            kind = SyncOperationKind.Patch, fields = mapOf("content" to "new user note"), causalContext = corpus.journal.observed)
        val updated = note.copy(fields = note.fields + ("content" to ResolvedSyncField("new user note", patch)))
        val result = assertIs<AppSyncProjectionImportResult.Ready>(prepare(listOf(updated), mapOf(old.replicaKey.stableKey to seq.value)))
        val projection = result.checkpoint.entities.single()
        val content = AppSyncCanonicalSchema.domains.getValue("detail-note").fields.getValue("content").id
        assertEquals(seq.value, projection.fields.getValue(content).sequence)
        assertEquals("new user note", projection.values().getValue(content).legacyValue())
        assertTrue(projection.fields.values.any { it.sequence == old.sequence.value })
    }

    @Test fun rejectsMismatchedValuesOwnersAndCoverageWithoutPartialProjection() {
        val entity = corpus.resolved.first { it.key.domainId.value == "detail-note" && it.tombstone == null }
        val field = entity.fields.getValue("content")
        fun reason(value: AppSyncProjectionImportResult) = assertIs<AppSyncProjectionImportResult.NeedsAttention>(value).reason
        assertEquals(AppSyncProjectionImportFailure.Provenance, reason(prepare(listOf(entity.copy(
            fields = entity.fields + ("content" to field.copy(value = "not the source value")))))))
        assertEquals(AppSyncProjectionImportFailure.Account, reason(prepare(listOf(entity.copy(
            fields = mapOf("content" to field.copy(operation = field.operation.copy(accountBinding = SyncAccountBinding("other")))))))))
        assertEquals(AppSyncProjectionImportFailure.Coverage, reason(prepare(listOf(entity), emptyMap())))
        assertEquals(AppSyncProjectionImportFailure.Provenance, reason(prepare(listOf(entity, entity))))
    }

    @Test fun removedRelationsAndTombstonesRemainRemovedWithoutStaleBodies() {
        val relation = corpus.resolved.first { it.relationOperation != null }
        val old = requireNotNull(relation.relationOperation)
        val seq = SyncSequence(corpus.journal.lastSequence + 1)
        val removal = old.copy(operationId = SyncOperation.idFor(old.deviceId, old.deviceEpoch, seq), sequence = seq,
            kind = SyncOperationKind.RelationRemove, causalContext = corpus.journal.observed)
        val removed = relation.copy(fields = removal.fields.mapValues { ResolvedSyncField(it.value, removal) },
            relationPresent = false, relationOperation = removal)
        val tombstone = corpus.resolved.first { it.tombstone != null }
        val result = assertIs<AppSyncProjectionImportResult.Ready>(prepare(listOf(removed, tombstone), mapOf(old.replicaKey.stableKey to seq.value)))
        val migrated = result.checkpoint.entities.first { it.relation != null }
        assertEquals(SyncOperationKind.RelationRemove, migrated.relation?.kind)
        assertTrue(migrated.fields.isEmpty())
        assertTrue(result.checkpoint.entities.single { it.tombstone != null }.fields.isEmpty())
    }

    @Test fun unknownSettingsAreExcludedButTheirAccountAndCoverageAreStillChecked() {
        val entity = corpus.resolved.first { it.key.domainId.value == "settings" }
        val key = entity.key.copy(entityId = SyncEntityId("future.secret"))
        val excluded = entity.copy(key = key, fields = entity.fields.mapValues { (_, field) ->
            field.copy(operation = field.operation.copy(entityId = key.entityId))
        })
        val result = assertIs<AppSyncProjectionImportResult.Ready>(prepare(listOf(excluded)))
        assertEquals(1, result.excludedEntities)
        assertTrue(result.checkpoint.entities.isEmpty())
        assertEquals(corpus.journal.observed.asStableMap(), result.checkpoint.coverage)
        assertIs<AppSyncProjectionImportResult.NeedsAttention>(prepare(listOf(excluded), emptyMap()))
    }
}

package me.thenano.yamibo.yamibo_app.repository.appsync

import kotlin.test.*
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.*
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.*
import me.thenano.yamibo.yamibo_app.repository.appsync.schema.*

class AppSyncSanitizedV2ProjectionExporterTest {
    private val corpus by lazy { AppSyncSyntheticCorpus.create() }
    private val exporter = AppSyncSanitizedV2ProjectionExporter()
    private fun canonical(entities: List<ResolvedSyncEntity> = corpus.resolved,
        coverage: Map<String, Long> = corpus.journal.observed.asStableMap()) =
        assertIs<AppSyncProjectionImportResult.Ready>(AppSyncCanonicalProjectionImporter().prepare(
            corpus.journal.accountBinding.value, "fallback", 100, coverage, entities)).checkpoint

    @Test fun everyDomainRoundTripsWithoutReplayingCompactedWinnersOrRestoringCaches() {
        val checkpoint = canonical()
        val result = assertIs<AppSyncV2ProjectionExport.Ready>(exporter.export(checkpoint))
        assertEquals(19, result.entities.map { it.key.domainId }.distinct().size)
        assertEquals(result, exporter.export(checkpoint.copy(entities = checkpoint.entities.reversed())))
        assertEquals(checkpoint, canonical(result.entities))
        result.entities.forEach { entity ->
            val domain = AppSyncCanonicalSchema.domains.getValue(entity.key.domainId.value)
            assertTrue(entity.fields.keys.all { domain.fields.getValue(it).portable })
            val operations = entity.fields.values.map { it.operation } + listOfNotNull(entity.relationOperation, entity.tombstone)
            operations.forEach { operation ->
                assertTrue(operation.fields.keys.none { domain.fields[it]?.classification in setOf(
                    AppSyncFieldClass.Cache, AppSyncFieldClass.ParentJoinable, AppSyncFieldClass.DeviceLocal) })
            }
        }
        assertTrue(result.entities.any { it.tombstone != null && it.fields.isEmpty() })
    }

    @Test fun olderPutAndNewerFieldWinnerKeepTheirIndependentOperationEvidence() {
        val entity = corpus.resolved.first { it.key.domainId.value == "detail-note" && it.tombstone == null }
        val old = entity.fields.getValue("content").operation
        val sequence = SyncSequence(corpus.journal.lastSequence + 1)
        val patch = old.copy(operationId = SyncOperation.idFor(old.deviceId, old.deviceEpoch, sequence), sequence = sequence,
            kind = SyncOperationKind.Patch, fields = mapOf("content" to "new note"), causalContext = corpus.journal.observed)
        val checkpoint = canonical(listOf(entity.copy(fields = entity.fields + ("content" to ResolvedSyncField("new note", patch)))),
            mapOf(old.replicaKey.stableKey to sequence.value))
        val result = assertIs<AppSyncV2ProjectionExport.Ready>(exporter.export(checkpoint)).entities.single()
        assertEquals(patch.operationId, result.fields.getValue("content").operation.operationId)
        assertEquals("new note", result.fields.getValue("content").value)
        assertTrue(result.fields.values.any { it.operation.operationId == old.operationId })
        assertEquals(checkpoint, canonical(listOf(result), checkpoint.coverage))
    }

    @Test fun portableEventRemovedRelationAndDeletionProofSurviveProjectionExport() {
        val event = corpus.journal.operations.first { it.domainId.value == "favorite.update-event" }
        val fields = event.fields
        val identity = me.thenano.yamibo.yamibo_app.repository.backup.favoriteUpdateEventIdentity(
            fields.getValue("targetType")!!, fields.getValue("targetId")!!.toLong(), fields.getValue("authorId")!!.toLong(),
            fields.getValue("mode")!!, emptyList(), true, fields.getValue("detectedAt")!!.toLong(),
            fields.getValue("summary")!!, fields.getValue("title")!!)
        val ambiguous = event.copy(entityId = SyncEntityId(identity.syncId), fields = fields + mapOf(
            "sourceDiscriminator" to identity.sourceDiscriminator, "sourceFingerprint" to identity.sourceFingerprint,
            "detailIds" to "", "ambiguous" to "true"))
        val eventProjection = ResolvedSyncEntity(SyncEntityKey(ambiguous.domainId, ambiguous.entityId, ambiguous.entityGeneration),
            fields = ambiguous.fields.mapValues { ResolvedSyncField(it.value, ambiguous) })
        val relation = corpus.resolved.first { it.relationOperation != null }
        val removal = requireNotNull(relation.relationOperation).copy(kind = SyncOperationKind.RelationRemove)
        val removed = relation.copy(fields = emptyMap(), relationPresent = false, relationOperation = removal)
        val tombstone = corpus.resolved.first { it.tombstone != null }
        val deletion = requireNotNull(tombstone.tombstone).copy(origin = SyncOperationOrigin.UserAction,
            bulkDeleteAuthorizationId = "delete-proof", fields = mapOf(AppSyncBulkDeleteProofFields.SCOPE to "selection",
                AppSyncBulkDeleteProofFields.COUNT to "1", AppSyncBulkDeleteProofFields.EXPIRES_AT to Long.MAX_VALUE.toString()))
        val checkpoint = canonical(listOf(eventProjection, removed, tombstone.copy(tombstone = deletion)))
        val result = assertIs<AppSyncV2ProjectionExport.Ready>(exporter.export(checkpoint)).entities
        assertEquals(checkpoint, canonical(result))
        val portable = result.single { it.key.domainId == event.domainId }
        assertEquals(identity.syncId, portable.key.entityId.value)
        assertTrue(portable.fields.getValue("sourceDiscriminator").value!!.startsWith(
            me.thenano.yamibo.yamibo_app.repository.backup.PORTABLE_LEGACY_EVENT_PREFIX))
        assertTrue(result.single { it.relationOperation != null }.fields.isEmpty())
        assertEquals(false, result.single { it.relationOperation != null }.relationPresent)
        assertEquals(deletion, result.single { it.tombstone != null }.tombstone)
        assertEquals(1, checkpoint.authorizations.size)
    }

    @Test fun invalidCoverageNeverExportsPartialProjectionOrPayloadDiagnostics() {
        val checkpoint = canonical().copy(coverage = emptyMap())
        val result = assertIs<AppSyncV2ProjectionExport.NeedsAttention>(exporter.export(checkpoint))
        assertEquals(AppSyncV2ProjectionExportFailure.InvalidCheckpoint, result.reason)
        assertFalse(result.toString().contains("Synthetic"))
    }
}

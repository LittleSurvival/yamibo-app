package me.thenano.yamibo.yamibo_app.repository.appsync

import kotlin.test.*
import kotlinx.serialization.json.Json
import me.thenano.yamibo.yamibo_app.repository.appsync.domain.SyncDomainRegistry
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.ResolvedSyncEntity
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.OperationReducer
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperationKind
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.*
import me.thenano.yamibo.yamibo_app.repository.appsync.schema.*

class AppSyncSyntheticCorpusTest {
    @Test
    fun editHistoryCoversEveryOperationKindAndReplaysToTheCheckpointState() {
        val corpus = AppSyncSyntheticCorpus.create()
        val operations = corpus.journal.operations
        assertEquals(SyncOperationKind.entries.toSet(), operations.map { it.kind }.toSet())
        val replayed = OperationReducer().reduce(operations = operations.reversed() + operations)
        assertTrue(replayed.quarantined.isEmpty())
        assertEquals(corpus.resolved.associateBy { it.key }, replayed.entities)
        val tombstone = corpus.resolved.single { it.tombstone != null }
        assertEquals("detail-note", tombstone.key.domainId.value)
        assertTrue(tombstone.fields.isEmpty())
        val patch = operations.single { it.kind == SyncOperationKind.Patch }
        val history = corpus.resolved.single { it.key.entityId == patch.entityId && it.key.domainId == patch.domainId }
        assertEquals(corpus.snapshot.readingState.threadHistory.first().page.toString(), history.fields.getValue("page").value)
        val relation = operations.first { it.kind == SyncOperationKind.RelationRemove }
        assertEquals(true, corpus.resolved.single {
            it.key.entityId == relation.entityId && it.key.domainId == relation.domainId
        }.relationPresent)
        operations.filter { it.kind == SyncOperationKind.Delete }
            .forEach { assertTrue(it.fields.isEmpty()) }
        val checkpointCodec = AppSyncCheckpointEnvelopeCodec()
        val validated = assertIs<AppSyncCheckpointValidation.Valid>(checkpointCodec.validate(
            checkpointCodec.encode(corpus.checkpoint())))
        assertEquals(corpus.checkpoint().resolvedEntities, validated.envelope.payload.resolvedEntities)
    }

    @Test
    fun repeatableBaselinesReportTransportCostWithoutInventingDeviceMemoryEvidence() {
        val corpus = AppSyncSyntheticCorpus.create()
        val first = AppSyncCorpusBaseline.measure(corpus)
        val second = AppSyncCorpusBaseline.measure(corpus)
        assertEquals(first.map { it.copy(encodeNanos = 0, decodeNanos = 0) },
            second.map { it.copy(encodeNanos = 0, decodeNanos = 0) })
        first.forEach {
            assertTrue(it.rawBytes > it.compressedBytes)
            assertEquals(((it.compressedBytes + 2) / 3) * 4, it.base64Chars)
            assertTrue(it.envelopeChars > it.base64Chars)
            assertNull(it.peakMemoryBytes)
            println("synthetic-v2-baseline $it")
        }
    }

    @Test
    fun largerCorpusProfilesHaveTheDeclaredScaleWithoutAnyUserExport() {
        listOf(AppSyncSyntheticCorpus.Size.Typical, AppSyncSyntheticCorpus.Size.Oversized).forEach { size ->
            val corpus = AppSyncSyntheticCorpus.create(size)
            assertEquals(size.favorites, corpus.snapshot.favorites.items.size)
            assertEquals(size.history, corpus.snapshot.readingState.threadHistory.size)
            assertEquals(size.noteBytes, corpus.snapshot.notes.single().content.encodeToByteArray().size)
            assertEquals(SyncDomainRegistry.REQUIRED_DOMAIN_IDS, corpus.journal.operations.map { it.domainId }.toSet())
            assertEquals(corpus.journal.operations.size.toLong(), corpus.journal.lastSequence)
        }
    }

    @Test
    fun smallCorpusIsDeterministicAndCoversEveryDomainAndScalarKind() {
        val first = AppSyncSyntheticCorpus.create()
        val second = AppSyncSyntheticCorpus.create()
        assertEquals(first, second)
        assertEquals(SyncDomainRegistry.REQUIRED_DOMAIN_IDS, first.journal.operations.map { it.domainId }.toSet())
        assertTrue(first.snapshot.notes.single().content.encodeToByteArray().size == 256)
        first.journal.operations.forEach { operation ->
            assertIs<AppSyncCanonicalFieldsResult.Accepted>(AppSyncCanonicalNormalizer.normalize(
                operation.domainId.value, operation.entityId.value, operation.fields), operation.domainId.value)
        }
        val codec = AppSyncJournalEnvelopeCodec()
        assertEquals(codec.encode(first.journal), codec.encode(second.journal))
        assertIs<AppSyncJournalValidation.Valid>(codec.validate(codec.encode(first.journal)))
        val checkpointCodec = AppSyncCheckpointEnvelopeCodec()
        val checkpoint = first.checkpoint()
        assertEquals(checkpointCodec.encode(checkpoint), checkpointCodec.encode(second.checkpoint()))
        assertIs<AppSyncCheckpointValidation.Valid>(checkpointCodec.validate(checkpointCodec.encode(checkpoint)))
    }

    @Test
    fun corpusExposesLegacyCheckpointDuplicationWithoutIncludingCovers() {
        val corpus = AppSyncSyntheticCorpus.create()
        val payload = corpus.checkpoint()
        val encodedResolved = Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(
            ResolvedSyncEntity.serializer()), payload.resolvedEntities)
        val winningCopies = payload.resolvedEntities.flatMap { it.fields.values }.map { it.operation }
        assertTrue(winningCopies.size > winningCopies.map { it.operationId }.toSet().size * 3)
        assertTrue(encodedResolved.contains("targetId"))
        assertTrue(encodedResolved.contains("subscriptionTitle"))
        assertFalse(encodedResolved.contains("example.test"))
        assertTrue(corpus.snapshot.favorites.items.any { it.coverUrl != null })
    }
}

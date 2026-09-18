package me.thenano.yamibo.yamibo_app.repository.appsync

import kotlin.test.*
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.*
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.*
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.*
import me.thenano.yamibo.yamibo_app.repository.appsync.schema.*
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.toByteString

class AppSyncCanonicalCheckpointCodecTest {
    private val codec = AppSyncCanonicalCheckpointCodec()
    private val winner = AppSyncCanonicalOperation("device", "epoch", 1, 14, "2026-09-18", 1,
        SyncOperationKind.Put, 10, SyncOperationOrigin.UserAction,
        fields = mapOf(54 to AppSyncCanonicalValue.Integer(50), 8 to AppSyncCanonicalValue.Integer(10)))
    private fun entity(operation: AppSyncCanonicalOperation = winner) = AppSyncCanonicalProjection(operation.domainId,
        operation.entityId, operation.generation, operation.fields.mapValues { operation })
    private fun checkpoint(entities: List<AppSyncCanonicalProjection> = listOf(entity())) =
        AppSyncCanonicalCheckpoint("checkpoint", "account", 20, mapOf("device:epoch" to 10), entities)

    @Test
    fun fixedEmptyRootHasNoNestedCompressionOrSnapshotEncoding() {
        val empty = AppSyncCanonicalCheckpoint("c", "a", 0, emptyMap(), emptyList())
        val expected = "5943503301016300000b594f42330200010161000000".decodeHex()
        assertEquals(expected, codec.encode(empty))
        assertEquals(empty, codec.decode("a", "c", expected))
    }

    @Test
    fun winningOperationIsStoredOnceAndMapOrderDoesNotAffectEncoding() {
        val source = checkpoint()
        val bytes = codec.encode(source)
        val decoded = codec.decode("account", "checkpoint", bytes)
        assertEquals(source, decoded)
        assertSame(decoded.entities.single().fields[8], decoded.entities.single().fields[54])
        assertEquals(winner.fields, decoded.entities.single().values())
        assertEquals(1, Regex("2026-09-18").findAll(bytes.utf8()).count())
        val reversed = source.copy(entities = listOf(entity().copy(fields = entity().fields.entries.reversed().associate { it.toPair() })))
        assertEquals(bytes, codec.encode(reversed))
    }

    @Test
    fun representativeCheckpointCoversAllDomainsWithOneCopyPerWinningOperation() {
        val corpus = AppSyncSyntheticCorpus.create()
        val cache = mutableMapOf<SyncOperationId, AppSyncCanonicalOperation>()
        fun canonical(op: SyncOperation): AppSyncCanonicalOperation = cache.getOrPut(op.operationId) {
            val domain = AppSyncCanonicalSchema.domains.getValue(op.domainId.value)
            val fields = if (op.kind in setOf(SyncOperationKind.Delete, SyncOperationKind.RelationRemove)) emptyMap() else
                assertIs<AppSyncCanonicalFieldsResult.Accepted>(AppSyncCanonicalNormalizer.normalize(
                    domain.name, op.entityId.value, op.fields)).fields
            AppSyncCanonicalOperation(op.deviceId.value, op.deviceEpoch.value, op.sequence.value, domain.id, op.entityId.value,
                op.entityGeneration, op.kind, op.createdAtEpochMillis, op.origin, op.bulkDeleteAuthorizationId,
                op.causalContext.asStableMap(), fields)
        }
        val entities = corpus.resolved.map { entity ->
            val domain = AppSyncCanonicalSchema.domains.getValue(entity.key.domainId.value)
            AppSyncCanonicalProjection(domain.id, entity.key.entityId.value, entity.key.generation,
                entity.fields.filter { (name, value) -> domain.fields[name]?.let { it.portable && it.id in canonical(value.operation).fields } == true }.entries.associate { (field, value) ->
                    domain.fields.getValue(field).id to canonical(value.operation)
                }, entity.relationOperation?.let(::canonical), entity.tombstone?.let(::canonical))
        }.sortedWith(compareBy({ it.domainId }, { it.entityId }, { it.generation }))
        assertEquals(AppSyncCanonicalSchema.domainsById.keys, entities.map { it.domainId }.toSet())
        val source = AppSyncCanonicalCheckpoint("corpus", corpus.journal.accountBinding.value, 1,
            corpus.journal.observed.asStableMap(), entities)
        val bytes = codec.encode(source)
        val decoded = codec.decode(source.accountBinding, source.checkpointId, bytes)
        assertEquals(source, decoded)
        assertEquals(entities.map { it.values() }, decoded.entities.map { it.values() })
        assertEquals(bytes, codec.encode(source.copy(entities = entities.reversed())))
        println("synthetic-v3-canonical-checkpoint rawBytes=${bytes.size} uniqueOperations=${cache.size} fieldReferences=${entities.sumOf { it.fields.size }}")
    }

    @Test
    fun roundTripPreservesSubsequentMonotonicAndConcurrentReduction() {
        fun legacy(op: AppSyncCanonicalOperation): SyncOperation {
            val device = SyncDeviceId(op.deviceId); val epoch = SyncDeviceEpoch(op.deviceEpoch); val sequence = SyncSequence(op.sequence)
            val schema = AppSyncCanonicalSchema.domainsById.getValue(op.domainId)
            return SyncOperation(SyncOperation.idFor(device, epoch, sequence), device, epoch, sequence, SyncAccountBinding("account"),
                SyncDomainId(schema.name), SyncEntityId(op.entityId), op.generation, op.kind,
                op.fields.mapKeys { schema.fieldsById.getValue(it.key).name }.mapValues { it.value.legacyValue() } +
                    AppSyncCanonicalEntityKeys.parse(op.domainId, op.entityId).derivedFields(),
                SyncCausalContext(op.causalContext), op.createdAtEpochMillis, op.origin, op.authorizationId)
        }
        fun base(value: AppSyncCanonicalCheckpoint): Map<SyncEntityKey, ResolvedSyncEntity> = value.entities.associate { projection ->
            val schema = AppSyncCanonicalSchema.domainsById.getValue(projection.domainId)
            val key = SyncEntityKey(SyncDomainId(schema.name), SyncEntityId(projection.entityId), projection.generation)
            key to ResolvedSyncEntity(key, projection.fields.mapKeys { schema.fieldsById.getValue(it.key).name }
                .mapValues { (field, operation) -> ResolvedSyncField(legacy(operation).fields[field], legacy(operation)) })
        }
        val source = checkpoint()
        val decoded = codec.decode("account", "checkpoint", codec.encode(source))
        val concurrent = legacy(winner.copy(deviceId = "other-device", fields = mapOf(54 to AppSyncCanonicalValue.Integer(30))))
        val reducer = OperationReducer()
        val before = reducer.reduce(base(source), listOf(concurrent))
        val after = reducer.reduce(base(decoded), listOf(concurrent))
        assertEquals(before, after)
        assertTrue(after.quarantined.isEmpty())
        assertEquals("50", after.entities.values.single().fields["durationMillis"]?.value)
        val observed = concurrent.copy(sequence = SyncSequence(2),
            operationId = SyncOperation.idFor(concurrent.deviceId, concurrent.deviceEpoch, SyncSequence(2)),
            causalContext = SyncCausalContext(mapOf("device:epoch" to 1, "other-device:epoch" to 1)))
        assertEquals(reducer.reduce(before.entities, listOf(observed)), reducer.reduce(after.entities, listOf(observed)))
    }

    @Test
    fun tombstonesRelationsAndSharedProofsPreserveTheirProvenance() {
        val delete = winner.copy(sequence = 2, kind = SyncOperationKind.Delete, fields = emptyMap(), authorizationId = "proof")
        val add = winner.copy(sequence = 3, domainId = 15, entityId = "ThreadNormal|1|0|category", kind = SyncOperationKind.RelationAdd,
            fields = mapOf(7 to AppSyncCanonicalValue.Integer(10)))
        val remove = add.copy(sequence = 4, kind = SyncOperationKind.RelationRemove, fields = emptyMap())
        val source = checkpoint(listOf(
            AppSyncCanonicalProjection(14, winner.entityId, 1, tombstone = delete),
            AppSyncCanonicalProjection(15, add.entityId, 1, mapOf(7 to add), relation = remove),
        )).copy(authorizations = listOf(AppSyncCanonicalDeleteProof("proof", 14, "scope", 1, 30)))
        assertEquals(source, codec.decode("account", "checkpoint", codec.encode(source)))
        val envelope = AppSyncV3EnvelopeCodec()
        val text = envelope.encode(AppSyncV3PayloadKind.Checkpoint, "account", "checkpoint", codec.encode(source))
        val verified = assertIs<AppSyncV3EnvelopeRead.VerifiedBytes>(envelope.decode(text, "account", AppSyncV3PayloadKind.Checkpoint, "checkpoint"))
        assertEquals(source, codec.decode("account", "checkpoint", verified.bytes))
    }

    @Test
    fun danglingForeignConflictingAndUncoveredProvenanceCannotBeEncoded() {
        val valid = checkpoint()
        val projection = entity()
        listOf(valid.copy(coverage = emptyMap()), valid.copy(entities = listOf(projection, projection)),
            checkpoint(listOf(projection.copy(fields = mapOf(54 to winner.copy(entityId = "other"))))),
            checkpoint(listOf(projection.copy(fields = mapOf(54 to winner, 8 to winner.copy(createdAtEpochMillis = 99))))),
            checkpoint(listOf(projection.copy(fields = mapOf(27 to winner)))),
            checkpoint(listOf(projection.copy(fields = emptyMap()))),
            checkpoint(listOf(projection.copy(tombstone = winner))),
            checkpoint(listOf(projection.copy(relation = winner)))).forEach { assertFails { codec.encode(it) } }
    }

    @Test
    fun corruptionAndAllocationClaimsAreRejectedWithoutPartialResults() {
        val bytes = codec.encode(checkpoint())
        for (length in 0 until bytes.size) assertFails { codec.decode("account", "checkpoint", bytes.substring(0, length)) }
        val corrupt = bytes.toByteArray().also { it[it.lastIndex] = 127 }
        val failure = assertFailsWith<IllegalArgumentException> { codec.decode("account", "checkpoint", corrupt.toByteString()) }
        assertEquals("Invalid canonical checkpoint", failure.message)
        assertNull(failure.cause)
        assertFails { codec.decode("different-account", "checkpoint", bytes) }
        assertFails { codec.decode("account", "different-checkpoint", bytes) }
        assertFails { codec.decode("account", "checkpoint", (bytes.toByteArray() + 0).toByteString()) }
        assertFails { AppSyncCanonicalCheckpointCodec(maximumBytes = bytes.size - 1).decode("account", "checkpoint", bytes) }
        assertFails { AppSyncCanonicalCheckpointCodec(maximumReferences = 1).decode("account", "checkpoint", bytes) }
        assertFails { AppSyncCanonicalCheckpointCodec(maximumReferences = 1).encode(checkpoint()) }
        assertEquals(checkpoint(), AppSyncCanonicalCheckpointCodec(maximumBytes = bytes.size).decode("account", "checkpoint", bytes))
    }
}

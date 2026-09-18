package me.thenano.yamibo.yamibo_app.repository.appsync

import kotlin.test.*
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.*
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.*
import me.thenano.yamibo.yamibo_app.repository.appsync.schema.*

class AppSyncCanonicalReductionTest {
    private val corpus by lazy { AppSyncSyntheticCorpus.create() }
    private val account get() = corpus.journal.accountBinding.value
    private val reducer = OperationReducer()
    private fun imported(op: SyncOperation) = assertIs<AppSyncCanonicalOperationImport.Accepted>(
        AppSyncCanonicalOperationImporter().import(account, op)).operation
    private fun empty() = AppSyncCanonicalCheckpoint("base", account, 1, emptyMap(), emptyList())
    private fun block(vararg ops: AppSyncCanonicalOperation) = AppSyncCanonicalOperationBlock(account, ops.toList())
    private fun source(domain: String) = imported(corpus.journal.operations.first { it.domainId.value == domain })

    @Test fun allDomainsMatchLegacyWinnersWithoutReintroducingDerivedFields() {
        val ops = corpus.journal.operations.map(::imported)
        val result = reducer.reduceCanonical(empty(), AppSyncCanonicalOperationBlock(account, ops))
        assertTrue(result.quarantined.isEmpty())
        assertEquals(corpus.journal.operations.size, result.appliedOperations.size)
        assertEquals(AppSyncCanonicalSchema.domainsById.keys, result.entities.map { it.domainId }.toSet())
        val expected = corpus.resolved.associateBy { it.key.domainId.value to it.key.entityId.value }
        result.entities.forEach { entity ->
            val schema = AppSyncCanonicalSchema.domainsById.getValue(entity.domainId)
            val legacy = expected.getValue(schema.name to entity.entityId)
            val fields = legacy.fields.filter { (name, value) -> schema.fields[name]?.let {
                it.portable && it.id in imported(value.operation).fields } == true }
            assertEquals(fields.keys, entity.fields.keys.map { schema.fieldsById.getValue(it).name }.toSet())
            entity.fields.forEach { (id, winner) ->
                assertEquals(imported(fields.getValue(schema.fieldsById.getValue(id).name).operation), winner)
            }
            assertEquals(legacy.tombstone?.let(::imported), entity.tombstone)
            assertEquals(legacy.relationOperation?.let(::imported), entity.relation)
        }
        val checkpoint = empty().copy(coverage = corpus.journal.observed.asStableMap(), entities = result.entities)
        val codec = AppSyncCanonicalCheckpointCodec()
        assertEquals(checkpoint, codec.decode(account, "base", codec.encode(checkpoint)))
        assertEquals(result.entities, reducer.reduceCanonical(checkpoint, AppSyncCanonicalOperationBlock(account, ops)).entities)
    }

    @Test fun concurrentProgressAndGenerationRulesRemainShared() {
        val put = source("reading.time").copy(deviceId = "a", sequence = 1, causalContext = emptyMap())
        val concurrent = put.copy(deviceId = "b", fields = put.fields + (54 to AppSyncCanonicalValue.Integer(1)))
        val result = reducer.reduceCanonical(empty(), block(put, concurrent))
        assertEquals(put.fields.getValue(54), result.entities.single().values().getValue(54))
        assertTrue(result.conflicts.isNotEmpty())
        val newerPatch = put.copy(deviceId = "c", generation = put.generation + 1, kind = SyncOperationKind.Patch,
            fields = mapOf(54 to AppSyncCanonicalValue.Integer(5)))
        val invalid = reducer.reduceCanonical(empty(), block(put, newerPatch))
        assertEquals(listOf(newerPatch), invalid.quarantined.map { it.operation })
        val recreated = newerPatch.copy(kind = SyncOperationKind.Put, fields = put.fields)
        assertEquals(recreated.generation, reducer.reduceCanonical(empty(), block(put, recreated)).entities.single().generation)
    }

    @Test fun minimalPatchValidatesOnlyPresentValuesButRejectsIllegalEventMutation() {
        val rss = source("rss.search-subscription").copy(deviceId = "a", sequence = 1, causalContext = emptyMap())
        val patch = rss.copy(sequence = 2, kind = SyncOperationKind.Patch,
            causalContext = mapOf("${rss.deviceId}:${rss.deviceEpoch}" to 1), fields = mapOf(18 to AppSyncCanonicalValue.Boolean(false)))
        val result = reducer.reduceCanonical(empty(), block(rss, patch))
        assertEquals(AppSyncCanonicalValue.Boolean(false), result.entities.single().values()[18])
        assertEquals(setOf(18), result.appliedOperations.last().fields.keys)
        val history = source("reading.rss-search")
        val historyPatch = history.copy(kind = SyncOperationKind.Patch, fields = mapOf(52 to AppSyncCanonicalValue.Integer(2)))
        assertEquals(setOf(52), reducer.reduceCanonical(empty(), block(historyPatch)).appliedOperations.single().fields.keys)
        val event = source("favorite.update-event")
        assertFailsWith<IllegalArgumentException> {
            reducer.reduceCanonical(empty(), block(event.copy(kind = SyncOperationKind.Patch, fields = mapOf(6 to AppSyncCanonicalValue.Text("changed")))))
        }
        val lifecycle = event.copy(kind = SyncOperationKind.Patch, fields = mapOf(65 to AppSyncCanonicalValue.Integer(100)))
        assertTrue(reducer.reduceCanonical(empty(), block(lifecycle)).quarantined.isEmpty())
    }

    @Test fun identityCollisionAndWrongAccountRejectTheWholeMerge() {
        val put = source("reading.time")
        val state = reducer.reduceCanonical(empty(), block(put))
        val checkpoint = empty().copy(coverage = mapOf("${put.deviceId}:${put.deviceEpoch}" to put.sequence), entities = state.entities)
        assertFailsWith<IllegalArgumentException> {
            reducer.reduceCanonical(checkpoint, block(put.copy(createdAtEpochMillis = put.createdAtEpochMillis + 1)))
        }
        assertFailsWith<IllegalArgumentException> { reducer.reduceCanonical(checkpoint, block(put).copy(accountBinding = "other")) }
        assertFailsWith<IllegalArgumentException> { reducer.reduceCanonical(empty(), block(put.copy(fields = emptyMap()))) }
    }

    @Test fun removeWinsAndBatchProofRemainCanonical() {
        val put = source("reading.time").copy(deviceId = "a", sequence = 1, causalContext = emptyMap())
        val delete = put.copy(deviceId = "b", kind = SyncOperationKind.Delete, fields = emptyMap(),
            origin = SyncOperationOrigin.UserAction, authorizationId = "proof")
        val proof = AppSyncCanonicalDeleteProof("proof", put.domainId, "scope", 1, delete.createdAtEpochMillis + 1)
        val result = reducer.reduceCanonical(empty(), block(put, delete).copy(authorizations = listOf(proof)))
        assertEquals(delete, result.entities.single().tombstone)
        assertTrue(result.entities.single().fields.isEmpty())
        assertEquals(listOf(proof), result.authorizations)
        assertTrue(result.appliedOperations.last().fields.isEmpty())
    }
}

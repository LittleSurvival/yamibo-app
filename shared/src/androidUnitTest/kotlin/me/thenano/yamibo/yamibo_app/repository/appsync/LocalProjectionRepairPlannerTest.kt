package me.thenano.yamibo.yamibo_app.repository.appsync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.LocalProjectionRepairPlanner
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.OperationReducer
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncAccountBinding
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncCausalContext
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncDeviceEpoch
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncDeviceId
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncDomainId
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncEntityId
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperation
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperationKind
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperationOrigin
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncSequence
import me.thenano.yamibo.yamibo_app.store.appsync.LocalSyncOperationDraft

class LocalProjectionRepairPlannerTest {
    private val planner = LocalProjectionRepairPlanner()

    @Test
    fun missingLocalFavoriteCreatesLiveRepairWithoutInferringDeletes() {
        val local = LocalSyncOperationDraft(
            domainId = SyncDomainId("favorite.item"),
            entityId = SyncEntityId("THREAD|42|0"),
            kind = SyncOperationKind.Put,
            fields = mapOf("targetType" to "THREAD", "targetId" to "42", "authorId" to "0"),
        )

        val repairs = planner.plan(listOf(local), emptyMap())

        assertEquals(listOf(local), repairs)
        assertTrue(planner.plan(emptyList(), emptyMap()).isEmpty())
    }

    @Test
    fun tombstonedFavoriteIsRecreatedInANewGeneration() {
        val keyDomain = SyncDomainId("favorite.item")
        val keyEntity = SyncEntityId("THREAD|42|0")
        val tombstone = operation(keyDomain, keyEntity, generation = 3, kind = SyncOperationKind.Delete)
        val state = OperationReducer().reduce(operations = listOf(tombstone)).entities
        val local = LocalSyncOperationDraft(
            domainId = keyDomain,
            entityId = keyEntity,
            kind = SyncOperationKind.Put,
            fields = mapOf("targetType" to "THREAD", "targetId" to "42", "authorId" to "0"),
        )

        val repair = planner.plan(listOf(local), state).single()

        assertEquals(4, repair.entityGeneration)
        assertEquals(SyncOperationKind.Put, repair.kind)
    }

    private fun operation(
        domain: SyncDomainId,
        entity: SyncEntityId,
        generation: Long,
        kind: SyncOperationKind,
    ) = SyncOperation(
        operationId = SyncOperation.idFor(SyncDeviceId("device"), SyncDeviceEpoch("epoch"), SyncSequence(1)),
        deviceId = SyncDeviceId("device"),
        deviceEpoch = SyncDeviceEpoch("epoch"),
        sequence = SyncSequence(1),
        accountBinding = SyncAccountBinding("account"),
        domainId = domain,
        entityId = entity,
        entityGeneration = generation,
        kind = kind,
        fields = emptyMap(),
        causalContext = SyncCausalContext(),
        createdAtEpochMillis = 1,
        origin = SyncOperationOrigin.UserAction,
    )
}
